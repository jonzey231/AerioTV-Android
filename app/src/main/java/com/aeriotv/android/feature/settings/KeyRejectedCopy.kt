package com.aeriotv.android.feature.settings

import com.aeriotv.android.core.network.DispatcharrError

/**
 * Every string of the "API key no longer valid" prompt and its sign-in form
 * (shared spec with Apple, 2026-11-11: same wording on every platform), and
 * the pure rules behind them, so the unit tests check exactly what is shown.
 */
object KeyRejectedCopy {
    /** Prompt title (unchanged; also the live player's stop notice). */
    const val TITLE = com.aeriotv.android.core.network.KEY_REJECTED_TITLE

    /**
     * Prompt body, every device. [passwordToo] = the playlist's stored
     * password was refused as well (its silent re-login failed). For a Child
     * ([isChild], shared spec 2026-11-11) the clauses naming what it cannot
     * do are trimmed: no Edit Playlist clause, and with no sign-in offered
     * ([canSignIn] false) the body ends after "It may have been changed on
     * the server." A non-default profile's own refused key ([profileKey],
     * APP7-1, 2026-11-12) drops the Edit Playlist clause too: Edit Playlist
     * writes only the playlist's own sign-in. No new words.
     */
    fun body(playlistName: String, passwordToo: Boolean, noApiKey: Boolean = false, isChild: Boolean = false, canSignIn: Boolean = true, profileKey: Boolean = false): String {
        // The re-login signed in, but the account has no API key on the server.
        if (noApiKey) return NO_API_KEY
        val name = playlistName.ifBlank { "this playlist" }
        val lead = if (passwordToo) {
            "Dispatcharr no longer accepts the sign-in for $name. It may have been changed on the server."
        } else {
            "Dispatcharr no longer accepts the API key for $name. It may have been changed on the server."
        }
        val signIn = "Sign in with your Dispatcharr username and password to reconnect"
        return when {
            isChild && !canSignIn -> lead
            isChild || profileKey -> "$lead $signIn."
            passwordToo -> "$lead $signIn, or enter the current details in Edit Playlist."
            else -> "$lead $signIn, or enter the current key in Edit Playlist."
        }
    }

    // ---- Prompt buttons ----

    const val SIGN_IN = "Sign In"
    const val SIGN_IN_WITH_REMOTE = "Sign In with Remote"
    const val EDIT_PLAYLIST = "Edit Playlist"
    const val NOT_NOW = "Not Now"
    /** TVs: Fix Login (docs/DeviceSetupHandoff.md). */
    const val UPDATE_FROM_PHONE = "Update from Phone"

    enum class Button { UpdateFromPhone, SignIn, SignInWithRemote, EditPlaylist, NotNow }

    /**
     * The prompt's buttons, in order; the first is the primary (default
     * focus). Phones and tablets: Sign In, Edit Playlist, Not Now. TVs:
     * Update from Phone, Sign In with Remote, Not Now (key entry stays in
     * Settings > Edit Playlist). [canSignIn] false (a Child profile, or the
     * launch PIN screen is up; the Set up gate) drops the button that opens
     * the sign-in form. [isChild] (a Child profile is active) drops Edit
     * Playlist, which a Child cannot open (Apple parity, 2026-11-11); TVs
     * never show it. [profileKey] (the refused key is an adult non-default
     * profile's own, APP7-1) drops Edit Playlist and Update from Phone, which
     * write only the playlist's own sign-in: its own Sign In and Not Now
     * stay. A non-default Child keeps its buttons.
     */
    fun buttons(isTv: Boolean, canSignIn: Boolean, isChild: Boolean = false, profileKey: Boolean = false): List<Button> {
        val ownOnly = profileKey && !isChild
        return if (isTv) listOfNotNull(Button.UpdateFromPhone.takeUnless { ownOnly }, Button.SignInWithRemote.takeIf { canSignIn }, Button.NotNow)
        else listOfNotNull(Button.SignIn.takeIf { canSignIn }, Button.EditPlaylist.takeUnless { isChild || ownOnly }, Button.NotNow)
    }

    fun label(b: Button): String = when (b) {
        Button.UpdateFromPhone -> UPDATE_FROM_PHONE
        Button.SignIn -> SIGN_IN
        Button.SignInWithRemote -> SIGN_IN_WITH_REMOTE
        Button.EditPlaylist -> EDIT_PLAYLIST
        Button.NotNow -> NOT_NOW
    }

    // ---- Sign-in form ----

    const val FORM_TITLE = "Sign in to Dispatcharr"
    const val USERNAME = "Username"
    const val PASSWORD = "Password"
    const val FORM_SIGN_IN = SIGN_IN
    const val CANCEL = "Cancel"
    const val FOOTNOTE = "AerioTV keeps your password on this device so it can reconnect if the key changes again."

    /**
     * Above the form's fields when a picked profile's sign-in is held because
     * its playlist moved to another server address (shared spec 2026-11-11,
     * same wording on every platform).
     */
    fun hostChanged(playlistName: String): String =
        "The server address for ${playlistName.ifBlank { "this playlist" }} changed. Sign in again to use this profile."

    const val WRONG_PASSWORD = "Dispatcharr did not accept that username or password."
    const val NO_API_KEY =
        "This account has no API key yet. Ask your Dispatcharr administrator to turn on API access for it."
    /** The existing connection error line (Sync's, Apple SyncPrompts.DISPATCHARR_UNREACHABLE). */
    const val CONNECTION_ERROR = DISPATCHARR_UNREACHABLE

    /** The form's inline line for a failed sign-in. */
    fun errorLine(t: Throwable): String = when (t) {
        is DispatcharrError.InvalidCredentials -> WRONG_PASSWORD
        is DispatcharrError.NoApiKey -> NO_API_KEY
        // The existing login throttle and 429 copy (rateLimitedMessage).
        is DispatcharrError.LoginThrottled -> t.message ?: CONNECTION_ERROR
        is DispatcharrError.RateLimited -> t.message ?: CONNECTION_ERROR
        else -> CONNECTION_ERROR
    }

    /** The username the form starts with: the first one known for the playlist, else empty. */
    fun prefillUsername(vararg known: String?): String =
        known.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    /** Sign In does something only with both fields filled and nothing in flight (TV: never disabled, see feedback_tv_disabled_button_loses_focus). */
    fun canSubmit(username: String, password: String, busy: Boolean): Boolean =
        !busy && username.isNotBlank() && password.isNotEmpty()
}
