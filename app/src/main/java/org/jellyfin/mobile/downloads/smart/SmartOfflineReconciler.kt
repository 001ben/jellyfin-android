package org.jellyfin.mobile.downloads.smart

import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import java.io.IOException
import java.util.UUID

private const val EPISODE_PAGE_SIZE = 200

internal class SmartOfflineReconciler(
    private val dao: SmartOfflineDao,
    private val users: UserDao,
    private val controller: ApiClientController,
    private val endpoints: SmartOfflineEndpoint,
    private val store: SmartOfflineDownloadStore,
) {
    suspend fun reconcile(key: String) {
        val saved = dao.account(key)?.takeIf { it.enabled } ?: return
        // A failed refresh must not leave an earlier confirmation displayed as current cleanup eligibility.
        dao.invalidateWatchConfirmation(key)
        val user = users.getUser(saved.userId) ?: return
        if (user.serverId != saved.serverId || user.accessToken == null) {
            dao.status(key, "Sign in to resume smart downloads")
            return
        }
        val remote = controller.getApiClient(saved.serverId, saved.userId)
        val account = pinIdentity(saved, remote) ?: return
        val endpoint = endpoints.resolve(account, requireNotNull(remote.baseUrl))
        val api = scopedDownloadApi(remote, endpoint)
        dao.status(key, endpointStatus(endpoint))
        val watchConfirmed = refreshWatched(account, endpoint, remote, api)
        val waiting = replenish(account, user, endpoint, api)
        val message = if (waiting) {
            "Waiting for storage; unknown-size episodes are skipped"
        } else {
            "Up to date; ${dao.managed(key).count { it.downloadId != null }} managed episodes"
        }
        val watchStatus = if (watchConfirmed) "" else "; watch synchronization pending"
        dao.status(key, message + watchStatus)
    }

    private suspend fun refreshWatched(
        account: SmartOfflineAccount,
        endpoint: VerifiedDownloadEndpoint,
        remote: ApiClient,
        api: ApiClient,
    ): Boolean {
        if (!endpoint.trustedHttpLan) {
            store.refreshWatched(account, api)
            return true
        }
        // HTTP trust permits media transfer, never authoritative watch confirmation or insecure cleanup fallback.
        return try {
            store.refreshWatched(account, remote)
            true
        } catch (_: IOException) {
            false
        } catch (_: ApiClientException) {
            false
        }
    }

    private fun endpointStatus(endpoint: VerifiedDownloadEndpoint): String = when {
        endpoint.trustedHttpLan -> "Using explicitly trusted HTTP over direct Wi-Fi"
        endpoint.lan -> "Using verified direct Wi-Fi endpoint"
        else -> "Using configured remote endpoint"
    }

    private suspend fun pinIdentity(account: SmartOfflineAccount, remote: ApiClient): SmartOfflineAccount? {
        if (!endpoints.allowed(account.wifiOnly)) throw IOException("Waiting for permitted network")
        if (account.serverIdentity.isNotBlank()) return account
        dao.pinIdentity(account.key, endpoints.identity(requireNotNull(remote.baseUrl)))
        return dao.account(account.key)?.takeIf { it.enabled }
    }

    private suspend fun replenish(
        account: SmartOfflineAccount,
        user: UserEntity,
        endpoint: VerifiedDownloadEndpoint,
        api: ApiClient,
    ): Boolean {
        var waiting = false
        val planned = mutableListOf<BaseItemDto>()
        for (series in dao.series(account.key)) {
            val episodes = episodes(api, user, series)
            val finished = dao.pending().filter {
                it.finished && it.serverId == account.serverId && it.userId == account.userId
            }.map { it.itemId }.toSet()
            planned += SmartOfflinePolicy.nextEpisodes(episodes, account.nextCount, finished).map {
                it.copy(seriesId = UUID.fromString(series.seriesId))
            }
        }
        store.plan(account, planned)
        var subtitlesPending = false
        for (item in planned) {
            try {
                if (!store.download(account, endpoint, api, item)) waiting = true
            } catch (_: OfflineSubtitlesPending) {
                subtitlesPending = true // A missing subtitle must not starve the remaining video downloads.
            }
        }
        if (subtitlesPending) throw OfflineSubtitlesPending()
        return waiting
    }

    private suspend fun episodes(api: ApiClient, user: UserEntity, series: SmartOfflineSeries): List<BaseItemDto> {
        val result = mutableListOf<BaseItemDto>()
        var start = 0
        do {
            val response by api.itemsApi.getItems(
                userId = user.userId,
                parentId = UUID.fromString(series.seriesId),
                recursive = true,
                includeItemTypes = setOf(BaseItemKind.EPISODE),
                fields = setOf(ItemFields.MEDIA_SOURCES, ItemFields.PATH),
                startIndex = start,
                limit = EPISODE_PAGE_SIZE,
            )
            result += response.items
            start += response.items.size
        } while (response.items.isNotEmpty() && start < response.totalRecordCount)
        return result
    }
}
