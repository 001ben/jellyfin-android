package org.jellyfin.mobile.downloads.smart

import org.jellyfin.sdk.model.api.BaseItemDto
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Pure policy shared by the worker and its tests. Specials and unaired/missing episodes are excluded. */
object SmartOfflinePolicy {
    const val GIB = 1024L * 1024 * 1024
    const val FREE_SPACE_RESERVE = 256L * 1024 * 1024
    const val MIN_EPISODES = 1
    const val MAX_EPISODES = 20
    const val MIN_CAP_GIB = 1
    const val MAX_CAP_GIB = 100
    private const val HTTP_REQUEST_TIMEOUT = 408
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_SERVER_ERROR = 500

    fun nextEpisodes(
        episodes: List<BaseItemDto>,
        count: Int,
        locallyFinished: Set<UUID> = emptySet(),
        now: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
    ): List<BaseItemDto> =
        episodes.asSequence()
            .filter { it.userData?.played != true && it.id !in locallyFinished }
            .filter(::hasEpisodeNumber)
            .filter { it.locationType?.name != "VIRTUAL" }
            .filter { it.premiereDate?.isAfter(now) != true }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.parentIndexNumber }, { it.indexNumber }, { it.id }))
            .take(count.coerceIn(MIN_EPISODES, MAX_EPISODES))
            .toList()

    private fun hasEpisodeNumber(item: BaseItemDto): Boolean =
        (item.parentIndexNumber ?: 0) > 0 && (item.indexNumber ?: 0) > 0

    fun fits(size: Long, used: Long, cap: Long, free: Long): Boolean =
        size > 0 && used >= 0 && used <= cap && size <= cap - used && free >= FREE_SPACE_RESERVE &&
            size <= free - FREE_SPACE_RESERVE

    fun canClean(enabled: Boolean, owned: Boolean, serverPlayed: Boolean, pending: Boolean, playing: Boolean): Boolean =
        enabled && owned && serverPlayed && !pending && !playing

    fun lanUrl(value: String): String {
        val uri = URI(value.trim())
        require(
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null
        ) { "Use an HTTPS server URL without credentials, query or fragment" }
        return value.trim().trimEnd('/')
    }

    fun sameServer(expected: String, actual: String?): Boolean = expected.isNotBlank() && expected == actual

    fun retryable(code: Int): Boolean =
        code == HTTP_REQUEST_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS || code >= HTTP_SERVER_ERROR
}
