package org.jellyfin.mobile.player

import android.graphics.Bitmap
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.downloads.smart.DirectWifiRoute
import org.jellyfin.mobile.downloads.smart.DownloadNetwork
import org.jellyfin.mobile.downloads.smart.OfflineSubtitlesPending
import org.jellyfin.mobile.downloads.smart.SmartOfflineAccount
import org.jellyfin.mobile.downloads.smart.SmartOfflineDownloadStore
import org.jellyfin.mobile.downloads.smart.SmartOfflineEndpoint
import org.jellyfin.mobile.downloads.smart.SmartOfflinePolicy
import org.jellyfin.mobile.downloads.smart.SmartOfflineSeries
import org.jellyfin.mobile.downloads.smart.VerifiedDownloadEndpoint
import org.jellyfin.mobile.player.source.PlaybackDetails
import org.jellyfin.mobile.player.source.localMediaItem
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

private const val SUBTITLE_TEXT = "Offline English subtitle"
private const val SUBTITLE_VTT = "WEBVTT\n\n00:00:00.000 --> 00:00:02.900\n$SUBTITLE_TEXT\n"
private const val SUBTITLE_INDEX = 7
private const val READY_TIMEOUT_MS = 15_000L
private const val PNG_QUALITY = 100

/** Synthetic loopback server, real Room and Media3. Never touches a phone or a user's Jellyfin server. */
@RunWith(AndroidJUnit4::class)
class OfflineSubtitlesEmulatorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun olderCompleteVideoGetsSubtitlesWithoutRedownloadAndCleanupKeepsUnrelatedFiles() = runBlocking<Unit> {
        val fixture = LocalPlaybackEmulatorFixture.create()
        try {
            SubtitleLoopback().use { server ->
                val item = fixture.configureSubtitles()
                val store = fixture.store()
                val account = fixture.account()
                val before = fixture.target.readBytes()
                assertTrue(store.download(account, server.endpoint, server.api, item))
                assertTrue(before.contentEquals(fixture.target.readBytes()))
                assertEquals(1, server.requests.size)
                assertTrue(server.requests.single().contains("/Subtitles/7/Stream.vtt"))
                assertFalse(server.requests.single().contains("/Download"))
                val files = fixture.database.downloadDao.getFiles(fixture.row.id)
                val subtitle = files.single { it.type == DownloadFileType.SUBTITLE }
                assertEquals(DownloadStatus.DOWNLOADED, subtitle.status)
                assertEquals(SUBTITLE_VTT.length.toLong(), subtitle.size)
                assertTrue(store.download(account, server.endpoint, server.api, item))
                assertEquals(1, server.requests.size)
                val owned = requireNotNull(fixture.smart.smartOfflineDao.owned(fixture.row.id))
                val target = File(requireNotNull(subtitle.uri.path))
                val partial = File(target.path + ".part").also { it.writeText("interrupted") }
                val unrelated = File(target.parentFile, "unrelated.srt").also { it.writeText("keep") }
                val foreign = File(target.parentFile, "99-99-${item.id}-subtitle-7.vtt").also { it.writeText("keep") }
                store.remove(owned, deleteFile = true)
                assertFalse(fixture.target.exists())
                assertFalse(target.exists())
                assertFalse(partial.exists())
                assertEquals("keep", unrelated.readText())
                assertEquals("keep", foreign.readText())
            }
        } finally { fixture.close() }
    }

    @Test fun subtitleFailureAndStorageCapRetainVideoAndRetryRepairsOnlySubtitles() = runBlocking<Unit> {
        val fixture = LocalPlaybackEmulatorFixture.create()
        try {
            SubtitleLoopback().use { server ->
                val item = fixture.configureSubtitles()
                val store = fixture.store()
                val account = fixture.account()
                server.status = 503
                assertThrows(OfflineSubtitlesPending::class.java) {
                    runBlocking { store.download(account, server.endpoint, server.api, item) }
                }
                assertTrue(requireNotNull(fixture.resolve()).mainFile.size > 0)
                val pending = requireNotNull(fixture.smart.smartOfflineDao.owned(fixture.row.id))
                assertTrue(pending.reason.contains("incomplete"))
                assertEquals("COMPLETE", pending.phase)
                server.status = 200
                val full = File(fixture.target.parentFile, "budget-fixture")
                RandomAccessFile(full, "rw").use { it.setLength(account.capGiB * SmartOfflinePolicy.GIB) }
                assertThrows(OfflineSubtitlesPending::class.java) {
                    runBlocking { store.download(account, server.endpoint, server.api, item) }
                }
                assertTrue(full.delete())
                assertTrue(store.download(account, server.endpoint, server.api, item))
                val source = requireNotNull(
                    fixture.resolve()
                ).createSource(PlaybackDetails(null, null, SUBTITLE_INDEX), "retry")
                assertEquals(SUBTITLE_INDEX, source.selectedSubtitleStreamIndex)
                assertEquals(1, source.externalSubtitleStreams.size)
            }
        } finally { fixture.close() }
    }

    @Test fun EnglishSubtitleRendersFromLocalFileWithNoServerAvailable() = runBlocking<Unit> {
        val fixture = LocalPlaybackEmulatorFixture.create()
        var player: ExoPlayer? = null
        try {
            SubtitleLoopback().use { server ->
                val item = fixture.configureSubtitles()
                fixture.store().download(fixture.account(), server.endpoint, server.api, item)
            } // Server is closed before the player is constructed.
            val source = requireNotNull(
                fixture.resolve()
            ).createSource(PlaybackDetails(null, null, SUBTITLE_INDEX), "render")
            val media = source.localMediaItem()
            assertEquals("file", media.localConfiguration?.uri?.scheme)
            assertEquals("file", media.localConfiguration?.subtitleConfigurations?.single()?.uri?.scheme)
            val cue = CompletableDeferred<Unit>()
            val firstFrame = CompletableDeferred<Unit>()
            player = withContext(Dispatchers.Main) {
                ExoPlayer.Builder(fixture.context).build().apply {
                    volume = 0f
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setPreferredTextLanguage("eng").setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
                    addListener(object : Player.Listener {
                        override fun onCues(cueGroup: CueGroup) {
                            if (cueGroup.cues.any { it.text.toString().contains(SUBTITLE_TEXT) }) cue.complete(Unit)
                        }
                        override fun onRenderedFirstFrame() { firstFrame.complete(Unit) }
                        override fun onPlayerError(error: PlaybackException) {
                            cue.completeExceptionally(error)
                            firstFrame.completeExceptionally(error)
                        }
                    })
                    setMediaSource(DefaultMediaSourceFactory(fixture.context).createMediaSource(media))
                    prepare()
                    playWhenReady = true
                }
            }
            showPlayer(requireNotNull(player))
            withTimeout(READY_TIMEOUT_MS) {
                firstFrame.await()
                cue.await()
            }
            screenshot()
        } finally {
            withContext(Dispatchers.Main) { player?.release() }
            fixture.close()
        }
    }

    private fun showPlayer(playback: Player) {
        compose.setContent {
            AndroidView(
                factory = { context ->
                    PlayerView(ContextThemeWrapper(context, R.style.AppTheme)).apply {
                        player = playback
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    private fun screenshot() {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "smart-offline-qa")
        check(directory.exists() || directory.mkdirs())
        File(directory, "15-local-english-subtitles.png").outputStream().use { output ->
            assertTrue(
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
            )
        }
    }
}

private suspend fun LocalPlaybackEmulatorFixture.configureSubtitles(): BaseItemDto {
    val user = requireNotNull(database.userDao.getUser(row.userId))
    database.userDao.update(user.id, "fixture-token")
    val stream = Json.decodeFromString<MediaStream>(
        """{"Type":"Subtitle", "Index":7, "Codec":"subrip", "Language":"eng", "DisplayTitle":"English",
        "IsInterlaced":false, "IsDefault":true, "IsForced":false, "IsHearingImpaired":false,
        "IsExternal":true, "IsTextSubtitleStream":true, "SupportsExternalStream":true}""",
    )
    val source = requireNotNull(row.item.mediaSources?.firstOrNull()).copy(
        mediaStreams = listOf(stream),
        defaultSubtitleStreamIndex = SUBTITLE_INDEX,
    )
    val item = row.item.copy(seriesId = UUID.randomUUID(), mediaSources = listOf(source))
    database.downloadDao.update(row.copy(item = item))
    smart.smartOfflineDao.save(owned.copy(item = item, phase = "COMPLETE"))
    smart.smartOfflineDao.save(account())
    smart.smartOfflineDao.subscribe(SmartOfflineSeries("fixture", owned.account, item.seriesId.toString(), "Fixture"))
    return item
}

private fun LocalPlaybackEmulatorFixture.account(): SmartOfflineAccount =
    SmartOfflineAccount(owned.account, row.serverId, row.userId, enabled = true, capGiB = 1, wifiOnly = false)

private fun LocalPlaybackEmulatorFixture.store(): SmartOfflineDownloadStore {
    val network = object : DownloadNetwork {
        override fun wifi(): DirectWifiRoute? = null
        override fun remoteAllowed(wifiOnly: Boolean): Boolean = true
    }
    return SmartOfflineDownloadStore(
        context,
        database,
        smart.smartOfflineDao,
        database.userDao,
        SmartOfflineEndpoint(network, OkHttpClient()),
        journal,
    )
}

private class SubtitleLoopback : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val requests = CopyOnWriteArrayList<String>()

    @Volatile var status = 200
    private val worker = thread(isDaemon = true, name = "subtitle-fixture") {
        while (!server.isClosed) {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    requests += requireNotNull(input.readLine())
                    while (!input.readLine().isNullOrBlank()) Unit
                    val bytes = SUBTITLE_VTT.toByteArray()
                    val headers = "HTTP/1.1 $status Fixture\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(headers.toByteArray() + bytes)
                }
            } catch (error: java.io.IOException) {
                if (!server.isClosed) throw error
            }
        }
    }
    val endpoint = VerifiedDownloadEndpoint("http://127.0.0.1:${server.localPort}/".toHttpUrl(), OkHttpClient(), false)
    val api = OkHttpFactory().let { factory ->
        factory.create(
            endpoint.url.toString(),
            "fixture-token",
            ClientInfo("fixture", "1"),
            DeviceInfo("fixture", "fixture"),
            HttpClientOptions(),
            factory,
        )
    }
    override fun close() {
        server.close()
        worker.join(1000)
    }
}
