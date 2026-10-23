package org.jellyfin.mobile.downloads.smart

import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import java.io.File
import java.io.IOException

private const val MAX_EXTERNAL_SUBTITLES = 32
private val supportedTextCodecs = setOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "ttml")

internal data class SmartOfflineSubtitleRequest(
    val account: SmartOfflineAccount,
    val endpoint: VerifiedDownloadEndpoint,
    val api: ApiClient,
    val entry: SmartOfflineManaged,
    val downloadId: Long,
)

internal fun externalTextSubtitles(entry: SmartOfflineManaged): List<MediaStream> =
    entry.item.mediaSources?.firstOrNull()?.mediaStreams.orEmpty().filter {
        it.type == MediaStreamType.SUBTITLE && it.isExternal && it.codec?.lowercase() in supportedTextCodecs &&
            it.index in 0..MAX_SUBTITLE_INDEX
    }.distinctBy { it.index }.take(MAX_EXTERNAL_SUBTITLES)

/** Backfills older complete videos without transferring their video bytes again. */
internal class SmartOfflineSubtitles(
    private val paths: SmartOfflinePaths,
    private val downloads: DownloadDao,
    private val dao: SmartOfflineDao,
    private val policy: SmartOfflineTransferPolicy,
    private val journal: OfflinePlaybackJournal,
) {
    private val transfer = SmartOfflineSubtitleTransfer()

    suspend fun download(request: SmartOfflineSubtitleRequest) {
        val tracks = externalTextSubtitles(request.entry)
        val externalCount = request.entry.item.mediaSources?.firstOrNull()?.mediaStreams.orEmpty().count {
            it.type == MediaStreamType.SUBTITLE && it.isExternal
        }
        if (externalCount == 0) return
        try {
            for (track in tracks) ensureTrack(request, track)
            val unsupported = externalCount - tracks.size
            val reason = "Offline text subtitles ready (${tracks.size}/${tracks.size})" +
                if (unsupported > 0) "; $unsupported unsupported or excess external tracks unavailable offline" else ""
            dao.lifecycle(request.entry.key, SmartOfflineItemPhase.COMPLETE.name, reason)
        } catch (error: CancellationException) {
            pending(request, "Video ready; offline subtitles interrupted and will retry")
            throw error
        } catch (_: IOException) {
            pending(request, "Video ready; offline subtitles incomplete and will retry on a permitted check")
            throw OfflineSubtitlesPending()
        }
    }

    private suspend fun ensureTrack(request: SmartOfflineSubtitleRequest, track: MediaStream) {
        val name = managedSubtitleName(request.entry.key, track.index)
        val target = paths.subtitle(request.entry, track.index)
        val old = downloads.getFiles(request.downloadId).firstOrNull {
            it.type == DownloadFileType.SUBTITLE && it.fileName == name
        }
        if (complete(old, target)) return
        if (journal.isPlaying(request.downloadId)) throw IOException("Subtitle backfill waits for local playback")
        val partial = paths.subtitle(request.entry, track.index, partial = true)
        val used = paths.root.listFiles().orEmpty().sumOf { if (it == partial) 0 else it.length() }
        val budget = request.account.capGiB * SmartOfflinePolicy.GIB - used
        val permitted = policy.permitted(request.account, request.endpoint, request.api, request.entry.item)
        val bytes = transfer.download(request, track.index, partial, budget, permitted)
        publish(request, old, track.index, bytes, permitted)
    }

    private fun complete(file: DownloadFileEntity?, target: File): Boolean {
        if (file?.status != DownloadStatus.DOWNLOADED || file.uri != target.toUri()) return false
        return file.size in 1..MAX_OFFLINE_SUBTITLE_BYTES && target.isFile && target.length() == file.size
    }

    private suspend fun publish(
        request: SmartOfflineSubtitleRequest,
        old: DownloadFileEntity?,
        index: Int,
        bytes: Long,
        permitted: suspend () -> Boolean,
    ) {
        val target = paths.subtitle(request.entry, index)
        val partial = paths.subtitle(request.entry, index, partial = true)
        policy.requireCurrent(request.account)
        if (!permitted() || !policy.subscribed(request.account, request.entry.item)) {
            throw IOException("Subtitle publication paused by policy")
        }
        if (!partial.renameTo(target)) throw IOException("Cannot publish local subtitle")
        val file = DownloadFileEntity(
            id = old?.id ?: 0,
            downloadId = request.downloadId,
            type = DownloadFileType.SUBTITLE,
            size = bytes,
            fileName = target.name,
            uri = target.toUri(),
            status = DownloadStatus.DOWNLOADED,
        )
        if (old == null) downloads.insertFile(file) else downloads.updateFile(file)
    }

    private suspend fun pending(request: SmartOfflineSubtitleRequest, reason: String) {
        dao.lifecycle(request.entry.key, SmartOfflineItemPhase.COMPLETE.name, reason)
    }
}
