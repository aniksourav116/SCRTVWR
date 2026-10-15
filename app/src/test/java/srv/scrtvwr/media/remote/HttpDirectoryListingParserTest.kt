package srv.scrtvwr.media.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpDirectoryListingParserTest {
    @Test
    fun parse_readsPythonHttpServerListing() {
        // Page shape produced by `python -m http.server` (SimpleHTTPRequestHandler.list_directory).
        val html = """
            <!DOCTYPE HTML>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <title>Directory listing for /Photos/</title>
            </head>
            <body>
            <h1>Directory listing for /Photos/</h1>
            <hr>
            <ul>
            <li><a href=".thumbnails/">.thumbnails/</a></li>
            <li><a href="2024%20Trip/">2024 Trip/</a></li>
            <li><a href="beach%20%26%20sun.jpg">beach &amp; sun.jpg</a></li>
            <li><a href="clip.mp4">clip.mp4</a></li>
            <li><a href="linked">linked@</a></li>
            </ul>
            <hr>
            </body>
            </html>
        """.trimIndent()

        val entries = HttpDirectoryListingParser.parse(html, "http://192.168.1.20:8000/Photos/")

        assertEquals(
            listOf(
                RemoteEntry(".thumbnails", "http://192.168.1.20:8000/Photos/.thumbnails/", isDirectory = true),
                RemoteEntry("2024 Trip", "http://192.168.1.20:8000/Photos/2024%20Trip/", isDirectory = true),
                RemoteEntry("beach & sun.jpg", "http://192.168.1.20:8000/Photos/beach%20%26%20sun.jpg", isDirectory = false),
                RemoteEntry("clip.mp4", "http://192.168.1.20:8000/Photos/clip.mp4", isDirectory = false),
                RemoteEntry("linked", "http://192.168.1.20:8000/Photos/linked", isDirectory = false),
            ),
            entries,
        )
        assertTrue(entries.first().isHidden)
    }

    @Test
    fun parse_skipsNavigationAndForeignLinks() {
        // Apache and nginx style indexes add parent, sorting, and duplicate icon links.
        val html = """
            <a href="?C=N;O=D">Name</a>
            <a href="/">Parent Directory</a>
            <a href="../">../</a>
            <a href='./'>.</a>
            <a href="#top">Top</a>
            <a href="https://elsewhere.example/file.jpg">mirror</a>
            <a class="icon" HREF="/media/cover.png"><img src="/icons/image.gif"></a>
            <a href="/media/cover.png">cover.png</a>
            <a href=nested/deeper/file.jpg>deep</a>
            <a href="Season%201/">Season 1/</a>
        """.trimIndent()

        val entries = HttpDirectoryListingParser.parse(html, "http://nas:8080/media")

        assertEquals(
            listOf(
                RemoteEntry("cover.png", "http://nas:8080/media/cover.png", isDirectory = false),
                RemoteEntry("Season 1", "http://nas:8080/media/Season%201/", isDirectory = true),
            ),
            entries,
        )
    }

    @Test
    fun parse_resolvesLinksAgainstTheDirectoryEvenWithoutTrailingSlash() {
        val entries = HttpDirectoryListingParser.parse("""<a href="a.jpg">a.jpg</a>""", "http://host:8000/Photos")

        assertEquals(listOf("http://host:8000/Photos/a.jpg"), entries.map(RemoteEntry::url))
    }
}
