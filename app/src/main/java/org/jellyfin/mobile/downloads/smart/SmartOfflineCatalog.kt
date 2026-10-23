package org.jellyfin.mobile.downloads.smart

import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.model.api.BaseItemDto

enum class SmartOfflineItemPhase { QUEUED, DOWNLOADING, PARTIAL, WAITING, COMPLETE, REMOVED }

/** Transfer state and watched/synchronization state are independent dimensions. */
data class SmartOfflineCatalogItem(
    val key: String,
    val item: BaseItemDto,
    val seriesId: String?,
    val downloadId: Long?,
    val phase: SmartOfflineItemPhase,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val reason: String,
    val watched: Boolean,
    val pendingSync: Boolean,
    val cleanupEligible: Boolean,
    val selected: Boolean,
    val removedAt: Long? = null,
)

internal data class SmartOfflineCatalogRecords(
    val account: SmartOfflineAccount,
    val series: List<SmartOfflineSeries>,
    val managed: List<SmartOfflineManaged>,
    val removals: List<SmartOfflineRemoval>,
    val pending: List<OfflineProgress>,
)

internal data class SmartOfflineCatalogFiles(
    val downloads: List<DownloadEntity>,
    val lengths: Map<String, Long>,
    val playing: Set<Long>,
    val transferring: Set<String>,
)

/** Pure projection: a display badge never authorizes playback or deletion. */
internal fun smartOfflineCatalog(
    records: SmartOfflineCatalogRecords,
    files: SmartOfflineCatalogFiles,
): List<SmartOfflineCatalogItem> {
    val managed = records.managed.filter { it.account == records.account.key }
        .map { catalogItem(it, records, files) }
    val ownedItems = managed.map { it.item.id }.toSet()
    val removed = records.removals.filter { it.account == records.account.key && it.item.id !in ownedItems }
        .sortedWith(compareByDescending<SmartOfflineRemoval> { it.removedAt }.thenByDescending { it.id })
        .distinctBy { it.item.id }.map { removal ->
            SmartOfflineCatalogItem(
                key = "removed-${removal.id}",
                item = removal.item,
                seriesId = removal.item.seriesId?.toString(),
                downloadId = null,
                phase = SmartOfflineItemPhase.REMOVED,
                downloadedBytes = 0,
                totalBytes = null,
                reason = if (removal.automatic) "Watched download removed automatically" else "Local download removed",
                watched = removal.automatic,
                pendingSync = false,
                cleanupEligible = false,
                selected = selected(records, removal.item),
                removedAt = removal.removedAt,
            )
        }
    return managed + removed
}

private fun catalogItem(
    entry: SmartOfflineManaged,
    records: SmartOfflineCatalogRecords,
    files: SmartOfflineCatalogFiles,
): SmartOfflineCatalogItem {
    val local = files.downloads.firstOrNull { it.id == entry.downloadId }
        ?.takeIf { ownsDownload(entry, records.account, it) }
    val length = files.lengths[entry.key]?.coerceAtLeast(0) ?: 0
    val complete = local?.status == DownloadStatus.DOWNLOADED && entry.bytes > 0 && length == entry.bytes
    val progress = records.pending.firstOrNull {
        it.serverId == records.account.serverId && it.userId == records.account.userId &&
            it.downloadId == entry.downloadId && it.itemId == entry.item.id
    }
    val selected = selected(records, entry.item)
    val phase = catalogPhase(entry, files, complete, length, selected)
    val total = if (complete) entry.bytes else expectedBytes(entry)
    return SmartOfflineCatalogItem(
        key = entry.key,
        item = local?.item ?: entry.item,
        seriesId = entry.item.seriesId?.toString(),
        downloadId = local?.id?.takeIf { complete },
        phase = phase,
        downloadedBytes = length,
        totalBytes = total,
        reason = catalogReason(entry, phase, selected),
        watched = entry.serverPlayed || local?.item?.userData?.played == true || progress?.finished == true,
        pendingSync = progress != null,
        cleanupEligible = cleanupEligible(
            records.account,
            entry,
            complete,
            progress != null,
            local?.id?.let { it in files.playing } == true
        ),
        selected = selected,
    )
}

private fun cleanupEligible(
    account: SmartOfflineAccount,
    entry: SmartOfflineManaged,
    complete: Boolean,
    pending: Boolean,
    playing: Boolean,
): Boolean = complete && account.enabled &&
    SmartOfflinePolicy.canClean(account.cleanup, true, entry.serverPlayed, pending, playing)

private fun catalogPhase(
    entry: SmartOfflineManaged,
    files: SmartOfflineCatalogFiles,
    complete: Boolean,
    length: Long,
    selected: Boolean,
): SmartOfflineItemPhase = when {
    complete -> SmartOfflineItemPhase.COMPLETE
    entry.key in files.transferring -> SmartOfflineItemPhase.DOWNLOADING
    !selected -> SmartOfflineItemPhase.WAITING
    entry.phase == SmartOfflineItemPhase.WAITING.name -> SmartOfflineItemPhase.WAITING
    length > 0 -> SmartOfflineItemPhase.PARTIAL
    entry.phase == SmartOfflineItemPhase.DOWNLOADING.name -> SmartOfflineItemPhase.WAITING
    else -> SmartOfflineItemPhase.QUEUED
}

private fun catalogReason(entry: SmartOfflineManaged, phase: SmartOfflineItemPhase, selected: Boolean): String = when {
    phase == SmartOfflineItemPhase.COMPLETE && entry.phase == SmartOfflineItemPhase.COMPLETE.name -> entry.reason
    phase == SmartOfflineItemPhase.COMPLETE -> ""
    !selected -> "Series is no longer selected; local files are retained"
    entry.reason.isNotBlank() -> entry.reason
    entry.phase == SmartOfflineItemPhase.DOWNLOADING.name && phase != SmartOfflineItemPhase.DOWNLOADING ->
        "Transfer interrupted; waiting to resume"
    else -> ""
}

private fun expectedBytes(entry: SmartOfflineManaged): Long? =
    (entry.expectedBytes ?: entry.item.mediaSources?.firstOrNull()?.size)?.takeIf { it > 0 }

private fun selected(records: SmartOfflineCatalogRecords, item: BaseItemDto): Boolean =
    records.series.any { it.account == records.account.key && it.seriesId == item.seriesId?.toString() }
