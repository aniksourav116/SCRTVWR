package srv.scrtvwr.media.remote

/** An HTTP server added as a storage device, such as `python -m http.server 8000 --bind 0.0.0.0`. */
data class HttpServer(
    val id: String,
    val name: String,
    /** Directory URL the server is browsed from; always ends with `/`. */
    val baseUrl: String,
)

/** Registered server whose tree holds [url], preferring the most specific base URL. */
fun List<HttpServer>.serverFor(url: String): HttpServer? =
    filter { RemotePaths.isSameOrChildOf(url, it.baseUrl) }.maxByOrNull { it.baseUrl.length }
