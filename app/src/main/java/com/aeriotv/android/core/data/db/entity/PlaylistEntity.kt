package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Persistent playlist row. Mirrors iOS Aerio/Models/PlaylistModels.swift M3UPlaylist
 * (SwiftData @Model). Channels themselves are NOT stored — they're re-parsed from
 * [urlString] on demand, same as iOS. Keeping the schema minimal so future
 * sync via Drive AppData has a small payload.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val urlString: String,
    val channelCount: Int = 0,
    val lastRefreshedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
)
