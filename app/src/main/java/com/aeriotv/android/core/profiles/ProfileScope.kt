package com.aeriotv.android.core.profiles

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.flow.MutableStateFlow
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
            if (overlay?.profileId?.let { it in value } == true) activate(null)
        }

    /** The overlay for [playlistId], or null (default profile, sync, or a held profile). */
    fun overlayFor(playlistId: String): Overlay? {
        if (forcedDefault.get() == true) return null
        return overlay?.takeIf { it.playlistId == playlistId && it.profileId !in held }
    }

    fun activeOverlay(): Overlay? = overlay

    /** Install (or clear, with null) the active non-default profile. A held profile is never installed. */
    fun activate(newOverlay: Overlay?) {
        val next = newOverlay?.takeUnless { it.profileId in held }
        overlay = next
        _token.value = next?.profileId.orEmpty()
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
        val next = transform(o)
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
