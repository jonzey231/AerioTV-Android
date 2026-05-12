package com.aeriotv.android.feature.watchprogress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Persistence facade for the VOD player. Reads + writes the watch-progress
 * Room table. Mirrors iOS NowPlayingManager.currentWatchProgress (the iOS
 * port pushes progress via SyncManager too; the Android port keeps the local
 * write path here and defers cloud-sync to Phase 12).
 */
@HiltViewModel
class WatchProgressViewModel @Inject constructor(
    private val dao: WatchProgressDao,
) : ViewModel() {

    fun observe(videoId: String): Flow<WatchProgressEntity?> = dao.observe(videoId)

    suspend fun get(videoId: String): WatchProgressEntity? = dao.getOnce(videoId)

    /** Upserts the current playback position. Called every ~5s from the player. */
    fun save(
        videoId: String,
        title: String,
        posterUrl: String?,
        positionMs: Long,
        durationMs: Long,
    ) {
        viewModelScope.launch {
            dao.upsert(
                WatchProgressEntity(
                    videoId = videoId,
                    title = title,
                    posterUrl = posterUrl,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun delete(videoId: String) {
        viewModelScope.launch { dao.delete(videoId) }
    }
}
