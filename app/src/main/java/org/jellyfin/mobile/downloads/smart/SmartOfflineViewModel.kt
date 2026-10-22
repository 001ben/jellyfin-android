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

    /** Loading starts read-only subscriptions; it does not save, schedule, reconcile or contact a server. */
    fun load() = run(SmartOfflineAction.LOAD) {
        observation?.cancel()
        search?.cancel()
        searchRevision++
        val (server, user) = accountProvider?.invoke() ?: (preferences.currentServerId to preferences.currentUserId)
        val key = if (server != null && user != null) "$server-$user" else null
        if (key != accountKey) _state.update { SmartOfflineUiState(action = SmartOfflineAction.LOAD) }
        accountKey = key
        _state.update { it.copy(results = emptyList()) }
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
                source.observeWork(key)
            ) { account, series, work ->
                Triple(account, series, work.copy(lastReport = account.status))
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
    }

    private fun publish(value: Triple<SmartOfflineAccount, List<SmartOfflineSeries>, SmartOfflineStatus>) {
        if (value.first.key != accountKey) return
        _state.update { it.copy(account = value.first, series = value.second, status = value.third) }
    }

    fun save(account: SmartOfflineAccount) = run(SmartOfflineAction.SAVE) {
        if (account.key != accountKey) return@run
        val saved = withContext(Dispatchers.IO) { source.save(account) }
        feedback(saved)
    }

    private fun feedback(saved: SmartOfflineSaveResult) {
        if (saved.account.key != accountKey) return
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
        val account = _state.value.account ?: return
        search = run(SmartOfflineAction.SEARCH) {
            val results = withContext(Dispatchers.IO) { source.search(account, query) }
            if (revision == searchRevision && account.key == accountKey) _state.update { it.copy(results = results) }
        }
    }

    fun toggle(item: BaseItemDto, enabled: Boolean): Job {
        val clickedAccount = _state.value.account
        return run(SmartOfflineAction.TOGGLE) {
            val account = clickedAccount?.takeIf { it.key == accountKey } ?: return@run
            val saved = withContext(Dispatchers.IO) { source.toggle(account, item, enabled) }
            feedback(saved)
        }
    }

    override fun onCleared() {
        observation?.cancel()
        search?.cancel()
    }
}
