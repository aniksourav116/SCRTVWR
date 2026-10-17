package srv.scrtvwr.media.remote

import java.util.concurrent.atomic.AtomicReference
import kotlin.io.encoding.Base64

/** An HTTP server added as a storage device, such as `python -m http.server 8000 --bind 0.0.0.0`. */
data class HttpServer(
    val id: String,
    val name: String,
    /** Directory URL the server is browsed from; always ends with `/`. */
    val baseUrl: String,
    /** Whether the server connects when the app starts and retries after dropping offline. */
    val autoReconnect: Boolean = false,
    /** Sent with HTTP Basic auth on every request to the server, or null for an open server. */
    val credentials: HttpCredentials? = null,
)

/** Username and password for HTTP Basic auth. [toString] leaves the password out of logs. */
data class HttpCredentials(val username: String, val password: String) {
    /** Value of the `Authorization` request header. */
    val authorization: String
        get() = "Basic " + Base64.encode("$username:$password".toByteArray(Charsets.UTF_8))

    override fun toString(): String = "HttpCredentials(username=$username, password=***)"

    companion object {
        /** Credentials typed into a form, or null when both fields are empty. */
        fun of(username: String, password: String): HttpCredentials? {
            val trimmedUsername = username.trim()
            return if (trimmedUsername.isEmpty() && password.isEmpty()) null else HttpCredentials(trimmedUsername, password)
        }
    }
}

/** Registered server whose tree holds [url], preferring the most specific base URL. */
fun List<HttpServer>.serverFor(url: String): HttpServer? =
    filter { RemotePaths.isSameOrChildOf(url, it.baseUrl) }.maxByOrNull { it.baseUrl.length }

/**
 * Saved servers' credentials, so any request can authenticate from its URL alone, without a
 * Context or reading preferences. The app loads it at startup and refreshes it on every save.
 */
object HttpServerAuth {
    private val servers = AtomicReference<List<HttpServer>>(emptyList())

    fun update(servers: List<HttpServer>) {
        this.servers.set(servers)
    }

    /** `Authorization` header value for [url], or null when its server needs none. */
    fun authorizationFor(url: String): String? = servers.get().serverFor(url)?.credentials?.authorization
}

/**
 * Which saved servers are connected in this process. A disconnected server stays saved but is
 * not checked, browsed or offered in recents and history. A new process starts with only the
 * servers that have [HttpServer.autoReconnect] turned on.
 */
object HttpServerConnections {
    /** Connected server IDs, or null until first used. */
    private val connectedIds = AtomicReference<Set<String>?>(null)

    /** The connected servers among [servers], every saved server. */
    fun connected(servers: List<HttpServer>): List<HttpServer> {
        val ids = idsFor(servers)
        return servers.filter { it.id in ids }
    }

    fun setConnected(serverId: String, connected: Boolean, servers: List<HttpServer>) {
        idsFor(servers)
        connectedIds.updateAndGet { ids -> if (connected) ids.orEmpty() + serverId else ids.orEmpty() - serverId }
    }

    private fun idsFor(servers: List<HttpServer>): Set<String> =
        connectedIds.updateAndGet { ids ->
            ids ?: servers.filter(HttpServer::autoReconnect).mapTo(mutableSetOf(), HttpServer::id)
        }!!
}
