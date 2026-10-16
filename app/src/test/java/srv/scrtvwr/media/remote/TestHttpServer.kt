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
 * path and lists every folder as an HTML page of links. Like Python's server, it ignores Range
 * headers and closes each connection after one response.
 */
class TestHttpServer(
    private val files: Map<String, ByteArray>,
    // Allows tests to exercise large Content-Length values without transferring gigabytes.
    private val declaredFileLength: Long? = null,
    private val videoPreview: ByteArray? = null,
    private val previewContentType: String = "image/jpeg",
    private val declaredPreviewLength: Long? = null,
    private val omitPreviewLength: Boolean = false,
) : Closeable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val fileRequestCount = AtomicInteger()
    private val previewGetRequestCount = AtomicInteger()

    val previewGets: Int
        get() = previewGetRequestCount.get()

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
        while (!reader.readLine().isNullOrEmpty()) {
            // Headers are not needed.
        }
        val method = requestLine.substringBefore(' ')
        val uri = URI(requestLine.substringAfter(' ').substringBefore(' '))
        val path = uri.path.removePrefix("/")
        val file = files[path]
        val isPreview = uri.rawQuery.orEmpty().split('&').contains("scrtvwr-preview=1") && videoPreview != null
        val (status, contentType, body) = when {
            isPreview -> {
                if (method == "GET") previewGetRequestCount.incrementAndGet()
                Triple("200 OK", previewContentType, videoPreview!!)
            }
            path.isEmpty() || path.endsWith("/") ->
                Triple("200 OK", "text/html; charset=utf-8", listing(path).toByteArray(Charsets.UTF_8))
            file != null -> {
                fileRequestCount.incrementAndGet()
                Triple("200 OK", "application/octet-stream", file)
            }
            else -> Triple("404 Not Found", "text/plain", ByteArray(0))
        }
        val output = client.getOutputStream()
        val contentLength = when {
            isPreview -> declaredPreviewLength ?: body.size.toLong()
            file != null -> declaredFileLength ?: body.size.toLong()
            else -> body.size.toLong()
        }
        val headers = "HTTP/1.0 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            (if (isPreview && omitPreviewLength) "" else "Content-Length: $contentLength\r\n") +
            (if (videoPreview != null) "X-SCRTVWR-Video-Previews: 1\r\n" else "") +
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
