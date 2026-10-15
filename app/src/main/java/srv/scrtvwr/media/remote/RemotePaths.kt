package srv.scrtvwr.media.remote

import java.net.URI
import java.net.URLDecoder

/**
 * Path helpers for media stored on an HTTP server. Remote media is addressed by its full URL
 * everywhere the app passes a path string around; directory URLs always end with `/`.
 */
object RemotePaths {
    fun isRemote(path: String?): Boolean =
        path != null && (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true))

    /**
     * Directory URL for a server address typed by the user, such as `192.168.1.20:8000`, or null
     * when it cannot be used. Plain addresses default to `http://`.
     */
    fun normalizeServerUrl(input: String): String? {
        val uri = parseAddress(input) ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.takeIf(String::isNotBlank) ?: return null
        // IPv6 literals would put "::" into every path, which already marks archive entries.
        if (':' in host) return null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return directoryUrl("$scheme://$host$port${uri.rawPath.orEmpty()}")
    }

    /**
     * Username and password typed into a server address as `user:password@host`, which
     * [normalizeServerUrl] leaves out of the URL, or null when the address has none.
     */
    fun credentials(input: String): HttpCredentials? {
        val userInfo = parseAddress(input)?.userInfo?.takeIf(String::isNotEmpty) ?: return null
        return HttpCredentials(
            username = userInfo.substringBefore(':'),
            password = userInfo.substringAfter(':', missingDelimiterValue = ""),
        )
    }

    fun directoryUrl(url: String): String = if (url.endsWith("/")) url else "$url/"

    /** Directory holding [url], or null once [url] is the server root. */
    fun parent(url: String): String? {
        val uri = parse(url) ?: return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.isEmpty()) return null
        return "${origin(uri)}${path.substringBeforeLast('/')}/"
    }

    /** Decoded last path segment, or the server's host and port for its root. */
    fun name(url: String): String {
        val uri = parse(url) ?: return url
        return segments(uri).lastOrNull() ?: uri.rawAuthority ?: url
    }

    fun extension(url: String): String = name(url).substringAfterLast('.', "").lowercase()

    /** The server address followed by the decoded path segments. */
    fun pathSegments(url: String): List<String> {
        val uri = parse(url) ?: return listOf(url)
        return listOfNotNull(uri.rawAuthority) + segments(uri)
    }

    fun isSameDirectory(left: String, right: String): Boolean = directoryUrl(left) == directoryUrl(right)

    fun isSameOrChildOf(url: String, root: String): Boolean = directoryUrl(url).startsWith(directoryUrl(root))

    /**
     * Highest directory a traversal starting at [directory] may reach, mirroring
     * `DirectoryTraversal.resolveRoot` for local folders.
     */
    fun resolveRoot(directory: String, preferredRoot: String?): String =
        preferredRoot?.takeIf { isSameOrChildOf(directory, it) }?.let(::directoryUrl)
            ?: parent(directory)
            ?: directoryUrl(directory)

    /** Percent-decodes a URL path segment; unlike form decoding, `+` stays a plus sign. */
    fun decodeSegment(segment: String): String =
        runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrDefault(segment)

    private fun segments(uri: URI): List<String> =
        uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty).map(::decodeSegment)

    private fun origin(uri: URI): String = "${uri.scheme}://${uri.rawAuthority}"

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()

    private fun parseAddress(input: String): URI? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return parse(if ("://" in trimmed) trimmed else "http://$trimmed")
    }
}
