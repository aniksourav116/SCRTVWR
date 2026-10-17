package srv.scrtvwr.media.remote

import java.util.concurrent.atomic.AtomicReference

/** An HTTP server added as a storage device, such as `python -m http.server 8000 --bind 0.0.0.0`. */
data class HttpServer(
    val id: String,
    val name: String,
    /** Directory URL the server is browsed from; always ends with `/`. */
    val baseUrl: String,
    /** Whether the server connects when the app starts and retries after dropping offline. */
    val autoReconnect: Boolean = false,
)

/** Registered server whose tree holds [url], preferring the most specific base URL. */
fun List<HttpServer>.serverFor(url: String): HttpServer? =
    filter { RemotePaths.isSameOrChildOf(url, it.baseUrl) }.maxByOrNull { it.baseUrl.length }

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
