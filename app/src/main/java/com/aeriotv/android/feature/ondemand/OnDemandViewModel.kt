package com.aeriotv.android.feature.ondemand

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.VodLearnedStream
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.debug.VodResetBus
import com.aeriotv.android.core.network.DispatcharrAuthBroker
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.DispatcharrVODEpisode
import com.aeriotv.android.core.network.DispatcharrVODLogo
import com.aeriotv.android.core.network.DispatcharrVODMovie
import com.aeriotv.android.core.network.DispatcharrVODProviderInfo
import com.aeriotv.android.core.network.DispatcharrVODProviderMedia
import com.aeriotv.android.core.network.DispatcharrVODProviderRelation
import com.aeriotv.android.core.network.DispatcharrVODSeries
import com.aeriotv.android.core.network.TMDBService
import com.aeriotv.android.core.network.TmdbCredits
import com.aeriotv.android.core.network.TmdbDetails
import com.aeriotv.android.core.network.TmdbKnownForItem
import com.aeriotv.android.core.network.TmdbPersonBio
import com.aeriotv.android.core.network.XtreamCodesApi
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.preferences.VodLearnedStreamStore
import com.aeriotv.android.core.preferences.VodLibrarySnapshotStore
import kotlinx.coroutines.flow.first
import com.aeriotv.android.core.preferences.VodVersionItemType
import com.aeriotv.android.core.preferences.VodVersionSelectionStore
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aeriotv.android.feature.movies.toMediaItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * On Demand tab state. Phase 10a is Movies first-page only; Phase 10b adds
 * Series + the detail / episode picker; Phase 10c wires WatchProgress + the
 * "Continue Watching" CTA.
 *
 * Pagination via Dispatcharr's `next` cursor is wired but not consumed by the
 * UI yet — the first 100 movies render immediately, full library walk lands
 * with the "Load more" affordance.
 */
