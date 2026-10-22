package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.smart.SmartOfflineArtwork
import org.jellyfin.mobile.downloads.smart.SmartOfflineUiState
import org.jellyfin.sdk.model.api.BaseItemDto

@Composable
internal fun SmartOfflineShowRow(item: BaseItemDto, state: SmartOfflineUiState, callbacks: SmartOfflineCallbacks) {
    val selected = state.series.any { it.seriesId == item.id.toString() }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .testTag("smart-series-${item.id}")
            .toggleable(selected, enabled = !state.busy, role = Role.Checkbox) { callbacks.toggle(item, it) },
        elevation = 2.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            SmartOfflinePoster(state.artwork[item.id], Modifier.size(56.dp, 84.dp).testTag("smart-art-${item.id}"))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    item.name.orEmpty(),
                    style = MaterialTheme.typography.subtitle1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.productionYear?.let { Text(it.toString(), style = MaterialTheme.typography.caption) }
                Text(
                    stringResource(if (selected) R.string.smart_offline_selected else R.string.smart_offline_add_show),
                    style = MaterialTheme.typography.caption,
                )
            }
            Checkbox(checked = selected, onCheckedChange = null, enabled = !state.busy)
        }
    }
}

@Composable
private fun SmartOfflinePoster(artwork: SmartOfflineArtwork?, modifier: Modifier) {
    val placeholder = painterResource(R.drawable.ic_local_movies_white_64)
    if (artwork == null) {
        Image(placeholder, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        val context = LocalContext.current
        val request = remember(artwork, context) { artwork.request(context) }
        AsyncImage(
            model = request,
            contentDescription = null,
            placeholder = placeholder,
            error = placeholder,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}
