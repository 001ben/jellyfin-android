package org.jellyfin.mobile.downloads.smart

import android.content.Context
import android.net.ConnectivityManager
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
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

data class VerifiedDownloadEndpoint(val url: HttpUrl, val client: OkHttpClient, val lan: Boolean)

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
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } == true
    }?.let { network -> DirectWifiRoute(network.socketFactory) { network.getAllByName(it).toList() } }

    override fun remoteAllowed(wifiOnly: Boolean): Boolean {
        val active = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(active) ?: return false
        // A connected spare Wi-Fi network cannot authorize data on a cellular default route.
        // VPNs must expose a Wi-Fi underlay; ambiguous capabilities conservatively wait.
        return !wifiOnly || (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            )
    }
}

class SmartOfflineEndpoint(private val network: DownloadNetwork, client: OkHttpClient) {
    constructor(context: Context, client: OkHttpClient) : this(AndroidDownloadNetwork(context), client)

    // Redirects must never move authentication to another endpoint (or downgrade HTTPS).
    private val strictClient = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

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
            val bytes = response.peekBody(64 * 1024 + 1L).bytes()
            if (bytes.size > 64 * 1024) throw IOException("Invalid server identity response")
            return Json.parseToJsonElement(bytes.decodeToString()).jsonObject["Id"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() } ?: throw IOException("Missing server identity")
        }
    }

    suspend fun resolve(account: SmartOfflineAccount, remote: String): VerifiedDownloadEndpoint = withContext(
        Dispatchers.IO
    ) {
        if (!allowed(account.wifiOnly)) throw IOException("Waiting for permitted network")
        val wifi = network.wifi()
        var lanFailure: Exception? = null
        if (account.lanUrl.isNotBlank() && wifi != null) {
            try {
                val lan = SmartOfflinePolicy.lanUrl(account.lanUrl).toHttpUrl()
                val addresses = wifi.lookup(lan.host)
                require(
                    addresses.isNotEmpty() && addresses.all {
                        !it.isLoopbackAddress && (
                            it.isSiteLocalAddress || it.isLinkLocalAddress ||
                                (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc)
                            )
                    }
                )
                val client = strictClient.newBuilder().proxy(Proxy.NO_PROXY).socketFactory(wifi.sockets)
                    .dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> =
                            if (hostname == lan.host) addresses else throw IOException("Unexpected host")
                    }).build()
                if (SmartOfflinePolicy.sameServer(account.serverIdentity, publicIdentity(client, lan))) {
                    return@withContext VerifiedDownloadEndpoint(lan, client, true)
                }
            } catch (error: IOException) {
                lanFailure = error
                // Reachability and TLS failure are safe fallback cases; credentials have not been sent.
            } catch (error: IllegalArgumentException) {
                lanFailure = error
                // Invalid configuration or identity response. Never relax validation.
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
}
