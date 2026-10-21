package org.jellyfin.mobile.downloads.smart

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.UUID

class SmartOfflineTransferTest {
    @TempDir lateinit var directory: Path
    private val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE)
    private val managed = SmartOfflineManaged("1-2-${item.id}", "1-2", item)

    private data class TransferOptions(
        val cap: Long = 100,
        val allowed: () -> Boolean = { true },
        val validator: suspend (String?) -> Unit = {},
    )

    @Test
    fun `interruption retains a validated partial and retry finishes it without duplicate bytes`() = runBlocking {
        LocalHttpFixture().use { server ->
            val body = ByteArray(160 * 1024) { (it % 251).toByte() }
            val file = directory.resolve("interrupted").toFile()
            var saved: String? = null
            var policyChecks = 0
            server.handler = { server.respond(it, 200, body, mapOf("ETag" to "\"v1\"")) }
            assertThrows(IOException::class.java) {
                runBlocking {
                    transfer(
                        server,
                        file,
                        options = TransferOptions(
                            cap = body.size.toLong(),
                            allowed = { ++policyChecks < 3 },
                            validator = { saved = it },
                        ),
                    )
                }
            }
            assertTrue(file.length() in 1 until body.size.toLong())
            assertEquals("\"v1\"", saved)
            val offset = file.length().toInt()
            server.handler = {
                server.respond(
                    it, 206, body.copyOfRange(offset, body.size),
                    mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes $offset-${body.size - 1}/${body.size}")
                )
            }
            transfer(server, file, managed.copy(etag = saved), TransferOptions(cap = body.size.toLong()))
            assertArrayEquals(body, file.readBytes())
        }
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
    private suspend fun transfer(
        server: LocalHttpFixture,
        file: File,
        entry: SmartOfflineManaged = managed,
        options: TransferOptions = TransferOptions(),
    ): Long =
        SmartOfflineTransfer().download(
            SmartOfflineTransferRequest(
                VerifiedDownloadEndpoint(
                    server.url.toHttpUrl(),
                    OkHttpClient.Builder().followRedirects(false).build(),
                    false,
                ),
                api(server.url),
                entry,
                file,
                options.cap,
            ),
            { options.allowed() },
            options.validator,
        )

    @Test fun `first download creates complete playable bytes within cap`() = runBlocking {
        LocalHttpFixture().use { server ->
            val body = "episode-data".toByteArray()
            server.handler = { server.respond(it, 200, body, mapOf("ETag" to "\"v1\"")) }
            val file = directory.resolve("new-file").toFile()
            assertEquals(body.size.toLong(), transfer(server, file))
            assertArrayEquals(body, file.readBytes())
            assertTrue(server.requests.single().authorization!!.contains("test-token"))
            val expectedPath = "/jellyfin/Items/${item.id}/Download"
            assertEquals(expectedPath, server.requests.single().path)
        }
    }

    @Test fun `interrupted range resume appends only matching validated bytes`() = runBlocking {
        LocalHttpFixture().use { server ->
            val file = directory.resolve("partial").toFile().apply { writeText("abc") }
            server.handler = {
                server.respond(
                    it,
                    206,
                    "def".toByteArray(),
                    mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 3-5/6"),
                )
            }
            assertEquals(6, transfer(server, file, managed.copy(etag = "\"v1\"")))
            assertEquals("abcdef", file.readText())
            assertEquals("bytes=3-", server.requests.single().range)
            assertEquals("\"v1\"", server.requests.single().ifRange)
        }
    }

    @Test fun `changed file returning full response replaces stale bytes`() = runBlocking {
        LocalHttpFixture().use { server ->
            val file = directory.resolve("partial").toFile().apply { writeText("old and longer") }
            server.handler = { server.respond(it, 200, "new".toByteArray(), mapOf("ETag" to "\"v2\"")) }
            var saved: String? = null
            transfer(server, file, managed.copy(etag = "\"v1\""), TransferOptions(validator = { saved = it }))
            assertEquals("new", file.readText())
            assertEquals("\"v2\"", saved)
        }
    }

    @Test fun `wrong partial range or validator never corrupts previous bytes`() = runBlocking {
        LocalHttpFixture().use { server ->
            val file = directory.resolve("partial").toFile().apply { writeText("abc") }
            for (headers in listOf(
                mapOf("ETag" to "\"v2\"", "Content-Range" to "bytes 3-5/6"),
                mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 2-5/6"),
                mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 3-4/6"),
                mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 3-6/6"),
            )) {
                server.handler = { server.respond(it, 206, "def".toByteArray(), headers) }
                assertThrows(
                    IOException::class.java
                ) { runBlocking { transfer(server, file, managed.copy(etag = "\"v1\"")) } }
                assertEquals("abc", file.readText())
            }
        }
    }

    @Test fun `completed file recovers after process death using matching 416 validator`() = runBlocking {
        LocalHttpFixture().use { server ->
            val file = directory.resolve("complete").toFile().apply { writeText("abcdef") }
            server.handler = {
                server.respond(it, 416, ByteArray(0), mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes */6"), -1)
            }
            assertEquals(6, transfer(server, file, managed.copy(etag = "\"v1\"")))
            assertEquals("abcdef", file.readText())
        }
    }

    @Test fun `cap rejects oversized responses before creating a file`() = runBlocking {
        LocalHttpFixture().use { server ->
            val file = directory.resolve("too-large").toFile()
            server.handler = { server.respond(it, 200, "abcdef".toByteArray()) }
            assertThrows(StorageBudgetExceeded::class.java) {
                runBlocking { transfer(server, file, options = TransferOptions(cap = 5)) }
            }
            assertFalse(file.exists())
        }
    }

    @Test fun `network policy pauses transfers without requesting credentials on forbidden route`() = runBlocking {
        LocalHttpFixture().use { server ->
            assertThrows(
                IOException::class.java
            ) {
                runBlocking {
                    transfer(
                        server,
                        directory.resolve("denied").toFile(),
                        options = TransferOptions(allowed = { false })
                    )
                }
            }
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test fun `redirect cannot forward download credentials`() = runBlocking {
        LocalHttpFixture().use { server ->
            server.handler = { server.respond(it, 302, ByteArray(0), mapOf("Location" to "${server.url}other"), -1) }
            assertThrows(
                DownloadRejected::class.java
            ) { runBlocking { transfer(server, directory.resolve("redirect").toFile()) } }
            assertEquals(1, server.requests.size)
        }
    }
}
