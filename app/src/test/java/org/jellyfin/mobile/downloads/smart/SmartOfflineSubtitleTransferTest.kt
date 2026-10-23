package org.jellyfin.mobile.downloads.smart

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.UUID

internal const val SUBTITLE_FIXTURE = "WEBVTT\n\n00:00:00.000 --> 00:00:02.900\nOffline English subtitle\n"

class SmartOfflineSubtitleTransferTest {
    @TempDir lateinit var directory: Path

    @Test fun `text subtitle requests use the verified source and header authentication`() = runBlocking {
        LocalHttpFixture().use { server ->
            server.handler = { server.respond(it, 200, SUBTITLE_FIXTURE.toByteArray()) }
            val request = subtitleRequest(server)
            val file = directory.resolve("subtitle.part").toFile()
            val bytes = SmartOfflineSubtitleTransfer().download(request, 7, file, 1024) { true }
            assertEquals(SUBTITLE_FIXTURE.length.toLong(), bytes)
            assertEquals(SUBTITLE_FIXTURE, file.readText())
            val seen = server.requests.single()
            assertEquals("/jellyfin/Videos/${request.entry.item.id}/fixture-source/Subtitles/7/Stream.vtt", seen.path)
            assertTrue(seen.authorization.orEmpty().contains("fixture-token"))
            assertFalse(seen.path.contains("token"))
        }
    }

    @Test fun `redirects never forward authentication even when an injected client follows redirects`() = runBlocking {
        LocalHttpFixture().use { first ->
            LocalHttpFixture().use { other ->
                first.handler = { first.respond(it, 302, ByteArray(0), mapOf("Location" to other.url)) }
                val file = directory.resolve("redirect.part").toFile()
                assertThrows(DownloadRejected::class.java) {
                    runBlocking {
                        SmartOfflineSubtitleTransfer().download(
                            subtitleRequest(first),
                            0,
                            file,
                            1024
                        ) { true }
                    }
                }
                assertTrue(other.requests.isEmpty())
                assertFalse(file.exists())
            }
        }
    }

    @Test fun `unknown length subtitles stream within budget while oversized bodies are rejected`() = runBlocking {
        LocalHttpFixture().use { server ->
            val file = directory.resolve("chunked.part").toFile()
            server.handler = { server.respond(it, 200, SUBTITLE_FIXTURE.toByteArray(), length = 0) }
            val transfer = SmartOfflineSubtitleTransfer()
            val request = subtitleRequest(server)
            assertEquals(SUBTITLE_FIXTURE.length.toLong(), transfer.download(request, 0, file, 1024) { true })
            assertThrows(StorageBudgetExceeded::class.java) {
                runBlocking { transfer.download(request, 0, file, 5) { true } }
            }
            assertFalse(file.exists())
            server.handler = { server.respond(it, 200, ByteArray(MAX_OFFLINE_SUBTITLE_BYTES.toInt() + 1)) }
            assertThrows(StorageBudgetExceeded::class.java) {
                runBlocking { transfer.download(request, 0, file, Long.MAX_VALUE) { true } }
            }
            assertFalse(file.exists())
        }
    }

    @Test fun `HTML and truncated successful responses never become subtitle files`() = runBlocking {
        LocalHttpFixture().use { server ->
            val file = directory.resolve("invalid.part").toFile()
            val request = subtitleRequest(server)
            val transfer = SmartOfflineSubtitleTransfer()
            server.handler = { server.respond(it, 200, "<html>login</html>".toByteArray()) }
            assertThrows(IOException::class.java) { runBlocking { transfer.download(request, 0, file, 1024) { true } } }
            assertFalse(file.exists())
            server.handler = { server.respond(it, 200, SUBTITLE_FIXTURE.toByteArray(), length = 200) }
            assertThrows(IOException::class.java) { runBlocking { transfer.download(request, 0, file, 1024) { true } } }
            assertFalse(file.exists())
        }
    }

