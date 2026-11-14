package com.aeriotv.android.feature.settings

import com.aeriotv.android.core.network.DispatcharrError
import com.aeriotv.android.core.network.RateLimitReason
import com.aeriotv.android.core.network.rateLimitedMessage
import com.aeriotv.android.core.setup.FixLogin
import com.aeriotv.android.core.setup.SetupProtocol
import com.aeriotv.android.feature.devicesetup.setupSendAllowed
import com.aeriotv.android.feature.settings.KeyRejectedCopy.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "API key no longer valid" prompt and its sign-in form (shared spec
 * with Apple, 2026-11-11): button sets per device type, the exact copy, the
 * error lines, and the apply rules.
 */
class KeyRejectedCopyTest {

    private fun labels(isTv: Boolean, canSignIn: Boolean) =
        KeyRejectedCopy.buttons(isTv, canSignIn).map(KeyRejectedCopy::label)

    @Test fun phoneButtons() {
        assertEquals(listOf(Button.SignIn, Button.EditPlaylist, Button.NotNow), KeyRejectedCopy.buttons(isTv = false, canSignIn = true))
        assertEquals(listOf("Sign In", "Edit Playlist", "Not Now"), labels(isTv = false, canSignIn = true))
        // Child profile or launch PIN: no way into the form.
        assertEquals(listOf("Edit Playlist", "Not Now"), labels(isTv = false, canSignIn = false))
    }

    @Test fun tvButtons() {
        assertEquals(listOf(Button.UpdateFromPhone, Button.SignInWithRemote, Button.NotNow), KeyRejectedCopy.buttons(isTv = true, canSignIn = true))
        assertEquals(listOf("Update from Phone", "Sign In with Remote", "Not Now"), labels(isTv = true, canSignIn = true))
        assertEquals(listOf("Update from Phone", "Not Now"), labels(isTv = true, canSignIn = false))
        // "Update with Remote" is gone; key entry stays in Edit Playlist.
        assertFalse(labels(isTv = true, canSignIn = true).contains("Update with Remote"))
        assertFalse(labels(isTv = true, canSignIn = true).contains("Edit Playlist"))
    }

    @Test fun childNeverSeesEditPlaylist() {
        // Apple parity (2026-11-11): Edit Playlist is hidden for a Child on every device.
        assertEquals(listOf("Sign In", "Not Now"), KeyRejectedCopy.buttons(isTv = false, canSignIn = true, isChild = true).map(KeyRejectedCopy::label))
        assertEquals(listOf("Not Now"), KeyRejectedCopy.buttons(isTv = false, canSignIn = false, isChild = true).map(KeyRejectedCopy::label))
        assertEquals(listOf("Update from Phone", "Not Now"), KeyRejectedCopy.buttons(isTv = true, canSignIn = false, isChild = true).map(KeyRejectedCopy::label))
        for (tv in listOf(true, false)) for (sign in listOf(true, false)) {
            assertFalse(KeyRejectedCopy.buttons(tv, sign, isChild = true).contains(Button.EditPlaylist))
        }
        // Everyone else keeps it on phones and tablets.
        assertTrue(KeyRejectedCopy.buttons(isTv = false, canSignIn = false, isChild = false).contains(Button.EditPlaylist))
    }

    @Test fun aChildsBodyNamesOnlyWhatItCanDo() {
        // A default Child is offered Sign In: the Edit Playlist clause is dropped.
        assertEquals(
            "Dispatcharr no longer accepts the API key for Home. It may have been changed on the server. " +
                "Sign in with your Dispatcharr username and password to reconnect.",
            KeyRejectedCopy.body("Home", passwordToo = false, isChild = true, canSignIn = true),
        )
        assertEquals(
            "Dispatcharr no longer accepts the sign-in for Home. It may have been changed on the server. " +
                "Sign in with your Dispatcharr username and password to reconnect.",
            KeyRejectedCopy.body("Home", passwordToo = true, isChild = true, canSignIn = true),
        )
        // A non-default Child is offered no sign-in: the body ends after the server sentence.
        assertEquals(
            "Dispatcharr no longer accepts the API key for Home. It may have been changed on the server.",
            KeyRejectedCopy.body("Home", passwordToo = false, isChild = true, canSignIn = false),
        )
        assertEquals(
            "Dispatcharr no longer accepts the sign-in for Home. It may have been changed on the server.",
            KeyRejectedCopy.body("Home", passwordToo = true, isChild = true, canSignIn = false),
        )
        // Everyone else keeps the full body, whatever the buttons.
        assertEquals(KeyRejectedCopy.body("Home", passwordToo = false), KeyRejectedCopy.body("Home", passwordToo = false, isChild = false, canSignIn = false))
        // The no-key line is the same for a Child.
        assertEquals(KeyRejectedCopy.NO_API_KEY, KeyRejectedCopy.body("Home", passwordToo = false, noApiKey = true, isChild = true, canSignIn = false))
    }

