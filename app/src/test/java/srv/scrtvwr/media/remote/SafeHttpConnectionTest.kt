package srv.scrtvwr.media.remote

import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import srv.scrtvwr.BuildConfig
import java.io.IOException

class SafeHttpConnectionTest {
    @After fun resetCredentials() { HttpServerAuth.update(emptyList()) }

    @Test fun publicEditionRejectsHttpBeforeOpeningASocket() {
        org.junit.Assume.assumeFalse(BuildConfig.PERSONAL_EDITION)
        val error = assertThrows(IOException::class.java) { SafeHttpConnection.open("http://127.0.0.1:9/") }
        assertTrue(error.message.orEmpty().contains("HTTPS"))
    }

    @Test fun savedCredentialsCannotBypassTransportPolicy() {
        val url = "http://127.0.0.1:9/"
        HttpServerAuth.update(listOf(HttpServer("private", "Private", url,
            credentials = HttpCredentials("user", "password"))))
        val error = assertThrows(IOException::class.java) { SafeHttpConnection.open(url) }
        assertTrue(error.message.orEmpty().contains("HTTPS"))
    }

    @Test fun redirectsCannotContactADifferentOrigin() {
        TestHttpServer(mapOf("secret.mp4" to byteArrayOf(1))).use { destination ->
            TestHttpServer(emptyMap(), redirects = mapOf("start" to destination.url("secret.mp4"))).use { source ->
                assertThrows(IOException::class.java) { SafeHttpConnection.open(source.url("start")) }
                assertEquals(0, destination.fileRequests)
            }
        }
    }

    @Test fun sameOriginRedirectStillReadsTheSelectedFile() {
        TestHttpServer(mapOf("photo.jpg" to "image".toByteArray()), redirects = mapOf("start" to "/photo.jpg")).use { server ->
            val connection = SafeHttpConnection.open(server.url("start"))
            try {
                assertEquals("image", connection.inputStream.bufferedReader().use { it.readText() })
            } finally { connection.disconnect() }
        }
    }
}
