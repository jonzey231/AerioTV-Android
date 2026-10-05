package com.aeriotv.android.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.capability.CAPABILITIES_SCHEMA
import com.aeriotv.android.core.data.capability.CAPABILITIES_TTL_MS
import com.aeriotv.android.core.data.capability.Capability
import com.aeriotv.android.core.data.capability.CapabilityCorrections
import com.aeriotv.android.core.data.capability.CapabilitySet
import com.aeriotv.android.core.data.capability.CapabilitySnapshot
import com.aeriotv.android.core.data.capability.deriveCapabilities
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

    /**
     * Cast audio (Logan 2026-09-12): the id of this server's built-in AAC
     * output profile ("Web Player (AAC Audio)", seeded locked+active by
     * Dispatcharr >= 0.30), captured from /api/core/outputprofiles/ at add,
     * on every refresh, and once at EPG load when still unknown.
     *
     * Casting appends `?output_profile=<id>` to /proxy/ts/stream/<uuid> so
     * the cast session receives stereo AAC and the phone passes it through
     * with NO decoding or transcoding of its own, while local playback
     * keeps the original AC-3 feed. THREE states, which is why it is
     * nullable:
     *   null = never looked up (capture it)
     *   [CAST_AAC_PROFILE_NONE] (-1) = looked up, this server has none
     *   > 0 = the profile id to request
     * Non-Dispatcharr sources leave it null and never use it. Added in
     * DB v29 (preserving migration; a nullable INTEGER column needs no
     * SQL default, like [dispatcharrProfileId]).
     */
    val dispatcharrCastAacProfileId: Int? = null,

    /**
     * Per-playlist User-Agent for this server's Dispatcharr API requests
     * (Apple `ServerConnection.customUserAgent`). Blank = the app default,
     * `AerioTV/<version> (Android; <model>)`.
     *
     * Its job is identification, not behavior: Dispatcharr's admin Stats
     * panel attributes traffic by User-Agent, so a household running several
     * boxes can tell them apart. Seeded into the client's per-host registry
     * by [com.aeriotv.android.core.network.DispatcharrClient.seedUserAgent]
     * from the one base-URL choke point every Dispatcharr call resolves
     * through.
     *
     * `@ColumnInfo(defaultValue = "")` MUST match the v35 migration's
     * `DEFAULT ''` or Room's schema validation fails on upgraded installs.
     */
    @ColumnInfo(defaultValue = "")
    val customUserAgent: String = "",

    /**
     * Fingerprint of this server's EPG sources list the cached guide was built
     * from (Logan 2026-09-12): the sorted "id:updated_at" pairs from
     * /api/epg/sources/, joined with commas, with sources that carry no
     * updated_at (dummy sources) contributing "id:none".
     *
     * Dispatcharr's ProgramData rows have no updated_at of their own, so this
     * list is the only way to learn that the guide changed WITHOUT
     * redownloading it: a source refresh replaces that source's programs
     * wholesale and bumps its updated_at. When the fingerprint differs from
     * the stored one, the grid coverage map is dropped so the window refetches
     * (a sporting event moved to another day must never keep showing the old
     * time); when it matches, every covered chunk is served from cache.
     *
     * Null means "never captured" (a new playlist, or a server whose sources
     * list is unreadable), which always reads as a change. Added in DB v30.
     */
    val dispatcharrEpgSourceFingerprint: String? = null,

    // ---------------------------------------------------------------------
    // Per-user capability SNAPSHOT (DB v33). The columns above
    // (dispatcharrDvrAccess / dispatcharrCatchupEnabled / the two vod flags)
    // are DERIVED conveniences kept for back-compat; these five are the raw
    // truth the server told us about THIS user, so a new Dispatcharr
    // permission key becomes a derivation change in
    // core/data/capability/DispatcharrCapability.kt rather than a migration.
    // ---------------------------------------------------------------------

    /** `is_staff` from /api/accounts/users/me/. Staff reads as effective level 10. */
    @ColumnInfo(defaultValue = "0")
    val dispatcharrIsStaff: Boolean = false,

    /** `is_superuser` from /api/accounts/users/me/. Also effective level 10. */
    @ColumnInfo(defaultValue = "0")
    val dispatcharrIsSuperuser: Boolean = false,

    /**
     * The FULL `custom_properties` object from /api/accounts/users/me/, stored
     * verbatim as JSON text ("" = never captured / none). Deliberately opaque:
     * Dispatcharr keeps adding per-user keys (dvr_access, vod_movies_enabled,
     * vod_series_enabled, catchup_enabled, allowed_m3u_profile_ids,
     * hide_adult_content, output_profile, ...) and the app must be able to read
     * a key it did not know about at install time.
     */
    @ColumnInfo(defaultValue = "")
    val dispatcharrCustomProperties: String = "",

    /** When the snapshot was last successfully read (epoch ms; 0 = never). */
    @ColumnInfo(defaultValue = "0")
    val dispatcharrCapabilitiesFetchedAt: Long = 0L,

    /**
     * Derivation schema the stored snapshot was written under. A row below
     * [com.aeriotv.android.core.data.capability.CAPABILITIES_SCHEMA] is treated
     * as having NO snapshot (every capability Unknown, so nothing is hidden)
     * and is force-re-probed once. This is the upgrade repair for users stuck
     * at view-only DVR under the old hard gates.
     */
    @ColumnInfo(defaultValue = "0")
    val dispatcharrCapabilitiesSchema: Int = 0,

    /**
     * True when the most recent probe FAILED and the stored snapshot is the
     * last good one. A failed probe never writes an empty snapshot over a good
     * one; it only flags it stale so the next opportunity re-probes.
     */
    @ColumnInfo(defaultValue = "0")
    val dispatcharrCapabilitiesStale: Boolean = false,

    /**
     * `system_settings.catchup_enabled` from /api/core/settings/ (readable at
     * level >= 1), as 1 / 0, or -1 when never read. Catch-up needs BOTH this
     * and the per-user flag, so an unread system flag leaves the capability
     * Unknown rather than denying it.
     */
    @ColumnInfo(defaultValue = "-1")
    val dispatcharrSystemCatchupEnabled: Int = -1,
)

