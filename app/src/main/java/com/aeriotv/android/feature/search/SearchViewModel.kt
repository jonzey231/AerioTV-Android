package com.aeriotv.android.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.network.DispatcharrAuthBroker
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.DispatcharrVODMovie
import com.aeriotv.android.core.network.DispatcharrVODSeries
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Global Search (parity task #41, iOS Features/Search/SearchView.swift). A
 * single search field with four scope chips over a mixed result set:
 *   - All        = EPG programmes + all VOD (movies + series)
 *   - Movies     = VOD movies only
 *   - TV Shows   = VOD series only
 *   - EPG        = EPG programmes only
 *
 * EPG search is local (Room, via the already-built [PlaylistRepository.searchEpg],
 * now-forward window) and reuses the guide-jump path on tap. VOD search hits the
 * Dispatcharr server ?search= endpoint (whole-library), mirroring the On Demand
 * tab's setSearchQuery. Live-as-you-type with a 300ms debounce matching iOS.
 *
 * VOD search is currently Dispatcharr-only (the primary source); Xtream/M3U
 * sources return EPG results only for now (their loaded VOD lists live in the
 * On Demand ViewModel; a local-filter fallback is a follow-up).
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val dispatcharrClient: DispatcharrClient,
    private val dispatcharrAuth: DispatcharrAuthBroker,
) : ViewModel() {

    enum class Scope(val label: String) { All("All"), Movies("Movies"), Series("TV Shows"), Epg("EPG") }

    sealed interface Result {
        val key: String

        data class Epg(
            val programme: EPGProgramme,
            /** Resolved from the channel list by [guideMatchKey] (Logan
             *  2026-08-06: results show the real channel, not a bare time).
             *  Null when no channel matches the programme's key. */
            val channelName: String? = null,
            val channelLogo: String? = null,
        ) : Result {
            override val key = "epg-${programme.channelId}-${programme.startMillis}"
            val isLive: Boolean
                get() = System.currentTimeMillis() in programme.startMillis until programme.endMillis
        }

        data class Movie(val movie: DispatcharrVODMovie) : Result {
            override val key = "movie-${movie.uuid}"
        }

        data class Series(val series: DispatcharrVODSeries) : Result {
            override val key = "series-${series.id}"
        }
    }

    data class UiState(
        val query: String = "",
        val scope: Scope = Scope.All,
        val results: List<Result> = emptyList(),
        val isSearching: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        schedule()
    }

    fun onScopeChange(scope: Scope) {
        _state.update { it.copy(scope = scope) }
        if (_state.value.query.isNotBlank()) schedule()
    }

    private fun schedule() {
        searchJob?.cancel()
        val q = _state.value.query.trim()
        if (q.isEmpty()) {
            _state.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _state.update { it.copy(isSearching = true) }
            val scope = _state.value.scope
            val out = mutableListOf<Result>()

            if (scope == Scope.All || scope == Scope.Epg) {
                out += searchEpg(q)
            }
            if (scope != Scope.Epg) {
                out += searchVod(q, scope)
            }

            // Drop a stale response if the user kept typing past this query.
            if (_state.value.query.trim() != q) return@launch
            _state.update { it.copy(results = out, isSearching = false) }
        }
    }

    private suspend fun searchEpg(q: String): List<Result.Epg> {
        val playlistId = runCatching { playlistRepository.activePlaylist()?.id }.getOrNull() ?: return emptyList()
        val programmes = runCatching { playlistRepository.searchEpg(playlistId, q) }.getOrDefault(emptyList())
        val channels = channelsByMatchKey(playlistId)
        val now = System.currentTimeMillis()
        // Live-first, then soonest start (searchEpg already returns a now-forward
        // window ordered by startMillis). Cap 30 like iOS.
        return programmes
            .sortedWith(
                compareByDescending<EPGProgramme> { now in it.startMillis until it.endMillis }
                    .thenBy { it.startMillis },
            )
            .take(30)
            .map { prog ->
                // Cached programmes already carry the canonical guideMatchKey as
                // channelId (bridgeChannelIds runs at fetch), so a plain map hit
                // resolves the channel for name + logo.
                val ch = channels[prog.channelId.trim().lowercase()]
                Result.Epg(
                    programme = prog,
                    channelName = ch?.name?.takeIf { it.isNotBlank() },
                    channelLogo = ch?.tvgLogo?.takeIf { it.isNotBlank() },
                )
            }
    }

    /** Channel lookup by canonical [guideMatchKey], cached per playlist so
     *  live-as-you-type search doesn't re-read the channel cache per keystroke.
     *  Keys are lowercased to match the bridge's normalization. */
    private var channelKeyCache: Pair<String, Map<String, M3UChannel>>? = null
    private suspend fun channelsByMatchKey(playlistId: String): Map<String, M3UChannel> {
        channelKeyCache?.let { (cachedId, map) -> if (cachedId == playlistId) return map }
        val channels = runCatching { playlistRepository.loadCachedChannels(playlistId) }
            .getOrDefault(emptyList())
        val map = LinkedHashMap<String, M3UChannel>(channels.size * 2)
        for (ch in channels) map.putIfAbsent(ch.guideMatchKey.trim().lowercase(), ch)
        channelKeyCache = playlistId to map
        return map
    }

    private suspend fun searchVod(q: String, scope: Scope): List<Result> {
        val playlist = runCatching { playlistRepository.activePlaylist() }.getOrNull() ?: return emptyList()
        val sourceType = SourceType.entries.firstOrNull { it.name == playlist.sourceType }
        val isDispatcharr = sourceType == SourceType.DispatcharrApiKey || sourceType == SourceType.DispatcharrUserPass
        if (!isDispatcharr || playlist.apiKey.isNullOrBlank()) return emptyList()

        val base = playlist.urlString.trimEnd('/')
        val enc = URLEncoder.encode(q, "UTF-8")
        val out = mutableListOf<Result>()

        if (scope == Scope.All || scope == Scope.Movies) {
            val movies = runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getVODMoviesPage("$base/api/vod/movies/?search=$enc&page_size=100", key)
                }.results
            }.getOrDefault(emptyList())
            out += movies.take(25).map { Result.Movie(it) }
        }
        if (scope == Scope.All || scope == Scope.Series) {
            val series = runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getVODSeriesPage("$base/api/vod/series/?search=$enc&page_size=100", key)
                }.results
            }.getOrDefault(emptyList())
            out += series.take(25).map { Result.Series(it) }
        }
        return out
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
