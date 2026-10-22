package org.jellyfin.mobile.ui.screens.downloads

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Card
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.smart.SmartOfflineCatalogItem
import org.jellyfin.mobile.downloads.smart.SmartOfflineItemPhase

@Composable
internal fun SmartOfflineCatalogSection(items: List<SmartOfflineCatalogItem>) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("smart-offline-catalog")) {
        SmartOfflineSectionTitle(R.string.smart_offline_catalog_heading)
        Text(stringResource(R.string.smart_offline_catalog_help), style = MaterialTheme.typography.body2)
        if (items.isEmpty()) Text(stringResource(R.string.smart_offline_catalog_empty))
        for (item in items) SmartOfflineCatalogRow(item)
    }
}

@Composable
private fun SmartOfflineCatalogRow(entry: SmartOfflineCatalogItem) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("smart-episode-${entry.key}")) {
        Column(Modifier.padding(12.dp)) {
            entry.item.seriesName?.let { Text(it, style = MaterialTheme.typography.caption) }
            Text(entry.item.name.orEmpty(), style = MaterialTheme.typography.subtitle1)
            Text(stringResource(smartOfflineLifecycleLabel(entry)), style = MaterialTheme.typography.body2)
            SmartOfflineTransferProgress(entry)
            if (!entry.selected && entry.phase != SmartOfflineItemPhase.REMOVED) {
                Text(
                    stringResource(R.string.smart_offline_catalog_deselected),
                    style = MaterialTheme.typography.caption
                )
            }
            if (entry.reason.isNotBlank()) Text(entry.reason, style = MaterialTheme.typography.caption)
        }
    }
}

@Composable
private fun SmartOfflineTransferProgress(entry: SmartOfflineCatalogItem) {
    if (entry.phase == SmartOfflineItemPhase.REMOVED) return
    val context = LocalContext.current
    val downloaded = Formatter.formatShortFileSize(context, entry.downloadedBytes)
    val total = entry.totalBytes?.takeIf { it > 0 }
    Text(
        if (total == null) {
            downloaded
        } else {
            stringResource(
                R.string.smart_offline_transfer_bytes,
                downloaded,
                Formatter.formatShortFileSize(context, total),
            )
        },
        style = MaterialTheme.typography.caption,
    )
    if (entry.phase == SmartOfflineItemPhase.DOWNLOADING || entry.phase == SmartOfflineItemPhase.PARTIAL) {
        if (total == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            val fraction = (entry.downloadedBytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
            LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A transfer badge never implies that watch progress has been acknowledged by the server. */
@StringRes
internal fun smartOfflineLifecycleLabel(entry: SmartOfflineCatalogItem): Int = when {
    entry.phase == SmartOfflineItemPhase.REMOVED -> R.string.smart_offline_item_removed
    entry.pendingSync && entry.watched -> R.string.smart_offline_item_watched_pending
    entry.pendingSync -> R.string.smart_offline_item_progress_pending
    entry.cleanupEligible -> R.string.smart_offline_item_cleanup_eligible
    entry.watched && entry.phase == SmartOfflineItemPhase.COMPLETE -> R.string.smart_offline_item_watched_kept
    entry.watched -> R.string.smart_offline_item_watched
    else -> smartOfflineTransferLabel(entry.phase)
}

@StringRes
private fun smartOfflineTransferLabel(phase: SmartOfflineItemPhase): Int = when (phase) {
    SmartOfflineItemPhase.QUEUED -> R.string.smart_offline_item_queued
    SmartOfflineItemPhase.DOWNLOADING -> R.string.smart_offline_item_downloading
    SmartOfflineItemPhase.PARTIAL -> R.string.smart_offline_item_partial
    SmartOfflineItemPhase.WAITING -> R.string.smart_offline_item_waiting
    SmartOfflineItemPhase.COMPLETE -> R.string.smart_offline_item_complete
    SmartOfflineItemPhase.REMOVED -> R.string.smart_offline_item_removed
}
