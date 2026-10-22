package org.jellyfin.mobile.bridge

import android.net.Uri
import androidx.webkit.WebMessageCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jellyfin.mobile.data.entity.ServerEntity
import org.junit.jupiter.api.Test

class SmartOfflineWebMessageTest {
    @Test
    fun `binary messages cannot crash the main thread or reach actions`() {
        val message = mockk<WebMessageCompat>()
        every { message.type } returns WebMessageCompat.TYPE_ARRAY_BUFFER
        every { message.data } throws IllegalStateException("Binary messages have no string data")
        val origin = mockk<Uri>()
        every { origin.toString() } returns "https://media.example"
        val bridge = SmartOfflineWebBridge(
            ServerEntity(1, "https://media.example", 0),
            CoroutineScope(Dispatchers.Unconfined),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
        )
        bridge.onPostMessage(mockk(), message, origin, true, mockk())
        verify(exactly = 0) { message.data }
    }
}
