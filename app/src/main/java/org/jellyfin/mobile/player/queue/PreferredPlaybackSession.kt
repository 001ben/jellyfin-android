package org.jellyfin.mobile.player.queue

import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.downloads.smart.OfflinePlaybackReporter
import org.jellyfin.mobile.downloads.smart.SmartOfflineManager
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.interaction.PlayerWebPreferences
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.LocalPlaybackCandidate
import org.jellyfin.mobile.player.source.LocalPlaybackResolver
import org.jellyfin.mobile.player.source.PlaybackDetails
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.systemApi
import java.io.IOException
import java.util.UUID

/** Identity and source policy shared by initialization and queue navigation. */
internal class PreferredPlaybackSession(
    private val preferences: AppPreferences,
    private val api: ApiClient,
    private val resolver: LocalPlaybackResolver,
    offline: SmartOfflineManager,
    private val local: suspend (LocalPlaybackCandidate, PlaybackRequest) -> PlayerException?,
    private val remote: suspend (PlaybackRequest, Int?) -> PlayerException?,
) {
    private val reporter = OfflinePlaybackReporter(offline)
    private var account: PlaybackAccount? = null
    private var downloadsOnly = false
    private var webPreferences: PlayerWebPreferences? = null
    private var streamingBitrate: Int? = null
    private var bitrateResolved = false

    fun initialize(options: PlayOptions, web: PlayerWebPreferences?): PlayerException? {
        account = null
        val current = currentAccount() ?: return PlayerException.InvalidPlayOptions()
        val matches = (options.accountServerId == null || options.accountServerId == current.server) &&
            (options.accountUserId == null || options.accountUserId == current.user)
        if (!matches) return PlayerException.InvalidPlayOptions()
        account = current
        downloadsOnly = options.playFromDownloads == true
        webPreferences = web
        bitrateResolved = false
        streamingBitrate = null
        return null
    }

    fun isCurrent(): Boolean = account != null && account == currentAccount()

    suspend fun start(request: PlaybackRequest, localOnly: Boolean = downloadsOnly): PlayerException? {
        val current = account?.takeIf { isCurrent() } ?: return PlayerException.InvalidPlayOptions()
        return preferLocalPlayback(
            downloadsOnly = localOnly,
            resolve = { resolver.resolve(request.itemId, current.server, current.user, request.mediaSourceId) },
            local = { if (isCurrent()) local(it, request) else PlayerException.InvalidPlayOptions() },
            remote = { if (isCurrent()) remote(request, resolveBitrate()) else PlayerException.InvalidPlayOptions() },
        )
    }

    fun finishLocal(source: JellyfinMediaSource?, player: Player?) {
        reporter.reportStop(source, player)
    }

    fun changeBitrate(bitrate: Int?) {
        streamingBitrate = bitrate
        bitrateResolved = true
    }

    private fun currentAccount(): PlaybackAccount? {
        val server = preferences.currentServerId ?: return null
        val user = preferences.currentUserId ?: return null
        return PlaybackAccount(server, user)
    }

    private suspend fun resolveBitrate(): Int? {
        if (!bitrateResolved) {
            val web = webPreferences
            streamingBitrate = if (web == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        val endpoint by api.systemApi.getEndpointInfo()
                        if (endpoint.isInNetwork) web.maxStreamingBitrateLocal else web.maxStreamingBitrateRemote
                    } catch (_: ApiClientException) {
                        web.maxStreamingBitrateRemote
                    } catch (_: IOException) {
                        web.maxStreamingBitrateRemote
                    }
                }
            }
            bitrateResolved = true
        }
        return streamingBitrate
    }
}

private data class PlaybackAccount(val server: Long, val user: Long)

internal data class PlaybackRequest(
    val itemId: UUID,
    val mediaSourceId: String? = null,
    val details: PlaybackDetails = PlaybackDetails(null, null, null),
    val playWhenReady: Boolean = true,
)
