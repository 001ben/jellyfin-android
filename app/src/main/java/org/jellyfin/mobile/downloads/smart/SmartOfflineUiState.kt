package org.jellyfin.mobile.downloads.smart

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

enum class SmartOfflineAction { NONE, LOAD, SAVE, SEARCH, TOGGLE }
enum class SmartOfflineSaveFeedback { SAVED, SAVED_AND_QUEUED, SAVED_AND_DISABLED }
enum class SmartOfflineWorkState { IDLE, QUEUED, RUNNING, RETRYING, SUCCEEDED, CANCELLED, FAILED }

data class SmartOfflineStatus(
    val workState: SmartOfflineWorkState = SmartOfflineWorkState.IDLE,
    val automaticScheduled: Boolean = false,
    val lastReport: String = "",
)

data class SmartOfflineUiState(
    val account: SmartOfflineAccount? = null,
    val series: List<SmartOfflineSeries> = emptyList(),
    val results: List<BaseItemDto> = emptyList(),
    val action: SmartOfflineAction = SmartOfflineAction.NONE,
    val error: String = "",
    val saveFeedback: SmartOfflineSaveFeedback? = null,
    val status: SmartOfflineStatus = SmartOfflineStatus(),
    val catalog: List<SmartOfflineCatalogItem> = emptyList(),
    val artwork: Map<UUID, SmartOfflineArtwork> = emptyMap(),
    val contextualSeries: BaseItemDto? = null,
    val seriesFeedback: SmartOfflineSeriesFeedback? = null,
) {
    val busy: Boolean get() = action != SmartOfflineAction.NONE
}

data class SmartOfflineSeriesFeedback(val item: BaseItemDto, val selected: Boolean)

data class SmartOfflineSaveResult(
    val account: SmartOfflineAccount,
    val feedback: SmartOfflineSaveFeedback,
    val schedulingFailed: Boolean = false,
)

/** Small immutable snapshots keep scheduler presentation testable without starting Android workers. */
data class SmartOfflineWorkSnapshot(
    val state: SmartOfflineWorkState,
    val attempt: Int = 0,
    val requestedAt: Long = 0,
)

internal const val SMART_OFFLINE_REQUEST_TAG = "smart-offline-requested-"

internal fun smartOfflineWorkStatus(
    immediate: List<SmartOfflineWorkSnapshot>,
    periodic: List<SmartOfflineWorkSnapshot>,
): SmartOfflineStatus {
    val activeStates = setOf(SmartOfflineWorkState.QUEUED, SmartOfflineWorkState.RUNNING)
    val scheduled = periodic.any { it.state in activeStates }
    val all = immediate + periodic
    val pending = immediate.filter { it.state == SmartOfflineWorkState.QUEUED }.maxByOrNull { it.requestedAt }
    val state = when {
        all.any { it.state == SmartOfflineWorkState.RUNNING } -> SmartOfflineWorkState.RUNNING
        pending != null && pending.attempt > 0 -> SmartOfflineWorkState.RETRYING
        pending != null -> SmartOfflineWorkState.QUEUED
        periodic.any { it.state == SmartOfflineWorkState.QUEUED && it.attempt > 0 } -> SmartOfflineWorkState.RETRYING
        else -> immediate.maxByOrNull { it.requestedAt }?.state ?: SmartOfflineWorkState.IDLE
    }
    return SmartOfflineStatus(state, scheduled)
}

/** These flows only inspect WorkManager's database. Collecting them never schedules or probes a server. */
internal fun observeSmartOfflineWork(context: Context, key: String): Flow<SmartOfflineStatus> {
    val work = WorkManager.getInstance(context)
    val name = SmartOfflineWorker.uniqueName(key)
    return combine(
        work.getWorkInfosForUniqueWorkFlow("$name-now"),
        work.getWorkInfosForUniqueWorkFlow(name)
    ) { now, periodic ->
        smartOfflineWorkStatus(now.map(WorkInfo::snapshot), periodic.map(WorkInfo::snapshot))
    }
}

private fun WorkInfo.snapshot(): SmartOfflineWorkSnapshot {
    val status = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> SmartOfflineWorkState.QUEUED
        WorkInfo.State.RUNNING -> SmartOfflineWorkState.RUNNING
        WorkInfo.State.SUCCEEDED -> SmartOfflineWorkState.SUCCEEDED
        WorkInfo.State.CANCELLED -> SmartOfflineWorkState.CANCELLED
        WorkInfo.State.FAILED -> SmartOfflineWorkState.FAILED
    }
    val requested = tags.firstOrNull { it.startsWith(SMART_OFFLINE_REQUEST_TAG) }
        ?.removePrefix(SMART_OFFLINE_REQUEST_TAG)?.toLongOrNull() ?: 0
    return SmartOfflineWorkSnapshot(status, runAttemptCount, requested)
}

interface SmartOfflineUiSource {
    fun observeAccount(server: Long, user: Long): Flow<SmartOfflineAccount>
    fun observeSeries(key: String): Flow<List<SmartOfflineSeries>>
    fun observeWork(key: String): Flow<SmartOfflineStatus>
    fun observeCatalog(server: Long, user: Long): Flow<List<SmartOfflineCatalogItem>> = flowOf(emptyList())
    suspend fun save(account: SmartOfflineAccount): SmartOfflineSaveResult
    suspend fun toggle(account: SmartOfflineAccount, item: BaseItemDto, enabled: Boolean): SmartOfflineSaveResult
    suspend fun search(account: SmartOfflineAccount, query: String): List<BaseItemDto>
    suspend fun findSeries(account: SmartOfflineAccount, id: UUID): BaseItemDto? = null
    suspend fun artwork(
        account: SmartOfflineAccount,
        items: List<BaseItemDto>,
        allowNetwork: Boolean,
    ): Map<UUID, SmartOfflineArtwork> = emptyMap()
}

internal class ManagerSmartOfflineUiSource(
    private val manager: SmartOfflineManager,
    private val context: Context,
) : SmartOfflineUiSource {
    override fun observeAccount(
        server: Long,
        user: Long
    ): Flow<SmartOfflineAccount> = manager.observeAccount(server, user)
    override fun observeSeries(key: String): Flow<List<SmartOfflineSeries>> = manager.observeSeries(key)
    override fun observeWork(key: String): Flow<SmartOfflineStatus> = observeSmartOfflineWork(context, key)
    override fun observeCatalog(server: Long, user: Long): Flow<List<SmartOfflineCatalogItem>> =
        manager.observeCatalog(server, user)
    override suspend fun save(account: SmartOfflineAccount): SmartOfflineSaveResult = manager.save(account)
    override suspend fun toggle(
        account: SmartOfflineAccount,
        item: BaseItemDto,
        enabled: Boolean
    ): SmartOfflineSaveResult =
        manager.subscribe(account, item, enabled)
    override suspend fun search(
        account: SmartOfflineAccount,
        query: String
    ): List<BaseItemDto> = manager.library.search(account, query)
    override suspend fun findSeries(account: SmartOfflineAccount, id: UUID): BaseItemDto? = manager.library.findSeries(
        account,
        id
    )
    override suspend fun artwork(
        account: SmartOfflineAccount,
        items: List<BaseItemDto>,
        allowNetwork: Boolean,
    ): Map<UUID, SmartOfflineArtwork> = manager.library.artwork(account, items, allowNetwork)
}
