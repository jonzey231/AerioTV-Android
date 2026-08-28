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
    /** Child-safety: the connected account's assigned Channel Profile id(s),
     *  comma-joined ("", "44", "44,57"). Synced so a kids account locked to a
     *  profile on one device gets the same fail-closed channel filter on every
     *  device. Defaulted to "" so older snapshots (no key) decode and stay
     *  unfiltered. Mirrors iOS SyncedServer.dispatcharrChannelProfileIDs. */
    val dispatcharrAccountProfileIds: String = "",
    /** Task #52: per-playlist On Demand opt-in travels with the playlist
     *  (iOS ServerConnection.vodEnabled parity). Default true keeps older
     *  snapshots decodable and matches the entity default. */
    val vodEnabled: Boolean = true,
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
    // 2026-08-28 (DVR + typed-rail sync; all defaulted so v1 snapshots
    // still decode): classification travels with the row so a FRESH
    // device puts an episode/recording in the right rail instead of
    // defaulting everything to "movie". streamUrl / upNextQueue /
    // playlistId stay deliberately off the wire (device-local, and
    // recording URLs can embed hosts and auth material).
    val vodType: String = "movie",
    val seriesId: String? = null,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val isFinished: Boolean = false,
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

/** Task #52: one favorite channel + its manual-order slot (iOS
 *  favoriteChannelIDs + favoriteOrder parity). */
@Serializable
data class FavoriteSnapshotEntry(
    val channelId: String,
    val channelName: String,
    val displayOrder: Long,
    val addedAt: Long,
)

@Serializable
data class FavoritesSnapshot(
    val envelope: SyncEnvelope,
    val entries: List<FavoriteSnapshotEntry>,
)

@Serializable
data class PreferencesSnapshot(
    val envelope: SyncEnvelope,
    /** key → value map encoded as strings (DataStore keys can be Bool/Int/Double/String; we string-encode for the wire). */
    val keys: Map<String, String>,
)

/**
 * One server's sign-in details for the Drive credentials snapshot.
 *
 * TRUST MODEL (audit task #53, accepted resolution): username/password/apiKey
 * travel here in CLEARTEXT. On-device the same credentials are encrypted at rest
 * (CredentialCipher, AndroidKeystore), but the Drive copy is deliberately NOT
 * encrypted because the device-local Keystore key can't decrypt on a second
 * device, and zero-touch cross-device restore is a headline feature. The file
 * lives in the user's own Drive AppData space (`spaces=appDataFolder`): reachable
 * only by AerioTV via the user's own OAuth, never shown in the Drive UI, TLS in
 * transit. The user is told this once via the Sync-settings disclosure
 * (AppPreferences.credentialsSyncDisclosed) and can disable the Credentials sync
 * category. A future passphrase-derived key would be the way to encrypt this
 * cross-device without losing zero-touch restore.
 */
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
