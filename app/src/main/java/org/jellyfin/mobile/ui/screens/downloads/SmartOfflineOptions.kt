package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.smart.SmartOfflinePolicy

private const val HTTP_CONSENT_MAX_HEIGHT = 360

@Composable
internal fun SmartOfflineDownloadOptions(draft: SmartOfflineDraft, enabled: Boolean) {
    val settings = draft.settings
    SmartOfflineSectionTitle(R.string.smart_offline_settings_heading)
    SmartOfflineCheck(stringResource(R.string.smart_offline_enabled), settings.enabled, enabled) {
        draft.settings = settings.copy(enabled = it)
    }
    SmartOfflineNumberField(draft.count, { draft.count = it }, R.string.smart_offline_count, draft.countError, enabled)
    SmartOfflineNumberField(draft.cap, { draft.cap = it }, R.string.smart_offline_cap, draft.capError, enabled)
    SmartOfflineCheck(stringResource(R.string.smart_offline_wifi), settings.wifiOnly, enabled) {
        draft.settings = settings.copy(wifiOnly = it)
    }
    SmartOfflineCheck(stringResource(R.string.smart_offline_cleanup), settings.cleanup, enabled) {
        draft.settings = settings.copy(cleanup = it)
    }
    Text(stringResource(R.string.smart_offline_cleanup_help), style = MaterialTheme.typography.body2)
}

@Composable
private fun SmartOfflineNumberField(
    value: String,
    changed: (String) -> Unit,
    label: Int,
    error: Int?,
    enabled: Boolean
) {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(error, focused) { if (error != null && focused) requester.bringIntoView() }
    Column(Modifier.bringIntoViewRequester(requester)) {
        OutlinedTextField(
            value,
            changed,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).onFocusChanged { focused = it.isFocused }.testTag(
                if (label == R.string.smart_offline_count) "smart-offline-count" else "smart-offline-cap"
            ),
            label = { Text(stringResource(label)) },
            singleLine = true,
            isError = error != null,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        SmartOfflineFieldError(error)
    }
}

@Composable
private fun SmartOfflineLanField(draft: SmartOfflineDraft, enabled: Boolean) {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val error = draft.lanError
    LaunchedEffect(error, focused) { if (error != null && focused) requester.bringIntoView() }
    Column(Modifier.bringIntoViewRequester(requester)) {
        OutlinedTextField(
            draft.settings.lanUrl,
            draft::setLanUrl,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.testTag("smart-offline-lan"),
            label = { Text(stringResource(R.string.smart_offline_lan)) },
            placeholder = { Text("https://jellyfin.home.example") },
            singleLine = true,
            enabled = enabled,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        SmartOfflineFieldError(error)
    }
}

@Composable
internal fun SmartOfflineNetworkOptions(draft: SmartOfflineDraft, enabled: Boolean) {
    var confirmHttp by remember(draft.settings.key) { mutableStateOf(false) }
    val settings = draft.settings
    val eligibleHttp = runCatching {
        SmartOfflinePolicy.lanUrl(
            settings.lanUrl
        ).startsWith("http:")
    }.getOrDefault(false)
    Column(Modifier.padding(horizontal = 16.dp)) {
        SmartOfflineSectionTitle(R.string.smart_offline_network_heading)
        SmartOfflineLanField(draft, enabled)
        Text(stringResource(R.string.smart_offline_lan_help), style = MaterialTheme.typography.body2)
        if (eligibleHttp) {
            SmartOfflineCheck(
                stringResource(R.string.smart_offline_http_trust),
                SmartOfflinePolicy.isTrustedHttpLan(settings),
                enabled
            ) {
                if (it) confirmHttp = true else draft.trustHttp(false)
            }
            Text(stringResource(R.string.smart_offline_http_help), style = MaterialTheme.typography.body2)
        }
        SmartOfflineCheck(stringResource(R.string.smart_offline_remote), settings.remoteFallback, enabled) {
            draft.settings = settings.copy(remoteFallback = it)
        }
        Text(stringResource(R.string.smart_offline_remote_help), style = MaterialTheme.typography.body2)
        SmartOfflineFieldError(draft.routeError)
    }
    if (confirmHttp) {
        SmartOfflineHttpConsent(settings.lanUrl, { confirmHttp = false }) {
            draft.trustHttp(true)
            confirmHttp = false
        }
    }
}

@Composable
private fun SmartOfflineHttpConsent(url: String, cancel: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(stringResource(R.string.smart_offline_http_title)) },
        text = {
            Column(Modifier.heightIn(max = HTTP_CONSENT_MAX_HEIGHT.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.smart_offline_http_warning, url))
            }
        },
        confirmButton = {
            TextButton(onClick = confirm) { Text(stringResource(R.string.smart_offline_http_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = cancel) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
internal fun SmartOfflineSectionTitle(label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.h6,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
}

@Composable
internal fun SmartOfflineFieldError(error: Int?) {
    if (error != null) {
        Text(
            stringResource(error),
            color = MaterialTheme.colors.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
internal fun SmartOfflineCheck(label: String, checked: Boolean, enabled: Boolean, changed: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(
            checked,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = changed
        ).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
}
