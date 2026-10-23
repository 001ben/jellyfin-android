package org.jellyfin.mobile.player.source

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.downloads.smart.SmartOfflineDao
import org.jellyfin.mobile.downloads.smart.SmartOfflineManaged
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.io.File
import java.io.IOException
import java.util.UUID

data class LocalPlaybackCandidate(
    val download: DownloadEntity,
    val mainFile: DownloadFileEntity,
    val sourceInfo: MediaSourceInfo,
) {
    fun createSource(details: PlaybackDetails, session: String): LocalJellyfinMediaSource = LocalJellyfinMediaSource(
        itemId = download.itemId,
        item = download.item,
        sourceInfo = sourceInfo,
        playSessionId = session,
        playbackDetails = details,
        download = LocalDownloadPlayback(download.id, mainFile.uri),
    ).apply {
        // An explicit zero starts over; otherwise use progress captured by offline playback.
        startTime = details.startTime ?: (download.item.userData?.playbackPositionTicks ?: 0).ticks
    }
}

/** A capability check never starts a session; the player must still acquire the managed playback lease. */
class LocalPlaybackResolver(
    private val context: Context,
    private val downloads: DownloadDao,
    private val smart: SmartOfflineDao,
    private val verifyFile: (DownloadFileEntity) -> Boolean = { completeLocalFile(context, it) },
) {
    suspend fun canPlay(itemId: UUID, serverId: Long, userId: Long): Boolean =
        resolve(itemId, serverId, userId) != null

    suspend fun resolve(
        itemId: UUID,
        serverId: Long,
        userId: Long,
        mediaSourceId: String? = null,
    ): LocalPlaybackCandidate? = withContext(Dispatchers.IO) {
        val rows = downloads.getDownloadsByItemIds(listOf(itemId)).filter {
            it.itemId == itemId && it.item.id == itemId && it.serverId == serverId && it.userId == userId &&
                it.status == DownloadStatus.DOWNLOADED
        }
        rows.firstNotNullOfOrNull { candidate(it, mediaSourceId) }
    }

    private suspend fun candidate(download: DownloadEntity, requestedSource: String?): LocalPlaybackCandidate? {
        // Downloads contain the first source, not every alternative version advertised by the server.
        val source = download.item.mediaSources?.firstOrNull()?.takeIf { !it.id.isNullOrBlank() } ?: return null
        val matchesSource = requestedSource == null || requestedSource == source.id ||
            requestedSource.toUUIDOrNull() == download.itemId // Legacy Downloads-screen options use the item id.
        if (!matchesSource) return null
        val files = downloads.getFiles(download.id)
        val main = files.firstOrNull {
            it.downloadId == download.id && it.type == DownloadFileType.ITEM &&
                it.status == DownloadStatus.DOWNLOADED && it.size > 0
        } ?: return null
        if (!managedFileMatches(download, main) || !verifyFile(main)) return null
        return LocalPlaybackCandidate(
            download,
            main,
            LocalSubtitleResolver(context, verifyFile).resolve(download, source, files)
        )
    }

    private suspend fun managedFileMatches(download: DownloadEntity, file: DownloadFileEntity): Boolean {
        val owned = smart.owned(download.id) ?: return !download.path.startsWith("smart-offline/")
        if (!ownershipMatches(download, owned)) return false
        val published = owned.bytes > 0 && file.size == owned.bytes && file.uri.scheme == "file"
        return published && managedPathMatches(file, owned.key)
    }

    private fun ownershipMatches(download: DownloadEntity, owned: SmartOfflineManaged): Boolean {
        val account = "${download.serverId}-${download.userId}"
        val key = "$account-${download.itemId}"
        return owned.account == account && owned.key == key && owned.item.id == download.itemId &&
            owned.downloadId == download.id && download.path == "smart-offline/$key"
    }

    private fun managedPathMatches(file: DownloadFileEntity, key: String): Boolean {
        val actual = file.uri.path?.let(::File) ?: return false
        return try {
            val root = File(context.filesDir, "smart-offline").canonicalFile
            actual.canonicalFile == File(root, key)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

private fun completeLocalFile(context: Context, entry: DownloadFileEntity): Boolean = try {
    when (entry.uri.scheme) {
        "file" -> entry.uri.path?.takeIf { entry.uri.authority.isNullOrEmpty() }?.let(::File)?.let {
            it.isFile && it.canRead() && it.length() == entry.size
        } == true
        "content" -> DocumentFile.fromSingleUri(context, entry.uri)?.let {
            it.isFile && it.canRead() && it.length() == entry.size
        } == true
        else -> false // A persisted remote URL is never a local playback capability.
    }
} catch (_: SecurityException) {
    false
} catch (_: IllegalArgumentException) {
    false
}
