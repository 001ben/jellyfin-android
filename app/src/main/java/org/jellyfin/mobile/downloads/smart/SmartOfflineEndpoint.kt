package org.jellyfin.mobile.downloads.smart

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

data class VerifiedDownloadEndpoint(val url: HttpUrl, val client: OkHttpClient, val lan: Boolean) {
    /** Explicit local trust and an ID consistency check do not authenticate an HTTP peer cryptographically. */
    val trustedHttpLan: Boolean get() = lan && url.scheme == "http"
}

data class DirectWifiRoute(val sockets: SocketFactory, val lookup: (String) -> List<InetAddress>)

interface DownloadNetwork {
    fun wifi(): DirectWifiRoute?
    fun remoteAllowed(wifiOnly: Boolean): Boolean
}

class AndroidDownloadNetwork(context: Context) : DownloadNetwork {
    private val connectivity = requireNotNull(context.getSystemService<ConnectivityManager>())

    @Suppress("DEPRECATION") // Inspection only: never request, replace or bind the app's/VPN's default network.
    override fun wifi(): DirectWifiRoute? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } == true
    }?.let { network ->
        val sockets = WifiDownloadSockets(network.socketFactory) { address -> wifiAddress(network, address) }
        DirectWifiRoute(sockets) { hostname -> wifiLookup(network, hostname) }
    }

    private fun wifiAddress(network: Network, address: InetAddress): Boolean {
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        val stillWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return stillWifi && connectivity.getLinkProperties(network)?.linkAddresses
            ?.any { it.address == address } == true
    }

    private fun wifiLookup(network: Network, hostname: String): List<InetAddress> = try {
        network.getAllByName(hostname).toList()
    } catch (_: IOException) {
        Dns.SYSTEM.lookup(hostname)
    } catch (_: SecurityException) {
        Dns.SYSTEM.lookup(hostname)
    }

    override fun remoteAllowed(wifiOnly: Boolean): Boolean {
        val active = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(active) ?: return false
        // A connected spare Wi-Fi network cannot authorize data on a cellular default route.
        // VPNs must expose a Wi-Fi underlay; ambiguous capabilities conservatively wait.
        return !wifiOnly || (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            )
    }
}

class SmartOfflineEndpoint(private val network: DownloadNetwork, client: OkHttpClient) {
    constructor(context: Context, client: OkHttpClient) : this(AndroidDownloadNetwork(context), client)

    // Redirects must never move authentication to another endpoint (or downgrade HTTPS).
    private val strictClient = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    fun allowed(wifiOnly: Boolean): Boolean = network.wifi() != null || network.remoteAllowed(wifiOnly)
    fun allowed(endpoint: VerifiedDownloadEndpoint, wifiOnly: Boolean): Boolean =
        if (endpoint.lan) network.wifi() != null else network.remoteAllowed(wifiOnly)

    suspend fun identity(url: String): String = withContext(Dispatchers.IO) {
        publicIdentity(strictClient, url.toHttpUrl())
    }

    private fun publicIdentity(client: OkHttpClient, url: HttpUrl): String {
        val request = Request.Builder().url(url.newBuilder().addPathSegments("System/Info/Public").build()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server identity unavailable")
            return Json.parseToJsonElement(identityBody(response)).jsonObject["Id"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() } ?: throw IOException("Missing server identity")
        }
    }

    private fun identityBody(response: Response): String {
        val bytes = response.peekBody(MAX_IDENTITY_BYTES + 1).bytes()
        if (bytes.size > MAX_IDENTITY_BYTES) throw IOException("Invalid server identity response")
        return bytes.decodeToString()
    }

    suspend fun resolve(account: SmartOfflineAccount, remote: String): VerifiedDownloadEndpoint = withContext(
        Dispatchers.IO
    ) {
        if (!allowed(account.wifiOnly)) throw IOException("Waiting for permitted network")
        val wifi = network.wifi()
        var lanFailure: Exception? = null
        if (account.lanUrl.isNotBlank() && wifi != null) {
            try {
                verifiedLan(account, wifi)?.let { return@withContext it }
            } catch (error: IOException) {
                lanFailure = error
                // Reachability and TLS failure are safe fallback cases; credentials have not been sent.
            } catch (error: IllegalArgumentException) {
                lanFailure = error
                // Invalid configuration or identity response. Never relax validation.
            } catch (error: SecurityException) {
                lanFailure = error
                // VPN lockdown or route restrictions remain enforced; only the configured fallback may be used.
            }
        }
        if (!account.remoteFallback) {
            throw IOException(
                "LAN unavailable or unverified; remote downloads disabled",
                lanFailure
            )
        }
        if (!network.remoteAllowed(account.wifiOnly)) throw IOException("Waiting for a permitted remote route")
        VerifiedDownloadEndpoint(remote.toHttpUrl(), strictClient, false)
    }

    private fun verifiedLan(account: SmartOfflineAccount, wifi: DirectWifiRoute): VerifiedDownloadEndpoint? {
        val lan = SmartOfflinePolicy.lanUrl(account.lanUrl).toHttpUrl()
        if (lan.scheme == "http" && !SmartOfflinePolicy.isTrustedHttpLan(account)) return null
        val addresses = wifi.lookup(lan.host)
        require(addresses.isNotEmpty() && addresses.all { isPermittedAddress(lan, it) })
        val client = strictClient.newBuilder().proxy(Proxy.NO_PROXY).socketFactory(wifi.sockets)
            .apply { (wifi.sockets as? WifiDownloadSockets)?.let(::addNetworkInterceptor) }
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == lan.host) addresses else throw IOException("Unexpected host")
            }).build()
        val verified = SmartOfflinePolicy.sameServer(account.serverIdentity, publicIdentity(client, lan))
        return if (verified) VerifiedDownloadEndpoint(lan, client, true) else null
    }

    private fun isPermittedAddress(lan: HttpUrl, address: InetAddress): Boolean = if (lan.scheme == "http") {
        SmartOfflinePolicy.isPrivateIpv4(address.hostAddress.orEmpty()) && address.hostAddress == lan.host
    } else {
        isLanAddress(address)
    }

    private fun isLanAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return false
        if (address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        val bytes = address.address
        return bytes.size == IPV6_BYTES && (bytes[0].toInt() and IPV6_LOCAL_MASK) == IPV6_LOCAL_PREFIX
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val MAX_IDENTITY_BYTES = 64L * 1024
        private const val IPV6_BYTES = 16
        private const val IPV6_LOCAL_MASK = 0xfe
        private const val IPV6_LOCAL_PREFIX = 0xfc
    }
}
