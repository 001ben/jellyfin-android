package org.jellyfin.mobile.downloads.smart

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SmartOfflineUiStateTest {
    @Test
    fun `a waiting periodic schedule is not an immediate queued or running check`() {
        val status = smartOfflineWorkStatus(emptyList(), listOf(SmartOfflineWorkSnapshot(SmartOfflineWorkState.QUEUED)))
        assertEquals(SmartOfflineWorkState.IDLE, status.workState)
        assertTrue(status.automaticScheduled)
    }

    @Test
    fun `queued retries running and terminal states are distinct`() {
        val queued = SmartOfflineWorkSnapshot(SmartOfflineWorkState.QUEUED)
        assertEquals(SmartOfflineWorkState.QUEUED, smartOfflineWorkStatus(listOf(queued), emptyList()).workState)
        assertEquals(
            SmartOfflineWorkState.RETRYING,
            smartOfflineWorkStatus(listOf(queued.copy(attempt = 1)), emptyList()).workState
        )
        val running = SmartOfflineWorkSnapshot(SmartOfflineWorkState.RUNNING)
        assertEquals(SmartOfflineWorkState.RUNNING, smartOfflineWorkStatus(listOf(queued), listOf(running)).workState)
        for (state in listOf(
            SmartOfflineWorkState.SUCCEEDED,
            SmartOfflineWorkState.FAILED,
            SmartOfflineWorkState.CANCELLED
        )) {
            assertEquals(state, smartOfflineWorkStatus(listOf(SmartOfflineWorkSnapshot(state)), emptyList()).workState)
        }
    }

    @Test
    fun `latest explicitly requested terminal work wins over old history`() {
        val history = listOf(
            SmartOfflineWorkSnapshot(SmartOfflineWorkState.FAILED, requestedAt = 1),
            SmartOfflineWorkSnapshot(SmartOfflineWorkState.SUCCEEDED, requestedAt = 2),
        )
        assertEquals(SmartOfflineWorkState.SUCCEEDED, smartOfflineWorkStatus(history, emptyList()).workState)
    }

    @Test
    fun `opening observes saved worker reports and selected series without starting any action`() = runBlocking {
        val source = FakeUiSource()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = SmartOfflineViewModel(source, { 1L to 2L }, scope)
            model.load().join()
            assertEquals(0, source.writes)
            assertEquals(0, source.searches)
            assertEquals(0, source.contextRequests)
            assertFalse(model.state.value.busy)
            source.account.value = source.account.value.copy(status = "Waiting for verified LAN")
            source.work.value = SmartOfflineStatus(SmartOfflineWorkState.RUNNING, automaticScheduled = true)
            val selected = SmartOfflineSeries("series", "1-2", UUID.randomUUID().toString(), "Example")
            source.series.value = listOf(selected)
            val state = withTimeout(TEST_TIMEOUT) {
                model.state.first {
                    it.status.lastReport == "Waiting for verified LAN" &&
                        it.status.workState == SmartOfflineWorkState.RUNNING &&
                        it.series == listOf(selected)
                }
            }
            assertEquals(0, source.writes)
            assertEquals(0, source.searches)
            assertEquals(source.account.value, state.account)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `save feedback waits for scheduler acceptance and preserves live report updates`() = runBlocking {
        val source = FakeUiSource()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        source.saveGate = CompletableDeferred()
        try {
            val model = SmartOfflineViewModel(source, { 1L to 2L }, scope)
            model.load().join()
            val save = model.save(source.account.value.copy(nextCount = 7))
            source.saveStarted.await()
            assertEquals(SmartOfflineAction.SAVE, model.state.value.action)
            assertEquals(null, model.state.value.saveFeedback)
            source.account.value = source.account.value.copy(status = "Previous check completed")
            source.saveGate!!.complete(Unit)
            save.join()
            val state = withTimeout(TEST_TIMEOUT) { model.state.first { it.account?.nextCount == 7 } }
            assertEquals("Previous check completed", state.status.lastReport)
            assertEquals(SmartOfflineSaveFeedback.SAVED_AND_QUEUED, state.saveFeedback)
            assertFalse(state.busy)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scheduler failure keeps saved configuration and is not described as queued`() = runBlocking {
        val source = FakeUiSource().apply { schedulingFailed = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = SmartOfflineViewModel(source, { 1L to 2L }, scope)
            model.load().join()
            model.save(source.account.value.copy(nextCount = 8)).join()
            val state = withTimeout(TEST_TIMEOUT) { model.state.first { it.account?.nextCount == 8 } }
            assertEquals(SmartOfflineSaveFeedback.SAVED, state.saveFeedback)
            assertTrue(state.error.isNotBlank())
            assertEquals(SmartOfflineWorkState.IDLE, state.status.workState)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `queued series click cannot be applied to another account after reload`() = runBlocking {
        val source = FakeUiSource()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        source.searchGate = CompletableDeferred()
        var identity = 1L to 2L
        try {
            val model = SmartOfflineViewModel(source, { identity }, scope)
            model.load().join()
            model.search("Series")
            source.searchStarted.await()
            identity = 3L to 4L
            source.account.value = SmartOfflineAccount("3-4", 3, 4)
            val reload = model.load()
            val toggle = model.toggle(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SERIES), true)
            source.searchGate!!.complete(Unit)
            reload.join()
            toggle.join()
            assertEquals("3-4", model.state.value.account?.key)
            assertEquals(0, source.writes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `explicit context loads one show without selecting it and browse permits an empty query`() = runBlocking {
        val source = FakeUiSource()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val item = BaseItemDto(id = UUID.randomUUID(), name = "Context show", type = BaseItemKind.SERIES)
            source.contextItem = item
            val model = SmartOfflineViewModel(source, { 1L to 2L }, scope)
            model.load(item.id).join()
            assertEquals(item, model.state.value.contextualSeries)
            assertEquals(1, source.contextRequests)
            assertEquals(0, source.writes)
            assertTrue(model.state.value.series.isEmpty())
            model.search("")
            source.searchStarted.await()
            withTimeout(TEST_TIMEOUT) { model.state.first { !it.busy } }
            assertEquals("", source.lastQuery)
            assertEquals(1, source.searches)
            assertEquals(0, source.writes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `identity change blocks old settings selection and browsing before reload`() = runBlocking {
        val source = FakeUiSource()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var identity = 1L to 2L
        try {
            val model = SmartOfflineViewModel(source, { identity }, scope)
            model.load().join()
            identity = 3L to 4L
            model.save(source.account.value.copy(nextCount = 6)).join()
            model.toggle(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SERIES), true).join()
            model.search("Show")
            assertEquals(0, source.writes)
            assertEquals(0, source.searches)
        } finally {
            scope.cancel()
        }
    }

    private class FakeUiSource : SmartOfflineUiSource {
        val account = MutableStateFlow(SmartOfflineAccount("1-2", 1, 2, enabled = true))
        val series = MutableStateFlow<List<SmartOfflineSeries>>(emptyList())
        val work = MutableStateFlow(SmartOfflineStatus())
        val saveStarted = CompletableDeferred<Unit>()
        val searchStarted = CompletableDeferred<Unit>()
        var saveGate: CompletableDeferred<Unit>? = null
        var searchGate: CompletableDeferred<Unit>? = null
        var schedulingFailed = false
        var writes = 0
        var searches = 0
        var contextRequests = 0
        var contextItem: BaseItemDto? = null
        var lastQuery: String? = null

        override fun observeAccount(server: Long, user: Long): Flow<SmartOfflineAccount> = account
        override fun observeSeries(key: String): Flow<List<SmartOfflineSeries>> = series
        override fun observeWork(key: String): Flow<SmartOfflineStatus> = work

        override suspend fun save(account: SmartOfflineAccount): SmartOfflineSaveResult {
            writes++
            saveStarted.complete(Unit)
            saveGate?.await()
            this.account.value = account.copy(status = this.account.value.status)
            val feedback = if (schedulingFailed) {
                SmartOfflineSaveFeedback.SAVED
            } else {
                SmartOfflineSaveFeedback.SAVED_AND_QUEUED
            }
            return SmartOfflineSaveResult(this.account.value, feedback, schedulingFailed)
        }

        override suspend fun toggle(
            account: SmartOfflineAccount,
            item: BaseItemDto,
            enabled: Boolean,
        ): SmartOfflineSaveResult {
            writes++
            return SmartOfflineSaveResult(this.account.value, SmartOfflineSaveFeedback.SAVED)
        }

        override suspend fun search(account: SmartOfflineAccount, query: String): List<BaseItemDto> {
            searches++
            lastQuery = query
            searchStarted.complete(Unit)
            searchGate?.await()
            return emptyList()
        }

        override suspend fun findSeries(account: SmartOfflineAccount, id: UUID): BaseItemDto? {
            contextRequests++
            return contextItem?.takeIf { it.id == id }
        }
    }

    private companion object {
        const val TEST_TIMEOUT = 5000L
    }
}
