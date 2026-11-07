package srv.scrtvwr.ui.dashboard.screens.viewmodels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import srv.scrtvwr.media.remote.TestHttpServer
import java.io.File
import java.io.RandomAccessFile

private const val MEBIBYTE = 1024L * 1024L

class DirectorySearchTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun recursiveSearchVisitsNonMatchingFoldersAndPreservesDuplicateNames() = runBlocking {
        val root = temporaryFolder.newFolder("media")
        val first = root.resolve("one/Holiday.JPG").createFile()
        val second = root.resolve("two/Holiday.JPG").createFile()
        root.resolve(".hidden/Holiday.JPG").createFile()
        root.resolve("two/holiday.mp4").createFile()

        val result = searchDirectory(state(root, "HOLIDAY"), false) {}
        assertEquals(setOf(first.path, second.path, root.resolve("two/holiday.mp4").path),
            result.entries.map { it.path }.toSet())
        assertTrue(result.canRead)
    }

    @Test
    fun disablingSubfoldersOnlySearchesTheOpenFolder() = runBlocking {
        val root = temporaryFolder.newFolder("media")
        root.resolve("nested/photo.jpg").createFile()
        val direct = root.resolve("photo.jpg").createFile()
        val result = searchDirectory(state(root, "photo").copy(
            searchOptions = DirectorySearchOptions(includeSubfolders = false),
        ), false) {}
        assertEquals(listOf(direct.path), result.entries.map { it.path })
    }

    @Test
    fun sizeDateAndMediaFiltersWorkTogetherWithoutANameQuery() = runBlocking {
        val root = temporaryFolder.newFolder("media")
        val wanted = root.resolve("nested/current.jpg").createFile(2 * MEBIBYTE)
        val old = root.resolve("old.jpg").createFile(2 * MEBIBYTE)
        assertTrue(old.setLastModified(System.currentTimeMillis() - 40L * 86_400_000L))
        root.resolve("small.jpg").createFile(50)
        root.resolve("large.jpg").createFile(4 * MEBIBYTE)
        root.resolve("movie.mp4").createFile(2 * MEBIBYTE)

        val result = searchDirectory(state(root, "").copy(
            mediaFilter = DirectoryMediaFilter.Images,
            searchOptions = DirectorySearchOptions(minimumSizeMiB = 1, maximumSizeMiB = 3, modifiedWithinDays = 7),
        ), false) {}
        assertEquals(listOf(wanted.path), result.entries.map { it.path })
    }

    @Test
    fun cancellationIsNotConvertedIntoAnEmptyListing() = runBlocking {
        val root = temporaryFolder.newFolder("media")
        val result = runCatching {
            searchDirectory(state(root, "photo"), false) { throw CancellationException("Search replaced") }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun remoteSearchTraversesFoldersAndFetchesMetadataForMatchingFiles() = runBlocking {
        TestHttpServer(mapOf(
            "nested/photo.jpg" to ByteArray(20),
            "nested/movie.mp4" to ByteArray(20),
            ".hidden/photo.jpg" to ByteArray(20),
        )).use { server ->
            val result = searchDirectory(DirectoryUiState(
                rootPath = server.baseUrl,
                searchQuery = "photo",
                mediaFilter = DirectoryMediaFilter.Images,
                searchOptions = DirectorySearchOptions(maximumSizeMiB = 1),
            ), false) {}
            assertEquals(listOf(server.url("nested/photo.jpg")), result.entries.map { it.path })
            assertEquals(20L, result.entries.single().sizeBytes)
            assertEquals(1, server.fileRequests)
        }
    }

    @Test
    fun remoteDateFilterExcludesFilesWithUnknownDates() = runBlocking {
        TestHttpServer(mapOf("photo.jpg" to ByteArray(20))).use { server ->
            val result = searchDirectory(DirectoryUiState(
                rootPath = server.baseUrl,
                searchQuery = "photo",
                searchOptions = DirectorySearchOptions(modifiedWithinDays = 7),
            ), false) {}
            assertTrue(result.entries.isEmpty())
        }
    }

    private fun state(root: File, query: String) = DirectoryUiState(rootPath = root.path, searchQuery = query)

    private fun File.createFile(size: Long = 0): File = apply {
        parentFile!!.mkdirs()
        RandomAccessFile(this, "rw").use { it.setLength(size) }
    }
}
