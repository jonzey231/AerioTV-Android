package com.aeriotv.android.core.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The playlist's sign-in in a Set up another device payload (Apple a634285):
 * the stored key and saved password go only when every address the setup
 * names is proven; the username always rides.
 */
class SetupCredentialTest {

    @Test fun aProvenRouteCarriesTheStoredKeyAndPassword() {
        val c = setupCredential("stored-key", "admin", "saved-pass", mayCarry = true)!!
        assertEquals("stored-key", c.apiKey)
        assertEquals("admin", c.username)
        assertEquals("saved-pass", c.password)
    }

    @Test fun anUnprovenRouteCarriesNeitherButKeepsTheUsername() {
        val c = setupCredential("stored-key", "admin", "saved-pass", mayCarry = false)!!
        assertNull(c.apiKey)
        assertNull(c.password)
        assertEquals("admin", c.username)
    }

    @Test fun nothingLeftIsNoCredential() {
        assertNull(setupCredential("stored-key", null, "saved-pass", mayCarry = false))
        assertNull(setupCredential(" ", "", null, mayCarry = true))
    }

    @Test fun fixLoginHasNothingToSendWhenTheRouteIsUnproven() {
        // Fix Login needs a key or a password; an unproven route leaves only the username.
        val part = SetupProtocol.PlaylistPart(
            type = "dispatcharr", name = "Home", url = "https://tv.example.test",
            credential = setupCredential("stored-key", "admin", "saved-pass", mayCarry = false),
        )
        assertNull(fixLoginPayload(part, "11111111-2222-3333-4444-555555555555"))
    }

    @Test fun presenceNeverLogsAValue() {
        assertEquals("present", presence("saved-pass"))
        assertEquals("absent", presence(null))
        assertEquals("absent", presence(""))
    }
}
