package org.jellyfin.mobile.downloads.smart

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

class SmartOfflineTransferPolicyTest {
    @TempDir lateinit var directory: Path
    private val dao = mockk<SmartOfflineDao>()
    private val users = mockk<UserDao>()
    private val endpoints = mockk<SmartOfflineEndpoint>()
    private val account = SmartOfflineAccount("1-2", 1, 2, enabled = true)
    private val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, seriesId = UUID.randomUUID())
    private val endpoint = VerifiedDownloadEndpoint("https://download.example/".toHttpUrl(), OkHttpClient(), true)
    private val api = mockk<ApiClient>()
    private var now = 0L

    private fun policy(): SmartOfflineTransferPolicy {
        coEvery { dao.account(account.key) } returns account
        coEvery { dao.isSubscribed(account.key, item.seriesId.toString()) } returns true
        every { users.getUser(2) } returns UserEntity(2, 1, UUID.randomUUID(), "token", 0)
        every { api.accessToken } returns "token"
        every { endpoints.allowed(endpoint, true) } returns true
        return SmartOfflineTransferPolicy(dao, users, endpoints) { now }
    }

    @Test
    fun `deselection stops transfer and publication checks reject it`() = runBlocking {
        val policy = policy()
        val permitted = policy.permitted(account, endpoint, api, item)
        assertTrue(permitted())
        coEvery { dao.isSubscribed(account.key, item.seriesId.toString()) } returns false
        assertFalse(policy.subscribed(account, item))
        now += TimeUnit.SECONDS.toNanos(1)
        assertFalse(permitted())
    }

    @Test
    fun `token rotation settings disable and network restrictions pause transfers`() = runBlocking<Unit> {
        val policy = policy()
        val permitted = policy.permitted(account, endpoint, api, item)
        assertTrue(permitted())
        every { endpoints.allowed(endpoint, true) } returns false
        assertFalse(permitted())
        every { endpoints.allowed(endpoint, true) } returns true
        every { users.getUser(2) } returns UserEntity(2, 1, UUID.randomUUID(), "rotated", 0)
        now += TimeUnit.SECONDS.toNanos(1)
        assertFalse(permitted())
        coEvery { dao.account(account.key) } returns account.copy(enabled = false)
        assertThrows(IOException::class.java) { runBlocking { policy.requireCurrent(account) } }
    }

    @Test
    fun `missing series identity cannot inherit another subscriptions authorization`() = runBlocking {
        val policy = policy()
        assertFalse(policy.subscribed(account, item.copy(seriesId = null)))
        assertFalse(policy.permitted(account, endpoint, api, item.copy(seriesId = null))())
    }

    @Test
    fun `deselection retains validated partial bytes`() = runBlocking {
        LocalHttpFixture().use { server ->
            val policy = policy()
            val endpoint = VerifiedDownloadEndpoint(server.url.toHttpUrl(), OkHttpClient(), true)
            every { endpoints.allowed(endpoint, true) } returns true
            every { api.clientInfo } returns ClientInfo("test", "1")
            every { api.deviceInfo } returns DeviceInfo("test", "device")
            val body = ByteArray(256 * 1024) { 7 }
            server.handler = { server.respond(it, 200, body, mapOf("ETag" to "\"version\"")) }
            val file = directory.resolve("partial").toFile()
            val permit = policy.permitted(account, endpoint, api, item)
            var checks = 0
            var validator: String? = null
            assertThrows(IOException::class.java) {
                runBlocking {
                    SmartOfflineTransfer().download(
                        SmartOfflineTransferRequest(
                            endpoint,
                            api,
                            SmartOfflineManaged("1-2-${item.id}", "1-2", item),
                            file,
                            body.size.toLong()
                        ),
                        permitted = {
                            now += TimeUnit.SECONDS.toNanos(1)
                            val allowed = permit()
                            if (++checks == 3) {
                                coEvery { dao.isSubscribed(account.key, item.seriesId.toString()) } returns false
                            }
                            allowed
                        },
                        saveValidator = { validator = it },
                    )
                }
            }
            assertTrue(file.length() in 1 until body.size.toLong())
            assertTrue(file.readBytes().all { it == 7.toByte() })
            assertTrue(validator == "\"version\"")
            assertFalse(policy.subscribed(account, item))
        }
    }
}
