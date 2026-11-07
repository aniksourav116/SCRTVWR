package srv.scrtvwr.media.remote

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HttpServerAuthTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val credentials = HttpCredentials(username = "media", password = "pä:ss")

    @After
    fun forgetServers() {
        HttpServerAuth.update(emptyList())
    }

    @Test
    fun authorization_encodesUsernameAndPasswordAsUtf8Base64() {
        // base64("media:pä:ss") with ä as two UTF-8 bytes.
        assertEquals("Basic bWVkaWE6cMOkOnNz", credentials.authorization)
    }

    @Test
    fun of_treatsTwoEmptyFieldsAsAnOpenServer() {
        assertNull(HttpCredentials.of("  ", ""))
        assertEquals(HttpCredentials("media", " secret "), HttpCredentials.of(" media ", " secret "))
        assertEquals(HttpCredentials("", "secret"), HttpCredentials.of("", "secret"))
    }

    @Test
    fun toString_leavesThePasswordOut() {
        assertFalse("pä:ss" in credentials.toString())
    }

    @Test
    fun authorizationFor_usesTheServerHoldingTheUrl() {
        HttpServerAuth.update(
            listOf(
                HttpServer("a", "A", "http://192.168.0.2:8000/", credentials = credentials),
                HttpServer("b", "B", "http://192.168.0.2:8001/"),
            ),
        )

        assertEquals(credentials.authorization, HttpServerAuth.authorizationFor("http://192.168.0.2:8000/Videos/a.mp4"))
        assertNull(HttpServerAuth.authorizationFor("http://192.168.0.2:8001/a.mp4"))
        assertNull(HttpServerAuth.authorizationFor("http://192.168.0.3:8000/a.mp4"))
    }

    @Test
    fun listAndDownload_rejectCredentialsOverHttp() = runBlocking {
        val payload = byteArrayOf(1, 2, 3)
        TestHttpServer(mapOf("Videos/a.mp4" to payload), requiredAuthorization = credentials.authorization).use { server ->
            HttpServerAuth.update(listOf(HttpServer("a", "A", server.baseUrl, credentials = credentials)))
            val target = temporaryFolder.newFile()

            assertThrows(java.io.IOException::class.java) { HttpDirectoryClient.list(server.url("Videos/"), refresh = true) }
            val error = runCatching { HttpDirectoryClient.download(server.url("Videos/a.mp4"), target) }.exceptionOrNull()
            org.junit.Assert.assertTrue(error is java.io.IOException)
            assertEquals(0, server.fileRequests)
            assertEquals(0L, target.length())
        }
    }

    @Test(expected = HttpAuthenticationException::class)
    fun list_reportsMissingCredentialsAsAnAuthenticationFailure() {
        TestHttpServer(mapOf("a.mp4" to ByteArray(1)), requiredAuthorization = credentials.authorization).use { server ->
            HttpDirectoryClient.list(server.baseUrl, refresh = true)
        }
    }

    @Test(expected = java.io.IOException::class)
    fun list_rejectsWrongCredentialsBeforeSendingThemOverHttp() {
        TestHttpServer(mapOf("a.mp4" to ByteArray(1)), requiredAuthorization = credentials.authorization).use { server ->
            HttpServerAuth.update(listOf(HttpServer("a", "A", server.baseUrl, credentials = credentials.copy(password = "no"))))
            HttpDirectoryClient.list(server.baseUrl, refresh = true)
        }
    }
}
