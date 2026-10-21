package org.jellyfin.mobile.downloads.smart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class SmartOfflineUiState(
    val account: SmartOfflineAccount? = null,
    val series: List<SmartOfflineSeries> = emptyList(),
    val results: List<BaseItemDto> = emptyList(),
    val busy: Boolean = false,
    val message: String = "",
)

class SmartOfflineViewModel : ViewModel(), KoinComponent {
    private val manager by inject<SmartOfflineManager>()
    private val preferences by inject<AppPreferences>()
    private val _state = MutableStateFlow(SmartOfflineUiState())
    val state = _state.asStateFlow()

    private fun run(action: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = "")
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.value = _state.value.copy(message = "Unable to update. Check settings and server access.")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun load() = run {
        val account = manager.current(preferences.currentServerId, preferences.currentUserId)
        _state.value = _state.value.copy(account = account, series = account?.let { manager.series(it) }.orEmpty())
    }

    fun save(account: SmartOfflineAccount) = run {
        manager.save(account)
        _state.value = _state.value.copy(
            account = account,
            message = "Saved; Android schedules downloads when constraints allow.",
        )
    }

    fun search(query: String) = run {
        val account = _state.value.account ?: return@run
        _state.value = _state.value.copy(results = manager.search(account, query))
    }

    fun toggle(item: BaseItemDto, enabled: Boolean) = run {
        val account = _state.value.account ?: return@run
        manager.subscribe(account, item, enabled)
        _state.value = _state.value.copy(series = manager.series(account))
    }
}
