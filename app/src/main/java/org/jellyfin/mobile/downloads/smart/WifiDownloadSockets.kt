package org.jellyfin.mobile.downloads.smart

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** Uses only system-permitted routes; a VPN/cellular source is rejected before HTTP headers are written. */
internal class WifiDownloadSockets(
    private val preferred: SocketFactory,
    private val fallback: SocketFactory = SocketFactory.getDefault(),
    private val wifiSource: (InetAddress) -> Boolean,
) : SocketFactory(), Interceptor {
    override fun createSocket(): Socket = try {
        preferred.createSocket()
    } catch (_: IOException) {
        fallback.createSocket()
    } catch (_: SecurityException) {
        fallback.createSocket()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val socket = chain.connection()?.socket() ?: throw IOException("Missing LAN connection")
        if (socket.isClosed || !socket.isConnected || !wifiSource(socket.localAddress)) {
            throw IOException("LAN connection is not using the permitted Wi-Fi address")
        }
        return chain.proceed(chain.request())
    }

    override fun createSocket(host: String, port: Int): Socket = connected(InetSocketAddress(host, port))

    override fun createSocket(address: InetAddress, port: Int): Socket = connected(InetSocketAddress(address, port))

    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(host, port), InetSocketAddress(local, localPort))

    override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(address, port), InetSocketAddress(local, localPort))

    private fun connected(remote: InetSocketAddress, local: InetSocketAddress? = null): Socket {
        val socket = createSocket()
        var connected = false
        try {
            local?.let(socket::bind)
            socket.connect(remote)
            connected = true
            return socket
        } finally {
            if (!connected) socket.close()
        }
    }
}
