package org.jellyfin.mobile.downloads.smart

import android.content.Context
import androidx.core.net.toUri
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

private const val WATCH_REFRESH_BATCH = 50

private suspend fun prepareIdleTransfer(
    journal: OfflinePlaybackJournal,
    entry: SmartOfflineManaged,
    localId: Long?,
): SmartOfflineManaged = journal.prepareTransfer(entry, localId) ?: throw IOException("Waiting for local playback")

internal class SmartOfflineDownloadStore(
    context: Context,
    private val database: JellyfinDatabase,
    private val dao: SmartOfflineDao,
    users: UserDao,
    endpoints: SmartOfflineEndpoint,
    private val journal: OfflinePlaybackJournal,
) {
    private val downloads = database.downloadDao
    private val paths = SmartOfflinePaths(context)
    private val transfer = SmartOfflineTransfer()
    private val policy = SmartOfflineTransferPolicy(dao, users, endpoints)
    private val subtitles = SmartOfflineSubtitles(paths, downloads, dao, policy, journal)
    private val transfers = MutableStateFlow<Set<String>>(emptySet())
    val activeTransfers = transfers.asStateFlow()

    suspend fun plan(account: SmartOfflineAccount, items: List<BaseItemDto>) {
        for (item in items) {
            if (!policy.subscribed(account, item)) continue
            val existing = existingDownload(account, item.id)
            val key = "${account.key}-${item.id}"
            if (existing == null || existing.path == "smart-offline/$key") entry(account, item)
        }
    }

    suspend fun download(
        account: SmartOfflineAccount,
        endpoint: VerifiedDownloadEndpoint,
        api: ApiClient,
        item: BaseItemDto,
    ): Boolean {
        policy.requireCurrent(account)
        if (!policy.subscribed(account, item)) return true
        val key = "${account.key}-${item.id}"
        val existing = existingDownload(account, item.id)
        if (existing != null && existing.path != "smart-offline/$key") return true
        val entry = entry(account, item)
        if (complete(entry, existing)) {
            subtitles.download(SmartOfflineSubtitleRequest(account, endpoint, api, entry, requireNotNull(existing).id))
            return true
        }
        val budget = budget(account, entry, item) ?: return false
        val pending = prepareIdleTransfer(journal, entry, existing?.id)
        dao.lifecycle(entry.key, SmartOfflineItemPhase.DOWNLOADING.name)
        transfers.value += entry.key
        val id = try {
            transferAndPublish(account, endpoint, api, pending, budget)
        } catch (error: CancellationException) {
            dao.lifecycle(entry.key, SmartOfflineItemPhase.WAITING.name, "Transfer interrupted; waiting to resume")
            throw error
        } catch (error: IOException) {
            val reason = if (!policy.subscribed(
                    account,
                    item
                )
            ) {
                "Series is no longer selected"
            } else {
                transferFailure(error)
            }
            dao.lifecycle(entry.key, SmartOfflineItemPhase.WAITING.name, reason)
            throw error
        } finally {
            transfers.value -= entry.key
        }
        subtitles.download(SmartOfflineSubtitleRequest(account, endpoint, api, pending, id))
        return true
    }

    private suspend fun transferAndPublish(
        account: SmartOfflineAccount,
        endpoint: VerifiedDownloadEndpoint,
        api: ApiClient,
        pending: SmartOfflineManaged,
        budget: Long,
    ): Long {
        val bytes = transfer.download(
            SmartOfflineTransferRequest(endpoint, api, pending, paths.file(pending), budget),
            permitted = policy.permitted(account, endpoint, api, pending.item),
            saveValidator = { dao.validator(pending.key, it) },
        )
        policy.requireCurrent(account)
        if (!policy.subscribed(account, pending.item)) throw IOException("Series deselected")
        val id = register(account, pending, pending.item, bytes)
        dao.published(pending.key, id, bytes)
        return id
    }

    private fun existingDownload(account: SmartOfflineAccount, item: UUID): DownloadEntity? =
        downloads.getDownloadsByItemIds(listOf(item)).firstOrNull {
            it.serverId == account.serverId && it.userId == account.userId
        }

    private suspend fun entry(account: SmartOfflineAccount, item: BaseItemDto): SmartOfflineManaged {
        val key = "${account.key}-${item.id}"
        val existing = dao.managed(account.key).firstOrNull { it.key == key }
        if (existing != null) return existing
        return SmartOfflineManaged(
            key,
            account.key,
            item,
            expectedBytes = item.mediaSources?.firstOrNull()?.size?.takeIf { it > 0 },
        ).also { dao.save(it) }
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
        dao.lifecycle(entry.key, SmartOfflineItemPhase.WAITING.name, "Waiting for storage or a known episode size")
        dao.status(account.key, "Waiting for storage; unknown-size episodes are skipped")
        return null
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
        // Only this caller's authoritative watch endpoint may set the cleanup confirmation.
        dao.confirmedWatched(entry.key, remote.userData?.played == true)
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
            if (entry.key != "${account.key}-${entry.item.id}") return@withPlaybackLease
            val local = localReference(entry, account)
            if (local != null && !ownsDownload(entry, account, local)) return@withPlaybackLease
            if (!removable(account, entry, local, automatic)) return@withPlaybackLease
            val hadFile = paths.removeFiles(entry, deleteFile)
            local?.id?.let { downloads.delete(it) }
            if (deleteFile) {
                if (hadFile) dao.removed(entry, automatic, System.currentTimeMillis()) else dao.forget(entry.key)
            } else {
                dao.save(
                    entry.copy(
                        downloadId = null,
                        phase = SmartOfflineItemPhase.PARTIAL.name,
                        reason = "Local file retained"
                    )
                )
            }
        }
    }

    private suspend fun removable(
        account: SmartOfflineAccount,
        entry: SmartOfflineManaged,
        local: DownloadEntity?,
        automatic: Boolean,
    ): Boolean {
        if (local != null && journal.isPlaying(local.id)) return false
        if (local != null && dao.progress(local.id) != null) return false
        if (!automatic) return true
        val latest = dao.account(account.key) ?: return false
        val owned = dao.managed(account.key).firstOrNull { it.key == entry.key } ?: return false
        return latest.enabled && latest.cleanup && owned.serverPlayed
    }
}
