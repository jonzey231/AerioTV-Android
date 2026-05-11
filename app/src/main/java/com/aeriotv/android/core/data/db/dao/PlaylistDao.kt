package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists WHERE isActive = 1 ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE isActive = 1 ORDER BY createdAt DESC LIMIT 1")
    suspend fun firstActive(): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity)

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists")
    suspend fun clear()
}
