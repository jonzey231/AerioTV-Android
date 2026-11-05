package com.aeriotv.android.core.sync

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInNonceTest {
    private fun token(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{\"alg\":\"RS256\"}".toByteArray()) + "." +
            enc.encodeToString(payload.toByteArray()) + ".sig"
    }

    @Test fun rawNoncesAreRandomAndLongEnough() {
        val a = SignInNonce.newRaw()
        assertNotEquals(a, SignInNonce.newRaw())
        assertTrue(Base64.getUrlDecoder().decode(a).size >= 16)
    }

    @Test fun hashIsSha256Hex() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SignInNonce.hashed("abc"),
        )
    }

    @Test fun matchingNonceAccepted() {
        val raw = SignInNonce.newRaw()
        assertTrue(SignInNonce.matches(token("{\"sub\":\"1\",\"nonce\":\"${SignInNonce.hashed(raw)}\"}"), raw))
    }

    @Test fun mismatchedNonceRejected() {
        val raw = SignInNonce.newRaw()
        val other = SignInNonce.hashed(SignInNonce.newRaw())
        assertFalse(SignInNonce.matches(token("{\"nonce\":\"$other\"}"), raw))
        assertFalse(SignInNonce.matches(token("{\"nonce\":\"$raw\"}"), raw))
    }

    @Test fun missingOrMalformedRejected() {
        val raw = SignInNonce.newRaw()
        assertFalse(SignInNonce.matches(token("{\"sub\":\"1\"}"), raw))
        assertFalse(SignInNonce.matches("not-a-jwt", raw))
        assertFalse(SignInNonce.matches("a.!!!.c", raw))
        assertNull(SignInNonce.nonceClaim(token("[1]")))
    }
}
