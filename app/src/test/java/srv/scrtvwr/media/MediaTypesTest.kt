package srv.scrtvwr.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTypesTest {
    @Test
    fun isImageAndIsVideo_readTheLastNameOfAnyPath() {
        assertTrue(MediaTypes.isImage("/Photos/a.JPG"))
        assertTrue(MediaTypes.isImage("http://host:8000/Photos/a%20b.webp"))
        assertTrue(MediaTypes.isImage("Pages\\001.png"))
        assertTrue(MediaTypes.isImage("/Comics/Issue 001.cbz::Pages/001.jpg"))
        assertTrue(MediaTypes.isVideo("/Movies/clip.MKV"))
        assertTrue(MediaTypes.isVideo("http://host:8000/Movies/clip.mp4"))

        assertFalse(MediaTypes.isImage("/Movies/clip.mp4"))
        assertFalse(MediaTypes.isVideo("/Photos/a.jpg"))
        assertFalse(MediaTypes.isImage("/Photos.jpg/notes"))
        assertFalse(MediaTypes.isVideo("http://host:8000/Movies.mp4/"))
    }

    @Test
    fun isArchive_recognizesRarAndCbrFilesIncludingArchiveEntries() {
        assertTrue(MediaTypes.isArchive("/Comics/Issue 001.RAR"))
        assertTrue(MediaTypes.isArchive("/Comics/Issue 002.cbr::Pages/001.jpg"))
        assertFalse(MediaTypes.isArchive("/Comics/Issue 003.7z"))
    }

    @Test
    fun archiveFormat_readsArchiveUrlsAndTheirEntries() {
        assertEquals(MediaTypes.ArchiveFormat.Zip, MediaTypes.archiveFormat("http://host:8000/Comics/Issue%20001.CBZ"))
        assertEquals(
            MediaTypes.ArchiveFormat.Rar,
            MediaTypes.archiveFormat("http://host:8000/Comics/Issue%20002.cbr::Pages/001.jpg"),
        )
        assertNull(MediaTypes.archiveFormat("http://host:8000/Comics.cbz/"))
        assertFalse(MediaTypes.isArchive("http://host:8000/Comics/"))
        assertFalse(MediaTypes.isArchive("http://host:8000/Comics/cover.jpg"))
    }
}
