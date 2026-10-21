package org.jellyfin.mobile.downloads.smart

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.sdk.model.extensions.inWholeTicks
import kotlin.time.Duration.Companion.milliseconds

class OfflinePlaybackReporter(private val manager: SmartOfflineManager) {
    suspend fun reportState(source: JellyfinMediaSource?, player: Player): Boolean {
        val local = source as? LocalJellyfinMediaSource ?: return false
        manager.record(
            OfflinePlaybackSnapshot(
                local.downloadId,
                player.currentPosition.milliseconds.inWholeTicks,
                player.playbackState == Player.STATE_ENDED,
                stopped = false,
                session = local.playSessionId,
            ),
        )
        return true
    }

    fun reportStop(source: JellyfinMediaSource?, player: Player?): Boolean {
        val local = source as? LocalJellyfinMediaSource ?: return false
        if (player == null) return true
        val finished = player.playbackState == Player.STATE_ENDED
        val position = if (finished) local.runTime.inWholeTicks else player.currentPosition.milliseconds.inWholeTicks
        val snapshot = OfflinePlaybackSnapshot(
            local.downloadId,
            position,
            finished,
            stopped = true,
            session = local.playSessionId,
        )
        // Player teardown may already have cancelled its viewModelScope; persist the captured snapshot independently.
        CoroutineScope(Dispatchers.IO).launch { manager.record(snapshot) }
        return true
    }
}
