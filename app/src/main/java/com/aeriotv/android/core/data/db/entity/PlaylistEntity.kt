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
    /** Dispatcharr admin API key. Used as `X-API-Key` header. Encrypted at rest (see note below). */
    val apiKey: String? = null,
    /** Username for Dispatcharr (user/pass) and Xtream Codes flows. Encrypted at rest. */
    val username: String? = null,
    /** Password for Dispatcharr (user/pass) and Xtream Codes. Encrypted at rest (see note below). */
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
     * Dispatcharr 0.30 granular permissions (custom_properties on
     * /api/accounts/users/me/), captured at add and re-read on every
     * refresh. Defaults are the server's permissive defaults so existing
     * rows and non-Dispatcharr sources behave as before. dvr_access is ""
     * until learned; then the level decides (admin = manage, standard =
     * view, streamer = none), exactly as apps/channels/dvr_access.py does.
     * Added in DB v26 (preserving migration). Mirrors iOS 5d7763e.
     */
    @ColumnInfo(defaultValue = "")
    val dispatcharrDvrAccess: String = "",
    @ColumnInfo(defaultValue = "1")
    val dispatcharrCatchupEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val dispatcharrVodMoviesEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val dispatcharrVodSeriesEnabled: Boolean = true,
    /** /api/core/version/ ("" = unknown). Gates the multi-day EPG grid window. */
    @ColumnInfo(defaultValue = "")
    val dispatcharrServerVersion: String = "",

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

    /**
     * Child-safety: the Channel Profile id(s) ASSIGNED to the connected
     * Dispatcharr account on the server (/api/accounts/users/me/
     * `channel_profiles`), comma-joined ("", "44", "44,57"). Captured live on
     * every load and self-healed. When non-empty the channel load keeps only
     * channels in the UNION of these profiles' memberships -- a FAIL-CLOSED
     * child-safety filter distinct from the user-chosen [dispatcharrProfileId]
     * (fail-open). Empty ("") = no account profile = admin / non-Dispatcharr =
     * show all (back-compat). Added in DB v17 (preserving migration). Mirrors
     * iOS ServerConnection.dispatcharrChannelProfileIDs (commit 3eb4ae3d8).
     *
     * `@ColumnInfo(defaultValue = "")` MUST match the v17 migration's
     * `DEFAULT ''` or Room rejects the post-upgrade schema on open (same rule
     * as `dispatcharrUserLevel` / `vodEnabled` above).
     */
    @ColumnInfo(defaultValue = "")
    val dispatcharrAccountProfileIds: String = "",

    /**
     * How many days of ALREADY-AIRED guide data to keep in the EPG cache for
     * this source. Past programmes are what the catch-up "Watch" action hangs
     * off, so the cache must retain them after the upstream feed stops
     * covering them (feeds typically carry little or no history). 7-day
     * default matches the common provider catch-up window; user-configurable
     * per playlist in setup/edit. Added in DB v19 (preserving migration).
     *
     * `@ColumnInfo(defaultValue = "7")` MUST match the v19 migration's
     * `DEFAULT 7` (same schema-validation rule as the columns above).
     */
    @ColumnInfo(defaultValue = "7")
    val epgRetentionDays: Int = 7,

    /**
     * Task #49 (iOS ServerConnection.dispatcharrAuthMode parity): which auth
     * header shape this Dispatcharr server accepts. "" (default) = the legacy
     * dual shape (`X-API-Key` + `Authorization: ApiKey`) that every
     * Dispatcharr build accepts directly; "xapikey" = X-API-Key alone (for
     * reverse proxies that reject an unrecognised Authorization header);
     * "bearer" = `Authorization: Bearer`. Auto-detected by
     * [com.aeriotv.android.core.network.DispatcharrAuthBroker] when a 401
     * surfaces, exactly like iOS detects on Test Connection. Added in DB v23.
     */
    @ColumnInfo(defaultValue = "")
    val dispatcharrAuthMode: String = "",
)
// Credential columns (apiKey, username, password) are encrypted at rest via
// CredentialCipher (AndroidKeystore AES-256-GCM), applied transparently by the
// EncryptingPlaylistDao decorator that every consumer is wired to (audit task
// #53). Stored values are ciphertext; reads return cleartext, so these columns
// stay `String?` and call sites are unchanged. A one-time pass in
// AerioTVApplication re-encrypts rows written by older (plaintext) builds.

/**
 * True when this playlist is a Dispatcharr admin account (user_level >= 10),
 * the only level Dispatcharr permits to POST server-side recordings. Drives
 * whether the Record affordances surface (channel long-press, guide cell,
 * player More menu). Mirrors iOS ServerConnection.dispatcharrCanRecordToServer.
 */
