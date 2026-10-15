package srv.scrtvwr.media.remote

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpDirectoryClientAuthTest {
    private val credentials = HttpCredentials(username = "alice", password = "s3cret")
    private val files = mapOf("Photos/a.jpg" to byteArrayOf(1, 2, 3))

    @After
    fun forgetServers() {
        HttpServerAccess.update(emptyList(), emptyMap())
    }

    @Test
    fun list_signsInWithTheServersCredentials() {
        TestHttpServer(files, requiredAuthorization = credentials.authorization()).use { server ->
            register(server.baseUrl, credentials)

            val entries = HttpDirectoryClient.list(server.baseUrl, refresh = true)

            assertEquals(listOf("Photos"), entries.map(RemoteEntry::name))
            assertEquals(listOf(credentials.authorization()), server.authorizations)
        }
    }

    @Test
    fun readBytes_signsInToo() {
        TestHttpServer(files, requiredAuthorization = credentials.authorization()).use { server ->
            register(server.baseUrl, credentials)

            assertArrayEquals(byteArrayOf(1, 2, 3), HttpDirectoryClient.readBytes(server.url("Photos/a.jpg"), maxBytes = 1024))
        }
    }

    @Test
    fun list_reportsMissingCredentialsAsAnAuthError() {
        TestHttpServer(files, requiredAuthorization = credentials.authorization()).use { server ->
            register(server.baseUrl, credentials = null)

            val error = runCatching { HttpDirectoryClient.list(server.baseUrl, refresh = true) }.exceptionOrNull()

            assertTrue(error.toString(), error is HttpAuthException)
            assertEquals(listOf<String?>(null), server.authorizations)
        }
    }

    @Test
    fun readBytes_reportsWrongCredentialsAsAnAuthError() {
        TestHttpServer(files, requiredAuthorization = credentials.authorization()).use { server ->
            register(server.baseUrl, credentials.copy(password = "wrong"))

            val error = runCatching { HttpDirectoryClient.readBytes(server.url("Photos/a.jpg"), maxBytes = 1024) }
                .exceptionOrNull()

            assertTrue(error.toString(), error is HttpAuthException)
            assertEquals(0, server.fileRequests)
        }
    }

    @Test
    fun credentialsOnlyGoToUrlsUnderTheirServer() {
        TestHttpServer(files).use { server ->
            TestHttpServer(files).use { otherServer ->
                register(server.url("Photos/"), credentials)

                HttpDirectoryClient.list(server.baseUrl, refresh = true)
                HttpDirectoryClient.list(otherServer.url("Photos/"), refresh = true)
                HttpDirectoryClient.list(server.url("Photos/"), refresh = true)

                assertEquals(listOf(null, credentials.authorization()), server.authorizations)
                assertEquals(listOf<String?>(null), otherServer.authorizations)
            }
        }
    }

    @Test
    fun clearCache_makesTheNextListingAskTheServerAgain() {
        TestHttpServer(files).use { server ->
            HttpDirectoryClient.list(server.baseUrl, refresh = true)
            HttpDirectoryClient.list(server.baseUrl)
            assertEquals(1, server.authorizations.size)

            HttpDirectoryClient.clearCache(server.baseUrl)
            HttpDirectoryClient.list(server.baseUrl)

            assertEquals(2, server.authorizations.size)
        }
    }

    private fun register(baseUrl: String, credentials: HttpCredentials?) {
        val server = HttpServer(id = "server", name = "Server", baseUrl = baseUrl)
        HttpServerAccess.update(listOf(server), listOfNotNull(credentials).associateBy { server.id })
    }
}
