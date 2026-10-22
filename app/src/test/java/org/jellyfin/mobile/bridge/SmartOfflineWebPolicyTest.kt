package org.jellyfin.mobile.bridge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SmartOfflineWebPolicyTest {
    private val user = UUID.randomUUID().toString()
    private val server = UUID.randomUUID().toString()
    private val item = UUID.randomUUID().toString()
    private val request = SmartOfflineWebRequest("request-1", "snapshot", user, server, listOf(item))

    @Test
    fun `bridge accepts configured main origin including explicit default port only`() {
        assertTrue(SmartOfflineWebPolicy.accepts("https://media.example/jellyfin", "https://media.example:443", true))
        assertFalse(SmartOfflineWebPolicy.accepts("https://media.example", "https://media.example", false))
        assertFalse(SmartOfflineWebPolicy.accepts("https://media.example", "http://media.example", true))
        assertFalse(SmartOfflineWebPolicy.accepts("https://media.example", "https://media.example:8443", true))
        assertFalse(SmartOfflineWebPolicy.accepts("https://media.example", "https://other.example", true))
        assertFalse(SmartOfflineWebPolicy.accepts("file:///tmp/page", "file:///tmp/page", true))
        assertNull(SmartOfflineWebPolicy.origin("https://user:secret@media.example"))
    }

    @Test
    fun `batch validation rejects malformed oversized and unrelated actions`() {
        assertNotNull(SmartOfflineWebPolicy.parse(Json.encodeToString(request)))
        listOf(
            request.copy(itemIds = List(SmartOfflineWebPolicy.MAX_ITEMS + 1) { item }),
            request.copy(itemIds = listOf("not-an-item")),
            request.copy(userId = ""),
            request.copy(serverId = ""),
            request.copy(action = "delete"),
            request.copy(action = "manage"),
            request.copy(action = "play"),
            request.copy(requestId = "x".repeat(65)),
        ).forEach { assertNull(SmartOfflineWebPolicy.parse(Json.encodeToString(it))) }
        assertNull(SmartOfflineWebPolicy.parse(" ".repeat(16_385)))
        assertNull(SmartOfflineWebPolicy.parse("{"))
    }

    @Test
    fun `native actions require valid identifiers and duplicate batch ids are collapsed`() {
        val manage = request.copy(action = "manage", seriesId = item)
        val play = request.copy(action = "play", itemId = item)
        assertNotNull(SmartOfflineWebPolicy.parse(Json.encodeToString(manage)))
        assertNotNull(SmartOfflineWebPolicy.parse(Json.encodeToString(play)))
        assertEquals(listOf(UUID.fromString(item)), request.copy(itemIds = listOf(item, item)).ids())
        assertTrue(SmartOfflineWebPolicy.sameId(item, item.replace("-", "")))
        assertFalse(SmartOfflineWebPolicy.sameId(item, user))
        assertFalse(SmartOfflineWebPolicy.sameId("invalid", "invalid"))
    }
}
