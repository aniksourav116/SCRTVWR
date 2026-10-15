package srv.scrtvwr.media.remote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpServerAccessTest {
    private val root = HttpServer(id = "root", name = "Root", baseUrl = "http://host:8000/")
    private val privateFolder = HttpServer(id = "private", name = "Private", baseUrl = "http://host:8000/private/")
    private val pinned = HttpServer(id = "pinned", name = "Pinned", baseUrl = "https://host:8443/", certificateSha256 = "AB12")
    private val alice = HttpCredentials(username = "alice", password = "s3cret")

    @After
    fun forgetServers() {
        HttpServerAccess.update(emptyList(), emptyMap())
    }

    @Test
    fun authorization_usesTheCredentialsOfTheMostSpecificServer() {
        HttpServerAccess.update(listOf(root, privateFolder), mapOf(privateFolder.id to alice))

        assertEquals(alice.authorization(), HttpServerAccess.authorizationFor("http://host:8000/private/clip.mp4"))
        assertNull(HttpServerAccess.authorizationFor("http://host:8000/public/clip.mp4"))
        assertNull(HttpServerAccess.authorizationFor("http://other:8000/private/clip.mp4"))
    }

    @Test
    fun pinFor_onlyAppliesWithinThePinnedServer() {
        HttpServerAccess.update(listOf(root, pinned), emptyMap())

        assertEquals("AB12", HttpServerAccess.pinFor("https://host:8443/Photos/a.jpg"))
        assertNull(HttpServerAccess.pinFor("http://host:8000/Photos/a.jpg"))
    }

    @Test
    fun isProtected_isTrueOnlyWhereOtherAppsWouldNeedCredentialsOrAPin() {
        HttpServerAccess.update(listOf(root, privateFolder, pinned), mapOf(privateFolder.id to alice))

        assertTrue(HttpServerAccess.isProtected("http://host:8000/private/a.jpg"))
        assertTrue(HttpServerAccess.isProtected("https://host:8443/a.jpg"))
        assertFalse(HttpServerAccess.isProtected("http://host:8000/a.jpg"))
        assertFalse(HttpServerAccess.isProtected("/storage/emulated/0/a.jpg"))
    }

    @Test
    fun authorization_isBasicWithUtf8AsInRfc7617() {
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", HttpCredentials("Aladdin", "open sesame").authorization())
        assertEquals("Basic dGVzdDoxMjPCow==", HttpCredentials("test", "123£").authorization())
    }

    @Test
    fun credentials_keepThePasswordOutOfTheirText() {
        assertFalse(alice.toString().contains(alice.password))
    }
}
