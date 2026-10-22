package org.jellyfin.mobile.player.queue

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID

/** Once a local source is chosen, failure is visible instead of silently starting a network stream. */
internal suspend fun <T : Any> preferLocalPlayback(
    downloadsOnly: Boolean,
    resolve: suspend () -> T?,
    local: suspend (T) -> PlayerException?,
    remote: suspend () -> PlayerException?,
): PlayerException? {
    val candidate = resolve()
    return when {
        candidate != null -> local(candidate)
        downloadsOnly -> PlayerException.UnsupportedContent()
        else -> remote()
    }
}

internal class PlaybackQueue {
    private var ids: List<UUID> = emptyList()
    private var index = 0
    private var pendingIndex: Int? = null
    private val transition = Mutex()

    fun initialize(options: PlayOptions): UUID? {
        ids = options.ids
        index = options.startIndex
        pendingIndex = null
        return if (ids.isEmpty()) options.mediaSourceId?.toUUIDOrNull() else ids.getOrNull(index)
    }

    // Source observers run before move returns; show navigation for the source being published.
    fun hasPrevious(): Boolean = (pendingIndex ?: index).let { it > 0 && it in ids.indices }
    fun hasNext(): Boolean = (pendingIndex ?: index).let { it >= 0 && it < ids.lastIndex }

    suspend fun move(offset: Int, start: suspend (UUID) -> PlayerException?): Boolean = transition.withLock {
        val target = index + offset
        val item = ids.getOrNull(target) ?: return@withLock false
        pendingIndex = target
        try {
            val started = start(item) == null
            if (started) index = target
            started
        } finally {
            pendingIndex = null
        }
    }
}
