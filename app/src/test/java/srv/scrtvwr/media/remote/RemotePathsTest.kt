package srv.scrtvwr.media.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePathsTest {
    @Test
    fun normalizeServerUrl_defaultsToHttpAndEndsWithSlash() {
        assertEquals("http://192.168.1.20:8000/", RemotePaths.normalizeServerUrl(" 192.168.1.20:8000 "))
        assertEquals("http://nas.local:8000/media/", RemotePaths.normalizeServerUrl("HTTP://NAS.local:8000/media"))
        assertEquals("https://example.com/", RemotePaths.normalizeServerUrl("https://example.com/?sort=name#top"))
    }

    @Test
    fun normalizeServerUrl_rejectsUnusableAddresses() {
        assertNull(RemotePaths.normalizeServerUrl(""))
        assertNull(RemotePaths.normalizeServerUrl("ftp://192.168.1.20/"))
        assertNull(RemotePaths.normalizeServerUrl("http://[::1]:8000/"))
        assertNull(RemotePaths.normalizeServerUrl("http://bad host:8000/"))
    }

    @Test
    fun parent_climbsToTheServerRootAndStops() {
        assertEquals("http://host:8000/Photos/", RemotePaths.parent("http://host:8000/Photos/2024/"))
        assertEquals("http://host:8000/Photos/", RemotePaths.parent("http://host:8000/Photos/a%20b.jpg"))
        assertEquals("http://host:8000/", RemotePaths.parent("http://host:8000/Photos/"))
        assertNull(RemotePaths.parent("http://host:8000/"))
    }

    @Test
    fun name_decodesTheLastSegmentWithoutTurningPlusIntoSpace() {
        assertEquals("a b+c.jpg", RemotePaths.name("http://host:8000/Photos/a%20b+c.jpg"))
        assertEquals("Été", RemotePaths.name("http://host:8000/%C3%89t%C3%A9/"))
        assertEquals("host:8000", RemotePaths.name("http://host:8000/"))
        assertEquals("jpg", RemotePaths.extension("http://host:8000/IMG.JPG"))
    }

    @Test
    fun isSameOrChildOf_comparesDirectoryUrls() {
        assertTrue(RemotePaths.isSameOrChildOf("http://host:8000/a/b/", "http://host:8000/a"))
        assertTrue(RemotePaths.isSameOrChildOf("http://host:8000/a", "http://host:8000/a/"))
        assertFalse(RemotePaths.isSameOrChildOf("http://host:8000/ab/", "http://host:8000/a/"))
        assertFalse(RemotePaths.isSameOrChildOf("http://host:8001/a/", "http://host:8000/a/"))
    }

    @Test
    fun resolveRoot_prefersTheServerRootThenFallsBackToTheParent() {
        assertEquals("http://host:8000/", RemotePaths.resolveRoot("http://host:8000/a/b/", "http://host:8000/"))
        assertEquals("http://host:8000/a/", RemotePaths.resolveRoot("http://host:8000/a/b/", "http://other:8000/"))
        assertEquals("http://host:8000/", RemotePaths.resolveRoot("http://host:8000/", null))
    }

    @Test
    fun origin_keepsOnlySchemeHostAndPort() {
        assertEquals("http://host:8000", RemotePaths.origin("http://host:8000/media/clip.mp4"))
        assertEquals("https://example.com", RemotePaths.origin("https://example.com/"))
        assertNull(RemotePaths.origin("/storage/emulated/0/clip.mp4"))
    }

    @Test
    fun serverFor_picksTheMostSpecificServer() {
        val root = HttpServer(id = "root", name = "Root", baseUrl = "http://host:8000/")
        val media = HttpServer(id = "media", name = "Media", baseUrl = "http://host:8000/media/")
        val servers = listOf(root, media)

        assertEquals(media, servers.serverFor("http://host:8000/media/2024/clip.mp4"))
        assertEquals(root, servers.serverFor("http://host:8000/docs/"))
        assertNull(servers.serverFor("http://elsewhere:8000/media/"))
    }
}