@HiltViewModel
class OnDemandViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val dispatcharrClient: DispatcharrClient,
    private val dispatcharrAuth: DispatcharrAuthBroker,
    private val xtreamApi: XtreamCodesApi,
    private val appPreferences: AppPreferences,
    private val tmdbService: TMDBService,
    private val vodResetBus: VodResetBus,
    private val learnedStreamStore: VodLearnedStreamStore,
    private val versionSelectionStore: VodVersionSelectionStore,
    private val snapshotStore: VodLibrarySnapshotStore,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val movies: List<DispatcharrVODMovie> = emptyList(),
        // Titles opened from Continue Watching / Watchlist that the library
        // walk never loaded (row cap, disabled group, provider gone), fetched
        // on demand so the detail screen opens instead of "not found".
        val resolvedMovies: Map<String, DispatcharrVODMovie> = emptyMap(),
        val resolvedSeries: Map<Int, DispatcharrVODSeries> = emptyMap(),
        val resolvingKeys: Set<String> = emptySet(),
        val totalCount: Int = 0,
        val searchQuery: String = "",
        // Server-side search results (Dispatcharr `?search=`). `visible` renders
        // these whenever the query is non-blank, so search reaches the WHOLE
        // library, not just the pages walked into `movies` so far. Empty when
        // not searching.
        val searchResults: List<DispatcharrVODMovie> = emptyList(),
        val isSearching: Boolean = false,
        // Cast & crew search (iOS MoviesView.searchPeople): the TMDB person
        // the query resolved to and their films found in the loaded library.
        // The grid merges these after the server results; the name feeds the
        // "Includes titles with <name>" line under the field.
        val personMatchName: String? = null,
        val personMatches: List<DispatcharrVODMovie> = emptyList(),
        val seriesPersonMatchName: String? = null,
        val seriesPersonMatches: List<DispatcharrVODSeries> = emptyList(),
        // Provider filter on search results (iOS providerNames /
        // selectedProviderID): Dispatcharr Direct Connect M3U account id ->
        // name, minus the locked "custom" account and disabled ones. Empty for
        // other sources. A pick re-runs the server search with
        // `&m3u_account=<id>` (DRF's m3u_relations__m3u_account__id filter).
        val providerNames: Map<Int, String> = emptyMap(),
        val selectedProviderId: Int? = null,
        val seriesSelectedProviderId: Int? = null,
        // Lazy-pagination cursor: the `next` URL after the last page appended to
        // `movies`. loadMoreMovies() consumes it as the grid nears its end. Null
        // once the library is fully walked.
        val moviesNextCursor: String? = null,
        val isLoadingMore: Boolean = false,
        val unsupportedSource: Boolean = false,
        // XC only: the cheap init probe found VOD/series categories but the
        // expensive per-category enumeration is deferred until the On Demand tab
        // is opened. Keeps the tab visible without doing the heavy work up front
        // (which used to hammer the device while the guide was still loading).
        val hasDeferredXtreamContent: Boolean = false,
        // Series state — separate from movies so each sub-tab's search /
        // loading flow is independent. Both share the same playlist context.
        val isLoadingSeries: Boolean = false,
        val seriesError: String? = null,
        val series: List<DispatcharrVODSeries> = emptyList(),
        val seriesTotalCount: Int = 0,
        val seriesSearchQuery: String = "",
        val seriesSearchResults: List<DispatcharrVODSeries> = emptyList(),
        val isSearchingSeries: Boolean = false,
        val seriesNextCursor: String? = null,
        val isLoadingMoreSeries: Boolean = false,
        // Episode cache: each series gets a lazy-loaded slot. The detail
        // screen reads its slot and shows a spinner while episodesLoadingFor
        // contains the seriesId.
        val episodesBySeries: Map<Int, List<DispatcharrVODEpisode>> = emptyMap(),
        val episodesLoadingFor: Set<Int> = emptySet(),
        val episodesErrorFor: Map<Int, String> = emptyMap(),
        // Provider-info cache. Movie + series detail pages call into this for
        // backdrop / cast / director / country / trailer enrichment. Keyed by
        // Dispatcharr's int `id` (NOT uuid) — matches the URL shape of
        // `/api/vod/{movies,series}/<id>/provider-info/`.
        val movieProviderInfo: Map<Int, DispatcharrVODProviderInfo> = emptyMap(),
        val seriesProviderInfo: Map<Int, DispatcharrVODProviderInfo> = emptyMap(),
        val movieProviderInfoLoading: Set<Int> = emptySet(),
        val seriesProviderInfoLoading: Set<Int> = emptySet(),
        // Server-authoritative VOD group names from /api/vod/categories/
        // (Dispatcharr only; enabled on at least one M3U account, name-sorted,
        // distinct). ManageGroupsSheet prefers these over the items-derived
        // list so groups whose items haven't paged in yet are still offered;
        // empty for sources without the endpoint (XC keeps deriving names
        // from the items themselves).
        val movieGroupNames: List<String> = emptyList(),
        val seriesGroupNames: List<String> = emptyList(),
        // VOD version switching (Dispatcharr Direct Connect only): per-item
        // provider copies from /api/vod/{movies,series}/<id>/providers/, keyed
        // by the Dispatcharr int id like the provider-info cache above. Empty
        // list = fetched, nothing to offer (the pill needs > 1 to render).
        val movieProviders: Map<Int, List<VodProviderOption>> = emptyMap(),
        val seriesProviders: Map<Int, List<VodProviderOption>> = emptyMap(),
        val movieProvidersLoading: Set<Int> = emptySet(),
        val seriesProvidersLoading: Set<Int> = emptySet(),
        // MEASURED stream properties per provider copy, keyed by RELATION id
        // (globally unique, so one map covers every movie). Fills in AFTER the
        // picker renders, because a copy the server has not inspected costs an
        // upstream round trip; missing entries just mean a shorter label.
        val movieProviderMedia: Map<Int, DispatcharrVODProviderMedia> = emptyMap(),
        // What THIS device measured while PLAYING each copy, same RELATION id
        // key. Fills the fields the upstream panel left blank (many publish a
        // bitrate and nothing else). Movies only: an episode option pins an
        // account rather than a file, so its id describes nothing playable and
        // the two id spaces would collide.
        val movieLearnedStreams: Map<Int, VodLearnedStream> = emptyMap(),
        // The user's pinned version per item. ABSENT = "Auto" (server priority
        // + failover), which is the default and never stored explicitly.
        // Persisted by VodVersionSelectionStore (playlist + type + item) and
        // restored as each item's provider list lands, so reopening a title
        // keeps the chosen copy. resetVodState() still drops the in-memory
        // maps on a playlist switch; the new source restores its own rows.
        val selectedMovieVersion: Map<Int, VodProviderOption> = emptyMap(),
        val selectedSeriesVersion: Map<Int, VodProviderOption> = emptyMap(),
    ) {
        // While searching, render the server-side results (full library). While
        // browsing, render the progressively-paginated list. The search request
        // itself lives in setSearchQuery(); these getters just pick the source.
        val visible: List<DispatcharrVODMovie> get() =
            if (searchQuery.isBlank()) movies else searchResults
        val visibleSeries: List<DispatcharrVODSeries> get() =
            if (seriesSearchQuery.isBlank()) series else seriesSearchResults
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Debounced server-side search jobs, cancelled + restarted per keystroke so
    // only the final query in a fast burst of typing hits the network.
    private var searchMoviesJob: kotlinx.coroutines.Job? = null
    private var searchSeriesJob: kotlinx.coroutines.Job? = null
    // TMDB person lookups behind the search fields, one per tab; cancelled
    // and restarted with the server search so a stale name never lands.
    private var personMoviesJob: kotlinx.coroutines.Job? = null
    private var personSeriesJob: kotlinx.coroutines.Job? = null
    // Provider names are fetched once per playlist, on the first search.
    private var providerNamesJob: kotlinx.coroutines.Job? = null

    // The raw relation rows behind state.movieProviders, keyed by movie id.
    // Kept out of UiState because the UI only ever consumes the derived
    // options; they live here so labels can be REBUILT in place as each
    // copy's measurements land. Main-thread confined (viewModelScope).
    private val movieProviderRelations = mutableMapOf<Int, List<DispatcharrVODProviderRelation>>()

    // Onn boxes 2026-09-02 (GH #84/#91): the full catalog walk used to start
    // within a second of launch and competed with the guide's first
    // composition and EPG load for the main thread and CPU on weak boxes.
    // Defer it a few seconds unless the On Demand tab is opened first.
    private var initialLoadsStarted = false
    private var deferredStart: kotlinx.coroutines.Job? = null

    private fun startInitialLoads() {
        if (initialLoadsStarted) return
        initialLoadsStarted = true
        deferredStart?.cancel()
        deferredStart = null
        viewModelScope.launch {
            // Open from the saved library at once; re-sweep the provider only
            // when the snapshot is older than the user's cadence (tester ask,
            // Freyguy1975 2026-09-07: a large XC library was re-pulled on every
            // open). Playlist switches, Refresh Everything and pull to refresh
            // still sweep unconditionally through refresh()/refreshSeries().
            val playlist = playlistRepository.activePlaylist()
            val snap = playlist?.let { snapshotStore.load(snapshotStore.identity(it)) }
            if (snap != null) {
                _state.update {
                    it.copy(
                        movies = snap.movies, totalCount = snap.movies.size,
                        series = snap.series, seriesTotalCount = snap.series.size,
                        movieGroupNames = snap.movieGroupNames.ifEmpty { it.movieGroupNames },
                        seriesGroupNames = snap.seriesGroupNames.ifEmpty { it.seriesGroupNames },
                        unsupportedSource = false, isLoading = false, isLoadingSeries = false,
                    )
                }
                val now = System.currentTimeMillis()
                val ageMs = now - snap.savedAtMs
                val limitMs = appPreferences.vodLibraryRefreshHours.first() * 3_600_000L
                Log.i(TAG, "[VOD-CACHE] restored ${snap.movies.size} movies, ${snap.series.size} series from ${ageMs / 60_000} min ago")
                // Gate each kind on ITS OWN completion stamp: the movie sweep
                // saves the file while the series sweep is still walking, so
                // a series sweep killed mid-walk (app update, force stop) left
                // a fresh file with a partial series list that the age check
                // alone would have served for the whole cadence window.
                moviesCompletedAtMs = snap.moviesCompletedAtMs
                seriesCompletedAtMs = snap.seriesCompletedAtMs
                val fresh = { at: Long -> limitMs > 0 && (now - at) in 0 until limitMs }
                val moviesFresh = fresh(snap.moviesCompletedAtMs)
                val seriesFresh = fresh(snap.seriesCompletedAtMs)
                if (moviesFresh && seriesFresh) {
                    Log.i(TAG, "[VOD-CACHE] both sweeps younger than ${limitMs / 3_600_000} h; skipping launch sweep")
                    restoredFromSnapshot = true
                    return@launch
                }
                Log.i(TAG, "[VOD-CACHE] launch sweep: movies=${if (moviesFresh) "fresh" else "stale"} series=${if (seriesFresh) "fresh" else "stale"}")
                if (!moviesFresh) refresh()
                if (!seriesFresh) refreshSeries()
                return@launch
            }
            refresh()
            refreshSeries()
        }
    }

    /** True when the launch served the saved library and skipped the sweep. */
    private var restoredFromSnapshot = false

    // Completion stamps carried into every save (see the launch gate).
    private var moviesCompletedAtMs = 0L
    private var seriesCompletedAtMs = 0L

    /**
     * Write the current lists as the playlist's library snapshot.
     * [completed] names the kind whose sweep just finished; its stamp is
     * refreshed, the other kind keeps whatever it had.
     */
    private fun persistSnapshot(completed: MediaSweep? = null) {
        val now = System.currentTimeMillis()
        when (completed) {
            MediaSweep.Movies -> moviesCompletedAtMs = now
            MediaSweep.Series -> seriesCompletedAtMs = now
            MediaSweep.Both -> { moviesCompletedAtMs = now; seriesCompletedAtMs = now }
            null -> Unit
        }
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist() ?: return@launch
            val st = _state.value
            if (st.movies.isEmpty() && st.series.isEmpty()) return@launch
            snapshotStore.save(
                VodLibrarySnapshotStore.Snapshot(
                    identity = snapshotStore.identity(playlist),
                    savedAtMs = now,
                    movies = st.movies, series = st.series,
                    movieGroupNames = st.movieGroupNames, seriesGroupNames = st.seriesGroupNames,
                    moviesCompletedAtMs = moviesCompletedAtMs, seriesCompletedAtMs = seriesCompletedAtMs,
                ),
            )
        }
    }

    /** On Demand tab shown: load now instead of waiting out the startup deferral. */
    fun ensureLoaded() = startInitialLoads()

    init {
        deferredStart = viewModelScope.launch {
            kotlinx.coroutines.delay(STARTUP_DEFER_MS)
            startInitialLoads()
        }
        // React to the active playlist changing (switch) or being deleted.
        // iOS Issue #25: when a playlist is removed, On Demand must drop the
        // old source's movies/series instead of leaving stale, unplayable
        // entries on screen. `drop(1)` skips the initial emission because the
        // `refresh()/refreshSeries()` above already kicked off the first load.
        viewModelScope.launch {
            playlistRepository.observeActiveId()
                .drop(1)
                .collect {
                    resetVodState()
                    initialLoadsStarted = true
                    refresh()
                    refreshSeries()
                }
        }
        // "Refresh Everything" (PlaylistViewModel.refreshEverything): the active
        // id is UNCHANGED, so the observeActiveId().drop(1) collector above never
        // fires. The VodResetBus bridges that gap and runs the same nuclear reset.
        viewModelScope.launch {
            vodResetBus.resets.collect {
                resetVodState()
                initialLoadsStarted = true
                refresh()
                refreshSeries()
            }
        }
    }

    /**
     * Drop all VOD state for the previous source: cancel any in-flight Xtream
     * probe / enumeration, clear the deferred-category bookkeeping, and reset
     * the UI state to empty so the next source starts clean. Mirrors iOS
     * `VODStore.clear()`.
     */
    private fun resetVodState() {
        // A running sweep belongs to the OLD playlist: cancel it so the
        // single-flight guard in refresh()/refreshSeries() lets the new one run.
        movieSweepJob?.cancel()
        seriesSweepJob?.cancel()
        movieSweepJob = null
        seriesSweepJob = null
        moviesCompletedAtMs = 0L
        seriesCompletedAtMs = 0L
        xtreamProbeJob?.cancel()
        xtreamItemsJob?.cancel()
        xtreamProbeJob = null
        xtreamItemsJob = null
        pendingMovieCats = emptyList()
        pendingSeriesCats = emptyList()
        movieCategoryNames = emptyMap()
        seriesCategoryNames = emptyMap()
        xtreamItemsLoaded = false
        xtreamPlaylist = null
        dispatcharrCategoriesFetch?.cancel()
        dispatcharrCategoriesFetch = null
        dispatcharrMovieCategoryNames = emptyMap()
        dispatcharrSeriesCategoryNames = emptyMap()
        dispatcharrMovieFallbackGroup = null
        dispatcharrSeriesFallbackGroup = null
        dispatcharrEnabledMovieCats = emptyList()
        dispatcharrEnabledSeriesCats = emptyList()
        movieProviderRelations.clear()
        personMoviesJob?.cancel()
        personSeriesJob?.cancel()
        personMoviesJob = null
        personSeriesJob = null
        providerNamesJob?.cancel()
        providerNamesJob = null
        _state.value = UiState()
    }

    /**
     * Movies search. On Dispatcharr this queries the server (`?search=`) so a
     * match anywhere in the full library is found even if its page was never
     * walked into `movies`. Non-Dispatcharr sources keep the instant client
     * filter (their whole library is already loaded). Debounced so a fast burst
     * of typing only fires the final query.
     */
    /**
     * Dispatcharr's `?search=` matches descriptions too ("law and order"
     * returns every synopsis containing "and"), and DRF returns the hits in
     * table order, which also changes between calls. Rank so title hits come
     * first (whole phrase, then every word), each tier alphabetical, so the
     * grid is stable across visits.
     */
    private fun <T> rankSearch(items: List<T>, q: String, title: (T) -> String): List<T> {
        // "&" and "and" are the same word to a person typing ("law and
        // order" must rank "Law & Order" first); articles carry no signal.
        fun norm(t: String) = t.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        val query = norm(q)
        val stop = setOf("and", "the", "of", "a", "an")
        val tokens = query.split(' ').filter { it.isNotBlank() && it !in stop }
        data class Score(val tier: Int, val matched: Int)
        fun score(t: String): Score {
            val n = norm(t)
            if (query.isNotEmpty() && n.contains(query)) return Score(0, tokens.size)
            val matched = tokens.count { n.contains(it) }
            return if (tokens.isNotEmpty() && matched == tokens.size) Score(1, matched) else Score(2, matched)
        }
        return items.sortedWith(
            compareBy<T>({ score(title(it)).tier }, { -score(title(it)).matched }, { title(it).lowercase() }),
        )
    }

    fun setSearchQuery(value: String) {
        _state.update { it.copy(searchQuery = value) }
        searchMoviesJob?.cancel()
        personMoviesJob?.cancel()
        val q = value.trim()
        if (q.isEmpty()) {
            // Clearing the field also drops the provider pick and the person
            // match (iOS clearSearch / onChange(searchText) with an empty query).
            _state.update {
                it.copy(
                    searchResults = emptyList(), isSearching = false,
                    selectedProviderId = null, personMatchName = null, personMatches = emptyList(),
                )
            }
            return
        }
        personMoviesJob = searchPeople(q, isMovie = true)
        searchMoviesJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            val playlist = playlistRepository.activePlaylist()
            val sourceType = playlist?.sourceType?.let { SourceType.entries.firstOrNull { st -> st.name == it } }
            val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                    sourceType == SourceType.DispatcharrUserPass
            if (playlist == null || !isDispatcharr || playlist.apiKey.isNullOrBlank()) {
                _state.update { st ->
                    st.copy(
                        searchResults = rankSearch(st.movies.filter { it.displayName.contains(q, ignoreCase = true) }, q) { it.displayName },
                        isSearching = false,
                    )
                }
                return@launch
            }
            _state.update { it.copy(isSearching = true) }
            ensureProviderNames(playlist)
            // Search results need the same group stamp as the browse list so
            // the Manage Groups filter applies identically to both.
            ensureDispatcharrCategories(playlist)
            val base = playlistRepository.effectiveBaseUrl(playlist).trimEnd('/')
            // Provider filter (iOS searchVODMoviesStream m3uAccountID): DRF's
            // `m3u_account` filter narrows the hits to one account's copies.
            val account = _state.value.selectedProviderId?.let { "&m3u_account=$it" } ?: ""
            val url = "$base/api/vod/movies/?search=" +
                    java.net.URLEncoder.encode(q, "UTF-8") + account + "&page_size=100"
            val page = runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getVODMoviesPage(url, key)
                }
            }.getOrNull()
            // Discard a stale response if the user kept typing past this query.
            if (_state.value.searchQuery.trim() != q) return@launch
            _state.update { it.copy(searchResults = rankSearch(page?.results?.map(::stampMovieGroup) ?: emptyList(), q) { m -> m.displayName }, isSearching = false) }
        }
    }

    /** Series search. Mirrors [setSearchQuery] against `/api/vod/series/`. */
    fun setSeriesSearchQuery(value: String) {
        _state.update { it.copy(seriesSearchQuery = value) }
        searchSeriesJob?.cancel()
        personSeriesJob?.cancel()
        val q = value.trim()
        if (q.isEmpty()) {
            _state.update {
                it.copy(
                    seriesSearchResults = emptyList(), isSearchingSeries = false,
                    seriesSelectedProviderId = null, seriesPersonMatchName = null, seriesPersonMatches = emptyList(),
                )
            }
            return
        }
        personSeriesJob = searchPeople(q, isMovie = false)
        searchSeriesJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            val playlist = playlistRepository.activePlaylist()
            val sourceType = playlist?.sourceType?.let { SourceType.entries.firstOrNull { st -> st.name == it } }
            val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                    sourceType == SourceType.DispatcharrUserPass
            if (playlist == null || !isDispatcharr || playlist.apiKey.isNullOrBlank()) {
                _state.update { st ->
                    st.copy(
                        seriesSearchResults = rankSearch(st.series.filter { it.displayName.contains(q, ignoreCase = true) }, q) { it.displayName },
                        isSearchingSeries = false,
                    )
                }
                return@launch
            }
            _state.update { it.copy(isSearchingSeries = true) }
            ensureProviderNames(playlist)
            // Same group stamp as the browse list; see setSearchQuery.
            ensureDispatcharrCategories(playlist)
            val base = playlistRepository.effectiveBaseUrl(playlist).trimEnd('/')
            val account = _state.value.seriesSelectedProviderId?.let { "&m3u_account=$it" } ?: ""
            val url = "$base/api/vod/series/?search=" +
                    java.net.URLEncoder.encode(q, "UTF-8") + account + "&page_size=100"
            val page = runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getVODSeriesPage(url, key)
                }
            }.getOrNull()
            if (_state.value.seriesSearchQuery.trim() != q) return@launch
            _state.update {
                it.copy(
                    seriesSearchResults = rankSearch(page?.results?.map(::stampSeriesGroup) ?: emptyList(), q) { s -> s.displayName },
                    isSearchingSeries = false,
                )
            }
        }
    }

    /**
     * Provider pill pick (iOS MoviesView.selectProvider): remember the account
     * and re-run the server search for the current query with the filter.
     * `null` = "All Providers". No-op when nothing changed.
     */
    fun selectProvider(providerId: Int?, isMovie: Boolean) {
        if (isMovie) {
            if (_state.value.selectedProviderId == providerId) return
            _state.update { it.copy(selectedProviderId = providerId) }
            setSearchQuery(_state.value.searchQuery)
        } else {
            if (_state.value.seriesSelectedProviderId == providerId) return
            _state.update { it.copy(seriesSelectedProviderId = providerId) }
            setSeriesSearchQuery(_state.value.seriesSearchQuery)
        }
    }

    /**
     * Dispatcharr M3U account id -> name for the provider pills (iOS
     * MoviesView.loadProviderNames), fetched once per playlist on the first
     * server search. Skips the locked built-in "custom" account and disabled
     * ones (Logan 2026-09-04: "custom" showed up as a provider). Only called
     * on the Dispatcharr path, so other sources keep an empty map.
     */
    private fun ensureProviderNames(playlist: PlaylistEntity) {
        if (providerNamesJob != null) return
        providerNamesJob = viewModelScope.launch {
            val base = playlistRepository.effectiveBaseUrl(playlist)
            val accounts = runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.listM3uAccounts(base, key)
                }
            }.getOrElse {
                warnUnlessCancelled("provider names (m3u accounts) failed", it)
                // Leave the job null so the next search retries.
                providerNamesJob = null
                return@launch
            }
            Log.w(TAG, "[VOD] provider names: ${accounts.size} accounts")
            val map = LinkedHashMap<Int, String>()
            for (a in accounts.sortedBy { it.id }) {
                if (a.locked == true || a.isActive == false) continue
                if (a.name?.trim()?.lowercase() == "custom") continue
                map[a.id] = a.name?.takeIf { it.isNotBlank() } ?: "Provider ${a.id}"
            }
            _state.update { it.copy(providerNames = map) }
        }
    }

    /**
     * Cast & crew search (iOS MoviesView.searchPeople): resolve the query to
     * a TMDB person after the same debounce as the server search, pull their
     * film (or show) credits, keep the ones in the loaded library. The server
     * search only covers title/description/genre and list rows carry no
     * cast. The top few people are tried and the one with the most library
     * hits wins. Same matcher as [relatedTitles]: tmdbId when the entity has
     * one, else the normalized title, over the loaded lists plus the resolved
     * maps. Requires a configured TMDB key and at least 3 characters; the
     * result clears otherwise.
     */
    private fun searchPeople(query: String, isMovie: Boolean): kotlinx.coroutines.Job = viewModelScope.launch {
        fun clear() = _state.update {
            if (isMovie) it.copy(personMatchName = null, personMatches = emptyList())
            else it.copy(seriesPersonMatchName = null, seriesPersonMatches = emptyList())
        }
        if (query.length < 3 || !isTmdbConfigured()) { clear(); return@launch }
        kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
        val key = appPreferences.tmdbApiKey.first()
        val people = tmdbService.searchPeople(query, key)
        if (people.isEmpty()) { clear(); return@launch }
        val snapshot = _state.value
        var bestName: String? = null
        var bestMovies: List<DispatcharrVODMovie> = emptyList()
        var bestSeries: List<DispatcharrVODSeries> = emptyList()
        withContext(Dispatchers.Default) {
            if (isMovie) {
                val library = snapshot.movies + snapshot.searchResults + snapshot.resolvedMovies.values
                val byTmdb = HashMap<String, DispatcharrVODMovie>()
                val byTitle = HashMap<String, DispatcharrVODMovie>()
                for (m in library) {
                    val t = m.tmdbId
                    if (!t.isNullOrBlank()) byTmdb.putIfAbsent(t, m)
                    else byTitle.putIfAbsent(normalizeVodTitle(m.displayName), m)
                }
                for (person in people) {
                    val credits = tmdbService.personCredits(person.id, isMovie = true, rawKey = key)
                    val seen = HashSet<String>()
                    val hits = ArrayList<DispatcharrVODMovie>()
                    for (c in credits) {
                        val hit = byTmdb[c.id] ?: byTitle[normalizeVodTitle(c.title)] ?: continue
                        if (seen.add(hit.uuid)) hits += hit
                    }
                    if (hits.size > bestMovies.size) { bestName = person.name; bestMovies = hits }
                }
            } else {
                val library = snapshot.series + snapshot.seriesSearchResults + snapshot.resolvedSeries.values
                val byTmdb = HashMap<String, DispatcharrVODSeries>()
                val byTitle = HashMap<String, DispatcharrVODSeries>()
                for (sr in library) {
                    val t = sr.tmdbId
                    if (!t.isNullOrBlank()) byTmdb.putIfAbsent(t, sr)
                    else byTitle.putIfAbsent(normalizeVodTitle(sr.displayName), sr)
                }
                for (person in people) {
                    val credits = tmdbService.personCredits(person.id, isMovie = false, rawKey = key)
                    val seen = HashSet<Int>()
                    val hits = ArrayList<DispatcharrVODSeries>()
                    for (c in credits) {
                        val hit = byTmdb[c.id] ?: byTitle[normalizeVodTitle(c.title)] ?: continue
                        if (seen.add(hit.id)) hits += hit
                    }
                    if (hits.size > bestSeries.size) { bestName = person.name; bestSeries = hits }
                }
            }
        }
        // Discard a stale answer if the user kept typing past this query.
        val current = if (isMovie) _state.value.searchQuery else _state.value.seriesSearchQuery
        if (current.trim() != query) return@launch
        Log.d(TAG, "People: '$query' -> ${bestName ?: "no match"} (${if (isMovie) bestMovies.size else bestSeries.size} in library)")
        _state.update {
            if (isMovie) it.copy(personMatchName = bestName, personMatches = bestMovies)
            else it.copy(seriesPersonMatchName = bestName, seriesPersonMatches = bestSeries)
        }
    }

    /**
     * Append the next browse page when the grid nears its end. No-op while a
     * load is already in flight, while searching (search isn't paginated here),
     * or once the cursor is exhausted. De-dups on uuid like the eager walk.
     */
    fun loadMoreMovies() {
        val st = _state.value
        if (st.isLoadingMore || st.searchQuery.isNotBlank()) return
        val cursor = st.moviesNextCursor ?: return
        // Flag in-flight synchronously BEFORE launching: the ~8 near-end items
        // that each trigger this within one frame then collapse to a single
        // fetch (main-thread serialization sees the flag set on the 2nd+ call).
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist()
            if (playlist == null) {
                _state.update { it.copy(isLoadingMore = false) }
                return@launch
            }
            // The cursor only ever exists for Dispatcharr sources; reuse this
            // cycle's category maps (no refetch) before stamping the page.
            ensureDispatcharrCategories(playlist)
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getVODMoviesPage(cursor, key)
                }
            }.fold(
                onSuccess = { p ->
                    _state.update { s ->
                        val merged = s.movies.toMutableList()
                        val seen = merged.mapTo(HashSet()) { it.uuid }
                        p.results.forEach { m -> if (m.uuid !in seen) { merged += stampMovieGroup(m); seen += m.uuid } }
                        s.copy(movies = merged, totalCount = p.count, moviesNextCursor = p.next, isLoadingMore = false)
                    }
                },
                onFailure = { t ->
                    warnUnlessCancelled("VOD movies load-more failed", t)
                    _state.update { it.copy(isLoadingMore = false) }
                },
            )
        }
    }

    /** Series counterpart of [loadMoreMovies]; de-dups on id. */
    fun loadMoreSeries() {
        val st = _state.value
        if (st.isLoadingMoreSeries || st.seriesSearchQuery.isNotBlank()) return
        val cursor = st.seriesNextCursor ?: return
        _state.update { it.copy(isLoadingMoreSeries = true) }
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist()
            if (playlist == null) {
                _state.update { it.copy(isLoadingMoreSeries = false) }
                return@launch
            }
            // See loadMoreMovies: stamp the appended page from the cached maps.
            ensureDispatcharrCategories(playlist)
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getVODSeriesPage(cursor, key)
                }
            }.fold(
                onSuccess = { p ->
                    _state.update { s ->
                        val merged = s.series.toMutableList()
                        val seen = merged.mapTo(HashSet()) { it.id }
                        p.results.forEach { x -> if (x.id !in seen) { merged += stampSeriesGroup(x); seen += x.id } }
                        s.copy(series = merged, seriesTotalCount = p.count, seriesNextCursor = p.next, isLoadingMoreSeries = false)
                    }
                },
                onFailure = { t ->
                    warnUnlessCancelled("VOD series load-more failed", t)
                    _state.update { it.copy(isLoadingMoreSeries = false) }
                },
            )
        }
    }

    // Single-flight sweeps. A second refresh() while the per-category walk is
    // still running (pull to refresh again, Settings refresh, a tab re-enter)
    // used to start a SECOND walk that published its own shorter `merged` list
    // over the first one's, so the All Movies count bounced between the two
    // runs (7862 / 3415 / 3502, Logan's Fold recording 2026-09-08). One walk
    // at a time; a request during a walk is a no-op since the walk already
    // produces the freshest list.
    enum class MediaSweep { Movies, Series, Both }

    private var movieSweepJob: kotlinx.coroutines.Job? = null
    private var seriesSweepJob: kotlinx.coroutines.Job? = null

    fun refresh() {
        if (movieSweepJob?.isActive == true) {
            Log.i(TAG, "[VOD] movie sweep already running; ignoring refresh()")
            return
        }
        movieSweepJob = viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist()
            val sourceType = playlist?.sourceType?.let { SourceType.entries.firstOrNull { st -> st.name == it } }
            val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                    sourceType == SourceType.DispatcharrUserPass
            // Per-playlist On Demand opt-out (iOS HomeView.swift:310): clear
            // movies and short-circuit when the user has toggled OFF "Fetch On
            // Demand from this playlist" at Add / Edit time. MainScaffold's
            // hasVodContent ALSO gates on vodEnabled so the tab disappears,
            // but we still belt-and-suspenders here in case something opens
            // the tab through another path (e.g. a deep link).
            if (playlist != null && (!playlist.vodEnabled || !playlist.dispatcharrVodMoviesEnabled)) {
                _state.update {
                    it.copy(
                        unsupportedSource = true,
                        movies = emptyList(),
                        totalCount = 0,
                        isLoading = false,
                        error = null,
                        hasDeferredXtreamContent = false,
                    )
                }
                return@launch
            }
            if (playlist != null && sourceType == SourceType.XtreamCodes) {
                ensureXtreamProbe(playlist)
                return@launch
            }
            if (playlist == null || !isDispatcharr || playlist.apiKey.isNullOrBlank()) {
                _state.update { it.copy(unsupportedSource = true, movies = emptyList(), isLoading = false, error = null) }
                return@launch
            }
            _state.update { it.copy(isLoading = true, error = null, unsupportedSource = false) }
            // New refresh cycle = new category snapshot (server-side group
            // edits become visible without an app restart). Series refresh,
            // pagination, and search all reuse this fetch's maps.
            ensureDispatcharrCategories(playlist, invalidate = true)
            val cats = dispatcharrEnabledMovieCats
            if (cats.isEmpty()) {
                // No category endpoint / nothing enabled: keep the legacy
                // unfiltered cursor walk as the fallback.
                loadDispatcharrMoviesUnfiltered(playlist)
                return@launch
            }
            // Per-category sweep. The movie LIST endpoint omits category_id on
            // this server, so the only way to learn a movie's real group (and
            // make the Manage Groups filter work) is to query each enabled
            // category endpoint and stamp the result directly. Mirrors iOS
            // StreamingAPIs.swift per-category VOD load. A failing category is
            // logged and skipped; one bad group never aborts the sweep.
            val totalCap = VOD_TOTAL_CAP
            // Walk every category to the end (Apple parity). The old "fair
            // share" of the row cap rounded down to a single page per category
            // on accounts with 150+ categories: 2,250 of 5,044 movies
            // (Logan 2026-09-08). The total cap alone bounds memory.
            val perCatCap = VOD_PER_CATEGORY_CAP
            val base = playlistRepository.effectiveBaseUrl(playlist)
            val merged = mutableListOf<DispatcharrVODMovie>()
            val seen = HashSet<String>()
            // First fill paints progressively so an empty tab shows rows at
            // once. A RE-sweep over a populated tab keeps the old list on
            // screen (with the refresh spinner) until the walk completes:
            // publishing the growing list emptied the grid to "No Movies"
            // and regrew it, and the header count ran up from 0 again.
            val progressive = _state.value.movies.isEmpty()
            // Categories that have >=1 movie for THIS account are the real
            // groups for this playlist; Manage Groups is published from here.
            // Presence is recorded from the category's OWN first-page response
            // (count/results), NOT from whether a row survived the merged-list
            // de-dup or made it under the row cap. Two regressions this guards:
            //   (1) row-cap: once `merged` hits totalCap we stop APPENDING rows
            //       and skip the cursor walk, but we still do the cheap one-page
            //       probe for every remaining enabled category so a real,
            //       content-bearing category past the cap is still offered (with
            //       many categories, 5000 rows is exhausted after ~50 of them).
            //   (2) overlap: Dispatcharr categories are many-to-many over items,
            //       so a category whose items were all added by an earlier
            //       category adds nothing to `seen`; presence keys off the
            //       category response being non-empty, not seen.add() succeeding.
            val groupsWithContent = LinkedHashSet<String>()
            var firstPainted = false
            for (catName in cats) {
                kotlinx.coroutines.delay(WALK_PACE_MS)
                val capped = merged.size >= totalCap
                var nextUrl: String? = null
                var fetchedForCat = 0
                // First page for this category. Doubles as the presence probe:
                // a single page_size=100 GET we already make per category, so
                // marking presence here adds no extra requests beyond running
                // it for the categories past the row cap too.
                val firstPage = runCatching {
                    dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                        dispatcharrClient.getVODMoviesByCategory(base, key, catName)
                    }
                }.onFailure { warnUnlessCancelled("VOD movies cat='$catName' failed; continuing", it) }.getOrNull()
                if (firstPage != null) {
                    // Presence is the category's own signal, independent of the
                    // row cap and the shared `seen` de-dup set.
                    if (firstPage.count > 0 || firstPage.results.isNotEmpty()) groupsWithContent += catName
                    // Stop appending rows once the cap is hit, but keep probing
                    // the remaining categories above for presence.
                    if (!capped) {
                        firstPage.results.forEach { m ->
                            if (seen.add(m.uuid)) { merged += m.copy(categoryName = catName); fetchedForCat++ }
                        }
                        nextUrl = firstPage.next
                        if (!progressive) {
                            // keep the old list; final publish below
                        } else if (!firstPainted) {
                            firstPainted = true
                            _state.update { it.copy(isLoading = false, movies = merged.toList(), totalCount = merged.size, moviesNextCursor = null, error = null) }
                        } else {
                            _state.update { it.copy(movies = merged.toList(), totalCount = merged.size, moviesNextCursor = null) }
                        }
                    }
                }
                // Walk this category's cursor up to its fair share (skipped once
                // capped: nextUrl stays null above).
                while (nextUrl != null && fetchedForCat < perCatCap && merged.size < totalCap) {
                    kotlinx.coroutines.delay(WALK_PACE_MS)
                    val captured = nextUrl
                    val p = runCatching {
                        dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                            dispatcharrClient.getVODMoviesPage(captured, key)
                        }
                    }.onFailure { warnUnlessCancelled("VOD movies cat='$catName' next-page failed; stopping cat", it) }.getOrNull()
                    if (p == null) break
                    p.results.forEach { m ->
                        if (seen.add(m.uuid)) { merged += m.copy(categoryName = catName); fetchedForCat++ }
                    }
                    nextUrl = p.next
                    if (progressive) _state.update { it.copy(movies = merged.toList(), totalCount = merged.size, moviesNextCursor = null) }
                }
            }
            // Ensure the spinner clears even if every category returned empty.
            // Publish ONLY names that carried content for this account so
            // Manage Groups never offers an empty category like "Apple TV".
            // Skip when the sweep produced nothing so a transient all-empty
            // refresh doesn't wipe a previously-good list.
            _state.update {
                it.copy(
                    isLoading = false,
                    movies = merged.toList(),
                    totalCount = if (merged.isEmpty()) it.totalCount else merged.size,
                    moviesNextCursor = null,
                    movieGroupNames = if (groupsWithContent.isEmpty()) it.movieGroupNames
                        else groupsWithContent.toList().sorted(),
                )
            }
            if (merged.isNotEmpty()) persistSnapshot(MediaSweep.Movies)
        }
    }

    /**
     * Legacy unfiltered movie load: first-page paint + a capped `next`-cursor
     * walk (Audit task #42). Used as the fallback when the category endpoint
     * returned nothing, so per-category fetch is impossible. Stamps via
     * stampMovieGroup (a no-op/Uncategorized when there are no categories).
     */
    private suspend fun loadDispatcharrMoviesUnfiltered(playlist: PlaylistEntity) {
        val base = playlistRepository.effectiveBaseUrl(playlist)
        runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getVODMoviesFirstPage(base, key)
            }
        }.fold(
            onSuccess = { page ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        movies = page.results.map(::stampMovieGroup),
                        totalCount = page.count,
                        moviesNextCursor = page.next,
                        error = null,
                    )
                }
                // Walk the `next` cursor in the background so the user gets the
                // full library appended progressively instead of just the first
                // 100. First-page paint already landed above so the grid is
                // interactive; subsequent pages append as they arrive. De-dup on
                // uuid in case two pages share a row.
                var nextUrl = page.next
                var pagesLoaded = 1
                while (nextUrl != null && pagesLoaded < MAX_EAGER_VOD_PAGES) {
                    val captured = nextUrl
                    val nextResult = runCatching {
                        dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                            dispatcharrClient.getVODMoviesPage(captured, key)
                        }
                    }
                    nextUrl = nextResult.getOrNull()?.next
                    pagesLoaded++
                    nextResult.getOrNull()?.let { p ->
                        _state.update { st ->
                            val merged = st.movies.toMutableList()
                            val seen = merged.mapTo(HashSet()) { it.uuid }
                            p.results.forEach { m ->
                                if (m.uuid !in seen) {
                                    merged += stampMovieGroup(m)
                                    seen += m.uuid
                                }
                            }
                            st.copy(movies = merged, totalCount = p.count, moviesNextCursor = p.next)
                        }
                    }
                    nextResult.exceptionOrNull()?.let { t ->
                        warnUnlessCancelled("VOD movies next-page fetch failed; stopping", t)
                        nextUrl = null
                    }
                }
            },
            onFailure = { t ->
                warnUnlessCancelled("getVODMovies failed", t)
                _state.update { it.copy(isLoading = false, error = t.message ?: t::class.simpleName) }
            },
        )
    }

    fun refreshSeries() {
        if (seriesSweepJob?.isActive == true) {
            Log.i(TAG, "[VOD] series sweep already running; ignoring refreshSeries()")
            return
        }
        seriesSweepJob = viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist()
            val sourceType = playlist?.sourceType?.let { SourceType.entries.firstOrNull { st -> st.name == it } }
            val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                    sourceType == SourceType.DispatcharrUserPass
            // Same opt-out gate as refresh() above for the series side; see
            // the longer comment there. Belt-and-suspenders with MainScaffold's
            // hasVodContent.
            if (playlist != null && (!playlist.vodEnabled || !playlist.dispatcharrVodSeriesEnabled)) {
                _state.update {
                    it.copy(
                        unsupportedSource = true,
                        series = emptyList(),
                        seriesTotalCount = 0,
                        isLoadingSeries = false,
                        seriesError = null,
                        hasDeferredXtreamContent = false,
                    )
                }
                return@launch
            }
            if (playlist != null && sourceType == SourceType.XtreamCodes) {
                ensureXtreamProbe(playlist)
                return@launch
            }
            if (playlist == null || !isDispatcharr || playlist.apiKey.isNullOrBlank()) {
                _state.update { it.copy(unsupportedSource = true, series = emptyList(), isLoadingSeries = false, seriesError = null) }
                return@launch
            }
            _state.update { it.copy(isLoadingSeries = true, seriesError = null) }
            // Piggybacks on refresh()'s category fetch when both run in the
            // same cycle (the usual case); only starts one if none exists.
            ensureDispatcharrCategories(playlist)
            val cats = dispatcharrEnabledSeriesCats
            if (cats.isEmpty()) {
                loadDispatcharrSeriesUnfiltered(playlist)
                return@launch
            }
            // Per-category sweep, mirror of refresh()'s movie path. Series dedup
            // by id (Int) to match the rest of the codebase (loadMoreSeries,
            // seriesById). A failing category is logged and skipped.
            val totalCap = VOD_TOTAL_CAP
            // Walk every category to the end (Apple parity). The old "fair
            // share" of the row cap rounded down to a single page per category
            // on accounts with 150+ categories: 2,250 of 5,044 movies
            // (Logan 2026-09-08). The total cap alone bounds memory.
            val perCatCap = VOD_PER_CATEGORY_CAP
            val base = playlistRepository.effectiveBaseUrl(playlist)
            val merged = mutableListOf<DispatcharrVODSeries>()
            val progressive = _state.value.series.isEmpty()
            val seen = HashSet<Int>()
            // Presence is recorded from each category's own first-page response,
            // independent of the row cap and the shared `seen` de-dup set. See
            // the longer note in refresh()'s movie sweep for the two regressions
            // this guards (row-cap reached before the loop, and fully-overlapping
            // many-to-many categories).
            val groupsWithContent = LinkedHashSet<String>()
            var firstPainted = false
            for (catName in cats) {
                kotlinx.coroutines.delay(WALK_PACE_MS)
                val capped = merged.size >= totalCap
                var nextUrl: String? = null
                var fetchedForCat = 0
                val firstPage = runCatching {
                    dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                        dispatcharrClient.getVODSeriesByCategory(base, key, catName)
                    }
                }.onFailure { warnUnlessCancelled("VOD series cat='$catName' failed; continuing", it) }.getOrNull()
                if (firstPage != null) {
                    if (firstPage.count > 0 || firstPage.results.isNotEmpty()) groupsWithContent += catName
                    if (!capped) {
                        firstPage.results.forEach { s ->
                            if (seen.add(s.id)) { merged += s.copy(categoryName = catName); fetchedForCat++ }
                        }
                        nextUrl = firstPage.next
                        if (!progressive) {
                            // keep the old list; final publish below
                        } else if (!firstPainted) {
                            firstPainted = true
                            _state.update { it.copy(isLoadingSeries = false, series = merged.toList(), seriesTotalCount = merged.size, seriesNextCursor = null, seriesError = null) }
                        } else {
                            _state.update { it.copy(series = merged.toList(), seriesTotalCount = merged.size, seriesNextCursor = null) }
                        }
                    }
                }
                while (nextUrl != null && fetchedForCat < perCatCap && merged.size < totalCap) {
                    kotlinx.coroutines.delay(WALK_PACE_MS)
                    val captured = nextUrl
                    val p = runCatching {
                        dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                            dispatcharrClient.getVODSeriesPage(captured, key)
                        }
                    }.onFailure { warnUnlessCancelled("VOD series cat='$catName' next-page failed; stopping cat", it) }.getOrNull()
                    if (p == null) break
                    p.results.forEach { s ->
                        if (seen.add(s.id)) { merged += s.copy(categoryName = catName); fetchedForCat++ }
                    }
                    nextUrl = p.next
                    if (progressive) _state.update { it.copy(series = merged.toList(), seriesTotalCount = merged.size, seriesNextCursor = null) }
                }
            }
            _state.update {
                it.copy(
                    isLoadingSeries = false,
                    series = merged.toList(),
                    seriesTotalCount = if (merged.isEmpty()) it.seriesTotalCount else merged.size,
                    seriesNextCursor = null,
                    seriesGroupNames = if (groupsWithContent.isEmpty()) it.seriesGroupNames
                        else groupsWithContent.toList().sorted(),
                )
            }
            if (merged.isNotEmpty()) persistSnapshot(MediaSweep.Series)
        }
    }

    /** Series counterpart of [loadDispatcharrMoviesUnfiltered]; dedup by id. */
    private suspend fun loadDispatcharrSeriesUnfiltered(playlist: PlaylistEntity) {
        val base = playlistRepository.effectiveBaseUrl(playlist)
        runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getVODSeriesFirstPage(base, key)
            }
        }.fold(
            onSuccess = { page ->
                _state.update {
                    it.copy(
                        isLoadingSeries = false,
                        series = page.results.map(::stampSeriesGroup),
                        seriesTotalCount = page.count,
                        seriesNextCursor = page.next,
                        seriesError = null,
                    )
                }
                var nextUrl = page.next
                var pagesLoaded = 1
                while (nextUrl != null && pagesLoaded < MAX_EAGER_VOD_PAGES) {
                    val captured = nextUrl
                    val nextResult = runCatching {
                        dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                            dispatcharrClient.getVODSeriesPage(captured, key)
                        }
                    }
                    nextUrl = nextResult.getOrNull()?.next
                    pagesLoaded++
                    nextResult.getOrNull()?.let { p ->
                        _state.update { st ->
                            val merged = st.series.toMutableList()
                            val seen = merged.mapTo(HashSet()) { it.id }
                            p.results.forEach { s ->
                                if (s.id !in seen) {
                                    merged += stampSeriesGroup(s)
                                    seen += s.id
                                }
                            }
                            st.copy(series = merged, seriesTotalCount = p.count, seriesNextCursor = p.next)
                        }
                    }
                    nextResult.exceptionOrNull()?.let { t ->
                        warnUnlessCancelled("VOD series next-page fetch failed; stopping", t)
                        nextUrl = null
                    }
                }
            },
            onFailure = { t ->
                warnUnlessCancelled("getVODSeries failed", t)
                _state.update { it.copy(isLoadingSeries = false, seriesError = t.message ?: t::class.simpleName) }
            },
        )
    }

    // ──────────────────── Dispatcharr VOD categories ────────────────────
    // /api/vod/movies|series/ rows hide their category inside
    // custom_properties.category_id; /api/vod/categories/ is the id -> name
    // join table. Fetched once per refresh cycle (the movies-side refresh()
    // invalidates; series refresh / pagination / search await the same
    // in-flight fetch), then every Dispatcharr row gets stamped with its
    // group display name so the Manage Groups filter works exactly like it
    // does for XC sources.

    private var dispatcharrCategoriesFetch: Deferred<Unit>? = null
    private var dispatcharrMovieCategoryNames: Map<String, String> = emptyMap()
    private var dispatcharrSeriesCategoryNames: Map<String, String> = emptyMap()
    // iOS v1.6.22 fallback: an item whose category_id is absent (or unknown)
    // lands in the FIRST enabled category of its type rather than
    // "Uncategorized". Null when the endpoint returned nothing, which keeps
    // today's no-groups behavior.
    private var dispatcharrMovieFallbackGroup: String? = null
    private var dispatcharrSeriesFallbackGroup: String? = null
    // Enabled-category NAME lists captured in fetchDispatcharrCategories, in the
    // same name-sorted order as movieGroupNames/seriesGroupNames. Drive the
    // per-category VOD fetch in refresh()/refreshSeries(). Empty => fall back to
    // the unfiltered cursor walk (older/edge servers with no category endpoint).
    private var dispatcharrEnabledMovieCats: List<String> = emptyList()
    private var dispatcharrEnabledSeriesCats: List<String> = emptyList()

    /**
     * Await this cycle's categories fetch, starting one if none is in
     * flight. `invalidate` forces a fresh fetch (a new refresh cycle should
     * see server-side group edits); everyone else piggybacks on the current
     * snapshot so pagination and search never refetch the endpoint. All
     * callers run on the Main-dispatched viewModelScope, so the
     * check-then-set on the var has no suspension point to race across.
     */
    private suspend fun ensureDispatcharrCategories(playlist: PlaylistEntity, invalidate: Boolean = false) {
        if (playlist.apiKey.isNullOrBlank()) return
        val fetch = dispatcharrCategoriesFetch
            ?.takeIf { !invalidate }
            ?: viewModelScope.async { fetchDispatcharrCategories(playlist) }
                .also { dispatcharrCategoriesFetch = it }
        fetch.await()
    }

    /**
     * One GET of /api/vod/categories/. On failure the maps stay empty and
     * every row stamps null (= "Uncategorized"), exactly the pre-categories
     * behavior. Also publishes the Manage Groups dialog's name lists.
     */
    private suspend fun fetchDispatcharrCategories(playlist: PlaylistEntity) {
        val base = playlistRepository.effectiveBaseUrl(playlist)
        val categories = runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getVODCategories(base, key)
            }
        }.onFailure { warnUnlessCancelled("getVODCategories failed; proceeding without groups", it) }
            .getOrDefault(emptyList())
            .filter { it.enabledOnAnyAccount }
        val movieCats = categories.filter { it.categoryType == "movie" }
        val seriesCats = categories.filter { it.categoryType == "series" }
        dispatcharrMovieCategoryNames = movieCats.associate { it.id.toString() to it.name }
        dispatcharrSeriesCategoryNames = seriesCats.associate { it.id.toString() to it.name }
        dispatcharrMovieFallbackGroup = movieCats.firstOrNull()?.name
        dispatcharrSeriesFallbackGroup = seriesCats.firstOrNull()?.name
        dispatcharrEnabledMovieCats = movieCats.map { it.name }.distinct()
        dispatcharrEnabledSeriesCats = seriesCats.map { it.name }.distinct()
        // NOTE: movieGroupNames / seriesGroupNames are intentionally NOT
        // published here. /api/vod/categories/ is SERVER-WIDE (every M3U
        // account on the box), and enabledOnAnyAccount passes a category that
        // is enabled on ANY account, so this list includes categories the
        // ACTIVE playlist/account has no VOD for (the "Apple TV" Series leak).
        // The per-category sweep in refresh()/refreshSeries() proves which
        // categories actually have content for THIS account and publishes only
        // those; the unfiltered fallback uses the UI's items-derived fallback.
    }

    /** Stamp a Dispatcharr movie with its group display name. */
    private fun stampMovieGroup(movie: DispatcharrVODMovie): DispatcharrVODMovie =
        movie.copy(
            categoryName = movie.vodCategoryId?.let { dispatcharrMovieCategoryNames[it] }
                ?: dispatcharrMovieFallbackGroup,
        )

    /** Stamp a Dispatcharr series with its group display name. */
    private fun stampSeriesGroup(series: DispatcharrVODSeries): DispatcharrVODSeries =
        series.copy(
            categoryName = series.vodCategoryId?.let { dispatcharrSeriesCategoryNames[it] }
                ?: dispatcharrSeriesFallbackGroup,
        )

    // While a search is active the detail screens navigate by id/uuid from
    // the search-results list, whose rows may never have paged into the
    // browse list -- so both lookups must check both lists or a search hit
    // opens to "not found".
    fun seriesById(id: Int): DispatcharrVODSeries? =
        _state.value.series.firstOrNull { it.id == id }
            ?: _state.value.seriesSearchResults.firstOrNull { it.id == id }
            ?: _state.value.resolvedSeries[id]

    fun movieById(id: Int): DispatcharrVODMovie? =
        _state.value.movies.firstOrNull { it.id == id }
            ?: _state.value.resolvedMovies.values.firstOrNull { it.id == id }

    fun movieByUuid(uuid: String): DispatcharrVODMovie? =
        _state.value.movies.firstOrNull { it.uuid == uuid }
            ?: _state.value.searchResults.firstOrNull { it.uuid == uuid }
            ?: _state.value.resolvedMovies[uuid]

    /** True while [resolveMovie] / [resolveSeries] is fetching this key. */
    fun isResolving(key: String): Boolean = key in _state.value.resolvingKeys

    // Display titles noted by the Movies / TV Shows decks when a Continue
    // Watching or Watchlist row is opened, so a movie missing from the walk
    // can be found by name (the movie list has no lookup by uuid).
    private val movieTitleHints = HashMap<String, String>()
    fun noteMovieTitle(uuid: String, title: String) { movieTitleHints[uuid] = title }

    /**
     * Fetch a movie the library walk never loaded. Dispatcharr's movie
     * endpoint is keyed by primary key, and the app only carries the uuid
     * (the play/route key), so the lookup is a name search filtered to the
     * uuid. [titleHint] comes from the watch-progress row or the Watchlist.
     */
    fun resolveMovie(uuid: String, titleHint: String?) {
        if (movieByUuid(uuid) != null) return
        val key = "m:$uuid"
        if (key in _state.value.resolvingKeys) return
        val hint = (titleHint ?: movieTitleHints[uuid])?.trim().orEmpty()
        if (hint.isEmpty()) return
        _state.update { it.copy(resolvingKeys = it.resolvingKeys + key) }
        viewModelScope.launch {
            try { resolveMovieNow(uuid, hint) } finally {
                _state.update { it.copy(resolvingKeys = it.resolvingKeys - key) }
            }
        }
    }

    /** Suspending core of [resolveMovie]; also used by the playback resolver
     *  so a deck Resume on a reassigned uuid plays without opening Details. */
    private suspend fun resolveMovieNow(uuid: String, hint: String): DispatcharrVODMovie? {
        movieByUuid(uuid)?.let { return it }
        run {
                val playlist = playlistRepository.activePlaylist() ?: return null
                if (playlist.apiKey.isNullOrBlank()) return null
                ensureDispatcharrCategories(playlist)
                val base = playlistRepository.effectiveBaseUrl(playlist).trimEnd('/')
                // Search on the bare name: playlists prefix quality tags
                // ("4K: ") and suffix years that the row title may or may not
                // carry, and the server search is a plain substring match.
                val q = hint.replace(Regex("""^\s*(4K|UHD|HD|FHD|SD)\s*[:\-]\s*""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\s*\((?:19|20)\d{2}\)\s*$"""), "").trim().ifEmpty { hint }
                val url = "$base/api/vod/movies/?search=" + java.net.URLEncoder.encode(q, "UTF-8") + "&page_size=100"
                val page = runCatching {
                    dispatcharrAuth.withApiKeyRetry(playlist.id) { key2 -> dispatcharrClient.getVODMoviesPage(url, key2) }
                }.onFailure { warnUnlessCancelled("resolveMovie '$q' failed", it) }.getOrNull()
                // Dispatcharr reassigns VOD uuids when a provider's catalog is
                // rescanned, so a Continue Watching or Watchlist row can carry
                // a uuid the server no longer has (phone 2026-09-09: every deck
                // title opened "not found" and playback got a 503). Accept the
                // exact uuid, else the single hit, else a hit whose cleaned
                // name equals the hint; the stale uuid then maps to the live row
                // and playback resolves through the live uuid.
                val results = page?.results.orEmpty()
                val cleanedHint = com.aeriotv.android.feature.movies.searchTitle(hint).lowercase()
                val hit = (results.firstOrNull { it.uuid == uuid }
                    ?: results.singleOrNull()
                    ?: results.firstOrNull { com.aeriotv.android.feature.movies.searchTitle(it.displayName).lowercase() == cleanedHint })
                    ?.let(::stampMovieGroup)
                if (hit != null) {
                    if (hit.uuid != uuid) Log.w(TAG, "[VOD] resolveMovie: '$q' now has uuid ${hit.uuid} (row carried $uuid)")
                    _state.update { it.copy(resolvedMovies = it.resolvedMovies + (uuid to hit)) }
                } else {
                    Log.w(TAG, "[VOD] resolveMovie: no match among ${results.size} hits for '$q'")
                }
                return hit
        }
    }

    /** Series counterpart of [resolveMovie]; the series endpoint is keyed by id. */
    fun resolveSeries(id: Int) {
        if (seriesById(id) != null) return
        val key = "s:$id"
        if (key in _state.value.resolvingKeys) return
        _state.update { it.copy(resolvingKeys = it.resolvingKeys + key) }
        viewModelScope.launch {
            try {
                val playlist = playlistRepository.activePlaylist() ?: return@launch
                if (playlist.apiKey.isNullOrBlank()) return@launch
                ensureDispatcharrCategories(playlist)
                val base = playlistRepository.effectiveBaseUrl(playlist)
                val hit = runCatching {
                    dispatcharrAuth.withApiKeyRetry(playlist.id) { key2 -> dispatcharrClient.getVODSeriesById(base, key2, id) }
                }.onFailure { warnUnlessCancelled("resolveSeries $id failed", it) }.getOrNull()?.let(::stampSeriesGroup)
                if (hit != null) _state.update { it.copy(resolvedSeries = it.resolvedSeries + (id to hit)) }
            } finally {
                _state.update { it.copy(resolvingKeys = it.resolvingKeys - key) }
            }
        }
    }

    /**
     * Navigation target for a "Known For" tile in the cast bio sheet: the
     * library entity's route key (movie [Movie.uuid] / series [Series.id],
     * the same args Routes.movieDetail / Routes.seriesDetail take). Null
     * from [resolveKnownForTarget] means the title is not in the library.
     */
    sealed interface KnownForTarget {
        data class Movie(val uuid: String) : KnownForTarget
        data class Series(val id: Int) : KnownForTarget
    }

    /** Trailing "(YYYY)" suffix many playlists append to VOD display names.
     *  Same shape TMDBService.splitTitleYear strips; re-implemented here
     *  because that helper is private to the service. */
    private val knownForTrailingYear = Regex("""\(((?:19|20)\d{2})\)\s*$""")

    /** Fold a display name for loose matching: trim, drop a trailing
     *  "(YYYY)" year suffix, lowercase. A name that is ONLY "(2010)" keeps
     *  its original text, mirroring splitTitleYear's empty-query guard. */
    private fun normalizeVodTitle(raw: String): String {
        val trimmed = raw.trim()
        val cleaned = knownForTrailingYear.find(trimmed)
            ?.let { trimmed.removeRange(it.range).trim() }
            ?.ifEmpty { trimmed }
            ?: trimmed
        return cleaned.lowercase()
    }

    /**
     * Find the library entity behind a "Known For" tile so the bio sheet can
     * open its detail screen. Loaded lists first (browse + search results,
     * same pair the by-uuid/by-id lookups above walk): an entity with a
     * non-blank tmdbId must match on tmdbId, everything else falls back to
     * the normalized-title comparison. When the loaded lists miss AND the
     * active source is Dispatcharr (whose library pages in lazily, ~1k of a
     * possibly 30k+ catalog), a one-shot server search covers the unwalked
     * remainder. XC sources load their whole library up front, so a
     * loaded-list miss there is a real miss and the fallback is skipped.
     * Returns null when the title is not in the library (or the fallback
     * fetch failed, which the caller treats the same way).
     */
    suspend fun resolveKnownForTarget(item: TmdbKnownForItem): KnownForTarget? {
        val wantTitle = normalizeVodTitle(item.title)
        if (item.isMovie) {
            val loaded = _state.value.movies + _state.value.searchResults
            val match = loaded.firstOrNull { !it.tmdbId.isNullOrBlank() && it.tmdbId == item.id }
                ?: loaded.firstOrNull {
                    it.tmdbId.isNullOrBlank() && normalizeVodTitle(it.displayName) == wantTitle
                }
                ?: searchDispatcharrKnownForMovie(item, wantTitle)
                ?: return null
            return KnownForTarget.Movie(match.uuid)
        }
        val loaded = _state.value.series + _state.value.seriesSearchResults
        val match = loaded.firstOrNull { !it.tmdbId.isNullOrBlank() && it.tmdbId == item.id }
            ?: loaded.firstOrNull {
                it.tmdbId.isNullOrBlank() && normalizeVodTitle(it.displayName) == wantTitle
            }
            ?: searchDispatcharrKnownForSeries(item, wantTitle)
            ?: return null
        return KnownForTarget.Series(match.id)
    }

    /**
     * "Related" strip for the phone/tablet detail screens: TMDB
     * recommendations for [tmdbId] (resolved by [title] when the item has
     * none) matched against the LOCAL library, so every tile is playable.
     * Same matcher as [resolveKnownForTarget] (tmdbId when the entity has
     * one, else the normalized title) but run over a snapshot on
     * [Dispatchers.Default] with prebuilt indexes, because the recommendation
     * list is up to 20 entries and the library can be thousands of titles
     * (tvOS VODDetailView.loadRelatedIfNeeded). [selfKey] is the opened
     * title's MediaItem key ("m:uuid" / "s:id") and is never returned; hits
     * are deduped and capped at 12. Empty when TMDB is not configured.
     */
    suspend fun relatedTitles(
        tmdbId: String?,
        title: String,
        isMovie: Boolean,
        selfKey: String,
    ): List<com.aeriotv.android.feature.movies.MediaItem> {
        if (!isTmdbConfigured()) return emptyList()
        val key = appPreferences.tmdbApiKey.first()
        val id = tmdbId?.takeIf { it.isNotBlank() }
            ?: tmdbService.resolveIdForTitleOrNull(title, isMovie, key)
            ?: return emptyList()
        val recs = tmdbService.recommendations(id, isMovie, key)
        if (recs.isEmpty()) return emptyList()
        val snapshot = _state.value
        return withContext(Dispatchers.Default) {
            val seen = mutableSetOf(selfKey)
            val out = mutableListOf<com.aeriotv.android.feature.movies.MediaItem>()
            if (isMovie) {
                val library = snapshot.movies + snapshot.searchResults + snapshot.resolvedMovies.values
                val byTmdb = HashMap<String, DispatcharrVODMovie>()
                val byTitle = HashMap<String, DispatcharrVODMovie>()
                for (m in library) {
                    val t = m.tmdbId
                    if (!t.isNullOrBlank()) byTmdb.putIfAbsent(t, m)
                    else byTitle.putIfAbsent(normalizeVodTitle(m.displayName), m)
                }
                for (rec in recs) {
                    if (!rec.isMovie) continue
                    val hit = byTmdb[rec.id] ?: byTitle[normalizeVodTitle(rec.title)] ?: continue
                    val item = hit.toMediaItem()
                    if (seen.add(item.key)) out += item
                    if (out.size >= 12) break
                }
            } else {
                val library = snapshot.series + snapshot.seriesSearchResults + snapshot.resolvedSeries.values
                val byTmdb = HashMap<String, DispatcharrVODSeries>()
                val byTitle = HashMap<String, DispatcharrVODSeries>()
                for (s in library) {
                    val t = s.tmdbId
                    if (!t.isNullOrBlank()) byTmdb.putIfAbsent(t, s)
                    else byTitle.putIfAbsent(normalizeVodTitle(s.displayName), s)
                }
                for (rec in recs) {
                    if (rec.isMovie) continue
                    val hit = byTmdb[rec.id] ?: byTitle[normalizeVodTitle(rec.title)] ?: continue
                    val item = hit.toMediaItem()
                    if (seen.add(item.key)) out += item
                    if (out.size >= 12) break
                }
            }
            Log.d(TAG, "Related: ${recs.size} TMDB recommendations -> ${out.size} in library")
            out
        }
    }

    /** The active playlist when it is a Dispatcharr source with a usable
     *  key, else null. Same gate the server-side search flows apply. */
    private suspend fun dispatcharrPlaylistOrNull(): PlaylistEntity? {
        val playlist = playlistRepository.activePlaylist() ?: return null
        val sourceType = SourceType.entries.firstOrNull { it.name == playlist.sourceType }
        val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                sourceType == SourceType.DispatcharrUserPass
        return playlist.takeIf { isDispatcharr && !it.apiKey.isNullOrBlank() }
    }

    /**
     * One-shot Dispatcharr movie search for a Known For tile the loaded
     * lists missed. Same endpoint + auth shape as setSearchQuery, but a
     * small page suffices (the query is the tile's exact display title).
     * A match is merged into the browse list, stamped with its group name
     * like every other ingest path, so movieByUuid resolves it when the
     * detail screen opens. Any failure returns null (= not in library).
     */
    private suspend fun searchDispatcharrKnownForMovie(
        item: TmdbKnownForItem,
        wantTitle: String,
    ): DispatcharrVODMovie? {
        val playlist = dispatcharrPlaylistOrNull() ?: return null
        ensureDispatcharrCategories(playlist)
        val base = playlistRepository.effectiveBaseUrl(playlist).trimEnd('/')
        val url = "$base/api/vod/movies/?search=" +
                java.net.URLEncoder.encode(item.title, "UTF-8") +
                "&page_size=$KNOWN_FOR_SEARCH_PAGE_SIZE&page=1"
        val results = runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getVODMoviesPage(url, key)
            }
        }.onFailure { warnUnlessCancelled("KnownFor movie search failed", it) }
            .getOrNull()?.results ?: return null
        val match = results.firstOrNull { !it.tmdbId.isNullOrBlank() && it.tmdbId == item.id }
            ?: results.firstOrNull { normalizeVodTitle(it.displayName) == wantTitle }
            ?: return null
        val stamped = stampMovieGroup(match)
        // Merge (uuid is the movies de-dup key everywhere else) so the
        // pushed detail screen's movieByUuid lookup can resolve it.
        _state.update { st ->
            if (st.movies.any { it.uuid == stamped.uuid }) st
            else st.copy(movies = st.movies + stamped)
        }
        return stamped
    }

    /** Series counterpart of [searchDispatcharrKnownForMovie]; merges on the
     *  Int id (the series de-dup key) so seriesById resolves it. */
    private suspend fun searchDispatcharrKnownForSeries(
        item: TmdbKnownForItem,
        wantTitle: String,
    ): DispatcharrVODSeries? {
        val playlist = dispatcharrPlaylistOrNull() ?: return null
        ensureDispatcharrCategories(playlist)
        val base = playlistRepository.effectiveBaseUrl(playlist).trimEnd('/')
        val url = "$base/api/vod/series/?search=" +
                java.net.URLEncoder.encode(item.title, "UTF-8") +
                "&page_size=$KNOWN_FOR_SEARCH_PAGE_SIZE&page=1"
        val results = runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getVODSeriesPage(url, key)
            }
        }.onFailure { warnUnlessCancelled("KnownFor series search failed", it) }
            .getOrNull()?.results ?: return null
        val match = results.firstOrNull { !it.tmdbId.isNullOrBlank() && it.tmdbId == item.id }
            ?: results.firstOrNull { normalizeVodTitle(it.displayName) == wantTitle }
            ?: return null
        val stamped = stampSeriesGroup(match)
        _state.update { st ->
            if (st.series.any { it.id == stamped.id }) st
            else st.copy(series = st.series + stamped)
        }
        return stamped
    }

    /**
     * TMDB poster fallback (iOS VODDetailView.loadTMDBPosterIfNeeded parity).
     * Returns a poster image URL only when the user has opted in AND set a key;
     * prefers an exact tmdb_id lookup, falling back to a title search. Returns
     * null (caller keeps its placeholder) when disabled, unkeyed, or no match.
     * Callers invoke this ONLY when the server provided no artwork.
     */
    /**
     * True when the TMDB opt-in is on AND a key is saved: the detail screens'
     * provenance note uses it to pick between "add a key in Settings" and
     * "no matching title" when a title has no artwork (iOS tmdbSourceNote).
     */
    suspend fun isTmdbConfigured(): Boolean =
        appPreferences.programPostersTmdbEnabled.first() && appPreferences.tmdbApiKey.first().isNotBlank()

    suspend fun resolveTmdbPoster(tmdbId: String?, title: String, isMovie: Boolean): String? {
        if (!appPreferences.programPostersTmdbEnabled.first()) return null
        val key = appPreferences.tmdbApiKey.first()
        if (key.isBlank()) return null
        tmdbId?.takeIf { it.isNotBlank() }?.let { id ->
            tmdbService.posterUrlForId(id, isMovie, key)?.let { return it }
        }
        if (title.isBlank()) return null
        return tmdbService.posterUrlForTitle(title, key)
    }

    /**
     * TMDB metadata backfill for detail fields the server left blank
     * (plot / genre / cast / director / year / rating). Same opt-in + key
     * gate and id-then-title resolution order as [resolveTmdbPoster].
     * Callers invoke this ONLY when something is actually missing.
     */
    suspend fun resolveTmdbDetails(tmdbId: String?, title: String, isMovie: Boolean): TmdbDetails? {
        if (!appPreferences.programPostersTmdbEnabled.first()) return null
        val key = appPreferences.tmdbApiKey.first()
        if (key.isBlank()) return null
        tmdbId?.takeIf { it.isNotBlank() }?.let { id ->
            tmdbService.detailsForId(id, isMovie, key)?.let { return it }
        }
        if (title.isBlank()) return null
        return tmdbService.detailsForTitle(title, isMovie, key)
    }

    /** Hero backdrop (media center): TMDB backdrop for the title, or null. Same gates as [resolveTmdbDetails]. */
    suspend fun resolveTmdbBackdropUrl(tmdbId: String?, title: String, isMovie: Boolean): String? {
        val details = resolveTmdbDetails(tmdbId, title, isMovie) ?: return null
        return details.backdropPath?.takeIf { it.isNotBlank() }?.let { tmdbService.imageUrlFor(it) }
    }

    /**
     * Structured TMDB credits (cast with headshots + directors) for the
     * detail screens. Same opt-in + key gate and id-then-title resolution
     * order as [resolveTmdbDetails].
     */
    suspend fun resolveTmdbCredits(tmdbId: String?, title: String, isMovie: Boolean): TmdbCredits? {
        if (!appPreferences.programPostersTmdbEnabled.first()) return null
        val key = appPreferences.tmdbApiKey.first()
        if (key.isBlank()) return null
        tmdbId?.takeIf { it.isNotBlank() }?.let { id ->
            tmdbService.creditsForId(id, isMovie, key)?.let { return it }
        }
        if (title.isBlank()) return null
        return tmdbService.creditsForTitle(title, isMovie, key)
    }

    /** Person biography for the cast-member sheet. Same opt-in + key gate as
     *  [resolveTmdbDetails]; the personId comes from [TmdbCredits] rows. */
    suspend fun resolveTmdbPersonBio(personId: String): TmdbPersonBio? {
        if (!appPreferences.programPostersTmdbEnabled.first()) return null
        val key = appPreferences.tmdbApiKey.first()
        if (key.isBlank()) return null
        return tmdbService.personBio(personId, key)
    }

    /** Headshot URL pass-through so screens never need a TMDBService
     *  reference. Pure string building, hence not gated on the pref. */
    fun tmdbProfileImageUrl(path: String?, size: String = "w185"): String? =
        tmdbService.profileImageUrl(path, size)

    /**
     * Lazy-fetch provider-info for a movie — backdrop, cast, director,
     * country, trailer URL. Idempotent within session: a second call for the
     * same id no-ops if either the data is already cached or a fetch is
     * already in flight. Mirrors iOS VODService.enrichMovie which runs this
     * the first time the detail page opens.
     */
    fun loadMovieProviderInfo(movieId: Int) {
        val current = _state.value
        if (current.movieProviderInfo.containsKey(movieId) ||
            current.movieProviderInfoLoading.contains(movieId)) return
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist() ?: return@launch
            if (playlist.apiKey.isNullOrBlank()) return@launch
            val base = playlistRepository.effectiveBaseUrl(playlist)
            _state.update { it.copy(movieProviderInfoLoading = it.movieProviderInfoLoading + movieId) }
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getMovieProviderInfo(base, key, movieId)
                }
            }.fold(
                onSuccess = { info ->
                    _state.update { st ->
                        st.copy(
                            movieProviderInfo = st.movieProviderInfo + (movieId to info),
                            movieProviderInfoLoading = st.movieProviderInfoLoading - movieId,
                        )
                    }
                },
                onFailure = { t ->
                    warnUnlessCancelled("getMovieProviderInfo($movieId) failed", t)
                    _state.update { it.copy(movieProviderInfoLoading = it.movieProviderInfoLoading - movieId) }
                },
            )
        }
    }

    /** Series equivalent of [loadMovieProviderInfo]. Same throttling +
     *  idempotency rules. */
    fun loadSeriesProviderInfo(seriesId: Int) {
        val current = _state.value
        if (current.seriesProviderInfo.containsKey(seriesId) ||
            current.seriesProviderInfoLoading.contains(seriesId)) return
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist() ?: return@launch
            if (playlist.apiKey.isNullOrBlank()) return@launch
            val base = playlistRepository.effectiveBaseUrl(playlist)
            _state.update { it.copy(seriesProviderInfoLoading = it.seriesProviderInfoLoading + seriesId) }
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getSeriesProviderInfo(base, key, seriesId)
                }
            }.fold(
                onSuccess = { info ->
                    _state.update { st ->
                        st.copy(
                            seriesProviderInfo = st.seriesProviderInfo + (seriesId to info),
                            seriesProviderInfoLoading = st.seriesProviderInfoLoading - seriesId,
                        )
                    }
                },
                onFailure = { t ->
                    warnUnlessCancelled("getSeriesProviderInfo($seriesId) failed", t)
                    _state.update { it.copy(seriesProviderInfoLoading = it.seriesProviderInfoLoading - seriesId) }
                },
            )
        }
    }

    /**
     * Lazy-load (idempotent within session) the provider copies of a movie for
     * the Version picker. Dispatcharr Direct Connect only - the repository
     * returns empty for every other source, which caches as "nothing to offer"
     * so the pill never renders. Failures also cache empty (best-effort UI;
     * label fields only in logs, never the raw payload).
     */
    fun loadMovieProviders(movieId: Int) {
        val current = _state.value
        if (current.movieProviders.containsKey(movieId) ||
            current.movieProvidersLoading.contains(movieId)) return
        viewModelScope.launch {
            _state.update { it.copy(movieProvidersLoading = it.movieProvidersLoading + movieId) }
            val relations = runCatching {
                playlistRepository.listVodMovieProviders(movieId)
            }.getOrElse { t ->
                Log.w(TAG, "listVodMovieProviders($movieId) failed: ${t::class.simpleName}")
                emptyList()
            }
            movieProviderRelations[movieId] = relations
            // Whatever previous playbacks measured for these copies is already
            // on disk, so the picker can open fully described even before the
            // server's own provider-info round trips land.
            val learned = learnedStreamStore.lookupAll(relations.map { it.id })
            _state.update { st ->
                val allLearned = st.movieLearnedStreams + learned
                st.copy(
                    movieLearnedStreams = allLearned,
                    movieProviders = st.movieProviders +
                        (movieId to relations.toVodProviderOptions(st.movieProviderMedia, allLearned)),
                    movieProvidersLoading = st.movieProvidersLoading - movieId,
                )
            }
            // AFTER the options are in state: the remembered pin is validated
            // against this list, so it has to exist first.
            restoreSelectedMovieVersion(movieId)
            // Only worth measuring when there is an actual choice to make; a
            // lone copy never renders the picker.
            if (relations.size > 1) loadMovieProviderMedia(movieId, relations.map { it.id })
        }
    }

    /**
     * Fill in each copy's MEASURED stream properties and rebuild the picker
     * labels around them. Runs AFTER the options are already in state because
     * a copy the server has not inspected yet costs an upstream round trip;
     * concurrency is capped so opening a detail page never fans out a burst at
     * the provider. Failures just leave that row showing account + container.
     *
     * Movies only: the series /providers/ rows describe the show, not an
     * episode file, so there is nothing measured to attach to them.
     */
    private suspend fun loadMovieProviderMedia(movieId: Int, relationIds: List<Int>) {
        val pending = relationIds.filterNot { _state.value.movieProviderMedia.containsKey(it) }
        pending.chunked(PROVIDER_MEDIA_CONCURRENCY).forEach { batch ->
            val measured = coroutineScope {
                batch.map { relationId ->
                    async { relationId to playlistRepository.vodMovieProviderMedia(movieId, relationId) }
                }.awaitAll()
            }
            // Only real measurements enter the map; a copy the server could not
            // describe stays absent rather than contributing an empty label.
            val fresh = measured.mapNotNull { (relationId, media) ->
                media?.takeIf { it.hasAnyMeasurement }?.let { relationId to it }
            }
            if (fresh.isEmpty()) return@forEach
            // Publish per batch so labels sharpen progressively instead of all
            // at once behind the slowest copy.
            _state.update { st ->
                st.copy(movieProviderMedia = st.movieProviderMedia + fresh)
                    .withRebuiltMovieOptions()
            }
        }
    }

    /**
     * Remember what the PLAYER measured for one movie copy and re-label the
     * picker around it. Called from the VOD player as tracks resolve, so the
     * store elides a write when nothing changed; state only churns when the
     * measurement is genuinely new.
     *
     * Movies only, by construction: [relationId] is a provider RELATION pk,
     * and only the movie player route hands one over.
     */
    suspend fun recordLearnedStream(relationId: Int, stream: VodLearnedStream) {
        if (stream.isEmpty) return
        learnedStreamStore.record(relationId, stream)
        _state.update { st ->
            if (st.movieLearnedStreams[relationId] == stream) return@update st
            st.copy(movieLearnedStreams = st.movieLearnedStreams + (relationId to stream))
                .withRebuiltMovieOptions()
        }
    }

    /**
     * Re-read this movie's on-device measurements and re-label the picker.
     * Runs when a player closes (iOS VODDetailView .onDisappear parity) so a
     * copy that was just played reads with its real resolution and codecs.
     * Normally a no-op on the shared VM, where [recordLearnedStream] already
     * landed it live; it earns its keep on a deep-linked player that ran
     * against its own VM instance.
     */
    fun refreshLearnedStreams(movieId: Int) {
        val relationIds = movieProviderRelations[movieId]?.map { it.id }.orEmpty()
        if (relationIds.isEmpty()) return
        viewModelScope.launch {
            val learned = learnedStreamStore.lookupAll(relationIds)
            if (learned.isEmpty()) return@launch
            _state.update { st ->
                if (learned.all { (id, s) -> st.movieLearnedStreams[id] == s }) return@update st
                st.copy(movieLearnedStreams = st.movieLearnedStreams + learned)
                    .withRebuiltMovieOptions()
            }
        }
    }

    /**
     * Rebuild every loaded movie's picker labels from the CURRENT server +
     * learned measurements, keeping each pinned row's label in step with the
     * picker (the detail pill and the player sheet both render it). Cheap: a
     * handful of relations per opened item.
     */
    private fun UiState.withRebuiltMovieOptions(): UiState {
        if (movieProviderRelations.isEmpty()) return this
        val rebuilt = movieProviders.mapValues { (movieId, existing) ->
            movieProviderRelations[movieId]
                ?.toVodProviderOptions(movieProviderMedia, movieLearnedStreams)
                ?: existing
        }
        val pinned = selectedMovieVersion.mapValues { (movieId, selected) ->
            rebuilt[movieId]?.firstOrNull { it.relationId == selected.relationId } ?: selected
        }
        return copy(movieProviders = rebuilt, selectedMovieVersion = pinned)
    }

    /** Series equivalent of [loadMovieProviders]. */
    fun loadSeriesProviders(seriesId: Int) {
        val current = _state.value
        if (current.seriesProviders.containsKey(seriesId) ||
            current.seriesProvidersLoading.contains(seriesId)) return
        viewModelScope.launch {
            _state.update { it.copy(seriesProvidersLoading = it.seriesProvidersLoading + seriesId) }
            val options = runCatching {
                // Episodes pin by m3u_account_id only (the series relation's
                // id does not address an episode row), so two relations from
                // one account would be two rows that play the same thing.
                // Collapse to one option per account BEFORE labelling.
                playlistRepository.listVodSeriesProviders(seriesId)
                    .distinctBy { it.m3uAccount?.id }
                    .toVodProviderOptions()
            }.getOrElse { t ->
                Log.w(TAG, "listVodSeriesProviders($seriesId) failed: ${t::class.simpleName}")
                emptyList()
            }
            _state.update { st ->
                st.copy(
                    seriesProviders = st.seriesProviders + (seriesId to options),
                    seriesProvidersLoading = st.seriesProvidersLoading - seriesId,
                )
            }
            // Same ordering rule as the movie path: validate the remembered pin
            // against the list that just landed.
            restoreSelectedSeriesVersion(seriesId)
        }
    }

    /**
     * Pin (or, with null, un-pin back to Auto) the movie's playback version.
     * Read by [resolveMovieUrl] on the next resolve, and REMEMBERED so
     * reopening the title keeps the chosen copy. Both pickers (the detail
     * screen's and the player's Switch Version sheet) land here, so an
     * in-player switch persists exactly like a detail-screen one; Auto clears
     * the stored row rather than storing a sentinel.
     */
    fun selectMovieVersion(movieId: Int, option: VodProviderOption?) {
        _state.update { st ->
            st.copy(
                selectedMovieVersion = if (option == null) {
                    st.selectedMovieVersion - movieId
                } else {
                    st.selectedMovieVersion + (movieId to option)
                },
            )
        }
        // State moved synchronously above, so a resolve firing right behind
        // this call already sees the pin; only the disk write is deferred.
        viewModelScope.launch {
            versionSelectionKey(VodVersionItemType.MOVIE, movieId)?.let { key ->
                versionSelectionStore.setSelection(key, option?.relationId)
            }
        }
    }

    /** Series counterpart of [selectMovieVersion]; read by [resolveEpisodeUrl].
     *  An episode option pins an m3u ACCOUNT, but the row stored is still the
     *  relation id, so the restore validates against the same option list. */
    fun selectSeriesVersion(seriesId: Int, option: VodProviderOption?) {
        _state.update { st ->
            st.copy(
                selectedSeriesVersion = if (option == null) {
                    st.selectedSeriesVersion - seriesId
                } else {
                    st.selectedSeriesVersion + (seriesId to option)
                },
            )
        }
        viewModelScope.launch {
            versionSelectionKey(VodVersionItemType.SERIES, seriesId)?.let { key ->
                versionSelectionStore.setSelection(key, option?.relationId)
            }
        }
    }

    /**
     * Re-read the remembered pin for a movie and adopt it. Runs when a player
     * closes (iOS VODDetailView .onDisappear parity) so the detail screen's
     * "Version: ..." pill agrees with a Switch Version made inside the player.
     * Normally a no-op: both routes resolve the MAIN-scoped VM, so
     * [selectMovieVersion] already moved this state. It earns its keep on a
     * deep-linked player that ran against its own VM instance.
     */
    fun refreshSelectedMovieVersion(movieId: Int) {
        viewModelScope.launch { restoreSelectedMovieVersion(movieId) }
    }

    /** Series counterpart of [refreshSelectedMovieVersion], for the episode
     *  player route. */
    fun refreshSelectedSeriesVersion(seriesId: Int) {
        viewModelScope.launch { restoreSelectedSeriesVersion(seriesId) }
    }

    /**
     * Apply the remembered pin for [movieId], but ONLY when that copy is still
     * in the current option list: a relation the provider dropped is cleared
     * and the title falls back to Auto rather than pinning a dead id that would
     * fail to play. Matching is by relation id, never by label, so measurements
     * landing later and relabelling a row cannot lose the selection.
     *
     * Nothing stored leaves the current state alone, matching iOS: an explicit
     * Auto is the absence of a row, not an instruction to un-pin.
     */
    private suspend fun restoreSelectedMovieVersion(movieId: Int) {
        val key = versionSelectionKey(VodVersionItemType.MOVIE, movieId) ?: return
        val savedId = versionSelectionStore.selection(key) ?: return
        // An EMPTY list is "no copies loaded" -- an unsupported source, or a
        // failed fetch that cached empty. Neither is evidence the pinned copy
        // is gone, so the row survives to be validated against a real list.
        val options = _state.value.movieProviders[movieId]?.takeIf { it.isNotEmpty() } ?: return
        val match = options.firstOrNull { it.relationId == savedId }
        if (match == null) {
            versionSelectionStore.setSelection(key, null)
            return
        }
        _state.update { st ->
            if (st.selectedMovieVersion[movieId] == match) return@update st
            st.copy(selectedMovieVersion = st.selectedMovieVersion + (movieId to match))
        }
    }

    /** Series counterpart of [restoreSelectedMovieVersion], same empty-list
     *  rule. */
    private suspend fun restoreSelectedSeriesVersion(seriesId: Int) {
        val key = versionSelectionKey(VodVersionItemType.SERIES, seriesId) ?: return
        val savedId = versionSelectionStore.selection(key) ?: return
        val options = _state.value.seriesProviders[seriesId]?.takeIf { it.isNotEmpty() } ?: return
        val match = options.firstOrNull { it.relationId == savedId }
        if (match == null) {
            versionSelectionStore.setSelection(key, null)
            return
        }
        _state.update { st ->
            if (st.selectedSeriesVersion[seriesId] == match) return@update st
            st.copy(selectedSeriesVersion = st.selectedSeriesVersion + (seriesId to match))
        }
    }

    /**
     * Storage key for one item's remembered version. iOS scopes these by the
     * server's UUID; the active playlist id is that same identity here, so the
     * same title on two sources keeps separate choices. Null with no active
     * playlist, which is nothing to scope a choice to.
     */
    private suspend fun versionSelectionKey(type: VodVersionItemType, itemId: Int): String? {
        val playlistId = playlistRepository.activePlaylist()?.id ?: return null
        return VodVersionSelectionStore.storageKey(playlistId, type, itemId)
    }

    /** Lazy-load (idempotent within session) episodes for a series. */
    fun loadEpisodes(seriesId: Int) {
        val current = _state.value
        if (current.episodesBySeries.containsKey(seriesId) ||
            current.episodesLoadingFor.contains(seriesId)) {
            return
        }
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist()
                ?: return@launch
            if (playlist.isXtream()) {
                loadXtreamEpisodes(playlist, seriesId)
                return@launch
            }
            if (playlist.apiKey.isNullOrBlank()) return@launch
            val base = playlistRepository.effectiveBaseUrl(playlist)
            _state.update {
                it.copy(
                    episodesLoadingFor = it.episodesLoadingFor + seriesId,
                    seriesProviderInfoLoading = it.seriesProviderInfoLoading + seriesId,
                )
            }
            // Dispatcharr lazy-scrape ordering (iOS VODService.dispatcharrSeriesDetail,
            // v1.6.16.x): the /provider-info/ (series_info) call is what triggers
            // Dispatcharr to populate the per-series episode table from the upstream
            // provider. Hitting /episodes/ first (or concurrently) returns an EMPTY
            // array for any series that hasn't been scraped yet; that empty list then
            // sticks for the session (episodesBySeries caches it) so the detail screen
            // shows no episode rows and therefore no Play affordance. Prime
            // provider-info FIRST, best-effort, before the episodes fetch; also caches
            // the payload so the separate loadSeriesProviderInfo() is unnecessary.
            if (!current.seriesProviderInfo.containsKey(seriesId)) {
                runCatching {
                    dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                        dispatcharrClient.getSeriesProviderInfo(base, key, seriesId)
                    }
                }.onSuccess { info ->
                    _state.update { st ->
                        st.copy(seriesProviderInfo = st.seriesProviderInfo + (seriesId to info))
                    }
                }.onFailure { t ->
                    warnUnlessCancelled("prime getSeriesProviderInfo($seriesId) failed; loading episodes anyway", t)
                }
            }
            _state.update { it.copy(seriesProviderInfoLoading = it.seriesProviderInfoLoading - seriesId) }
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getSeriesEpisodesFirstPage(base, key, seriesId) { partial ->
                        // Progressive fill: the first page is on screen while
                        // the rest of a long-running show still loads.
                        _state.update { st ->
                            st.copy(episodesBySeries = st.episodesBySeries + (seriesId to partial))
                        }
                    }
                }
            }.fold(
                onSuccess = { page ->
                    _state.update { st ->
                        st.copy(
                            episodesBySeries = st.episodesBySeries + (seriesId to page.results),
                            episodesLoadingFor = st.episodesLoadingFor - seriesId,
                            episodesErrorFor = st.episodesErrorFor - seriesId,
                        )
                    }
                },
                onFailure = { t ->
                    warnUnlessCancelled("getSeriesEpisodes($seriesId) failed", t)
                    // GH #87: an ART allocation dump is not a user message.
                    val oom = generateSequence(t as Throwable?) { it.cause }.any { it is OutOfMemoryError } ||
                        t.message?.contains("Failed to allocate", ignoreCase = true) == true
                    val message = if (oom) "This series is too large to load on this device."
                    else (t.message ?: t::class.simpleName.orEmpty())
                    _state.update { st ->
                        st.copy(
                            episodesLoadingFor = st.episodesLoadingFor - seriesId,
                            episodesErrorFor = st.episodesErrorFor + (seriesId to message),
                        )
                    }
                },
            )
        }
    }

    /** Same pattern as resolveMovieUrl but for an episode proxy URL. */
    suspend fun resolveEpisodeUrl(episodeUuid: String, streamId: Int?): Result<ResolvedVod> {
        val playlist = playlistRepository.activePlaylist()
            ?: return Result.failure(IllegalStateException("No playlist loaded."))
        // Xtream episodes carry a deterministic URL encoded in the sentinel
        // uuid ("xc-ep-<id>-<ext>"); no network round-trip needed.
        if (episodeUuid.startsWith(XC_EP_PREFIX)) {
            return resolveXtreamUrl(playlist, episodeUuid, XC_EP_PREFIX) { base, u, p, id, ext ->
                xtreamApi.episodeStreamUrl(base, u, p, id, ext)
            }.map { ResolvedVod(it, authSafe = true) }
        }
        if (playlist.apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Active source is not Dispatcharr-backed."))
        }
        // Version pinning: when the user picked a provider copy for the parent
        // series, pin the episode to that account (episodes have no per-copy
        // stream id; the account is the whole pin).
        val parentSeriesId = _state.value.episodesBySeries.entries
            .firstOrNull { (_, list) -> list.any { it.uuid == episodeUuid } }
            ?.key
        val selection = parentSeriesId?.let { _state.value.selectedSeriesVersion[it] }
        val base = playlistRepository.effectiveBaseUrl(playlist)
        return runCatching {
            val url = dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.resolveVODEpisodeStreamUrl(
                    baseUrl = base,
                    apiKey = key,
                    episodeUuid = episodeUuid,
                    streamId = streamId,
                    m3uAccountId = selection?.accountId,
                )
            }
            ResolvedVod(url, authSafe = sameOrigin(base, url))
        }
    }

    /**
     * Resolves the redirect-bound proxy URL to the session-bound playback URL
     * for a movie. Returns a Result so the caller can surface failures via
     * Toast / inline error rather than landing on a half-loaded player.
     */
    suspend fun resolveMovieUrl(movieUuid: String): Result<ResolvedVod> {
        val playlist = playlistRepository.activePlaylist()
            ?: return Result.failure(IllegalStateException("No playlist loaded."))
        // Xtream movies carry a deterministic URL encoded in the sentinel
        // uuid ("xc-movie-<id>-<ext>"); build it directly.
        if (movieUuid.startsWith(XC_MOVIE_PREFIX)) {
            return resolveXtreamUrl(playlist, movieUuid, XC_MOVIE_PREFIX) { base, u, p, id, ext ->
                xtreamApi.vodStreamUrl(base, u, p, id, ext)
            }.map { ResolvedVod(it, authSafe = true) }
        }
        if (playlist.apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Active source is not Dispatcharr-backed."))
        }
        // A row that carried a reassigned uuid was mapped to the live movie by
        // resolveMovie; play through the live uuid, not the stale one.
        val movie = movieByUuid(movieUuid)
            ?: movieTitleHints[movieUuid]?.let { hint -> resolveMovieNow(movieUuid, hint) }
        val liveUuid = movie?.uuid ?: movieUuid
        // Version pinning: a picked provider copy replaces the firstStreamId
        // default entirely (its stream_id + m3u_account_id ride the proxy URL;
        // stream_id wins server-side when both land). Absent selection = Auto,
        // the server's priority + failover behavior.
        val selection = movie?.id?.let { _state.value.selectedMovieVersion[it] }
        val base = playlistRepository.effectiveBaseUrl(playlist)
        return runCatching {
            val url = dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.resolveVODStreamUrl(
                    baseUrl = base,
                    apiKey = key,
                    movieUuid = liveUuid,
                    streamId = if (selection != null) selection.streamId?.toIntOrNull()
                    else movie?.firstStreamId,
                    m3uAccountId = selection?.accountId,
                )
            }
            ResolvedVod(url, authSafe = sameOrigin(base, url))
        }
    }

    /**
     * Audit #53 (finding #38): a resolved VOD playback URL plus whether the
     * API-key headers may ride along. Dispatcharr's 301 hands back a one-time
     * session URL that needs no further auth; when that Location points OFF
     * the server's origin (hostile server, or a MITM on a cleartext LAN
     * source), attaching the default request headers would replay the user's
     * API key to an arbitrary third-party host. Callers attach auth headers
     * ONLY when [authSafe] is true. The non-3xx fallback (older Dispatcharr
     * serving content from the entry path, which DOES need the key) is
     * same-origin by construction, so it keeps its headers.
     */
    data class ResolvedVod(val url: String, val authSafe: Boolean)

    /** Scheme+host+effective-port equality; unparseable input = NOT same origin. */
    private fun sameOrigin(a: String, b: String): Boolean {
        fun origin(raw: String): String? = runCatching {
            val u = java.net.URI(raw.trim())
            val scheme = u.scheme?.lowercase() ?: return@runCatching null
            val host = u.host?.lowercase() ?: return@runCatching null
            val port = if (u.port > 0) u.port else if (scheme == "https") 443 else 80
            "$scheme://$host:$port"
        }.getOrNull()
        val oa = origin(a) ?: return false
        return oa == origin(b)
    }

    // ───────────────────────── Xtream Codes VOD ─────────────────────────
    // XC VOD/series are mapped into the existing DispatcharrVOD* shapes so
    // the On Demand UI + nav are source-agnostic. The play URL is encoded
    // in a sentinel uuid (xc-movie-<id>-<ext> / xc-ep-<id>-<ext>) that the
    // resolve* methods decode -- XC URLs are deterministic, no round-trip.

    private fun PlaylistEntity.isXtream(): Boolean = sourceType == SourceType.XtreamCodes.name

    private fun XtreamCodesApi.XtreamVod.toMovie(categoryNames: Map<String, String> = emptyMap()): DispatcharrVODMovie = DispatcharrVODMovie(
        id = streamId,
        uuid = "$XC_MOVIE_PREFIX$streamId-$containerExtension",
        title = name,
        plot = plot,
        genre = genre,
        rating = rating,
        year = year,
        logo = icon?.let { DispatcharrVODLogo(url = it) },
        // v0.26.0: surface the XC trailer so XC movies get a Trailer button too
        // (native Dispatcharr movies already do via provider-info).
        youtubeTrailer = youtubeTrailer,
        // Resolve category_id -> display name from the lookup the probe /
        // walk pre-fetched. Falls back to null when the panel omitted the id
        // or the id has no matching row in get_vod_categories -- the filter
        // UI treats null as "Uncategorized" and lets the user hide that too.
        categoryName = categoryId?.let { categoryNames[it] },
    )

    private fun XtreamCodesApi.XtreamSeries.toSeries(categoryNames: Map<String, String> = emptyMap()): DispatcharrVODSeries = DispatcharrVODSeries(
        id = seriesId,
        uuid = "xc-series-$seriesId",
        name = name,
        plot = plot,
        genre = genre,
        rating = rating,
        year = year,
        logo = cover?.let { DispatcharrVODLogo(url = it) },
        categoryName = categoryId?.let { categoryNames[it] },
    )

    // XC On Demand is loaded LAZILY. At init a cheap probe runs (a standard
    // panel returns its whole library in one request; Dispatcharr's XC bridge
    // needs only the category-id lists to know the tab has content). The
    // expensive per-category enumeration is DEFERRED to loadXtreamItemsIfNeeded,
    // triggered when the On Demand tab is actually opened, so it never hammers
    // the device while the guide is still loading.
    private var xtreamProbeJob: kotlinx.coroutines.Job? = null
    private var xtreamItemsJob: kotlinx.coroutines.Job? = null
    private var pendingMovieCats: List<String> = emptyList()
    private var pendingSeriesCats: List<String> = emptyList()
    // category_id -> display-name lookups, populated once in probeXtream and
    // re-used by the per-category walk so every toMovie/toSeries call can stamp
    // the human-readable group name on its row. Empty for non-XC sources.
    private var movieCategoryNames: Map<String, String> = emptyMap()
    private var seriesCategoryNames: Map<String, String> = emptyMap()
    private var xtreamItemsLoaded = false
    private var xtreamPlaylist: PlaylistEntity? = null

    private fun ensureXtreamProbe(playlist: PlaylistEntity) {
        if (xtreamProbeJob?.isActive == true) return
        xtreamPlaylist = playlist
        xtreamProbeJob = viewModelScope.launch { probeXtream(playlist) }
    }

    private enum class XcKind { MOVIE, SERIES }

    /**
     * Cheap init probe. A standard Xtream panel returns the whole library on an
     * unfiltered query, so that fills the grids directly (and there's nothing to
     * defer). Dispatcharr's XC bridge 500s / returns empty without a
     * category_id, so we only fetch the VOD + series category-id lists here --
     * enough to show the On Demand tab -- and defer the per-category walk to
     * [loadXtreamItemsIfNeeded].
     */
    private suspend fun probeXtream(playlist: PlaylistEntity) {
        val user = playlist.username
        val pass = playlist.password
        val base = playlistRepository.effectiveBaseUrl(playlist)
        if (user.isNullOrBlank() || pass == null) {
            _state.update {
                it.copy(
                    unsupportedSource = true,
                    movies = emptyList(), series = emptyList(),
                    isLoading = false, isLoadingSeries = false,
                    hasDeferredXtreamContent = false,
                )
            }
            return
        }
        // Mirror iOS VODService.xcMovies: ONE unfiltered full-library fetch is
        // the whole strategy -- a standard Xtream panel returns the entire VOD /
        // series library here in a single response. With the streaming decoder
        // (XtreamCodesApi.fetchAndMapArray) and the iOS-aligned idle timeout, a
        // large library completes in one fetch instead of truncating, so the
        // per-category walk below stays the rare last-resort (a bridge that gen
        // -uinely 500s / returns empty for the unfiltered query) rather than the
        // normal path. iOS never walks categories at all.
        val movieFast = runCatching { xtreamApi.getVodStreams(base, user, pass) }
            .onFailure { warnUnlessCancelled("XC getVodStreams failed", it) }.getOrDefault(emptyList())
        val seriesFast = runCatching { xtreamApi.getSeries(base, user, pass) }
            .onFailure { warnUnlessCancelled("XC getSeries failed", it) }.getOrDefault(emptyList())
        // Also fetch the full category lists (id + name) so each movie / series
        // can be tagged with its group display name -- the Manage Groups filter
        // sheet on the On Demand tab needs human-readable group names. Cheap
        // (a few dozen rows each), so we always fetch even when the unfiltered
        // probe succeeded. Stashed on the VM so the lazy per-category walk
        // (loadXtreamItemsIfNeeded) re-uses the same lookup.
        val movieCats = runCatching { xtreamApi.getVodCategories(base, user, pass) }
            .onFailure { warnUnlessCancelled("XC getVodCategories failed", it) }.getOrDefault(emptyList())
        val seriesCats = runCatching { xtreamApi.getSeriesCategories(base, user, pass) }
            .onFailure { warnUnlessCancelled("XC getSeriesCategories failed", it) }.getOrDefault(emptyList())
        movieCategoryNames = movieCats.associate { it.id to it.name }
        seriesCategoryNames = seriesCats.associate { it.id to it.name }
        if (movieFast.isNotEmpty()) {
            val movies = movieFast.map { it.toMovie(movieCategoryNames) }
            _state.update { it.copy(movies = movies, totalCount = movies.size) }
        }
        if (seriesFast.isNotEmpty()) {
            val series = seriesFast.map { it.toSeries(seriesCategoryNames) }
            _state.update { it.copy(series = series, seriesTotalCount = series.size) }
        }
        pendingMovieCats = if (movieFast.isEmpty()) movieCats.map { it.id } else emptyList()
        pendingSeriesCats = if (seriesFast.isEmpty()) seriesCats.map { it.id } else emptyList()
        // Nothing left to walk: a standard panel already filled above, or the
        // source genuinely has no VOD/series. Mark loaded so the lazy trigger
        // is a no-op.
        if (pendingMovieCats.isEmpty() && pendingSeriesCats.isEmpty()) xtreamItemsLoaded = true
        _state.update {
            it.copy(
                unsupportedSource = false,
                isLoading = false, isLoadingSeries = false,
                hasDeferredXtreamContent = pendingMovieCats.isNotEmpty() || pendingSeriesCats.isNotEmpty(),
            )
        }
        if (xtreamItemsLoaded) persistSnapshot(MediaSweep.Both)
    }

    /**
     * Walk the per-category lists captured by [probeXtream] and fill the grids.
     * Triggered once when the On Demand tab is opened. Movie + series category
     * fetches are INTERLEAVED (movie, series, movie, series, ...) so neither
     * sub-tab starves on the shared request gate. JSON parsing runs off the Main
     * dispatcher (see XtreamCodesApi) and state is flushed in batches rather than
     * once per category, so a large library doesn't ANR / churn the UI. Safe to
     * call repeatedly -- guarded by [xtreamItemsLoaded] and the active job.
     */
    fun loadXtreamItemsIfNeeded() {
        if (xtreamItemsLoaded || xtreamItemsJob?.isActive == true) return
        val movieCats = pendingMovieCats
        val seriesCats = pendingSeriesCats
        if (movieCats.isEmpty() && seriesCats.isEmpty()) return
        val playlist = xtreamPlaylist ?: return
        val user = playlist.username ?: return
        val pass = playlist.password ?: return
        xtreamItemsJob = viewModelScope.launch {
            val base = playlistRepository.effectiveBaseUrl(playlist)
            _state.update { it.copy(isLoading = movieCats.isNotEmpty(), isLoadingSeries = seriesCats.isNotEmpty()) }
            Log.i(TAG, "XC On Demand: enumerating ${movieCats.size} movie + ${seriesCats.size} series categories (interleaved)")
            val work = ArrayList<Pair<XcKind, String>>(movieCats.size + seriesCats.size)
            var mi = 0
            var si = 0
            while (mi < movieCats.size || si < seriesCats.size) {
                if (mi < movieCats.size) work += XcKind.MOVIE to movieCats[mi++]
                if (si < seriesCats.size) work += XcKind.SERIES to seriesCats[si++]
            }
            val movieAcc = LinkedHashMap<Int, DispatcharrVODMovie>()
            val seriesAcc = LinkedHashMap<Int, DispatcharrVODSeries>()
            // Push both lists at most every STATE_FLUSH_EVERY categories.
            // Rebuilding the full lists on every one of hundreds of categories is
            // O(n^2) work plus a grid recomposition each time -- the source of
            // the On Demand lag / crash on large libraries.
            fun flush() = _state.update {
                it.copy(
                    movies = movieAcc.values.toList(), totalCount = movieAcc.size,
                    series = seriesAcc.values.toList(), seriesTotalCount = seriesAcc.size,
                )
            }
            var done = 0
            coroutineScope {
                work.forEach { (kind, cat) ->
                    launch {
                        when (kind) {
                            XcKind.MOVIE -> {
                                val items = runCatching { xtreamApi.getVodStreams(base, user, pass, cat) }.getOrDefault(emptyList())
                                items.forEach { movieAcc[it.streamId] = it.toMovie(movieCategoryNames) }
                            }
                            XcKind.SERIES -> {
                                val items = runCatching { xtreamApi.getSeries(base, user, pass, cat) }.getOrDefault(emptyList())
                                items.forEach { seriesAcc[it.seriesId] = it.toSeries(seriesCategoryNames) }
                            }
                        }
                        done++
                        if (done % STATE_FLUSH_EVERY == 0) flush()
                    }
                }
            }
            flush()
            _state.update { it.copy(isLoading = false, isLoadingSeries = false) }
            xtreamItemsLoaded = true
            persistSnapshot(MediaSweep.Both)
        }
    }

    private suspend fun loadXtreamEpisodes(playlist: PlaylistEntity, seriesId: Int) {
        val user = playlist.username
        val pass = playlist.password
        if (user.isNullOrBlank() || pass == null) return
        val base = playlistRepository.effectiveBaseUrl(playlist)
        _state.update { it.copy(episodesLoadingFor = it.episodesLoadingFor + seriesId) }
        runCatching { xtreamApi.getSeriesEpisodes(base, user, pass, seriesId) }.fold(
            onSuccess = { eps ->
                val mapped = eps.map { e ->
                    DispatcharrVODEpisode(
                        id = e.id,
                        uuid = "$XC_EP_PREFIX${e.id}-${e.containerExtension}",
                        title = e.title,
                        seasonNumber = e.season,
                        episodeNumber = e.episodeNum,
                        plot = e.plot,
                        durationSecs = e.durationSecs,
                        // Episode still: the screen reads stillImageUrl from
                        // custom_properties.movie_image, so stash the XC image there.
                        customProperties = e.imageUrl?.let { img ->
                            com.aeriotv.android.core.network.VODCustomProps(movieImage = JsonPrimitive(img))
                        },
                    )
                }
                _state.update { st ->
                    st.copy(
                        episodesBySeries = st.episodesBySeries + (seriesId to mapped),
                        episodesLoadingFor = st.episodesLoadingFor - seriesId,
                        episodesErrorFor = st.episodesErrorFor - seriesId,
                    )
                }
            },
            onFailure = { t ->
                warnUnlessCancelled("XC getSeriesEpisodes($seriesId) failed", t)
                _state.update { st ->
                    st.copy(
                        episodesLoadingFor = st.episodesLoadingFor - seriesId,
                        episodesErrorFor = st.episodesErrorFor + (seriesId to (t.message ?: t::class.simpleName.orEmpty())),
                    )
                }
            },
        )
    }

    /** Decode a sentinel uuid ("<prefix><id>-<ext>") and build the XC URL. */
    private suspend fun resolveXtreamUrl(
        playlist: PlaylistEntity,
        uuid: String,
        prefix: String,
        build: (base: String, user: String, pass: String, id: Int, ext: String) -> String,
    ): Result<String> {
        val user = playlist.username
        val pass = playlist.password
        if (user.isNullOrBlank() || pass == null) {
            return Result.failure(IllegalStateException("Xtream credentials missing."))
        }
        val payload = uuid.removePrefix(prefix)
        val id = payload.substringBefore('-').toIntOrNull()
            ?: return Result.failure(IllegalStateException("Bad Xtream id in $uuid"))
        val ext = payload.substringAfter('-', "mp4").ifBlank { "mp4" }
        val base = playlistRepository.effectiveBaseUrl(playlist)
        return Result.success(build(base, user, pass, id, ext))
    }

    private companion object {
        private const val STARTUP_DEFER_MS = 6_000L
        /** Pause between catalog pages (Streamer 2026-09-03): the unpaced walk
         *  ran at ~7 pages/s across both passes and held the box at 30%+ CPU
         *  for minutes, which starved the tabs' rendering. */
        private const val WALK_PACE_MS = 250L
        const val TAG = "OnDemandViewModel"
        const val XC_MOVIE_PREFIX = "xc-movie-"
        const val XC_EP_PREFIX = "xc-ep-"
        // Batch size for XC enumeration state flushes (see loadXtreamItemsIfNeeded).
        const val STATE_FLUSH_EVERY = 16

        // In-flight cap for the per-copy measurement fetches (see
        // loadMovieProviderMedia). Each uncached copy can cost the server an
        // upstream fetch, so a wide fan-out would hammer the provider.
        const val PROVIDER_MEDIA_CONCURRENCY = 3

        /**
         * Size of the eagerly-walked head of the VOD library. A large Dispatcharr
         * provider can expose 30k+ movies (340+ pages) plus thousands of series;
         * walking the whole library on load fired ~420 back-to-back requests,
         * ballooned the Dalvik heap past 90MB, and starved the EPG + UI for
         * minutes (Z Fold 5 field report: page 90/344 after 2 min, EPG never
         * painting). We now load this browsable head eagerly and fetch the rest
         * lazily on scroll via loadMoreMovies()/loadMoreSeries(); search reaches
         * the full library server-side regardless. 100 rows/page, so ~1,000
         * movies + ~1,000 series up front.
         */
        const val MAX_EAGER_VOD_PAGES = 10

        /**
         * Total VOD rows loaded up front in the per-category fetch path, shared
         * across all enabled categories (each category gets a page-aligned fair
         * share). Mirrors iOS StreamingAPIs totalCap = 5000. The legacy
         * unfiltered fallback still uses MAX_EAGER_VOD_PAGES.
         */
        // Raised from 5000 (2026-09-08): a real account holds 5,044 movies and
        // the cap cut the last of them. Raised again 2026-09-09: with a
        // provider re-enabled the same account holds 10k+ of each kind, and
        // an alphabetical walk that fills the cap on its first categories
        // ("AL: ..." held 10,000 series alone) hides every later group. Per
        // category the walk stops at VOD_PER_CATEGORY_CAP (Apple parity:
        // StreamingAPIs.vodPaginationItemCap = 5,000 per call); the total
        // only bounds memory. Rows are small; JSON decode is off-main.
        const val VOD_TOTAL_CAP = 40_000

        /** Rows walked per category before moving on (Apple: 5,000 per call). */
        const val VOD_PER_CATEGORY_CAP = 5_000

        /** Debounce before a keystroke fires a server-side VOD search. */
        const val SEARCH_DEBOUNCE_MS = 300L

        /** Page size for the one-shot Known For fallback search. The query
         *  is an exact display title, so a small page is plenty. */
        const val KNOWN_FOR_SEARCH_PAGE_SIZE = 25
    }

    /** VOD category fetches are cancelled wholesale whenever the user leaves
     *  On Demand mid-load; each cancelled job used to log a full
     *  JobCancellationException stack at W (864 lines in one release-build
     *  session, 2026-09-01). Cancellation is not a failure: stay silent. */
    private fun warnUnlessCancelled(message: String, t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) return
        // Class and message inline: Android prints no stack for some network
        // exceptions, which hid the cause of a whole class of category
        // failures (2026-09-08).
        Log.w(TAG, "$message: ${t::class.java.simpleName}: ${t.message}", t)
    }

}
