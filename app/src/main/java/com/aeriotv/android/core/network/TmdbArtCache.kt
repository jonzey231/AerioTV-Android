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
            val rows = runCatching { dao.getAll() }.getOrElse {
                Log.w(TAG, "[TMDB-ART] load failed: ${it.message}")
                emptyList()
            }
            rows.forEach { entries[it.key] = TmdbArtEntry(it.tmdbId, it.poster, it.backdrop, it.overview, it.at) }
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

    fun overview(key: String): String? = entries[key]?.overview?.takeIf { it.isNotBlank() }

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
                overview = overview?.takeIf { it.isNotBlank() } ?: old?.overview,
                at = System.currentTimeMillis(),
            ),
        )
    }

    private fun store(key: String, entry: TmdbArtEntry) {
        entries[key] = entry
        dirty[key] = entry
        scheduleSave()
    }

    private fun scheduleSave() {
        synchronized(saveLock) {
            if (saveJob?.isActive == true) return
            saveJob = scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                val rows = dirty.entries.map { (k, e) ->
                    TmdbArtEntity(k, e.tmdbId, e.poster, e.backdrop, e.overview, e.at)
                }
                rows.forEach { dirty.remove(it.key) }
                if (rows.isEmpty()) return@launch
                runCatching { dao.upsertAll(rows) }
                    .onFailure { Log.w(TAG, "[TMDB-ART] save failed: ${it.message}") }
                _version.value = _version.value + 1
            }
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
        }
    }

    private companion object {
        const val TAG = "TmdbArtCache"
        const val SAVE_DEBOUNCE_MS = 2_000L
        const val BACKOFF_MS = 2_000L
        const val PACE_MS = 80L
        const val MISS_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
