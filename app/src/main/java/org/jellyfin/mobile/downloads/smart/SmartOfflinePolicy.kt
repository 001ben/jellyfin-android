package org.jellyfin.mobile.downloads.smart

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jellyfin.sdk.model.api.BaseItemDto
import java.net.URI
import java.net.URISyntaxException
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

    fun lanUrl(value: String): String = LanUrlPolicy.canonical(value)

    fun isPrivateIpv4(host: String): Boolean = LanUrlPolicy.isPrivateIpv4(host)

    fun httpConsentUrl(value: String, confirmed: Boolean): String {
        if (!confirmed || value.isBlank()) return ""
        return lanUrl(value).takeIf { it.startsWith("http://") }.orEmpty()
    }

    fun isTrustedHttpLan(account: SmartOfflineAccount): Boolean {
        if (account.trustedHttpLanUrl.isBlank()) return false
        val canonical = runCatching { lanUrl(account.lanUrl) }.getOrNull() ?: return false
        return canonical.startsWith("http://") && account.trustedHttpLanUrl == canonical
    }

    fun sameServer(expected: String, actual: String?): Boolean = expected.isNotBlank() && expected == actual

    fun retryable(code: Int): Boolean =
        code == HTTP_REQUEST_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS || code >= HTTP_SERVER_ERROR
}

/** Transport syntax and address boundaries are independent of episode selection and storage policy. */
private object LanUrlPolicy {
    private const val IPV4_PARTS = 4
    private const val MAX_IPV4_OCTET = 255
    private const val MAX_PORT = 65535
    private const val PRIVATE_CLASS_A = 10
    private const val PRIVATE_CLASS_B = 172
    private const val PRIVATE_CLASS_C = 192
    private const val PRIVATE_CLASS_C_NETWORK = 168
    private const val PRIVATE_CLASS_B_MIN_NETWORK = 16
    private const val PRIVATE_CLASS_B_MAX_NETWORK = 31

    fun canonical(value: String): String {
        val uri = parse(value)
        require(
            uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null
        ) { "Use a server URL without credentials, query or fragment" }
        if (uri.scheme == "http") requireHttpAddress(uri)
        val url = value.trim().toHttpUrl()
        // Keep even an explicitly entered default HTTP port in the consent identity.
        val authority = if (uri.scheme == "http") {
            "${url.host}:${uri.port}"
        } else {
            url.toString().removePrefix("https://").substringBefore('/')
        }
        return "${url.scheme}://$authority${url.encodedPath.trimEnd('/')}"
    }

    private fun parse(value: String): URI = try {
        URI(value.trim())
    } catch (error: URISyntaxException) {
        throw IllegalArgumentException("Invalid server URL", error)
    }

    private fun requireHttpAddress(uri: URI) {
        require(uri.port in 1..MAX_PORT && isPrivateIpv4(uri.host)) {
            "HTTP home LAN requires a numeric private IPv4 address and an explicit port"
        }
    }

    fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != IPV4_PARTS) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        val canonicalOctets = parts.zip(octets).all { (text, value) ->
            value in 0..MAX_IPV4_OCTET && text == value.toString()
        }
        if (!canonicalOctets) return false
        return when (octets[0]) {
            PRIVATE_CLASS_A -> true
            PRIVATE_CLASS_B -> octets[1] in PRIVATE_CLASS_B_MIN_NETWORK..PRIVATE_CLASS_B_MAX_NETWORK
            PRIVATE_CLASS_C -> octets[1] == PRIVATE_CLASS_C_NETWORK
            else -> false
        }
    }
}
