package org.jellyfin.mobile.downloads.smart

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class SmartOfflinePolicyTest {
    private fun episode(season: Int, number: Int, watched: Boolean = false): BaseItemDto {
        val id = UUID.nameUUIDFromBytes("$season-$number".toByteArray())
        return BaseItemDto(
            id = id,
            type = BaseItemKind.EPISODE,
            parentIndexNumber = season,
            indexNumber = number,
            userData = UserItemDataDto(
                key = id.toString(),
                itemId = id,
                playbackPositionTicks = 0,
                played = watched,
                playCount = 0,
                isFavorite = false
            )
        )
    }

    @Test fun `selects next N across season boundaries and replenishes watched slots`() {
        val episodes = listOf(
            episode(2, 2),
            episode(1, 2),
            episode(1, 1, true),
            episode(2, 1),
            episode(1, 2),
            episode(0, 1)
        )
        val first = SmartOfflinePolicy.nextEpisodes(episodes, 2)
        assertEquals(listOf(episode(1, 2).id, episode(2, 1).id), first.map { it.id })
        val next = SmartOfflinePolicy.nextEpisodes(episodes, 2, setOf(first.first().id))
        assertEquals(listOf(episode(2, 1).id, episode(2, 2).id), next.map { it.id })
    }

    @Test fun `missing and unaired episodes do not displace available episodes`() {
        val now = LocalDateTime.of(2026, 1, 1, 0, 0)
        val available = episode(1, 3)
        val missing = episode(1, 1).copy(locationType = LocationType.VIRTUAL)
        val future = episode(1, 2).copy(premiereDate = now.plusDays(1))
        assertEquals(
            listOf(available),
            SmartOfflinePolicy.nextEpisodes(listOf(missing, future, available), 3, now = now)
        )
    }

    @Test fun `cleanup requires explicit opt in ownership server confirmation and idle synchronized playback`() {
        assertTrue(SmartOfflinePolicy.canClean(true, true, true, false, false))
        assertFalse(SmartOfflinePolicy.canClean(false, true, true, false, false))
        assertFalse(SmartOfflinePolicy.canClean(true, false, true, false, false))
        assertFalse(SmartOfflinePolicy.canClean(true, true, false, false, false))
        assertFalse(SmartOfflinePolicy.canClean(true, true, true, true, false))
        assertFalse(SmartOfflinePolicy.canClean(true, true, true, false, true))
    }

    @Test fun `storage admission handles exact cap low disk unknown sizes and overflow`() {
        val reserve = SmartOfflinePolicy.FREE_SPACE_RESERVE
        assertTrue(SmartOfflinePolicy.fits(10, 90, 100, reserve + 10))
        assertFalse(SmartOfflinePolicy.fits(11, 90, 100, reserve + 100))
        assertFalse(SmartOfflinePolicy.fits(10, 0, 100, reserve + 9))
        assertFalse(SmartOfflinePolicy.fits(0, 0, 100, reserve + 100))
        assertFalse(SmartOfflinePolicy.fits(Long.MAX_VALUE, 90, Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(SmartOfflinePolicy.fits(1, 101, 100, Long.MAX_VALUE))
    }

    @Test fun `endpoint config cannot embed tokens or silently downgrade transport`() {
        assertEquals("https://home.example/jellyfin", SmartOfflinePolicy.lanUrl(" https://home.example/jellyfin/ "))
        for (url in listOf(
            "http://home.example",
            "https://user:secret@home.example",
            "https://home.example?api_key=secret",
            "https://home.example/#token"
        )) {
            assertThrows(IllegalArgumentException::class.java) { SmartOfflinePolicy.lanUrl(url) }
        }
    }

    @Test fun `retry policy distinguishes transient failures from invalid credentials or redirects`() {
        listOf(408, 429, 500, 503).forEach { assertTrue(SmartOfflinePolicy.retryable(it)) }
        listOf(301, 401, 403, 404, 416).forEach { assertFalse(SmartOfflinePolicy.retryable(it)) }
    }
}
