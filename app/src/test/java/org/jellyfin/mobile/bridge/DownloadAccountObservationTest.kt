package org.jellyfin.mobile.bridge

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.utils.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DownloadAccountObservationTest {
    @Test
    fun `download account observers update immediately on switch and unregister on close`() = runBlocking {
        val context = mockk<Context>()
        val storage = mockk<SharedPreferences>(relaxed = true)
        var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        var server = 1L
        var user = 2L
        every { context.packageName } returns "test.jellyfin"
        every { context.getSharedPreferences(any(), any()) } returns storage
        every { storage.getLong(Constants.PREF_SERVER_ID, any()) } answers { server }
        every { storage.getLong(Constants.PREF_USER_ID, any()) } answers { user }
        every { storage.registerOnSharedPreferenceChangeListener(any()) } answers { listener = firstArg() }
        val accounts = mutableListOf<Pair<Long?, Long?>>()
        val preferences = AppPreferences(context)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            preferences.currentAccountChanges.collect { accounts.add(it) }
        }
        yield()
        yield()
        server = 3
        user = 4
        listener?.onSharedPreferenceChanged(storage, Constants.PREF_SERVER_ID)
        listener?.onSharedPreferenceChanged(storage, Constants.PREF_USER_ID)
        yield()
        yield()
        user = -1
        listener?.onSharedPreferenceChanged(storage, Constants.PREF_USER_ID)
        yield()
        yield()
        assertEquals(listOf(1L to 2L, 3L to 4L, 3L to null), accounts)
        job.cancel()
        job.join()
        verify { storage.unregisterOnSharedPreferenceChangeListener(any()) }
    }
}
