package org.jellyfin.mobile.downloads.smart

import android.content.Context
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.UUID

class SmartOfflineCatalogObserverTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `collecting catalog reads only scoped state and does not create storage`() = runBlocking {
        val context = mockk<Context>()
        every { context.filesDir } returns directory.toFile()
        val dao = mockk<SmartOfflineDao>(relaxed = true)
        val downloads = mockk<DownloadDao>()
        val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE)
        val entry = SmartOfflineManaged("1-2-${item.id}", "1-2", item)
        every { dao.observeAccount("1-2") } returns flowOf(SmartOfflineAccount("1-2", 1, 2))
        every { dao.observeSeries("1-2") } returns flowOf(emptyList())
        every { dao.observeManaged("1-2") } returns flowOf(listOf(entry))
        every { dao.observeRemovals("1-2") } returns flowOf(emptyList())
        every { dao.observePending(1, 2) } returns flowOf(emptyList())
        every { downloads.getAllDownloads() } returns flowOf(
            listOf(
                DownloadEntity(9, 3, 4, item.id, "smart-offline/${entry.key}", item, DownloadStatus.DOWNLOADED),
            )
        )
        val observer = SmartOfflineCatalogObserver(
            context,
            dao,
            downloads,
            OfflinePlaybackJournal(context, downloads, dao),
            MutableStateFlow(emptySet()),
        )
        val result = observer.observe(1, 2).first().single()
        assertEquals(SmartOfflineItemPhase.WAITING, result.phase)
        assertEquals(0L, result.downloadedBytes)
        assertFalse(File(directory.toFile(), "smart-offline").exists())
        coVerify(exactly = 0) { dao.lifecycle(any(), any(), any()) }
        coVerify(exactly = 0) { dao.save(any<SmartOfflineManaged>()) }
        coVerify(exactly = 0) { downloads.delete(any()) }
    }
}
