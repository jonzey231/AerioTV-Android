package com.aeriotv.android.core.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/** Fixture: a self-signed EC certificate, CN "AerioTV Test", password "test", AES-encrypted. */
class ClientCertificatesTest {
    private val p12 = javaClass.getResourceAsStream("/mtls/client.p12")!!.use { it.readBytes() }

    @After fun clearLookup() {
        ClientCertificates.lookup = { null }
    }

    @Test fun parsesKeyAndChain() {
        val cert = ClientCertificates.parse(p12, "test")
        assertEquals("AerioTV Test", cert.subject)
        assertEquals("EC", cert.key.algorithm)
        assertEquals(1, cert.chain.size)
    }

    @Test fun wrongPasswordSaysSo() {
        val e = runCatching { ClientCertificates.parse(p12, "nope") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("password"))
    }

    @Test fun offersTheCertificateToItsHostOnly() {
        val cert = ClientCertificates.parse(p12, "test")
        ClientCertificates.lookup = { if (it == "tv.example.com") cert else null }
        val km = ClientCertificates.HostKeyManager
        fun aliasFor(host: String) = km.chooseEngineClientAlias(
            arrayOf("EC"), null, SSLContext.getDefault().createSSLEngine(host, 443),
        )
        assertEquals("tv.example.com", aliasFor("TV.example.com"))
        assertSame(cert.key, km.getPrivateKey("tv.example.com"))
        assertNull(aliasFor("api.themoviedb.org"))
    }

    // BoringSSL's wording as Conscrypt puts it in the exception message.
    private fun alert(name: String) = IOException(
        "Connection check failed",
        SSLException("Read error: ssl=0x7b: Failure in SSL library, usually a protocol error\n" +
            "error:10000074:SSL routines:OPENSSL_internal:$name"),
    )

    @Test fun certificateRequiredAsksForOne() {
        val copy = ClientCertificates.describe(alert("TLSV1_ALERT_CERTIFICATE_REQUIRED"), "tv.example.com")
        assertTrue(copy!!.startsWith("The server asks for a client certificate"))
    }

    @Test fun badCertificateDependsOnWhetherOneWasOffered() {
        val bad = alert("SSLV3_ALERT_BAD_CERTIFICATE")
        assertTrue(ClientCertificates.describe(bad, "tv.example.com")!!.startsWith("The server asks"))
        val cert = ClientCertificates.parse(p12, "test")
        ClientCertificates.lookup = { if (it == "tv.example.com") cert else null }
        assertTrue(ClientCertificates.describe(bad, "tv.example.com")!!.startsWith("The server didn't accept"))
    }

    @Test fun otherFailuresAreLeftAlone() {
        // The server's own certificate failing OUR check is not about the client certificate.
        assertNull(ClientCertificates.describe(alert("CERTIFICATE_VERIFY_FAILED"), "tv.example.com"))
        assertNull(ClientCertificates.describe(IOException("timeout"), null))
    }
}
