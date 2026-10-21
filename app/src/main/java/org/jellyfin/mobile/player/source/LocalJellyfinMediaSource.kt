package org.jellyfin.mobile.player.source

import android.net.Uri
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlayMethod
import java.util.UUID

data class LocalDownloadPlayback(val id: Long, val uri: Uri)

class LocalJellyfinMediaSource(
    itemId: UUID,
    item: BaseItemDto?,
    sourceInfo: MediaSourceInfo,
    playSessionId: String,
    playbackDetails: PlaybackDetails? = null,
    private val download: LocalDownloadPlayback,
) : JellyfinMediaSource(itemId, item, sourceInfo, playSessionId, playbackDetails) {
    val remoteFileUri: Uri get() = download.uri
    val downloadId: Long get() = download.id
    override val playMethod: PlayMethod = PlayMethod.DIRECT_PLAY
}
