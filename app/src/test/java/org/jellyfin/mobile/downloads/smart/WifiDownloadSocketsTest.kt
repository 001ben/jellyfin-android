package org.jellyfin.mobile.downloads.smart

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

private const val HOME_ADDRESS = "192.168.1.2"

class WifiDownloadSocketsTest {
    private val remote = "https://remote.example/jellyfin/"
    private val saved = SmartOfflineAccount("1-2", 1, 2, enabled = true, serverIdentity = "server-A")

    @Test fun `VPN denied explicit binding uses only the permitted system LAN route`() = runBlocking {
        LocalHttpFixture(tls = true).use { server ->
            val denied = deniedBinding()
            val sockets = WifiDownloadSockets(denied, RedirectToFixtureSockets(server.port)) { it.isLoopbackAddress }
            val endpoint = resolver(server, sockets).resolve(settings(server), remote)
            assertTrue(endpoint.lan)
            assertNull(server.requests.single().authorization)
            endpoint.client.newCall(
                Request.Builder().url(endpoint.url).header("Authorization", "fixture-token").build()
            )
                .execute().use { assertEquals(200, it.code) }
            assertEquals("fixture-token", server.requests.last().authorization)
        }
    }

    @Test fun `a VPN or cellular source is rejected before the public identity request`() = runBlocking {
        LocalHttpFixture(tls = true).use { server ->
            val sockets = WifiDownloadSockets(deniedBinding(), RedirectToFixtureSockets(server.port)) { false }
            val resolver = resolver(server, sockets)
            assertThrows(IOException::class.java) { runBlocking { resolver.resolve(settings(server), remote) } }
            assertTrue(server.requests.isEmpty())
            assertFalse(resolver.resolve(settings(server).copy(remoteFallback = true), remote).lan)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test fun `Wi-Fi loss on a pooled connection blocks credentials before the next HTTP request`() = runBlocking {
        LocalHttpFixture(tls = true).use { server ->
            var wifiPresent = true
            val sockets = WifiDownloadSockets(deniedBinding(), RedirectToFixtureSockets(server.port)) { wifiPresent }
            val endpoint = resolver(server, sockets).resolve(settings(server), remote)
            assertEquals(1, server.requests.size)
            wifiPresent = false
            val request = Request.Builder().url(endpoint.url).header("Authorization", "must-not-be-sent").build()
            assertThrows(IOException::class.java) { endpoint.client.newCall(request).execute().close() }
            assertEquals(1, server.requests.size)
            assertNull(server.requests.single().authorization)
        }
    }

    @Test fun `system route still requires server identity and exact HTTP consent`() = runBlocking {
        LocalHttpFixture().use { server ->
            val sockets = WifiDownloadSockets(deniedBinding(), RedirectToFixtureSockets(server.port)) { true }
            val resolver = resolver(server, sockets)
            val url = "http://$HOME_ADDRESS:${server.port}/jellyfin/"
            val settings = saved.copy(lanUrl = url)
            assertThrows(IOException::class.java) { runBlocking { resolver.resolve(settings, remote) } }
            assertTrue(server.requests.isEmpty())
            val consented = settings.copy(trustedHttpLanUrl = SmartOfflinePolicy.httpConsentUrl(url, true))
            server.handler = { server.respond(it, 200, "{\"Id\":\"different\"}".toByteArray()) }
            assertThrows(IOException::class.java) { runBlocking { resolver.resolve(consented, remote) } }
            assertEquals(1, server.requests.size)
            assertNull(server.requests.single().authorization)
        }
    }

    @Test fun `LAN-specific sockets never replace the normal client's configured route`() = runBlocking {
        LocalHttpFixture().use { server ->
            val normal = OkHttpClient()
            val sockets = WifiDownloadSockets(deniedBinding(), RedirectToFixtureSockets(server.port)) { false }
            val route = DirectWifiRoute(sockets) { listOf(InetAddress.getByName(HOME_ADDRESS)) }
            val resolver = SmartOfflineEndpoint(FakeDownloadNetwork(route), normal)
            val url = "http://$HOME_ADDRESS:${server.port}/jellyfin/"
            val settings = saved.copy(
                lanUrl = url,
                trustedHttpLanUrl = SmartOfflinePolicy.httpConsentUrl(url, true),
                remoteFallback = true,
            )
            val endpoint = resolver.resolve(settings, server.url)
            assertFalse(endpoint.lan)
            endpoint.client.newCall(Request.Builder().url(endpoint.url).header("Authorization", "remote-token").build())
                .execute().use { assertEquals(200, it.code) }
            assertEquals("remote-token", server.requests.single().authorization)
            assertTrue(normal.socketFactory !== sockets)
        }
    }

    @Test fun `consented HTTP works while Tailscale denies explicit network binding`() = runBlocking {
        LocalHttpFixture().use { server ->
            val sockets = WifiDownloadSockets(
                deniedBinding(SecurityException("Synthetic VPN restriction")),
                RedirectToFixtureSockets(server.port),
            ) { it.isLoopbackAddress }
            val url = "http://$HOME_ADDRESS:${server.port}/jellyfin/"
            val settings = saved.copy(lanUrl = url, trustedHttpLanUrl = SmartOfflinePolicy.httpConsentUrl(url, true))
            val endpoint = resolver(server, sockets).resolve(settings, remote)
            assertTrue(endpoint.trustedHttpLan)
            assertNull(server.requests.single().authorization)
            endpoint.client.newCall(
                Request.Builder().url(endpoint.url).header("Authorization", "fixture-token").build()
            )
                .execute().use { assertEquals(200, it.code) }
            assertEquals("fixture-token", server.requests.last().authorization)
        }
    }

    @Test fun `an already permitted explicit Wi-Fi route does not use another socket factory`() = runBlocking {
        LocalHttpFixture(tls = true).use { server ->
            val fallback = object : SocketFactory() {
                override fun createSocket(): Socket = error("Fallback must not be used")
                override fun createSocket(host: String, port: Int) = createSocket()
                override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = createSocket()
                override fun createSocket(address: InetAddress, port: Int) = createSocket()
                override fun createSocket(
                    address: InetAddress,
                    port: Int,
                    local: InetAddress,
                    localPort: Int
                ) = createSocket()
            }
            val sockets = WifiDownloadSockets(RedirectToFixtureSockets(server.port), fallback) { it.isLoopbackAddress }
            assertTrue(resolver(server, sockets).resolve(settings(server), remote).lan)
        }
    }

    private fun resolver(server: LocalHttpFixture, sockets: WifiDownloadSockets): SmartOfflineEndpoint =
        SmartOfflineEndpoint(
            FakeDownloadNetwork(DirectWifiRoute(sockets) { listOf(InetAddress.getByName(HOME_ADDRESS)) }),
            server.trustedClient,
        )

    private fun settings(server: LocalHttpFixture) = saved.copy(lanUrl = "https://home.test:${server.port}/jellyfin/")

    private fun deniedBinding(error: Exception = IOException("Synthetic VPN binding denial")): SocketFactory =
        object : SocketFactory() {
            override fun createSocket(): Socket = throw error
            override fun createSocket(host: String, port: Int) = createSocket()
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = createSocket()
            override fun createSocket(address: InetAddress, port: Int) = createSocket()
            override fun createSocket(
                address: InetAddress,
                port: Int,
                local: InetAddress,
                localPort: Int,
            ) = createSocket()
        }
}
