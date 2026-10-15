package srv.scrtvwr.media.remote

import kotlin.io.encoding.Base64

/** An HTTP server added as a storage device, such as `python -m http.server 8000 --bind 0.0.0.0`. */
data class HttpServer(
    val id: String,
    val name: String,
    /** Directory URL the server is browsed from; always ends with `/`. */
    val baseUrl: String,
    /**
     * SHA-256 of the self-signed certificate the user chose to trust for this `https://` server,
     * as uppercase hex, or null to rely on the system's certificate authorities.
     */
    val certificateSha256: String? = null,
)

/** Registered server whose tree holds [url], preferring the most specific base URL. */
fun List<HttpServer>.serverFor(url: String): HttpServer? =
    filter { RemotePaths.isSameOrChildOf(url, it.baseUrl) }.maxByOrNull { it.baseUrl.length }

/** Username and password for a server that requires HTTP Basic authentication. */
data class HttpCredentials(
    val username: String,
    val password: String,
) {
    /** `Authorization` header value, encoded as UTF-8 like `tools/auth_http_server.py` expects. */
    fun authorization(): String = "Basic " + Base64.encode("$username:$password".toByteArray(Charsets.UTF_8))

    // Keeps the password out of logs and crash reports that print this object.
    override fun toString(): String = "HttpCredentials(username=$username)"
}
