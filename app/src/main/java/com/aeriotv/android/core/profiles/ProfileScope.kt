package com.aeriotv.android.core.profiles

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The scope key for per-profile local state (Profiles phase 1, 2026-10-16).
 *
 * Before profiles, every piece of user state was keyed by nothing (favorites,
 * recents, hidden groups, collections, view prefs) or by playlistId (watch
 * progress, reminders, recordings, group tokens). The legacy keys and rows ARE
 * the default profile: the token for a default profile is "" and every lookup
 * with an empty token reads exactly what it read before, so migrating to
 * profiles moves no data. A non-default active profile has the token
 * "<profileId>" (a UUID): Room rows carry it in `profileScope`, preference keys
 * get the suffix "@prof:<profileId>", and channel snapshot rows are written
 * under "<playlistId>@<profileId>".
 *
 * Kept as a process-wide object (like DispatcharrLoginGate) so the Room DAO
 * default methods, entity default arguments, and AppPreferences can read it
 * without DI plumbing through ~300 call sites.
 */
object ProfileScope {

    /** Per-user columns of the active non-default profile, overlaid on the
     *  playlist row by EncryptingPlaylistDao. Null fields keep the row value. */
    data class Overlay(
        val playlistId: String,
        val profileId: String,
        val apiKey: String?,
        val username: String?,
        val password: String?,
        val userLevel: Int,
        val dvrAccess: String,
        val catchupEnabled: Boolean,
        val vodMoviesEnabled: Boolean,
        val vodSeriesEnabled: Boolean,
        val accountProfileIds: String,
        val isStaff: Boolean,
        val isSuperuser: Boolean,
        val customProperties: String,
        val capabilitiesFetchedAt: Long,
        val capabilitiesSchema: Int,
        val capabilitiesStale: Boolean,
    )

    private val _token = MutableStateFlow("")

    /** "" = default profile (legacy keys), otherwise the active profile id. */
    val token: StateFlow<String> = _token.asStateFlow()

    @Volatile private var overlay: Overlay? = null

    /** Set when a coroutine runs under [defaultOnly] (Drive sync). */
    private val forcedDefault = ThreadLocal<Boolean?>()

    /** The token the CURRENT caller sees: "" under [defaultOnly]. */
    fun current(): String = if (forcedDefault.get() == true) "" else _token.value

    fun isForcedDefault(): Boolean = forcedDefault.get() == true

    /**
     * Profiles whose stored sign-in is held after a server address change
     * ([ProfileHostHold]), kept by ProfileManager from before the first
     * request. A held profile's overlay is never laid over a row and never
     * installed; setting this drops one that is installed.
     */
    @Volatile var held: Set<String> = emptySet()
        set(value) {
            field = value
            if (overlay?.profileId?.let { it in value && it !in sealed } == true) activate(null, Why.HELD)
        }

    /**
     * Held profiles that stay active: a Child (item 8). Leaving a Child needs
     * a PIN, so a server change never switches it to the default profile.
     * Its overlay stays installed with no sign-in: nothing it holds is sent,
     * and the playlist's own sign-in is never used in its place. Set before
     * [held] so the setter keeps it.
     */
    @Volatile var sealed: Set<String> = emptySet()

    /** The routes ("scheme://host:port") of a sealed Child's playlist: Dispatcharr requests there are refused until the hold lifts. */
    @Volatile var sealedRoutes: Set<String> = emptySet()

    /** True when a Dispatcharr request to [url] belongs to a held Child's playlist and must not be sent. */
    fun isSealedRoute(url: String): Boolean = sealedRoutes.isNotEmpty() && ProfileHostHold.routeKey(url) in sealedRoutes

    private fun sealedHeld(o: Overlay): Boolean = o.profileId in held && o.profileId in sealed

    /** The overlay for [playlistId], or null (default profile, sync, or a held profile). A held Child's comes with no sign-in. */
    fun overlayFor(playlistId: String): Overlay? {
        if (forcedDefault.get() == true) return null
        val o = overlay?.takeIf { it.playlistId == playlistId } ?: return null
        if (o.profileId !in held) return o
        return if (o.profileId in sealed) o.copy(apiKey = null, password = null) else null
    }

    fun activeOverlay(): Overlay? = overlay

    /**
     * Whose sign-in a row of [playlistId] read in this context carries: the
     * installed profile's id when its overlay applies here, else "" (the
     * playlist's own). Rows cached per identity with it never serve another
     * one (2026-11-12, security).
     */
    fun readScope(playlistId: String): String = overlayFor(playlistId)?.profileId.orEmpty()

