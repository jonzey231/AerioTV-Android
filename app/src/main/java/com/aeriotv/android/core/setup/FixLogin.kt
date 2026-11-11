package com.aeriotv.android.core.setup

/**
 * Fix Login: a phone updates a TV's sign-in when the server rejected the
 * TV's API key (docs/DeviceSetupHandoff.md, "Fix Login"). Same receiver,
 * sender, crypto, and envelope as Set up another device; the TV advertises
 * `mode=fixlogin` and `pl=<playlist id>`, and the payload carries
 * `mode: "fixlogin"` with one playlist and its credential. Pure JVM so the
 * unit tests cover the advert, the card rule, and the receiver's apply rule.
 */
object FixLogin {
    /** Dock card and Settings banner button for a Fix Login advert. */
    const val BUTTON = "Fix Login"
    /** Sender sheet title while fixing a TV's sign-in. */
    const val SHEET_TITLE = "Fix Login"
    /** Receive screen header on the TV. */
    const val RECEIVE_TITLE = "Update from your phone"
    /** Receive screen line on the TV. */
    const val RECEIVE_LINE = "On your phone, open AerioTV and tap Fix Login."
    /** TV progress line while the sign-in is written. */
    const val APPLYING_LINE = "Signing in"
    /** TV confirmation before the receive screen closes. */
    const val DONE_LINE = "Signed in. Loading channels."
    /** TV error (under "Setup did not finish") when the sign-in is not for a playlist here. */
    const val NOT_HERE =
        "The sign-in that was sent is not for a playlist on this device."

    /** Rejected-key prompt buttons on TVs, in this order. */
    const val PROMPT_FROM_PHONE = "Update from Phone"
    const val PROMPT_WITH_REMOTE = "Update with Remote"
    const val PROMPT_NOT_NOW = "Not Now"

    /** Phone line after a successful send. */
    fun sentLine(deviceName: String): String = "Sent. ${deviceName.ifBlank { "The device" }} is signing in."

