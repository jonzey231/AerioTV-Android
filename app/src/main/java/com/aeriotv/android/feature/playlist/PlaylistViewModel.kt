package com.aeriotv.android.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.M3UChannel
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
 * App-scoped state for the playlist flow:
 *   Splash → (existing playlist? auto-refresh : UrlEntry → load) →
 *   ChannelList → tap → Player.
 *
 * Persistence is in [PlaylistRepository] (Room PlaylistEntity row). Channels
 * themselves are kept only in memory and re-parsed on every refresh — same as
 * iOS Aerio.
 */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    enum class Phase { Bootstrapping, NeedsUrl, ChannelsReady }

    data class UiState(
        val phase: Phase = Phase.Bootstrapping,
        val url: String = "",
        val playlist: PlaylistEntity? = null,
        val channels: List<M3UChannel> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
    )

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
            // Found a saved playlist; refresh channels in the background and surface
            // them when ready. UI can show the channel screen immediately with a
            // loading indicator if we want — for v1 we just wait.
            _state.update {
                it.copy(
                    playlist = saved,
                    url = saved.urlString,
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
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            phase = Phase.NeedsUrl,
                            isLoading = false,
                            error = "Failed to refresh saved playlist: ${t.message ?: t::class.simpleName}",
                        )
                    }
                }
            )
        }
    }

    fun onUrlChange(value: String) {
        _state.update { it.copy(url = value, error = null) }
    }

    /** Pre-fill URL and immediately attempt to load. Used by debug intent-extra and future deep links. */
    fun loadFromUrl(url: String) {
        _state.update { it.copy(url = url, error = null) }
        loadPlaylist()
    }

    fun loadPlaylist() {
        val url = _state.value.url.trim()
        if (url.isEmpty()) {
            _state.update { it.copy(error = "Enter a playlist URL") }
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.update { it.copy(error = "URL must start with http:// or https://") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.loadAndPersist(url = url, existingId = _state.value.playlist?.id).fold(
                onSuccess = { (entity, channels) ->
                    _state.update {
                        it.copy(
                            phase = Phase.ChannelsReady,
                            playlist = entity,
                            channels = channels,
                            isLoading = false,
                            error = if (channels.isEmpty()) "No channels found. Check the URL." else null,
                        )
                    }
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            channels = emptyList(),
                            error = "Failed to load: ${t.message ?: t::class.simpleName}",
                        )
                    }
                }
            )
        }
    }

    /** Clear the saved playlist and return to URL entry. */
    fun clearPlaylist() {
        viewModelScope.launch {
            repository.clear()
            _state.update {
                UiState(phase = Phase.NeedsUrl)
            }
        }
    }
}
