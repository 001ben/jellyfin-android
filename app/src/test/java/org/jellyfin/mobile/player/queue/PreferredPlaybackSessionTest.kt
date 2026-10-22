package org.jellyfin.mobile.player.queue

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.downloads.smart.SmartOfflineManager
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.interaction.PlayerWebPreferences
import org.jellyfin.mobile.player.source.LocalPlaybackCandidate
import org.jellyfin.mobile.player.source.LocalPlaybackResolver
import org.jellyfin.mobile.player.source.PlaybackDetails
import org.jellyfin.sdk.api.client.ApiClient
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration

class PreferredPlaybackSessionTest {
    private val preferences = mockk<AppPreferences>()
    private val api = mockk<ApiClient>()
    private val resolver = mockk<LocalPlaybackResolver>()
    private val offline = mockk<SmartOfflineManager>()
    private val itemId = UUID.randomUUID()

    @Test fun `local playback preserves the request without inspecting the remote endpoint`() = runBlocking {
        val candidate = mockk<LocalPlaybackCandidate>()
        val request = PlaybackRequest(itemId, "version", PlaybackDetails(Duration.ZERO, 1, -1), false)
        every { preferences.currentServerId } returns 1
        every { preferences.currentUserId } returns 2
        coEvery { resolver.resolve(itemId, 1, 2, "version") } returns candidate
        var received: PlaybackRequest? = null
        val session = PreferredPlaybackSession(
            preferences,
            api,
            resolver,
            offline,
            local = { selected, actual ->
                assertSame(candidate, selected)
                received = actual
                null
            },
            remote = { _, _ -> error("Remote source must not start") },
        )
        assertNull(session.initialize(options(), PlayerWebPreferences(1, 2)))
        assertNull(session.start(request))
        assertSame(request, received)
        confirmVerified(api)
    }

    @Test fun `captured account mismatch is rejected before looking up a same-id item`() = runBlocking {
        every { preferences.currentServerId } returns 1
        every { preferences.currentUserId } returns 2
        val session = refusingSession()
        assertInstanceOf(
            PlayerException.InvalidPlayOptions::class.java,
            session.initialize(options().copy(accountServerId = 1, accountUserId = OTHER_USER), null),
        )
        assertInstanceOf(PlayerException.InvalidPlayOptions::class.java, session.start(PlaybackRequest(itemId)))
        coVerify(exactly = 0) { resolver.resolve(any(), any(), any(), any()) }
        confirmVerified(api)
    }

    @Test fun `account switch during local lookup cannot load or start remote metadata`() = runBlocking {
        var currentUser = 2L
        every { preferences.currentServerId } returns 1
        every { preferences.currentUserId } answers { currentUser }
        coEvery { resolver.resolve(itemId, 1, 2, null) } answers {
            currentUser = OTHER_USER
            null
        }
        val session = refusingSession()
        assertNull(session.initialize(options(), PlayerWebPreferences(1, 2)))
        assertInstanceOf(PlayerException.InvalidPlayOptions::class.java, session.start(PlaybackRequest(itemId)))
        confirmVerified(api)
    }

    private fun refusingSession(): PreferredPlaybackSession = PreferredPlaybackSession(
        preferences,
        api,
        resolver,
        offline,
        local = { _, _ -> error("A different account must not play") },
        remote = { _, _ -> error("A different account must not start a remote request") },
    )

    private fun options(): PlayOptions = PlayOptions(listOf(itemId), null, 0, null, null, null, false)

    private companion object {
        const val OTHER_USER = 9L
    }
}