    /**
     * An advert's `mode`: null for a normal setup receiver (absent or
     * blank, which is every build before this key), [SetupProtocol.MODE_FIX_LOGIN],
     * or any other value as read (a mode this build does not know; such an
     * advert is not offered).
     */
    fun normalizeMode(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** An advert's `pl`: trimmed and lowercased, null when absent or blank. */
    fun normalizePlaylistId(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    // ---- Phone: is the card offered, and with which playlist ----

    /** One playlist on the phone, as the card rule sees it (never a key). */
    data class Local(
        val id: String,
        val isDispatcharr: Boolean,
        /** The username it signs in as, when known here. */
        val account: String?,
        /** It has an API key or a password to send. */
        val hasCredential: Boolean,
        /** This phone's own key for it is held as rejected. */
        val keyHeld: Boolean,
    )

    /**
     * The phone playlist a Fix Login advert for [pl] can be fixed from, or
     * null (no card). A Dispatcharr playlist whose id equals [pl] (ids
     * compare without case), else, per the one-playlist identity rule, the
     * one Dispatcharr playlist here that signs in as [learnedAccount] (the
     * account sync reported for [pl]; two candidates are ambiguous and match
     * nothing). The match must have a sign-in to send and its own key must
     * not be held as rejected here.
     */
    fun matchPlaylist(pl: String?, locals: List<Local>, learnedAccount: String?): Local? {
        val want = normalizePlaylistId(pl) ?: return null
        val dispatcharr = locals.filter { it.isDispatcharr }
        val match = dispatcharr.firstOrNull { it.id.trim().equals(want, ignoreCase = true) }
            ?: learnedAccount?.trim()?.takeIf { it.isNotEmpty() }?.let { acct ->
                dispatcharr.filter { it.account?.trim()?.equals(acct, ignoreCase = true) == true }.singleOrNull()
            }
            ?: return null
        if (!match.hasCredential || match.keyHeld) return null
        return match
    }

    /**
     * Whether the Set up card and banner offer [target], and as what.
     * [fixLoginPid] is [matchPlaylist]'s answer for a Fix Login advert;
     * [canSetUp] is the sender gate (Admin or default profile) for a
     * playlist id.
     */
    sealed interface Offer {
        data object None : Offer
        /** Normal setup, from the active playlist. */
        data object Setup : Offer
        /** Fix Login, sending [playlistId]'s sign-in. */
        data class Fix(val playlistId: String) : Offer
    }

    fun offer(
        mode: String?,
        activePlaylistId: String?,
        fixLoginPid: String?,
        canSetUp: (String) -> Boolean,
    ): Offer = when (mode) {
        null -> if (activePlaylistId != null && canSetUp(activePlaylistId)) Offer.Setup else Offer.None
        SetupProtocol.MODE_FIX_LOGIN -> if (fixLoginPid != null && canSetUp(fixLoginPid)) Offer.Fix(fixLoginPid) else Offer.None
        else -> Offer.None
    }

    /** The button on the card and banner for an advert's mode. */
    fun buttonLabel(mode: String?): String = if (mode == SetupProtocol.MODE_FIX_LOGIN) BUTTON else "Set up"

    // ---- TV: which playlist a received payload updates, and with what ----

    /**
     * The local playlist id a Fix Login payload updates, or null (the
     * "not for a playlist on this device" error). The payload's
     * `playlist.id` must name the playlist the screen was opened for
     * ([screenPid], null outside Fix Login) and a playlist that exists here
     * ([localIds]); ids compare without case. A payload with no id never
     * matches.
     */
    fun receiverPlaylistId(screenPid: String?, payloadId: String?, localIds: List<String>): String? {
        val id = payloadId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (screenPid != null && !screenPid.trim().equals(id, ignoreCase = true)) return null
        return localIds.firstOrNull { it.trim().equals(id, ignoreCase = true) }
    }

    /** The sign-in fields to write; the rest of the row never changes. */
    data class SignIn(
        val apiKey: String?,
        val username: String?,
        val password: String?,
        /** The username or password changed: the cached session belongs to the old sign-in. */
        val accountChanged: Boolean,
    )

    /**
     * Only the sign-in: the API key, and the username and password when the
     * payload carries them. A field the payload leaves out (or sends blank)
     * keeps the stored value. Null when the payload carries no sign-in at all.
     */
    fun signInToWrite(
        oldKey: String?,
        oldUser: String?,
        oldPass: String?,
        sent: SetupProtocol.Credential?,
    ): SignIn? {
        val key = sent?.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        val user = sent?.username?.trim()?.takeIf { it.isNotEmpty() }
        val pass = sent?.password?.takeIf { it.isNotEmpty() }
        if (key == null && user == null && pass == null) return null
        return SignIn(
            apiKey = key ?: oldKey,
            username = user ?: oldUser,
            password = pass ?: oldPass,
            accountChanged = (user != null && user != oldUser?.trim()) || (pass != null && pass != oldPass),
        )
    }

    /**
     * After the sign-in is written: the rejected-key hold for [playlistId]
     * and its prompt are lifted, [newKey] counts as good, and requests or
     * streams still built with [oldKey] go out with [newKey].
     */
    fun liftHold(playlistId: String, oldKey: String?, newKey: String?) {
        val gate = com.aeriotv.android.core.network.DispatcharrRequestGate
        gate.clearPlaylist(playlistId)
        newKey?.let { gate.clearKey(it) }
        if (oldKey != null && newKey != null && oldKey != newKey) gate.noteKeyReplaced(oldKey, newKey)
    }

    /**
     * True when a received payload is applied as a Fix Login: the screen was
     * opened in Fix Login mode (whatever the sender sent, only the sign-in is
     * taken), or the payload says `mode: "fixlogin"`.
     */
    fun appliesAsFixLogin(screenPid: String?, payloadMode: String?): Boolean =
        screenPid != null || normalizeMode(payloadMode) == SetupProtocol.MODE_FIX_LOGIN
}
