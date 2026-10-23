package org.jellyfin.mobile.player

import android.net.Uri
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.downloads.smart.subtitleFixtureSource
import org.jellyfin.mobile.downloads.smart.subtitleFixtureStream
import org.jellyfin.mobile.player.source.LocalDownloadPlayback
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.mobile.player.source.PlaybackDetails
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

private const val ENGLISH_INDEX = 7

class SubtitleSelectionStateTest {
    @Test fun `selection before track preparation must not remember an unapplied English track`() = runBlocking {
        val fixture = SubtitleSelectionFixture(selected = -1)
        assertFalse(fixture.helper.selectSubtitleTrack(ENGLISH_INDEX))
        assertNull(fixture.source.selectedSubtitleStream)
    }

    @Test fun `a repeated selection without a prepared track must not report success`() = runBlocking {
        val fixture = SubtitleSelectionFixture(selected = -1)
        assertFalse(fixture.helper.selectSubtitleTrack(ENGLISH_INDEX))
        assertFalse(fixture.helper.selectSubtitleTrack(ENGLISH_INDEX))
    }

    @Test fun `a remembered English selection cannot substitute for a live player`() = runBlocking {
        val fixture = SubtitleSelectionFixture(selected = ENGLISH_INDEX)
        every { fixture.model.playerOrNull } returns null
        assertFalse(fixture.helper.selectSubtitleTrack(ENGLISH_INDEX))
    }
}

private class SubtitleSelectionFixture(selected: Int) {
    private val stream = subtitleFixtureStream(ENGLISH_INDEX).copy(
        codec = "webvtt",
        deliveryUrl = "file:///fixture/english.vtt",
    )
    private val info = subtitleFixtureSource().copy(mediaStreams = listOf(stream), defaultSubtitleStreamIndex = -1)
    private val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, mediaSources = listOf(info))
    val source = LocalJellyfinMediaSource(
        item.id,
        item,
        info,
        "fixture",
        PlaybackDetails(null, null, selected),
        LocalDownloadPlayback(1, mockk<Uri>()),
    )
    val model = mockk<PlayerViewModel>(relaxed = true)
    private val player = mockk<ExoPlayer>()
    val helper = TrackSelectionHelper(model, mockk<DefaultTrackSelector>(relaxed = true))

    init {
        every { model.mediaSourceOrNull } returns source
        every { model.playerOrNull } returns player
        every { player.currentTracks } returns Tracks.EMPTY
    }
}
