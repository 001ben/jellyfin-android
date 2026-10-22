package org.jellyfin.mobile.downloads.smart

import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class SmartOfflineArtworkTest {
    private val item = BaseItemDto(
        id = UUID.randomUUID(),
        type = BaseItemKind.SERIES,
        imageTags = mapOf(ImageType.PRIMARY to "poster-tag"),
    )

    @Test
    fun `poster captures account endpoint without exposing credentials in URL or cache keys`() {
        val factory = OkHttpFactory()
        val api = factory.create(
            "https://legacy-user:legacy-pass@configured.example/jellyfin/",
            "private-token",
            ClientInfo("test", "1"),
            DeviceInfo("test", "device"),
            HttpClientOptions(),
            factory,
        )
        val image = requireNotNull(SmartOfflineArtwork.capture("1-2", api, item, false))
        assertEquals(
            "https://configured.example/jellyfin/Items/${item.id}/Images/Primary",
            image.url.substringBefore('?')
        )
        assertFalse(image.url.contains("private-token"))
        assertFalse(image.cacheKey.contains("private-token"))
        assertFalse(image.cacheKey.contains("configured.example"))
        assertFalse(image.allowNetwork)
        val other = requireNotNull(SmartOfflineArtwork.capture("1-3", api, item, true))
        assertNotEquals(image.cacheKey, other.cacheKey)
        api.update(baseUrl = "https://changed.example/", accessToken = "changed-token")
        assertFalse(image.url.contains("changed.example"))
    }

    @Test
    fun `missing primary artwork uses a local placeholder without a request`() {
        val factory = OkHttpFactory()
        val api = factory.create(
            "https://configured.example/",
            "token",
            ClientInfo("test", "1"),
            DeviceInfo("test", "device"),
            HttpClientOptions(),
            factory,
        )
        assertNull(SmartOfflineArtwork.capture("1-2", api, item.copy(imageTags = emptyMap()), true))
    }
}