/** Stored sentinel for "this server has no AAC output profile", so the
 *  lookup is not retried on every EPG load. */
const val CAST_AAC_PROFILE_NONE: Int = -1
// Credential columns (apiKey, username, password) are encrypted at rest via
// CredentialCipher (AndroidKeystore AES-256-GCM), applied transparently by the
// EncryptingPlaylistDao decorator that every consumer is wired to (audit task
// #53). Stored values are ciphertext; reads return cleartext, so these columns
// stay `String?` and call sites are unchanged. A one-time pass in
// AerioTVApplication re-encrypts rows written by older (plaintext) builds.

/**
 * The output profile id to request when casting a live channel from this
 * playlist, or null when there is none to request (never looked up, a
 * server without an AAC profile, or a non-Dispatcharr source).
 */
fun PlaylistEntity.castAacOutputProfileId(): Int? =
    if (!isDispatcharrDirectConnect()) null
    else dispatcharrCastAacProfileId?.takeIf { it > 0 }

/**
 * True when this playlist can create SERVER-side recordings.
 *
 * This is [Capability.CanManageDvr], not the old `user_level >= 10` admin bar:
 * Dispatcharr's recording writes are IsAdminOrDVRManager, so a standard account
 * whose `custom_properties.dvr_access` is "manage" can record and must see the
 * Record affordances. An unprobed account reads capable (Unknown never hides).
 */
fun PlaylistEntity.canRecordToServer(): Boolean =
    isDispatcharrDirectConnect() && dispatcharrCanManageDvr()