    /**
     * The profile [rowUsername] (a row of [playlistId] read in this context)
     * signs in as: the installed profile's id when its overlay applies here
     * and is that user, else null (the playlist's own sign-in). A silent
     * re-login is kept per identity with it, so one account's fresh key is
     * never shared with another (2026-11-12, security).
     */
    fun signInIdentity(playlistId: String, rowUsername: String?): String? {
        val o = overlayFor(playlistId) ?: return null
        return o.profileId.takeIf { o.username.orEmpty().trim().equals(rowUsername.orEmpty().trim(), ignoreCase = true) }
    }

    /**
     * The API key requests for [playlistId] may carry outside the row reads
     * (image requests): the installed profile's (none while it is sealed or
     * has none), else [rowKey], the playlist's own. Never the playlist's own
     * while another profile is installed, even for a row read under
     * [defaultOnly] (2026-11-11, security).
     */
    fun activeKeyFor(playlistId: String, rowKey: String?): String? {
        val o = overlay?.takeIf { it.playlistId == playlistId } ?: return rowKey
        return if (o.profileId in sealed) null else o.apiKey
    }

    /**
     * Why the active profile changed. Every change goes through [activate],
     * and each one reaches [changes] with its reason, so Live TV lands on the
     * new profile's own group whichever path made the change (Nothing Phone
     * 2026-11-13: a server address hold moved jwilliams4362 to the default
     * profile and the guide stayed on jwilliams4362's Favorites).
     */
    object Why {
        /** A profile switch; its reload lands the guide. */
        const val SWITCH = "switch"
        /** A playlist activation; the playlist's load lands the guide. */
        const val PLAYLIST = "playlist"
        /** The launch restore; the launch lands the guide. */
        const val LAUNCH = "launch"
        /** The active profile's sign-in was held after a server address change. */
        const val HELD = "held, moved to the default profile"
        const val UNSPECIFIED = "profile change"
    }

    /** The active profile changed to [token] ("" = default) because of [reason]. */
    data class Change(val token: String, val reason: String)

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every change of the active profile, with its reason ([Why]). */
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    /** Install (or clear, with null) the active non-default profile. A held profile is never installed, except a held Child ([sealed]). */
    fun activate(newOverlay: Overlay?, reason: String = Why.UNSPECIFIED) {
        val next = newOverlay?.takeUnless { it.profileId in held && it.profileId !in sealed }
        overlay = next
        val token = next?.profileId.orEmpty()
        val changed = _token.value != token
        _token.value = token
        if (changed) _changes.tryEmit(Change(token, reason))
    }

    /** Non-default profile usernames per playlist, kept by ProfileManager.
     *  Lets the DAO reject a stale write of a profile's identity onto the
     *  playlist row after a switch back to the default profile. */
    @Volatile var profileUsernames: Map<String, Set<String>> = emptyMap()

    fun isNonDefaultProfileUser(playlistId: String, username: String?): Boolean =
        !username.isNullOrBlank() && profileUsernames[playlistId]?.contains(username) == true

    /** Dispatcharr user id of the active profile on a playlist (set by
     *  ProfileManager), for the channel snapshot stamp. 0 = unknown. */
    @Volatile var activeUserIdLookup: ((String) -> Int)? = null

    /** Persists a diverted per-user write (set by ProfileManager). */
    @Volatile var onOverlayChanged: ((Overlay) -> Unit)? = null

    fun updateOverlay(transform: (Overlay) -> Overlay) {
        val o = overlay ?: return
        // A held Child reads with no sign-in: a write of that row must not
        // replace the stored key and password it still has.
        val next = transform(o).let { if (sealedHeld(o)) it.copy(apiKey = o.apiKey, password = o.password) else it }
        if (next == o) return
        overlay = next
        onOverlayChanged?.invoke(next)
    }

    /** Preference key name for [base] in the current scope. */
    fun keyName(base: String, token: String = current()): String =
        if (token.isEmpty()) base else "$base@prof:$token"

    /** Channel snapshot / cache key for [playlistId] in the current scope. */
    fun cacheKey(playlistId: String): String {
        val o = overlayFor(playlistId) ?: return playlistId
        return "$playlistId@${o.profileId}"
    }

    /**
     * Coroutine context that pins the caller to the default profile: scope ""
     * and raw playlist rows. Drive sync runs under it so the snapshot keeps
     * carrying only the default profile. PHASE 2 HOOK: per-profile sync
     * replaces this with an explicit profile id per snapshot.
     */
    fun defaultOnly(): CoroutineContext = DefaultOnlyElement()

    private class DefaultOnlyElement :
        AbstractCoroutineContextElement(Key), ThreadContextElement<Boolean?> {
        companion object Key : CoroutineContext.Key<DefaultOnlyElement>
        override fun updateThreadContext(context: CoroutineContext): Boolean? {
            val old = forcedDefault.get()
            forcedDefault.set(true)
            return old
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Boolean?) {
            forcedDefault.set(oldState)
        }
    }
}
