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

    /** What a switch to a target does about its sign-in. */
    enum class Step {
        /** Sign in as usual (or paint from cache and verify behind). */
        SignIn,
        /** A USER-chosen switch to a target whose key is held: the sign-in form, nothing sent. */
        Form,
        /**
         * An app-made switch (a removal, a synced tombstone, a switch back)
         * to a target whose key is held: land on it with no sign-in and
         * nothing sent with the held key; its existing prompt handles it.
         * Never a form, never a failure (APP8-1, Apple parity).
         */
        LandHeld,
    }

    /** [chosenByUser]: the switcher or the launch picker; false = the app switched on its own. */
    fun step(target: DispatcharrProfile, playlistKey: String?, isRejected: (String) -> Boolean, chosenByUser: Boolean): Step = when {
        formFor(target, playlistKey, isRejected) == Form.None -> Step.SignIn
        chosenByUser -> Step.Form
        else -> Step.LandHeld
    }

    /**
     * An app-made switch never opens a form: an adult target that is held or
     * has no sign-in gives way to the default profile. A Child target is kept
     * (a Child never escapes to another profile this way); the switch then
     * does not run when its sign-in cannot be used.
     */
    fun appMadeTarget(target: DispatcharrProfile, defaultId: String, signInUnusable: Boolean): String =
        if (!target.isDefault && signInUnusable && !ProfileHostHold.staysActiveWhenHeld(target.effectiveRole())) defaultId else target.id

    /** The profile's form passes only for that profile's own user, compared without case. */
    fun profilePasses(target: DispatcharrProfile, signedInUsername: String): Boolean =
        ProfileHostHold.isOwnUser(target.username, signedInUsername)
}
