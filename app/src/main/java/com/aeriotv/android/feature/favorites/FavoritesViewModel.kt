package com.aeriotv.android.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.db.dao.FavoriteChannelDao
import com.aeriotv.android.core.data.db.entity.FavoriteChannelEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Facade over the favorites Room table. Mirrors iOS FavoritesStore. Reads the
 * channelId set as a Flow so any composable observing favourites updates the
 * moment a toggle elsewhere in the app fires.
 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val dao: FavoriteChannelDao,
) : ViewModel() {

    val all: Flow<List<FavoriteChannelEntity>> = dao.observeAll()
    val count: Flow<Int> = dao.observeCount()

    fun observe(channelId: String): Flow<Boolean> = dao.observeIsFavorite(channelId)

    fun toggle(channel: M3UChannel) {
        viewModelScope.launch {
            val existing = dao.observeIsFavorite(channel.id).first()
            if (existing) {
                dao.delete(channel.id)
            } else {
                val now = System.currentTimeMillis()
                dao.upsert(
                    FavoriteChannelEntity(
                        channelId = channel.id,
                        channelName = channel.name,
                        displayOrder = now,
                        addedAt = now,
                    ),
                )
            }
        }
    }

    /**
     * Persist a user-chosen Favorites order (top-to-bottom channelIds). Backs
     * the drag-to-reorder gesture on the Favorites tab; mirrors iOS
     * `favoriteOrder`. The reorder UI commits on drag-end, not on every
     * onMove frame, so this hits Room once per gesture.
     */
    fun applyOrder(orderedChannelIds: List<String>) {
        viewModelScope.launch { dao.applyDisplayOrder(orderedChannelIds) }
    }
}
