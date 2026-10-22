package org.jellyfin.mobile.bridge

import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.UserEntity

/** Captured again after asynchronous work, including the token, so a sign-out/account switch invalidates the reply. */
class SmartOfflineWebSession(
    private val preferences: AppPreferences,
    private val users: UserDao,
    private val server: ServerEntity,
) {
    internal fun capture(request: SmartOfflineWebRequest): UserEntity? {
        if (preferences.currentServerId != server.id) return null
        val id = preferences.currentUserId ?: return null
        val user = users.getServerUser(server.id, id)?.user ?: return null
        return user.takeIf {
            !it.accessToken.isNullOrBlank() && SmartOfflineWebPolicy.sameId(request.userId, it.userId.toString())
        }
    }
}
