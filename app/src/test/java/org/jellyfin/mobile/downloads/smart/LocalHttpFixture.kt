package org.jellyfin.mobile.downloads.smart

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class LocalHttpFixture(tls: Boolean = false) : AutoCloseable {
    data class Seen(val path: String, val authorization: String?, val range: String?, val ifRange: String?)
    val requests = CopyOnWriteArrayList<Seen>()
    private val certificate = HeldCertificate.Builder().commonName(
        "home.test"
    ).addSubjectAlternativeName("home.test").build()
    private val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    val trustedClient = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build().let {
        OkHttpClient.Builder().sslSocketFactory(
            it.sslSocketFactory(),
            it.trustManager
        ).followRedirects(false).followSslRedirects(false).build()
    }
    val server: HttpServer = if (tls) {
        HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(certificates.sslContext())
        }
    } else {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    }
    var handler: (HttpExchange) -> Unit = { respond(it, 200, "{\"Id\":\"server-A\"}".toByteArray()) }
    val port get() = server.address.port
    val url get() = "http://127.0.0.1:$port/jellyfin/"

    init {
        server.createContext("/") { exchange ->
            requests += Seen(
                exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestHeaders.getFirst("Range"), exchange.requestHeaders.getFirst("If-Range")
            )
            try { handler(exchange) } finally { exchange.close() }
        }
        server.start()
    }

    fun respond(exchange: HttpExchange, code: Int, bytes: ByteArray, headers: Map<String, String> = emptyMap(), length: Long = bytes.size.toLong()) {
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.sendResponseHeaders(code, length)
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() { server.stop(0) }
}
