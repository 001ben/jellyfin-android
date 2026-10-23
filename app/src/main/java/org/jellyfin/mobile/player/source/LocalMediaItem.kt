package org.jellyfin.mobile.player.source

import androidx.core.net.toUri
import androidx.media3.common.MediaItem

/** Shared by the native queue and emulator acceptance test. All resources come from verified local files. */
internal fun LocalJellyfinMediaSource.localMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(remoteFileUri)
    .setCustomCacheKey(remoteFileUri.toString())
    .setSubtitleConfigurations(
        externalSubtitleStreams.map { stream ->
            val uri = stream.deliveryUrl.toUri()
            require(uri.scheme in setOf("file", "content"))
            MediaItem.SubtitleConfiguration.Builder(uri)
                .setId("${ExternalSubtitleStream.ID_PREFIX}${stream.index}")
                .setLabel(stream.displayTitle).setLanguage(stream.language).setMimeType(stream.mimeType).build()
        }
    ).build()