/**
 * This playlist's per-user capability snapshot, or null for a non-Dispatcharr
 * source / a row that has never been probed under the current schema.
 */
fun PlaylistEntity.capabilitySnapshot(): CapabilitySnapshot? {
    if (!isDispatcharrDirectConnect()) return null
    return CapabilitySnapshot(
        userLevel = dispatcharrUserLevel,
        isStaff = dispatcharrIsStaff,
        isSuperuser = dispatcharrIsSuperuser,
        customPropertiesJson = dispatcharrCustomProperties,
        fetchedAtMillis = dispatcharrCapabilitiesFetchedAt,
        schema = dispatcharrCapabilitiesSchema,
        isStale = dispatcharrCapabilitiesStale,
        systemCatchupEnabled = when (dispatcharrSystemCatchupEnabled) {
            1 -> true
            0 -> false
            else -> null
        },
    )
}

/**
 * The capabilities this playlist's account actually has, with any corrections
 * learned from server responses this session applied on top.
 *
 * Non-Dispatcharr sources are fully permissive (nothing is gated server-side).
 * A Dispatcharr row with no usable snapshot reads Unknown, which renders every
 * affordance ENABLED: the app never silently downgrades a user it has not
 * measured.
 */
fun PlaylistEntity.capabilities(): CapabilitySet {
    if (!isDispatcharrDirectConnect()) return CapabilitySet.PERMISSIVE
    return CapabilityCorrections.apply(id, deriveCapabilities(capabilitySnapshot()))
}

/** True when the snapshot is missing, past its TTL, or flagged stale. */
fun PlaylistEntity.capabilitiesNeedProbe(nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (!isDispatcharrDirectConnect()) return false
    if (dispatcharrCapabilitiesSchema < CAPABILITIES_SCHEMA) return true
    if (dispatcharrCapabilitiesFetchedAt <= 0L) return true
    if (dispatcharrCapabilitiesStale) return true
    return nowMillis - dispatcharrCapabilitiesFetchedAt >= CAPABILITIES_TTL_MS
}

/** Effective level: staff / superuser read as admin regardless of user_level. */
fun PlaylistEntity.effectiveUserLevel(): Int =
    if (dispatcharrIsStaff || dispatcharrIsSuperuser) 10 else dispatcharrUserLevel

fun PlaylistEntity.allows(capability: Capability): Boolean = capabilities().allows(capability)

fun PlaylistEntity.isDenied(capability: Capability): Boolean = capabilities().isDenied(capability)

/**
 * Effective DVR access (mirrors apps/channels/dvr_access.py): "none" / "view" /
 * "manage". Non-Dispatcharr sources are "manage" (their recordings are local
 * and never gated). An UNPROBED Dispatcharr row reads "manage" so nothing is
 * hidden before we have measured the account.
 */
fun PlaylistEntity.dispatcharrEffectiveDvrAccess(): String {
    val caps = capabilities()
    return when {
        caps.isDenied(Capability.CanViewDvr) -> "none"
        caps.allows(Capability.CanManageDvr) -> "manage"
        else -> "view"
    }
}

fun PlaylistEntity.dispatcharrCanViewDvr(): Boolean = capabilities().allows(Capability.CanViewDvr)

fun PlaylistEntity.dispatcharrCanManageDvr(): Boolean = capabilities().allows(Capability.CanManageDvr)

fun PlaylistEntity.dispatcharrCanUseCatchup(): Boolean =
    capabilities().allows(Capability.CanUseCatchup)

fun PlaylistEntity.dispatcharrCanViewVod(): Boolean = capabilities().allows(Capability.CanViewVod)

fun PlaylistEntity.dispatcharrCanViewSeries(): Boolean =
    capabilities().allows(Capability.CanViewSeries)

/**
 * Whether this playlist can use the player's Switch Stream picker.
 * POST /proxy/ts/change_stream is still IsAdmin on the server, so this resolves
 * to admin today; routing it through [Capability.CanSwitchStream] means a
 * future server change (a per-user key, say) is a one-line edit in
 * [deriveCapabilities], not a hunt through the player UI.
 */
