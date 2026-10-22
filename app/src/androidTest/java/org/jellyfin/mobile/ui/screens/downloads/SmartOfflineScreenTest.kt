package org.jellyfin.mobile.ui.screens.downloads

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.smart.SmartOfflineAccount
import org.jellyfin.mobile.downloads.smart.SmartOfflineAction
import org.jellyfin.mobile.downloads.smart.SmartOfflineArtwork
import org.jellyfin.mobile.downloads.smart.SmartOfflineCatalogItem
import org.jellyfin.mobile.downloads.smart.SmartOfflineItemPhase
import org.jellyfin.mobile.downloads.smart.SmartOfflineSaveFeedback
import org.jellyfin.mobile.downloads.smart.SmartOfflineSeries
import org.jellyfin.mobile.downloads.smart.SmartOfflineSeriesFeedback
import org.jellyfin.mobile.downloads.smart.SmartOfflineStatus
import org.jellyfin.mobile.downloads.smart.SmartOfflineUiState
import org.jellyfin.mobile.downloads.smart.SmartOfflineWorkState
import org.jellyfin.mobile.ui.utils.AppTheme
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** All content/callbacks are fixtures: no sign-in, scheduling, downloads or cleanup are performed. */
class SmartOfflineScreenTest {
    @get:Rule val compose = createComposeRule()
    private val account = SmartOfflineAccount("test-1", 1, 2, enabled = true, remoteFallback = true)
    private val state = mutableStateOf(SmartOfflineUiState(account = account))
    private var saved: SmartOfflineAccount? = null
    private var callbacks = SmartOfflineCallbacks({ saved = it }, {}, { _, _ -> })

