package com.aeriotv.android.core.profiles

import android.util.Log

/**
 * A playlist's STORED secrets after its server address changed (security
 * fix 2026-11-12, Apple ca72783 StoredSecretRules).
 *
 * Found on this app's own phone log (21:55 and 22:06): after a playlist's
 * address moved to a DIFFERENT server, the same-server check found another
 * user there, and the identity repair, seeing the stored key answer as that
 * other user, signed in at the new address with the playlist's saved
 * username and password. The password went to a server just shown not to be
 * the user's. A silent re-login after a 401 there, the launch sign-in, Edit
 * Playlist's sign-in with the password the form showed, and Refresh Session
 * could send it the same way.
 *
 * A route change (the scheme, host, or port of the server URL or the local
 * URL) leaves the playlist's route UNPROVEN, profiles or not, persisted
 * across a relaunch (HostHoldBook.unproven), until the same-server check
 * proves it (the default account answers there with the user id and
 * username recorded before the change, at every address in use), or the
 * user signs in there with what they type. While it is unproven, nothing
 * stored and secret goes to an address not proven yet: no saved password
 * (identity repair, silent re-login, launch sign-in, the Edit Playlist
 * sign-in, Refresh Session), no sign-in sent from another device (Fix
 * Login), no Dispatcharr Sync session, and no other profile's sign-in (the
 * profile holds). The new address gets only what the user types, and the
 * playlist's own API key from the save the user made there. A check that
 * finds another account there holds that key, so the default profile shows
 * the sign-in form and nothing goes there with it again.
 */
object StoredSecrets {

    /** Every path that can send a stored secret, for the log and the tests. */
    enum class Path(val label: String) {
        IdentityRepair("identity repair"),
        SilentRelogin("silent re-login"),
        LaunchSignIn("launch sign-in"),
        EditPlaylistSignIn("Edit Playlist sign-in"),
        RefreshSession("Refresh Session"),
        FixLogin("Fix Login"),
        DispatcharrSync("Dispatcharr Sync"),
    }

    /** Edit Playlist and Refresh Session at a new address with no typed password (Apple copy). */
    const val TYPE_PASSWORD_AT_NEW_ADDRESS = "Enter the password to sign in at the new server address."

    /** Fix Login received for a playlist whose address is not proven (Apple copy). */
    const val FIX_LOGIN_NEW_ADDRESS = "This playlist's server address changed. Sign in on this device to use the new address."

    /** A stored secret refused at an unproven address; its message is the copy shown as it is. */
    class Refused(message: String) : IllegalStateException(message)

    /**
     * A stored secret may go to [target] only when the playlist's route is
     * proven ([proof] null), or [target] is a route in use that a check has
     * proven (the server URL kept when only the local URL changed).
     */
    fun mayUseStored(proof: RouteProof?, target: String?): Boolean {
        if (proof == null) return true
        val k = ProfileHostHold.routeKey(target)
        return k.isNotEmpty() && k in proof.current && k in proof.trusted
    }

    /** What a password field sends: what the user typed, else the saved one only where it may go, else nothing. */
    fun password(typed: String?, stored: String?, mayUseStored: Boolean): String? =
        typed?.takeIf { it.isNotEmpty() } ?: stored?.takeIf { mayUseStored && it.isNotEmpty() }

    /** A same-server check failure that holds the playlist's own key: another account answers there. */
    fun holdsPlaylistKey(reason: String, routeUnproven: Boolean): Boolean = routeUnproven && reason == "different-user"

    /**
     * An Edit Playlist save to an address not proven the same server, with
     * no typed secret: the playlist's own key answered there as [answer].
     * It is the playlist's account when it is the one [recorded] before the
     * change (same user id and username); with none recorded, when it has
     * the playlist's [username] (none: nothing to compare, the same-server
     * check decides later).
     */
    fun answersAsOwnAccount(recorded: ProfileHostHold.Identity?, username: String?, answer: ProfileHostHold.Identity): Boolean =
        if (recorded != null && recorded.known) {
            ProfileHostHold.decide(recorded, ProfileHostHold.Answer.Me(answer)) == ProfileHostHold.Verdict.SameServer
        } else {
            username.isNullOrBlank() || answer.username.trim().equals(username.trim(), ignoreCase = true)
        }

    /** Dispatcharr Sync starts no session for an unproven route. */
    fun maySync(routeUnproven: Boolean): Boolean = !routeUnproven

    // ---- runtime (ProfileManager installs the book at construction) ----

    @Volatile internal var book: HostHoldBook? = null

    /** Test hook for the log line (pure JVM tests have no android.util.Log). */
    @Volatile internal var logSink: ((String) -> Unit)? = null

    /** [playlistId]'s route proof now; null when proven. No book yet: nothing is known, so nothing is held. */
    fun proof(playlistId: String): RouteProof? = book?.routeProof(playlistId)

    fun isRouteUnproven(playlistId: String): Boolean = proof(playlistId) != null

    /** Whether [playlistId]'s stored secrets may go to [target] now. */
    fun mayUse(playlistId: String, target: String?): Boolean = mayUseStored(proof(playlistId), target)

    /** One line per refusal: the path and the playlist, never a secret or an address. */
    fun noteRefused(playlistId: String, path: Path) {
        val line = "[PROFILE-HOST] ${path.label} refused for playlist=${playlistId.take(8)}: " +
            "its address is not proven the same server; nothing stored is sent"
        logSink?.invoke(line) ?: runCatching { Log.i("AerioProfiles", line) }
    }

    /** [mayUse], logging a refusal for [path]. */
    fun check(playlistId: String, target: String?, path: Path): Boolean {
        if (mayUse(playlistId, target)) return true
        noteRefused(playlistId, path)
        return false
    }
}
