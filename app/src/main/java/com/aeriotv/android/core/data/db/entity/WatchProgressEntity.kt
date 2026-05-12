package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-video playback position persistence. Mirrors iOS `WatchProgress`
 * (Aerio/Models/VODModels.swift) — same fields, same purpose, same
 * "5-minute-from-end = completed" heuristic encoded by callers.
 *
 * `videoId` is the Dispatcharr UUID for movies or episodes (or the source
 * URL hash for future M3U/Xtream VOD support). One row per video; upsert on
 * every periodic tick from the player.
 */
@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val posterUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)
