package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aeriotv.android.core.data.db.entity.FavoriteChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteChannelEntity)

    @Query("DELETE FROM favorite_channel WHERE channelId = :channelId")
    suspend fun delete(channelId: String)

    @Query("SELECT * FROM favorite_channel ORDER BY displayOrder ASC")
    fun observeAll(): Flow<List<FavoriteChannelEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_channel WHERE channelId = :channelId)")
    fun observeIsFavorite(channelId: String): Flow<Boolean>

    @Query("SELECT COUNT(*) FROM favorite_channel")
    fun observeCount(): Flow<Int>

    @Query("UPDATE favorite_channel SET displayOrder = :order WHERE channelId = :channelId")
    suspend fun setDisplayOrder(channelId: String, order: Long)

    /**
     * Persist the user's manual Favorites order in one transaction. Caller
     * passes channelIds top-to-bottom; we stamp displayOrder 0..n-1. observeAll
     * sorts by displayOrder ASC so the new order takes effect immediately.
     * Mirrors PlaylistDao.applyDisplayOrder + iOS `favoriteOrder`.
     */
    @androidx.room.Transaction
    suspend fun applyDisplayOrder(orderedChannelIds: List<String>) {
        orderedChannelIds.forEachIndexed { index, id -> setDisplayOrder(id, index.toLong()) }
    }
}
