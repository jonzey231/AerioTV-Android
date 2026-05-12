package com.aeriotv.android.feature.playlist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-scoped state for the source flow:
 *   Bootstrap -> (existing source? auto-refresh : Onboarding -> add) ->
 *   Main (tabs) -> tap channel -> Player.
 *
 * Onboarding supports multiple source types (M3U URL, Dispatcharr API key, etc.)
 * via a picker. The fields surface conditionally based on [UiState.sourceType].
 */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    enum class Phase { Bootstrapping, NeedsUrl, ChannelsReady }

    data class UiState(
        val phase: Phase = Phase.Bootstrapping,
        val sourceType: SourceType = SourceType.M3uUrl,
        /** User-supplied display name for the playlist (Phase 30 multi-playlist). */
        val name: String = "",
        /** Generic URL field; M3U URL for [SourceType.M3uUrl], base URL otherwise. */
        val url: String = "",
        val epgUrl: String = "",
        val apiKey: String = "",
        val username: String = "",
        val password: String = "",
        val playlist: PlaylistEntity? = null,
        val channels: List<M3UChannel> = emptyList(),
        val epgByChannel: Map<String, List<EPGProgramme>> = emptyMap(),
        val isEpgLoading: Boolean = false,
        val searchQuery: String = "",
        val selectedGroup: String = ALL_GROUPS,
        val sortMode: SortMode = SortMode.ByNumber,
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    companion object {
        const val ALL_GROUPS = "All"
        private const val TAG = "PlaylistViewModel"
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            val saved = repository.activePlaylist()
            if (saved == null) {
                _state.update { it.copy(phase = Phase.NeedsUrl) }
                return@launch
            }
            val sourceType = SourceType.entries.firstOrNull { it.name == saved.sourceType }
                ?: SourceType.M3uUrl
            _state.update {
                it.copy(
                    playlist = saved,
                    sourceType = sourceType,
                    name = saved.name.orEmpty(),
                    url = saved.urlString,
                    epgUrl = saved.epgUrl.orEmpty(),
                    apiKey = saved.apiKey.orEmpty(),
                    username = saved.username.orEmpty(),
                    password = saved.password.orEmpty(),
                    isLoading = true,
                )
            }
            repository.refresh(saved).fold(
                onSuccess = { channels ->
                    _state.update {
                        it.copy(
                            phase = Phase.ChannelsReady,
                            channels = channels,
                            isLoading = false,
                            error = null,
                        )
                    }
                    loadEpgIfConfigured(saved)
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            phase = Phase.NeedsUrl,
                            isLoading = false,
                            error = "Failed to refresh saved source: ${t.message ?: t::class.simpleName}",
                        )
                    }
                },
            )
        }
    }

    fun onSourceTypeChange(value: SourceType) {
        _state.update { it.copy(sourceType = value, error = null) }
    }
    fun onNameChange(value: String) {
        _state.update { it.copy(name = value, error = null) }
    }
    fun onUrlChange(value: String) {
        _state.update { it.copy(url = value, error = null) }
    }
    fun onEpgUrlChange(value: String) {
        _state.update { it.copy(epgUrl = value) }
    }
    fun onApiKeyChange(value: String) {
        _state.update { it.copy(apiKey = value, error = null) }
    }
    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value) }
    }
    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value) }
    }
    fun onSearchQueryChange(value: String) {
        _state.update { it.copy(searchQuery = value) }
    }
    fun onGroupSelected(group: String) {
        _state.update { it.copy(selectedGroup = group) }
    }

    fun onSortModeChange(mode: SortMode) {
        _state.update { it.copy(sortMode = mode) }
    }

    /** Pre-fill an M3U URL pair and immediately load. Debug-only path used by --es intent extras. */
    fun loadFromUrl(url: String, epgUrl: String? = null) {
        _state.update {
            it.copy(
                sourceType = SourceType.M3uUrl,
                url = url,
                epgUrl = epgUrl.orEmpty(),
                error = null,
            )
        }
        loadPlaylist()
    }

    /** Debug-only Dispatcharr auto-load to bypass keyboard typing on emulators. */
    fun loadFromDispatcharr(url: String, apiKey: String) {
        _state.update {
            it.copy(
                sourceType = SourceType.DispatcharrApiKey,
                url = url,
                apiKey = apiKey,
                error = null,
            )
        }
        loadPlaylist()
    }

    fun loadPlaylist() {
        val s = _state.value
        val url = s.url.trim()
        if (url.isEmpty()) {
            _state.update { it.copy(error = "Enter a URL") }
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.update { it.copy(error = "URL must start with http:// or https://") }
            return
        }
        if (!s.sourceType.isImplemented) {
            _state.update {
                it.copy(error = "${s.sourceType.displayName} support is coming in a later phase.")
            }
            return
        }
        if (s.sourceType == SourceType.DispatcharrApiKey && s.apiKey.isBlank()) {
            _state.update { it.copy(error = "API key is required") }
            return
        }
        if (s.sourceType == SourceType.DispatcharrUserPass &&
            (s.username.isBlank() || s.password.isBlank())) {
            _state.update { it.copy(error = "Username and password are required") }
            return
        }
        val epgUrl = s.epgUrl.trim().takeIf { it.isNotEmpty() }
        if (s.sourceType == SourceType.M3uUrl && epgUrl != null &&
            !epgUrl.startsWith("http://") && !epgUrl.startsWith("https://")) {
            _state.update { it.copy(error = "EPG URL must start with http:// or https://") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val request = PlaylistRepository.SaveRequest(
                sourceType = s.sourceType,
                name = s.name.trim().ifBlank { null },
                url = url,
                epgUrl = epgUrl,
                apiKey = s.apiKey.trim().ifBlank { null },
                username = s.username.trim().ifBlank { null },
                password = s.password.ifBlank { null },
            )
            repository.loadAndPersist(request, existingId = s.playlist?.id).fold(
                onSuccess = { (entity, channels) ->
                    _state.update {
                        it.copy(
                            phase = Phase.ChannelsReady,
                            playlist = entity,
                            channels = channels,
                            isLoading = false,
                            error = if (channels.isEmpty()) "No channels found." else null,
                        )
                    }
                    loadEpgIfConfigured(entity)
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            channels = emptyList(),
                            error = "Failed to load: ${t.message ?: t::class.simpleName}",
                        )
                    }
                },
            )
        }
    }

    private fun loadEpgIfConfigured(playlist: PlaylistEntity) {
        val sourceType = SourceType.entries.firstOrNull { it.name == playlist.sourceType }
            ?: SourceType.M3uUrl
        // M3uUrl only loads EPG when user provided an XMLTV URL. Dispatcharr derives one
        // from the base URL automatically.
        val willHaveEpg = when (sourceType) {
            SourceType.M3uUrl -> !playlist.epgUrl.isNullOrBlank()
            SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass -> true
            SourceType.XtreamCodes -> false
        }
        if (!willHaveEpg) {
            Log.i(TAG, "loadEpgIfConfigured: no EPG for sourceType=${playlist.sourceType}")
            return
        }
        viewModelScope.launch {
            Log.i(TAG, "loadEpgIfConfigured: fetching EPG for ${playlist.sourceType}")
            _state.update { it.copy(isEpgLoading = true) }
            repository.loadEpg(playlist).fold(
                onSuccess = { programmes ->
                    Log.i(TAG, "EPG loaded: ${programmes.size} programmes across ${programmes.map { it.channelId }.toSet().size} channels")
                    val byChannel: Map<String, List<EPGProgramme>> = programmes
                        .groupBy { it.channelId }
                        .mapValues { (_, list) -> list.sortedBy { it.startMillis } }
                    _state.update { it.copy(epgByChannel = byChannel, isEpgLoading = false) }
                },
                onFailure = { t ->
                    Log.w(TAG, "EPG load failed", t)
                    _state.update { it.copy(isEpgLoading = false) }
                },
            )
        }
    }

    /**
     * Re-fetch the active playlist (channels) and follow with EPG. Used by
     * Playlist Detail's "Refresh Playlist" action.
     */
    fun refreshPlaylist() {
        viewModelScope.launch {
            val active = repository.activePlaylist() ?: return@launch
            Log.i(TAG, "refreshPlaylist: re-loading ${active.name}")
            _state.update { it.copy(isLoading = true, error = null) }
            repository.refresh(active).fold(
                onSuccess = { channels ->
                    _state.update {
                        it.copy(
                            channels = channels,
                            isLoading = false,
                            error = if (channels.isEmpty()) "No channels found." else null,
                        )
                    }
                    loadEpgIfConfigured(active)
                },
                onFailure = { t ->
                    Log.w(TAG, "refreshPlaylist failed", t)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = "Refresh failed: ${t.message ?: t::class.simpleName}",
                        )
                    }
                },
            )
        }
    }

    /** Re-fetch the EPG without re-fetching the channel list. */
    fun refreshEpg() {
        viewModelScope.launch {
            val active = repository.activePlaylist() ?: return@launch
            loadEpgIfConfigured(active)
        }
    }

    /**
     * Apply user edits to the active playlist. Reuses [PlaylistRepository.loadAndPersist]
     * with `existingId` so the row's UUID stays stable. Mirrors iOS Edit Playlist
     * Save action — connection details + auth credentials + EPG URL can change
     * but the source type does not (iOS gates that too via a separate flow).
     */
    fun saveEdits(
        name: String,
        url: String,
        lanUrl: String?,
        epgUrl: String?,
        apiKey: String?,
        username: String?,
        password: String?,
    ) {
        viewModelScope.launch {
            val active = repository.activePlaylist() ?: return@launch
            val sourceType = SourceType.entries.firstOrNull { it.name == active.sourceType }
                ?: SourceType.M3uUrl
            _state.update { it.copy(isLoading = true, error = null) }
            val request = PlaylistRepository.SaveRequest(
                sourceType = sourceType,
                name = name.ifBlank { null },
                url = url.trim(),
                lanUrl = lanUrl?.trim()?.ifBlank { null },
                epgUrl = epgUrl?.trim()?.ifBlank { null },
                apiKey = apiKey?.trim()?.ifBlank { null },
                username = username?.trim()?.ifBlank { null },
                password = password?.ifBlank { null },
            )
            repository.loadAndPersist(request, existingId = active.id).fold(
                onSuccess = { (entity, channels) ->
                    _state.update {
                        it.copy(
                            playlist = entity,
                            channels = channels,
                            isLoading = false,
                            error = if (channels.isEmpty()) "No channels found." else null,
                        )
                    }
                    loadEpgIfConfigured(entity)
                },
                onFailure = { t ->
                    Log.w(TAG, "saveEdits failed", t)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = "Save failed: ${t.message ?: t::class.simpleName}",
                        )
                    }
                },
            )
        }
    }

    /**
     * Probe the playlist URL. v1 re-runs the channel fetch as a connectivity
     * test — same code path the bootstrap uses, so success means the source
     * still responds with parseable content.
     */
    fun testConnection() {
        viewModelScope.launch {
            val active = repository.activePlaylist() ?: return@launch
            Log.i(TAG, "testConnection: probing ${active.urlString}")
            repository.refresh(active)
                .onSuccess { Log.i(TAG, "testConnection: ok (${it.size} channels)") }
                .onFailure { Log.w(TAG, "testConnection failed", it) }
        }
    }

    /**
     * Observe the full set of saved playlists for the multi-playlist
     * switcher in Settings.
     */
    val allPlaylists: Flow<List<PlaylistEntity>> = repository.observeAll()

    /** Make [playlistId] active and load its channels. Mirrors the bootstrap
     * load-and-render flow, but skipping the JWT exchange the first-load does
     * for User+Pass since the apiKey is already cached on the row. */
    fun switchToPlaylist(playlistId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.switchActive(playlistId).fold(
                onSuccess = { (entity, channels) ->
                    _state.update {
                        it.copy(
                            phase = Phase.ChannelsReady,
                            playlist = entity,
                            channels = channels,
                            isLoading = false,
                            error = if (channels.isEmpty()) "No channels found." else null,
                        )
                    }
                    loadEpgIfConfigured(entity)
                },
                onFailure = { t ->
                    Log.w(TAG, "switchToPlaylist failed", t)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = "Switch failed: ${t.message ?: t::class.simpleName}",
                        )
                    }
                },
            )
        }
    }

    /** Persist a user-chosen ordering of playlists (top-to-bottom). Used by
     * the Playlists drag-to-reorder UI. */
    fun applyPlaylistOrder(orderedIds: List<String>) {
        viewModelScope.launch { repository.applyPlaylistOrder(orderedIds) }
    }

    /** Delete a saved playlist by id. If the deleted row was the active one,
     * fall back to the most-recent remaining playlist (or NeedsUrl if none). */
    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            val wasActive = repository.activePlaylist()?.id == playlistId
            repository.deletePlaylist(playlistId)
            if (wasActive) {
                val remaining = repository.allOnce()
                val next = remaining.firstOrNull()
                if (next == null) {
                    _state.update {
                        it.copy(phase = Phase.NeedsUrl, playlist = null, channels = emptyList())
                    }
                } else {
                    switchToPlaylist(next.id)
                }
            }
        }
    }

    fun clearPlaylist() {
        viewModelScope.launch {
            repository.clear()
            _state.update { UiState(phase = Phase.NeedsUrl) }
        }
    }
}

/** Find the programme containing `now` for a given channel. */
fun List<EPGProgramme>.nowPlaying(now: Long = System.currentTimeMillis()): EPGProgramme? =
    firstOrNull { it.startMillis <= now && now < it.endMillis }

/**
 * Channel-list sort options. Mirrors iOS sort menu (16:44:33 screenshot):
 * By Number / By Name / Favorites First.
 */
enum class SortMode(val label: String) {
    ByNumber("By Number"),
    ByName("By Name"),
    FavoritesFirst("Favorites First"),
}
