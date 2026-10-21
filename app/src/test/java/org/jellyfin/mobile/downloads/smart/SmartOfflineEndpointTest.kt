package org.jellyfin.mobile.downloads.smart

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

class RedirectToFixtureSockets(private val port: Int) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) =
            super.connect(InetSocketAddress("127.0.0.1", this@RedirectToFixtureSockets.port), timeout)
    }
    override fun createSocket(host: String, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress, port: Int) = createSocket().apply {
        connect(
            InetSocketAddress(address, port)
        )
    }
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = createSocket(host, port)
    override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int) = createSocket(
        address,
        port
    )
}

class FakeDownloadNetwork(var direct: DirectWifiRoute? = null, var remote: Boolean = true) : DownloadNetwork {
    override fun wifi() = direct
    override fun remoteAllowed(wifiOnly: Boolean) = remote
}

class SmartOfflineEndpointTest {
    private val account = SmartOfflineAccount("1-2", 1, 2, enabled = true, serverIdentity = "server-A")
    private val remote = "https://remote.example/jellyfin/"

    private fun network(server: LocalHttpFixture) = FakeDownloadNetwork(
        DirectWifiRoute(RedirectToFixtureSockets(server.port)) { listOf(InetAddress.getByName("192.168.1.2")) },
    )

    @Test fun `reachable LAN with trusted TLS and matching identity is chosen without probe credentials`() = runBlocking {
        LocalHttpFixture(tls = true).use { server ->
            val resolver = SmartOfflineEndpoint(network(server), server.trustedClient)
            val endpoint = resolver.resolve(account.copy(lanUrl = "https://home.test:${server.port}/jellyfin/"), remote)
            assertTrue(endpoint.lan)
            assertEquals("home.test", endpoint.url.host)
            assertEquals("/jellyfin/System/Info/Public", server.requests.single().path)
            assertNull(server.requests.single().authorization)
        }
    }

    @Test fun `mismatched server never receives credentials and fallback requires explicit policy`() = runBlocking {
        LocalHttpFixture(tls = true).use { server ->
            server.handler = { server.respond(it, 200, "{\"Id\":\"server-B\"}".toByteArray()) }
            val resolver = SmartOfflineEndpoint(network(server), server.trustedClient)
            val configured = account.copy(lanUrl = "https://home.test:${server.port}/jellyfin/")
            assertThrows(IOException::class.java) { runBlocking { resolver.resolve(configured, remote) } }
            val endpoint = resolver.resolve(configured.copy(remoteFallback = true), remote)
            assertFalse(endpoint.lan)
            assertEquals("remote.example", endpoint.url.host)
            assertTrue(server.requests.all { it.authorization == null })
        }
    }

    @Test fun `TLS failures and downgrade redirects cannot authorize LAN`() = runBlocking {
        LocalHttpFixture(tls = true).use { server ->
            val configured = account.copy(lanUrl = "https://home.test:${server.port}/jellyfin/", remoteFallback = true)
            val untrusted = SmartOfflineEndpoint(network(server), OkHttpClient())
            assertFalse(untrusted.resolve(configured, remote).lan)
            assertTrue(server.requests.isEmpty())
            server.handler = { server.respond(it, 302, ByteArray(0), mapOf("Location" to "http://home.test/insecure"), -1) }
            assertFalse(SmartOfflineEndpoint(network(server), server.trustedClient).resolve(configured, remote).lan)
            assertEquals(1, server.requests.size)
            assertNull(server.requests.single().authorization)
        }
    }

    @Test fun `LAN must resolve entirely to local addresses and fallback must use permitted active route`() = runBlocking {
        LocalHttpFixture(tls = true).use { server ->
            val route = DirectWifiRoute(RedirectToFixtureSockets(server.port)) {
                listOf(InetAddress.getByName("192.168.1.2"), InetAddress.getByName("8.8.8.8"))
            }
            val network = FakeDownloadNetwork(route, remote = false)
            val resolver = SmartOfflineEndpoint(network, server.trustedClient)
            val configured = account.copy(lanUrl = "https://home.test:${server.port}/", remoteFallback = true)
            assertThrows(IOException::class.java) { runBlocking { resolver.resolve(configured, remote) } }
            assertTrue(server.requests.isEmpty())
            network.remote = true
            assertFalse(resolver.resolve(configured, remote).lan)
        }
    }

    @Test fun `unreachable LAN waits unless remote fallback was selected`() = runBlocking {
        val network = FakeDownloadNetwork(direct = null)
        val resolver = SmartOfflineEndpoint(network, OkHttpClient())
        assertThrows(IOException::class.java) { runBlocking { resolver.resolve(account, remote) } }
        assertFalse(resolver.resolve(account.copy(remoteFallback = true), remote).lan)
    }
}
