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
    /**
     * For [SourceType.M3uUrl] this is the raw M3U URL.
     * For Dispatcharr / Xtream Codes this is the server base URL (no trailing slash
     * is required; the repository strips one if present). Derived endpoints
     * (`/output/m3u`, `/output/epg`, `/player_api.php`, etc.) live in
     * [PlaylistRepository], NOT in the entity.
     */
    val urlString: String,
    /**
     * Optional LAN-side URL for this server (e.g. `http://192.168.1.10:9191`).
     * When the device is connected to one of the user's saved home SSIDs
     * (AppPreferences.homeSsids) and this is non-null, network calls + stream
     * playback route through this URL instead of [urlString]. Mirrors iOS
     * Settings > Home WiFi LAN switching.
     */
    val lanUrlString: String? = null,
    /** Optional XMLTV URL when [sourceType] is [SourceType.M3uUrl]; ignored otherwise. */
    val epgUrl: String? = null,
    /** Source type as enum NAME (`M3uUrl`, `DispatcharrApiKey`, etc.). */
    val sourceType: String = "M3uUrl",
    /** Dispatcharr admin API key. Used as `X-API-Key` header. Sensitive — see TODO. */
    val apiKey: String? = null,
    /** Username for Dispatcharr (user/pass) and Xtream Codes flows. */
    val username: String? = null,
    /** Password for Dispatcharr (user/pass) and Xtream Codes. Sensitive — see TODO. */
    val password: String? = null,
    val channelCount: Int = 0,
    val lastRefreshedAt: Long? = null,
    val lastEpgRefreshedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    /**
     * User-controlled order index for the Playlists list. Mirrors iOS
     * `displayOrder` written by SettingsView's drag-reorder. New rows default
     * to 0 so the row sorts to the top until the user touches the order.
     * Pre-DB-v10 rows survive the destructive migration with displayOrder = 0
     * and bunch together at the top until the user reorders.
     */
    val displayOrder: Int = 0,
    /**
     * Optional Dispatcharr channel-profile id this playlist is scoped to.
     * When non-null, only channels that belong to that profile (per
     * `/api/channels/profiles/`) are surfaced in the channel list, guide,
     * search, and multiview; null means "All Channels" (no filter). Ignored
     * for non-Dispatcharr sources. Set via Settings -> Edit Playlist ->
     * Channel Profile. Added in DB v11 (preserving migration, not destructive).
     */
    val dispatcharrProfileId: Int? = null,
)
// TODO Phase 9 (Block Store): move apiKey + password out of Room into Google Play
// Block Store / EncryptedSharedPreferences. Room cleartext storage is acceptable for
// pre-1.0 dev iteration but ships of any real build need encrypted credential storage.

