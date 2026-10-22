package org.jellyfin.mobile.player

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.downloads.smart.OfflinePlaybackJournal
import org.jellyfin.mobile.downloads.smart.OfflinePlaybackSnapshot
import org.jellyfin.mobile.downloads.smart.SmartOfflineDatabase
import org.jellyfin.mobile.downloads.smart.SmartOfflineManaged
import org.jellyfin.mobile.player.queue.preferLocalPlayback
import org.jellyfin.mobile.player.source.LocalPlaybackCandidate
import org.jellyfin.mobile.player.source.LocalPlaybackResolver
import org.jellyfin.mobile.player.source.PlaybackDetails
import org.jellyfin.mobile.ui.utils.AppTheme
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Local fixture coverage: real Media3 and Room; no Jellyfin server, browse UI, worker or phone integration.
 * The test MP4 is generated color video (FFmpeg lavfi, H264 baseline, 96x64, 12 fps, three seconds, no audio).
 */
@RunWith(AndroidJUnit4::class)
class LocalOfflinePlaybackEmulatorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun completeLocalVideoPlaysWhenRemoteIsUnavailableAndProgressRemainsPending() = runBlocking<Unit> {
        val fixture = LocalPlaybackEmulatorFixture.create()
        try {
            var remoteCalls = 0
            val error = preferLocalPlayback(
                downloadsOnly = false,
                resolve = { fixture.resolve() },
                local = { candidate ->
                    assertTrue(fixture.journal.started(candidate.download.id, SESSION))
                    val position = playLocalVideo(fixture.context, candidate)
                    fixture.journal.record(
                        OfflinePlaybackSnapshot(
                            candidate.download.id,
                            position.milliseconds.inWholeTicks,
                            finished = false,
                            stopped = false,
                            session = SESSION,
                        ),
                    )
                    null
                },
                remote = {
                    remoteCalls++
                    PlayerException.NetworkFailure(IOException("Fixture remote endpoint unavailable"))
                },
            )
            assertNull(error)
            assertEquals(0, remoteCalls)
            val progress = requireNotNull(fixture.smart.smartOfflineDao.progress(fixture.row.id))
            assertEquals(fixture.row.serverId, progress.serverId)
            assertEquals(fixture.row.userId, progress.userId)
            assertEquals(fixture.row.itemId, progress.itemId)
            assertTrue(progress.position > 0)
            assertFalse(progress.finished)
            assertFalse(progress.stopReported)
            assertTrue(fixture.journal.isPlaying(fixture.row.id))
            // Release only the test session, without enqueueing synchronization or acknowledging its outbox.
            fixture.journal.abandon(fixture.row.id, SESSION)
            assertFalse(fixture.journal.isPlaying(fixture.row.id))
            assertNotNull(fixture.smart.smartOfflineDao.progress(fixture.row.id))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun partialFilesRepairingDownloadsAndSameItemOnAnotherAccountCannotBecomePlayable() = runBlocking<Unit> {
        val fixture = LocalPlaybackEmulatorFixture.create()
        try {
            assertNotNull(fixture.resolve())
            assertNull(fixture.resolver.resolve(fixture.row.itemId, fixture.row.serverId, fixture.row.userId + 1))
            assertNull(fixture.resolver.resolve(fixture.row.itemId, fixture.row.serverId + 1, fixture.row.userId))
            fixture.database.downloadDao.updateFile(fixture.main.copy(status = DownloadStatus.DOWNLOADING))
            assertNull(fixture.resolve())
            fixture.database.downloadDao.updateFile(fixture.main)
            val complete = fixture.target.readBytes()
            fixture.target.writeBytes(complete.copyOf(complete.size / 2))
            assertNull(fixture.resolve())
            assertFalse(fixture.journal.started(fixture.row.id, SESSION))
            fixture.target.writeBytes(complete)
            fixture.smart.smartOfflineDao.save(fixture.owned.copy(bytes = 0))
            assertNull(fixture.resolve())
            assertFalse(fixture.journal.started(fixture.row.id, SESSION))
        } finally {
            fixture.close()
        }
    }

    private suspend fun playLocalVideo(context: Context, candidate: LocalPlaybackCandidate): Long {
        val ready = CompletableDeferred<Unit>()
        val firstFrame = CompletableDeferred<Unit>()
        val playbackPlayer = prepareLocalPlayer(context, candidate, ready, firstFrame)
        try {
            showPlayer(playbackPlayer)
            withTimeout(READY_TIMEOUT_MS) { ready.await() }
            withTimeout(READY_TIMEOUT_MS) { firstFrame.await() }
            val position = withTimeout(READY_TIMEOUT_MS) {
                var position = 0L
                while (position < MIN_PLAYED_MS) {
                    delay(POLL_MS)
                    position = withContext(Dispatchers.Main) { playbackPlayer.currentPosition }
                }
                position
            }
            screenshot()
            return position
        } finally {
            withContext(Dispatchers.Main) { playbackPlayer.release() }
        }
    }

    private suspend fun prepareLocalPlayer(
        context: Context,
        candidate: LocalPlaybackCandidate,
        ready: CompletableDeferred<Unit>,
        firstFrame: CompletableDeferred<Unit>,
    ): ExoPlayer = withContext(Dispatchers.Main) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.complete(Unit)
                }

