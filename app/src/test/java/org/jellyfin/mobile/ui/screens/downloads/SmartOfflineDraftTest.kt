package org.jellyfin.mobile.ui.screens.downloads

import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.smart.SmartOfflineAccount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartOfflineDraftTest {
    private val account = SmartOfflineAccount("1-2", 1, 2, enabled = true, remoteFallback = true)

    @Test
    fun `invalid fields carry visible errors and cannot become saved settings`() {
        val draft = SmartOfflineDraft(account)
        draft.count = "0"
        draft.cap = "large"
        assertEquals(R.string.smart_offline_count_error, draft.countError)
        assertEquals(R.string.smart_offline_cap_error, draft.capError)
        assertNull(draft.savedSettings())
        draft.count = "3"
        draft.cap = "10"
        assertTrue(draft.canSave)
    }

    @Test
    fun `enabled account requires an explicit permitted route`() {
        val draft = SmartOfflineDraft(account.copy(remoteFallback = false))
        assertEquals(R.string.smart_offline_route_error, draft.routeError)
        assertFalse(draft.canSave)
    }

    @Test
    fun `HTTP consent is explicit and cleared when address changes`() {
        val draft = SmartOfflineDraft(account)
        draft.setLanUrl("http://192.168.4.10:8096")
        assertEquals(R.string.smart_offline_http_consent_error, draft.lanError)
        draft.trustHttp(true)
        assertTrue(draft.canSave)
        assertEquals("http://192.168.4.10:8096", draft.savedSettings()?.trustedHttpLanUrl)
        draft.setLanUrl("http://192.168.4.11:8096")
        assertFalse(draft.canSave)
        assertTrue(draft.settings.trustedHttpLanUrl.isEmpty())
    }

    @Test
    fun `worker identity and status updates do not create unsaved configuration`() {
        val draft = SmartOfflineDraft(account)
        assertTrue(draft.matches(account.copy(status = "Checking", serverIdentity = "server-A")))
        draft.count = "5"
        assertFalse(draft.matches(account.copy(status = "Completed")))
        assertEquals(5, draft.savedSettings()?.nextCount)
    }
}
