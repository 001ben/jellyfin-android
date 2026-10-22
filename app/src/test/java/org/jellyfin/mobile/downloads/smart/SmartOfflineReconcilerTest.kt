package org.jellyfin.mobile.downloads.smart

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID

class SmartOfflineReconcilerTest {
    private val dao = mockk<SmartOfflineDao>(relaxed = true)
    private val users = mockk<UserDao>()
    private val controller = mockk<ApiClientController>()
    private val endpoints = mockk<SmartOfflineEndpoint>()
    private val store = mockk<SmartOfflineDownloadStore>(relaxed = true)
    private val episodeId = UUID.randomUUID()
    private val settings = SmartOfflineAccount("1-2", 1, 2, enabled = true, cleanup = true, serverIdentity = "server-A")
    private val remote = remoteApi()
    private val pending = OfflineProgress(9, 1, 2, episodeId, 1000, false, "revision", "generation", 1)

    @Test
    fun `remote IO failure still dispatches HTTP download without cleanup or playback acknowledgement`() = runBlocking {
        assertHttpReplenishesAfter(IOException("Remote is unavailable"))
    }

    @Test
    fun `remote API failure leaves watch reconciliation pending while trusted HTTP replenishes`() = runBlocking {
        assertHttpReplenishesAfter(ApiClientException("Remote request failed"))
    }

    @Test
    fun `cancellation during remote watched refresh stops HTTP replenishment`() = runBlocking {
        LocalHttpFixture().use { lan ->
            val reconciler = setup(lan)
            coEvery { store.refreshWatched(any(), any()) } throws CancellationException("Cancelled")
            assertThrows(CancellationException::class.java) { runBlocking { reconciler.reconcile(settings.key) } }
            coVerify(exactly = 0) { store.download(any(), any(), any(), any()) }
            assertTrue(lan.requests.isEmpty())
        }
    }

    @Test
    fun `verified HTTPS watched refresh failure remains fatal`() = runBlocking {
        LocalHttpFixture().use { unused ->
            val endpoint = VerifiedDownloadEndpoint("https://secure.example/".toHttpUrl(), OkHttpClient(), true)
            val reconciler = setup(unused, endpoint)
            coEvery { store.refreshWatched(any(), any()) } throws IOException("Watched refresh failed")
            assertThrows(IOException::class.java) { runBlocking { reconciler.reconcile(settings.key) } }
            coVerify(exactly = 0) { store.download(any(), any(), any(), any()) }
        }
    }

    private suspend fun assertHttpReplenishesAfter(failure: Exception) {
        LocalHttpFixture().use { lan ->
            val reconciler = setup(lan)
            coEvery { store.refreshWatched(any(), any()) } throws failure
            lan.handler = { exchange ->
                val response = "{\"Items\":[{\"Id\":\"$episodeId\",\"Type\":\"Episode\"," +
                    "\"ParentIndexNumber\":1,\"IndexNumber\":1}],\"TotalRecordCount\":1,\"StartIndex\":0}"
                lan.respond(exchange, 200, response.toByteArray())
            }

            reconciler.reconcile(settings.key)

            coVerify(exactly = 1) { store.refreshWatched(any(), remote) }
            coVerify(exactly = 1) {
                store.download(
                    any(),
                    match { it.trustedHttpLan },
                    match { it.baseUrl?.trimEnd('/') == lan.url.trimEnd('/') },
                    match { it.id == episodeId },
                )
            }
            coVerify(exactly = 0) { store.remove(any(), any(), any()) }
            coVerify(exactly = 0) { dao.acknowledged(any(), any()) }
            coVerify { dao.status(settings.key, match { it.endsWith("; watch synchronization pending") }) }
            assertEquals(listOf(pending), dao.pending())
            assertEquals(1, lan.requests.size)
            assertTrue(lan.requests.single().authorization.orEmpty().contains("test-token"))
        }
    }

    private fun setup(lan: LocalHttpFixture, selected: VerifiedDownloadEndpoint? = null): SmartOfflineReconciler {
        val account = settings.copy(lanUrl = lan.url, trustedHttpLanUrl = lan.url)
        val endpoint = selected ?: VerifiedDownloadEndpoint(lan.url.toHttpUrl(), OkHttpClient(), true)
        coEvery { dao.account(settings.key) } returns account
        coEvery { dao.pending() } returns listOf(pending)
        coEvery { dao.managed(settings.key) } returns emptyList()
        coEvery { dao.series(settings.key) } returns listOf(
            SmartOfflineSeries("series", settings.key, UUID.randomUUID().toString(), "Series"),
        )
        every { users.getUser(settings.userId) } returns UserEntity(2, 1, UUID.randomUUID(), "test-token", 0)
        every { controller.getApiClient(1, 2) } returns remote
        every { endpoints.allowed(any<Boolean>()) } returns true
        coEvery { endpoints.resolve(any(), any()) } returns endpoint
        coEvery { store.download(any(), any(), any(), any()) } returns true
        return SmartOfflineReconciler(dao, users, controller, endpoints, store)
    }

    private fun remoteApi(): ApiClient {
        val factory = OkHttpFactory()
        return factory.create(
            "https://remote.example/",
            "test-token",
            ClientInfo("test", "1"),
            DeviceInfo("test", "device"),
            HttpClientOptions(),
            factory,
        )
    }
}
