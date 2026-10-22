package org.jellyfin.mobile.downloads.smart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** User-triggered metadata browsing always captures the configured account's API client. */
class SmartOfflineLibrary(private val users: UserDao, private val controller: ApiClientController) {
    suspend fun search(account: SmartOfflineAccount, query: String): List<BaseItemDto> = withContext(Dispatchers.IO) {
        val user = users.getUser(account.userId)?.takeIf {
            it.serverId == account.serverId && it.accessToken != null
        } ?: return@withContext emptyList()
        val result by controller.getApiClient(account.serverId, account.userId).itemsApi.getItems(
            userId = user.userId,
            searchTerm = query.takeIf { it.isNotBlank() },
            includeItemTypes = setOf(BaseItemKind.SERIES),
            recursive = true,
            limit = SERIES_SEARCH_LIMIT,
        )
        result.items
    }

    suspend fun findSeries(account: SmartOfflineAccount, id: UUID): BaseItemDto? = withContext(Dispatchers.IO) {
        val user = users.getUser(account.userId)?.takeIf {
            it.serverId == account.serverId && it.accessToken != null
        }
            ?: return@withContext null
        val response by controller.getApiClient(account.serverId, account.userId).itemsApi.getItems(
            userId = user.userId,
            ids = listOf(id),
            includeItemTypes = setOf(BaseItemKind.SERIES),
        )
        response.items.firstOrNull { it.id == id && it.type == BaseItemKind.SERIES }
    }

    suspend fun artwork(
        account: SmartOfflineAccount,
        items: List<BaseItemDto>,
        allowNetwork: Boolean,
    ): Map<UUID, SmartOfflineArtwork> = withContext(Dispatchers.IO) {
        val user = users.getUser(account.userId)?.takeIf {
            it.serverId == account.serverId && it.accessToken != null
        } ?: return@withContext emptyMap()
        val api = controller.getApiClient(account.serverId, user.id)
        items.mapNotNull { item ->
            SmartOfflineArtwork.capture(account.key, api, item, allowNetwork)?.let { item.id to it }
        }.toMap()
    }

    private companion object {
        const val SERIES_SEARCH_LIMIT = 50
    }
}
