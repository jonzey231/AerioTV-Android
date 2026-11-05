package com.aeriotv.android.core.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed vectors shared with the Apple app (docs/DeviceSetupHandoff.md). */
class UserQrCryptoTest {
    private val key = "test-key-123"
    private val link = "aeriotv://setup?v=1&ip=192.168.1.20&port=5000&code=123456&pk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&name=iPad"

    @Test
    fun uqMatchesVector() = assertEquals("d66eadb6a1fff42c", UserQrCrypto.uq(key))

    @Test
    fun proofMatchesVector() = assertEquals("WjHdnpPuNCR8MJeEQP4quCIAynNokhPPygG2+VGcyNk=", UserQrCrypto.proof(key, link))

    @Test
    fun verifyRejectsOtherKey() {
        assertTrue(UserQrCrypto.verify(key, link, UserQrCrypto.proof(key, link)))
        assertFalse(UserQrCrypto.verify("other", link, UserQrCrypto.proof(key, link)))
    }
}
