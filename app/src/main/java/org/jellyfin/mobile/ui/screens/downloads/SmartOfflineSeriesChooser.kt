package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
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
    var query by remember(state.account?.key) { mutableStateOf("") }
    var searched by remember(state.account?.key) { mutableStateOf(false) }
    Column(Modifier.padding(16.dp).testTag("smart-offline-series")) {
        SmartOfflineSectionTitle(R.string.smart_offline_series_heading)
        Text(
            stringResource(R.string.smart_offline_selected_count, state.series.size),
            style = MaterialTheme.typography.subtitle1
        )
        if (state.series.isEmpty()) Text(stringResource(R.string.smart_offline_no_series))
        for (series in state.series) {
            SmartOfflineCheck(series.name, true, !state.busy) { callbacks.toggle(series.asItem(), false) }
        }
        Text(stringResource(R.string.smart_offline_series_help), style = MaterialTheme.typography.body2)
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
        Button(
            onClick = {
                searched = true
                callbacks.search(query)
            },
            enabled = !state.busy && query.isNotBlank(),
        ) {
            val label = if (state.action == SmartOfflineAction.SEARCH) {
                R.string.smart_offline_searching
            } else {
                R.string.smart_offline_search
            }
            Text(stringResource(label))
        }
        val selectedIds = state.series.map { it.seriesId }.toSet()
        if (searched) {
            for (item in state.results.filterNot { it.id.toString() in selectedIds }) {
                SmartOfflineCheck(item.name.orEmpty(), false, !state.busy) { callbacks.toggle(item, true) }
            }
        }
        val completedSearch = searched && !state.busy
        val noMatches = state.results.isEmpty() && state.error.isBlank()
        if (completedSearch && noMatches) {
            Text(stringResource(R.string.smart_offline_no_results))
        }
        Text(stringResource(R.string.smart_offline_next_step), modifier = Modifier.padding(top = 16.dp))
    }
}

private fun SmartOfflineSeries.asItem() = BaseItemDto(
    id = UUID.fromString(seriesId),
    name = name,
    type = BaseItemKind.SERIES
)