    private fun show(fontScale: Float = 1f) {
        compose.setContent {
            AppTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    SmartOfflineEditor(state.value, callbacks, {})
                }
            }
        }
    }

    @Test fun scrollingBackToTopKeepsFieldsAndSaveVisible() {
        show()
        screenshot("01-form-top")
        repeat(4) {
            compose.onNodeWithTag("smart-offline-form").performScrollToIndex(2)
            compose.onNodeWithText("3. Choose series").assertIsDisplayed()
            compose.onNodeWithTag("smart-offline-save").assertIsDisplayed()
            compose.onNodeWithTag("smart-offline-form").performScrollToIndex(0)
            compose.onNodeWithTag("smart-offline-count").assertIsDisplayed()
        }
        screenshot("02-scrolled-back-top")
    }

    @Test fun invalidAddressExplainsRequirementsAndCannotSave() {
        show()
        compose.onNodeWithTag("smart-offline-lan").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { keyboardShown() }
        compose.onNodeWithTag("smart-offline-lan").performTextReplacement("http://public.example:8096")
        compose.onNodeWithText(text(R.string.smart_offline_lan_error)).assertIsDisplayed()
        compose.onNodeWithTag("smart-offline-save").assertIsNotEnabled()
        screenshot("03-inline-address-error")
    }

    @Test fun httpRequiresVisibleAddressSpecificConsent() {
        show()
        compose.onNodeWithTag("smart-offline-lan").performScrollTo().performTextReplacement("http://192.168.4.10:8096")
        compose.onNodeWithText("Trust HTTP for this address").performScrollTo().performClick()
        compose.onNodeWithText("Trust this HTTP address?").assertIsDisplayed()
        screenshot("04-http-consent")
        compose.onNodeWithText("Trust this address").performClick()
        compose.onNodeWithTag("smart-offline-save").assertIsEnabled().performClick()
        assertEquals("http://192.168.4.10:8096", saved?.trustedHttpLanUrl)
        compose.onNodeWithTag("smart-offline-lan").performTextReplacement("http://192.168.4.11:8096")
        compose.onNodeWithTag("smart-offline-save").assertIsNotEnabled()
    }

    @Test fun workerUpdatesPreserveDraftAndFeedbackDistinguishesQueueFromRunning() {
        show()
        compose.onNodeWithTag("smart-offline-count").performTextReplacement("5")
        compose.runOnIdle {
            state.value = state.value.copy(
                account = account.copy(status = "Last pass finished"),
                status = SmartOfflineStatus(workState = SmartOfflineWorkState.RUNNING),
            )
        }
        compose.onNodeWithText("Check running").assertIsDisplayed()
        compose.onNodeWithTag("smart-offline-save").performClick()
        assertEquals(5, saved?.nextCount)
        compose.runOnIdle { state.value = state.value.copy(action = SmartOfflineAction.SAVE) }
        compose.onNodeWithText("Saving settings…").assertIsDisplayed()
        screenshot("05-saving")
        compose.runOnIdle {
            state.value = state.value.copy(
                account = saved,
                action = SmartOfflineAction.NONE,
                saveFeedback = SmartOfflineSaveFeedback.SAVED_AND_QUEUED,
                status = SmartOfflineStatus(workState = SmartOfflineWorkState.QUEUED),
            )
        }
        compose.onNodeWithText("Settings saved. Check queued; it has not necessarily started.").assertIsDisplayed()
        compose.onNodeWithText("Check queued").assertIsDisplayed()
        screenshot("06-saved-queued")
    }

    @Test fun selectedShowsAndSaveErrorsRemainVisible() {
        state.value = state.value.copy(
            series = listOf(
                SmartOfflineSeries("show-1", account.key, "00000000-0000-0000-0000-000000000001", "Example series"),
            ),
            error = "Settings saved, but Android could not queue a check. Try saving again.",
        )
        show()
        compose.onNodeWithTag("smart-offline-form").performScrollToIndex(2)
        compose.onNodeWithText("Selected series: 1").assertIsDisplayed()
        compose.onNodeWithText("Example series").assertIsDisplayed()
        compose.onNodeWithText(state.value.error).assertIsDisplayed()
        screenshot("07-series-and-error")
    }

    @Test fun largeTextAndKeyboardKeepSaveAccessible() {
        show(fontScale = 1.5f)
        compose.onNodeWithTag("smart-offline-lan").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { keyboardShown() }
        compose.onNodeWithTag("smart-offline-save").assertIsDisplayed()
        compose.onNodeWithText("Smart offline").assertIsDisplayed()
        screenshot("08-large-text-keyboard")
        compose.onNodeWithTag("smart-offline-form").performScrollToIndex(2)
        compose.onNodeWithTag("smart-offline-save").assertIsDisplayed()
        compose.onNodeWithText("Smart offline").assertIsDisplayed()
        screenshot("09-large-text-series")
    }

    @Test fun closeProtectsUnsavedSettings() {
        show()
        compose.onNodeWithTag("smart-offline-count").performTextReplacement("5")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val service = automation.serviceInfo
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service
        val editorWindow = requireNotNull(automation.rootInActiveWindow).windowId
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Discard unsaved settings?").assertIsDisplayed()
        screenshot("10-unsaved-confirmation")
        val confirmationWindow = requireNotNull(automation.rootInActiveWindow).windowId
        assertTrue("Confirmation must use a separate native window", editorWindow != confirmationWindow)
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Discard unsaved settings?").assertDoesNotExist()
        waitForEditorWindow(editorWindow, confirmationWindow)
        compose.onNodeWithTag("smart-offline-count").assertTextContains("5")
        screenshot("10b-kept-editing-before-save")
        compose.onNodeWithTag("smart-offline-save").assertIsDisplayed().assertIsEnabled().performClick()
        screenshot("10c-kept-editing-after-save")
        compose.runOnIdle { assertEquals(5, saved?.nextCount) }
    }

    @Test fun artworkBrowseSelectionAndUndoExplainKeptFiles() {
        val show = BaseItemDto(id = UUID.randomUUID(), name = "Example poster show", type = BaseItemKind.SERIES)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val poster = File(context.cacheDir, "fixture-poster.png")
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff8855dd.toInt()) }
        poster.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        state.value = state.value.copy(
            results = listOf(show),
            artwork = mapOf(show.id to SmartOfflineArtwork(poster.toURI().toString(), "fixture-art", "", false)),
        )
        var browse: String? = null
        callbacks = callbacks.copy(search = { browse = it }, toggle = { item, selected ->
            state.value = state.value.copy(
                series = if (selected) {
                    listOf(
                        SmartOfflineSeries(
                            "show", account.key, item.id.toString(),
                            item.name.orEmpty(), item
                        )
                    )
                } else {
                    emptyList()
                },
                seriesFeedback = SmartOfflineSeriesFeedback(item, selected),
            )
        })
        show()
        compose.onNodeWithTag("smart-offline-form").performScrollToIndex(2)
        compose.onNodeWithTag("smart-offline-browse").performScrollTo().performClick()
        assertEquals("", browse)
        compose.onNodeWithTag("smart-series-${show.id}").performScrollTo().performClick()
        compose.onNodeWithTag("smart-series-${show.id}").performScrollTo().assertIsDisplayed()
        screenshot("11-selected-poster")
        compose.onNodeWithTag("smart-series-${show.id}").performClick()
        compose.onNodeWithTag("smart-offline-selection-undo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.smart_offline_selection_removed).format(show.name))
            .assertIsDisplayed()
        compose.onNodeWithTag("smart-offline-selection-undo").performClick()
        assertEquals(1, state.value.series.size)
    }

    @Test fun episodeLifecycleSeparatesPendingWatchedCleanupAndRemoved() {
        val episode = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.EPISODE,
            name = "Pending episode",
            seriesName = "Example show",
        )
        val pending = SmartOfflineCatalogItem(
            "pending", episode, null, 1, SmartOfflineItemPhase.COMPLETE, 100, 100, "",
            watched = true, pendingSync = true, cleanupEligible = false, selected = true,
        )
        state.value = state.value.copy(
            catalog = listOf(
                pending,
                pending.copy(
                    key = "eligible", item = episode.copy(name = "Eligible episode"), pendingSync = false,
                    cleanupEligible = true
                ),
                pending.copy(
                    key = "removed", item = episode.copy(name = "Removed episode"), pendingSync = false,
                    phase = SmartOfflineItemPhase.REMOVED, removedAt = 1
                ),
            )
        )
        show()
        compose.onNodeWithTag("smart-offline-form").performScrollToIndex(3)
        compose.onNodeWithText(text(R.string.smart_offline_catalog_heading)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.smart_offline_item_watched_pending)).performScrollTo().assertIsDisplayed()
        screenshot("12-pending-watch-sync")
        compose.onNodeWithText(text(R.string.smart_offline_item_cleanup_eligible)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.smart_offline_item_removed)).performScrollTo().assertIsDisplayed()
        screenshot("13-cleanup-and-removed")
        compose.onNodeWithTag("smart-offline-save").assertIsDisplayed()
    }

    private fun text(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun keyboardShown(): Boolean {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "dumpsys input_method"
        )
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
            it.readText().contains("mInputShown=true")
        }
    }

    /** Compose idle does not wait for Android to return input focus after a nested Dialog window closes. */
    private fun waitForEditorWindow(editor: Int, confirmation: Int) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(timeoutMillis = 10_000) {
            val windows = automation.windows
            windows.none { it.id == confirmation } && windows.any { it.id == editor && it.isFocused }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(
            instrumentation.targetContext.getExternalFilesDir(null),
            "smart-offline-qa"
        ).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
