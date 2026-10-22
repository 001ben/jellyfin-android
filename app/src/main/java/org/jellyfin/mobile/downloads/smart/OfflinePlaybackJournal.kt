package org.jellyfin.mobile.downloads.smart

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val playbackSequence = AtomicLong()
internal fun nextOfflineSequence(): Long = playbackSequence.incrementAndGet()

data class OfflinePlaybackSnapshot(
    val downloadId: Long,
    val position: Long,
    val finished: Boolean,
    val stopped: Boolean,
    val eventSequence: Long = nextOfflineSequence(),
    val session: String? = null,
)

internal class OfflinePlaybackJournal(
    private val context: Context,
    private val downloads: DownloadDao,
    private val dao: SmartOfflineDao,
) {
    private val mutex = Mutex()
    private val paths = SmartOfflinePaths(context)
    private val generation = UUID.randomUUID().toString()
    private val latestSequence = mutableMapOf<String, Long>()
    private val playing = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val active = MutableStateFlow<Set<Long>>(emptySet())
    val activeDownloads = active.asStateFlow()
    private val sessions = mutableMapOf<Long, String>()

    fun isPlaying(id: Long): Boolean = id in playing

    suspend fun <T> withPlaybackLease(action: suspend () -> T): T = mutex.withLock { action() }

    suspend fun abandon(id: Long, session: String) = withPlaybackLease {
        if (sessions[id] == session) {
            sessions.remove(id)
            playing.remove(id)
            active.value = playing.toSet()
        }
    }

    suspend fun started(id: Long, session: String): Boolean = withPlaybackLease {
        val download = downloads.getDownload(id) ?: return@withPlaybackLease false
        val owned = dao.owned(id)
        if (download.path.startsWith("smart-offline/") && owned == null) return@withPlaybackLease false
        if (owned != null && !ownedMatches(owned, download)) return@withPlaybackLease false
        if (owned != null && !complete(owned)) return@withPlaybackLease false
        playing.add(id)
        active.value = playing.toSet()
        sessions[id] = session
        true
    }

    private fun complete(entry: SmartOfflineManaged): Boolean =
        entry.bytes > 0 && paths.file(entry).length() == entry.bytes

    private fun ownedMatches(entry: SmartOfflineManaged, download: DownloadEntity): Boolean {
        if (entry.account != "${download.serverId}-${download.userId}") return false
        return entry.item.id == download.itemId && download.path == "smart-offline/${entry.key}"
    }

    suspend fun prepareTransfer(entry: SmartOfflineManaged, localId: Long?): SmartOfflineManaged? = withPlaybackLease {
        if (localId != null && isPlaying(localId)) return@withPlaybackLease null
        // The published URI may already exist during repair. It must not become playable mid-transfer by size alone.
        entry.copy(bytes = 0).also { dao.save(it) }
    }

    suspend fun record(snapshot: OfflinePlaybackSnapshot) = withContext(Dispatchers.IO) {
        withPlaybackLease {
            val download = downloads.getDownload(snapshot.downloadId) ?: return@withPlaybackLease
            val key = "${download.serverId}-${download.userId}-${download.itemId}-${download.id}"
            val previous = matchingProgress(download)
            if (!outdated(key, snapshot, previous)) {
                persist(download, snapshot, previous?.finished == true)
                latestSequence[key] = snapshot.eventSequence
            }
            finishPlayback(snapshot)
        }
    }

    private suspend fun matchingProgress(download: DownloadEntity): OfflineProgress? =
        dao.progress(download.id)?.takeIf {
            it.serverId == download.serverId && it.userId == download.userId && it.itemId == download.itemId
        }

    private fun outdated(key: String, snapshot: OfflinePlaybackSnapshot, previous: OfflineProgress?): Boolean {
        if ((latestSequence[key] ?: -1) >= snapshot.eventSequence) return true
        return previous?.generation == generation && previous.sequence >= snapshot.eventSequence
    }

    private suspend fun persist(download: DownloadEntity, snapshot: OfflinePlaybackSnapshot, wasFinished: Boolean) {
        dao.save(
            OfflineProgress(
                download.id, download.serverId, download.userId, download.itemId,
                snapshot.position.coerceAtLeast(0), snapshot.finished || wasFinished,
                UUID.randomUUID().toString(), generation, snapshot.eventSequence,
            ),
        )
        val position = if (snapshot.finished) 0 else snapshot.position.coerceAtLeast(0)
        val old = download.item.userData
        val data = old?.copy(playbackPositionTicks = position, played = old.played || snapshot.finished)
            ?: UserItemDataDto(
                key = download.itemId.toString(), itemId = download.itemId, playbackPositionTicks = position,
                played = snapshot.finished, playCount = 0, isFavorite = false,
            )
        downloads.update(download.copy(item = download.item.copy(userData = data)))
    }

    private fun finishPlayback(snapshot: OfflinePlaybackSnapshot) {
        if (!snapshot.stopped) return
        if (snapshot.session != null && sessions[snapshot.downloadId] != snapshot.session) return
        sessions.remove(snapshot.downloadId)
        playing.remove(snapshot.downloadId)
        active.value = playing.toSet()
        OfflineProgressWorker.enqueue(context)
    }
}
