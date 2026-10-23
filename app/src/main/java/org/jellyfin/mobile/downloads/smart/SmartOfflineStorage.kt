package org.jellyfin.mobile.downloads.smart

import android.annotation.SuppressLint
import android.content.Context
import org.jellyfin.mobile.data.entity.DownloadEntity
import java.io.File
import java.io.IOException

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

    fun subtitle(entry: SmartOfflineManaged, index: Int, partial: Boolean = false): File {
        val directory = requireNotNull(readFile(entry).parentFile)
        val name = managedSubtitleName(entry.key, index) + if (partial) ".part" else ""
        return File(directory, name).also {
            require(it.canonicalFile == File(directory.canonicalFile, name))
        }
    }

    /** Include interrupted sidecars, but never follow server paths or remove a different item's files. */
    fun subtitleFiles(entry: SmartOfflineManaged): List<File> {
        val directory = requireNotNull(readFile(entry).parentFile)
        val name = Regex("${Regex.escape(entry.key)}-subtitle-[0-9]+\\.vtt(?:\\.part)?")
        return directory.listFiles().orEmpty().filter { name.matches(it.name) }.onEach {
            require(it.canonicalFile == File(directory.canonicalFile, it.name))
        }
    }
}

internal fun managedSubtitleName(key: String, index: Int): String {
    require(key.matches(Regex("[0-9]+-[0-9]+-[0-9a-f-]{36}")))
    require(index in 0..MAX_SUBTITLE_INDEX)
    return "$key-subtitle-$index.vtt"
}

internal const val MAX_SUBTITLE_INDEX = 10_000

internal fun SmartOfflinePaths.removeFiles(entry: SmartOfflineManaged, deleteFile: Boolean): Boolean {
    val target = readFile(entry)
    val hadFile = target.isFile
    val files = (listOf(target) + subtitleFiles(entry)).filter { it.exists() }
    if (files.any { !it.isFile }) throw IOException("Managed download is not a regular file")
    if (deleteFile && files.any { !it.delete() }) throw IOException("Cannot delete managed local file")
    return hadFile
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