fun PlaylistEntity.canSwitchStream(): Boolean =
    // FAIL CLOSED: admin-only, so an unknown level (never probed, identity
    // reset, probe not landed yet) hides the option instead of offering a
    // picker that would 403. Only a measured admin level shows it.
    isDispatcharrDirectConnect() && capabilities().isKnownAllowed(Capability.CanSwitchStream)

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
@Deprecated(
    "Hard admin gate. Use canSwitchStream() or capabilities()[Capability.X].",
    ReplaceWith("canSwitchStream()"),
)
fun PlaylistEntity.isDispatcharrAdmin(): Boolean = canSwitchStream()

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
        if (effectiveUserLevel() >= 10) "Dispatcharr Direct Connect - Admin API Key"
        else "Dispatcharr Direct Connect - Standard API Key"
    SourceType.XtreamCodes.name -> "Xtream Codes (XC)"
    SourceType.M3uUrl.name -> "M3U"
    // Unknown enum NAME (shouldn't happen; future-proofing) falls back to raw.
    else -> sourceType
}

/**
 * SHORT source-type label for the playlist row's badge pill (Settings phase 3,
 * Apple parity). [sourceTypeDisplayLabel] is the long form the Playlist Detail
 * Type row shows; a pill needs one or two words.
 */
fun PlaylistEntity.sourceTypeBadgeLabel(): String = when (sourceType) {
    SourceType.DispatcharrUserPass.name, SourceType.DispatcharrApiKey.name -> "Dispatcharr"
    SourceType.XtreamCodes.name -> "Xtream Codes"
    SourceType.M3uUrl.name -> "M3U Playlist"
    else -> sourceType
}

/**
 * The playlist row's SUBTITLE, one string on every form factor and both
 * platforms (Logan 2026-09-18): "<Type>" on its own, or "<Type> · <N>
 * channels" once a count is known. Never the URL - a provider URL can carry
 * the username and password in its query string, and these rows are the ones
 * users screenshot.
 *
 * A stored count of 0 means "not synced yet", not "no channels", so it is
 * omitted rather than shown as a zero.
 */
fun PlaylistEntity.playlistRowSubtitle(): String {
    val type = sourceTypeBadgeLabel()
    return if (channelCount > 0) "$type \u00B7 $channelCount channels" else type
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

/**
 * Guide Days (Logan 2026-09-11). The playlist's [PlaylistEntity.epgRetentionDays]
 * governs the guide window in BOTH directions. Valid stored values are 1..14
 * days, plus the sentinel [GUIDE_DAYS_ALL] (0) = "All Available": fetch one-day
 * chunks back and ahead until the server runs dry (two consecutive empty chunks
 * end a direction), bounded by [GUIDE_DAYS_ALL_MAX_BACK] /
 * [GUIDE_DAYS_ALL_MAX_AHEAD] so a huge server cannot run forever. The legacy
 * 30-day option is gone from the UI and reads back as All Available.
 */
const val GUIDE_DAYS_ALL: Int = 0

/** Hard floor for All Available history, in days. */
const val GUIDE_DAYS_ALL_MAX_BACK: Int = 30

/** Hard ceiling for All Available forward fetch, in days. */
const val GUIDE_DAYS_ALL_MAX_AHEAD: Int = 60

/** Clamp a selected/stored Guide Days value: 1..14, or [GUIDE_DAYS_ALL]. */
fun sanitizeGuideDays(value: Int): Int =
    if (value <= 0 || value >= 30) GUIDE_DAYS_ALL else value.coerceIn(1, 14)

/** Resolved Guide Days, or null when the playlist is set to All Available. */
fun resolveGuideDays(value: Int): Int? = sanitizeGuideDays(value).takeIf { it > 0 }
