package org.jellyfin.mobile.player

import android.graphics.Bitmap
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.mobile.player.source.PlaybackDetails
import org.jellyfin.mobile.player.source.localMediaItem
import org.jellyfin.mobile.utils.clearSelectionAndDisableRendererByType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val ENGLISH_INDEX = 7
private const val RESUME_MS = 1000L
private const val TIMEOUT_MS = 15_000L
private const val PNG_QUALITY = 100

private enum class SelectionJourney { EARLY_RETRY, REPAIR_DISABLED }

/** Real Media3 and the production selection function; only synthetic local files, no phone or server media. */
@RunWith(AndroidJUnit4::class)
class SubtitleSelectionEmulatorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedEarlyEnglishSelectionCanBeRetriedAfterPreparation() = runBlocking<Unit> {
        render(SelectionJourney.EARLY_RETRY)
    }

    @Test fun rememberedEnglishSelectionRepairsDisabledRendererAndDisplaysCaption() = runBlocking<Unit> {
        render(SelectionJourney.REPAIR_DISABLED)
    }

    private suspend fun render(journey: SelectionJourney) {
        val fixture = LocalPlaybackEmulatorFixture.create()
        var player: ExoPlayer? = null
        try {
            SubtitleLoopback().use { server ->
                val item = fixture.configureSubtitles()
                fixture.store().download(fixture.account(), server.endpoint, server.api, item)
            }
            val selected = if (journey == SelectionJourney.REPAIR_DISABLED) ENGLISH_INDEX else -1
            val source = requireNotNull(
                fixture.resolve()
            ).createSource(PlaybackDetails(null, null, selected), "selection")
            val ready = CompletableDeferred<Unit>()
            val caption = CompletableDeferred<Unit>()
            lateinit var selector: DefaultTrackSelector
            player = withContext(Dispatchers.Main) {
                selector = DefaultTrackSelector(fixture.context)
                selector.clearSelectionAndDisableRendererByType(C.TRACK_TYPE_TEXT)
                ExoPlayer.Builder(fixture.context).setTrackSelector(selector).build().apply {
                    volume = 0f
                    addListener(selectionListener(ready, caption))
                    setMediaSource(DefaultMediaSourceFactory(fixture.context).createMediaSource(source.localMediaItem()))
                    if (journey == SelectionJourney.EARLY_RETRY) verifyEarlyFailure(source, this, selector)
                    prepare()
                    seekTo(RESUME_MS)
                    playWhenReady = false
                }
            }
            showPlayer(requireNotNull(player))
            withTimeout(TIMEOUT_MS) { ready.await() }
            withContext(Dispatchers.Main) {
                assertTrue(selector.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
                assertTrue(applySubtitleTrackSelection(source, source.subtitleStreams.single(), player, selector))
                requireNotNull(player).playWhenReady = true
            }
            withTimeout(TIMEOUT_MS) { caption.await() }
            screenshot(journey)
        } finally {
            withContext(Dispatchers.Main) { player?.release() }
            fixture.close()
        }
    }

    private fun verifyEarlyFailure(source: LocalJellyfinMediaSource, player: Player, selector: DefaultTrackSelector) {
        assertFalse(applySubtitleTrackSelection(source, source.subtitleStreams.single(), player, selector))
        assertNull(source.selectedSubtitleStream)
    }

    private fun selectionListener(ready: CompletableDeferred<Unit>, caption: CompletableDeferred<Unit>): Player.Listener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) ready.complete(Unit)
            }
            override fun onCues(cues: CueGroup) {
                if (cues.cues.any { it.text.toString().contains("Offline English subtitle") }) caption.complete(Unit)
            }
            override fun onPlayerError(error: PlaybackException) {
                ready.completeExceptionally(error)
                caption.completeExceptionally(error)
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
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
        }
    }

    private fun screenshot(journey: SelectionJourney) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.getExternalFilesDir(null), "smart-offline-qa")
        check(root.exists() || root.mkdirs())
        File(root, "16-subtitle-${journey.name.lowercase()}.png").outputStream().use { output ->
            assertTrue(
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
            )
        }
    }
}
