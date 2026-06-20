package srv.scrtvwr.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun File.archive(name: String, entryName: String): File =
        resolve(name).also { archive ->
            ZipOutputStream(archive.outputStream()).use { output ->
                output.putNextEntry(ZipEntry(entryName))
                output.write(byteArrayOf(1))
                output.closeEntry()
            }
        }
}
