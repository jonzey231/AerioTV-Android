package com.aeriotv.android.core.profiles

/**
 * The profile a channel refresh started under (2026-10-27).
 *
 * A refresh that begins as profile X and lands after a switch to Y used to
 * resolve the snapshot key at WRITE time, so X's list was cached under Y's key
 * and published while Y was on screen. Capture the scope token and the cache
 * key when the fetch starts; every write and publish checks [isCurrent] and
 * uses [cacheKey], never the live scope.
 */
class ScopeBinding(val token: String, val cacheKey: String) {

    /** True while the profile this refresh started under is still active. */
    fun isCurrent(): Boolean = ProfileScope.current() == token

    /** Throws [ScopeMovedException] when the profile changed. */
    fun ensureCurrent(what: String) {
        if (!isCurrent()) throw ScopeMovedException(what)
    }

    /** Server-wide row fields (channel count, last refreshed) belong to the
     *  default profile only; another profile's refresh never writes them. */
    val isDefaultProfile: Boolean get() = token.isEmpty()

    companion object {
        fun capture(playlistId: String): ScopeBinding =
            ScopeBinding(ProfileScope.current(), ProfileScope.cacheKey(playlistId))
    }
}

/** A refresh whose profile is no longer active; its result was dropped. */
class ScopeMovedException(what: String) :
    IllegalStateException("profile changed during $what; result dropped")
