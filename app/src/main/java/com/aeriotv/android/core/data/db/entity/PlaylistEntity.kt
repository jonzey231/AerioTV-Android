package com.aeriotv.android.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aeriotv.android.core.data.SourceType
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
    /**
     * Dispatcharr account level captured at connect from
     * /api/accounts/users/me/ `user_level`: 10 = admin, 1 = standard,
     * 0 = streamer. Only admins (>= 10) can POST server-side recordings, so the
     * Record affordances are gated on this. Defaults to 10 (admin): existing
     * rows, non-Dispatcharr sources, and a failed capture stay
     * recording-capable. Added in DB v15 (preserving migration). Mirrors iOS
     * ServerConnection.dispatcharrUserLevel.
     *
     * `@ColumnInfo(defaultValue)` MUST match the v15 migration's
     * `DEFAULT 10`: a NOT NULL column added via ALTER needs a SQL default, and
     * Room rejects the schema on open if the entity doesn't declare the same
     * one. Without this annotation the app crashes on the first post-upgrade
     * launch (schema-validation IllegalStateException).
     */
    @ColumnInfo(defaultValue = "10")
    val dispatcharrUserLevel: Int = 10,

    /**
     * Per-playlist On Demand opt-in (iOS `ServerConnection.vodEnabled`, set at
     * onboarding via "Fetch On Demand from this playlist" in AddServerView /
     * EditServerView). When false the OnDemandViewModel skips the unfiltered
     * VOD + series fetches entirely and the On Demand tab disappears from the
     * top nav (MainScaffold.hasVodContent gates on this), saving the user the
     * multi-thousand-item sync when they only want Live TV from this server.
     * Doesn't change source-type semantics: a DispatcharrApiKey playlist with
     * vodEnabled=false is still a Dispatcharr playlist (Live TV / DVR / EPG
     * all behave normally), just opted out of the VOD fetch. M3U playlists
     * ignore this (they don't carry VOD anyway). Added in DB v16 (preserving
     * migration); existing rows default to true so behaviour is unchanged for
     * users who upgrade.
     *
     * @ColumnInfo(defaultValue) must match the v16 migration's `DEFAULT 1`
     * (Room SQLite stores Boolean as INTEGER); same schema-validation rules
     * as `dispatcharrUserLevel` above.
     */
    @ColumnInfo(defaultValue = "1")
    val vodEnabled: Boolean = true,
)
// TODO Phase 9 (Block Store): move apiKey + password out of Room into Google Play
// Block Store / EncryptedSharedPreferences. Room cleartext storage is acceptable for
// pre-1.0 dev iteration but ships of any real build need encrypted credential storage.

/**
 * True when this playlist is a Dispatcharr admin account (user_level >= 10),
 * the only level Dispatcharr permits to POST server-side recordings. Drives
 * whether the Record affordances surface (channel long-press, guide cell,
 * player More menu). Mirrors iOS ServerConnection.dispatcharrCanRecordToServer.
 */
fun PlaylistEntity.canRecordToServer(): Boolean =
    (sourceType == SourceType.DispatcharrApiKey.name ||
        sourceType == SourceType.DispatcharrUserPass.name) &&
        dispatcharrUserLevel >= 10

