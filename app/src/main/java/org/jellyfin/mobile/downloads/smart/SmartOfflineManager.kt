package org.jellyfin.mobile.downloads.smart

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.sdk.model.api.BaseItemDto

data class SmartOfflinePersistence(val database: JellyfinDatabase, val dao: SmartOfflineDao)

class SmartOfflineManager(
    private val context: Context,
    persistence: SmartOfflinePersistence,
    private val users: UserDao,
    private val controller: ApiClientController,
    endpoints: SmartOfflineEndpoint,
    private val schedule: suspend (SmartOfflineAccount) -> Unit = { SmartOfflineWorker.scheduleAndAwait(context, it) },
) {
    val library = SmartOfflineLibrary(users, controller)
    private val database = persistence.database
    private val dao = persistence.dao
    private val mutex = Mutex()
    private val journal = OfflinePlaybackJournal(context, database.downloadDao, dao)
    private val store = SmartOfflineDownloadStore(context, database, dao, users, endpoints, journal)
    private val reconciler = SmartOfflineReconciler(dao, users, controller, endpoints, store)
    private val sync = OfflinePlaybackSync(journal, dao, users, controller, endpoints)

    suspend fun current(server: Long?, user: Long?): SmartOfflineAccount? = withContext(Dispatchers.IO) {
        if (server == null || user == null) return@withContext null
        dao.account("$server-$user") ?: SmartOfflineAccount("$server-$user", server, user)
    }

    fun observeAccount(server: Long, user: Long): Flow<SmartOfflineAccount> = dao.observeAccount("$server-$user").map {
        it ?: SmartOfflineAccount("$server-$user", server, user)
    }

    fun observeSeries(key: String): Flow<List<SmartOfflineSeries>> = dao.observeSeries(key)

    fun observeCatalog(server: Long, user: Long): Flow<List<SmartOfflineCatalogItem>> =
        SmartOfflineCatalogObserver(
            context,
            dao,
            database.downloadDao,
            journal,
            store.activeTransfers
        ).observe(server, user)

    suspend fun save(account: SmartOfflineAccount): SmartOfflineSaveResult = withContext(Dispatchers.IO) {
        require(account.nextCount in SmartOfflinePolicy.MIN_EPISODES..SmartOfflinePolicy.MAX_EPISODES)
        require(account.capGiB in SmartOfflinePolicy.MIN_CAP_GIB..SmartOfflinePolicy.MAX_CAP_GIB)
        val saved = dao.saveSettings(validatedSettings(account))
        scheduleSaved(saved, schedule)
    }

    suspend fun series(account: SmartOfflineAccount): List<SmartOfflineSeries> = dao.series(account.key)

    suspend fun subscribe(
        account: SmartOfflineAccount,
        item: BaseItemDto,
        enabled: Boolean,
    ): SmartOfflineSaveResult = withContext(Dispatchers.IO) {
        dao.insertAccountIfMissing(SmartOfflineAccount(account.key, account.serverId, account.userId))
        val key = "${account.key}-${item.id}"
        if (enabled) {
            dao.subscribe(SmartOfflineSeries(key, account.key, item.id.toString(), item.name.orEmpty(), item))
        } else {
            dao.unsubscribe(key)
        }
        // A series click carries identity only. Its stale UI snapshot must not overwrite settings or worker reports.
        val current = requireNotNull(dao.account(account.key))
        scheduleSaved(current, schedule)
    }

    suspend fun started(id: Long, session: String = ""): Boolean = journal.started(id, session)
    suspend fun abandonPlayback(id: Long, session: String) = journal.abandon(id, session)
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
}

private fun validatedSettings(account: SmartOfflineAccount): SmartOfflineAccount {
    if (account.lanUrl.isBlank()) return account.copy(lanUrl = "", trustedHttpLanUrl = "")
    val canonical = SmartOfflinePolicy.lanUrl(account.lanUrl)
    val trustedHttp = if (canonical.startsWith("http://")) {
        require(
            SmartOfflinePolicy.isTrustedHttpLan(account)
        ) { "Confirm the exact HTTP home LAN endpoint before saving" }
        canonical
    } else {
        ""
    }
    return account.copy(lanUrl = canonical, trustedHttpLanUrl = trustedHttp)
}

private suspend fun scheduleSaved(
    account: SmartOfflineAccount,
    schedule: suspend (SmartOfflineAccount) -> Unit,
): SmartOfflineSaveResult = try {
    schedule(account)
    val feedback = if (account.enabled) {
        SmartOfflineSaveFeedback.SAVED_AND_QUEUED
    } else {
        SmartOfflineSaveFeedback.SAVED_AND_DISABLED
    }
    SmartOfflineSaveResult(account, feedback)
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    // Configuration is already durable. A failed scheduler operation must not be presented as a failed save.
    SmartOfflineSaveResult(account, SmartOfflineSaveFeedback.SAVED, schedulingFailed = true)
}
