package org.jellyfin.mobile.player.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.downloads.smart.SmartOfflineDao
import org.jellyfin.mobile.downloads.smart.SmartOfflineManaged
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.UserItemDataDto
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val RESUME_TICKS = 300_000_000L
private const val AUDIO_INDEX = 5
private const val SUBTITLE_INDEX = 8

class LocalPlaybackResolverTest {
    @TempDir lateinit var directory: Path

    @Test fun `a complete main file plays without a completed optional poster`() = runBlocking {
        val fixture = LocalPlaybackFixture(directory.toFile())
        val poster = fixture.main.copy(type = DownloadFileType.IMAGE_PRIMARY, status = DownloadStatus.QUEUED)
        fixture.files = listOf(poster, fixture.main)
        assertNotNull(fixture.resolve())
    }

    @Test fun `same item on another server or user is never a local capability`() = runBlocking {
        val fixture = LocalPlaybackFixture(directory.toFile())
        fixture.rows = listOf(fixture.row.copy(serverId = 9), fixture.row.copy(userId = 9))
        assertNull(fixture.resolve())
        assertFalse(fixture.resolver.canPlay(fixture.item.id, 1, 2))
    }

    @Test fun `queued main missing file partial bytes and zero size are rejected`() = runBlocking {
        val fixture = LocalPlaybackFixture(directory.toFile())
        fixture.files = listOf(fixture.main.copy(status = DownloadStatus.DOWNLOADING))
        assertNull(fixture.resolve())
        fixture.files = listOf(fixture.main.copy(size = 0))
        assertNull(fixture.resolve())
        fixture.files = listOf(fixture.main)
        fixture.target.writeText("a")
        assertNull(fixture.resolve())
        assertTrue(fixture.target.delete())
        assertNull(fixture.resolve())
    }

    @Test fun `download row and usable cached source metadata are required`() = runBlocking {
        val fixture = LocalPlaybackFixture(directory.toFile())
        fixture.rows = listOf(fixture.row.copy(status = DownloadStatus.DOWNLOADING))
        assertNull(fixture.resolve())
        fixture.rows = listOf(fixture.row.copy(item = fixture.item.copy(mediaSources = emptyList())))
        assertNull(fixture.resolve())
        fixture.rows = listOf(fixture.row.copy(item = fixture.item.copy(id = UUID.randomUUID())))
        assertNull(fixture.resolve())
    }

    @Test fun `unavailable media version is not substituted with another downloaded version`() = runBlocking {
        val fixture = LocalPlaybackFixture(directory.toFile())
        assertNotNull(fixture.resolve("source"))
        assertNotNull(fixture.resolve(fixture.item.id.toString())) // Legacy Downloads-screen options.
        assertNull(fixture.resolve("another-version"))
    }

    @Test fun `local source preserves resume explicit zero and global stream indices`() = runBlocking {
        val fixture = LocalPlaybackFixture(directory.toFile())
        val source = fixtureMediaSource().copy(
            defaultAudioStreamIndex = AUDIO_INDEX,
            defaultSubtitleStreamIndex = SUBTITLE_INDEX,
            mediaStreams = listOf(
                fixtureMediaStream("Video", 0),
                fixtureMediaStream("Audio", AUDIO_INDEX),
                fixtureMediaStream("Subtitle", SUBTITLE_INDEX),
            ),
        )
        val item = fixture.item.copy(
            mediaSources = listOf(source),
            userData = UserItemDataDto(
                key = fixture.item.id.toString(),
                itemId = fixture.item.id,
                playbackPositionTicks = RESUME_TICKS,
                played = false,
                playCount = 0,
                isFavorite = false,
            ),
        )
        fixture.rows = listOf(fixture.row.copy(item = item))
        val candidate = requireNotNull(fixture.resolve())
        val resumed = candidate.createSource(PlaybackDetails(null, null, null), "resume")
        assertEquals(30.seconds, resumed.startTime)
        val restarted = candidate.createSource(PlaybackDetails(Duration.ZERO, AUDIO_INDEX, -1), "restart")
        assertEquals(Duration.ZERO, restarted.startTime)
        assertEquals(AUDIO_INDEX, restarted.selectedAudioStreamIndex)
        assertNull(restarted.selectedSubtitleStream)
    }

    @Test fun `network URLs and an image-only download are not playable files`() = runBlocking {
        val fixture = LocalPlaybackFixture(directory.toFile())
        fixture.files = listOf(fixture.main.copy(uri = fixture.uri("https")))
        assertNull(fixture.resolve())
        fixture.files = listOf(fixture.main.copy(type = DownloadFileType.IMAGE_PRIMARY))
        assertNull(fixture.resolve())
    }

