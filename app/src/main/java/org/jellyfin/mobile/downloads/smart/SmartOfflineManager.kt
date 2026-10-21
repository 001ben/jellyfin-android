package org.jellyfin.mobile.downloads.smart

import android.content.Context
import androidx.core.net.toUri
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SmartOfflineManager(
    private val context: Context,
    private val database: JellyfinDatabase,
    private val dao: SmartOfflineDao,
    private val users: UserDao,
    private val controller: ApiClientController,
    private val preferences: AppPreferences,
    private val endpoints: SmartOfflineEndpoint,
) {
    private val downloads = database.downloadDao
    private val transfer = SmartOfflineTransfer()
    private val mutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val generation = UUID.randomUUID().toString()
    private val sequence = AtomicLong()
    private val latestSequence = mutableMapOf<Long, Long>()
    private val playing = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val playbackSessions = mutableMapOf<Long, String>()
    private val root get() = File(context.filesDir, "smart-offline").also { check(it.exists() || it.mkdirs()) }
    private fun file(entry: SmartOfflineManaged): File {
        require(entry.key.matches(Regex("[0-9]+-[0-9]+-[0-9a-f-]{36}")))
        return File(root, entry.key)
    }

    suspend fun current(): SmartOfflineAccount? = withContext(Dispatchers.IO) {
        val server = preferences.currentServerId ?: return@withContext null
        val user = preferences.currentUserId ?: return@withContext null
        dao.account("$server-$user") ?: SmartOfflineAccount("$server-$user", server, user)
    }

    suspend fun save(account: SmartOfflineAccount) = withContext(Dispatchers.IO) {
        require(account.nextCount in 1..20 && account.capGiB in 1..100)
        if (account.lanUrl.isNotBlank()) SmartOfflinePolicy.lanUrl(account.lanUrl)
        dao.save(account.copy(serverIdentity = dao.account(account.key)?.serverIdentity.orEmpty()))
        SmartOfflineWorker.schedule(context, account)
    }

    suspend fun series(account: SmartOfflineAccount) = dao.series(account.key)

    suspend fun search(account: SmartOfflineAccount, query: String): List<BaseItemDto> = withContext(Dispatchers.IO) {
        val user = users.getUser(account.userId) ?: return@withContext emptyList()
        val result by controller.getApiClient(account.serverId, account.userId).itemsApi.getItems(
            userId = user.userId,
            searchTerm = query,
            includeItemTypes = setOf(BaseItemKind.SERIES),
            recursive = true,
            limit = 50,
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

    suspend fun started(downloadId: Long, session: String = ""): Boolean = lifecycleMutex.withLock {
        val download = downloads.getDownload(downloadId) ?: return@withLock false
        val owned = dao.owned(downloadId)
        if (download.path.startsWith("smart-offline/") && owned == null) return@withLock false
        if (owned?.let { file(it).length() != it.bytes || it.bytes <= 0 } == true) return@withLock false
        playing.add(download.id)
        playbackSessions[downloadId] = session
        true
    }

    fun nextSequence(): Long = sequence.incrementAndGet()

    suspend fun record(
        downloadId: Long,
        position: Long,
        finished: Boolean,
        stopped: Boolean,
        eventSequence: Long = nextSequence(),
        session: String? = null,
    ) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            val download = downloads.getDownload(downloadId) ?: return@withContext
            if ((latestSequence[downloadId] ?: -1) >= eventSequence) {
                finishPlayback(downloadId, session, stopped)
                return@withContext
            }
            val previous = dao.progress(downloadId)?.takeIf {
                it.serverId == download.serverId && it.userId == download.userId && it.itemId == download.itemId
            }
            if (previous?.generation == generation && previous.sequence >= eventSequence) {
                finishPlayback(downloadId, session, stopped)
                return@withContext
            }
            val wasFinished = previous?.finished == true
            dao.save(
                OfflineProgress(
                    downloadId, download.serverId, download.userId, download.itemId,
                    position.coerceAtLeast(
                        0
                    ),
                    finished || wasFinished, UUID.randomUUID().toString(), generation, eventSequence
                )
            )
            latestSequence[downloadId] = eventSequence
            val old = download.item.userData
            val data = old?.copy(playbackPositionTicks = if (finished) 0 else position, played = old.played || finished)
                ?: UserItemDataDto(
                    key = download.itemId.toString(), itemId = download.itemId, playbackPositionTicks = if (finished) 0 else position,
                    played = finished, playCount = 0, isFavorite = false,
                )
            downloads.update(download.copy(item = download.item.copy(userData = data)))
            finishPlayback(downloadId, session, stopped)
        }
    }

    private fun finishPlayback(downloadId: Long, session: String?, stopped: Boolean) {
        if (!stopped || (session != null && playbackSessions[downloadId] != session)) return
        playbackSessions.remove(downloadId)
        playing.remove(downloadId)
        OfflineProgressWorker.enqueue(context)
    }

    /** Outbox entries survive authentication errors and outages; no retry count silently loses watched state. */
    suspend fun syncProgress() = withContext(Dispatchers.IO) {
        var failure: Exception? = null
        for (entry in dao.pending()) {
            try {
                if (entry.downloadId in playing) continue
                val user = users.getUser(entry.userId) ?: continue
                if (user.serverId != entry.serverId) continue
                if (user.accessToken == null) continue
                val remoteApi = controller.getApiClient(entry.serverId, entry.userId)
                val settings = dao.account("${entry.serverId}-${entry.userId}")
                val api = if (settings != null && settings.serverIdentity.isNotBlank() && settings.lanUrl.isNotBlank()) {
                    // Playback synchronization is small metadata traffic; download fallback policy doesn't disable it.
                    val endpoint = endpoints.resolve(
                        settings.copy(wifiOnly = false, remoteFallback = true),
                        requireNotNull(remoteApi.baseUrl)
                    )
                    scopedApi(remoteApi, endpoint)
                } else {
                    remoteApi
                }
                if (!entry.stopReported) {
                    api.playStateApi.reportPlaybackStopped(
                        PlaybackStopInfo(itemId = entry.itemId, positionTicks = entry.position, failed = false)
                    )
                    dao.stopped(entry.downloadId, entry.revision)
                }
                if (entry.finished) api.playStateApi.markPlayedItem(itemId = entry.itemId)
                dao.acknowledged(entry.downloadId, entry.revision)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
            }
        }
        if (failure != null) throw IOException("Some playback events are pending", failure)
    }

    suspend fun reconcile(key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            var account = dao.account(key) ?: return@withLock
            if (!account.enabled) return@withLock
            val user = users.getUser(account.userId) ?: return@withLock
            if (user.serverId != account.serverId || user.accessToken == null) {
                dao.status(key, "Sign in to resume smart downloads")
                return@withLock
            }
            val remoteApi = controller.getApiClient(account.serverId, account.userId)
            if (!endpoints.allowed(account.wifiOnly)) throw IOException("Waiting for permitted network")
            if (account.serverIdentity.isBlank()) {
                dao.pinIdentity(key, endpoints.identity(requireNotNull(remoteApi.baseUrl)))
                account = dao.account(key) ?: return@withLock
            }
            if (!account.enabled) return@withLock
            val endpoint = endpoints.resolve(account, requireNotNull(remoteApi.baseUrl))
            val api = scopedApi(remoteApi, endpoint)
            dao.status(
                key,
                if (endpoint.lan) "Using verified direct Wi-Fi endpoint" else "Using configured remote endpoint"
            )
            // A pending local watch event always blocks cleanup, including when reconciliation races synchronization.
            refreshAndClean(account, api)
            var storageWaiting = false
            for (series in dao.series(key)) {
                val episodes = mutableListOf<BaseItemDto>()
                var start = 0
                do {
                    val response by api.itemsApi.getItems(
                        userId = user.userId,
                        parentId = UUID.fromString(series.seriesId),
                        recursive = true,
                        includeItemTypes = setOf(BaseItemKind.EPISODE),
                        fields = setOf(ItemFields.MEDIA_SOURCES, ItemFields.PATH),
                        startIndex = start,
                        limit = 200,
                    )
                    episodes += response.items
                    start += response.items.size
                } while (response.items.isNotEmpty() && start < response.totalRecordCount)
                val finished = dao.pending().filter {
                    it.finished && it.serverId == account.serverId && it.userId == account.userId
                }.map { it.itemId }.toSet()
                val next = SmartOfflinePolicy.nextEpisodes(episodes, account.nextCount, finished)
                for (item in next) if (!download(account, endpoint, api, item)) storageWaiting = true
            }
            dao.status(
                key,
                if (storageWaiting) {
                    "Waiting for storage; unknown-size episodes are skipped"
                } else {
                    "Up to date; ${dao.managed(key).count { it.downloadId != null }} managed episodes"
                }
            )
        }
    }

    private fun scopedApi(remote: ApiClient, endpoint: VerifiedDownloadEndpoint): ApiClient {
        val factory = OkHttpFactory(endpoint.client)
        return factory.create(
            endpoint.url.toString(),
            remote.accessToken,
            remote.clientInfo,
            remote.deviceInfo,
            remote.httpClientOptions.copy(followRedirects = false),
            factory
        )
    }

    private suspend fun refreshAndClean(account: SmartOfflineAccount, api: ApiClient) {
        for (chunk in dao.managed(account.key).chunked(50)) {
            val response by api.itemsApi.getItems(ids = chunk.map { it.item.id })
            for (entry in chunk) {
                val remote = response.items.firstOrNull { it.id == entry.item.id } ?: continue
                val id = entry.downloadId
                if (id == null) {
                    if (account.cleanup && remote.userData?.played == true) {
                        removeOwned(
                            entry,
                            deleteFile = true,
                            automatic = true
                        )
                    }
                    continue
                }
                val clean = lifecycleMutex.withLock {
                    val local = downloads.getDownload(id) ?: return@withLock false
                    if (!owns(entry, account, local)) return@withLock false
                    val pending = dao.progress(id) != null
                    if (!pending && id !in playing) {
                        downloads.update(local.copy(item = local.item.copy(userData = remote.userData)))
                    }
                    SmartOfflinePolicy.canClean(
                        account.cleanup,
                        true,
                        remote.userData?.played == true,
                        pending,
                        id in playing
                    )
                }
                if (clean) removeOwned(entry, deleteFile = true, automatic = true)
            }
        }
    }

    private suspend fun download(account: SmartOfflineAccount, endpoint: VerifiedDownloadEndpoint, api: ApiClient, item: BaseItemDto): Boolean {
        val latest = dao.account(account.key) ?: return true
        if (!latest.enabled || latest.copy(status = "") != account.copy(status = "")) {
            throw IOException(
                "Settings changed"
            )
        }
        val key = "${account.key}-${item.id}"
        var entry = dao.managed(account.key).firstOrNull { it.key == key }
        val existing = downloads.getDownloadsByItemIds(listOf(item.id)).firstOrNull {
            it.serverId == account.serverId && it.userId == account.userId
        }
        if (existing != null && existing.path != "smart-offline/$key") return true // Never adopt manual downloads.
        if (entry?.downloadId != null && existing != null && file(entry).length() == entry.bytes && entry.bytes > 0) return true
        if (entry == null) {
            entry = SmartOfflineManaged(key, account.key, item)
            dao.save(entry)
        }
        val current = entry
        val target = file(current)
        val used = root.listFiles().orEmpty().sumOf { if (it == target) 0 else it.length() }
        val size = item.mediaSources?.firstOrNull()?.size ?: 0
        val budget = account.capGiB * SmartOfflinePolicy.GIB - used
        if (!SmartOfflinePolicy.fits(
                size,
                used,
                account.capGiB * SmartOfflinePolicy.GIB,
                root.usableSpace + target.length()
            )
        ) {
            dao.status(account.key, "Waiting for storage; unknown-size episodes are skipped")
            return false
        }
        var lastPolicyCheck = 0L
        var settingsAllowed = true
        val bytes = transfer.download(endpoint, api, current, target, budget, permitted = {
            val now = System.nanoTime()
            if (now - lastPolicyCheck >= 1_000_000_000L) {
                val settings = dao.account(account.key)
                settingsAllowed = settings?.enabled == true && settings.copy(status = "") == account.copy(status = "") &&
                    users.getUser(account.userId)?.accessToken == api.accessToken
                lastPolicyCheck = now
            }
            settingsAllowed && endpoints.allowed(endpoint, account.wifiOnly)
        }, saveValidator = { dao.save(current.copy(etag = it)) })
        val downloadId = database.withTransaction {
            val id = existing?.id ?: downloads.insert(
                DownloadEntity(
                    serverId = account.serverId, userId = account.userId, itemId = item.id,
                    path = "smart-offline/$key", item = item, status = DownloadStatus.DOWNLOADED
                ),
            )
            if (existing != null) downloads.update(existing.copy(item = item, status = DownloadStatus.DOWNLOADED))
            val oldFile = downloads.getFiles(id).firstOrNull { it.type == DownloadFileType.ITEM }
            if (oldFile == null) {
                downloads.insertFile(
                    DownloadFileEntity(
                        downloadId = id,
                        type = DownloadFileType.ITEM,
                        size = bytes,
                        fileName = key,
                        uri = target.toUri(),
                        status = DownloadStatus.DOWNLOADED
                    )
                )
            } else {
                downloads.updateFile(
                    oldFile.copy(size = bytes, uri = target.toUri(), status = DownloadStatus.DOWNLOADED)
                )
            }
            id
        }
        dao.save(current.copy(downloadId = downloadId, bytes = bytes, etag = null))
        return true
    }

    suspend fun remove(id: Long, deleteFile: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entry = dao.owned(id) ?: return@withLock false
            removeOwned(entry, deleteFile)
            true
        }
    }

    suspend fun resume(id: Long): Boolean {
        val entry = dao.owned(id) ?: return false
        dao.account(entry.account)?.let { SmartOfflineWorker.schedule(context, it) }
        return true
    }

    private fun owns(entry: SmartOfflineManaged, account: SmartOfflineAccount, local: DownloadEntity): Boolean =
        local.path == "smart-offline/${entry.key}" && local.serverId == account.serverId &&
            local.userId == account.userId && local.itemId == entry.item.id

    private suspend fun removeOwned(entry: SmartOfflineManaged, deleteFile: Boolean, automatic: Boolean = false) {
        lifecycleMutex.withLock {
            val account = dao.account(entry.account) ?: return@withLock
            val local = entry.downloadId?.let {
                downloads.getDownload(it)
            } ?: downloads.getDownloadsByItemIds(listOf(entry.item.id))
                .firstOrNull {
                    it.path == "smart-offline/${entry.key}" && it.serverId == account.serverId && it.userId == account.userId
                }
            if (local != null && !owns(entry, account, local)) return@withLock
            if (local?.id?.let { it in playing || dao.progress(it) != null } == true) return
            if (automatic && dao.account(entry.account)?.let { it.enabled && it.cleanup } != true) return
            val target = file(entry)
            if (deleteFile && target.exists() && !target.delete()) throw IOException("Cannot delete managed local file")
            local?.id?.let { downloads.delete(it) }
            // Keeping a file retains ownership and budget accounting; it is never swept as an unrelated file.
            if (deleteFile) {
                dao.forget(entry.key)
            } else {
                dao.save(entry.copy(downloadId = null))
            }
        }
    }
}
