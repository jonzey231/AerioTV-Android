package com.aeriotv.android.core.network

import android.util.Log
import com.aeriotv.android.core.data.db.dao.TmdbArtDao
import com.aeriotv.android.core.data.db.entity.TmdbArtEntity
import com.aeriotv.android.core.preferences.AppPreferences
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The persistent TMDB art cache (Apple parity: TMDBArtCache,
 * VODService.swift:2017). Logan's rule 2026-09-04: whenever a TMDB key is set
 * and Program Posters TMDB is on, TMDB art comes FIRST and the provider's is
 * the fallback; with no key the provider's art is all there is.
 *
 * Shape:
 *  - PATHS are cached, never pixels. The bytes are Coil's business, and its
 *    disk cache is what keeps a relaunch from re-downloading them.
 *  - One in-memory map, read once from Room on first use, so every poster card
 *    resolves its art with a synchronous map read.
 *  - A background pass ([enrich]) resolves whatever is not cached yet at about
 *    12 requests a second, one pass per library kind at a time, and a new call
 *    replaces a running pass (the library republished mid-sweep).
 *  - [version] bumps at most every two seconds as entries land so the grids can
 *    re-read with a single observer per tab instead of one per card; the same
 *    tick writes the dirty rows to Room.
 */
@Singleton
class TmdbArtCache @Inject constructor(
    private val dao: TmdbArtDao,
    private val tmdbService: TMDBService,
    private val appPreferences: AppPreferences,
) {
    private val entries = ConcurrentHashMap<String, TmdbArtEntry>()
    private val dirty = ConcurrentHashMap<String, TmdbArtEntry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadLock = Mutex()
    private val saveLock = Any()
    private val enrichJobs = ConcurrentHashMap<String, Job>()

    /** Session-only synopsis text; never persisted (Apple parity 2026-10-27). */
    private val overviews = object : java.util.LinkedHashMap<String, String>(OVERVIEW_LRU, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > OVERVIEW_LRU
    }
    @Volatile private var lastWriteAt = 0L

    @Volatile private var loaded = false
    private var saveJob: Job? = null

    private val _version = MutableStateFlow(0)

    /** Bumped (throttled) as entries land so poster cards re-read their art. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Read the whole table into memory once per process. */
    suspend fun loadIfNeeded() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            val rows = runCatching {
                // Cap and strip on load (Apple TestFlight OOM 2026-10-27: an
                // unbounded art cache, 53778 titles on the Streamer).
                val total = dao.count()
                if (total > MAX_ENTRIES) {
                    val removed = dao.pruneTo(MAX_ENTRIES)
                    Log.i(TAG, "[TMDB-ART] pruned $removed entries (now ${total - removed})")
                }
                runCatching { dao.clearOverviews() }
                dao.newest(MAX_ENTRIES)
            }.getOrElse {
                Log.w(TAG, "[TMDB-ART] load failed: ${it.message}")
                emptyList()
            }
            rows.forEach { entries[it.key] = TmdbArtEntry(it.tmdbId, it.poster, it.backdrop, null, it.at) }
            loaded = true
            if (rows.isNotEmpty()) {
                Log.i(TAG, "[TMDB-ART] loaded ${rows.size} cached titles")
                _version.value = _version.value + 1
            }
        }
    }

    /** Cached entry for a library key, or null before the load or on a miss. */
    fun entry(key: String): TmdbArtEntry? = entries[key]

    fun posterUrl(key: String, size: String = "w500"): String? =
        entries[key]?.poster?.takeIf { it.isNotEmpty() }?.let { tmdbService.imageUrlFor(it, size) }

    fun backdropUrl(key: String, size: String = "w1280"): String? =
        entries[key]?.backdrop?.takeIf { it.isNotEmpty() }?.let { tmdbService.imageUrlFor(it, size) }

    /**
     * Synopsis from this session's LRU (500 titles), or null on a miss; the
     * caller fetches it on demand. Overview text is never persisted.
     */
    fun overview(key: String): String? = synchronized(overviews) { overviews[key] }

    fun tmdbId(key: String): String? = entries[key]?.tmdbId?.takeIf { it.isNotEmpty() }

    /**
     * Fold an entry resolved somewhere else (a hero backdrop fetched on
     * demand) into the cache so that title never hits TMDB again. Blank
     * incoming fields keep whatever the cache already had, so a detail-screen
     * lookup with no backdrop cannot erase a poster the background pass found.
     */
    fun merge(key: String, tmdbId: String, poster: String, backdrop: String, overview: String?) {
        val old = entries[key]
        store(
            key,
            TmdbArtEntry(
                tmdbId = tmdbId.ifBlank { old?.tmdbId ?: "" },
                poster = poster.ifBlank { old?.poster ?: "" },
                backdrop = backdrop.ifBlank { old?.backdrop ?: "" },
                overview = overview?.takeIf { it.isNotBlank() },
                at = System.currentTimeMillis(),
            ),
        )
    }

    private fun store(key: String, entry: TmdbArtEntry) {
        entry.overview?.takeIf { it.isNotBlank() }?.let { text ->
            synchronized(overviews) { overviews[key] = text }
        }
        val slim = if (entry.overview == null) entry else entry.copy(overview = null)
        entries[key] = slim
        dirty[key] = slim
        scheduleSave()
    }

    private fun enrichRunning(): Boolean = enrichJobs.values.any { it.isActive }

    /** Keep newest [MAX_ENTRIES] in memory, confirmed misses dropped first. */
    private fun pruneMemory() {
        val size = entries.size
        if (size <= MAX_ENTRIES) return
        val drop = entries.entries
            .sortedWith(compareBy<Map.Entry<String, TmdbArtEntry>>({ it.value.poster.isNotEmpty() }, { it.value.at }))
            .take(size - MAX_ENTRIES)
        drop.forEach { entries.remove(it.key); dirty.remove(it.key) }
        Log.i(TAG, "[TMDB-ART] pruned ${drop.size} entries (now ${entries.size})")
    }

    private fun scheduleSave() {
        synchronized(saveLock) {
            if (saveJob?.isActive == true) return
            saveJob = scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                // Cards re-read on the short tick; the table write is
                // throttled to once per 30 s while a pass runs.
                _version.value = _version.value + 1
                val sinceWrite = System.currentTimeMillis() - lastWriteAt
                if (enrichRunning() && sinceWrite < SAVE_THROTTLE_MS) {
                    delay(SAVE_THROTTLE_MS - sinceWrite)
                }
                writeDirty()
                synchronized(saveLock) { saveJob = null }
                if (dirty.isNotEmpty()) scheduleSave()
            }
        }
    }

    /** Off the main thread (scope is IO); a failure is logged, never thrown. */
    private suspend fun writeDirty() {
        try {
            pruneMemory()
            val rows = dirty.entries.map { (k, e) ->
                TmdbArtEntity(k, e.tmdbId, e.poster, e.backdrop, null, e.at)
            }
            rows.forEach { dirty.remove(it.key) }
            lastWriteAt = System.currentTimeMillis()
            if (rows.isEmpty()) return
            dao.upsertAll(rows)
            val total = dao.count()
            if (total > MAX_ENTRIES) {
                val removed = dao.pruneTo(MAX_ENTRIES)
                Log.i(TAG, "[TMDB-ART] pruned $removed entries (now ${total - removed})")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "[TMDB-ART] save failed: ${t.message}")
        }
    }

    /**
     * Resolve every title not cached yet (or whose confirmed miss is older
     * than 30 days), [priority] first. Gated on the Program Posters TMDB
     * opt-in plus a saved key; with neither the provider's art stands and
     * nothing is requested. One pass per kind: a new call cancels the running
     * one, because the only reason to call again is a republished library.
     *
     * Items are (art key, title to search). A 40k-title map is cheap and runs
     * off the main thread here.
     */
    fun enrich(
        items: List<Pair<String, String>>,
        isMovie: Boolean,
        priority: List<Pair<String, String>> = emptyList(),
    ) {
        val kind = if (isMovie) "movie" else "series"
        enrichJobs.remove(kind)?.cancel()
        enrichJobs[kind] = scope.launch {
            if (!appPreferences.programPostersTmdbEnabled.first()) return@launch
            val apiKey = appPreferences.tmdbApiKey.first()
            if (apiKey.isBlank()) return@launch
            // The 50k-row cache load and the lookups are launch work: they
            // wait for a PIN pad / picker and stage 4 (Streamer 2026-10-27
            // 14:37:59: "loaded 53778 cached titles" under the pad).
            com.aeriotv.android.core.app.LaunchWorkHold.awaitVod("TMDB art cache load and art pass ($kind)")
            loadIfNeeded()
            val cutoff = System.currentTimeMillis() - MISS_TTL_MS
            val ordered = priority + items
            val todo = withContext(Dispatchers.Default) {
                val seen = HashSet<String>(ordered.size)
                ordered.filter { (key, _) ->
                    if (!seen.add(key)) {
                        false
                    } else {
                        val cached = entries[key]
                        cached == null || (cached.poster.isEmpty() && cached.at < cutoff)
                    }
                }
            }
            if (todo.isEmpty()) return@launch
            Log.i(TAG, "[TMDB-ART] enrich $kind: ${todo.size} of ${ordered.size} titles to resolve")
            var resolved = 0
            for ((key, title) in todo) {
                if (!isActive) return@launch
                com.aeriotv.android.core.app.LaunchWorkHold.checkpointVod("TMDB art pass ($kind)")
                val entry = tmdbService.lookupArt(title, isMovie, apiKey)
                if (entry != null) {
                    store(key, entry)
                    resolved++
                } else {
                    // Transport failure or 429: back off and keep going.
                    delay(BACKOFF_MS)
                }
                delay(PACE_MS)
            }
            Log.i(TAG, "[TMDB-ART] enrich $kind: done, $resolved resolved")
            finalSave()
        }
    }

    /**
     * [enrich] over a library too large to hand over as one list (GH #109:
     * the catalog lives in Room and can hold 100k+ titles). [priority] runs
     * first; then [nextPage] is called with the previous page's cursor (0 to
     * start) and returns the next cursor plus (art key, title) pairs, or null
     * when the library is exhausted. Each page is filtered against the cache
     * and resolved before the next is read, so only one page is ever held.
     */
    fun enrichPaged(
        isMovie: Boolean,
        priority: List<Pair<String, String>>,
        nextPage: suspend (cursor: Long) -> Pair<Long, List<Pair<String, String>>>?,
    ) {
        val kind = if (isMovie) "movie" else "series"
        enrichJobs.remove(kind)?.cancel()
        enrichJobs[kind] = scope.launch {
            if (!appPreferences.programPostersTmdbEnabled.first()) return@launch
            val apiKey = appPreferences.tmdbApiKey.first()
            if (apiKey.isBlank()) return@launch
            // The 50k-row cache load and the lookups are launch work: they
            // wait for a PIN pad / picker and stage 4 (Streamer 2026-10-27
            // 14:37:59: "loaded 53778 cached titles" under the pad).
            com.aeriotv.android.core.app.LaunchWorkHold.awaitVod("TMDB art cache load and art pass ($kind)")
            loadIfNeeded()
            val cutoff = System.currentTimeMillis() - MISS_TTL_MS
            var resolved = 0
            var cursor = 0L
            var page: List<Pair<String, String>>? = priority
            while (page != null) {
                val todo = page.filter { (key, _) ->
                    val cached = entries[key]
                    cached == null || (cached.poster.isEmpty() && cached.at < cutoff)
                }.distinctBy { it.first }
                for ((key, title) in todo) {
                    if (!isActive) return@launch
                    // A duplicate title in a later page resolved meanwhile.
                    if (entries[key]?.let { it.poster.isNotEmpty() || it.at >= cutoff } == true) continue
                    com.aeriotv.android.core.app.LaunchWorkHold.checkpointVod("TMDB art pass ($kind)")
                    val entry = tmdbService.lookupArt(title, isMovie, apiKey)
                    if (entry != null) {
                        store(key, entry)
                        resolved++
                    } else {
                        delay(BACKOFF_MS)
                    }
                    delay(PACE_MS)
                }
                val next = nextPage(cursor) ?: break
                cursor = next.first
                page = next.second
            }
            Log.i(TAG, "[TMDB-ART] enrich $kind (paged): done, $resolved resolved")
            finalSave()
        }
    }

    /** Final debounced save once a pass ends (the throttle no longer applies). */
    private fun finalSave() {
        scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            if (dirty.isNotEmpty() && !enrichRunning()) writeDirty()
        }
    }

    private companion object {
        const val MAX_ENTRIES = 60_000
        const val OVERVIEW_LRU = 500
        const val SAVE_THROTTLE_MS = 30_000L
        const val TAG = "TmdbArtCache"
        const val SAVE_DEBOUNCE_MS = 2_000L
        const val BACKOFF_MS = 2_000L
        const val PACE_MS = 80L
        const val MISS_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
