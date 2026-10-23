package org.jellyfin.mobile.player.source

import android.content.Context
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.downloads.smart.MAX_OFFLINE_SUBTITLE_BYTES
import org.jellyfin.mobile.downloads.smart.MAX_SUBTITLE_INDEX
import org.jellyfin.mobile.downloads.smart.managedSubtitleName
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import java.io.File
import java.io.IOException

/** Replace remote subtitle metadata only with complete, owned local sidecars; never make HTTP playback requests. */
internal class LocalSubtitleResolver(
    private val context: Context,
    private val verifyFile: (DownloadFileEntity) -> Boolean,
) {
    fun resolve(download: DownloadEntity, source: MediaSourceInfo, files: List<DownloadFileEntity>): MediaSourceInfo =
        source.copy(
            mediaStreams = source.mediaStreams?.map { stream ->
                if (stream.type != MediaStreamType.SUBTITLE) {
                    stream
                } else if (!stream.isExternal) {
                    stream.copy(deliveryMethod = SubtitleDeliveryMethod.EMBED, deliveryUrl = null)
                } else {
                    val file = localSubtitle(download, stream, files)
                    stream.copy(
                        codec = if (file == null) stream.codec else "webvtt",
                        deliveryMethod = if (file == null) null else SubtitleDeliveryMethod.EXTERNAL,
                        deliveryUrl = file?.uri?.toString(),
                    )
                }
            }
        )

    private fun localSubtitle(
        download: DownloadEntity,
        stream: MediaStream,
        files: List<DownloadFileEntity>,
    ): DownloadFileEntity? {
        val key = "${download.serverId}-${download.userId}-${download.itemId}"
        if (download.path != "smart-offline/$key" || stream.index !in 0..MAX_SUBTITLE_INDEX) return null
        val name = managedSubtitleName(key, stream.index)
        return files.firstOrNull {
            it.downloadId == download.id && it.type == DownloadFileType.SUBTITLE && it.fileName == name &&
                it.status == DownloadStatus.DOWNLOADED && it.size in 1..MAX_OFFLINE_SUBTITLE_BYTES &&
                ownedFile(it, name) && verifyFile(it)
        }
    }

    private fun ownedFile(entry: DownloadFileEntity, name: String): Boolean = try {
        val root = File(context.filesDir, "smart-offline").canonicalFile
        entry.uri.scheme == "file" && entry.uri.authority.isNullOrEmpty() &&
            entry.uri.path?.let { File(it).canonicalFile == File(root, name) } == true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
