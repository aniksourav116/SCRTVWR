package srv.scrtvwr.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import srv.scrtvwr.ui.sort.DirectorySortOrder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MediaCatalogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun isArchive_recognizesRarAndCbrFilesIncludingArchiveEntries() {
        assertTrue(MediaCatalog.isArchive("/Comics/Issue 001.RAR"))
        assertTrue(MediaCatalog.isArchive("/Comics/Issue 002.cbr::Pages/001.jpg"))
        assertFalse(MediaCatalog.isArchive("/Comics/Issue 003.7z"))
    }

    @Test
    fun nextArchive_usesSortOrderAndSkipsArchivesWithoutImages() = runBlocking {
        val directory = temporaryFolder.newFolder("archives")
        val current = directory.archive("a.cbz", "page.jpg")
        directory.archive("b.cbz", "notes.txt")
        val expected = directory.archive("c.zip", "cover.png")

        val result = MediaCatalog.nextArchive(current, DirectorySortOrder.NameAscending)

        assertEquals(expected.absolutePath, result?.path)
        assertEquals(true, result?.isArchive)
    }

    @Test
    fun nextArchive_doesNotWrapPastLastArchive() = runBlocking {
        val directory = temporaryFolder.newFolder("archives")
        directory.archive("a.cbz", "page.jpg")
        val current = directory.archive("b.cbz", "page.jpg")

        assertNull(MediaCatalog.nextArchive(current, DirectorySortOrder.NameAscending))
    }

    @Test
    fun previousArchive_usesSortOrderAndSkipsArchivesWithoutImages() = runBlocking {
        val directory = temporaryFolder.newFolder("archives")
        val expected = directory.archive("a.cbz", "page.jpg")
        directory.archive("b.cbz", "notes.txt")
        val current = directory.archive("c.zip", "cover.png")

        val result = MediaCatalog.previousArchive(current, DirectorySortOrder.NameAscending)

        assertEquals(expected.absolutePath, result?.path)
        assertEquals(true, result?.isArchive)
    }

    @Test
    fun previousArchive_doesNotWrapPastFirstArchive() = runBlocking {
        val directory = temporaryFolder.newFolder("archives")
        val current = directory.archive("a.cbz", "page.jpg")
        directory.archive("b.cbz", "page.jpg")

        assertNull(MediaCatalog.previousArchive(current, DirectorySortOrder.NameAscending))
    }

    @Test
    fun previousDirectory_usesSortOrderAndSkipsDirectoriesWithoutMedia() = runBlocking {
        val root = temporaryFolder.newFolder("directories")
        val expected = root.mediaDirectory("a", "page.jpg")
        root.mediaDirectory("b", "notes.txt")
        val current = root.mediaDirectory("c", "cover.png")

        val result = MediaCatalog.previousDirectory(current, DirectorySortOrder.NameAscending)

        assertEquals(expected.absolutePath, result?.path)
        assertEquals(false, result?.isArchive)
    }

    @Test
    fun previousDirectory_doesNotWrapPastFirstDirectory() = runBlocking {
        val root = temporaryFolder.newFolder("directories")
        val current = root.mediaDirectory("a", "page.jpg")
        root.mediaDirectory("b", "page.jpg")

        assertNull(MediaCatalog.previousDirectory(current, DirectorySortOrder.NameAscending))
    }

    private fun File.archive(name: String, entryName: String): File =
        resolve(name).also { archive ->
            ZipOutputStream(archive.outputStream()).use { output ->
                output.putNextEntry(ZipEntry(entryName))
                output.write(byteArrayOf(1))
                output.closeEntry()
            }
        }

    private fun File.mediaDirectory(name: String, itemName: String): File =
        resolve(name).also { directory ->
            directory.mkdirs()
            directory.resolve(itemName).writeBytes(byteArrayOf(1))
        }
}
