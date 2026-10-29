package srv.scrtvwr.media.remote

import java.net.URI

/** A file or folder listed by an HTTP server. Unknown sizes are -1 and unknown times 0. */
data class RemoteEntry(
    val name: String,
    val url: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = UNKNOWN_SIZE,
    val lastModifiedMillis: Long = 0L,
) {
    val isHidden: Boolean
        get() = name.startsWith(".")

    companion object {
        const val UNKNOWN_SIZE = -1L
    }
}

/**
 * Reads the HTML index page an HTTP server generates for a directory. Written against
 * `python -m http.server`, which lists one `<a href>` per entry with folders ending in `/`, but
 * any index that links its children with relative or absolute URLs works the same way.
 */
object HttpDirectoryListingParser {
    private val anchorPattern = Regex(
        """<a\s[^>]*?href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val entityPattern = Regex("""&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);""")

    /**
     * Direct children of [directoryUrl] linked from [html], in page order. Navigation links such
     * as the parent folder, column sorting queries, or links to other servers are skipped.
     */
    fun parse(html: String, directoryUrl: String): List<RemoteEntry> {
        val base = runCatching { URI(RemotePaths.directoryUrl(directoryUrl)) }.getOrNull() ?: return emptyList()
        val basePath = base.path.orEmpty()
        val entries = LinkedHashMap<String, RemoteEntry>()
        // One matcher walks the whole page. Regex.findAll starts a new matcher for every link, and
        // on Android each one copies the page, so a 30,000-entry listing took minutes to read.
        val matcher = anchorPattern.toPattern().matcher(html)
        while (matcher.find()) {
            val href = (1..matcher.groupCount()).firstNotNullOfOrNull { group ->
                matcher.group(group)?.takeIf(String::isNotEmpty)
            } ?: continue
            val entry = childEntry(base, basePath, unescapeHtml(href)) ?: continue
            entries.putIfAbsent(entry.url, entry)
        }
        return entries.values.toList()
    }

    private fun childEntry(base: URI, basePath: String, href: String): RemoteEntry? {
        if (href.startsWith("#") || href.startsWith("?")) return null
        val resolved = resolve(base, href) ?: return null
        if (resolved.rawQuery != null || resolved.rawFragment != null) return null
        if (!resolved.scheme.equals(base.scheme, ignoreCase = true) ||
            !resolved.rawAuthority.equals(base.rawAuthority, ignoreCase = true)
        ) {
            return null
        }
        val path = resolved.path.orEmpty()
        if (!path.startsWith(basePath)) return null
        val isDirectory = resolved.rawPath.orEmpty().endsWith("/")
        val name = path.removePrefix(basePath).removeSuffix("/")
        if (name.isEmpty() || '/' in name || name == "." || name == "..") return null
        return RemoteEntry(
            name = name,
            url = resolved.toASCIIString(),
            isDirectory = isDirectory,
        )
    }

    private fun resolve(base: URI, href: String): URI? =
        runCatching { base.resolve(href) }
            .recoverCatching { base.resolve(href.replace(" ", "%20")) }
            .getOrNull()
            ?.normalize()

    private fun unescapeHtml(value: String): String =
        entityPattern.replace(value) { match ->
            val entity = match.groupValues[1]
            when {
                entity.startsWith("#x", ignoreCase = true) ->
                    entity.drop(2).toIntOrNull(16)?.let(::codePointString)
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePointString)
                else -> NAMED_ENTITIES[entity.lowercase()]
            } ?: match.value
        }

    private fun codePointString(codePoint: Int): String? =
        runCatching { String(Character.toChars(codePoint)) }.getOrNull()

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
    )
}
