package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.smart.SmartOfflineAction
import org.jellyfin.mobile.downloads.smart.SmartOfflineSeries
import org.jellyfin.mobile.downloads.smart.SmartOfflineUiState
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

@Composable
internal fun SmartOfflineSeriesChooser(state: SmartOfflineUiState, callbacks: SmartOfflineCallbacks) {
    Column(Modifier.padding(16.dp).testTag("smart-offline-series")) {
        SmartOfflineSectionTitle(R.string.smart_offline_series_heading)
        Text(stringResource(R.string.smart_offline_series_help), style = MaterialTheme.typography.body2)
        state.contextualSeries?.let { item ->
            Text(stringResource(R.string.smart_offline_context_show), modifier = Modifier.padding(top = 8.dp))
            SmartOfflineShowRow(item, state, callbacks)
        }
        Text(
            stringResource(R.string.smart_offline_selected_count, state.series.size),
            style = MaterialTheme.typography.subtitle1,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (state.series.isEmpty()) Text(stringResource(R.string.smart_offline_no_series))
        for (series in state.series) SmartOfflineShowRow(series.asItem(), state, callbacks)
        SmartOfflineSelectionFeedback(state, callbacks)
        SmartOfflineSearch(state, callbacks)
        Text(stringResource(R.string.smart_offline_next_step), modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun SmartOfflineSelectionFeedback(state: SmartOfflineUiState, callbacks: SmartOfflineCallbacks) {
    if (state.action == SmartOfflineAction.TOGGLE) {
        Text(stringResource(R.string.smart_offline_selection_saving))
    } else {
        state.seriesFeedback?.let { feedback ->
            val label = if (feedback.selected) {
                R.string.smart_offline_selection_added
            } else {
                R.string.smart_offline_selection_removed
            }
            Text(stringResource(label, feedback.item.name.orEmpty()), style = MaterialTheme.typography.body2)
            TextButton(
                onClick = { callbacks.toggle(feedback.item, !feedback.selected) },
                enabled = !state.busy,
                modifier = Modifier.testTag("smart-offline-selection-undo"),
            ) { Text(stringResource(R.string.smart_offline_undo)) }
        }
    }
}

@Composable
private fun SmartOfflineSearch(state: SmartOfflineUiState, callbacks: SmartOfflineCallbacks) {
    var query by remember(state.account?.key) { mutableStateOf("") }
    var searched by remember(state.account?.key) { mutableStateOf(false) }
    OutlinedTextField(
        query,
        {
            query = it
            searched = false
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("smart-offline-search"),
        label = { Text(stringResource(R.string.smart_offline_search)) },
        singleLine = true,
        enabled = !state.busy,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                searched = true
                callbacks.search(query)
            },
            enabled = !state.busy && query.isNotBlank(),
        ) { Text(stringResource(R.string.smart_offline_search)) }
        TextButton(
            onClick = {
                query = ""
                searched = true
                callbacks.search("")
            },
            enabled = !state.busy,
            modifier = Modifier.testTag("smart-offline-browse"),
        ) { Text(stringResource(R.string.smart_offline_browse)) }
    }
    if (state.action == SmartOfflineAction.SEARCH) Text(stringResource(R.string.smart_offline_searching))
    if (searched) SmartOfflineSearchResults(state, callbacks)
}

@Composable
private fun SmartOfflineSearchResults(state: SmartOfflineUiState, callbacks: SmartOfflineCallbacks) {
    val selectedIds = state.series.map { it.seriesId }.toSet()
    for (item in state.results.filterNot { it.id.toString() in selectedIds }) {
        SmartOfflineShowRow(item, state, callbacks)
    }
    if (!state.busy && state.results.isEmpty() && state.error.isBlank()) {
        Text(stringResource(R.string.smart_offline_no_results))
    }
    if (state.results.isNotEmpty()) {
        Text(
            stringResource(R.string.smart_offline_browse_limit),
            style = MaterialTheme.typography.caption,
        )
    }
}

private fun SmartOfflineSeries.asItem(): BaseItemDto = item ?: BaseItemDto(
    id = UUID.fromString(seriesId),
    name = name,
    type = BaseItemKind.SERIES,
)
