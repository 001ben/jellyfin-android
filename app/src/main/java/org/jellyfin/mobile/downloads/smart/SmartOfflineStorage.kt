package org.jellyfin.mobile.downloads.smart

import android.annotation.SuppressLint
import android.content.Context
import org.jellyfin.mobile.data.entity.DownloadEntity
import java.io.File

internal object SmartOfflineDiskSpace {
    // Admission reserves existing free bytes and never assumes that cache can be reclaimed.
    // File.usableSpace also supports API 23, unlike StorageManager's allocatable-byte APIs.
    @SuppressLint("UsableSpace")
    fun available(directory: File): Long = directory.usableSpace
}

internal class SmartOfflinePaths(private val context: Context) {
    val root: File
        get() = File(context.filesDir, "smart-offline").also { check(it.exists() || it.mkdirs()) }

    fun file(entry: SmartOfflineManaged): File {
        root
        return readFile(entry)
    }

    /** Catalog observation must not create directories or change download state. */
    fun readFile(entry: SmartOfflineManaged): File {
        require(entry.key.matches(Regex("[0-9]+-[0-9]+-[0-9a-f-]{36}")))
        return File(File(context.filesDir, "smart-offline"), entry.key)
    }
}

internal fun ownsDownload(
    entry: SmartOfflineManaged,
    account: SmartOfflineAccount,
    local: DownloadEntity,
): Boolean {
    if (entry.account != account.key || entry.key != "${account.key}-${entry.item.id}") return false
    val sameAccount = local.serverId == account.serverId && local.userId == account.userId
    return sameAccount && local.path == "smart-offline/${entry.key}" && local.itemId == entry.item.id
}
