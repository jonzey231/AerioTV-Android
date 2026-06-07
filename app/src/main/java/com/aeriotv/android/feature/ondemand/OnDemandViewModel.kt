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
import com.aeriotv.android.core.network.XtreamCodesApi
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
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
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val movies: List<DispatcharrVODMovie> = emptyList(),
        val totalCount: Int = 0,
        val searchQuery: String = "",
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
    ) {
        val visible: List<DispatcharrVODMovie> get() {
            val q = searchQuery.trim()
            if (q.isEmpty()) return movies
            return movies.filter { it.displayName.contains(q, ignoreCase = true) }
        }
        val visibleSeries: List<DispatcharrVODSeries> get() {
            val q = seriesSearchQuery.trim()
            if (q.isEmpty()) return series
            return series.filter { it.displayName.contains(q, ignoreCase = true) }
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

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
        _state.value = UiState()
    }

    fun setSearchQuery(value: String) {
        _state.update { it.copy(searchQuery = value) }
    }

    fun setSeriesSearchQuery(value: String) {
        _state.update { it.copy(seriesSearchQuery = value) }
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
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getVODMoviesFirstPage(playlist.urlString, key)
                }
            }.fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            movies = page.results,
                            totalCount = page.count,
                            error = null,
                        )
                    }
                    // Audit task #42: walk the `next` cursor in the background
                    // so the user gets the full library appended progressively
                    // instead of just the first 100. First-page paint already
                    // landed above so the grid is interactive; subsequent
                    // pages append as they arrive. De-dup on uuid in case two
                    // pages share a row.
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
                                        merged += m
                                        seen += m.uuid
                                    }
                                }
                                st.copy(movies = merged, totalCount = p.count)
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
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getVODSeriesFirstPage(playlist.urlString, key)
                }
            }.fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(
                            isLoadingSeries = false,
                            series = page.results,
                            seriesTotalCount = page.count,
                            seriesError = null,
                        )
                    }
                    // Audit task #42: same next-cursor walk as movies above.
                    // Series use `id` as the de-dup key. Stops on first error.
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
                                        merged += s
                                        seen += s.id
                                    }
                                }
                                st.copy(series = merged, seriesTotalCount = p.count)
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
    }

    fun seriesById(id: Int): DispatcharrVODSeries? =
        _state.value.series.firstOrNull { it.id == id }

    fun movieById(id: Int): DispatcharrVODMovie? =
        _state.value.movies.firstOrNull { it.id == id }

    fun movieByUuid(uuid: String): DispatcharrVODMovie? =
        _state.value.movies.firstOrNull { it.uuid == uuid }

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
         * Cap on the eager VOD next-cursor walk. A large Dispatcharr provider can
         * expose 30k+ movies (340+ pages) plus thousands of series; walking the
         * whole library on load fired ~420 back-to-back requests, ballooned the
         * Dalvik heap past 90MB, and starved the EPG + UI for minutes (Z Fold 5
         * field report: page 90/344 after 2 min, EPG never painting). Load a
         * browsable head eagerly; fetching the rest lazily on scroll is a
         * follow-up. 100 rows/page, so this is ~1,000 movies + ~1,000 series.
         */
        const val MAX_EAGER_VOD_PAGES = 10
    }
}
