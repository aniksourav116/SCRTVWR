package srv.scrtvwr.media.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class HttpVideoPreviewTest {
    @Test
    fun unsupportedServerReceivesOnlyAHeadProbe() = runBlocking {
        TestHttpServer(mapOf("video.mp4" to byteArrayOf(1, 2, 3))).use { server ->
            assertNull(HttpDirectoryClient.videoPreview(server.url("video.mp4")))
            assertEquals(1, server.fileRequests)
            assertEquals(0, server.previewGets)
        }
    }

    @Test
    fun supportingServerReturnsOnlyTheThumbnailForEncodedNames() = runBlocking {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte())
        TestHttpServer(mapOf("clip & \u00e9.mp4" to byteArrayOf(9)), videoPreview = jpeg).use { server ->
            assertArrayEquals(jpeg, HttpDirectoryClient.videoPreview(server.url("clip & \u00e9.mp4")))
            assertEquals(1, server.previewGets)
            assertEquals(0, server.fileRequests)
        }
    }

    @Test
    fun existingQueriesAndFragmentsDoNotBreakPreviewRequests() = runBlocking {
        val jpeg = byteArrayOf(1, 2)
        TestHttpServer(mapOf("video.mp4" to byteArrayOf(9)), videoPreview = jpeg).use { server ->
            assertArrayEquals(jpeg, HttpDirectoryClient.videoPreview(server.url("video.mp4") + "?key=value#clip"))
            assertEquals(1, server.previewGets)
        }
    }

    @Test
    fun aVideoResponseCannotBeUsedAsAThumbnail() = runBlocking {
        TestHttpServer(emptyMap(), videoPreview = byteArrayOf(9), previewContentType = "video/mp4").use { server ->
            assertNull(HttpDirectoryClient.videoPreview(server.url("video.mp4")))
        }
    }

    @Test
    fun oversizedDeclaredPreviewIsRejected() = runBlocking {
        TestHttpServer(emptyMap(), videoPreview = byteArrayOf(1), declaredPreviewLength = 2L * 1024 * 1024).use { server ->
            expectIOException { HttpDirectoryClient.videoPreview(server.url("video.mp4")) }
        }
    }

    @Test
    fun oversizedBodyIsRejectedEvenWithoutAContentLength() = runBlocking {
        TestHttpServer(emptyMap(), videoPreview = ByteArray(1024 * 1024 + 1), omitPreviewLength = true).use { server ->
            expectIOException { HttpDirectoryClient.videoPreview(server.url("video.mp4")) }
        }
    }

    private suspend fun expectIOException(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected an IOException")
        } catch (_: IOException) {
            // Expected: the preview exceeds the response limit.
        }
    }
}
