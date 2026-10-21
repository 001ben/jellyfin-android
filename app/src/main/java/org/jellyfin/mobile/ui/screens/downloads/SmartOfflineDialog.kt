package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Checkbox
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.smart.SmartOfflineAccount
import org.jellyfin.mobile.downloads.smart.SmartOfflinePolicy
import org.jellyfin.mobile.downloads.smart.SmartOfflineSeries
import org.jellyfin.mobile.downloads.smart.SmartOfflineUiState
import org.jellyfin.mobile.downloads.smart.SmartOfflineViewModel
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

@Composable
fun SmartOfflineActions(visible: Boolean, content: @Composable () -> Unit) {
    var show by remember { mutableStateOf(false) }
    if (show) SmartOfflineDialog(onDismiss = { show = false })
    Row {
        if (visible) {
            TextButton(onClick = { show = true }) { Text(stringResource(R.string.smart_offline_title)) }
        }
        content()
    }
}

private const val DIALOG_MAX_HEIGHT = 520

/** Unsaved text remains editable even while it cannot yet be parsed into valid settings. */
private class SmartOfflineDraft(account: SmartOfflineAccount) {
    var settings by mutableStateOf(account)
    var count by mutableStateOf(account.nextCount.toString())
    var cap by mutableStateOf(account.capGiB.toString())

    val validCount: Boolean
        get() = count.toIntOrNull() in SmartOfflinePolicy.MIN_EPISODES..SmartOfflinePolicy.MAX_EPISODES
    val validCap: Boolean get() = cap.toIntOrNull() in SmartOfflinePolicy.MIN_CAP_GIB..SmartOfflinePolicy.MAX_CAP_GIB
    val canSave: Boolean get() = validCount && validCap && validLanUrl()

    private fun validLanUrl(): Boolean = settings.lanUrl.isBlank() ||
        runCatching { SmartOfflinePolicy.lanUrl(settings.lanUrl) }.isSuccess

    fun savedSettings(): SmartOfflineAccount? = if (canSave) {
        settings.copy(nextCount = count.toInt(), capGiB = cap.toInt())
    } else {
        null
    }
}

@Composable
fun SmartOfflineDialog(onDismiss: () -> Unit, model: SmartOfflineViewModel = viewModel()) {
    val state by model.state.collectAsState()
    LaunchedEffect(Unit) { model.load() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.smart_offline_title)) },
        text = { SmartOfflineContent(state, model) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.smart_offline_close)) }
        },
    )
}

@Composable
private fun SmartOfflineContent(state: SmartOfflineUiState, model: SmartOfflineViewModel) {
    Column(Modifier.heightIn(max = DIALOG_MAX_HEIGHT.dp).verticalScroll(rememberScrollState())) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val account = state.account
        if (account == null) {
            Text(stringResource(R.string.smart_offline_sign_in))
        } else {
            val draft = remember(account) { SmartOfflineDraft(account) }
            SmartOfflineDownloadOptions(draft, enabled = !state.busy)
            SmartOfflineNetworkOptions(draft, enabled = !state.busy)
            if (account.status.isNotBlank()) Text(account.status)
            TextButton(enabled = draft.canSave && !state.busy, onClick = {
                draft.savedSettings()?.let(model::save)
            }) { Text(stringResource(R.string.smart_offline_save)) }
            SmartOfflineSeriesChooser(state, model)
        }
        if (state.message.isNotBlank()) Text(state.message)
    }
}

@Composable
private fun SmartOfflineDownloadOptions(draft: SmartOfflineDraft, enabled: Boolean) {
    val settings = draft.settings
    Text(stringResource(R.string.smart_offline_description))
    SmartCheck(stringResource(R.string.smart_offline_enabled), settings.enabled, enabled) {
        draft.settings = settings.copy(enabled = it)
    }
    OutlinedTextField(
        draft.count,
        { draft.count = it },
        label = { Text(stringResource(R.string.smart_offline_count)) },
        singleLine = true,
        isError = !draft.validCount,
    )
    OutlinedTextField(
        draft.cap,
        { draft.cap = it },
        label = { Text(stringResource(R.string.smart_offline_cap)) },
        singleLine = true,
        isError = !draft.validCap,
    )
    SmartCheck(stringResource(R.string.smart_offline_wifi), settings.wifiOnly, enabled) {
        draft.settings = settings.copy(wifiOnly = it)
    }
    SmartCheck(stringResource(R.string.smart_offline_cleanup), settings.cleanup, enabled) {
        draft.settings = settings.copy(cleanup = it)
    }
    Text(stringResource(R.string.smart_offline_cleanup_help))
}

@Composable
private fun SmartOfflineNetworkOptions(draft: SmartOfflineDraft, enabled: Boolean) {
    val settings = draft.settings
    OutlinedTextField(
        settings.lanUrl,
        { draft.settings = settings.copy(lanUrl = it.trim()) },
        label = { Text(stringResource(R.string.smart_offline_lan)) },
        placeholder = { Text("https://jellyfin.home.example") },
        singleLine = true,
    )
    Text(stringResource(R.string.smart_offline_lan_help))
    SmartCheck(stringResource(R.string.smart_offline_remote), settings.remoteFallback, enabled) {
        draft.settings = settings.copy(remoteFallback = it)
    }
    Text(stringResource(R.string.smart_offline_remote_help))
}

@Composable
private fun SmartOfflineSeriesChooser(state: SmartOfflineUiState, model: SmartOfflineViewModel) {
    var query by remember { mutableStateOf("") }
    Text(stringResource(R.string.smart_offline_series_help))
    for (series in state.series) {
        SmartCheck(series.name, true, !state.busy) { model.toggle(series.asItem(), false) }
    }
    OutlinedTextField(
        query,
        { query = it },
        label = { Text(stringResource(R.string.smart_offline_search)) },
        singleLine = true,
    )
    TextButton(enabled = !state.busy, onClick = { model.search(query) }) {
        Text(stringResource(R.string.smart_offline_search))
    }
    val selectedIds = state.series.map { it.seriesId }.toSet()
    for (item in state.results.filterNot { it.id.toString() in selectedIds }) {
        SmartCheck(item.name.orEmpty(), false, !state.busy) { model.toggle(item, true) }
    }
}

private fun SmartOfflineSeries.asItem() = BaseItemDto(
    id = UUID.fromString(seriesId),
    name = name,
    type = BaseItemKind.SERIES,
)

@Composable
private fun SmartCheck(label: String, checked: Boolean, enabled: Boolean, changed: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Checkbox(checked = checked, onCheckedChange = changed, enabled = enabled)
        Text(label)
    }
}
