package srv.scrtvwr.media.remote

/**
 * What a request to a saved server needs beyond its URL: the credentials to sign in with and the
 * certificate the user trusted. Requests look both up by URL, so neither ever goes into a URL, and
 * a server's credentials are only sent to URLs under its own base URL.
 */
object HttpServerAccess {
    @Volatile
    private var snapshot = Snapshot(servers = emptyList(), credentials = emptyMap())

    /** Replaces what requests use; call after every change to the saved servers or credentials. */
    fun update(servers: List<HttpServer>, credentials: Map<String, HttpCredentials>) {
        snapshot = Snapshot(servers, credentials)
    }

    fun credentialsFor(url: String): HttpCredentials? {
        val current = snapshot
        return current.servers.serverFor(url)?.let { current.credentials[it.id] }
    }

    /** `Authorization` header for [url], or null when its server needs none. */
    fun authorizationFor(url: String): String? = credentialsFor(url)?.authorization()

    /** SHA-256 of the certificate trusted for [url]'s server, or null to use the system's trust. */
    fun pinFor(url: String): String? = snapshot.servers.serverFor(url)?.certificateSha256

    /** Whether another app handed [url] would fail for lack of its credentials or trusted certificate. */
    fun isProtected(url: String): Boolean = credentialsFor(url) != null || pinFor(url) != null

    private class Snapshot(
        val servers: List<HttpServer>,
        val credentials: Map<String, HttpCredentials>,
    )
}
