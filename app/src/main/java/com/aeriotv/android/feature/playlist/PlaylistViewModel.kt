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
                name = null,
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
            SourceType.DispatcharrApiKey -> true
            else -> false
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
