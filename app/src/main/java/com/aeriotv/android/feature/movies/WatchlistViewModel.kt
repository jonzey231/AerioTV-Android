package com.aeriotv.android.feature.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.preferences.WatchlistStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Watchlist for the active playlist (Apple parity: WatchlistManager). */
@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val store: WatchlistStore,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val entries: Flow<List<WatchlistStore.Entry>> =
        playlistRepository.observeActiveId().flatMapLatest { _ ->
            store.observe(playlistRepository.activePlaylist()?.let(WatchlistStore::scopeFor))
        }

    private suspend fun scope(): String? = playlistRepository.activePlaylist()?.let(WatchlistStore::scopeFor)

    fun toggle(item: MediaItem) {
        viewModelScope.launch {
            val playlistId = scope()
            store.toggle(
                WatchlistStore.Entry(
                    key = item.key, title = item.title, posterUrl = item.posterUrl, year = item.year,
                    rating = item.rating, isMovie = item.movieUuid != null, playlistId = playlistId,
                ),
            )
        }
    }

    fun remove(key: String) {
        viewModelScope.launch { store.remove(key, scope()) }
    }
}
