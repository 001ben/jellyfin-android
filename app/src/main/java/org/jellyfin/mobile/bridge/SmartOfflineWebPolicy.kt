package org.jellyfin.mobile.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.net.URI
import java.util.UUID

/** The configured WebView origin is the authority; identifiers supplied by a page are only consistency checks. */
internal object SmartOfflineWebPolicy {
    private const val MAX_MESSAGE_LENGTH = 16_384
    private const val MAX_REQUEST_ID_LENGTH = 64
    private const val HTTPS_PORT = 443
    private const val HTTP_PORT = 80
    const val MAX_ITEMS = 80
    private val json = Json { ignoreUnknownKeys = true }

    fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        require(uri.scheme in setOf("http", "https") && uri.rawUserInfo == null && uri.host != null)
        val port = if (uri.port == -1) {
            if (uri.scheme == "https") HTTPS_PORT else HTTP_PORT
        } else {
            uri.port
        }
        URI(uri.scheme, null, uri.host.lowercase(), port, null, null, null).toASCIIString()
    }.getOrNull()

    fun accepts(configured: String, source: String, mainFrame: Boolean): Boolean =
        mainFrame && origin(configured)?.let { it == origin(source) } == true

    fun parse(raw: String): SmartOfflineWebRequest? = runCatching {
        require(raw.length <= MAX_MESSAGE_LENGTH)
        val request = json.decodeFromString<SmartOfflineWebRequest>(raw)
        require(request.requestId.length in 1..MAX_REQUEST_ID_LENGTH)
        require(request.action in setOf("snapshot", "manage", "play"))
        require(request.userId.toUUIDOrNull() != null && request.serverId.toUUIDOrNull() != null)
        require(request.itemIds.size <= MAX_ITEMS && request.itemIds.all { it.toUUIDOrNull() != null })
        require(request.action != "manage" || request.seriesId?.toUUIDOrNull() != null)
        require(request.action != "play" || request.itemId?.toUUIDOrNull() != null)
        request
    }.getOrNull()

    fun sameId(first: String, second: String): Boolean =
        first.toUUIDOrNull()?.let { it == second.toUUIDOrNull() } == true
}

@Serializable
internal data class SmartOfflineWebRequest(
    val requestId: String,
    val action: String,
    val userId: String,
    val serverId: String,
    val itemIds: List<String> = emptyList(),
    val seriesId: String? = null,
    val itemId: String? = null,
) {
    fun ids(): List<UUID> = itemIds.mapNotNull { it.toUUIDOrNull() }.distinct()
}

@Serializable
internal data class SmartOfflineWebItem(
    val id: String,
    val seriesId: String? = null,
    val phase: String = "",
    val progress: Double? = null,
    val watched: Boolean = false,
    val pendingSync: Boolean = false,
    val cleanupEligible: Boolean = false,
    val selected: Boolean = false,
    val canPlayLocal: Boolean = false,
)

@Serializable
internal data class SmartOfflineWebReply(
    val requestId: String,
    val items: List<SmartOfflineWebItem> = emptyList(),
    val error: String? = null,
)
