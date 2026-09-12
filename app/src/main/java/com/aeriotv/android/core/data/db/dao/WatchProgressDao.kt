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

    /**
     * Every episode row of one series, newest first. Apple's detail page
     * queries `vodType == "episode"` and filters by seriesID in memory
     * (VODDetailView.swift:252-253); this is the same set, scoped in SQL so
     * the screen never depends on a recency window.
     */
    @Query("SELECT * FROM watch_progress WHERE vodType = 'episode' AND seriesId = :seriesId ORDER BY updatedAt DESC")
    fun observeEpisodesForSeries(seriesId: String): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<WatchProgressEntity>>

    @Query("DELETE FROM watch_progress WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC")
    suspend fun allOnce(): List<WatchProgressEntity>

    @Query("DELETE FROM watch_progress")
    suspend fun clear()
}
