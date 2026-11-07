package srv.scrtvwr.media.remote

import srv.scrtvwr.media.TransportSafety
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Validates each redirect, never forwards credentials to another origin, and never downgrades TLS. */
object SafeHttpConnection {
    fun open(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMillis: Int = HttpDirectoryClient.CONNECT_TIMEOUT_MILLIS,
        readTimeoutMillis: Int = HttpDirectoryClient.READ_TIMEOUT_MILLIS,
        beforeResponse: (HttpURLConnection) -> Unit = {},
    ): HttpURLConnection {
        var address = url
        repeat(6) {
            val authorization = HttpServerAuth.authorizationFor(address)
            TransportSafety.requireAllowed(address, authorization != null)
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                headers.filterKeys { !it.equals("Authorization", true) }.forEach { (name, value) ->
                    connection.setRequestProperty(name, value)
                }
                authorization?.let { connection.setRequestProperty("Authorization", it) }
                beforeResponse(connection)
                val status = connection.responseCode
                if (status !in setOf(301, 302, 303, 307, 308)) return connection
                val location = connection.getHeaderField("Location") ?: throw IOException("Server redirect has no destination.")
                val next = URI(address).resolve(location).toString()
                if (RemotePaths.origin(next) != RemotePaths.origin(address)) {
                    throw IOException("Server redirects must stay on the same secure origin. Add the destination server directly.")
                }
                address = next
            } catch (error: Exception) {
                connection.disconnect()
                throw if (error is IOException) error else IOException("Unable to connect to this server safely.", error)
            }
            connection.disconnect()
        }
        throw IOException("Too many server redirects.")
    }
}
