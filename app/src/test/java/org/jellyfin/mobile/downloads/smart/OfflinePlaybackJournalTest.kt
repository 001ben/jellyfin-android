package org.jellyfin.mobile.downloads.smart

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.UUID

class OfflinePlaybackJournalTest {
    @TempDir lateinit var directory: Path
    private val context = mockk<Context>()
    private val downloads = mockk<DownloadDao>()
    private val dao = mockk<SmartOfflineDao>()
    private val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE)
    private var entry = SmartOfflineManaged("1-2-${item.id}", "1-2", item, downloadId = 9, bytes = 3)
    private val row = DownloadEntity(
        id = 9,
        serverId = 1,
        userId = 2,
        itemId = item.id,
        item = item,
        path = "smart-offline/${entry.key}",
    )

    private fun setup(): OfflinePlaybackJournal {
        every { context.filesDir } returns directory.toFile()
        coEvery { downloads.getDownload(9) } returns row
        coEvery { dao.owned(9) } answers { entry }
        coEvery { dao.save(any<SmartOfflineManaged>()) } answers {
            entry = firstArg()
        }
        val root = File(directory.toFile(), "smart-offline").also { it.mkdirs() }
        File(root, entry.key).writeText("abc")
        return OfflinePlaybackJournal(context, downloads, dao)
    }

    @Test fun `repair refuses an active lease and blocks playback until complete`() = runBlocking {
        val journal = setup()
        assertTrue(journal.started(9, "playing"))
        assertNull(journal.prepareTransfer(entry, 9))
        assertEquals(3, entry.bytes)
        // A new process has no active lease. Existing complete-sized bytes still need invalidation before repair.
        val recovered = OfflinePlaybackJournal(context, downloads, dao)
        assertNotNull(recovered.prepareTransfer(entry, 9))
        assertEquals(0, entry.bytes)
        assertFalse(recovered.started(9, "repairing"))
    }

    @Test fun `a stale ownership record cannot acquire playback for a different account`() = runBlocking {
        val journal = setup()
        entry = entry.copy(account = "1-99")
        assertFalse(journal.started(9, "wrong-account"))
    }

    @Test fun `abandon releases only matching lease without recording playback or scheduling sync`() = runBlocking {
        val journal = setup()
        assertTrue(journal.started(9, "old"))
        assertTrue(journal.started(9, "new"))
        journal.abandon(9, "old")
        assertTrue(journal.isPlaying(9))
        assertEquals(setOf(9L), journal.activeDownloads.value)
        journal.abandon(9, "new")
        assertFalse(journal.isPlaying(9))
        assertTrue(journal.activeDownloads.value.isEmpty())
        coVerify(exactly = 0) { dao.save(any<OfflineProgress>()) }
    }
}
