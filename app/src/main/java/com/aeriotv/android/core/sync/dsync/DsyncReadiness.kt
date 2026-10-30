package com.aeriotv.android.core.sync.dsync

/**
 * Readiness gate for keys whose rows are translated from local ids to the
 * server's integer ids (schema section 4). Field run 2026-10-30: the first
 * push after turning Dispatcharr Sync on ran before the VOD catalog was
 * built, so eight local progress rows could not be translated, an empty
 * list went up, and the key was marked clean, so nothing ever re-sent them.
 *
 * Rule: a push of a cache-dependent key waits while its id cache is not
 * built and local rows exist that cannot be translated. A deferred key stays
 * dirty and is re-pushed when the cache is ready. Once the cache is built, a
 * row that still cannot be translated is a real miss (not in this catalog)
 * and stays on this device only, as before.
 *
 * Field run 2026-10-30 08:56 to 09:02 (fresh install, 544 movie lanes): the
 * sweep was still walking six minutes later, so waiting for "sweep complete"
 * held progress and watchlist for many minutes. Now the waiting keys are
 * re-checked on catalog page writes (at most every 3 s) and go out as soon as
 * every row translates ([retryDuringSweep]). After the sweep, rows that still
 * do not translate stay local and the key stays dirty ([Decision.PushPartial]).
 *
 * Persistence: the dirty flag lives in the "aerio_dsync" SharedPreferences
 * (DispatcharrSyncManager.setDirty, written with commit()), not in memory, and
 * every session start (launch, reconnect, profile) schedules each dirty key, so
 * a deferred key that never pushed before backgrounding or process death goes
 * out on the next session start or the 15-minute periodic pull.
 */
object DsyncReadiness {
    enum class Cache { Channels, VodCatalog }

    enum class Decision {
        Push, Defer,
        /** Cache built, some rows still untranslated: push the rest, keep the key dirty. */
        PushPartial,
    }

    /** The id cache [key] translates through, or null when it needs none. */
    fun cacheFor(key: DsyncKey): Cache? = when (key) {
        DsyncKey.Favorites, DsyncKey.Recents, DsyncKey.Reminders, DsyncKey.Collections -> Cache.Channels
        DsyncKey.Progress, DsyncKey.Watchlist, DsyncKey.HiddenTitles -> Cache.VodCatalog
        else -> null
    }

    /**
     * Channel keys cannot translate anything without the channel map, so they
     * always wait for it. VOD keys wait only when a local row went untranslated
     * (rows already known by id, and series ids, need no catalog).
     */
    fun decide(key: DsyncKey, cacheReady: Boolean, unresolvedRows: Int): Decision = when (cacheFor(key)) {
        null -> Decision.Push
        Cache.Channels -> if (cacheReady) Decision.Push else Decision.Defer
        Cache.VodCatalog -> when {
            unresolvedRows <= 0 -> Decision.Push
            !cacheReady -> Decision.Defer
            else -> Decision.PushPartial
        }
    }

    /**
     * On a catalog page write mid-sweep: retry a deferred VOD key's push now,
     * because a local probe found every movie row translates.
     */
    fun retryDuringSweep(key: DsyncKey, cacheReady: Boolean, probeMisses: Int): Boolean =
        cacheFor(key) == Cache.VodCatalog && !cacheReady && probeMisses == 0

    /** Minimum gap between page-write re-checks of the waiting keys. */
    const val PAGE_RECHECK_MS = 3_000L

    /** Whether the key is dirty after this write attempt: a deferred key stays dirty. */
    fun dirtyAfter(decision: Decision, putOk: Boolean, dirtyBefore: Boolean): Boolean = when (decision) {
        Decision.Defer, Decision.PushPartial -> true
        Decision.Push -> if (putOk) false else dirtyBefore
    }
}