    @Test fun `content URI requires read access and exact published length`() = runBlocking {
        val fixture = LocalPlaybackFixture(directory.toFile())
        val content = fixture.uri("content")
        fixture.files = listOf(fixture.main.copy(uri = content))
        val document = mockk<DocumentFile>()
        mockkStatic(DocumentFile::class)
        try {
            every { DocumentFile.fromSingleUri(fixture.context, content) } returns document
            every { document.isFile } returns true
            every { document.canRead() } returns true
            every { document.length() } returns 3
            assertNotNull(fixture.resolve())
            every { document.length() } returns 1
            assertNull(fixture.resolve())
            every { document.length() } returns 3
            every { document.canRead() } returns false
            assertNull(fixture.resolve())
            every { DocumentFile.fromSingleUri(fixture.context, content) } throws SecurityException("Revoked")
            assertNull(fixture.resolve())
        } finally {
            unmockkStatic(DocumentFile::class)
        }
    }
}

class ManagedLocalPlaybackResolverTest {
    @TempDir lateinit var directory: Path

    @Test fun `published managed file is playable only with matching ownership`() = runBlocking {
        val fixture = LocalPlaybackFixture(directory.toFile())
        fixture.manage()
        assertNotNull(fixture.resolve())
        fixture.owned = fixture.owned?.copy(account = "1-99")
        assertNull(fixture.resolve())
    }

    @Test fun `repair invalidation rejects old complete-sized published bytes`() = runBlocking {
        val fixture = LocalPlaybackFixture(directory.toFile())
        fixture.manage()
        fixture.owned = fixture.owned?.copy(bytes = 0)
        assertNull(fixture.resolve())
    }

    @Test fun `orphan ownership stale download id and replaced URI are rejected`() = runBlocking {
        val fixture = LocalPlaybackFixture(directory.toFile())
        fixture.manage()
        val owned = fixture.owned
        fixture.owned = null
        assertNull(fixture.resolve())
        fixture.owned = owned?.copy(downloadId = 99)
        assertNull(fixture.resolve())
        fixture.owned = owned
        val unrelated = File(directory.toFile(), "unrelated").also { it.writeText("abc") }
        fixture.files = listOf(fixture.main.copy(uri = fixture.uri("file", unrelated)))
        assertNull(fixture.resolve())
    }

    @Test fun `owned subtitles become local VTT and missing external tracks stay unselectable`() = runBlocking {
        val fixture = LocalPlaybackFixture(directory.toFile())
        fixture.manage()
        val english = fixtureMediaStream("Subtitle", 7).copy(
            codec = "subrip",
            isExternal = true,
            language = "eng",
            deliveryMethod = SubtitleDeliveryMethod.EXTERNAL,
            deliveryUrl = "https://unverified.invalid/subtitles",
        )
        val missing = english.copy(index = 8, language = "zho")
        val embedded = english.copy(index = 9, isExternal = false, language = "fra")
        val source = fixtureMediaSource().copy(mediaStreams = listOf(english, missing, embedded))
        fixture.rows = fixture.rows.map { it.copy(item = it.item.copy(mediaSources = listOf(source))) }
        val name = "${requireNotNull(fixture.owned).key}-subtitle-7.vtt"
        val target = File(fixture.target.parentFile, name).also { it.writeText("WEBVTT\n\n") }
        val subtitle = fixture.main.copy(
            type = DownloadFileType.SUBTITLE,
            fileName = name,
            uri = fixture.uri("file", target),
            size = target.length(),
        )
        fixture.files = fixture.files + subtitle
        val candidate = requireNotNull(fixture.resolve())
        val playback = candidate.createSource(PlaybackDetails(null, null, 7), "subtitles")
        assertEquals(listOf(7, 9), playback.subtitleStreams.map { it.index })
        assertEquals(7, playback.selectedSubtitleStreamIndex)
        assertEquals("webvtt", playback.selectedSubtitleStream?.codec)
        assertTrue(playback.externalSubtitleStreams.single().deliveryUrl.startsWith("file:"))
        assertFalse(playback.selectSubtitleStream(playback.mediaStreams.first { it.index == 8 }))
        assertTrue(playback.selectSubtitleStream(playback.subtitleStreams.last()))
    }

