package srv.scrtvwr.media.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import srv.scrtvwr.media.MediaCatalog
import srv.scrtvwr.ui.sort.DirectorySortOrder

class RemoteDirectorySearchTest {
    @Test
    fun nextDirectory_checksFoldersWithoutFetchingFileMetadata() = runBlocking {
        TestHttpServer(
            mapOf("a/current.jpg" to ByteArray(1), "b/next.mp4" to ByteArray(1)),
        ).use { server ->
            val next = MediaCatalog.nextRemoteDirectory(
                server.url("a/"), DirectorySortOrder.NewestFirst, server.baseUrl,
            )

            assertEquals(server.url("b/"), next?.path)
            assertEquals(0, server.fileRequests)
            // a, root, and b; b is reused when testing the yielded candidate.
            assertEquals(3, server.directoryRequests)
        }
    }

    @Test
    fun nextDirectory_stopsBeforeScanningAnEntireDeepServerTree() = runBlocking {
        TestHttpServer(mapOf("a/".repeat(140) + "image.jpg" to ByteArray(1))).use { server ->
            val next = MediaCatalog.nextRemoteDirectory(
                server.baseUrl, DirectorySortOrder.NameAscending, server.baseUrl,
            )

            assertNull(next)
            assertTrue(server.directoryRequests <= 128)
            assertEquals(0, server.fileRequests)
        }
    }
}
