package org.jellyfin.mobile.downloads.smart

import android.content.Context
import androidx.core.net.toUri
import androidx.room.withTransaction
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val WATCH_REFRESH_BATCH = 50

internal class SmartOfflineDownloadStore(
    context: Context,
    private val database: JellyfinDatabase,
    private val dao: SmartOfflineDao,
    private val users: UserDao,
    private val endpoints: SmartOfflineEndpoint,
    private val journal: OfflinePlaybackJournal,
) {
    private val downloads = database.downloadDao
    private val paths = SmartOfflinePaths(context)
    private val transfer = SmartOfflineTransfer()

    suspend fun download(
        account: SmartOfflineAccount,
        endpoint: VerifiedDownloadEndpoint,
        api: ApiClient,
        item: BaseItemDto,
    ): Boolean {
        requireCurrentSettings(account)
        val key = "${account.key}-${item.id}"
        val existing = existingDownload(account, item.id)
        if (existing != null && existing.path != "smart-offline/$key") return true
        val entry = entry(account, item)
        if (complete(entry, existing)) return true
        val budget = budget(account, entry, item) ?: return false
        val pending = journal.prepareTransfer(entry, existing?.id) ?: throw IOException("Waiting for local playback")
        val bytes = transfer.download(
            SmartOfflineTransferRequest(endpoint, api, pending, paths.file(pending), budget),
            permitted = transferPolicy(account, endpoint, api),
            saveValidator = { dao.save(pending.copy(etag = it)) },
        )
        val id = register(account, entry, item, bytes)
        dao.save(entry.copy(downloadId = id, bytes = bytes, etag = null))
        return true
    }

    private suspend fun requireCurrentSettings(account: SmartOfflineAccount) {
        val latest = dao.account(account.key)
        if (latest?.enabled != true || latest.copy(status = "") != account.copy(status = "")) {
            throw IOException("Settings changed")
        }
    }

    private fun existingDownload(account: SmartOfflineAccount, item: UUID): DownloadEntity? =
        downloads.getDownloadsByItemIds(listOf(item)).firstOrNull {
            it.serverId == account.serverId && it.userId == account.userId
        }

    private suspend fun entry(account: SmartOfflineAccount, item: BaseItemDto): SmartOfflineManaged {
        val key = "${account.key}-${item.id}"
        val existing = dao.managed(account.key).firstOrNull { it.key == key }
        if (existing != null) return existing
        return SmartOfflineManaged(key, account.key, item).also { dao.save(it) }
    }

    private fun complete(entry: SmartOfflineManaged, existing: DownloadEntity?): Boolean {
        if (entry.downloadId == null || existing == null) return false
        if (entry.downloadId != existing.id) return false
        return entry.bytes > 0 && paths.file(entry).length() == entry.bytes
    }

    private suspend fun budget(
        account: SmartOfflineAccount,
        entry: SmartOfflineManaged,
        item: BaseItemDto,
    ): Long? {
        val target = paths.file(entry)
        val used = paths.root.listFiles().orEmpty().sumOf { if (it == target) 0 else it.length() }
        val cap = account.capGiB * SmartOfflinePolicy.GIB
        val free = SmartOfflineDiskSpace.available(paths.root) + target.length()
        val size = item.mediaSources?.firstOrNull()?.size ?: 0
        if (SmartOfflinePolicy.fits(size, used, cap, free)) return cap - used
        dao.status(account.key, "Waiting for storage; unknown-size episodes are skipped")
        return null
    }

    private fun transferPolicy(
        account: SmartOfflineAccount,
        endpoint: VerifiedDownloadEndpoint,
        api: ApiClient,
    ): suspend () -> Boolean {
        var checkedAt: Long? = null
        var settingsAllowed = true
        return {
            val now = System.nanoTime()
            val previousCheck = checkedAt
            if (previousCheck == null || now - previousCheck >= TimeUnit.SECONDS.toNanos(1)) {
                val current = dao.account(account.key)
                settingsAllowed = current?.enabled == true && current.copy(status = "") == account.copy(status = "")
                settingsAllowed = settingsAllowed && users.getUser(account.userId)?.accessToken == api.accessToken
                checkedAt = now
            }
            settingsAllowed && endpoints.allowed(endpoint, account.wifiOnly)
        }
    }

    private suspend fun register(
        account: SmartOfflineAccount,
        entry: SmartOfflineManaged,
        item: BaseItemDto,
        bytes: Long,
    ): Long = database.withTransaction {
        // A manual download added during the transfer must never be adopted at publication.
        val existing = existingDownload(account, item.id)
        check(existing == null || existing.path == "smart-offline/${entry.key}")
        val id = existing?.id ?: downloads.insert(
            DownloadEntity(
                serverId = account.serverId, userId = account.userId, itemId = item.id,
                path = "smart-offline/${entry.key}", item = item, status = DownloadStatus.DOWNLOADED,
            ),
        )
        if (existing != null) downloads.update(existing.copy(item = item, status = DownloadStatus.DOWNLOADED))
        val oldFile = downloads.getFiles(id).firstOrNull { it.type == DownloadFileType.ITEM }
        val file = DownloadFileEntity(
            id = oldFile?.id ?: 0,
            downloadId = id,
            type = DownloadFileType.ITEM,
            size = bytes,
            fileName = entry.key,
            uri = paths.file(entry).toUri(),
            status = DownloadStatus.DOWNLOADED,
        )
        if (oldFile == null) downloads.insertFile(file) else downloads.updateFile(file)
        id
    }

    suspend fun refreshWatched(account: SmartOfflineAccount, api: ApiClient) {
        for (chunk in dao.managed(account.key).chunked(WATCH_REFRESH_BATCH)) refreshChunk(account, api, chunk)
    }

    private suspend fun refreshChunk(
        account: SmartOfflineAccount,
        api: ApiClient,
        chunk: List<SmartOfflineManaged>,
    ) {
        val response by api.itemsApi.getItems(ids = chunk.map { it.item.id })
        val remote = response.items.associateBy { it.id }
        for (entry in chunk) refreshEntry(account, entry, remote[entry.item.id])
    }

    private suspend fun refreshEntry(
        account: SmartOfflineAccount,
        entry: SmartOfflineManaged,
        remote: BaseItemDto?,
    ) {
        if (remote == null) return
        val clean = journal.withPlaybackLease {
            val local = localReference(entry, account)
            if (local == null) return@withPlaybackLease account.cleanup && remote.userData?.played == true
            if (!ownsDownload(entry, account, local)) return@withPlaybackLease false
            val pending = dao.progress(local.id) != null
            val playing = journal.isPlaying(local.id)
            if (!pending && !playing) downloads.update(local.copy(item = local.item.copy(userData = remote.userData)))
            SmartOfflinePolicy.canClean(account.cleanup, true, remote.userData?.played == true, pending, playing)
        }
        if (clean) remove(entry, deleteFile = true, automatic = true)
    }

    private suspend fun localReference(entry: SmartOfflineManaged, account: SmartOfflineAccount): DownloadEntity? {
        val referenced = entry.downloadId?.let { downloads.getDownload(it) }
        return referenced ?: existingDownload(account, entry.item.id)?.takeIf {
            it.path == "smart-offline/${entry.key}"
        }
    }

    suspend fun remove(entry: SmartOfflineManaged, deleteFile: Boolean, automatic: Boolean = false) {
        journal.withPlaybackLease {
            val account = dao.account(entry.account) ?: return@withPlaybackLease
            val local = localReference(entry, account)
            if (local != null && !ownsDownload(entry, account, local)) return@withPlaybackLease
            if (!removable(account, local, automatic)) return@withPlaybackLease
            val target = paths.file(entry)
            if (deleteFile && target.exists() && !target.delete()) throw IOException("Cannot delete managed local file")
            local?.id?.let { downloads.delete(it) }
            if (deleteFile) dao.forget(entry.key) else dao.save(entry.copy(downloadId = null))
        }
    }

    private suspend fun removable(
        account: SmartOfflineAccount,
        local: DownloadEntity?,
        automatic: Boolean,
    ): Boolean {
        if (local != null && journal.isPlaying(local.id)) return false
        if (local != null && dao.progress(local.id) != null) return false
        if (!automatic) return true
        val latest = dao.account(account.key) ?: return false
        return latest.enabled && latest.cleanup
    }
}
