package org.jellyfin.mobile.downloads.smart

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID

class SmartOfflineSettingsTest {
    private val context = mockk<Context>()
    private val database = mockk<JellyfinDatabase>()
    private val downloads = mockk<DownloadDao>()
    private val dao = mockk<SmartOfflineDao>(relaxed = true)
    private val users = mockk<UserDao>()
    private val controller = mockk<ApiClientController>()
    private val endpoints = mockk<SmartOfflineEndpoint>()

    @Test
    fun `series toggle schedules fresh settings without rewriting its stale account snapshot`() = runBlocking {
        every { database.downloadDao } returns downloads
        val current = SmartOfflineAccount("1-2", 1, 2, enabled = true, nextCount = 9, status = "Latest worker report")
        coEvery { dao.account(current.key) } returns current
        coEvery { dao.insertAccountIfMissing(any()) } returns -1L
        val scheduled = mutableListOf<SmartOfflineAccount>()
        val manager =
            SmartOfflineManager(
                context,
                SmartOfflinePersistence(database, dao),
                users,
                controller,
                endpoints
            ) { scheduled += it }
        val stale = current.copy(enabled = false, nextCount = 1, status = "Old report")
        val item = BaseItemDto(id = UUID.randomUUID(), name = "Series", type = BaseItemKind.SERIES)
        val result = manager.subscribe(stale, item, true)
        assertEquals(listOf(current), scheduled)
        assertEquals(current, result.account)
        coVerify(exactly = 0) { dao.saveSettings(any()) }
        coVerify(exactly = 0) { dao.save(any<SmartOfflineAccount>()) }
    }

    @Test
    fun `failed scheduling reports durable save instead of queue success`() = runBlocking {
        every { database.downloadDao } returns downloads
        val requested = SmartOfflineAccount("1-2", 1, 2, enabled = true, nextCount = 5)
        val merged = requested.copy(serverIdentity = "Pinned server", status = "Last report")
        coEvery { dao.saveSettings(requested) } returns merged
        val manager =
            SmartOfflineManager(context, SmartOfflinePersistence(database, dao), users, controller, endpoints) {
                throw IOException("Scheduler unavailable")
            }
        val result = manager.save(requested)
        assertEquals(merged, result.account)
        assertEquals(SmartOfflineSaveFeedback.SAVED, result.feedback)
        assertTrue(result.schedulingFailed)
        coVerify(exactly = 1) { dao.saveSettings(requested) }
    }

    @Test
    fun `backend rejects missing or stale HTTP consent before writing settings`() {
        every { database.downloadDao } returns downloads
        val manager =
            SmartOfflineManager(context, SmartOfflinePersistence(database, dao), users, controller, endpoints) { }
        val request = SmartOfflineAccount("1-2", 1, 2, lanUrl = "http://10.0.0.20:8096")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager.save(request) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.save(request.copy(trustedHttpLanUrl = "http://10.0.0.21:8096")) }
        }
        coVerify(exactly = 0) { dao.saveSettings(any()) }
    }

    @Test
    fun `backend canonicalizes HTTP consent and clears it for HTTPS or blank URLs`() = runBlocking {
        every { database.downloadDao } returns downloads
        coEvery { dao.saveSettings(any()) } answers { firstArg<SmartOfflineAccount>() }
        val manager =
            SmartOfflineManager(context, SmartOfflinePersistence(database, dao), users, controller, endpoints) { }
        val consent = "http://10.0.0.20:8096/jellyfin"
        val request = SmartOfflineAccount("1-2", 1, 2, lanUrl = " $consent/ ", trustedHttpLanUrl = consent)
        val http = manager.save(request).account
        assertEquals(consent, http.lanUrl)
        assertEquals(consent, http.trustedHttpLanUrl)
        val https = manager.save(request.copy(lanUrl = " https://home.test/jellyfin/ ")).account
        assertEquals("https://home.test/jellyfin", https.lanUrl)
        assertEquals("", https.trustedHttpLanUrl)
        val blank = manager.save(request.copy(lanUrl = " ")).account
        assertEquals("", blank.lanUrl)
        assertEquals("", blank.trustedHttpLanUrl)
    }
}
