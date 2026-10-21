package org.jellyfin.mobile.downloads.smart

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class SmartOfflineManagerTest {
    @TempDir lateinit var directory: Path
    private val context = mockk<Context>()
    private val database = mockk<JellyfinDatabase>()
    private val downloads = mockk<DownloadDao>()
    private val dao = mockk<SmartOfflineDao>(relaxed = true)
    private val users = mockk<UserDao>()
    private val controller = mockk<ApiClientController>()
    private val outbox = mutableMapOf<Long, OfflineProgress>()
    private val managed = mutableMapOf<Long, SmartOfflineManaged>()
    private val rows = mutableMapOf<Long, DownloadEntity>()
    private val account = SmartOfflineAccount("1-2", 1, 2, enabled = true, cleanup = true, serverIdentity = "server-A")

    @Test
    fun `account server or item mismatch prevents deletion of the referenced record`() = runBlocking {
        val manager = setup()
        val original = row()
        val variants = listOf(
            original.copy(serverId = 99),
            original.copy(userId = 99),
            original.copy(itemId = UUID.randomUUID())
        )
        for (variant in variants) {
            rows[original.id] = variant
            manager.remove(original.id, true)
            assertEquals(variant, rows[original.id])
            assertTrue(File(directory.toFile(), original.path).exists())
        }
    }

    @Test
    fun `worker cancellation propagates and preserves pending watch state`() = runBlocking {
        val manager = setup()
        val download = row()
        manager.record(OfflinePlaybackSnapshot(download.id, 1000, true, stopped = false))
        every { controller.getApiClient(1, 2) } throws CancellationException("test cancellation")
        assertThrows(CancellationException::class.java) { runBlocking { manager.syncProgress() } }
        assertTrue(outbox.getValue(download.id).finished)
    }

    @Test
    fun `automatic cleanup waits for server watched state and pending local reconciliation`() = runBlocking {
        val manager = setup()
        val download = row()
        val played = AtomicBoolean(false)
        coEvery { dao.account(any()) } returns account.copy(remoteFallback = true)
        coEvery { dao.managed(any()) } answers { managed.values.toList() }
        coEvery { dao.series(any()) } returns emptyList()
        LocalHttpFixture().use { server ->
            every { controller.getApiClient(1, 2) } returns api(server.url)
            server.handler = { exchange ->
                val response = "{\"Items\":[{\"Id\":\"${download.itemId}\",\"Type\":\"Episode\",\"UserData\":{" +
                    "\"Key\":\"test\",\"ItemId\":\"${download.itemId}\",\"PlaybackPositionTicks\":0," +
                    "\"Played\":${played.get()},\"PlayCount\":1,\"IsFavorite\":false}}]," +
                    "\"TotalRecordCount\":1,\"StartIndex\":0}"
                server.respond(exchange, 200, response.toByteArray())
            }
            manager.reconcile(account.key)
            assertTrue(File(directory.toFile(), download.path).exists())
            manager.record(OfflinePlaybackSnapshot(download.id, 1000, true, stopped = false))
            played.set(true)
            manager.reconcile(account.key)
            assertTrue(File(directory.toFile(), download.path).exists())
            outbox.clear() // Simulate the exact-revision server acknowledgement.
            manager.reconcile(account.key)
            assertFalse(File(directory.toFile(), download.path).exists())
            assertFalse(rows.containsKey(download.id))
        }
    }

    @Test
    fun `late stop releases its own lease without releasing a newer playback session`() = runBlocking {
        mockkObject(OfflineProgressWorker.Companion)
        every { OfflineProgressWorker.enqueue(any()) } returns Unit
        try {
            val manager = setup()
            val row = row()
            assertTrue(manager.started(row.id, "old-session"))
            manager.record(
                OfflinePlaybackSnapshot(
                    row.id,
                    200,
                    false,
                    stopped = false,
                    eventSequence = 20,
                    session = "old-session"
                )
            )
            assertTrue(manager.started(row.id, "new-session"))
            manager.record(
                OfflinePlaybackSnapshot(row.id, 100, false, stopped = true, eventSequence = 10, session = "old-session")
            )
            LocalHttpFixture().use { server ->
                every { controller.getApiClient(1, 2) } returns api(server.url)
                server.handler = { server.respond(it, 204, ByteArray(0), length = -1) }
                manager.syncProgress()
                assertTrue(server.requests.isEmpty())
                manager.record(
                    OfflinePlaybackSnapshot(
                        row.id,
                        150,
                        false,
                        stopped = true,
                        eventSequence = 15,
                        session = "new-session"
                    )
                )
                manager.syncProgress()
                assertEquals(1, server.requests.size)
                assertTrue(outbox.isEmpty())
            }
        } finally {
            unmockkObject(OfflineProgressWorker.Companion)
        }
    }

    @Test
    fun `an older acknowledgement cannot discard progress recorded while the request was in flight`() = runBlocking {
        val manager = setup()
        val row = row()
        manager.record(OfflinePlaybackSnapshot(row.id, 100, false, stopped = false, eventSequence = 1))
        LocalHttpFixture().use { server ->
            every { controller.getApiClient(1, 2) } returns api(server.url)
            server.handler = { exchange ->
                runBlocking {
                    manager.record(OfflinePlaybackSnapshot(row.id, 200, false, stopped = false, eventSequence = 2))
                }
                server.respond(exchange, 204, ByteArray(0), length = -1)
            }
            manager.syncProgress()
            assertEquals(200L, outbox.getValue(row.id).position)
            assertFalse(outbox.getValue(row.id).stopReported)
        }
    }

    private fun setup(
        endpoint: SmartOfflineEndpoint = SmartOfflineEndpoint(
            FakeDownloadNetwork(),
            OkHttpClient()
        )
    ): SmartOfflineManager {
        every { context.filesDir } returns directory.toFile()
        every { database.downloadDao } returns downloads
        coEvery { downloads.getDownload(any()) } answers { rows[firstArg()] }
        every {
            downloads.getDownloadsByItemIds(any())
        } answers { rows.values.filter { it.itemId in firstArg<Collection<UUID>>() } }
        coEvery { downloads.update(any()) } answers {
            rows[firstArg<DownloadEntity>().id] = firstArg()
            1
        }
        coEvery { downloads.delete(any()) } answers {
            rows.remove(firstArg())
        }
        coEvery { dao.account(any()) } returns account
        coEvery { dao.owned(any()) } answers { managed[firstArg()] }
        coEvery { dao.progress(any()) } answers { outbox[firstArg()] }
        coEvery { dao.pending() } answers { outbox.values.toList() }
        coEvery {
            dao.save(any<OfflineProgress>())
        } answers {
            outbox[firstArg<OfflineProgress>().downloadId] = firstArg()
        }
        coEvery { dao.acknowledged(any(), any()) } answers {
            val id = firstArg<Long>()
            if (outbox[id]?.revision == secondArg<String>()) outbox.remove(id)
        }
        coEvery { dao.stopped(any(), any()) } answers {
            val id = firstArg<Long>()
            if (outbox[id]?.revision == secondArg<String>()) outbox[id] = outbox.getValue(id).copy(stopReported = true)
        }
        every { users.getUser(2) } returns UserEntity(2, 1, UUID.randomUUID(), "test-token", 0)
        return SmartOfflineManager(context, database, dao, users, controller, endpoint)
    }

    private fun row(id: Long = 9): DownloadEntity {
        val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE)
        val key = "1-2-${item.id}"
        val row = DownloadEntity(
            id = id,
            serverId = 1,
            userId = 2,
            itemId = item.id,
            item = item,
            path = "smart-offline/$key",
            status = DownloadStatus.DOWNLOADED
        )
        rows[id] = row
        managed[id] = SmartOfflineManaged(key, "1-2", item, downloadId = id, bytes = 3)
        File(directory.toFile(), "smart-offline").mkdirs()
        File(directory.toFile(), "smart-offline/$key").writeText("abc")
        return row
    }

    private fun api(url: String): org.jellyfin.sdk.api.client.ApiClient {
        val factory = OkHttpFactory()
        return factory.create(
            url,
            "test-token",
            ClientInfo("test", "1"),
            DeviceInfo("test", "device"),
            HttpClientOptions(),
            factory
        )
    }

    @Test fun `cleanup removes only owned phone file and matching download record`() = runBlocking {
        val manager = setup()
        val row = row()
        val unrelated = directory.resolve("manual-video").toFile().apply { writeText("keep") }
        assertTrue(manager.remove(row.id, true))
        assertFalse(File(directory.toFile(), row.path).exists())
        assertTrue(rows.isEmpty())
        assertEquals("keep", unrelated.readText())
        assertFalse(manager.remove(999, true))
    }

    @Test fun `stale ownership cannot delete an unrelated restored database row`() = runBlocking {
        val manager = setup()
        val row = row()
        rows[row.id] = row.copy(path = "manual-download")
        manager.remove(row.id, true)
        assertTrue(File(directory.toFile(), row.path).exists())
        assertEquals("manual-download", rows[row.id]?.path)
    }

    @Test fun `playback lease and pending watch event protect local files`() = runBlocking {
        val manager = setup()
        val row = row()
        assertTrue(manager.started(row.id))
        manager.remove(row.id, true)
        assertTrue(File(directory.toFile(), row.path).exists())
        manager.record(OfflinePlaybackSnapshot(row.id, 500, false, stopped = false))
        assertNotNull(outbox[row.id])
        manager.remove(row.id, true)
        assertTrue(File(directory.toFile(), row.path).exists())
    }

    @Test fun `late partial snapshot cannot erase completed watch or an acknowledged completion`() = runBlocking {
        val manager = setup()
        val row = row()
        manager.record(OfflinePlaybackSnapshot(row.id, 1000, true, stopped = false, eventSequence = 20))
        manager.record(OfflinePlaybackSnapshot(row.id, 100, false, stopped = false, eventSequence = 10))
        assertTrue(outbox.getValue(row.id).finished)
        assertEquals(1000L, outbox.getValue(row.id).position)
        outbox.clear() // Simulate server acknowledgement before an already captured older coroutine reaches Room.
        manager.record(OfflinePlaybackSnapshot(row.id, 200, false, stopped = false, eventSequence = 15))
        assertTrue(outbox.isEmpty())
        assertTrue(rows.getValue(row.id).item.userData!!.played)
    }

    @Test fun `retry retains watched event and does not repeat acknowledged stop`() = runBlocking {
        val manager = setup()
        val row = row()
        manager.record(OfflinePlaybackSnapshot(row.id, 1000, true, stopped = false))
        LocalHttpFixture().use { server ->
            every { controller.getApiClient(1, 2) } returns api(server.url)
            var markAttempts = 0
            server.handler = { exchange ->
                if (exchange.requestURI.path.contains("Stopped")) {
                    server.respond(exchange, 204, ByteArray(0), length = -1)
                } else if (markAttempts++ == 0) {
                    server.respond(exchange, 503, ByteArray(0), length = -1)
                } else {
                    server.respond(
                        exchange, 200,
                        (
                            "{\"Key\":\"test\",\"ItemId\":\"${row.itemId}\",\"PlaybackPositionTicks\":0," +
                                "\"Played\":true,\"PlayCount\":1,\"IsFavorite\":false}"
                            ).toByteArray()
                    )
                }
            }
            assertThrows(IOException::class.java) { runBlocking { manager.syncProgress() } }
            assertTrue(outbox.getValue(row.id).stopReported)
            manager.syncProgress()
            assertTrue(outbox.isEmpty())
            assertEquals(1, server.requests.count { it.path.contains("Stopped") })
            assertEquals(2, markAttempts)
        }
    }

    @Test fun `metadata uses verified LAN even when remote server route is unavailable`() = runBlocking {
        LocalHttpFixture(tls = true).use { server ->
            val network = FakeDownloadNetwork(
                DirectWifiRoute(RedirectToFixtureSockets(server.port)) {
                    listOf(InetAddress.getByName("192.168.1.2"))
                }
            )
            val resolver = SmartOfflineEndpoint(network, server.trustedClient)
            val manager = setup(resolver)
            val settings = account.copy(lanUrl = "https://home.test:${server.port}/jellyfin/")
            coEvery { dao.account(any()) } returns settings
            coEvery { dao.managed(any()) } returns emptyList()
            coEvery {
                dao.series(any())
            } returns listOf(SmartOfflineSeries("series", "1-2", UUID.randomUUID().toString(), "Series"))
            every { controller.getApiClient(1, 2) } returns api("https://remote.invalid/")
            server.handler = { exchange ->
                server.respond(
                    exchange, 200,
                    if (exchange.requestURI.path.contains("Public")) {
                        "{\"Id\":\"server-A\"}".toByteArray()
                    } else {
                        "{\"Items\":[],\"TotalRecordCount\":0,\"StartIndex\":0}".toByteArray()
                    }
                )
            }
            manager.reconcile("1-2")
            assertEquals(2, server.requests.size)
            assertNull(server.requests[0].authorization)
            assertTrue(server.requests[1].authorization!!.contains("test-token"))
            assertEquals("/jellyfin/Items", server.requests[1].path)
        }
    }
}
