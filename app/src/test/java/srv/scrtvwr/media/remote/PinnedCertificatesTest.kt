package srv.scrtvwr.media.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class PinnedCertificatesTest {
    @Test
    fun sha256_isUppercaseHexOfTheDerEncoding() {
        // FIPS 180-2 test vector.
        assertEquals(
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            PinnedCertificates.sha256("abc".toByteArray()),
        )
    }

    @Test
    fun format_separatesBytesWithColonsLikeTheServerScript() {
        assertEquals("BA:78:16:BF", PinnedCertificates.format("BA7816BF"))
    }

    @Test
    fun trustManager_acceptsOnlyThePinnedCertificate() {
        val (pinned, other) = realCertificates().distinctBy { PinnedCertificates.sha256(it) }.take(2)
        val trustManager = PinnedCertificates.pinning(PinnedCertificates.sha256(pinned)).trustManager

        trustManager.checkServerTrusted(arrayOf(pinned), "RSA")
        assertThrows(CertificateException::class.java) { trustManager.checkServerTrusted(arrayOf(other), "RSA") }
        assertThrows(CertificateException::class.java) { trustManager.checkServerTrusted(emptyArray(), "RSA") }
    }

    @Test
    fun serverCertificate_recordsTheFingerprintShownForReview() {
        val certificate = realCertificates().first()

        assertEquals(PinnedCertificates.sha256(certificate.encoded), ServerCertificate(certificate).sha256)
    }

    /** Any real certificates will do; the JVM's own certificate authorities are always there. */
    private fun realCertificates(): List<X509Certificate> {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first().acceptedIssuers.toList()
    }
}
