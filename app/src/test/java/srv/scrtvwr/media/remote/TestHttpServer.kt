package srv.scrtvwr.media.remote

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A stand-in for `python -m http.server` on the loopback interface: serves [files] by relative
 * path and lists every folder as an HTML page of links. Like Python's server, it closes each
 * connection after one response and ignores Range headers, unless [supportsRanges] is set; then
 * it answers them with the requested bytes, as nginx does. With [requiredAuthorization] set, it
 * answers 401 to requests without that exact `Authorization` header, like
 * `tools/media_http_server.py` run with a password.
 */
class TestHttpServer(
    private val files: Map<String, ByteArray>,
    // Allows tests to exercise large Content-Length values without transferring gigabytes.
    private val declaredFileLength: Long? = null,
    private val requiredAuthorization: String? = null,
    private val supportsRanges: Boolean = false,
) : Closeable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val fileRequestCount = AtomicInteger()

    val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}/"

    /** Requests for files, as opposed to folder listings. */
    val fileRequests: Int
        get() = fileRequestCount.get()

    init {
        thread(isDaemon = true, name = "test-http-server") {
            while (!serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                client.use {
                    try {
                        respond(it)
                    } catch (_: IOException) {
                        // A client may cancel after receiving headers or part of a file.
                    }
                }
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
        var range: String? = null
        while (true) {
            val header = reader.readLine()
            if (header.isNullOrEmpty()) break
            val name = header.substringBefore(':')
            if (name.equals("Authorization", ignoreCase = true)) {
                authorization = header.substringAfter(':').trim()
            } else if (name.equals("Range", ignoreCase = true)) {
                range = header.substringAfter(':').trim()
            }
        }
        val method = requestLine.substringBefore(' ')
        val path = URI(requestLine.substringAfter(' ').substringBefore(' ')).path.removePrefix("/")
        val file = files[path]
        val (status, contentType, body) = when {
            requiredAuthorization != null && authorization != requiredAuthorization ->
                Triple("401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"media\"", "text/plain", ByteArray(0))
            path.isEmpty() || path.endsWith("/") ->
                Triple("200 OK", "text/html; charset=utf-8", listing(path).toByteArray(Charsets.UTF_8))
            file != null && supportsRanges && range != null -> {
                fileRequestCount.incrementAndGet()
                partial(file, range)
            }
            file != null -> {
                fileRequestCount.incrementAndGet()
                Triple("200 OK", "application/octet-stream", file)
            }
            else -> Triple("404 Not Found", "text/plain", ByteArray(0))
        }
        val output = client.getOutputStream()
        val contentLength = if (body === file) declaredFileLength ?: body.size.toLong() else body.size.toLong()
        val headers = "HTTP/1.0 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: $contentLength\r\n" +
            "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        if (method != "HEAD") output.write(body)
        output.flush()
    }

    /** Response to a single `bytes=start-end` range of [file]; the end may be left out. */
    private fun partial(file: ByteArray, range: String): Triple<String, String, ByteArray> {
        val start = range.removePrefix("bytes=").substringBefore('-').toInt()
        if (start >= file.size) {
            return Triple("416 Range Not Satisfiable\r\nContent-Range: bytes */${file.size}", "text/plain", ByteArray(0))
        }
        val end = minOf(range.substringAfter('-').toIntOrNull() ?: file.size - 1, file.size - 1)
        return Triple(
            "206 Partial Content\r\nContent-Range: bytes $start-$end/${file.size}",
            "application/octet-stream",
            file.copyOfRange(start, end + 1),
        )
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
