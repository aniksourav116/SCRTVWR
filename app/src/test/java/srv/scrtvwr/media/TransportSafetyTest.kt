package srv.scrtvwr.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import srv.scrtvwr.BuildConfig

class TransportSafetyTest {
    @Test fun passwordsRequireHttpsInEveryEdition() {
        assertTrue(TransportSafety.isAllowed("https://nas.example/media/", authenticated = true))
        assertFalse(TransportSafety.isAllowed("http://192.168.1.20:8000/", authenticated = true))
        assertFalse(TransportSafety.isAllowed("http://127.0.0.1/", authenticated = true))
    }

    @Test fun onlyPersonalAllowsUnauthenticatedPrivateIpv4Http() {
        for (host in listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.20", "127.0.0.1")) {
            assertEquals(host, BuildConfig.PERSONAL_EDITION, TransportSafety.isAllowed("http://$host:8000/"))
        }
        for (host in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "nas.local", "127.1", "010.0.0.1")) {
            assertFalse(host, TransportSafety.isAllowed("http://$host/"))
        }
    }

    @Test fun rejectsEmbeddedSecretsAndInvalidPorts() {
        assertFalse(TransportSafety.isAllowed("https://user:password@example.com/"))
        assertFalse(TransportSafety.isAllowed("https://example.com:99999/"))
        assertFalse(TransportSafety.isAllowed("file:///storage/emulated/0/"))
    }
}
