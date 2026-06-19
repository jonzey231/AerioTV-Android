package com.aeriotv.android.feature.ondemand

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.network.DispatcharrAuthBroker
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.DispatcharrVODEpisode
import com.aeriotv.android.core.network.DispatcharrVODLogo
import com.aeriotv.android.core.network.DispatcharrVODMovie
import com.aeriotv.android.core.network.DispatcharrVODProviderInfo
import com.aeriotv.android.core.network.DispatcharrVODSeries
import com.aeriotv.android.core.network.TMDBService
import com.aeriotv.android.core.network.TmdbCredits
import com.aeriotv.android.core.network.TmdbDetails
import com.aeriotv.android.core.network.TmdbKnownForItem
import com.aeriotv.android.core.network.TmdbPersonBio
import com.aeriotv.android.core.network.XtreamCodesApi
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val movies: List<DispatcharrVODMovie> = emptyList(),
        val totalCount: Int = 0,
        val searchQuery: String = "",
        // Server-side search results (Dispatcharr `?search=`). `visible` renders
        // these whenever the query is non-blank, so search reaches the WHOLE
        // library, not just the pages walked into `movies` so far. Empty when
        // not searching.
        val searchResults: List<DispatcharrVODMovie> = emptyList(),
        val isSearching: Boolean = false,
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

    init {
        refresh()
        refreshSeries()
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
        _state.value = UiState()
    }

    /**
     * Movies search. On Dispatcharr this queries the server (`?search=`) so a
     * match anywhere in the full library is found even if its page was never
     * walked into `movies`. Non-Dispatcharr sources keep the instant client
     * filter (their whole library is already loaded). Debounced so a fast burst
     * of typing only fires the final query.
     */
    fun setSearchQuery(value: String) {
        _state.update { it.copy(searchQuery = value) }
        searchMoviesJob?.cancel()
        val q = value.trim()
        if (q.isEmpty()) {
            _state.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        searchMoviesJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            val playlist = playlistRepository.activePlaylist()
            val sourceType = playlist?.sourceType?.let { SourceType.entries.firstOrNull { st -> st.name == it } }
            val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                    sourceType == SourceType.DispatcharrUserPass
            if (playlist == null || !isDispatcharr || playlist.apiKey.isNullOrBlank()) {
                _state.update { st ->
                    st.copy(
                        searchResults = st.movies.filter { it.displayName.contains(q, ignoreCase = true) },
                        isSearching = false,
                    )
                }
                return@launch
            }
            _state.update { it.copy(isSearching = true) }
            // Search results need the same group stamp as the browse list so
            // the Manage Groups filter applies identically to both.
            ensureDispatcharrCategories(playlist)
            val base = playlist.urlString.trimEnd('/')
            val url = "$base/api/vod/movies/?search=" +
                    java.net.URLEncoder.encode(q, "UTF-8") + "&page_size=100"
            val page = runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getVODMoviesPage(url, key)
                }
            }.getOrNull()
            // Discard a stale response if the user kept typing past this query.
            if (_state.value.searchQuery.trim() != q) return@launch
            _state.update { it.copy(searchResults = page?.results?.map(::stampMovieGroup) ?: emptyList(), isSearching = false) }
        }
    }

    /** Series search. Mirrors [setSearchQuery] against `/api/vod/series/`. */
    fun setSeriesSearchQuery(value: String) {
        _state.update { it.copy(seriesSearchQuery = value) }
        searchSeriesJob?.cancel()
        val q = value.trim()
        if (q.isEmpty()) {
            _state.update { it.copy(seriesSearchResults = emptyList(), isSearchingSeries = false) }
            return
        }
        searchSeriesJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            val playlist = playlistRepository.activePlaylist()
            val sourceType = playlist?.sourceType?.let { SourceType.entries.firstOrNull { st -> st.name == it } }
            val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                    sourceType == SourceType.DispatcharrUserPass
            if (playlist == null || !isDispatcharr || playlist.apiKey.isNullOrBlank()) {
                _state.update { st ->
                    st.copy(
                        seriesSearchResults = st.series.filter { it.displayName.contains(q, ignoreCase = true) },
                        isSearchingSeries = false,
                    )
                }
                return@launch
            }
            _state.update { it.copy(isSearchingSeries = true) }
            // Same group stamp as the browse list; see setSearchQuery.
            ensureDispatcharrCategories(playlist)
            val base = playlist.urlString.trimEnd('/')
            val url = "$base/api/vod/series/?search=" +
                    java.net.URLEncoder.encode(q, "UTF-8") + "&page_size=100"
            val page = runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getVODSeriesPage(url, key)
                }
            }.getOrNull()
            if (_state.value.seriesSearchQuery.trim() != q) return@launch
            _state.update { it.copy(seriesSearchResults = page?.results?.map(::stampSeriesGroup) ?: emptyList(), isSearchingSeries = false) }
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
                    Log.w(TAG, "VOD movies load-more failed", t)
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
                    Log.w(TAG, "VOD series load-more failed", t)
                    _state.update { it.copy(isLoadingMoreSeries = false) }
                },
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
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
            if (playlist != null && !playlist.vodEnabled) {
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
            val perCatCap = maxOf((totalCap / 100 / maxOf(cats.size, 1)) * 100, 100)
            val merged = mutableListOf<DispatcharrVODMovie>()
            val seen = HashSet<String>()
            var firstPainted = false
            for (catName in cats) {
                if (merged.size >= totalCap) break
                var nextUrl: String? = null
                var fetchedForCat = 0
                // First page for this category.
                val firstPage = runCatching {
                    dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                        dispatcharrClient.getVODMoviesByCategory(playlist.urlString, key, catName)
                    }
                }.onFailure { Log.w(TAG, "VOD movies cat='$catName' failed; continuing", it) }.getOrNull()
                if (firstPage != null) {
                    firstPage.results.forEach { m ->
                        if (seen.add(m.uuid)) { merged += m.copy(categoryName = catName); fetchedForCat++ }
                    }
                    nextUrl = firstPage.next
                    if (!firstPainted) {
                        firstPainted = true
                        _state.update { it.copy(isLoading = false, movies = merged.toList(), totalCount = merged.size, moviesNextCursor = null, error = null) }
                    } else {
                        _state.update { it.copy(movies = merged.toList(), totalCount = merged.size, moviesNextCursor = null) }
                    }
                }
                // Walk this category's cursor up to its fair share.
                while (nextUrl != null && fetchedForCat < perCatCap && merged.size < totalCap) {
                    val captured = nextUrl
                    val p = runCatching {
                        dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                            dispatcharrClient.getVODMoviesPage(captured, key)
                        }
                    }.onFailure { Log.w(TAG, "VOD movies cat='$catName' next-page failed; stopping cat", it) }.getOrNull()
                    if (p == null) break
                    p.results.forEach { m ->
                        if (seen.add(m.uuid)) { merged += m.copy(categoryName = catName); fetchedForCat++ }
                    }
                    nextUrl = p.next
                    _state.update { it.copy(movies = merged.toList(), totalCount = merged.size, moviesNextCursor = null) }
                }
            }
            // Ensure the spinner clears even if every category returned empty.
            _state.update { it.copy(isLoading = false, movies = merged.toList(), totalCount = if (merged.isEmpty()) it.totalCount else merged.size, moviesNextCursor = null) }
        }
    }

    /**
     * Legacy unfiltered movie load: first-page paint + a capped `next`-cursor
     * walk (Audit task #42). Used as the fallback when the category endpoint
     * returned nothing, so per-category fetch is impossible. Stamps via
     * stampMovieGroup (a no-op/Uncategorized when there are no categories).
     */
    private suspend fun loadDispatcharrMoviesUnfiltered(playlist: PlaylistEntity) {
        runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getVODMoviesFirstPage(playlist.urlString, key)
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
                        Log.w(TAG, "VOD movies next-page fetch failed; stopping", t)
                        nextUrl = null
                    }
                }
            },
            onFailure = { t ->
                Log.w(TAG, "getVODMovies failed", t)
                _state.update { it.copy(isLoading = false, error = t.message ?: t::class.simpleName) }
            },
        )
    }

    fun refreshSeries() {
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist()
            val sourceType = playlist?.sourceType?.let { SourceType.entries.firstOrNull { st -> st.name == it } }
            val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                    sourceType == SourceType.DispatcharrUserPass
            // Same opt-out gate as refresh() above for the series side; see
            // the longer comment there. Belt-and-suspenders with MainScaffold's
            // hasVodContent.
            if (playlist != null && !playlist.vodEnabled) {
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
            val perCatCap = maxOf((totalCap / 100 / maxOf(cats.size, 1)) * 100, 100)
            val merged = mutableListOf<DispatcharrVODSeries>()
            val seen = HashSet<Int>()
            var firstPainted = false
            for (catName in cats) {
                if (merged.size >= totalCap) break
                var nextUrl: String? = null
                var fetchedForCat = 0
                val firstPage = runCatching {
                    dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                        dispatcharrClient.getVODSeriesByCategory(playlist.urlString, key, catName)
                    }
                }.onFailure { Log.w(TAG, "VOD series cat='$catName' failed; continuing", it) }.getOrNull()
                if (firstPage != null) {
                    firstPage.results.forEach { s ->
                        if (seen.add(s.id)) { merged += s.copy(categoryName = catName); fetchedForCat++ }
                    }
                    nextUrl = firstPage.next
                    if (!firstPainted) {
                        firstPainted = true
                        _state.update { it.copy(isLoadingSeries = false, series = merged.toList(), seriesTotalCount = merged.size, seriesNextCursor = null, seriesError = null) }
                    } else {
                        _state.update { it.copy(series = merged.toList(), seriesTotalCount = merged.size, seriesNextCursor = null) }
                    }
                }
                while (nextUrl != null && fetchedForCat < perCatCap && merged.size < totalCap) {
                    val captured = nextUrl
                    val p = runCatching {
                        dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                            dispatcharrClient.getVODSeriesPage(captured, key)
                        }
                    }.onFailure { Log.w(TAG, "VOD series cat='$catName' next-page failed; stopping cat", it) }.getOrNull()
                    if (p == null) break
                    p.results.forEach { s ->
                        if (seen.add(s.id)) { merged += s.copy(categoryName = catName); fetchedForCat++ }
                    }
                    nextUrl = p.next
                    _state.update { it.copy(series = merged.toList(), seriesTotalCount = merged.size, seriesNextCursor = null) }
                }
            }
            _state.update { it.copy(isLoadingSeries = false, series = merged.toList(), seriesTotalCount = if (merged.isEmpty()) it.seriesTotalCount else merged.size, seriesNextCursor = null) }
        }
    }

    /** Series counterpart of [loadDispatcharrMoviesUnfiltered]; dedup by id. */
    private suspend fun loadDispatcharrSeriesUnfiltered(playlist: PlaylistEntity) {
        runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getVODSeriesFirstPage(playlist.urlString, key)
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
                        Log.w(TAG, "VOD series next-page fetch failed; stopping", t)
                        nextUrl = null
                    }
                }
            },
            onFailure = { t ->
                Log.w(TAG, "getVODSeries failed", t)
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
        val categories = runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getVODCategories(playlist.urlString, key)
            }
        }.onFailure { Log.w(TAG, "getVODCategories failed; proceeding without groups", it) }
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
        _state.update {
            it.copy(
                movieGroupNames = movieCats.map { c -> c.name }.distinct().sorted(),
                seriesGroupNames = seriesCats.map { c -> c.name }.distinct().sorted(),
            )
        }
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

    fun movieById(id: Int): DispatcharrVODMovie? =
        _state.value.movies.firstOrNull { it.id == id }

    fun movieByUuid(uuid: String): DispatcharrVODMovie? =
        _state.value.movies.firstOrNull { it.uuid == uuid }
            ?: _state.value.searchResults.firstOrNull { it.uuid == uuid }

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
        val base = playlist.urlString.trimEnd('/')
        val url = "$base/api/vod/movies/?search=" +
                java.net.URLEncoder.encode(item.title, "UTF-8") +
                "&page_size=$KNOWN_FOR_SEARCH_PAGE_SIZE&page=1"
        val results = runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getVODMoviesPage(url, key)
            }
        }.onFailure { Log.w(TAG, "KnownFor movie search failed", it) }
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
        val base = playlist.urlString.trimEnd('/')
        val url = "$base/api/vod/series/?search=" +
                java.net.URLEncoder.encode(item.title, "UTF-8") +
                "&page_size=$KNOWN_FOR_SEARCH_PAGE_SIZE&page=1"
        val results = runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getVODSeriesPage(url, key)
            }
        }.onFailure { Log.w(TAG, "KnownFor series search failed", it) }
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
            _state.update { it.copy(movieProviderInfoLoading = it.movieProviderInfoLoading + movieId) }
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getMovieProviderInfo(playlist.urlString, key, movieId)
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
                    Log.w(TAG, "getMovieProviderInfo($movieId) failed", t)
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
            _state.update { it.copy(seriesProviderInfoLoading = it.seriesProviderInfoLoading + seriesId) }
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getSeriesProviderInfo(playlist.urlString, key, seriesId)
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
                    Log.w(TAG, "getSeriesProviderInfo($seriesId) failed", t)
                    _state.update { it.copy(seriesProviderInfoLoading = it.seriesProviderInfoLoading - seriesId) }
                },
            )
        }
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
            _state.update { it.copy(episodesLoadingFor = it.episodesLoadingFor + seriesId) }
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getSeriesEpisodesFirstPage(playlist.urlString, key, seriesId)
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
                    Log.w(TAG, "getSeriesEpisodes($seriesId) failed", t)
                    _state.update { st ->
                        st.copy(
                            episodesLoadingFor = st.episodesLoadingFor - seriesId,
                            episodesErrorFor = st.episodesErrorFor +
                                    (seriesId to (t.message ?: t::class.simpleName.orEmpty())),
                        )
                    }
                },
            )
        }
    }

    /** Same pattern as resolveMovieUrl but for an episode proxy URL. */
    suspend fun resolveEpisodeUrl(episodeUuid: String, streamId: Int?): Result<String> {
        val playlist = playlistRepository.activePlaylist()
            ?: return Result.failure(IllegalStateException("No playlist loaded."))
        // Xtream episodes carry a deterministic URL encoded in the sentinel
        // uuid ("xc-ep-<id>-<ext>"); no network round-trip needed.
        if (episodeUuid.startsWith(XC_EP_PREFIX)) {
            return resolveXtreamUrl(playlist, episodeUuid, XC_EP_PREFIX) { base, u, p, id, ext ->
                xtreamApi.episodeStreamUrl(base, u, p, id, ext)
            }
        }
        if (playlist.apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Active source is not Dispatcharr-backed."))
        }
        return runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.resolveVODEpisodeStreamUrl(
                    baseUrl = playlist.urlString,
                    apiKey = key,
                    episodeUuid = episodeUuid,
                    streamId = streamId,
                )
            }
        }
    }

    /**
     * Resolves the redirect-bound proxy URL to the session-bound playback URL
     * for a movie. Returns a Result so the caller can surface failures via
     * Toast / inline error rather than landing on a half-loaded player.
     */
    suspend fun resolveMovieUrl(movieUuid: String): Result<String> {
        val playlist = playlistRepository.activePlaylist()
            ?: return Result.failure(IllegalStateException("No playlist loaded."))
        // Xtream movies carry a deterministic URL encoded in the sentinel
        // uuid ("xc-movie-<id>-<ext>"); build it directly.
        if (movieUuid.startsWith(XC_MOVIE_PREFIX)) {
            return resolveXtreamUrl(playlist, movieUuid, XC_MOVIE_PREFIX) { base, u, p, id, ext ->
                xtreamApi.vodStreamUrl(base, u, p, id, ext)
            }
        }
        if (playlist.apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Active source is not Dispatcharr-backed."))
        }
        val movie = _state.value.movies.firstOrNull { it.uuid == movieUuid }
        return runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.resolveVODStreamUrl(
                    baseUrl = playlist.urlString,
                    apiKey = key,
                    movieUuid = movieUuid,
                    streamId = movie?.firstStreamId,
                )
            }
        }
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
        val base = playlist.urlString
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
            .onFailure { Log.w(TAG, "XC getVodStreams failed", it) }.getOrDefault(emptyList())
        val seriesFast = runCatching { xtreamApi.getSeries(base, user, pass) }
            .onFailure { Log.w(TAG, "XC getSeries failed", it) }.getOrDefault(emptyList())
        // Also fetch the full category lists (id + name) so each movie / series
        // can be tagged with its group display name -- the Manage Groups filter
        // sheet on the On Demand tab needs human-readable group names. Cheap
        // (a few dozen rows each), so we always fetch even when the unfiltered
        // probe succeeded. Stashed on the VM so the lazy per-category walk
        // (loadXtreamItemsIfNeeded) re-uses the same lookup.
        val movieCats = runCatching { xtreamApi.getVodCategories(base, user, pass) }
            .onFailure { Log.w(TAG, "XC getVodCategories failed", it) }.getOrDefault(emptyList())
        val seriesCats = runCatching { xtreamApi.getSeriesCategories(base, user, pass) }
            .onFailure { Log.w(TAG, "XC getSeriesCategories failed", it) }.getOrDefault(emptyList())
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
        val base = playlist.urlString
        xtreamItemsJob = viewModelScope.launch {
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
        }
    }

    private suspend fun loadXtreamEpisodes(playlist: PlaylistEntity, seriesId: Int) {
        val user = playlist.username
        val pass = playlist.password
        if (user.isNullOrBlank() || pass == null) return
        _state.update { it.copy(episodesLoadingFor = it.episodesLoadingFor + seriesId) }
        runCatching { xtreamApi.getSeriesEpisodes(playlist.urlString, user, pass, seriesId) }.fold(
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
                            JsonObject(mapOf("movie_image" to JsonPrimitive(img)))
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
                Log.w(TAG, "XC getSeriesEpisodes($seriesId) failed", t)
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
    private fun resolveXtreamUrl(
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
        return Result.success(build(playlist.urlString, user, pass, id, ext))
    }

    private companion object {
        const val TAG = "OnDemandViewModel"
        const val XC_MOVIE_PREFIX = "xc-movie-"
        const val XC_EP_PREFIX = "xc-ep-"
        // Batch size for XC enumeration state flushes (see loadXtreamItemsIfNeeded).
        const val STATE_FLUSH_EVERY = 16

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
        const val VOD_TOTAL_CAP = 5000

        /** Debounce before a keystroke fires a server-side VOD search. */
        const val SEARCH_DEBOUNCE_MS = 300L

        /** Page size for the one-shot Known For fallback search. The query
         *  is an exact display title, so a small page is plenty. */
        const val KNOWN_FOR_SEARCH_PAGE_SIZE = 25
    }
}
