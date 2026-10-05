package com.aeriotv.android.feature.ondemand

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.app.AppSettleGate
import com.aeriotv.android.core.app.BackgroundSweep
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.VodLearnedStream
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.data.vod.VodCatalogStore
import com.aeriotv.android.core.network.DispatcharrError
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
import com.aeriotv.android.core.network.TmdbArtCache
import com.aeriotv.android.core.network.TmdbArtEntry
import com.aeriotv.android.core.network.TmdbCredits
import com.aeriotv.android.core.network.TmdbEpisodeInfo
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
import com.aeriotv.android.core.data.db.entity.dispatcharrCanViewVod
import com.aeriotv.android.core.data.db.entity.dispatcharrCanViewSeries
import com.aeriotv.android.core.data.db.entity.capabilitiesNeedProbe
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aeriotv.android.feature.movies.cleanArtTitle
import com.aeriotv.android.feature.movies.displayTitle
import com.aeriotv.android.feature.movies.tmdbArtKey
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
    private val tmdbArtCache: TmdbArtCache,
    private val hiddenTitlesStore: com.aeriotv.android.core.preferences.HiddenTitlesStore,
    private val settleGate: AppSettleGate,
    private val catalogStore: VodCatalogStore,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        /**
         * A catalog SWEEP is walking the server right now (Logan 2026-09-16).
         * Separate from [isLoading], which drops as soon as the first page has
         * painted: the Movies / TV Shows "Updating" indicator is driven from
         * THIS, so it stays up for as long as the sweep is actually running on
         * both the phone and TV.
         */
        val sweepingMovies: Boolean = false,
        val sweepingSeries: Boolean = false,
        val error: String? = null,
        // ALWAYS EMPTY since GH #109: the library lives in the Room catalog
        // (VodCatalogStore) and the grids read it through [library]. Kept only
        // for the legacy OnDemandTabContent, which splitVod never reaches.
        // Look titles up with movieByUuid / seriesById instead.
        val movies: List<DispatcharrVODMovie> = emptyList(),
        // Catalog rows the detail screens, Continue Watching and the Watchlist
        // asked for by key, read from Room on demand (bounded, newest kept).
        val catalogMovies: Map<String, DispatcharrVODMovie> = emptyMap(),
        val catalogSeries: Map<Int, DispatcharrVODSeries> = emptyMap(),
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
        // Always empty since GH #109; see [movies].
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
        // Per-half permission verdicts (Capability.CanViewVod / CanViewSeries).
        // These are INDEPENDENT of [unsupportedSource], which means "this
        // source does no VOD at all". Denying only the movies half must leave
        // the series half fully working (tab, library, rails, search), so a
        // denial never touches the shared flag; it only raises its own.
        // Re-derived at the top of every sweep, so a capability flipped back to
        // allowed on the server clears it without a reinstall.
        val moviesDenied: Boolean = false,
        val seriesDenied: Boolean = false,
    ) {
        // While searching, render the server-side results (full library). While
        // browsing, render the progressively-paginated list. The search request
        // itself lives in setSearchQuery(); these getters just pick the source.
        // A denied half never renders rows anywhere (grid, rails, search
        // results, Continue Watching, multiview add), even if a cached list or
        // an in-flight search result is still in state.
        val visible: List<DispatcharrVODMovie> get() = when {
            moviesDenied -> emptyList()
            searchQuery.isBlank() -> movies
            else -> searchResults
        }
        val visibleSeries: List<DispatcharrVODSeries> get() = when {
            seriesDenied -> emptyList()
            seriesSearchQuery.isBlank() -> series
            else -> seriesSearchResults
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ---- Derived library cache (Logan 2026-09-11: "the libraries for any of
    // the three tabs are not being cached so they appear to be reloading every
    // time they're opened"). tvOS never rebuilds on a tab return
    // (tvos_movies_spec 1.3), so the built list lives HERE, not in a
    // composable that is only reached when the tab is first opened.
    //
    // Since GH #109 the list is a window over the Room catalog: the query
    // sorts and filters in SQL and keeps only the row ids and rail buckets in
    // memory, and rows are read a window at a time as the grid scrolls. The
    // 40k MediaItem list this replaced was ~80 MB on its own at 130k titles.
    data class MediaLibrary(
        val items: List<com.aeriotv.android.feature.movies.MediaItem> = emptyList(),
        val letters: Set<Char> = emptySet(),
        /** First grid index for a rail letter, without reading rows. */
        val indexOfLetter: (Char) -> Int = { -1 },
        /** Grid index of a MediaItem key (the TV return-from-detail restore). */
        val indexOfKey: (Any) -> Int = { -1 },
    )

    /** The inputs a built library depends on. [version] moves when the
     *  catalog behind [catalogKey] changes (a sweep page, a finished sweep). */
    private data class LibrarySpec(
        val catalogKey: String?,
        val version: Int,
        val hidden: Set<String>,
        val hiddenTitles: Set<String>,
        val genre: String?,
        val sort: com.aeriotv.android.feature.movies.MediaSortOrder,
    )

    private val moviesGenre = MutableStateFlow<String?>(null)
    private val seriesGenre = MutableStateFlow<String?>(null)
    private val _moviesLibrary = MutableStateFlow(MediaLibrary())
    private val _seriesLibrary = MutableStateFlow(MediaLibrary())
    private val _moviesLibraryPending = MutableStateFlow(true)
    private val _seriesLibraryPending = MutableStateFlow(true)

    /** The filtered + sorted library and its rail letters for a tab. */
    fun library(isMovie: Boolean): StateFlow<MediaLibrary> =
        if (isMovie) _moviesLibrary.asStateFlow() else _seriesLibrary.asStateFlow()

    /** True while a rebuild is in flight; the tab keeps the previous list up. */
    fun libraryPending(isMovie: Boolean): StateFlow<Boolean> =
        if (isMovie) _moviesLibraryPending.asStateFlow() else _seriesLibraryPending.asStateFlow()

    /** Genre pill selection. Owned here so it survives with the built list. */
    fun selectedGenre(isMovie: Boolean): StateFlow<String?> =
        if (isMovie) moviesGenre.asStateFlow() else seriesGenre.asStateFlow()

    fun setSelectedGenre(isMovie: Boolean, genre: String?) {
        if (isMovie) moviesGenre.value = genre else seriesGenre.value = genre
    }

    private var lastMoviesSpec: LibrarySpec? = null
    private var lastSeriesSpec: LibrarySpec? = null

    // ---- Catalog identity and change signal (GH #109). The key is the
    // active playlist's snapshot identity while that kind is enabled and
    // supported, null otherwise (the grid is then empty). The version is
    // bumped, throttled, as sweep pages land so the grid fills progressively
    // without a rebuild per page, and once more when a sweep ends.
    private val movieCatalogKey = MutableStateFlow<String?>(null)
    private val seriesCatalogKey = MutableStateFlow<String?>(null)
    private val movieCatalogVersion = MutableStateFlow(0)
    private val seriesCatalogVersion = MutableStateFlow(0)
    @Volatile private var lastMovieCatalogBumpAt = 0L
    @Volatile private var lastSeriesCatalogBumpAt = 0L

    private fun catalogKeyFlow(isMovie: Boolean) = if (isMovie) movieCatalogKey else seriesCatalogKey

    private fun kindOf(isMovie: Boolean) = if (isMovie) VodCatalogStore.KIND_MOVIE else VodCatalogStore.KIND_SERIES

    /**
     * The catalog behind a tab changed. Throttled to one rebuild per
     * [CATALOG_PUBLISH_THROTTLE_MS] unless [force] (the end of a sweep): a
     * rebuild re-runs the sorted id query, which is cheap next to the
     * per-page full-list copies it replaces but not free on an Onn box.
     */
    private fun catalogChanged(isMovie: Boolean, force: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = if (isMovie) lastMovieCatalogBumpAt else lastSeriesCatalogBumpAt
        if (!force && now - last < CATALOG_PUBLISH_THROTTLE_MS) return
        if (isMovie) lastMovieCatalogBumpAt = now else lastSeriesCatalogBumpAt = now
        (if (isMovie) movieCatalogVersion else seriesCatalogVersion).update { it + 1 }
        viewModelScope.launch {
            catalogMisses.clear()
            publishCatalogCount(isMovie)
        }
    }

    /** Header counts and tab presence read the stored title count. */
    private suspend fun publishCatalogCount(isMovie: Boolean) {
        val key = catalogKeyFlow(isMovie).value
        val n = if (key == null) 0 else catalogStore.count(key, kindOf(isMovie))
        _state.update { if (isMovie) it.copy(totalCount = n) else it.copy(seriesTotalCount = n) }
    }

    // On-demand single-title reads for movieByUuid / seriesById. A key is
    // looked up once per catalog change: a miss is remembered so a
    // recomposition that asks again does not re-query. Main-thread confined.
    private val catalogLookups = HashSet<String>()
    private val catalogMisses = HashSet<String>()

    private fun requestCatalogMovie(uuid: String) {
        val key = movieCatalogKey.value ?: return
        val tag = "m:$uuid"
        if (tag in catalogMisses || !catalogLookups.add(tag)) return
        viewModelScope.launch {
            try {
                val m = catalogStore.movie(key, uuid)
                if (m == null) catalogMisses += tag
                else _state.update { it.copy(catalogMovies = (it.catalogMovies + (uuid to m)).newest(CATALOG_LOOKUP_CACHE)) }
            } finally { catalogLookups.remove(tag) }
        }
    }

    private fun requestCatalogSeries(id: Int) {
        val key = seriesCatalogKey.value ?: return
        val tag = "s:$id"
        if (tag in catalogMisses || !catalogLookups.add(tag)) return
        viewModelScope.launch {
            try {
                val s = catalogStore.series(key, id)
                if (s == null) catalogMisses += tag
                else _state.update { it.copy(catalogSeries = (it.catalogSeries + (id to s)).newest(CATALOG_LOOKUP_CACHE)) }
            } finally { catalogLookups.remove(tag) }
        }
    }

    /** The last [n] entries of an insertion-ordered map. */
    private fun <K, V> Map<K, V>.newest(n: Int): Map<K, V> =
        if (size <= n) this else entries.drop(size - n).associate { it.toPair() }

    // Resolved hero backdrops, kept here so a tab that is torn down and shown
    // again does not re-resolve every card (tvOS holds them for the session).
    private val _heroBackdrops = MutableStateFlow<Map<String, String?>>(emptyMap())
    val heroBackdrops: StateFlow<Map<String, String?>> = _heroBackdrops.asStateFlow()

    fun putHeroBackdrop(key: String, url: String?) {
        if (_heroBackdrops.value.containsKey(key) && _heroBackdrops.value[key] == url) return
        _heroBackdrops.update { it + (key to url) }
    }

    fun heroBackdropResolved(key: String): Boolean = _heroBackdrops.value.containsKey(key)

    // ---- Hidden titles (Logan 2026-09-14: long press a poster to hide it).
    // Per playlist, in its own DataStore alongside the watchlist. The set
    // feeds the library pipeline, so a hidden title leaves every grid, shelf
    // and search result at once.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val hiddenTitles: StateFlow<Set<String>> =
        playlistRepository.observeActiveId()
            .flatMapLatest { hiddenTitlesStore.observe(hiddenScope()) }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    private suspend fun hiddenScope(): String? = playlistRepository.activePlaylist()
        ?.let(com.aeriotv.android.core.preferences.WatchlistStore::scopeFor)

    /** Hide the title, or unhide it when it is already hidden. */
    fun toggleHidden(key: String) {
        viewModelScope.launch {
            val scope = hiddenScope()
            if (key in hiddenTitles.value) hiddenTitlesStore.unhide(key, scope)
            else hiddenTitlesStore.hide(key, scope)
        }
    }

    // The Hidden category is a row in the Filter list like any other
    // category: unchecked until the user checks it, and only offered while
    // something is hidden. Its state is per playlist (AppPreferences).
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun hiddenCategoryShownFlow(isMovie: Boolean): StateFlow<Boolean> =
        playlistRepository.observeActiveId()
            .flatMapLatest { _ ->
                // Same token AppPreferences stores for a playlist with no host.
                val scope = hiddenScope() ?: ANY_PLAYLIST_SCOPE
                (if (isMovie) appPreferences.hiddenCategoryShownMovies else appPreferences.hiddenCategoryShownSeries)
                    .map { scope in it }
            }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    private val moviesHiddenCategoryShown by lazy { hiddenCategoryShownFlow(true) }
    private val seriesHiddenCategoryShown by lazy { hiddenCategoryShownFlow(false) }

    /** True when the Filter list's "Hidden" row is checked for this tab. */
    fun hiddenCategoryShown(isMovie: Boolean): StateFlow<Boolean> =
        if (isMovie) moviesHiddenCategoryShown else seriesHiddenCategoryShown

    fun setHiddenCategoryShown(isMovie: Boolean, shown: Boolean) {
        viewModelScope.launch {
            appPreferences.setHiddenCategoryShown(isMovie, hiddenScope(), shown)
            if (!shown && selectedGenre(isMovie).value == HIDDEN_CATEGORY) setSelectedGenre(isMovie, null)
        }
    }

    private fun startLibraryPipeline(isMovie: Boolean) {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                kotlinx.coroutines.flow.combine(catalogKeyFlow(isMovie), if (isMovie) movieCatalogVersion else seriesCatalogVersion) { k, v -> k to v },
                if (isMovie) appPreferences.hiddenMovieGroups else appPreferences.hiddenSeriesGroups,
                if (isMovie) appPreferences.moviesSortOrder else appPreferences.seriesSortOrder,
                if (isMovie) moviesGenre else seriesGenre,
                hiddenTitles,
            ) { catalog, hidden, sortWire, genre, hiddenKeys ->
                LibrarySpec(
                    catalog.first, catalog.second, hidden, hiddenKeys, genre,
                    com.aeriotv.android.feature.movies.MediaSortOrder.fromWire(sortWire),
                )
            }.collectLatest { spec ->
                if (spec == (if (isMovie) lastMoviesSpec else lastSeriesSpec)) return@collectLatest
                val pending = if (isMovie) _moviesLibraryPending else _seriesLibraryPending
                pending.value = true
                val built = runCatching { buildLibrary(isMovie, spec) }
                    .onFailure { warnUnlessCancelled("VOD library query failed", it) }
                    .getOrNull()
                if (built != null) {
                    if (isMovie) { lastMoviesSpec = spec; _moviesLibrary.value = built }
                    else { lastSeriesSpec = spec; _seriesLibrary.value = built }
                }
                pending.value = false
            }
        }
    }

    /** Runs the catalog query on the IO pool; nothing here touches main. */
    private suspend fun buildLibrary(isMovie: Boolean, spec: LibrarySpec): MediaLibrary {
        val key = spec.catalogKey ?: return MediaLibrary()
        val list = catalogStore.buildLibrary(
            VodCatalogStore.LibraryQuery(
                playlistKey = key, kind = kindOf(isMovie), hiddenGroups = spec.hidden,
                hiddenTitleKeys = spec.hiddenTitles, genre = spec.genre, onlyHiddenToken = HIDDEN_CATEGORY,
                sort = spec.sort,
            ),
        )
        val letters = withContext(Dispatchers.Default) { list.letters }
        return MediaLibrary(list, letters, list::indexOfLetter, list::indexOfKey)
    }

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
            // Open from the stored catalog at once; re-sweep the provider only
            // when a kind's last finished sweep is older than the user's cadence
            // (tester ask, Freyguy1975 2026-09-07: a large XC library was
            // re-pulled on every open). Playlist switches, Refresh Everything
            // and pull to refresh still sweep through refresh()/refreshSeries().
            val playlist = playlistRepository.activePlaylist()
            val identity = playlist?.let { snapshotStore.identity(it) }
            val decodeStartedAt = android.os.SystemClock.elapsedRealtime()
            val meta = identity?.let { loadSnapshotMetadata(it) }
            val decodeMs = android.os.SystemClock.elapsedRealtime() - decodeStartedAt
            // Per-half permission verdict, applied BEFORE the catalog is
            // attached: a half the server has denied must not resurface from
            // the stored catalog (rails, search, Continue Watching, multiview
            // add all read the same lists), and the flags must be right on the
            // first frame because a fresh install means no sweep runs to set
            // them.
            val moviesDenied = playlist != null &&
                (!playlist.vodEnabled || !playlist.dispatcharrCanViewVod())
            val seriesDenied = playlist != null &&
                (!playlist.vodEnabled || !playlist.dispatcharrCanViewSeries())
            _state.update { it.copy(moviesDenied = moviesDenied, seriesDenied = seriesDenied) }
            if (playlist == null || identity == null) {
                launchRestoreDone.complete(Unit)
                refresh()
                refreshSeries()
                return@launch
            }
            runCatching { catalogStore.pruneIdentities(playlistRepository.allOnce().mapTo(HashSet()) { it.id }, identity) }
                .onFailure { warnUnlessCancelled("VOD catalog prune failed", it) }
            val movieCount = catalogStore.count(identity, VodCatalogStore.KIND_MOVIE)
            val seriesCount = catalogStore.count(identity, VodCatalogStore.KIND_SERIES)
            if (movieCount == 0 && seriesCount == 0) {
                // Nothing stored: there is nothing to show instantly, so this
                // one sweep stays on the launch path.
                launchRestoreDone.complete(Unit)
                refresh()
                refreshSeries()
                return@launch
            }
            // A denied half is simply not attached to its catalog. The rows
            // stay in Room untouched, so a later grant reattaches instantly
            // (the Room-era replacement for shelving the JSON lists).
            if (!moviesDenied) movieCatalogKey.value = identity
            if (!seriesDenied) seriesCatalogKey.value = identity
            val movieGroups = if (moviesDenied) emptyList() else meta?.movieGroupNames.orEmpty()
                .ifEmpty { catalogStore.distinctCategories(identity, VodCatalogStore.KIND_MOVIE) }
            val seriesGroups = if (seriesDenied) emptyList() else meta?.seriesGroupNames.orEmpty()
                .ifEmpty { catalogStore.distinctCategories(identity, VodCatalogStore.KIND_SERIES) }
            _state.update {
                it.copy(
                    totalCount = if (moviesDenied) 0 else movieCount,
                    seriesTotalCount = if (seriesDenied) 0 else seriesCount,
                    movieGroupNames = if (moviesDenied) emptyList() else movieGroups.ifEmpty { it.movieGroupNames },
                    seriesGroupNames = if (seriesDenied) emptyList() else seriesGroups.ifEmpty { it.seriesGroupNames },
                    unsupportedSource = false, isLoading = false, isLoadingSeries = false,
                )
            }
            val now = System.currentTimeMillis()
            val limitMs = appPreferences.vodLibraryRefreshHours.first() * 3_600_000L
            Log.i(TAG, "[VOD-CACHE] restored $movieCount movies, $seriesCount series from the catalog (metadata ${decodeMs}ms)")
            com.aeriotv.android.core.app.AppLaunchTrace.noteVodRestored(decodeMs)
            // Gate each kind on ITS OWN finished sweep. A sweep still OPEN
            // (killed mid-walk by an app update or a force stop, or stopped by
            // a failed page) is stale however recent its last completion: the
            // background sweep then RESUMES it from the saved lane positions.
            val movieSweep = catalogStore.state(identity, VodCatalogStore.KIND_MOVIE)
            val seriesSweep = catalogStore.state(identity, VodCatalogStore.KIND_SERIES)
            moviesCompletedAtMs = movieSweep?.takeIf { !it.open }?.completedAtMs ?: 0L
            seriesCompletedAtMs = seriesSweep?.takeIf { !it.open }?.completedAtMs ?: 0L
            val fresh = { at: Long -> at > 0L && limitMs > 0 && (now - at) in 0 until limitMs }
            // A half written while it was DENIED, or written by a build that
            // predates the denied markers, is never "fresh": it may be the
            // emptied library a blocked capability left behind, and trusting it
            // is exactly what kept Movies hidden after the user re-enabled it
            // on the server (phone 2026-09-15). Force that half's sweep,
            // cadence and change-probe gates ignored.
            val legacySnapshot = (meta?.schema ?: 0) < VodLibrarySnapshotStore.SCHEMA
            // The LATCH is armed from the stored metadata alone, never from the
            // current capability: at this point the capability probe may not
            // have landed yet (phone 2026-09-15 restored at :31 and probed at
            // :32), so a verdict read here would be the stale, still-denied
            // one. Whoever sees the allowed verdict first, this launch's gate
            // below or the capability collector when the probe lands, disarms
            // the latch by running the full sweep.
            if (meta?.moviesDenied == true || legacySnapshot) pendingMoviesRecovery = true
            if (meta?.seriesDenied == true || legacySnapshot) pendingSeriesRecovery = true
            val forceMovies = !moviesDenied && pendingMoviesRecovery
            val forceSeries = !seriesDenied && pendingSeriesRecovery
            Log.i(
                TAG,
                "[VOD-CACHE] written-under-denial latch: movies=$pendingMoviesRecovery " +
                    "series=$pendingSeriesRecovery (legacy=$legacySnapshot); " +
                    "forcing now movies=$forceMovies series=$forceSeries",
            )
            // A denied half is not swept at all; the sweep's own gate would
            // return immediately anyway, and asking for it only logs noise.
            val moviesFresh = moviesDenied || (!forceMovies && fresh(moviesCompletedAtMs))
            val seriesFresh = seriesDenied || (!forceSeries && fresh(seriesCompletedAtMs))
            // Change-probe baseline travels with the snapshot metadata.
            meta?.let {
                moviesProbeCount = it.moviesProbeCount
                moviesProbeNewest = it.moviesProbeNewest
                seriesProbeCount = it.seriesProbeCount
                seriesProbeNewest = it.seriesProbeNewest
            }
            restoredFromSnapshot = true
            // The restored library is on screen NOW and is what the art
            // pass works from (Apple enriches on restore too). Nothing
            // sweeps on the foreground launch path any more (Logan
            // 2026-09-12): the refresh is a quiet background sweep that
            // starts only once the app has settled.
            enrichArt(isMovie = true)
            enrichArt(isMovie = false)
            Log.i(TAG, "[VOD-CACHE] launch cadence: movies=${if (moviesFresh) "fresh" else "stale"} series=${if (seriesFresh) "fresh" else "stale"}")
            launchRestoreDone.complete(Unit)
            scheduleBackgroundSweep(
                moviesStale = !moviesFresh,
                seriesStale = !seriesFresh,
                forced = forceMovies || forceSeries,
            )
        }
    }

    /**
     * The snapshot file's metadata (group names, probe baseline). A pre-Room
     * file that still carries the title lists is imported into the catalog
     * ONCE here and rewritten without them, so an upgrade opens with the
     * library it had and never decodes the 30 MB file again. The decoded
     * lists stay local to this function so they are collectable as soon as
     * the import returns. Watch progress, the Watchlist and hidden titles are
     * keyed "m:uuid" / "s:id", exactly the catalog's keys, so nothing they
     * reference changes.
     */
    private suspend fun loadSnapshotMetadata(identity: String): VodLibrarySnapshotStore.Snapshot? {
        val snap = snapshotStore.load(identity) ?: return null
        if (snap.movies.isEmpty() && snap.series.isEmpty()) return snap
        runCatching {
            catalogStore.importLegacy(identity, snap.movies, snap.series, snap.moviesCompletedAtMs, snap.seriesCompletedAtMs)
        }.onFailure { warnUnlessCancelled("legacy VOD snapshot import failed", it) }.onSuccess {
            Log.i(TAG, "[VOD-DB] imported legacy snapshot: ${snap.movies.size} movies, ${snap.series.size} series")
        }
        val meta = snap.copy(movies = emptyList(), series = emptyList())
        snapshotStore.save(meta)
        return meta
    }

    /**
     * Quiet background refresh of the saved library (Logan 2026-09-12).
     * Starts only once [AppSettleGate] reports the app settled (guide
     * rendered, past first frame if anything is playing, ~20 s after launch or
     * a foreground return), runs on [BackgroundSweep.dispatcher], paces itself
     * at [BG_WALK_PACE_MS] per page, and publishes state only at the end so
     * the Compose grids do not recompose once per page.
     *
     * Two gates open it:
     *  - cadence: the user's "Refresh Movies and TV Shows" window has expired
     *    for that kind. Applies to every provider.
     *  - changed: Dispatcharr only. The cheap count + newest-created_at probe
     *    disagrees with the baseline the snapshot recorded, so the library
     *    plainly moved and waiting out the cadence would serve stale rows.
     */
    private fun scheduleBackgroundSweep(
        moviesStale: Boolean,
        seriesStale: Boolean,
        forced: Boolean = false,
    ) {
        viewModelScope.launch {
            settleGate.awaitSettled()
            var gate = if (forced) "capability" else if (moviesStale || seriesStale) "cadence" else null
            var sweepMovies = moviesStale
            var sweepSeries = seriesStale
            if (gate == null) {
                val changed = probeLibraryChange()
                if (changed != null && (changed.first || changed.second)) {
                    gate = "changed"
                    sweepMovies = changed.first
                    sweepSeries = changed.second
                }
            }
            if (gate == null) {
                Log.i(TAG, "[VOD] background sweep: gate=skipped movies=fresh series=fresh probe=unchanged")
                return@launch
            }
            Log.i(TAG, "[VOD] background sweep: gate=$gate movies=$sweepMovies series=$sweepSeries")
            if (sweepMovies) startMovieSweep(background = true)
            if (sweepSeries) startSeriesSweep(background = true)
        }
    }

    /**
     * One tiny request per kind (page_size=1, newest first). Returns
     * (moviesChanged, seriesChanged), or null when the probe does not apply
     * (not Dispatcharr) or failed. When no baseline was recorded yet the probe
     * only stores one, so the very next launch can compare.
     */
    private suspend fun probeLibraryChange(): Pair<Boolean, Boolean>? {
        val playlist = playlistRepository.activePlaylist() ?: return null
        val sourceType = playlist.sourceType.let { st -> SourceType.entries.firstOrNull { it.name == st } }
        val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                sourceType == SourceType.DispatcharrUserPass
        if (!isDispatcharr || playlist.apiKey.isNullOrBlank()) return null
        val base = playlistRepository.effectiveBaseUrl(playlist)
        val movieProbe = runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getVODMoviesChangeProbe(base, key)
            }
        }.onFailure { warnUnlessCancelled("VOD movies change probe failed", it) }.getOrNull()
        val seriesProbe = runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getVODSeriesChangeProbe(base, key)
            }
        }.onFailure { warnUnlessCancelled("VOD series change probe failed", it) }.getOrNull()
        if (movieProbe == null && seriesProbe == null) return null
        val hadMovieBaseline = moviesProbeCount > 0 || moviesProbeNewest.isNotBlank()
        val hadSeriesBaseline = seriesProbeCount > 0 || seriesProbeNewest.isNotBlank()
        val moviesChanged = movieProbe != null && hadMovieBaseline &&
                (movieProbe.count != moviesProbeCount || movieProbe.newest != moviesProbeNewest)
        val seriesChanged = seriesProbe != null && hadSeriesBaseline &&
                (seriesProbe.count != seriesProbeCount || seriesProbe.newest != seriesProbeNewest)
        Log.i(
            TAG,
            "[VOD] change probe: movies ${moviesProbeCount}/${moviesProbeNewest} -> " +
                "${movieProbe?.count}/${movieProbe?.newest}, series ${seriesProbeCount}/${seriesProbeNewest} -> " +
                "${seriesProbe?.count}/${seriesProbe?.newest}",
        )
        movieProbe?.let { moviesProbeCount = it.count; moviesProbeNewest = it.newest }
        seriesProbe?.let { seriesProbeCount = it.count; seriesProbeNewest = it.newest }
        // Record the (possibly first) baseline so a later launch can compare
        // even when nothing is swept now.
        if (!moviesChanged && !seriesChanged) persistSnapshot()
        return moviesChanged to seriesChanged
    }

    /** True when the launch served the saved library and skipped the sweep. */
    private var restoredFromSnapshot = false

    // Completion stamps carried into every save (see the launch gate).
    private var moviesCompletedAtMs = 0L
    private var seriesCompletedAtMs = 0L

    // Dispatcharr change-probe baseline carried into every save: what the
    // server reported the last time we probed it (see probeLibraryChange).
    private var moviesProbeCount = 0
    private var moviesProbeNewest = ""
    private var seriesProbeCount = 0
    private var seriesProbeNewest = ""

    // ---- Shelved halves (2026-09-15).
    // A half the server has denied is cleared from UI state, but its real
    // library must NOT be lost from disk: re-granting the capability should
    // bring the tab back instantly, not force a slow full re-sweep of a
    // library that was perfectly valid. So the rows are moved here instead of
    // being dropped, and [persistSnapshot] writes the shelved copy back for
    // that half. The memory cost is exactly what the half occupied before it
    // was denied, and it is released as soon as a sweep repopulates the half.
    private var shelvedMovies: List<DispatcharrVODMovie>? = null
    private var shelvedMovieGroupNames: List<String> = emptyList()
    private var shelvedSeries: List<DispatcharrVODSeries>? = null
    private var shelvedSeriesGroupNames: List<String> = emptyList()

    // ---- Pending capability recovery (2026-09-15, round 2).
    // Set at launch when the stored snapshot says that half was written while
    // DENIED (or by a build older than the denied markers). It is a LATCH, not
    // a one-shot decision: the capability probe can land either side of the
    // cache restore (phone probes after, Streamer before), so a verdict read
    // once at restore time is a coin flip. The latch survives until that half
    // actually completes a sweep while allowed, so whichever order the probe
    // and the restore happen in, the half re-sweeps in full exactly once,
    // ignoring the cadence window, the change probe and the stored counts.
    private var pendingMoviesRecovery = false
    private var pendingSeriesRecovery = false

    // Completed once the launch restore has published, so the capability
    // collector cannot race ahead of it and have its rows overwritten.
    private val launchRestoreDone = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Move a half's rows off screen but keep them for the next save. */
    private fun shelveMovies() {
        val st = _state.value
        if (st.movies.isNotEmpty()) {
            shelvedMovies = st.movies
            shelvedMovieGroupNames = st.movieGroupNames
        }
    }

    private fun shelveSeries() {
        val st = _state.value
        if (st.series.isNotEmpty()) {
            shelvedSeries = st.series
            shelvedSeriesGroupNames = st.seriesGroupNames
        }
    }

    /**
     * Background TMDB art pass over a library once its sweep completes
     * (Apple MoviesView.enrichArt / VODStore, MoviesView.swift:143). Titles
     * the caller wants first (the tab's hero pages) go in [priorityKeys] as
     * (art key, title) pairs; everything else follows in library order.
     */
    fun enrichArt(priorityKeys: List<Pair<String, String>> = emptyList(), isMovie: Boolean) {
        // The library is read from the catalog a page at a time (GH #109), so
        // the pass never holds the whole title list; the keys are the stored
        // artKey / title columns, the same tmdbArtKey(displayTitle) pairs the
        // in-memory mapping produced.
        val key = catalogKeyFlow(isMovie).value
        if (key == null && priorityKeys.isEmpty()) return
        val kind = kindOf(isMovie)
        tmdbArtCache.enrichPaged(isMovie, priorityKeys) { cursor ->
            if (key == null) return@enrichPaged null
            val rows = catalogStore.artPage(key, kind, cursor, ART_PAGE_SIZE)
            if (rows.isEmpty()) null else rows.last().id to rows.map { it.artKey to it.title }
        }
    }

    /** Bumped as cached art lands; the media tabs observe it once per tab. */
    val artVersion: StateFlow<Int> get() = tmdbArtCache.version

    /** Cached TMDB poster for a library key, or null (caller falls back). */
    fun artPosterUrl(key: String): String? = tmdbArtCache.posterUrl(key)

    /** Cached TMDB backdrop for a library key, or null. */
    fun artBackdropUrl(key: String): String? = tmdbArtCache.backdropUrl(key)

    /** Cached TMDB synopsis for a library key, or null. */
    fun artOverview(key: String): String? = tmdbArtCache.overview(key)

    /**
     * Hero backdrop: the persistent cache first, then a one-off TMDB details
     * lookup whose result is folded back into the cache, so a hero title
     * resolved once never refetches.
     */
    suspend fun heroBackdropUrl(artKey: String, tmdbId: String?, title: String, isMovie: Boolean): String? {
        tmdbArtCache.backdropUrl(artKey)?.let { return it }
        val details = resolveTmdbDetails(tmdbId, title, isMovie) ?: return null
        tmdbArtCache.merge(
            key = artKey,
            tmdbId = tmdbId?.trim().orEmpty(),
            poster = details.posterPath.orEmpty(),
            backdrop = details.backdropPath.orEmpty(),
            overview = details.overview,
        )
        return details.backdropPath?.takeIf { it.isNotBlank() }?.let { tmdbService.imageUrlFor(it) }
    }

    /**
     * Write the playlist's library metadata (group names, probe baseline).
     * [completed] names the kind whose sweep just finished; its stamp is
     * refreshed, the other kind keeps whatever it had.
     */
    private fun persistSnapshot(completed: MediaSweep? = null) {
        val now = System.currentTimeMillis()
        val st0 = _state.value
        // A DENIED half never completes, so it never earns a completion stamp.
        // Stamping it was the whole of the 2026-09-15 phone bug: the series
        // sweep finished while movies was denied, the save carried an emptied
        // movies list, and the next launch read "movies=fresh" off that file
        // and skipped the sweep, so re-granting the capability could not bring
        // the tab back.
        when (completed) {
            MediaSweep.Movies -> if (!st0.moviesDenied) {
                moviesCompletedAtMs = now; pendingMoviesRecovery = false
            }
            MediaSweep.Series -> if (!st0.seriesDenied) {
                seriesCompletedAtMs = now; pendingSeriesRecovery = false
            }
            MediaSweep.Both -> {
                if (!st0.moviesDenied) { moviesCompletedAtMs = now; pendingMoviesRecovery = false }
                if (!st0.seriesDenied) { seriesCompletedAtMs = now; pendingSeriesRecovery = false }
            }
            null -> Unit
        }
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist() ?: return@launch
            val st = _state.value
            // Metadata only: the titles live in the Room catalog (GH #109),
            // so a denied half's rows are never at risk from the other half's
            // save (the shelving this replaces existed only to protect them in
            // the JSON file). What still MUST be recorded is the denial itself
            // and the zeroed freshness markers, or the next launch reads the
            // denied half as fresh and never re-sweeps it once it is allowed
            // again (phone 2026-09-15).
            snapshotStore.save(
                VodLibrarySnapshotStore.Snapshot(
                    identity = snapshotStore.identity(playlist),
                    savedAtMs = now,
                    movieGroupNames = if (st.moviesDenied) shelvedMovieGroupNames else st.movieGroupNames,
                    seriesGroupNames = if (st.seriesDenied) shelvedSeriesGroupNames else st.seriesGroupNames,
                    // Freshness is never inferable for a denied half.
                    moviesCompletedAtMs = if (st.moviesDenied) 0L else moviesCompletedAtMs,
                    seriesCompletedAtMs = if (st.seriesDenied) 0L else seriesCompletedAtMs,
                    moviesProbeCount = if (st.moviesDenied) 0 else moviesProbeCount,
                    moviesProbeNewest = if (st.moviesDenied) "" else moviesProbeNewest,
                    seriesProbeCount = if (st.seriesDenied) 0 else seriesProbeCount,
                    seriesProbeNewest = if (st.seriesDenied) "" else seriesProbeNewest,
                    moviesDenied = st.moviesDenied,
                    seriesDenied = st.seriesDenied,
                    // Stamp the CURRENT schema. Without this every file decoded
                    // as schema 0 and `legacySnapshot` was permanently true, so
                    // both halves force-swept on every single launch and the
                    // cadence gate never applied.
                    schema = VodLibrarySnapshotStore.SCHEMA,
                ),
            )
        }
    }

    /** On Demand tab shown: load now instead of waiting out the startup deferral. */
    fun ensureLoaded() = startInitialLoads()

    init {
        startLibraryPipeline(isMovie = true)
        startLibraryPipeline(isMovie = false)
        deferredStart = viewModelScope.launch {
            // Round 2 (gtvlogs/session6.txt) moved this off a fixed 6 s
            // deferral because reading and decoding the 30 MB library JSON on
            // top of the guide's EPG read starved the guide for twenty seconds
            // (GC at 14:19:32.219 freed "7(120MB) LOS objects").
            //
            // Round 3 (gtvlogs/session7.txt) shows that waiting for the SETTLE
            // signal overshot: "[VOD-CACHE] restored 40015 movies, 11780
            // series" printed at 14:29:32.223, after "settled (+20005 ms)" at
            // 14:29:29.121, so Movies and TV Shows had nothing on screen for
            // the first 25 s of the launch (Logan 2026-09-12: "Loading EPG,
            // DVR, Movies, and TV Shows is a different story"). The settle
            // signal gates NETWORK sweeps, never a cached restore.
            //
            // The guide's cached paint is the real ordering constraint, and it
            // lands at +1.3 s on the phone and +10.9 s on the Streamer, so
            // waiting on it keeps round 2's finding and still puts the library
            // on screen many seconds earlier. [ensureLoaded] short-circuits
            // this entirely when the user opens the tab first.
            settleGate.awaitGuidePainted()
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
                    // Go back through the STORED-CATALOG path, not straight to
                    // a sweep (Logan 2026-09-16): every playlist keeps its own
                    // vod_title / vod_sweep_state rows, so the newly active
                    // playlist opens from Room instantly and only re-sweeps
                    // when its own cadence gate says it is due. A deleted
                    // playlist's rows are gone by now, so this correctly falls
                    // through to a fresh sweep for whatever became active.
                    initialLoadsStarted = false
                    startInitialLoads()
                }
        }
        // Per-half capability changes on the SAME playlist (a re-probe after the
        // admin flips vod_movies_enabled / vod_series_enabled). Each half is
        // independent: a denial retires only its own library, and a grant
        // re-sweeps only its own, so the other tab is never disturbed and a
        // restored permission repopulates without a reinstall.
        viewModelScope.launch {
            combine(
                playlistRepository.observeActiveId(),
                playlistRepository.observeAll(),
            ) { id, all -> all.firstOrNull { it.id == id } }
                .map { p ->
                    if (p == null) null
                    else Pair(
                        !p.vodEnabled || !p.dispatcharrCanViewVod(),
                        !p.vodEnabled || !p.dispatcharrCanViewSeries(),
                    )
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { (moviesDenied, seriesDenied) ->
                    // No drop(1): on the phone the probe landed BEFORE this
                    // collector's first emission, so the only emission that
                    // carried the restored capability was the one being
                    // dropped, and the transition was never seen. Every
                    // emission is now considered; the guards below make a
                    // repeat a no-op.
                    launchRestoreDone.await()
                    val st = _state.value
                    if (moviesDenied != st.moviesDenied ||
                        (!moviesDenied && pendingMoviesRecovery)
                    ) {
                        refresh()
                    }
                    if (seriesDenied != st.seriesDenied ||
                        (!seriesDenied && pendingSeriesRecovery)
                    ) {
                        refreshSeries()
                    }
                }
        }
        // "Refresh Everything" (PlaylistViewModel.refreshEverything): the active
        // id is UNCHANGED, so the observeActiveId().drop(1) collector above never
        // fires. The VodResetBus bridges that gap and runs the same nuclear reset.
        //
        // Unlike a playlist switch, this one is destructive on purpose (Logan
        // 2026-09-16): the ACTIVE playlist's stored catalog is deleted (its
        // vod_title / vod_sweep_state / vod_sweep_lane rows across every
        // identity it owns, plus its snapshot metadata) and rebuilt from
        // scratch, so nothing stale can paint. Only the active playlist is
        // touched; every other playlist keeps its catalog. The forced sweep is
        // the ordinary foreground one, so a kind whose capability is denied
        // stays empty instead of being walked.
        viewModelScope.launch {
            vodResetBus.resets.collect {
                resetVodState()
                val active = playlistRepository.activePlaylist()
                if (active != null) {
                    runCatching {
                        catalogStore.deleteForPlaylistId(active.id)
                        snapshotStore.delete(snapshotStore.identity(active))
                        catalogStore.clearRowCache()
                    }.onFailure {
                        Log.w(TAG, "[VOD-CAT] refresh everything: catalog wipe failed", it)
                    }
                    Log.i(
                        TAG,
                        "[VOD-CAT] refresh everything: catalog cleared for playlist=${active.id}, " +
                            "full sweep starting",
                    )
                }
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
        // Shelved rows belong to the OLD playlist; never write them into the
        // new source's snapshot.
        shelvedMovies = null
        shelvedMovieGroupNames = emptyList()
        shelvedSeries = null
        shelvedSeriesGroupNames = emptyList()
        pendingMoviesRecovery = false
        pendingSeriesRecovery = false
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
        // The old playlist's catalog stays in Room (switching back opens it
        // at once); the grids detach from it until the new source's key is set.
        movieCatalogKey.value = null; seriesCatalogKey.value = null
        catalogLookups.clear(); catalogMisses.clear()
        lastMoviesSpec = null; lastSeriesSpec = null
        _moviesLibrary.value = MediaLibrary(); _seriesLibrary.value = MediaLibrary()
        _heroBackdrops.value = emptyMap()
        moviesGenre.value = null; seriesGenre.value = null
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
                // No server search: match the stored catalog (GH #109: the
                // library is no longer held in memory to filter).
                val hits = movieCatalogKey.value?.let { catalogStore.searchMovies(it, q) }.orEmpty()
                if (_state.value.searchQuery.trim() != q) return@launch
                _state.update { st ->
                    st.copy(searchResults = rankSearch(hits, q) { it.displayName }, isSearching = false)
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
                val hits = seriesCatalogKey.value?.let { catalogStore.searchSeries(it, q) }.orEmpty()
                if (_state.value.seriesSearchQuery.trim() != q) return@launch
                _state.update { st ->
                    st.copy(seriesSearchResults = rankSearch(hits, q) { it.displayName }, isSearchingSeries = false)
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
            // The pool is the stored catalog (queried per person for just the
            // credited ids and titles, GH #109) plus the search results and
            // resolved titles, catalog rows first as the library came first.
            if (isMovie) {
                val catalogKey = movieCatalogKey.value
                for (person in people) {
                    val credits = tmdbService.personCredits(person.id, isMovie = true, rawKey = key)
                    val titles = credits.map { normalizeVodTitle(it.title) }
                    val stored = if (catalogKey == null) emptyList() else
                        catalogStore.moviesByTmdbIds(catalogKey, credits.map { it.id }) +
                            catalogStore.moviesByNormTitlesWithoutTmdb(catalogKey, titles)
                    val byTmdb = HashMap<String, DispatcharrVODMovie>()
                    val byTitle = HashMap<String, DispatcharrVODMovie>()
                    for (m in stored + snapshot.searchResults + snapshot.resolvedMovies.values) {
                        val t = m.tmdbId
                        if (!t.isNullOrBlank()) byTmdb.putIfAbsent(t, m)
                        else byTitle.putIfAbsent(normalizeVodTitle(m.displayName), m)
                    }
                    val seen = HashSet<String>()
                    val hits = ArrayList<DispatcharrVODMovie>()
                    for (c in credits) {
                        val hit = byTmdb[c.id] ?: byTitle[normalizeVodTitle(c.title)] ?: continue
                        if (seen.add(hit.uuid)) hits += hit
                    }
                    if (hits.size > bestMovies.size) { bestName = person.name; bestMovies = hits }
                }
            } else {
                val catalogKey = seriesCatalogKey.value
                for (person in people) {
                    val credits = tmdbService.personCredits(person.id, isMovie = false, rawKey = key)
                    val titles = credits.map { normalizeVodTitle(it.title) }
                    val stored = if (catalogKey == null) emptyList() else
                        catalogStore.seriesByTmdbIds(catalogKey, credits.map { it.id }) +
                            catalogStore.seriesByNormTitlesWithoutTmdb(catalogKey, titles)
                    val byTmdb = HashMap<String, DispatcharrVODSeries>()
                    val byTitle = HashMap<String, DispatcharrVODSeries>()
                    for (sr in stored + snapshot.seriesSearchResults + snapshot.resolvedSeries.values) {
                        val t = sr.tmdbId
                        if (!t.isNullOrBlank()) byTmdb.putIfAbsent(t, sr)
                        else byTitle.putIfAbsent(normalizeVodTitle(sr.displayName), sr)
                    }
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

    /** Foreground sweep (pull to refresh, playlist switch, Refresh Everything). */
    fun refresh() = startMovieSweep(background = false)

    // True while the ACTIVE movie sweep is the quiet background one, so a
    // foreground request can preempt it instead of being swallowed by the
    // single-flight guard.
    private var movieSweepIsBackground = false
    private var seriesSweepIsBackground = false

    private fun startMovieSweep(background: Boolean) {
        if (movieSweepJob?.isActive == true) {
            if (background || !movieSweepIsBackground) {
                Log.i(TAG, "[VOD] movie sweep already running; ignoring refresh(background=$background)")
                return
            }
            Log.i(TAG, "[VOD] foreground refresh preempts the background movie sweep")
            movieSweepJob?.cancel()
        }
        movieSweepIsBackground = background
        // The "Updating" indicator on Movies / TV Shows is driven from this
        // flag, so it stays up for exactly as long as a sweep is walking the
        // server, rather than dropping with isLoading at the first painted
        // page (Logan 2026-09-16). Cleared by job identity below, so the
        // completion of a job this call preempted cannot clear the new one.
        _state.update { it.copy(sweepingMovies = true) }
        movieSweepJob = viewModelScope.launch(sweepContext(background)) {
            // Opportunistic capability probe on entering On Demand with a
            // stale / unprobed snapshot, so a just-granted VOD permission takes
            // effect without an app restart. No-op when fresh; never downgrades
            // on failure.
            playlistRepository.activePlaylist()?.let { active ->
                if (active.capabilitiesNeedProbe()) {
                    runCatching { playlistRepository.probeCapabilities(active.id) }
                }
            }
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
            // Capability.CanViewVod. Denied = the server serves an EMPTY
            // catalog (apps/vod/utils.py blocks, it does not 403), so skipping
            // the fetch matches the server exactly. Unknown still fetches.
            // Only the MOVIES half is retired here: `moviesDenied` is its own
            // flag and `unsupportedSource` (which means the source does no VOD
            // at all) is deliberately left alone, so a user with
            // vod_movies_enabled=false and series still allowed keeps a working
            // TV Shows tab. Denying both halves is what hides the combined On
            // Demand tab, and MainScaffold composes that from the two.
            if (playlist != null && (!playlist.vodEnabled || !playlist.dispatcharrCanViewVod())) {
                // Detaching the grid from the catalog key IS the shelving in
                // the Room world (GH #109): the stored rows survive untouched,
                // so re-granting the capability puts the tab back instantly
                // instead of paying for a full re-sweep.
                movieCatalogKey.value = null
                shelveMovies()
                _state.update {
                    it.copy(
                        moviesDenied = true,
                        movies = emptyList(),
                        totalCount = 0,
                        movieGroupNames = emptyList(),
                        searchResults = emptyList(),
                        personMatches = emptyList(),
                        personMatchName = null,
                        isLoading = false,
                        error = null,
                    )
                }
                return@launch
            }
            // Allowed (or unknown): clear any earlier denial so a capability
            // granted back on the server restores this half without a reinstall.
            if (_state.value.moviesDenied || pendingMoviesRecovery) {
                // Denied -> allowed, or a library stored under denial: this
                // sweep repopulates the half from the server, so the shelved
                // copy has done its job. The stored count and completion stamp
                // are not trusted either (the phone's 400-movie remnant read as
                // a fresh library), so they are zeroed until this walk finishes.
                // Un-shelve FIRST: the rows the block hid are valid and put the
                // tab back on screen immediately, instead of leaving it retired
                // for the whole length of the forced walk below.
                val unshelved = shelvedMovies
                _state.update {
                    it.copy(
                        moviesDenied = false,
                        movies = if (unshelved != null && it.movies.isEmpty()) unshelved else it.movies,
                        totalCount = if (unshelved != null && it.movies.isEmpty()) unshelved.size
                            else it.totalCount,
                        movieGroupNames = if (unshelved != null && it.movieGroupNames.isEmpty())
                            shelvedMovieGroupNames else it.movieGroupNames,
                    )
                }
                shelvedMovies = null
                shelvedMovieGroupNames = emptyList()
                // Reattach the grid to the stored catalog at once; the forced
                // sweep below then rebuilds it honestly in the background.
                playlist?.takeIf { it.vodEnabled }?.let { movieCatalogKey.value = snapshotStore.identity(it) }
                moviesCompletedAtMs = 0L
                moviesProbeCount = 0
                moviesProbeNewest = ""
                Log.i(
                    TAG,
                    "[VOD] movies capability restored: reattached the stored catalog " +
                        "(shelved ${unshelved?.size ?: 0}); forcing a full sweep",
                )
            }
            if (playlist != null && sourceType == SourceType.XtreamCodes) {
                ensureXtreamProbe(playlist)
                return@launch
            }
            if (playlist == null || !isDispatcharr || playlist.apiKey.isNullOrBlank()) {
                movieCatalogKey.value = null
                _state.update { it.copy(unsupportedSource = true, totalCount = 0, isLoading = false, error = null) }
                return@launch
            }
            _state.update { it.copy(isLoading = true, error = null, unsupportedSource = false) }
            // New refresh cycle = new category snapshot (server-side group
            // edits become visible without an app restart). Series refresh,
            // pagination, and search all reuse this fetch's maps.
            ensureDispatcharrCategories(playlist, invalidate = true)
            sweepDispatcharrCatalog(playlist, isMovie = true, background = background)
        }
        movieSweepJob?.let { job ->
            job.invokeOnCompletion {
                if (movieSweepJob === job) {
                    _state.update { st -> st.copy(sweepingMovies = false) }
                    // Backstop for a watcher of a VodResetBus rebuild (Edit
                    // Playlist's "Loading movies..." row): every terminal
                    // outcome lands here - denied, unsupported source, failed,
                    // or simply finished - so the row can never hang on a sweep
                    // that ended without ever storing a page. The in-sweep mark
                    // at the first stored page is what normally releases it.
                    // Guarded by job identity: a reset CANCELS the previous
                    // sweep, and that dying job must not report the rebuild it
                    // was replaced by as settled.
                    vodResetBus.markSettled(VodResetBus.Kind.Movies)
                }
            }
        }
    }

    /** Foreground series sweep; see [refresh]. */
    fun refreshSeries() = startSeriesSweep(background = false)

    private fun startSeriesSweep(background: Boolean) {
        if (seriesSweepJob?.isActive == true) {
            if (background || !seriesSweepIsBackground) {
                Log.i(TAG, "[VOD] series sweep already running; ignoring refreshSeries(background=$background)")
                return
            }
            Log.i(TAG, "[VOD] foreground refresh preempts the background series sweep")
            seriesSweepJob?.cancel()
        }
        seriesSweepIsBackground = background
        // The "Updating" indicator on Movies / TV Shows is driven from this
        // flag, so it stays up for exactly as long as a sweep is walking the
        // server, rather than dropping with isLoading at the first painted
        // page (Logan 2026-09-16). Cleared by job identity below, so the
        // completion of a job this call preempted cannot clear the new one.
        _state.update { it.copy(sweepingSeries = true) }
        seriesSweepJob = viewModelScope.launch(sweepContext(background)) {
            val playlist = playlistRepository.activePlaylist()
            val sourceType = playlist?.sourceType?.let { SourceType.entries.firstOrNull { st -> st.name == it } }
            val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                    sourceType == SourceType.DispatcharrUserPass
            // Same opt-out gate as refresh() above for the series side; see
            // the longer comment there. Belt-and-suspenders with MainScaffold's
            // hasVodContent.
            // Capability.CanViewSeries; same server semantics as movies above.
            // Mirror of the movies half above: this retires SERIES only, and
            // detaching the catalog key leaves the stored rows intact.
            if (playlist != null && (!playlist.vodEnabled || !playlist.dispatcharrCanViewSeries())) {
                seriesCatalogKey.value = null
                shelveSeries()
                _state.update {
                    it.copy(
                        seriesDenied = true,
                        series = emptyList(),
                        seriesTotalCount = 0,
                        seriesGroupNames = emptyList(),
                        seriesSearchResults = emptyList(),
                        seriesPersonMatches = emptyList(),
                        seriesPersonMatchName = null,
                        isLoadingSeries = false,
                        seriesError = null,
                    )
                }
                return@launch
            }
            if (_state.value.seriesDenied || pendingSeriesRecovery) {
                val unshelved = shelvedSeries
                _state.update {
                    it.copy(
                        seriesDenied = false,
                        series = if (unshelved != null && it.series.isEmpty()) unshelved else it.series,
                        seriesTotalCount = if (unshelved != null && it.series.isEmpty()) unshelved.size
                            else it.seriesTotalCount,
                        seriesGroupNames = if (unshelved != null && it.seriesGroupNames.isEmpty())
                            shelvedSeriesGroupNames else it.seriesGroupNames,
                    )
                }
                shelvedSeries = null
                shelvedSeriesGroupNames = emptyList()
                playlist?.takeIf { it.vodEnabled }?.let { seriesCatalogKey.value = snapshotStore.identity(it) }
                seriesCompletedAtMs = 0L
                seriesProbeCount = 0
                seriesProbeNewest = ""
                Log.i(
                    TAG,
                    "[VOD] series capability restored: reattached the stored catalog " +
                        "(shelved ${unshelved?.size ?: 0}); forcing a full sweep",
                )
            }
            if (playlist != null && sourceType == SourceType.XtreamCodes) {
                ensureXtreamProbe(playlist)
                return@launch
            }
            if (playlist == null || !isDispatcharr || playlist.apiKey.isNullOrBlank()) {
                seriesCatalogKey.value = null
                _state.update { it.copy(unsupportedSource = true, seriesTotalCount = 0, isLoadingSeries = false, seriesError = null) }
                return@launch
            }
            _state.update { it.copy(isLoadingSeries = true, seriesError = null) }
            // Piggybacks on refresh()'s category fetch when both run in the
            // same cycle (the usual case); only starts one if none exists.
            ensureDispatcharrCategories(playlist)
            sweepDispatcharrCatalog(playlist, isMovie = false, background = background)
        }
        seriesSweepJob?.let { job ->
            job.invokeOnCompletion {
                if (seriesSweepJob === job) {
                    _state.update { st -> st.copy(sweepingSeries = false) }
                    // See the movies half: terminal backstop for a rebuild
                    // watcher, guarded by job identity for the same reason.
                    vodResetBus.markSettled(VodResetBus.Kind.Series)
                }
            }
        }
    }

    /** One fetched + stored page of a lane walk. */
    private class LanePage(val count: Int, val nonEmpty: Boolean, val nextQuery: String?, val written: Int)

    /**
     * The Dispatcharr Movies or TV Shows sweep (GH #109), writing straight
     * into the Room catalog.
     *
     * The movie LIST endpoint omits category_id on this server, so the only
     * way to learn a title's real group (and make the Manage Groups filter
     * work) is to query each enabled category and stamp the result directly
     * (iOS StreamingAPIs.swift per-category VOD load). With no category
     * endpoint, or nothing enabled, one unfiltered lane walks the whole list
     * and stamps from custom_properties instead.
     *
     * What changed from the in-memory walk:
     *  - No row caps. VOD_TOTAL_CAP (40,000) bounded memory, and nothing is
     *    held in memory any more; VOD_PER_CATEGORY_CAP (5,000) kept one giant
     *    early category from starving the rest, which the ROUND-ROBIN walk
     *    below now does instead: every lane gets one page per round, so a
     *    category with 30,000 titles no longer delays a later one.
     *  - Every page is written as it arrives, tagged with the sweep's
     *    generation. Rows older generations wrote are deleted only when every
     *    lane finished; an interrupted or failed sweep deletes nothing.
     *  - A failed page is retried [PAGE_RETRY_ATTEMPTS] times with backoff.
     *    If it still fails the lane keeps its position, the sweep stays open,
     *    and the NEXT sweep resumes that lane from the failed page instead of
     *    skipping the category until a full re-sweep. A lane that fails
     *    [LANE_MAX_FAILURES] sweeps running is given up for that generation;
     *    its stored titles are kept rather than deleted.
     *  - The grid fills progressively from the catalog (throttled rebuilds),
     *    and a re-sweep over a populated tab never empties it first. A
     *    background sweep still publishes only at the end.
     *
     * Group presence (Manage Groups) is each lane's own signal (its pages
     * report content), independent of which lane first stored a title:
     * Dispatcharr categories are many-to-many over items, so a category whose
     * titles were all stored by an earlier lane still has content.
     */
    private suspend fun sweepDispatcharrCatalog(playlist: PlaylistEntity, isMovie: Boolean, background: Boolean) {
        val label = if (isMovie) "movies" else "series"
        val identity = snapshotStore.identity(playlist)
        val kind = kindOf(isMovie)
        catalogKeyFlow(isMovie).value = identity
        // What a previous sweep or the legacy import stored paints at once.
        // Over a populated tab the refresh spinner stays up until the sweep
        // ends (as before); an empty tab drops it at the first stored page.
        publishCatalogCount(isMovie)
        val startedEmpty = (if (isMovie) _state.value.totalCount else _state.value.seriesTotalCount) == 0
        val cats = if (isMovie) dispatcharrEnabledMovieCats else dispatcharrEnabledSeriesCats
        val laneNames = cats.ifEmpty { listOf(UNFILTERED_LANE) }
        val plan = catalogStore.beginSweep(identity, kind, laneNames) { dispatcharrClient.vodCategoryQuery(isMovie, it) }
        val base = playlistRepository.effectiveBaseUrl(playlist)
        val active = ArrayDeque(plan.lanes.filter { it.nextQuery != null })
        Log.i(
            TAG,
            "[VOD] $label sweep: generation ${plan.generation} (${if (plan.resumed) "resumed" else "new"}), " +
                "${laneNames.size} lanes, ${active.size} to walk, background=$background",
        )
        // ---- [VOD-CAT] verification trace (GH #109). A handful of lines per
        // sweep, never one per title: enough to confirm from a device log
        // alone that the sweep started, resumed where it left off, wrote its
        // pages, finished, and applied NO total cap.
        Log.i(
            TAG,
            "[VOD-CAT] sweep start kind=$label playlist=${playlist.id} generation=${plan.generation} " +
                "lanes=${laneNames.size} toWalk=${active.size} background=$background",
        )
        Log.i(TAG, "[VOD-CAT] no total cap applied: every page the server offers is stored (VOD_TOTAL_CAP removed)")
        if (plan.resumed) {
            val resumedLanes = plan.lanes.count { it.nextQuery != null }
            Log.i(
                TAG,
                "[VOD-CAT] resumed from saved positions: $resumedLanes of ${plan.lanes.size} lanes still open, " +
                    "${catalogStore.count(identity, kind)} titles already stored",
            )
        }
        val pagesThisRun = HashMap<String, Int>()
        var written = 0
        var aborted = false
        var firstPainted = false
        while (active.isNotEmpty()) {
            // FAST MODE (Logan 2026-09-16): a foreground sweep with the Movies
            // or TV Shows page actually on screen is the user waiting for the
            // grid, so it runs with NO artificial pause and up to
            // SWEEP_FOREGROUND_LANES pages in flight (never more than three
            // concurrent requests, page_size stays 100). Anything else - a
            // quiet background sweep, or a foreground sweep whose tab the user
            // has left - keeps the paced, strictly one-at-a-time walk.
            val fast = !background && aLibraryIsOnScreen()
            val batch = buildList {
                while (size < (if (fast) SWEEP_FOREGROUND_LANES else 1) && active.isNotEmpty()) {
                    val candidate = active.removeFirst()
                    if (candidate.nextQuery != null) add(candidate)
                }
            }
            if (batch.isEmpty()) continue
            if (!fast) pace(background)
            val results = coroutineScope {
                batch.map { lane ->
                    async {
                        val url = dispatcharrClient.vodListUrl(base, isMovie, lane.nextQuery!!)
                        var failure: Throwable? = null
                        val fetched = try {
                            fetchPageWithRetry("VOD $label lane='${lane.lane}'") {
                                fetchAndStoreLanePage(playlist, identity, isMovie, plan.generation, lane.lane, url)
                            }
                        } catch (u: DispatcharrError.Unauthorized) {
                            failure = u
                            null
                        }
                        Triple(lane, fetched, failure)
                    }
                }.awaitAll()
            }
            for ((lane, page, failure) in results) {
                if (failure != null) {
                    // The broker already tried to recover the key; every other
                    // lane would 401 too. Stop, keep every position for next
                    // time.
                    warnUnlessCancelled("VOD $label sweep: unauthorized; stopping (positions kept)", failure)
                    aborted = true
                    continue
                }
                if (page == null) {
                    // Retries exhausted: keep the lane on this page for the
                    // next sweep and move on to the other lanes.
                    catalogStore.saveLane(lane.copy(failures = lane.failures + 1))
                    continue
                }
                written += page.written
                val pages = (pagesThisRun[lane.lane] ?: 0) + 1
                pagesThisRun[lane.lane] = pages
                Log.i(
                    TAG,
                    "[VOD-CAT] page written kind=$label lane='${lane.lane}' page=$pages " +
                        "rows=${page.written} runningTotal=$written serverCount=${page.count}",
                )
                // A server whose `next` never ends must not walk forever.
                val next = page.nextQuery?.takeIf { pages < LANE_PAGE_SAFETY }
                val updated = lane.copy(
                    nextQuery = next, failures = 0,
                    hasContent = lane.hasContent || page.count > 0 || page.nonEmpty,
                )
                catalogStore.saveLane(updated)
                if (next != null && !aborted) active.addLast(updated)
                if (page.written > 0) {
                    // The first page of THIS kind is the point a rebuild has
                    // something to show, which is all a save screen waits for;
                    // the rest of the walk (minutes, uncapped) runs on.
                    vodResetBus.markSettled(
                        if (isMovie) VodResetBus.Kind.Movies else VodResetBus.Kind.Series,
                    )
                }
                if (!background && page.written > 0) {
                    if (!firstPainted) {
                        firstPainted = true
                        if (startedEmpty) {
                            _state.update { if (isMovie) it.copy(isLoading = false, error = null) else it.copy(isLoadingSeries = false, seriesError = null) }
                        }
                        catalogChanged(isMovie, force = true)
                    } else {
                        catalogChanged(isMovie, force = false)
                    }
                }
            }
            if (aborted) break
        }
        val lanes = catalogStore.lanes(identity, kind)
        val unfinished = lanes.filter { it.nextQuery != null }
        val givenUp = unfinished.filter { it.failures >= LANE_MAX_FAILURES }
        val complete = !aborted && unfinished.size == givenUp.size
        // Publish ONLY names that carried content for this account so Manage
        // Groups never offers an empty category like "Apple TV". The
        // unfiltered lane has no name: its groups come from the stamped rows.
        val groups = if (cats.isEmpty()) catalogStore.distinctCategories(identity, kind)
            else lanes.filter { it.hasContent }.map { it.lane }.sorted()
        var closed = false
        if (complete) {
            val deleted = catalogStore.finishSweep(identity, kind, plan.generation, givenUp.map { it.lane })
            if (deleted < 0) {
                // Every lane walked but nothing arrived: keep the stored library
                // (a transient all-empty answer must not wipe it) and close.
                catalogStore.abandonSweep(identity, kind)
                Log.w(TAG, "[VOD] $label sweep: generation ${plan.generation} stored nothing; library kept")
            } else {
                closed = true
                Log.i(
                    TAG,
                    "[VOD] $label sweep: generation ${plan.generation} complete, $written rows written this run, " +
                        "$deleted stale removed, ${givenUp.size} lanes given up",
                )
                Log.i(
                    TAG,
                    "[VOD-CAT] sweep complete kind=$label playlist=${playlist.id} written=$written " +
                        "stored movies=${catalogStore.count(identity, VodCatalogStore.KIND_MOVIE)} " +
                        "series=${catalogStore.count(identity, VodCatalogStore.KIND_SERIES)} " +
                        "staleRemoved=$deleted lanesGivenUp=${givenUp.size}",
                )
            }
        } else {
            Log.w(
                TAG,
                "[VOD] $label sweep: generation ${plan.generation} left open (${unfinished.size} lanes unfinished, " +
                    "aborted=$aborted); the next sweep resumes them",
            )
            Log.w(
                TAG,
                "[VOD-CAT] sweep incomplete kind=$label written=$written openLanes=${unfinished.size} " +
                    "stored=${catalogStore.count(identity, kind)}; positions saved for resume",
            )
        }
        // Nothing stored and the walk did not finish: say so instead of an
        // empty "No Movies" grid (the old first-page failure path did too).
        val failure = if (!complete && catalogStore.count(identity, kind) == 0) {
            if (aborted) "Dispatcharr rejected the API key." else "Couldn't load $label from Dispatcharr."
        } else null
        _state.update {
            if (isMovie) it.copy(isLoading = false, error = failure, moviesNextCursor = null, movieGroupNames = groups.ifEmpty { it.movieGroupNames })
            else it.copy(isLoadingSeries = false, seriesError = failure, seriesNextCursor = null, seriesGroupNames = groups.ifEmpty { it.seriesGroupNames })
        }
        catalogChanged(isMovie, force = true)
        if (closed) persistSnapshot(if (isMovie) MediaSweep.Movies else MediaSweep.Series) else persistSnapshot()
        if (written > 0 || closed) enrichArt(isMovie = isMovie)
    }

    /** GET one lane page, stamp its group, store it. Throws on any failure. */
    private suspend fun fetchAndStoreLanePage(
        playlist: PlaylistEntity,
        identity: String,
        isMovie: Boolean,
        generation: Long,
        lane: String,
        url: String,
    ): LanePage {
        if (isMovie) {
            val p = dispatcharrAuth.withApiKeyRetry(playlist.id) { key -> dispatcharrClient.getVODMoviesPage(url, key) }
            val stamped = p.results.map { m -> if (lane == UNFILTERED_LANE) stampMovieGroup(m) else m.copy(categoryName = lane) }
            return LanePage(p.count, p.results.isNotEmpty(), nextQueryOf(p.next), catalogStore.writeMovies(identity, generation, stamped))
        }
        val p = dispatcharrAuth.withApiKeyRetry(playlist.id) { key -> dispatcharrClient.getVODSeriesPage(url, key) }
        val stamped = p.results.map { s -> if (lane == UNFILTERED_LANE) stampSeriesGroup(s) else s.copy(categoryName = lane) }
        return LanePage(p.count, p.results.isNotEmpty(), nextQueryOf(p.next), catalogStore.writeSeries(identity, generation, stamped))
    }

    /** The host-free query of a `next` cursor (already pinned to the request's
     *  origin by DispatcharrClient), or null when the walk is done. */
    private fun nextQueryOf(next: String?): String? =
        next?.let { runCatching { java.net.URI(it).rawQuery }.getOrNull() }?.takeIf { it.isNotBlank() }

    /**
     * Run [block] up to [PAGE_RETRY_ATTEMPTS] times, waiting
     * [PAGE_RETRY_BASE_MS] then three times as long between attempts. Returns
     * null when every attempt failed. Cancellation and a 401 the auth broker
     * could not recover propagate.
     */
    private suspend fun <T> fetchPageWithRetry(label: String, block: suspend () -> T): T? {
        var backoff = PAGE_RETRY_BASE_MS
        for (attempt in 1..PAGE_RETRY_ATTEMPTS) {
            try {
                return block()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (u: DispatcharrError.Unauthorized) {
                throw u
            } catch (t: Throwable) {
                warnUnlessCancelled("$label page failed (attempt $attempt of $PAGE_RETRY_ATTEMPTS)", t)
                if (attempt < PAGE_RETRY_ATTEMPTS) {
                    kotlinx.coroutines.delay(backoff)
                    backoff *= 3
                }
            }
        }
        return null
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
    //
    // Since GH #109 the browse library is in Room, so a title not already in
    // one of the in-memory maps is read from the catalog in the background:
    // the call returns null now and the state update that lands the row
    // recomposes the caller (screens key on catalogMovies / catalogSeries).
    fun seriesById(id: Int): DispatcharrVODSeries? =
        _state.value.catalogSeries[id]
            ?: _state.value.seriesSearchResults.firstOrNull { it.id == id }
            ?: _state.value.resolvedSeries[id]
            ?: run { requestCatalogSeries(id); null }

    fun movieById(id: Int): DispatcharrVODMovie? =
        _state.value.catalogMovies.values.firstOrNull { it.id == id }
            ?: _state.value.resolvedMovies.values.firstOrNull { it.id == id }

    fun movieByUuid(uuid: String): DispatcharrVODMovie? =
        _state.value.catalogMovies[uuid]
            ?: _state.value.searchResults.firstOrNull { it.uuid == uuid }
            ?: _state.value.resolvedMovies[uuid]
            ?: run { requestCatalogMovie(uuid); null }

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
        // No hint means no name search, but the stored catalog can still hold
        // the title (GH #109); the resolving flag keeps "not found" off screen
        // while that read runs.
        if (hint.isEmpty() && movieCatalogKey.value == null) return
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
        // The stored catalog first: the in-memory lookup above only kicked
        // off a background read, and a stored title needs no server search.
        movieCatalogKey.value?.let { key -> catalogStore.movie(key, uuid) }?.let { m ->
            _state.update { it.copy(catalogMovies = (it.catalogMovies + (uuid to m)).newest(CATALOG_LOOKUP_CACHE)) }
            return m
        }
        if (hint.isEmpty()) return null
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
                seriesCatalogKey.value?.let { k -> catalogStore.series(k, id) }?.let { stored ->
                    _state.update { it.copy(catalogSeries = (it.catalogSeries + (id to stored)).newest(CATALOG_LOOKUP_CACHE)) }
                    return@launch
                }
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

    /** Fold a display name for loose matching; shared with the catalog's
     *  normTitle column (see [com.aeriotv.android.core.data.vod.normalizeVodTitle]). */
    private fun normalizeVodTitle(raw: String): String = com.aeriotv.android.core.data.vod.normalizeVodTitle(raw)

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
        // The stored catalog stands in for the old in-memory browse list
        // (GH #109): an indexed tmdb id lookup, then the normalized title.
        if (item.isMovie) {
            val key = movieCatalogKey.value
            val loaded = _state.value.searchResults
            val match = key?.let { catalogStore.moviesByTmdbIds(it, listOf(item.id)).firstOrNull() }
                ?: loaded.firstOrNull { !it.tmdbId.isNullOrBlank() && it.tmdbId == item.id }
                ?: key?.let { catalogStore.moviesByNormTitlesWithoutTmdb(it, listOf(wantTitle)).firstOrNull() }
                ?: loaded.firstOrNull {
                    it.tmdbId.isNullOrBlank() && normalizeVodTitle(it.displayName) == wantTitle
                }
                ?: searchDispatcharrKnownForMovie(item, wantTitle)
                ?: return null
            return KnownForTarget.Movie(match.uuid)
        }
        val key = seriesCatalogKey.value
        val loaded = _state.value.seriesSearchResults
        val match = key?.let { catalogStore.seriesByTmdbIds(it, listOf(item.id)).firstOrNull() }
            ?: loaded.firstOrNull { !it.tmdbId.isNullOrBlank() && it.tmdbId == item.id }
            ?: key?.let { catalogStore.seriesByNormTitlesWithoutTmdb(it, listOf(wantTitle)).firstOrNull() }
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
        // Only the recommended ids and titles are read from the catalog.
        val recIds = recs.map { it.id }
        val recTitles = recs.map { cleanArtTitle(it.title) }
        val storedMovies = if (!isMovie) emptyList() else movieCatalogKey.value?.let {
            catalogStore.moviesByTmdbIds(it, recIds) + catalogStore.moviesByCleanTitles(it, recTitles)
        }.orEmpty()
        val storedSeries = if (isMovie) emptyList() else seriesCatalogKey.value?.let {
            catalogStore.seriesByTmdbIds(it, recIds) + catalogStore.seriesByCleanTitles(it, recTitles)
        }.orEmpty()
        return withContext(Dispatchers.Default) {
            val seen = mutableSetOf(selfKey)
            val out = mutableListOf<com.aeriotv.android.feature.movies.MediaItem>()
            // Apple's LibraryMatcher (VODModels.swift:1219-1239) over Apple's
            // exact pool: the browse list plus the search results, NOT the
            // lazily resolved-detail cache, which was adding titles tvOS never
            // sees and made the Android strip longer than the tvOS one
            // (Logan 2026-09-11). Every entry is indexed by BOTH its tmdb id
            // and its cleaned title, and a recommendation matches on the id
            // first and the title second, with no per-rec media-type filter:
            // the pool is already type-scoped.
            if (isMovie) {
                val library = storedMovies + snapshot.searchResults
                val byTmdb = HashMap<String, DispatcharrVODMovie>()
                val byTitle = HashMap<String, DispatcharrVODMovie>()
                for (m in library) {
                    m.tmdbId?.takeIf { it.isNotBlank() }?.let { byTmdb.putIfAbsent(it, m) }
                    cleanArtTitle(m.displayName).takeIf { it.isNotEmpty() }
                        ?.let { byTitle.putIfAbsent(it, m) }
                }
                for (rec in recs) {
                    val hit = byTmdb[rec.id] ?: byTitle[cleanArtTitle(rec.title)] ?: continue
                    val item = hit.toMediaItem()
                    if (seen.add(item.key)) out += item
                    if (out.size >= 12) break
                }
            } else {
                val library = storedSeries + snapshot.seriesSearchResults
                val byTmdb = HashMap<String, DispatcharrVODSeries>()
                val byTitle = HashMap<String, DispatcharrVODSeries>()
                for (s in library) {
                    s.tmdbId?.takeIf { it.isNotBlank() }?.let { byTmdb.putIfAbsent(it, s) }
                    cleanArtTitle(s.displayName).takeIf { it.isNotEmpty() }
                        ?.let { byTitle.putIfAbsent(it, s) }
                }
                for (rec in recs) {
                    val hit = byTmdb[rec.id] ?: byTitle[cleanArtTitle(rec.title)] ?: continue
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
        // Remember it so the pushed detail screen's movieByUuid resolves it.
        _state.update { st -> st.copy(resolvedMovies = st.resolvedMovies + (stamped.uuid to stamped)) }
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
        _state.update { st -> st.copy(resolvedSeries = st.resolvedSeries + (stamped.id to stamped)) }
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

    /**
     * One season's TMDB episodes, keyed by episode number, for the episode
     * cards (still image, title fallback, guest stars and crew). Same opt-in
     * + key gate and id-then-title resolution order as [resolveTmdbCredits].
     */
    suspend fun resolveTmdbSeason(
        tmdbId: String?,
        title: String,
        seasonNumber: Int,
    ): Map<Int, TmdbEpisodeInfo>? {
        if (!appPreferences.programPostersTmdbEnabled.first()) return null
        val key = appPreferences.tmdbApiKey.first()
        if (key.isBlank()) return null
        tmdbId?.takeIf { it.isNotBlank() }?.let { id ->
            tmdbService.seasonEpisodes(id, seasonNumber, key)?.let { return it }
        }
        if (title.isBlank()) return null
        return tmdbService.seasonEpisodesForTitle(title, seasonNumber, key)
    }

    /** Episode-still URL pass-through (same rationale as [tmdbProfileImageUrl]). */
    fun tmdbStillImageUrl(path: String?, size: String = "w780"): String? =
        path?.takeIf { it.isNotBlank() }?.let { tmdbService.imageUrlFor(it, size) }

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
                    // PR 92 parity: when the episodes listing is empty, take
                    // the seasons provider-info already embedded (its
                    // `episodes` map), so the series is not left with none.
                    val episodes = page.results.ifEmpty {
                        _state.value.seriesProviderInfo[seriesId]?.embeddedEpisodes.orEmpty()
                            .also { if (it.isNotEmpty()) android.util.Log.i("OnDemandVM", "series $seriesId: episodes listing empty, using ${it.size} from provider-info") }
                    }
                    _state.update { st ->
                        st.copy(
                            episodesBySeries = st.episodesBySeries + (seriesId to episodes),
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
        // movieByUuid only STARTS a catalog read on a miss (GH #109), so a
        // stored title is read here directly before the name search fallback.
        val movie = movieByUuid(movieUuid)
            ?: movieCatalogKey.value?.let { key -> catalogStore.movie(key, movieUuid) }
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
            movieCatalogKey.value = null
            seriesCatalogKey.value = null
            _state.update {
                it.copy(
                    unsupportedSource = true,
                    totalCount = 0, seriesTotalCount = 0,
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
        // The catalog is the library for Xtream too (GH #109): the panel's
        // one-shot answer is stored in chunks as a complete generation, and
        // the grids read it back like any Dispatcharr sweep.
        val identity = snapshotStore.identity(playlist)
        if (playlist.vodEnabled && playlist.dispatcharrCanViewVod()) movieCatalogKey.value = identity
        if (playlist.vodEnabled && playlist.dispatcharrCanViewSeries()) seriesCatalogKey.value = identity
        if (movieFast.isNotEmpty()) {
            storeWholeXtreamCatalog(identity, isMovie = true) { gen ->
                movieFast.chunked(XC_WRITE_CHUNK).forEach { chunk ->
                    catalogStore.writeMovies(identity, gen, chunk.map { it.toMovie(movieCategoryNames) })
                }
            }
        }
        if (seriesFast.isNotEmpty()) {
            storeWholeXtreamCatalog(identity, isMovie = false) { gen ->
                seriesFast.chunked(XC_WRITE_CHUNK).forEach { chunk ->
                    catalogStore.writeSeries(identity, gen, chunk.map { it.toSeries(seriesCategoryNames) })
                }
            }
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
        if (xtreamItemsLoaded) { persistSnapshot(MediaSweep.Both); enrichArt(isMovie = true); enrichArt(isMovie = false) }
    }

    /**
     * Store an Xtream library answered in one response as a complete catalog
     * generation: open a generation, let [write] store every chunk, then
     * close it, which removes titles the panel no longer lists. A write that
     * throws leaves the generation open and deletes nothing.
     */
    private suspend fun storeWholeXtreamCatalog(identity: String, isMovie: Boolean, write: suspend (generation: Long) -> Unit) {
        val kind = kindOf(isMovie)
        val plan = catalogStore.beginSweep(identity, kind, emptyList()) { "" }
        runCatching { write(plan.generation) }
            .onFailure { warnUnlessCancelled("XC catalog write failed", it); return }
        if (catalogStore.finishSweep(identity, kind, plan.generation) < 0) catalogStore.abandonSweep(identity, kind)
        publishXtreamGroups(identity, isMovie)
    }

    /** Header count, group names and a grid rebuild after an Xtream write. */
    private suspend fun publishXtreamGroups(identity: String, isMovie: Boolean) {
        val groups = catalogStore.distinctCategories(identity, kindOf(isMovie))
        _state.update { if (isMovie) it.copy(movieGroupNames = groups) else it.copy(seriesGroupNames = groups) }
        catalogChanged(isMovie, force = true)
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
            // Each category's answer is stored as it lands (GH #109) instead
            // of accumulating both libraries in memory; the grids rebuild
            // from the catalog at most every CATALOG_PUBLISH_THROTTLE_MS.
            val identity = snapshotStore.identity(playlist)
            if (movieCats.isNotEmpty()) movieCatalogKey.value = identity
            if (seriesCats.isNotEmpty()) seriesCatalogKey.value = identity
            val moviePlan = if (movieCats.isNotEmpty()) catalogStore.beginSweep(identity, VodCatalogStore.KIND_MOVIE, emptyList()) { "" } else null
            val seriesPlan = if (seriesCats.isNotEmpty()) catalogStore.beginSweep(identity, VodCatalogStore.KIND_SERIES, emptyList()) { "" } else null
            var movieFailures = 0
            var seriesFailures = 0
            coroutineScope {
                work.forEach { (kind, cat) ->
                    launch {
                        when (kind) {
                            XcKind.MOVIE -> {
                                val gen = moviePlan?.generation ?: return@launch
                                val items = runCatching { xtreamApi.getVodStreams(base, user, pass, cat) }
                                    .onFailure { movieFailures++; warnUnlessCancelled("XC movies category $cat failed", it) }
                                    .getOrDefault(emptyList())
                                runCatching { catalogStore.writeMovies(identity, gen, items.map { it.toMovie(movieCategoryNames) }) }
                                    .onFailure { movieFailures++; warnUnlessCancelled("XC movies category $cat store failed", it) }
                                catalogChanged(isMovie = true, force = false)
                            }
                            XcKind.SERIES -> {
                                val gen = seriesPlan?.generation ?: return@launch
                                val items = runCatching { xtreamApi.getSeries(base, user, pass, cat) }
                                    .onFailure { seriesFailures++; warnUnlessCancelled("XC series category $cat failed", it) }
                                    .getOrDefault(emptyList())
                                runCatching { catalogStore.writeSeries(identity, gen, items.map { it.toSeries(seriesCategoryNames) }) }
                                    .onFailure { seriesFailures++; warnUnlessCancelled("XC series category $cat store failed", it) }
                                catalogChanged(isMovie = false, force = false)
                            }
                        }
                    }
                }
            }
            // A generation with a failed category stays open (nothing deleted)
            // and the next walk resumes it; a clean one removes dropped titles.
            moviePlan?.let {
                if (movieFailures == 0 && catalogStore.finishSweep(identity, VodCatalogStore.KIND_MOVIE, it.generation) < 0) {
                    catalogStore.abandonSweep(identity, VodCatalogStore.KIND_MOVIE)
                }
                publishXtreamGroups(identity, isMovie = true)
            }
            seriesPlan?.let {
                if (seriesFailures == 0 && catalogStore.finishSweep(identity, VodCatalogStore.KIND_SERIES, it.generation) < 0) {
                    catalogStore.abandonSweep(identity, VodCatalogStore.KIND_SERIES)
                }
                publishXtreamGroups(identity, isMovie = false)
            }
            _state.update { it.copy(isLoading = false, isLoadingSeries = false) }
            xtreamItemsLoaded = true
            persistSnapshot(MediaSweep.Both)
            enrichArt(isMovie = true)
            enrichArt(isMovie = false)
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
                        duration = e.durationText?.let { JsonPrimitive(it) },
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

    /**
     * Dispatcher for a sweep: the shared low-priority background lane for a
     * quiet refresh, the ViewModel's own (main) context for a user-triggered
     * one, where the page-by-page paint is the point.
     */
    /**
     * How many Movies / TV Shows libraries are on screen right now.
     *
     * A FOREGROUND sweep with a library on screen is the user waiting for the
     * grid to fill, so it runs unpaced and with a small number of pages in
     * flight (Logan 2026-09-16). With nothing on screen there is no one
     * waiting, so it falls back to the paced walk that keeps the box free for
     * playback. A counter rather than a boolean: the two tabs can overlap
     * while one is being swapped for the other.
     */
    private val visibleLibraries = java.util.concurrent.atomic.AtomicInteger(0)

    /** Called by the Movies / TV Shows pages as they enter and leave. */
    fun setLibraryVisible(visible: Boolean) {
        if (visible) visibleLibraries.incrementAndGet()
        else visibleLibraries.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    private fun aLibraryIsOnScreen(): Boolean = visibleLibraries.get() > 0

    private fun sweepContext(background: Boolean) =
        if (background) BackgroundSweep.dispatcher else kotlin.coroutines.EmptyCoroutineContext

    /**
     * Pause between catalog pages. A background sweep also waits out any tune
     * that has not reached its first frame and any time spent backgrounded, so
     * it never competes with channel start time.
     */
    private suspend fun pace(background: Boolean) {
        if (background) {
            settleGate.awaitSweepWindow()
            kotlinx.coroutines.delay(BG_WALK_PACE_MS)
        } else {
            kotlinx.coroutines.delay(WALK_PACE_MS)
        }
    }

    private companion object {
        /** Pace for the quiet background sweep: twice the pause of the
         *  foreground walk, so a full library refresh costs the box almost
         *  nothing while the user watches TV. */
        private const val BG_WALK_PACE_MS = 500L
        /** Pause between catalog pages (Streamer 2026-09-03): the unpaced walk
         *  ran at ~7 pages/s across both passes and held the box at 30%+ CPU
         *  for minutes, which starved the tabs' rendering. */
        private const val WALK_PACE_MS = 250L
        const val TAG = "OnDemandViewModel"
        const val XC_MOVIE_PREFIX = "xc-movie-"
        const val XC_EP_PREFIX = "xc-ep-"

        // In-flight cap for the per-copy measurement fetches (see
        // loadMovieProviderMedia). Each uncached copy can cost the server an
        // upstream fetch, so a wide fan-out would hammer the provider.
        const val PROVIDER_MEDIA_CONCURRENCY = 3

        // VOD_TOTAL_CAP (40,000), VOD_PER_CATEGORY_CAP (5,000) and
        // MAX_EAGER_VOD_PAGES are gone (GH #109): the catalog is stored in
        // Room page by page, so nothing bounds memory by row count any more,
        // and the round-robin lane walk provides the fairness the per
        // category cap did. Apple still caps at 5,000 per kind (HomeView
        // totalCap) and needs the same change.

        /** Attempts per page before a lane is left for the next sweep. */
        const val PAGE_RETRY_ATTEMPTS = 3

        /** First retry wait; each later one is three times longer. */
        const val PAGE_RETRY_BASE_MS = 1_000L

        /** Sweeps running a lane may fail before its titles are kept as-is
         *  and the generation is allowed to close without it. */
        const val LANE_MAX_FAILURES = 3

        /** Pages one lane may walk in one sweep (1,000,000 rows at 100 per
         *  page): only a server whose `next` never ends reaches it. */
        const val LANE_PAGE_SAFETY = 10_000

        /** The lane name for the unfiltered walk (no category endpoint). */
        const val UNFILTERED_LANE = ""

        /** Minimum gap between progressive grid rebuilds during a sweep. */
        const val CATALOG_PUBLISH_THROTTLE_MS = 2_000L

        /**
         * Catalog pages in flight while a library is on screen (Logan
         * 2026-09-16). Small on purpose: NEVER more than three concurrent
         * requests against the server, which is the same in-flight cap the
         * per-copy provider fetches use. The paced background walk stays
         * strictly one at a time.
         */
        const val SWEEP_FOREGROUND_LANES = 3

        /** Titles kept in UiState.catalogMovies / catalogSeries. */
        const val CATALOG_LOOKUP_CACHE = 200

        /** Catalog rows per page of the background TMDB art pass. */
        const val ART_PAGE_SIZE = 1_000

        /** Xtream titles per catalog write transaction. */
        const val XC_WRITE_CHUNK = 500

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

/**
 * The sentinel category pill for hidden titles, shown at the top of the
 * Filter list only while something is hidden. The leading zero-width space
 * keeps it from colliding with a provider group actually named "Hidden";
 * it renders as plain "Hidden".
 */
const val HIDDEN_CATEGORY: String = "\u200BHidden"

/** Scope token for a playlist whose URL carries no host (AppPreferences). */
private const val ANY_PLAYLIST_SCOPE: String = "*"