    @Test fun `partial foreign and replaced subtitle records never authorize a local subtitle`() = runBlocking {
        val fixture = LocalPlaybackFixture(directory.toFile())
        fixture.manage()
        val stream = fixtureMediaStream("Subtitle", 7).copy(codec = "subrip", isExternal = true)
        fixture.rows = fixture.rows.map {
            val source = fixtureMediaSource().copy(mediaStreams = listOf(stream))
            it.copy(item = it.item.copy(mediaSources = listOf(source)))
        }
        val name = "${requireNotNull(fixture.owned).key}-subtitle-7.vtt"
        val target = File(fixture.target.parentFile, name).also { it.writeText("WEBVTT\n\n") }
        val subtitle = fixture.main.copy(
            type = DownloadFileType.SUBTITLE,
            fileName = name,
            uri = fixture.uri("file", target),
            size = target.length(),
        )
        val main = fixture.files.single()
        val unrelated = File(directory.toFile(), "unrelated-subtitle").also { it.writeText("WEBVTT\n\n") }
        for (invalid in listOf(
            subtitle.copy(status = DownloadStatus.DOWNLOADING),
            subtitle.copy(downloadId = 999),
            subtitle.copy(size = subtitle.size + 1),
            subtitle.copy(uri = fixture.uri("https", target)),
            subtitle.copy(uri = fixture.uri("file", unrelated)),
        )) {
            fixture.files = listOf(main, invalid)
            val playback = requireNotNull(fixture.resolve()).createSource(PlaybackDetails(null, null, null), "invalid")
            assertTrue(playback.subtitleStreams.isEmpty())
            assertTrue(playback.externalSubtitleStreams.isEmpty())
        }
        assertEquals("WEBVTT\n\n", unrelated.readText())
    }
}

private class LocalPlaybackFixture(private val directory: File) {
    val context = mockk<Context>()
    private val downloads = mockk<DownloadDao>()
    private val smart = mockk<SmartOfflineDao>()
    val item = BaseItemDto(
        id = UUID.randomUUID(),
        type = BaseItemKind.EPISODE,
        mediaSources = listOf(fixtureMediaSource()),
    )
    val row = DownloadEntity(9, 1, 2, item.id, "manual", item, DownloadStatus.DOWNLOADED)
    var rows = listOf(row)
    var target = File(directory, "episode").also { it.writeText("abc") }
    val main = DownloadFileEntity(
        downloadId = row.id,
        type = DownloadFileType.ITEM,
        size = 3,
        fileName = "episode",
        uri = uri("file"),
        status = DownloadStatus.DOWNLOADED,
    )
    var files = listOf(main)
    var owned: SmartOfflineManaged? = null
    val resolver = LocalPlaybackResolver(context, downloads, smart)

    init {
        every { context.filesDir } returns directory
        every { downloads.getDownloadsByItemIds(listOf(item.id)) } answers { rows }
        coEvery { downloads.getFiles(row.id) } answers { files }
        coEvery { smart.owned(row.id) } answers { owned }
    }

    suspend fun resolve(source: String? = null): LocalPlaybackCandidate? = resolver.resolve(item.id, 1, 2, source)

    fun uri(scheme: String, file: File = target): Uri = mockk {
        every { getScheme() } returns scheme
        every { path } returns file.absolutePath
        every { authority } returns null
        every { this@mockk.toString() } returns
            if (scheme == "file") file.toURI().toString() else "$scheme://fixture/subtitle"
    }

    fun manage() {
        val key = "1-2-${item.id}"
        target = File(directory, "smart-offline/$key").also {
            it.parentFile?.mkdirs()
            it.writeText("abc")
        }
        owned = SmartOfflineManaged(key, "1-2", item, downloadId = row.id, bytes = 3)
        rows = listOf(row.copy(path = "smart-offline/$key"))
        files = listOf(main.copy(uri = uri("file")))
    }
}

private fun fixtureMediaSource(): MediaSourceInfo = Json.decodeFromString(
    """{
        "Id":"source", "Protocol":"File", "Type":"Default", "IsRemote":false,
        "ReadAtNativeFramerate":false, "IgnoreDts":false, "IgnoreIndex":false, "GenPtsInput":false,
        "SupportsTranscoding":false, "SupportsDirectStream":false, "SupportsDirectPlay":true,
        "IsInfiniteStream":false, "RequiresOpening":false, "RequiresClosing":false,
        "RequiresLooping":false, "SupportsProbing":false, "TranscodingSubProtocol":"http", "HasSegments":false
    }""",
)

private fun fixtureMediaStream(type: String, index: Int): MediaStream = Json.decodeFromString(
    """{
        "Type":"$type", "Index":$index, "IsInterlaced":false, "IsDefault":false, "IsForced":false,
        "IsHearingImpaired":false, "IsExternal":false, "IsTextSubtitleStream":false, "SupportsExternalStream":false
    }""",
)
