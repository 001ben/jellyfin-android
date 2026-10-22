package org.jellyfin.mobile.downloads.smart

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.jellyfin.mobile.data.dao.DownloadDao

private const val CATALOG_REFRESH_MILLIS = 1_000L

/** Collection reads local state only: it never schedules work, probes a server, or creates files. */
internal class SmartOfflineCatalogObserver(
    context: Context,
    private val dao: SmartOfflineDao,
    private val downloads: DownloadDao,
    private val journal: OfflinePlaybackJournal,
    private val transfers: Flow<Set<String>>,
) {
    private val paths = SmartOfflinePaths(context)

    fun observe(server: Long, user: Long): Flow<List<SmartOfflineCatalogItem>> {
        val key = "$server-$user"
        val records = combine(
            dao.observeAccount(key),
            dao.observeSeries(key),
            dao.observeManaged(key),
            dao.observeRemovals(key),
            dao.observePending(server, user),
        ) { account, series, managed, removals, pending ->
            SmartOfflineCatalogRecords(
                account ?: SmartOfflineAccount(key, server, user),
                series,
                managed,
                removals,
                pending
            )
        }
        val local = downloads.getAllDownloads().map { rows ->
            rows.filter { it.serverId == server && it.userId == user }
        }
        return combine(records, local, journal.activeDownloads, transfers, ticks()) { saved, rows, playing, active, _ ->
            val lengths = saved.managed.filter { it.account == key }.associate { entry ->
                entry.key to runCatching { paths.readFile(entry).takeIf { it.isFile }?.length() ?: 0 }.getOrDefault(0)
            }
            smartOfflineCatalog(saved, SmartOfflineCatalogFiles(rows, lengths, playing, active))
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(CATALOG_REFRESH_MILLIS)
        }
    }
}
