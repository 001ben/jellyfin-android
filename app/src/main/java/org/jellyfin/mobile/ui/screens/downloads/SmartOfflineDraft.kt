package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.smart.SmartOfflineAccount
import org.jellyfin.mobile.downloads.smart.SmartOfflinePolicy

/** Kept by account key, so live worker reports never replace unfinished edits. */
internal class SmartOfflineDraft(account: SmartOfflineAccount) {
    var settings by mutableStateOf(account)
    var count by mutableStateOf(account.nextCount.toString())
    var cap by mutableStateOf(account.capGiB.toString())

    val countError: Int?
        get() = if (count.toIntOrNull() in SmartOfflinePolicy.MIN_EPISODES..SmartOfflinePolicy.MAX_EPISODES) {
            null
        } else {
            R.string.smart_offline_count_error
        }
    val capError: Int?
        get() = if (cap.toIntOrNull() in SmartOfflinePolicy.MIN_CAP_GIB..SmartOfflinePolicy.MAX_CAP_GIB) {
            null
        } else {
            R.string.smart_offline_cap_error
        }
    val lanError: Int?
        get() {
            if (settings.lanUrl.isBlank()) return null
            val url = runCatching { SmartOfflinePolicy.lanUrl(settings.lanUrl) }.getOrNull()
                ?: return R.string.smart_offline_lan_error
            return if (url.startsWith("http:") && !SmartOfflinePolicy.isTrustedHttpLan(settings)) {
                R.string.smart_offline_http_consent_error
            } else {
                null
            }
        }
    val routeError: Int?
        get() = if (settings.enabled && settings.lanUrl.isBlank() && !settings.remoteFallback) {
            R.string.smart_offline_route_error
        } else {
            null
        }
    val canSave: Boolean get() = countError == null && capError == null && lanError == null && routeError == null

    fun setLanUrl(value: String) {
        val candidate = value.trim()
        val canonical = runCatching { SmartOfflinePolicy.lanUrl(candidate) }.getOrNull()
        settings = settings.copy(
            lanUrl = candidate,
            trustedHttpLanUrl = settings.trustedHttpLanUrl.takeIf { it == canonical }.orEmpty(),
        )
    }

    fun trustHttp(confirmed: Boolean) {
        settings = settings.copy(trustedHttpLanUrl = SmartOfflinePolicy.httpConsentUrl(settings.lanUrl, confirmed))
    }

    fun savedSettings(): SmartOfflineAccount? = if (canSave) {
        settings.copy(
            nextCount = count.toInt(),
            capGiB = cap.toInt(),
            lanUrl = settings.lanUrl.takeIf { it.isNotBlank() }?.let(SmartOfflinePolicy::lanUrl).orEmpty()
        )
    } else {
        null
    }

    fun matches(account: SmartOfflineAccount?): Boolean {
        if (account == null) return false
        val values = savedSettings() ?: return false
        // Identity and last worker status are live server state, not editable configuration.
        return values.copy(serverIdentity = account.serverIdentity, status = account.status) == account
    }
}