    @Test fun aProfilesOwnRefusedKeyOffersOnlyItsOwnSignIn() {
        // APP7-1: an adult non-default profile's own key. Edit Playlist and
        // Update from Phone write only the playlist's sign-in, so they go.
        assertEquals(listOf("Sign In", "Not Now"), KeyRejectedCopy.buttons(isTv = false, canSignIn = true, profileKey = true).map(KeyRejectedCopy::label))
        assertEquals(listOf("Sign In with Remote", "Not Now"), KeyRejectedCopy.buttons(isTv = true, canSignIn = true, profileKey = true).map(KeyRejectedCopy::label))
        assertEquals(
            "Dispatcharr no longer accepts the API key for Home. It may have been changed on the server. " +
                "Sign in with your Dispatcharr username and password to reconnect.",
            KeyRejectedCopy.body("Home", passwordToo = false, profileKey = true),
        )
        assertEquals(
            "Dispatcharr no longer accepts the sign-in for Home. It may have been changed on the server. " +
                "Sign in with your Dispatcharr username and password to reconnect.",
            KeyRejectedCopy.body("Home", passwordToo = true, profileKey = true),
        )
        // A non-default Child keeps its trimmed body and buttons.
        assertEquals(listOf("Not Now"), KeyRejectedCopy.buttons(isTv = false, canSignIn = false, isChild = true, profileKey = true).map(KeyRejectedCopy::label))
        assertEquals(listOf("Update from Phone", "Not Now"), KeyRejectedCopy.buttons(isTv = true, canSignIn = false, isChild = true, profileKey = true).map(KeyRejectedCopy::label))
        assertEquals(
            "Dispatcharr no longer accepts the API key for Home. It may have been changed on the server.",
            KeyRejectedCopy.body("Home", passwordToo = false, isChild = true, canSignIn = false, profileKey = true),
        )
        // The default profile's prompt is unchanged.
        assertEquals(listOf("Sign In", "Edit Playlist", "Not Now"), KeyRejectedCopy.buttons(isTv = false, canSignIn = true).map(KeyRejectedCopy::label))
        assertEquals(listOf("Update from Phone", "Sign In with Remote", "Not Now"), KeyRejectedCopy.buttons(isTv = true, canSignIn = true).map(KeyRejectedCopy::label))
        assertTrue(KeyRejectedCopy.body("Home", passwordToo = false).endsWith("or enter the current key in Edit Playlist."))
        // The no-key line is the same.
        assertEquals(KeyRejectedCopy.NO_API_KEY, KeyRejectedCopy.body("Home", passwordToo = false, noApiKey = true, profileKey = true))
    }

    @Test fun promptCopy() {
        assertEquals("API key no longer valid", KeyRejectedCopy.TITLE)
        assertEquals(KeyRejectedCopy.TITLE, API_KEY_REJECTED_TITLE)
        assertEquals(
            "Dispatcharr no longer accepts the API key for Home. It may have been changed on the server. " +
                "Sign in with your Dispatcharr username and password to reconnect, or enter the current key in Edit Playlist.",
            KeyRejectedCopy.body("Home", passwordToo = false),
        )
        assertEquals(
            "Dispatcharr no longer accepts the sign-in for Home. It may have been changed on the server. " +
                "Sign in with your Dispatcharr username and password to reconnect, or enter the current details in Edit Playlist.",
            KeyRejectedCopy.body("Home", passwordToo = true),
        )
        assertTrue(KeyRejectedCopy.body(" ", passwordToo = false).contains("for this playlist."))
    }