fun PlaylistEntity.canRecordToServer(): Boolean =
    isDispatcharrDirectConnect() && dispatcharrCanManageDvr()

/**
 * Effective DVR access (Dispatcharr 0.30, mirrors apps/channels/dvr_access.py):
 * "none" / "view" / "manage". Non-Dispatcharr sources are "manage" (their
 * recordings are local and never gated).
 */
fun PlaylistEntity.dispatcharrEffectiveDvrAccess(): String {
    if (!isDispatcharrDirectConnect()) return "manage"
    if (dispatcharrUserLevel >= 10) return "manage"
    if (dispatcharrUserLevel < 1) return "none"
    return when (dispatcharrDvrAccess) {
        "none" -> "none"
        "manage" -> "manage"
        else -> "view"
    }
}

fun PlaylistEntity.dispatcharrCanViewDvr(): Boolean = dispatcharrEffectiveDvrAccess() != "none"
fun PlaylistEntity.dispatcharrCanManageDvr(): Boolean = dispatcharrEffectiveDvrAccess() == "manage"
fun PlaylistEntity.dispatcharrCanUseCatchup(): Boolean =
    !isDispatcharrDirectConnect() || dispatcharrCatchupEnabled

/** Whether the server is at least [minimum] ("0.30.0"); false when unknown. */
fun PlaylistEntity.dispatcharrVersionAtLeast(minimum: String): Boolean {
    if (!isDispatcharrDirectConnect() || dispatcharrServerVersion.isBlank()) return false
    fun parts(v: String) = v.split('.').take(3).map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    val a = parts(dispatcharrServerVersion); val b = parts(minimum)
    for (i in 0 until 3) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return true
}

/**
 * True when this playlist is a Dispatcharr Direct Connect source (API key or
 * user/pass) -- the only source type with the per-channel streams list +
 * change_stream endpoint the player's Switch Stream picker needs. XC and M3U
 * sources expose a single URL per channel with no streams API, so Switch Stream
 * is hidden for them (there is nothing to switch to).
 */
fun PlaylistEntity.isDispatcharrDirectConnect(): Boolean =
    sourceType == SourceType.DispatcharrApiKey.name ||
        sourceType == SourceType.DispatcharrUserPass.name

/**
 * True when this playlist is a Dispatcharr Direct Connect ADMIN account
 * (user_level >= 10). POST /proxy/ts/change_stream is IsAdmin on the server, so
 * only admin accounts can actually switch streams; the player's Switch Stream
 * option gates on this so a standard sub-account never sees an option that would
 * 403. Same admin bar as server-side recording (see [canRecordToServer]).
 */
fun PlaylistEntity.isDispatcharrAdmin(): Boolean =
    isDispatcharrDirectConnect() && dispatcharrUserLevel >= 10

/**
 * User-facing label for this playlist's source type. Single source of truth
 * for the Type row on Playlist Detail AND the playlist-row subtitle on the
 * Settings root, so the two never drift apart.
 *
 * Lives on the entity (not on [SourceType]) because the Dispatcharr API-key
 * wording splits Admin vs Standard on [PlaylistEntity.dispatcharrUserLevel]
 * (10 = admin; 1 = standard and 0 = streamer both read as Standard), which
 * only the row carries. Caveat: the column defaults to 10 when the
 * /api/accounts/users/me/ capture fails or for pre-DB-v15 rows, so those
 * read as Admin until the next successful connect (any refresh / Test
 * Connection) refreshes the level.
 */
fun PlaylistEntity.sourceTypeDisplayLabel(): String = when (sourceType) {
    SourceType.DispatcharrUserPass.name ->
        "Dispatcharr Direct Connect - Username & Password"
    SourceType.DispatcharrApiKey.name ->
        if (dispatcharrUserLevel >= 10) "Dispatcharr Direct Connect - Admin API Key"
        else "Dispatcharr Direct Connect - Standard API Key"
    SourceType.XtreamCodes.name -> "Xtream Codes (XC)"
    SourceType.M3uUrl.name -> "M3U"
    // Unknown enum NAME (shouldn't happen; future-proofing) falls back to raw.
    else -> sourceType
}

/**
 * The connected account's assigned Channel Profile ids parsed from the
 * comma-joined [PlaylistEntity.dispatcharrAccountProfileIds]. Empty list = no
 * account filter (show all). Tolerant of blanks / non-integers so a malformed
 * stored value can't crash the channel load. Mirrors iOS
 * ServerConnection.dispatcharrProfileIDList.
 */
fun PlaylistEntity.dispatcharrAccountProfileIdList(): List<Int> =
    dispatcharrAccountProfileIds
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }

