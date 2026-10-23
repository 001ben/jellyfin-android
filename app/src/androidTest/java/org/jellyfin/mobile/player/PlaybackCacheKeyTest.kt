package org.jellyfin.mobile.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jellyfin.mobile.utils.playbackCacheKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

private const val ITEM_ID = "00000000-0000-0000-0000-000000000123"

@RunWith(AndroidJUnit4::class)
class PlaybackCacheKeyTest {
    @Test fun localVideoAndEachSubtitleHaveDistinctKeysWithoutUuidDirectories() {
        val video = "file:///fixture/smart-offline/1-1-$ITEM_ID"
        val english = "$video-subtitle-0.vtt"
        val chinese = "$video-subtitle-1.vtt"
        val uris = listOf(video, english, chinese)
        assertEquals(uris, uris.map { key(it) })
        assertEquals(uris.size, uris.map { key(it) }.toSet().size)
    }

    @Test fun documentProviderUrisRemainDistinctWithoutServerIds() {
        val first = "content://fixture.documents/document/english"
        val second = "content://fixture.documents/document/chinese"
        assertEquals(listOf(first, second), listOf(first, second).map { key(it) })
    }

    @Test fun explicitDownloadKeysRetainPrecedence() {
        listOf("file:///fixture/video", "content://fixture/video", "https://fixture.invalid/video").forEach {
            assertEquals("existing-key", key(it, "existing-key"))
        }
    }

    @Test fun existingRemoteVideoAndSubtitleDownloadCacheKeysArePreserved() {
        assertEquals(ITEM_ID, key("https://fixture.invalid/Videos/$ITEM_ID/stream"))
        assertEquals("$ITEM_ID:7", key("https://fixture.invalid/Videos/$ITEM_ID/Subtitles/7/0/Stream.subrip"))
        assertEquals("$ITEM_ID:8", key("https://fixture.invalid/Videos/$ITEM_ID/8.subrip"))
    }

    private fun key(uri: String, explicit: String? = null): String =
        playbackCacheKey(DataSpec.Builder().setUri(Uri.parse(uri)).setKey(explicit).build())
}
