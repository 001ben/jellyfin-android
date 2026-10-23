package org.jellyfin.mobile.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import org.jellyfin.mobile.player.source.ExternalSubtitleStream
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.mobile.utils.clearSelectionAndDisableRendererByType
import org.jellyfin.mobile.utils.selectTrackByTypeAndGroup
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

/** Commit remembered selection only after applying it; retrying a selected track must repair disabled renderers. */
internal fun applySubtitleTrackSelection(
    source: JellyfinMediaSource,
    stream: MediaStream?,
    player: Player?,
    selector: DefaultTrackSelector,
): Boolean {
    if (player == null || stream != null && source.subtitleStreams.none { it === stream }) return false
    if (stream == null) {
        selector.clearSelectionAndDisableRendererByType(C.TRACK_TYPE_TEXT)
        return source.selectSubtitleStream(null)
    }
    val delivery = when {
        source !is LocalJellyfinMediaSource -> stream.deliveryMethod
        stream.isExternal -> SubtitleDeliveryMethod.EXTERNAL
        else -> SubtitleDeliveryMethod.EMBED
    }
    if (delivery == SubtitleDeliveryMethod.ENCODE) return source.selectSubtitleStream(stream)
    val group = subtitleGroup(source, stream, player, delivery) ?: return false
    // Even the same remembered index may need to re-enable text after an interrupted/preparation-time selection.
    if (!selector.selectTrackByTypeAndGroup(C.TRACK_TYPE_TEXT, group)) return false
    return source.selectSubtitleStream(stream)
}

private fun subtitleGroup(
    source: JellyfinMediaSource,
    stream: MediaStream,
    player: Player,
    delivery: SubtitleDeliveryMethod?,
): TrackGroup? = when (delivery) {
    SubtitleDeliveryMethod.EMBED ->
        player.currentTracks.groups.getOrNull(source.getEmbeddedStreamIndex(stream))
            ?.takeIf { it.type == C.TRACK_TYPE_TEXT }?.mediaTrackGroup
    SubtitleDeliveryMethod.EXTERNAL -> player.currentTracks.groups.firstOrNull { group ->
        group.type == C.TRACK_TYPE_TEXT && group.getTrackFormat(0).id
            ?.substringAfter(ExternalSubtitleStream.ID_PREFIX, "") == stream.index.toString()
    }?.mediaTrackGroup
    else -> null
}
