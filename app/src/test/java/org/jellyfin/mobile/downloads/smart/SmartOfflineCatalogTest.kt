package org.jellyfin.mobile.downloads.smart

import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SmartOfflineCatalogTest {
    private val series = UUID.randomUUID()
    private val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, seriesId = series)
    private val account = SmartOfflineAccount("1-2", 1, 2, enabled = true, cleanup = true)
    private val entry =
        SmartOfflineManaged("1-2-${item.id}", account.key, item, downloadId = 9, bytes = 3, serverPlayed = true)
    private val local = DownloadEntity(
        id = 9,
        serverId = 1,
        userId = 2,
        itemId = item.id,
        item = item,
        path = "smart-offline/${entry.key}",
        status = DownloadStatus.DOWNLOADED,
    )
    private val records = SmartOfflineCatalogRecords(
        account,
        listOf(SmartOfflineSeries("selected", account.key, series.toString(), "Series")),
        listOf(entry),
        emptyList(),
        emptyList(),
    )
    private val files = SmartOfflineCatalogFiles(listOf(local), mapOf(entry.key to 3L), emptySet(), emptySet())

    @Test
    fun `complete watched files become eligible only with every cleanup gate satisfied`() {
        val shown = smartOfflineCatalog(records, files).single()
        assertEquals(SmartOfflineItemPhase.COMPLETE, shown.phase)
        assertEquals(9L, shown.downloadId)
        assertEquals(3L, shown.downloadedBytes)
        assertTrue(shown.watched)
        assertTrue(shown.selected)
        assertTrue(shown.cleanupEligible)
        assertFalse(
            smartOfflineCatalog(records.copy(account = account.copy(cleanup = false)), files).single().cleanupEligible
        )
        assertFalse(
            smartOfflineCatalog(records.copy(account = account.copy(enabled = false)), files).single().cleanupEligible
        )
        assertFalse(smartOfflineCatalog(records, files.copy(playing = setOf(9L))).single().cleanupEligible)
    }

    @Test
    fun `locally finished pending synchronization is separate from complete and blocks cleanup`() {
        val pending = OfflineProgress(9, 1, 2, item.id, 100, true, "revision", "generation", 1)
        val shown = smartOfflineCatalog(
            records.copy(managed = listOf(entry.copy(serverPlayed = false)), pending = listOf(pending)),
            files,
        ).single()
        assertEquals(SmartOfflineItemPhase.COMPLETE, shown.phase)
        assertTrue(shown.watched)
        assertTrue(shown.pendingSync)
        assertFalse(shown.cleanupEligible)
    }

    @Test
    fun `matching byte count alone never publishes playback or cleanup authority`() {
        for (unowned in listOf(
            local.copy(userId = 3),
            local.copy(path = "manual"),
            local.copy(itemId = UUID.randomUUID())
        )) {
            val shown = smartOfflineCatalog(records, files.copy(downloads = listOf(unowned))).single()
            assertEquals(SmartOfflineItemPhase.PARTIAL, shown.phase)
            assertNull(shown.downloadId)
            assertFalse(shown.cleanupEligible)
        }
        val truncated = smartOfflineCatalog(records, files.copy(lengths = mapOf(entry.key to 2L))).single()
        assertEquals(SmartOfflineItemPhase.PARTIAL, truncated.phase)
        assertNull(truncated.downloadId)
    }

    @Test
    fun `progress follows actual bytes and active transfer while interrupted state remains honest`() {
        val pending = entry.copy(bytes = 0, phase = "DOWNLOADING", expectedBytes = 10)
        val saved = records.copy(managed = listOf(pending))
        val active = files.copy(lengths = mapOf(entry.key to 4L), transferring = setOf(entry.key))
        val shown = smartOfflineCatalog(saved, active).single()
        assertEquals(SmartOfflineItemPhase.DOWNLOADING, shown.phase)
        assertEquals(4L, shown.downloadedBytes)
        assertEquals(10L, shown.totalBytes)
        val interrupted = smartOfflineCatalog(saved, active.copy(transferring = emptySet())).single()
        assertEquals(SmartOfflineItemPhase.PARTIAL, interrupted.phase)
        assertTrue(interrupted.reason.contains("interrupted"))
        assertEquals(
            SmartOfflineItemPhase.WAITING,
            smartOfflineCatalog(saved, files.copy(lengths = emptyMap())).single().phase
        )
    }

    @Test
    fun `waiting unknown size queued selection and deselection do not invent completion`() {
        val pending = entry.copy(bytes = 0, phase = "WAITING", reason = "Waiting for storage")
        val saved = records.copy(managed = listOf(pending), series = emptyList())
        val shown = smartOfflineCatalog(saved, files.copy(lengths = emptyMap())).single()
        assertEquals(SmartOfflineItemPhase.WAITING, shown.phase)
        assertTrue(shown.reason.contains("no longer selected"))
        assertNull(shown.totalBytes)
        assertFalse(shown.selected)
        val queued = smartOfflineCatalog(
            records.copy(managed = listOf(pending.copy(phase = "QUEUED", reason = ""))),
            files.copy(lengths = emptyMap())
        ).single()
        assertEquals(SmartOfflineItemPhase.QUEUED, queued.phase)
    }

    @Test
    fun `removal history has no file authority and other accounts never enter catalog`() {
        val removal = SmartOfflineRemoval(7, account.key, item, 123, true)
        val foreign = entry.copy(key = "3-4-${item.id}", account = "3-4")
        val shown = smartOfflineCatalog(
            records.copy(managed = listOf(foreign), removals = listOf(removal)),
            files
        ).single()
        assertEquals("removed-7", shown.key)
        assertEquals(SmartOfflineItemPhase.REMOVED, shown.phase)
        assertEquals(123L, shown.removedAt)
        assertNull(shown.downloadId)
        assertFalse(shown.cleanupEligible)
        assertTrue(shown.watched)
        assertTrue(
            smartOfflineCatalog(
                records.copy(managed = listOf(foreign), removals = listOf(removal.copy(account = "3-4"))),
                files
            ).isEmpty()
        )
        val current = smartOfflineCatalog(records.copy(removals = listOf(removal)), files)
        assertEquals(1, current.size)
        assertEquals(SmartOfflineItemPhase.COMPLETE, current.single().phase)
        val latest = smartOfflineCatalog(
            records.copy(
                managed = emptyList(),
                removals = listOf(
                    removal,
                    removal.copy(id = 8, removedAt = 124, automatic = false),
                )
            ),
            files
        ).single()
        assertEquals(124L, latest.removedAt)
        assertEquals("Local download removed", latest.reason)
    }
}
