package org.jellyfin.mobile.ui.screens.downloads

import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.smart.SmartOfflineCatalogItem
import org.jellyfin.mobile.downloads.smart.SmartOfflineItemPhase
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class SmartOfflineLifecycleTest {
    private val complete = SmartOfflineCatalogItem(
        "episode", BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE),
        null, 1, SmartOfflineItemPhase.COMPLETE,
        100, 100, "", watched = false, pendingSync = false, cleanupEligible = false, selected = true,
    )

    @Test
    fun `watched pending sync stays distinct from cleanup eligibility`() {
        assertEquals(R.string.smart_offline_item_complete, smartOfflineLifecycleLabel(complete))
        assertEquals(
            R.string.smart_offline_item_watched_pending,
            smartOfflineLifecycleLabel(complete.copy(watched = true, pendingSync = true, cleanupEligible = true)),
        )
        assertEquals(
            R.string.smart_offline_item_cleanup_eligible,
            smartOfflineLifecycleLabel(complete.copy(watched = true, cleanupEligible = true)),
        )
        assertEquals(
            R.string.smart_offline_item_watched_kept,
            smartOfflineLifecycleLabel(complete.copy(watched = true)),
        )
    }

    @Test
    fun `partial progress is not described as watched or retained and removal is explicit`() {
        assertEquals(
            R.string.smart_offline_item_progress_pending,
            smartOfflineLifecycleLabel(complete.copy(pendingSync = true)),
        )
        assertEquals(
            R.string.smart_offline_item_watched,
            smartOfflineLifecycleLabel(complete.copy(phase = SmartOfflineItemPhase.PARTIAL, watched = true)),
        )
        assertEquals(
            R.string.smart_offline_item_removed,
            smartOfflineLifecycleLabel(complete.copy(phase = SmartOfflineItemPhase.REMOVED, watched = true)),
        )
    }
}
