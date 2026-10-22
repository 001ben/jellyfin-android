package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.smart.SmartOfflineSaveFeedback
import org.jellyfin.mobile.downloads.smart.SmartOfflineUiState
import org.jellyfin.mobile.downloads.smart.SmartOfflineWorkState

@Composable
internal fun SmartOfflineStatusCard(state: SmartOfflineUiState) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            val title = when (state.status.workState) {
                SmartOfflineWorkState.RUNNING -> R.string.smart_offline_running
                SmartOfflineWorkState.QUEUED -> R.string.smart_offline_queued
                SmartOfflineWorkState.RETRYING -> R.string.smart_offline_retrying
                SmartOfflineWorkState.SUCCEEDED -> R.string.smart_offline_check_complete
                SmartOfflineWorkState.CANCELLED -> R.string.smart_offline_cancelled
                SmartOfflineWorkState.FAILED -> R.string.smart_offline_failed
                SmartOfflineWorkState.IDLE -> when {
                    state.account?.enabled != true -> R.string.smart_offline_off
                    state.status.automaticScheduled -> R.string.smart_offline_scheduled
                    else -> R.string.smart_offline_not_scheduled
                }
            }
            Text(stringResource(title), style = MaterialTheme.typography.subtitle1)
            Text(stringResource(R.string.smart_offline_queue_help), style = MaterialTheme.typography.body2)
            if (state.status.lastReport.isNotBlank()) {
                Text(
                    stringResource(R.string.smart_offline_last_report, state.status.lastReport),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
internal fun smartOfflineSaveFeedback(state: SmartOfflineUiState): String = stringResource(
    when (state.saveFeedback) {
        SmartOfflineSaveFeedback.SAVED_AND_QUEUED -> R.string.smart_offline_saved_queued
        SmartOfflineSaveFeedback.SAVED_AND_DISABLED -> R.string.smart_offline_saved_disabled
        SmartOfflineSaveFeedback.SAVED -> R.string.smart_offline_saved
        null -> R.string.smart_offline_save_hint
    },
)
