package srv.scrtvwr.media.remote

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A stand-in for `python -m http.server` on the loopback interface: serves [files] by relative
 * path and lists every folder as an HTML page of links. Like Python's server, it ignores Range
 * headers and closes each connection after one response. With [requiredAuthorization] it answers
 * 401 to requests without that `Authorization` header, like `tools/auth_http_server.py`.
 */
class TestHttpServer(
    private val files: Map<String, ByteArray>,
    private val requiredAuthorization: String? = null,
) : Closeable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val fileRequestCount = AtomicInteger()
    private val receivedAuthorizations = CopyOnWriteArrayList<String?>()

    val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}/"

    /** Requests for files, as opposed to folder listings. */
    val fileRequests: Int
        get() = fileRequestCount.get()

    /** The `Authorization` header of every request so far, in order, or null where none was sent. */
    val authorizations: List<String?>
        get() = receivedAuthorizations.toList()

    init {
        thread(isDaemon = true, name = "test-http-server") {
            while (!serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                client.use { respond(it) }
            }
        }
    }

    /** URL of [path], percent-encoded the way Python's listing links are. */
    fun url(path: String): String =
        baseUrl + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun respond(client: Socket) {
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        var authorization: String? = null
        while (true) {
            val header = reader.readLine()
            if (header.isNullOrEmpty()) break
            if (header.substringBefore(':').trim().equals("Authorization", ignoreCase = true)) {
                authorization = header.substringAfter(':').trim()
            }
        }
        receivedAuthorizations += authorization
        val method = requestLine.substringBefore(' ')
        val path = URI(requestLine.substringAfter(' ').substringBefore(' ')).path.removePrefix("/")
        val file = files[path]
        val signedIn = requiredAuthorization == null || authorization == requiredAuthorization
        val (status, contentType, body) = when {
            !signedIn -> Triple("401 Unauthorized", "text/plain", ByteArray(0))
            path.isEmpty() || path.endsWith("/") ->
                Triple("200 OK", "text/html; charset=utf-8", listing(path).toByteArray(Charsets.UTF_8))
            file != null -> {
                fileRequestCount.incrementAndGet()
                Triple("200 OK", "application/octet-stream", file)
            }
            else -> Triple("404 Not Found", "text/plain", ByteArray(0))
        }
        val output = client.getOutputStream()
        val headers = "HTTP/1.0 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            (if (signedIn) "" else "WWW-Authenticate: Basic realm=\"SCRTVWR\", charset=\"UTF-8\"\r\n") +
            "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        if (method != "HEAD") output.write(body)
        output.flush()
    }

    private fun listing(directory: String): String {
        val children = files.keys
            .filter { it.startsWith(directory) }
            .map { it.removePrefix(directory) }
            .map { if ('/' in it) "${it.substringBefore('/')}/" else it }
            .distinct()
            .sorted()
        return buildString {
            append("<!DOCTYPE HTML>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\"></head>\n<body>\n<ul>\n")
            children.forEach { child ->
                val href = url(directory + child).removePrefix(url(directory))
                append("<li><a href=\"$href\">$child</a></li>\n")
            }
            append("</ul>\n</body>\n</html>\n")
        }
    }

    override fun close() {
        serverSocket.close()
    }
}