    @Test fun formCopy() {
        assertEquals("Sign in to Dispatcharr", KeyRejectedCopy.FORM_TITLE)
        assertEquals("Username", KeyRejectedCopy.USERNAME)
        assertEquals("Password", KeyRejectedCopy.PASSWORD)
        assertEquals("Sign In", KeyRejectedCopy.FORM_SIGN_IN)
        assertEquals("Cancel", KeyRejectedCopy.CANCEL)
        assertEquals("AerioTV saves your password with this playlist's sign-in so it can reconnect if the API key changes again.", KeyRejectedCopy.FOOTNOTE)
        assertEquals("Dispatcharr did not accept that username or password.", KeyRejectedCopy.WRONG_PASSWORD)
        assertEquals(
            "This account has no API key yet. Ask your Dispatcharr administrator to turn on API access for it.",
            KeyRejectedCopy.NO_API_KEY,
        )
        assertEquals("Could not reach your Dispatcharr server", KeyRejectedCopy.CONNECTION_ERROR)
        val all = listOf(
            KeyRejectedCopy.TITLE, KeyRejectedCopy.body("x", true), KeyRejectedCopy.body("x", false),
            KeyRejectedCopy.FORM_TITLE, KeyRejectedCopy.FOOTNOTE, KeyRejectedCopy.WRONG_PASSWORD, KeyRejectedCopy.NO_API_KEY,
        ) + Button.entries.map(KeyRejectedCopy::label)
        assertTrue("no em dashes", all.none { it.contains('\u2014') })
    }

    @Test fun errorLines() {
        assertEquals(KeyRejectedCopy.WRONG_PASSWORD, KeyRejectedCopy.errorLine(DispatcharrError.InvalidCredentials("401")))
        assertEquals(KeyRejectedCopy.NO_API_KEY, KeyRejectedCopy.errorLine(DispatcharrError.NoApiKey("none")))
        // The existing login throttle copy.
        assertEquals(
            rateLimitedMessage(30, RateLimitReason.SignInLockout),
            KeyRejectedCopy.errorLine(DispatcharrError.LoginThrottled(30, RateLimitReason.SignInLockout)),
        )
        assertEquals(
            rateLimitedMessage(45, RateLimitReason.ApiKeyLockout),
            KeyRejectedCopy.errorLine(DispatcharrError.RateLimited(45, RateLimitReason.ApiKeyLockout)),
        )
        assertEquals(KeyRejectedCopy.CONNECTION_ERROR, KeyRejectedCopy.errorLine(DispatcharrError.Transport("Login transport error: timeout")))
        assertEquals(KeyRejectedCopy.CONNECTION_ERROR, KeyRejectedCopy.errorLine(java.net.UnknownHostException("x")))
        assertEquals(KeyRejectedCopy.CONNECTION_ERROR, KeyRejectedCopy.errorLine(DispatcharrError.UnexpectedResponse("html")))
        // Still an UnexpectedResponse for every existing catch.
        assertTrue(DispatcharrError.NoApiKey("none") is DispatcharrError.UnexpectedResponse)
    }

    @Test fun formRules() {
        assertEquals("alex", KeyRejectedCopy.prefillUsername(null, " ", " alex ", "sam"))
        assertEquals("", KeyRejectedCopy.prefillUsername(null, ""))
        assertTrue(KeyRejectedCopy.canSubmit("alex", "pw", busy = false))
        assertFalse(KeyRejectedCopy.canSubmit("alex", "pw", busy = true))
        assertFalse(KeyRejectedCopy.canSubmit(" ", "pw", busy = false))
        assertFalse(KeyRejectedCopy.canSubmit("alex", "", busy = false))
        // The Set up gate: a Child profile or a locked app never opens the form.
        assertTrue(setupSendAllowed(locked = false, canSetUpTv = true))
        assertFalse(setupSendAllowed(locked = true, canSetUpTv = true))
        assertFalse(setupSendAllowed(locked = false, canSetUpTv = false))
    }

    @Test fun signInSavesKeyUsernameAndPasswordOnly() {
        // Key-only playlist: the read key, the username, and the password are all written.
        val first = FixLogin.signInToWrite(
            oldKey = "old-key", oldUser = null, oldPass = null,
            sent = SetupProtocol.Credential(apiKey = "users-me-key", username = "alex", password = "pw"),
        )!!
        assertEquals(Triple("users-me-key", "alex", "pw"), Triple(first.apiKey, first.username, first.password))
        assertTrue("new sign-in: the old session goes", first.accountChanged)
        // Same account and password again (the key changed on the server): only the key moves.
        val again = FixLogin.signInToWrite("k1", "alex", "pw", SetupProtocol.Credential(apiKey = "k2", username = "alex", password = "pw"))!!
        assertEquals(Triple("k2", "alex", "pw"), Triple(again.apiKey, again.username, again.password))
        assertFalse(again.accountChanged)
    }
}
