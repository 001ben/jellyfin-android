package org.jellyfin.mobile.downloads.smart

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

class SmartOfflineManager(
    private val context: Context,
    database: JellyfinDatabase,
    private val dao: SmartOfflineDao,
    private val users: UserDao,
    private val controller: ApiClientController,
    endpoints: SmartOfflineEndpoint,
) {
    private val mutex = Mutex()
    private val journal = OfflinePlaybackJournal(context, database.downloadDao, dao)
    private val store = SmartOfflineDownloadStore(context, database, dao, users, endpoints, journal)
    private val reconciler = SmartOfflineReconciler(dao, users, controller, endpoints, store)
    private val sync = OfflinePlaybackSync(journal, dao, users, controller, endpoints)

    suspend fun current(server: Long?, user: Long?): SmartOfflineAccount? = withContext(Dispatchers.IO) {
        if (server == null || user == null) return@withContext null
        dao.account("$server-$user") ?: SmartOfflineAccount("$server-$user", server, user)
    }

    suspend fun save(account: SmartOfflineAccount) = withContext(Dispatchers.IO) {
        require(account.nextCount in SmartOfflinePolicy.MIN_EPISODES..SmartOfflinePolicy.MAX_EPISODES)
        require(account.capGiB in SmartOfflinePolicy.MIN_CAP_GIB..SmartOfflinePolicy.MAX_CAP_GIB)
        if (account.lanUrl.isNotBlank()) SmartOfflinePolicy.lanUrl(account.lanUrl)
        dao.save(account.copy(serverIdentity = dao.account(account.key)?.serverIdentity.orEmpty()))
        SmartOfflineWorker.schedule(context, account)
    }

    suspend fun series(account: SmartOfflineAccount): List<SmartOfflineSeries> = dao.series(account.key)

    suspend fun search(account: SmartOfflineAccount, query: String): List<BaseItemDto> = withContext(Dispatchers.IO) {
        val user = users.getUser(account.userId) ?: return@withContext emptyList()
        val result by controller.getApiClient(account.serverId, account.userId).itemsApi.getItems(
            userId = user.userId,
            searchTerm = query,
            includeItemTypes = setOf(BaseItemKind.SERIES),
            recursive = true,
            limit = SERIES_SEARCH_LIMIT,
        )
        result.items
    }

    suspend fun subscribe(account: SmartOfflineAccount, item: BaseItemDto, enabled: Boolean) {
        val key = "${account.key}-${item.id}"
        if (enabled) {
            dao.subscribe(SmartOfflineSeries(key, account.key, item.id.toString(), item.name.orEmpty()))
        } else {
            dao.unsubscribe(key)
        }
        save(account)
    }

    suspend fun started(id: Long, session: String = ""): Boolean = journal.started(id, session)
    fun nextSequence(): Long = nextOfflineSequence()
    suspend fun record(snapshot: OfflinePlaybackSnapshot) = journal.record(snapshot)
    suspend fun syncProgress() = sync.sync()

    suspend fun reconcile(key: String) = withContext(Dispatchers.IO) {
        mutex.withLock { reconciler.reconcile(key) }
    }

    suspend fun remove(id: Long, deleteFile: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entry = dao.owned(id) ?: return@withLock false
            store.remove(entry, deleteFile)
            true
        }
    }

    suspend fun resume(id: Long): Boolean {
        val entry = dao.owned(id) ?: return false
        dao.account(entry.account)?.let { SmartOfflineWorker.schedule(context, it) }
        return true
    }

    private companion object {
        const val SERIES_SEARCH_LIMIT = 50
    }
}
