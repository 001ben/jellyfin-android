package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.downloads.smart.SmartOfflineAccount
import org.jellyfin.mobile.downloads.smart.SmartOfflineAction
import org.jellyfin.mobile.downloads.smart.SmartOfflineUiState
import org.jellyfin.mobile.downloads.smart.SmartOfflineViewModel
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import java.util.UUID

@Composable
fun SmartOfflineActions(visible: Boolean, initialSeriesId: UUID? = null, content: @Composable () -> Unit) {
    var show by remember(initialSeriesId) { mutableStateOf(initialSeriesId != null) }
    var pendingSeriesId by remember(initialSeriesId) { mutableStateOf(initialSeriesId) }
    if (show) {
        SmartOfflineDialog(
            onDismiss = {
                show = false
                pendingSeriesId = null
            },
            initialSeriesId = pendingSeriesId,
        )
    }
    Row {
        if (visible) TextButton(onClick = { show = true }) { Text(stringResource(R.string.smart_offline_title)) }
        content()
    }
}

@Composable
fun SmartOfflineDialog(
    onDismiss: () -> Unit,
    initialSeriesId: UUID? = null,
    model: SmartOfflineViewModel = viewModel(),
) {
    val state by model.state.collectAsState()
    val preferences = koinInject<AppPreferences>()
    val identity by preferences.currentAccountChanges.collectAsState(
        initial = preferences.currentServerId to preferences.currentUserId
    )
    val contextIdentity = remember(initialSeriesId) { identity }
    LaunchedEffect(identity, initialSeriesId) {
        model.load(initialSeriesId.takeIf { identity == contextIdentity })
    }
    val visibleState = if (state.account == null || state.account?.let { it.serverId to it.userId } == identity) {
        state
    } else {
        SmartOfflineUiState(action = SmartOfflineAction.LOAD)
    }
    SmartOfflineEditor(visibleState, SmartOfflineCallbacks(model::save, model::search, model::toggle), onDismiss)
}

internal data class SmartOfflineCallbacks(
    val save: (SmartOfflineAccount) -> Unit,
    val search: (String) -> Unit,
    val toggle: (BaseItemDto, Boolean) -> Unit,
)

/** A bounded full-screen layout avoids AlertDialog text-slot clipping and keeps Save above the keyboard. */
@Composable
internal fun SmartOfflineEditor(state: SmartOfflineUiState, callbacks: SmartOfflineCallbacks, onClose: () -> Unit) {
    val account = state.account
    val draft = account?.let { remember(it.key) { SmartOfflineDraft(it) } }
    var confirmDiscard by remember(account?.key) { mutableStateOf(false) }
    val close = {
        if (!state.busy) {
            if (draft != null && !draft.matches(account)) confirmDiscard = true else onClose()
        }
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
            Scaffold(
                modifier = Modifier.systemBarsPadding().imePadding(),
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.smart_offline_title)) },
                        actions = {
                            TextButton(onClick = close, enabled = !state.busy) {
                                Text(stringResource(R.string.smart_offline_close))
                            }
                        },
                    )
                },
                bottomBar = {
                    if (draft != null) SmartOfflineSaveBar(state, draft) { draft.savedSettings()?.let(callbacks.save) }
                },
            ) { padding -> SmartOfflineBody(state, draft, callbacks, Modifier.padding(padding)) }
        }
    }
    if (confirmDiscard) SmartOfflineDiscardDialog({ confirmDiscard = false }, onClose)
}

@Composable
private fun SmartOfflineDiscardDialog(keepEditing: () -> Unit, discard: () -> Unit) {
    AlertDialog(
        onDismissRequest = keepEditing,
        title = { Text(stringResource(R.string.smart_offline_unsaved_title)) },
        text = { Text(stringResource(R.string.smart_offline_unsaved_help)) },
        confirmButton = {
            TextButton(onClick = discard) { Text(stringResource(R.string.smart_offline_discard)) }
        },
        dismissButton = {
            TextButton(onClick = keepEditing) { Text(stringResource(R.string.smart_offline_keep_editing)) }
        },
    )
}

@Composable
private fun SmartOfflineBody(
    state: SmartOfflineUiState,
    draft: SmartOfflineDraft?,
    callbacks: SmartOfflineCallbacks,
    modifier: Modifier,
) {
    LazyColumn(modifier.fillMaxSize().testTag("smart-offline-form")) {
        item {
            Column(Modifier.padding(16.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (draft == null) {
                    Text(
                        stringResource(
                            if (state.busy) R.string.smart_offline_loading else R.string.smart_offline_sign_in
                        )
                    )
                } else {
                    Text(stringResource(R.string.smart_offline_description))
                    SmartOfflineStatusCard(state)
                    SmartOfflineDownloadOptions(draft, !state.busy)
                }
            }
        }
        if (draft != null) {
            item { SmartOfflineNetworkOptions(draft, !state.busy) }
            item { SmartOfflineSeriesChooser(state, callbacks) }
            item { SmartOfflineCatalogSection(state.catalog) }
        }
    }
}

@Composable
private fun SmartOfflineSaveBar(state: SmartOfflineUiState, draft: SmartOfflineDraft, save: () -> Unit) {
    Surface(elevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            val dirty = !draft.matches(state.account)
            val feedback = when {
                state.error.isNotBlank() -> state.error
                state.action == SmartOfflineAction.SAVE -> stringResource(R.string.smart_offline_saving)
                dirty && !draft.canSave -> stringResource(R.string.smart_offline_fix_errors)
                dirty -> stringResource(R.string.smart_offline_unsaved)
                else -> smartOfflineSaveFeedback(state)
            }
            Text(
                feedback,
                color = if (state.error.isNotBlank()) MaterialTheme.colors.error else MaterialTheme.colors.onSurface,
                modifier = Modifier.padding(bottom = 8.dp).testTag("smart-offline-save-feedback"),
            )
            Button(
                onClick = save,
                enabled = draft.canSave && !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("smart-offline-save")
            ) {
                Text(
                    stringResource(
                        if (draft.settings.enabled) R.string.smart_offline_save else R.string.smart_offline_save_only
                    )
                )
            }
        }
    }
}
