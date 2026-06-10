package com.aeriotv.android.core.sync

import kotlinx.serialization.Serializable

/**
 * Wire-format payloads for each [SyncCategory]. Versioned so we can change
 * schemas across app updates without breaking older clients holding stale
 * snapshots — see [version] (currently always 1).
 *
 * Times are millis-since-epoch UTC. Strings are intentionally permissive so a
 * future iOS port (or vice versa) can round-trip the same files.
 */

@Serializable
data class SyncEnvelope(
    val version: Int = 1,
    val lastModified: Long,
    val device: String,
)

@Serializable
data class PlaylistSnapshotEntry(
    val id: String,
    val name: String,
    val sourceType: String,
    val urlString: String,
    val epgUrlString: String? = null,
    // LAN URL travels with the playlist: a sync RESTORE used to recreate the
    // entity without it, silently dropping the user's home-network URL.
    // Nullable default keeps old snapshots decodable.
    val lanUrlString: String? = null,
    val username: String? = null,
    val apiKey: String? = null,
    val lastUsedMillis: Long? = null,
)

@Serializable
data class PlaylistsSnapshot(
    val envelope: SyncEnvelope,
    val active: String? = null,
    val playlists: List<PlaylistSnapshotEntry>,
)

@Serializable
data class WatchProgressSnapshotEntry(
    val videoId: String,
    val title: String,
    val posterUrl: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Serializable
data class WatchProgressSnapshot(
    val envelope: SyncEnvelope,
    val entries: List<WatchProgressSnapshotEntry>,
)

@Serializable
data class ReminderSnapshotEntry(
    val reminderKey: String,
    val channelName: String,
    val programTitle: String,
    val startMillis: Long,
    val endMillis: Long,
)

@Serializable
data class RemindersSnapshot(
    val envelope: SyncEnvelope,
    val entries: List<ReminderSnapshotEntry>,
)

@Serializable
data class PreferencesSnapshot(
    val envelope: SyncEnvelope,
    /** key → value map encoded as strings (DataStore keys can be Bool/Int/Double/String; we string-encode for the wire). */
    val keys: Map<String, String>,
)

@Serializable
data class CredentialsSnapshotEntry(
    val playlistId: String,
    val username: String? = null,
    val password: String? = null,
    val apiKey: String? = null,
)

@Serializable
data class CredentialsSnapshot(
    val envelope: SyncEnvelope,
    val entries: List<CredentialsSnapshotEntry>,
)
