package org.jellyfin.mobile.bridge

import io.mockk.every
import io.mockk.mockk
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.ServerUser
import org.jellyfin.mobile.data.entity.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class SmartOfflineWebSessionTest {
    private val preferences = mockk<AppPreferences>()
    private val users = mockk<UserDao>()
    private val server = ServerEntity(1, "https://media.example", 0)
    private val user = UserEntity(2, 1, UUID.randomUUID(), "test-only-token", 0)
    private val request = SmartOfflineWebRequest("1", "snapshot", user.userId.toString(), UUID.randomUUID().toString())
    private val session = SmartOfflineWebSession(preferences, users, server)

    private fun configure() {
        every { preferences.currentServerId } returns server.id
        every { preferences.currentUserId } returns user.id
        every { users.getServerUser(server.id, user.id) } returns ServerUser(user, server)
    }

    @Test
    fun `bound server and logged in native user must match the web request`() {
        configure()
        assertEquals(user, session.capture(request))
        assertNull(session.capture(request.copy(userId = UUID.randomUUID().toString())))
        every { preferences.currentServerId } returns 3
        assertNull(session.capture(request))
    }

    @Test
    fun `sign out and token replacement invalidate a previously captured session`() {
        configure()
        val captured = session.capture(request)
        every { users.getServerUser(server.id, user.id) } returns ServerUser(user.copy(accessToken = null), server)
        assertNull(session.capture(request))
        every { users.getServerUser(server.id, user.id) } returns ServerUser(
            user.copy(accessToken = "replacement"), server,
        )
        assertNotEquals(captured, session.capture(request))
        every { preferences.currentUserId } returns null
        assertNull(session.capture(request))
    }
}
