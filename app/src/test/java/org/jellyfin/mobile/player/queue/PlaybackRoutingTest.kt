package org.jellyfin.mobile.player.queue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PlaybackRoutingTest {
    @Test fun `local playback is attempted before any remote operation`() = runBlocking {
        val calls = mutableListOf<String>()
        val error = preferLocalPlayback(
            downloadsOnly = false,
            resolve = {
                calls.add("verify local")
                "completed"
            },
            local = {
                calls.add(it)
                null
            },
            remote = { error("Remote endpoint and playback info must not be requested") },
        )
        assertNull(error)
        assertEquals(listOf("verify local", "completed"), calls)
    }

    @Test fun `local lease failure is returned without silently streaming`() = runBlocking {
        val failure = PlayerException.NetworkFailure()
        val result = preferLocalPlayback(
            downloadsOnly = false,
            resolve = { "completed" },
            local = { failure },
            remote = { error("A selected local source must not silently fall back") },
        )
        assertSame(failure, result)
    }

    @Test fun `unavailable local item follows remote path unless explicitly downloads only`() = runBlocking<Unit> {
        val remoteFailure = PlayerException.NetworkFailure()
        val normal = preferLocalPlayback<String>(false, { null }, { error("No local source") }, { remoteFailure })
        assertSame(remoteFailure, normal)
        val offline = preferLocalPlayback<String>(
            true,
            { null },
            { error("No local source") },
            { error("Offline only") }
        )
        assertInstanceOf(PlayerException.UnsupportedContent::class.java, offline)
    }

    @Test fun `cancellation during local lookup never starts a remote request`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                preferLocalPlayback<String>(
                    false,
                    { throw CancellationException("Cancelled") },
                    { error("Cancelled") },
                    { error("Cancelled") },
                )
            }
        }
    }
}

class PlaybackQueueTest {
    private val items = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

    @Test fun `invalid starting indices return an error instead of indexing outside the queue`() {
        val queue = PlaybackQueue()
        assertNull(queue.initialize(options(-1)))
        assertNull(queue.initialize(options(items.size)))
        assertEquals(items[1], queue.initialize(options(1)))
        assertTrue(queue.hasPrevious())
        assertTrue(queue.hasNext())
    }

    @Test fun `failed and cancelled transitions keep the previous queue position`() = runBlocking {
        val queue = PlaybackQueue()
        assertEquals(items[0], queue.initialize(options()))
        assertFalse(queue.move(1) { PlayerException.NetworkFailure() })
        assertFalse(queue.hasPrevious())
        assertThrows(CancellationException::class.java) {
            runBlocking { queue.move(1) { throw CancellationException("Cancelled") } }
        }
        assertFalse(queue.hasPrevious())
        val attempted = mutableListOf<UUID>()
        assertTrue(
            queue.move(1) {
                attempted.add(it)
                null
            }
        )
        assertEquals(listOf(items[1]), attempted)
        assertTrue(queue.hasPrevious())
    }

    @Test fun `mixed queue selects local or remote independently for next and previous`() = runBlocking {
        val queue = PlaybackQueue()
        queue.initialize(options())
        val routes = mutableListOf<String>()
        suspend fun start(item: UUID): PlayerException? = preferLocalPlayback(
            false,
            { item.takeIf { it == items[1] } },
            {
                routes.add("local")
                null
            },
            {
                routes.add("remote")
                null
            },
        )
        assertTrue(queue.move(1, ::start))
        assertTrue(queue.move(1, ::start))
        assertTrue(queue.move(-1, ::start))
        assertEquals(listOf("local", "remote", "local"), routes)
    }

    private fun options(index: Int = 0): PlayOptions = PlayOptions(items, null, index, null, null, null, false)
}
