package com.aeriotv.android.core.profiles

/**
 * Choosing a profile whose key is held as rejected (2026-11-12, parity with
 * the Roku spec, section 8.4 step 4): after the PIN the switch already
 * needs, and before anything else, the "Sign in to Dispatcharr" form opens
 * and nothing is sent with the held key. A pass replaces that profile's
 * sign-in, clears only its own hold, and the switch goes on; Cancel stays on
 * the current profile with nothing changed. Pure rules, for the tests.
 */
object RejectedKeySwitch {
    enum class Form {
        /** The key is not held: the switch runs as usual. */
        None,
        /** A non-default profile: its own form, for its own username only. */
        Profile,
        /** The default profile: the playlist's own sign-in, for the playlist's known user (item 11). */
        PlaylistOwn,
    }

    /**
     * The form choosing [target] opens: its key (a non-default profile's
     * own; the default profile's is the playlist's, [playlistKey]) is held as
     * rejected ([isRejected]).
     */
    fun formFor(target: DispatcharrProfile, playlistKey: String?, isRejected: (String) -> Boolean): Form {
        val key = (if (target.isDefault) playlistKey else target.apiKey)?.trim()?.takeIf { it.isNotEmpty() } ?: return Form.None
        if (!isRejected(key)) return Form.None
        return if (target.isDefault) Form.PlaylistOwn else Form.Profile
    }

    /** The profile's form passes only for that profile's own user, compared without case. */
    fun profilePasses(target: DispatcharrProfile, signedInUsername: String): Boolean =
        ProfileHostHold.isOwnUser(target.username, signedInUsername)
}
