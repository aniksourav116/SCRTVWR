package srv.scrtvwr.media.remote

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust for `https://` servers with self-signed certificates, such as `tools/auth_http_server.py`
 * started with `--cert`. Once the user confirms a certificate's fingerprint, connections to that
 * server accept exactly that certificate and nothing else, the way SSH remembers host keys.
 */
object PinnedCertificates {
    private const val PROBE_TIMEOUT_MILLIS = 5_000
    private const val DEFAULT_HTTPS_PORT = 443

    private val pinnings = ConcurrentHashMap<String, Pinning>()

    /** Uppercase hex SHA-256 of a certificate's DER encoding, the form pins are stored in. */
    fun sha256(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).toHexString(HexFormat.UpperCase)

    fun sha256(certificate: X509Certificate): String = sha256(certificate.encoded)

    /** [sha256] split into colon-separated bytes, the way `tools/auth_http_server.py` prints it. */
    fun format(sha256: String): String = sha256.chunked(2).joinToString(":")

    /**
     * Connection settings that accept only the certificate whose SHA-256 is [pin]. They are shared
     * per pin so repeated requests can resume TLS sessions.
     */
    fun pinning(pin: String): Pinning = pinnings.getOrPut(pin) { Pinning(pin) }

    /**
     * The certificate an `https://` server presents, read without trusting it: the handshake is
     * always aborted once the certificate arrives, so nothing is sent to the server. Returns null
     * when [url] is not `https://` or the server cannot be reached. Blocks on the network.
     */
    fun probe(url: String): ServerCertificate? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host ?: return null
        val port = if (uri.port >= 0) uri.port else DEFAULT_HTTPS_PORT
        val recorder = RecordingTrustManager()
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recorder), null) }
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MILLIS)
                socket.soTimeout = PROBE_TIMEOUT_MILLIS
                (context.socketFactory.createSocket(socket, host, port, true) as SSLSocket).use { it.startHandshake() }
            }
        }
        return recorder.leaf?.let { ServerCertificate(it) }
    }

    class Pinning internal constructor(pin: String) {
        val trustManager: X509TrustManager = PinnedTrustManager(pin)

        val socketFactory: SSLSocketFactory =
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory

        /**
         * Host names stop mattering once the exact certificate is pinned, and LAN servers are
         * usually reached by an IP address their self-signed certificate does not name.
         */
        val hostnameVerifier = HostnameVerifier { _, session ->
            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            leaf != null && sha256(leaf) == pin
        }
    }

    private class PinnedTrustManager(private val pin: String) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("The server sent no certificate")
            // Validity dates are not checked: the user trusted this exact certificate, not a CA.
            if (sha256(leaf) != pin) throw CertificateException("The server certificate is not the trusted one")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are not accepted")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Remembers the server's certificate, then fails the handshake so it is never trusted. */
    private class RecordingTrustManager : X509TrustManager {
        @Volatile
        var leaf: X509Certificate? = null
            private set

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            leaf = chain?.firstOrNull()
            throw CertificateException("Certificate recorded for review, not trusted")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are not accepted")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

/** A server certificate shown to the user before they decide whether to trust it. */
data class ServerCertificate(
    /** Uppercase hex, as stored in [HttpServer.certificateSha256]. */
    val sha256: String,
    val subject: String,
    val expiresAtMillis: Long,
) {
    constructor(certificate: X509Certificate) : this(
        sha256 = PinnedCertificates.sha256(certificate),
        subject = certificate.subjectX500Principal.name,
        expiresAtMillis = certificate.notAfter.time,
    )
}
