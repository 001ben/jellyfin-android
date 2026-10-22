package org.jellyfin.mobile.bridge

import org.jellyfin.mobile.player.interaction.PlayOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration

class LocalPlayOptionsTest {
    @Test
    fun `explicit play from beginning survives web option parsing`() {
        val options = PlayOptions.fromJson("""{"ids":[],"startPositionTicks":0,"subtitleStreamIndex":-1}""")
        assertEquals(Duration.ZERO, options?.startPosition)
        assertEquals(-1, options?.subtitleStreamIndex)
        assertNull(PlayOptions.fromJson("""{"ids":[],"startPositionTicks":-1}""")?.startPosition)
    }
}
