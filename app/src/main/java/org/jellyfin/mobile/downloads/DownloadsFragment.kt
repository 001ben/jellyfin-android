package org.jellyfin.mobile.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.ui.ComposeFragment
import org.jellyfin.mobile.ui.screens.downloads.DownloadsScreen
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject

class DownloadsFragment : ComposeFragment() {
    private val preferences: AppPreferences by inject()

    @Composable
    override fun Content() {
        val identity by preferences.currentAccountChanges.collectAsState(
            initial = preferences.currentServerId to preferences.currentUserId,
        )
        val context = arguments?.takeIf { it.getLong(SERVER_ID, -1) to it.getLong(USER_ID, -1) == identity }
        DownloadsScreen(
            initialSeriesId = context?.getString(SERIES_ID)?.toUUIDOrNull(),
            onBackPressed = {
                isAdded && !parentFragmentManager.isStateSaved && parentFragmentManager.popBackStackImmediate()
            },
        )
    }

    companion object {
        const val SERIES_ID = "smart-offline-series-id"
        const val SERVER_ID = "smart-offline-server-id"
        const val USER_ID = "smart-offline-user-id"
    }
}
