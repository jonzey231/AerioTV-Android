package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchProgressEntity)

    @Query("SELECT * FROM watch_progress WHERE videoId = :videoId LIMIT 1")
    suspend fun getOnce(videoId: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE videoId = :videoId LIMIT 1")
    fun observe(videoId: String): Flow<WatchProgressEntity?>

    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<WatchProgressEntity>>

    @Query("DELETE FROM watch_progress WHERE videoId = :videoId")
    suspend fun delete(videoId: String)
}
