package org.jellyfin.mobile.downloads.smart

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

private data class ObservedSmartOfflineState(
    val account: SmartOfflineAccount,
    val series: List<SmartOfflineSeries>,
    val work: SmartOfflineStatus,
    val catalog: List<SmartOfflineCatalogItem>,
)

class SmartOfflineViewModel(
    uiSource: SmartOfflineUiSource? = null,
    private val accountProvider: (() -> Pair<Long?, Long?>)? = null,
    private val actionScope: CoroutineScope? = null,
) : ViewModel(), KoinComponent {
    private val manager by inject<SmartOfflineManager>()
    private val preferences by inject<AppPreferences>()
    private val context by inject<Context>()
    private val source by lazy { uiSource ?: ManagerSmartOfflineUiSource(manager, context) }
    private val scope: CoroutineScope get() = actionScope ?: viewModelScope
    private val actionMutex = Mutex()
    private var observation: Job? = null
    private var search: Job? = null
    private var accountKey: String? = null
    private var searchRevision = 0L
    private val _state = MutableStateFlow(SmartOfflineUiState())
    val state = _state.asStateFlow()

    private fun currentIdentity(): Pair<Long?, Long?> =
        accountProvider?.invoke() ?: (preferences.currentServerId to preferences.currentUserId)

    private fun isCurrent(key: String): Boolean {
        val (server, user) = currentIdentity()
        return key == accountKey && key == "$server-$user"
    }

    private fun run(action: SmartOfflineAction, task: suspend () -> Unit): Job = scope.launch {
        actionMutex.withLock {
            _state.update { it.copy(action = action, error = "", saveFeedback = null) }
            try {
                task()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(error = "Unable to update. Check settings and server access.") }
            } finally {
                _state.update { it.copy(action = SmartOfflineAction.NONE) }
            }
        }
    }

    /** Generic loading only observes local state; explicit contextual entry also fetches that show's metadata. */
    fun load(initialSeriesId: UUID? = null) = run(SmartOfflineAction.LOAD) {
        observation?.cancel()
        search?.cancel()
        searchRevision++
        val (server, user) = currentIdentity()
        val key = if (server != null && user != null) "$server-$user" else null
        if (key != accountKey) _state.update { SmartOfflineUiState(action = SmartOfflineAction.LOAD) }
        accountKey = key
        _state.update { it.copy(results = emptyList(), contextualSeries = null, artwork = emptyMap()) }
        if (server == null || user == null) {
            _state.update {
                it.copy(
                    account = null,
                    series = emptyList(),
                    results = emptyList(),
                    status = SmartOfflineStatus()
                )
            }
            return@run
        }
        val saved =
            combine(
                source.observeAccount(server, user),
                source.observeSeries(key!!),
                source.observeWork(key),
                source.observeCatalog(server, user),
            ) { account, series, work, catalog ->
                ObservedSmartOfflineState(account, series, work.copy(lastReport = account.status), catalog)
            }
        publish(saved.first())
        observation = scope.launch {
            try {
                saved.collect { publish(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(error = "Unable to observe background status. Reopen Smart offline to retry.") }
            }
        }
        if (initialSeriesId != null) loadContext(initialSeriesId)
    }

    private suspend fun publish(value: ObservedSmartOfflineState) {
        if (!isCurrent(value.account.key)) return
        _state.update {
            it.copy(account = value.account, series = value.series, status = value.work, catalog = value.catalog)
        }
        captureArtwork(value.account, value.series.mapNotNull { it.item }, allowNetwork = false)
    }

    private suspend fun loadContext(id: UUID) {
        val account = _state.value.account?.takeIf { isCurrent(it.key) } ?: return
        val item = withContext(Dispatchers.IO) { source.findSeries(account, id) }
        if (!isCurrent(account.key)) return
        _state.update {
            it.copy(
                contextualSeries = item,
                error = if (item == null) "This show is unavailable for this account." else ""
            )
        }
        if (item != null) captureArtwork(account, listOf(item), allowNetwork = true)
    }

    private suspend fun captureArtwork(account: SmartOfflineAccount, items: List<BaseItemDto>, allowNetwork: Boolean) {
        if (!isCurrent(account.key)) return
        val captured = withContext(Dispatchers.IO) { source.artwork(account, items, allowNetwork) }
        if (!isCurrent(account.key)) return
        _state.update { current ->
            val artwork = captured.mapValues { (id, image) ->
                current.artwork[id]?.takeIf {
                    it.cacheKey == image.cacheKey && (it.allowNetwork || !image.allowNetwork)
                } ?: image
            }
            current.copy(artwork = current.artwork + artwork)
        }
    }

    fun save(account: SmartOfflineAccount) = run(SmartOfflineAction.SAVE) {
        if (!isCurrent(account.key)) return@run
        val saved = withContext(Dispatchers.IO) { source.save(account) }
        feedback(saved)
    }

    private fun feedback(saved: SmartOfflineSaveResult) {
        if (!isCurrent(saved.account.key)) return
        _state.update {
            it.copy(
                saveFeedback = saved.feedback,
                error = if (saved.schedulingFailed) {
                    "Settings saved, but Android could not queue the check. Save again to retry."
                } else {
                    ""
                },
            )
        }
    }

    fun search(query: String) {
        search?.cancel()
        val revision = ++searchRevision
        val account = _state.value.account?.takeIf { isCurrent(it.key) } ?: return
        search = run(SmartOfflineAction.SEARCH) {
            if (!isCurrent(account.key)) return@run
            val results = withContext(Dispatchers.IO) { source.search(account, query) }
            if (revision == searchRevision && isCurrent(account.key)) {
                _state.update { it.copy(results = results) }
                captureArtwork(account, results, allowNetwork = true)
            }
        }
    }

    fun toggle(item: BaseItemDto, enabled: Boolean): Job {
        val clickedAccount = _state.value.account
        return run(SmartOfflineAction.TOGGLE) {
            val account = clickedAccount?.takeIf { isCurrent(it.key) } ?: return@run
            val saved = withContext(Dispatchers.IO) { source.toggle(account, item, enabled) }
            if (isCurrent(saved.account.key)) {
                _state.update {
                    it.copy(
                        seriesFeedback = SmartOfflineSeriesFeedback(item, enabled),
                        error = if (saved.schedulingFailed) {
                            "Selection saved, but the check could not be queued."
                        } else {
                            ""
                        },
                    )
                }
            }
        }
    }

    override fun onCleared() {
        observation?.cancel()
        search?.cancel()
    }
}