                override fun onPlayerError(error: PlaybackException) {
                    ready.completeExceptionally(error)
                    firstFrame.completeExceptionally(error)
                }

                override fun onRenderedFirstFrame() {
                    firstFrame.complete(Unit)
                }
            })
            val source = candidate.createSource(PlaybackDetails(null, null, null), SESSION)
            val item = MediaItem.fromUri(source.remoteFileUri)
            // This is the same file/content URI path passed by QueueManager's native playback route.
            setMediaSource(DefaultMediaSourceFactory(context).createMediaSource(item))
            prepare()
            playWhenReady = true
        }
    }

    private fun showPlayer(playbackPlayer: Player) {
        compose.setContent {
            AppTheme {
                Column(Modifier.fillMaxSize().background(Color.Black)) {
                    Text(
                        "SYNTHETIC LOCAL H264 - emulator fixture, no server",
                        color = Color.White,
                        modifier = Modifier.padding(FIXTURE_PADDING_DP.dp),
                    )
                    AndroidView(
                        factory = { viewContext ->
                            PlayerView(ContextThemeWrapper(viewContext, R.style.AppTheme)).apply {
                                useController = false
                                player = playbackPlayer
                            }
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }

    private fun screenshot() {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "smart-offline-qa")
        check(directory.exists() || directory.mkdirs())
        File(directory, "14-local-video-fixture.png").outputStream().use { output ->
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output))
        }
    }

    private companion object {
        const val SESSION = "emulator-local-fixture"
        const val READY_TIMEOUT_MS = 15_000L
        const val MIN_PLAYED_MS = 150L
        const val POLL_MS = 25L
        const val FIXTURE_PADDING_DP = 16
        const val PNG_QUALITY = 100
    }
}

private class LocalPlaybackEmulatorFixture(
    val context: Context,
    val database: JellyfinDatabase,
    val smart: SmartOfflineDatabase,
    val row: DownloadEntity,
    val main: DownloadFileEntity,
    val owned: SmartOfflineManaged,
) {
    val resolver = LocalPlaybackResolver(context, database.downloadDao, smart.smartOfflineDao)
    val journal = OfflinePlaybackJournal(context, database.downloadDao, smart.smartOfflineDao)
    val target: File = File(context.filesDir, row.path)

    suspend fun resolve(): LocalPlaybackCandidate? = resolver.resolve(row.itemId, row.serverId, row.userId)

    fun close() {
        database.close()
        smart.close()
        context.filesDir.deleteRecursively()
    }

    companion object {
        suspend fun create(): LocalPlaybackEmulatorFixture = withContext(Dispatchers.IO) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = File(instrumentation.targetContext.cacheDir, "local-playback-${UUID.randomUUID()}")
            check(directory.mkdirs())
            val context = object : ContextWrapper(instrumentation.targetContext) {
                override fun getFilesDir(): File = directory
            }
            val database = Room.inMemoryDatabaseBuilder(context, JellyfinDatabase::class.java).build()
            val smart = Room.inMemoryDatabaseBuilder(context, SmartOfflineDatabase::class.java).build()
            val server = database.serverDao.insert("https://offline-fixture.invalid")
            val user = database.userDao.insert(server, UUID.randomUUID(), null)
            val item = BaseItemDto(
                id = UUID.randomUUID(),
                type = BaseItemKind.EPISODE,
                name = "Local playback fixture",
                mediaSources = listOf(
                    Json.decodeFromString<MediaSourceInfo>(
                        """{
                            "Id":"fixture-source", "Protocol":"File", "Type":"Default", "IsRemote":false,
                            "ReadAtNativeFramerate":false, "IgnoreDts":false, "IgnoreIndex":false, "GenPtsInput":false,
                            "SupportsTranscoding":false, "SupportsDirectStream":false, "SupportsDirectPlay":true,
                            "IsInfiniteStream":false, "RequiresOpening":false, "RequiresClosing":false,
                            "RequiresLooping":false, "SupportsProbing":false,
                            "TranscodingSubProtocol":"http", "HasSegments":false
                        }""",
                    ),
                ),
            )
            val key = "$server-$user-${item.id}"
            val target = File(directory, "smart-offline/$key")
            check(requireNotNull(target.parentFile).mkdirs())
            instrumentation.context.assets.open("smart_offline_local_fixture.mp4").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            val pending = DownloadEntity(
                serverId = server,
                userId = user,
                itemId = item.id,
                path = "smart-offline/$key",
                item = item,
                status = DownloadStatus.DOWNLOADED,
            )
            val row = pending.copy(id = database.downloadDao.insert(pending))
            val pendingFile = DownloadFileEntity(
                downloadId = row.id,
                type = DownloadFileType.ITEM,
                size = target.length(),
                fileName = key,
                uri = target.toUri(),
                status = DownloadStatus.DOWNLOADED,
            )
            val main = pendingFile.copy(id = database.downloadDao.insertFile(pendingFile))
            val owned = SmartOfflineManaged(key, "$server-$user", item, downloadId = row.id, bytes = target.length())
            smart.smartOfflineDao.save(owned)
            LocalPlaybackEmulatorFixture(context, database, smart, row, main, owned)
        }
    }
}
