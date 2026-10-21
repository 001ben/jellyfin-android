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
import org.jellyfin.mobile.downloads.smart.SmartOfflinePolicy
import org.jellyfin.mobile.downloads.smart.SmartOfflineViewModel
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

@Composable
fun SmartOfflineDialog(onDismiss: () -> Unit, model: SmartOfflineViewModel = viewModel()) {
    val state by model.state.collectAsState()
    LaunchedEffect(Unit) { model.load() }
    val account = state.account
    var draft by remember(account) { mutableStateOf(account) }
    var count by remember(account) { mutableStateOf(account?.nextCount?.toString().orEmpty()) }
    var cap by remember(account) { mutableStateOf(account?.capGiB?.toString().orEmpty()) }
    var query by remember { mutableStateOf("") }
    val nextCount = count.toIntOrNull()
    val capGiB = cap.toIntOrNull()
    val valid = nextCount in 1..20 && capGiB in 1..100 &&
        (draft?.lanUrl.isNullOrBlank() || runCatching { SmartOfflinePolicy.lanUrl(draft!!.lanUrl) }.isSuccess)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.smart_offline_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (account == null) Text(stringResource(R.string.smart_offline_sign_in))
                draft?.let { settings ->
                    Text(stringResource(R.string.smart_offline_description))
                    SmartCheck(stringResource(R.string.smart_offline_enabled), settings.enabled, !state.busy) {
                        draft = settings.copy(enabled = it)
                    }
                    OutlinedTextField(
                        count,
                        { count = it },
                        label = { Text(stringResource(R.string.smart_offline_count)) },
                        singleLine = true,
                        isError = nextCount !in 1..20
                    )
                    OutlinedTextField(
                        cap,
                        { cap = it },
                        label = { Text(stringResource(R.string.smart_offline_cap)) },
                        singleLine = true,
                        isError = capGiB !in 1..100
                    )
                    SmartCheck(stringResource(R.string.smart_offline_wifi), settings.wifiOnly, !state.busy) {
                        draft = settings.copy(wifiOnly = it)
                    }
                    SmartCheck(stringResource(R.string.smart_offline_cleanup), settings.cleanup, !state.busy) {
                        draft = settings.copy(cleanup = it)
                    }
                    Text(stringResource(R.string.smart_offline_cleanup_help))
                    OutlinedTextField(
                        settings.lanUrl,
                        { draft = settings.copy(lanUrl = it.trim()) },
                        label = { Text(stringResource(R.string.smart_offline_lan)) },
                        placeholder = { Text("https://jellyfin.home.example") },
                        singleLine = true
                    )
                    Text(stringResource(R.string.smart_offline_lan_help))
                    SmartCheck(stringResource(R.string.smart_offline_remote), settings.remoteFallback, !state.busy) {
                        draft = settings.copy(remoteFallback = it)
                    }
                    Text(stringResource(R.string.smart_offline_remote_help))
                    if (settings.status.isNotBlank()) Text(settings.status)
                    TextButton(enabled = valid && !state.busy, onClick = {
                        model.save(settings.copy(nextCount = nextCount!!, capGiB = capGiB!!))
                    }) { Text(stringResource(R.string.smart_offline_save)) }
                    Text(stringResource(R.string.smart_offline_series_help))
                    for (series in state.series) {
                        SmartCheck(series.name, true, !state.busy) {
                            model.toggle(
                                BaseItemDto(
                                    id = UUID.fromString(series.seriesId),
                                    name = series.name,
                                    type = BaseItemKind.SERIES
                                ),
                                false
                            )
                        }
                    }
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text(stringResource(R.string.smart_offline_search)) },
                        singleLine = true
                    )
                    TextButton(
                        enabled = !state.busy,
                        onClick = { model.search(query) }
                    ) { Text(stringResource(R.string.smart_offline_search)) }
                    for (item in state.results.filter { result -> state.series.none { it.seriesId == result.id.toString() } }) {
                        SmartCheck(item.name.orEmpty(), false, !state.busy) { model.toggle(item, true) }
                    }
                }
                if (state.message.isNotBlank()) Text(state.message)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.smart_offline_close)) } },
    )
}

@Composable
private fun SmartCheck(label: String, checked: Boolean, enabled: Boolean, changed: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Checkbox(checked = checked, onCheckedChange = changed, enabled = enabled)
        Text(label)
    }
}