    @Test fun `policy revocation removes partial subtitles and retry starts a complete text response`() = runBlocking {
        LocalHttpFixture().use { server ->
            val body = SUBTITLE_FIXTURE + " ".repeat(24 * 1024)
            server.handler = { server.respond(it, 200, body.toByteArray()) }
            val request = subtitleRequest(server)
            val file = directory.resolve("interrupted.part").toFile()
            val transfer = SmartOfflineSubtitleTransfer()
            var checks = 0
            assertThrows(IOException::class.java) {
                runBlocking { transfer.download(request, 0, file, 64 * 1024) { ++checks < 3 } }
            }
            assertFalse(file.exists())
            assertEquals(body.length.toLong(), transfer.download(request, 0, file, 64 * 1024) { true })
            assertEquals(body, file.readText())
            assertTrue(server.requests.all { it.range == null })
        }
    }

    @Test fun `only supported external text tracks from the downloaded source are planned`() {
        val source = subtitleFixtureSource().copy(
            mediaStreams = listOf(
                subtitleFixtureStream(0),
                subtitleFixtureStream(1).copy(isExternal = false),
                subtitleFixtureStream(2).copy(codec = "pgssub"),
                subtitleFixtureStream(0),
            )
        )
        val item = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.EPISODE,
            mediaSources = listOf(source, subtitleFixtureSource())
        )
        val entry = SmartOfflineManaged("1-2-${item.id}", "1-2", item)
        assertEquals(listOf(0), externalTextSubtitles(entry).map { it.index })
        assertThrows(IllegalArgumentException::class.java) { managedSubtitleName("../other", 0) }
        assertThrows(IllegalArgumentException::class.java) { managedSubtitleName(entry.key, -1) }
    }
}

internal fun subtitleRequest(server: LocalHttpFixture): SmartOfflineSubtitleRequest {
    val account = SmartOfflineAccount("1-2", 1, 2, enabled = true)
    val item = BaseItemDto(
        id = UUID.randomUUID(),
        type = BaseItemKind.EPISODE,
        seriesId = UUID.randomUUID(),
        mediaSources = listOf(subtitleFixtureSource()),
    )
    val api = mockk<ApiClient> {
        every { clientInfo } returns ClientInfo("fixture", "1")
        every { deviceInfo } returns DeviceInfo("fixture", "fixture")
        every { accessToken } returns "fixture-token"
    }
    return SmartOfflineSubtitleRequest(
        account,
        VerifiedDownloadEndpoint(server.url.toHttpUrl(), OkHttpClient(), false),
        api,
        SmartOfflineManaged("${account.key}-${item.id}", account.key, item, downloadId = 9, bytes = 3),
        9,
    )
}

internal fun subtitleFixtureSource(): MediaSourceInfo = Json.decodeFromString<MediaSourceInfo>(
    """{
        "Id":"fixture-source", "Protocol":"File", "Type":"Default", "IsRemote":false,
        "ReadAtNativeFramerate":false, "IgnoreDts":false, "IgnoreIndex":false, "GenPtsInput":false,
        "SupportsTranscoding":false, "SupportsDirectStream":false, "SupportsDirectPlay":true,
        "IsInfiniteStream":false, "RequiresOpening":false, "RequiresClosing":false,
        "RequiresLooping":false, "SupportsProbing":false, "TranscodingSubProtocol":"http", "HasSegments":false
    }""",
).copy(mediaStreams = listOf(subtitleFixtureStream(0)))

internal fun subtitleFixtureStream(index: Int): MediaStream = Json.decodeFromString(
    """{
        "Type":"Subtitle", "Index":$index, "Codec":"subrip", "Language":"eng", "DisplayTitle":"English",
        "IsInterlaced":false, "IsDefault":false, "IsForced":false, "IsHearingImpaired":false,
        "IsExternal":true, "IsTextSubtitleStream":true, "SupportsExternalStream":true,
        "DeliveryMethod":"External", "DeliveryUrl":"https://unverified.invalid/subtitle?token=ignored"
    }""",
)
