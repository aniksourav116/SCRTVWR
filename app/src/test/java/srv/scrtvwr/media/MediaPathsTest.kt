package srv.scrtvwr.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPathsTest {
    @Test
    fun archiveEntryPath_keepsTheArchiveUrl() {
        assertEquals(
            "http://host:8000/Comics/Issue%20001.cbz::Pages/001.jpg",
            MediaPaths.archiveEntryPath("http://host:8000/Comics/Issue%20001.cbz", "Pages/001.jpg"),
        )
        assertEquals("/Comics/Issue 001.cbr::001.jpg", MediaPaths.archiveEntryPath("/Comics/Issue 001.cbr", "001.jpg"))
    }

    @Test
    fun archivePathAndEntryName_splitAtTheFirstSeparator() {
        val path = "http://host:8000/Comics/Issue%20001.cbr::Pages\\a::b.jpg"

        assertEquals("http://host:8000/Comics/Issue%20001.cbr", MediaPaths.archivePath(path))
        assertEquals("Pages\\a::b.jpg", MediaPaths.archiveEntryName(path))
        assertEquals(path, MediaPaths.archiveEntryPath(MediaPaths.archivePath(path), MediaPaths.archiveEntryName(path)!!))
    }

    @Test
    fun archivePathAndEntryName_leaveOtherPathsAlone() {
        assertEquals("http://host:8000/Comics/Issue%20001.cbz", MediaPaths.archivePath("http://host:8000/Comics/Issue%20001.cbz"))
        assertNull(MediaPaths.archiveEntryName("http://host:8000/Comics/Issue%20001.cbz"))
        assertNull(MediaPaths.archiveEntryName("http://host:8000/Photos/"))
        assertNull(MediaPaths.archiveEntryName("/Photos/a.jpg"))
    }

    @Test
    fun archiveUrls_resolveTheirServerFolderAndName() {
        val archiveUrl = "http://host:8000/Comics/Issue%20001.cbz"

        assertTrue(MediaPaths.isRemote(archiveUrl))
        assertEquals("http://host:8000/Comics/", MediaPaths.parent(archiveUrl))
        assertEquals("Issue 001.cbz", MediaPaths.name(archiveUrl))
        assertEquals("cbz", MediaPaths.extension(archiveUrl))
    }

    @Test
    fun link_coversServerPathsOnly() {
        assertEquals("http://host:8000/Photos/", MediaPaths.link("http://host:8000/Photos/"))
        assertEquals("http://host:8000/Photos/a%20b.jpg", MediaPaths.link("http://host:8000/Photos/a%20b.jpg"))
        assertEquals(
            "http://host:8000/Comics/Issue%20001.cbz",
            MediaPaths.link("http://host:8000/Comics/Issue%20001.cbz::Pages/001.jpg"),
        )
        assertNull(MediaPaths.link("/storage/emulated/0/Photos/a.jpg"))
        assertNull(MediaPaths.link("/Comics/Issue 001.cbr::001.jpg"))
        assertNull(MediaPaths.link(null))
    }
}
