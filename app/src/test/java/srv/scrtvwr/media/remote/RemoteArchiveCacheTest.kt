package srv.scrtvwr.media.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RemoteArchiveCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fileName_isTheSha256OfTheUrlInHex() {
        // FIPS 180-2 test vector; changing the hash would orphan every cached archive.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            RemoteArchiveCache.fileName("abc"),
        )
    }

    @Test
    fun fileName_keepsTheLowercaseExtensionSoTheCopyIsRecognizedAsAnArchive() {
        val name = RemoteArchiveCache.fileName("http://host:8000/Comics/Issue%20001.CBZ")

        assertTrue(name, name.matches(Regex("[0-9a-f]{64}\\.cbz")))
        assertEquals(name, RemoteArchiveCache.fileName("http://host:8000/Comics/Issue%20001.CBZ"))
    }

    @Test
    fun fileName_differsForEveryArchiveUrl() {
        val name = RemoteArchiveCache.fileName("http://host:8000/Comics/a.cbz")

        assertNotEquals(name, RemoteArchiveCache.fileName("http://host:8000/Comics/b.cbz"))
        assertNotEquals(name, RemoteArchiveCache.fileName("http://host:8001/Comics/a.cbz"))
        assertNotEquals(name, RemoteArchiveCache.fileName("http://host:8000/Other/a.cbz"))
    }

    @Test
    fun fileName_keepsOnlyLettersAndDigitsOfTheExtension() {
        assertTrue(RemoteArchiveCache.fileName("http://host:8000/odd.c%20b%2Fr").matches(Regex("[0-9a-f]{64}\\.cbr")))
        assertTrue(RemoteArchiveCache.fileName("http://host:8000/archive").matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun filesToEvict_dropsTheLeastRecentlyUsedFilesBeyondTheCap() {
        val newest = cacheFile("newest", sizeBytes = 40, lastModified = 3)
        val middle = cacheFile("middle", sizeBytes = 30, lastModified = 2)
        val oldest = cacheFile("oldest", sizeBytes = 50, lastModified = 1)

        assertEquals(listOf(oldest), RemoteArchiveCache.filesToEvict(listOf(oldest, newest, middle), maxBytes = 80))
        assertEquals(listOf(middle, oldest), RemoteArchiveCache.filesToEvict(listOf(oldest, newest, middle), maxBytes = 60))
        assertEquals(emptyList<File>(), RemoteArchiveCache.filesToEvict(listOf(oldest, newest, middle), maxBytes = 120))
    }

    @Test
    fun filesToEvict_keepsTheArchiveBeingOpenedEvenWhenItExceedsTheBudget() {
        val current = cacheFile("current", sizeBytes = 120, lastModified = 1)
        val other = cacheFile("other", sizeBytes = 30, lastModified = 2)

        assertEquals(
            listOf(other),
            RemoteArchiveCache.filesToEvict(listOf(other, current), maxBytes = 80, keep = current),
        )
    }

    @Test
    fun file_acceptsMultiGigabyteLengthAndCleansUpWhenTheCallerStops() = runBlocking {
        val cacheDirectory = temporaryFolder.newFolder("large-cache")
        RemoteArchiveCache.initialize(cacheDirectory)
        val declaredLength = 3L * 1024L * 1024L * 1024L
        TestHttpServer(mapOf("large.cbz" to ByteArray(0)), declaredFileLength = declaredLength).use { server ->
            val url = server.url("large.cbz")
            var reportedLength: Long? = null
            val stoppedByCaller = IOException("Stop after headers")

            val error = runCatching {
                RemoteArchiveCache.file(url) { progress ->
                    reportedLength = progress.totalBytes
                    throw stoppedByCaller
                }
            }.exceptionOrNull()

            assertEquals(declaredLength, reportedLength ?: -1L)
            assertEquals(stoppedByCaller, error)
            assertNull(RemoteArchiveCache.cachedFile(url))
            assertTrue(File(cacheDirectory, "remote_archives").list().orEmpty().isEmpty())
        }
    }

    @Test
    fun file_downloadsTheArchiveOnceUnderItsHashedName() = runBlocking {
        val cacheDirectory = temporaryFolder.newFolder("cache")
        RemoteArchiveCache.initialize(cacheDirectory)
        val bytes = ByteArray(200_000) { it.toByte() }
        TestHttpServer(mapOf("Comics/Issue 001.CBZ" to bytes)).use { server ->
            val url = server.url("Comics/Issue 001.CBZ")
            val progress = mutableListOf<DownloadProgress>()

            val file = RemoteArchiveCache.file(url) { progress += it }

            val archives = File(cacheDirectory, "remote_archives")
            assertEquals(File(archives, RemoteArchiveCache.fileName(url)), file)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(DownloadProgress(0L, bytes.size.toLong()), progress.first())
            assertEquals(DownloadProgress(bytes.size.toLong(), bytes.size.toLong()), progress.last())
            assertEquals(listOf(file.name), archives.list()?.toList())
            assertEquals(file, RemoteArchiveCache.file(url))
            assertEquals(1, server.fileRequests)
        }
    }

    @Test
    fun file_failsWithoutCachingAnything() = runBlocking {
        val cacheDirectory = temporaryFolder.newFolder("cache")
        RemoteArchiveCache.initialize(cacheDirectory)
        TestHttpServer(emptyMap()).use { server ->
            val url = server.url("Comics/missing.cbz")

            val error = runCatching { RemoteArchiveCache.file(url) }.exceptionOrNull()

            assertTrue(error.toString(), error is IOException)
            assertNull(RemoteArchiveCache.cachedFile(url))
            assertEquals(emptyList<String>(), File(cacheDirectory, "remote_archives").list()?.toList().orEmpty())
        }
    }

    private fun cacheFile(name: String, sizeBytes: Int, lastModified: Long): File =
        temporaryFolder.newFile(name).also { file ->
            file.writeBytes(ByteArray(sizeBytes))
            // Whole minutes apart, so coarse file system timestamps still order them.
            file.setLastModified(1_700_000_000_000L + lastModified * 60_000L)
        }
}
