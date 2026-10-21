package org.jellyfin.mobile.downloads.smart

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import java.io.IOException

internal fun scopedDownloadApi(remote: ApiClient, endpoint: VerifiedDownloadEndpoint): ApiClient {
    val factory = OkHttpFactory(endpoint.client)
    return factory.create(
        endpoint.url.toString(),
        remote.accessToken,
        remote.clientInfo,
        remote.deviceInfo,
        remote.httpClientOptions.copy(followRedirects = false),
        factory,
    )
}

internal class OfflinePlaybackSync(
    private val journal: OfflinePlaybackJournal,
    private val dao: SmartOfflineDao,
    private val users: UserDao,
    private val controller: ApiClientController,
    private val endpoints: SmartOfflineEndpoint,
) {
    /** Failed entries remain durable and do not starve the other accounts in this pass. */
    suspend fun sync() = withContext(Dispatchers.IO) {
        var failure: Exception? = null
        for (entry in dao.pending()) {
            val error = trySync(entry)
            if (error != null) failure = error
        }
        if (failure != null) throw IOException("Some playback events are pending", failure)
    }

    private suspend fun trySync(entry: OfflineProgress): Exception? = try {
        syncEntry(entry)
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: IOException) {
        error
    } catch (error: ApiClientException) {
        error
    } catch (error: IllegalArgumentException) {
        error
    } catch (error: IllegalStateException) {
        error
    }

    private suspend fun syncEntry(entry: OfflineProgress) {
        if (journal.isPlaying(entry.downloadId)) return
        val user = users.getUser(entry.userId) ?: return
        if (user.serverId != entry.serverId || user.accessToken == null) return
        val api = api(entry)
        if (!entry.stopReported) {
            api.playStateApi.reportPlaybackStopped(
                PlaybackStopInfo(itemId = entry.itemId, positionTicks = entry.position, failed = false),
            )
            dao.stopped(entry.downloadId, entry.revision)
        }
        if (entry.finished) api.playStateApi.markPlayedItem(itemId = entry.itemId)
        dao.acknowledged(entry.downloadId, entry.revision)
    }

    private suspend fun api(entry: OfflineProgress): ApiClient {
        val remote = controller.getApiClient(entry.serverId, entry.userId)
        val settings = dao.account("${entry.serverId}-${entry.userId}")
        if (settings == null || settings.serverIdentity.isBlank() || settings.lanUrl.isBlank()) return remote
        // Small watch-state metadata can use the remote endpoint independently of media-download policy.
        val endpoint = endpoints.resolve(
            settings.copy(wifiOnly = false, remoteFallback = true),
            requireNotNull(remote.baseUrl),
        )
        return scopedDownloadApi(remote, endpoint)
    }
}
