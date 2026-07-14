package com.aeriotv.android.core.preferences

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.longPreferencesKey
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.core.category.CustomCategoryEntry
import com.aeriotv.android.core.category.ProgramCategory
import com.aeriotv.android.core.security.CredentialCipher
import com.aeriotv.android.core.sync.SyncCategory
import com.aeriotv.android.ui.theme.AppTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aerio_prefs",
    produceMigrations = { _ ->
        // One-time cleanup. "Default Live TV View" used to be a Drive-synced pref,
        // so a phone's "list" default could clobber a TV's "guide" (and vice versa)
        // whenever devices synced -- the guide would silently open in List after a
        // sync. It is now a PER-DEVICE preference (iOS @AppStorage parity), so clear
        // the possibly-clobbered value exactly once. Each device then falls back to
        // its form-factor default (TV -> Guide, phone -> List) until the user picks
        // one locally, and that local choice never leaves the device.
        listOf(
            object : DataMigration<Preferences> {
                private val clearedFlag =
                    booleanPreferencesKey("default_live_tv_view_unsynced_cleared_v1")
                private val viewKey = stringPreferencesKey("default_live_tv_view")
                override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                    currentData[clearedFlag] != true
                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.toMutablePreferences().apply {
                        remove(viewKey)
                        set(clearedFlag, true)
                    }
                override suspend fun cleanUp() {}
            },
        )
    },
)

/** Guide timeline zoom bounds. 0.5x = twice the hours on screen, 2x = half. */
const val GUIDE_SCALE_MIN = 0.5f
const val GUIDE_SCALE_MAX = 2.0f

/**
 * Typed DataStore wrapper, mirroring the iOS @AppStorage registry
 * (project_aeriotv_ios_architecture.md section C). Each key has a Flow getter
 * and a suspend setter so screen state can stay reactive via collectAsState.
 *
 * Phase 8a lands the keys with visible UI: selectedTheme + defaultLiveTVView.
 * Remaining keys (App Behaviors / Multiview / Network / Sync / DVR / Developer)
 * follow as their sub-screens land.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: CredentialCipher,
) {
    private val store get() = context.appDataStore

    val selectedTheme: Flow<AppTheme> = store.data.map { prefs ->
        val raw = prefs[KEY_SELECTED_THEME] ?: AppTheme.Aerio.name
        AppTheme.entries.firstOrNull { it.name == raw } ?: AppTheme.Aerio
    }

    suspend fun setSelectedTheme(theme: AppTheme) {
        store.edit { it[KEY_SELECTED_THEME] = theme.name }
    }

    /**
     * Custom accent override (iOS ThemeManager useCustomAccent parity). When
     * the user enables this in Appearance, AerioTVTheme replaces the selected
     * preset's accentPrimary with this hex. Per iOS canon: a 6-char uppercase
     * hex string (without leading '#'). Empty string falls back to the preset's
     * own accent.
     */
    val useCustomAccent: Flow<Boolean> = store.data.map { it[KEY_USE_CUSTOM_ACCENT] ?: false }
    suspend fun setUseCustomAccent(value: Boolean) {
        store.edit { it[KEY_USE_CUSTOM_ACCENT] = value }
    }

    /**
     * iOS Issue #28 (`ui.showChannelLogos`). When off, the Live TV list hides
     * each channel's logo so long channel names use the full row width.
     * Default ON.
     */
    val showChannelLogos: Flow<Boolean> = store.data.map { it[KEY_SHOW_CHANNEL_LOGOS] ?: true }
    suspend fun setShowChannelLogos(value: Boolean) {
        store.edit { it[KEY_SHOW_CHANNEL_LOGOS] = value }
    }

    /**
     * GH #19 (`ui.showChannelNumbers` on iOS). When off, the Live TV list and
     * Guide hide the channel number column. Default ON.
     */
    val showChannelNumbers: Flow<Boolean> = store.data.map { it[KEY_SHOW_CHANNEL_NUMBERS] ?: true }
    suspend fun setShowChannelNumbers(value: Boolean) {
        store.edit { it[KEY_SHOW_CHANNEL_NUMBERS] = value }
    }

    /**
     * iOS Issue #26 player aspect mode: "fit" (letterbox, default), "zoom"
     * (crop to fill while preserving aspect), or "fill" (stretch). Maps to
     * Media3 AspectRatioFrameLayout RESIZE_MODE_FIT / ZOOM / FILL in the player.
     */
    val playerAspectMode: Flow<String> = store.data.map { it[KEY_PLAYER_ASPECT_MODE] ?: "fit" }
    suspend fun setPlayerAspectMode(value: String) {
        store.edit { it[KEY_PLAYER_ASPECT_MODE] = value }
    }

    val customAccentHex: Flow<String> = store.data.map { it[KEY_CUSTOM_ACCENT_HEX] ?: "" }
    suspend fun setCustomAccentHex(value: String) {
        store.edit { prefs ->
            val clean = value.trim().removePrefix("#").uppercase().take(6)
            if (clean.isBlank()) prefs.remove(KEY_CUSTOM_ACCENT_HEX)
            else prefs[KEY_CUSTOM_ACCENT_HEX] = clean
        }
    }

    /** iOS `displayScaleMovies` parity. 0.85 .. 1.25. Default 1.0. */
    val displayScaleMovies: Flow<Float> = store.data.map {
        (it[KEY_DISPLAY_SCALE_MOVIES] ?: 1.0).toFloat()
    }
    suspend fun setDisplayScaleMovies(value: Float) {
        store.edit { it[KEY_DISPLAY_SCALE_MOVIES] = value.toDouble() }
    }

    /** iOS `displayScaleLiveTV` parity. 0.85 .. 1.25. Default 1.0. */
    val displayScaleLiveTV: Flow<Float> = store.data.map {
        (it[KEY_DISPLAY_SCALE_LIVE_TV] ?: 1.0).toFloat()
    }
    suspend fun setDisplayScaleLiveTV(value: Float) {
        store.edit { it[KEY_DISPLAY_SCALE_LIVE_TV] = value.toDouble() }
    }

    /**
     * EPG guide timeline zoom. iOS `guideScale` parity: scales the hour-column
     * width so the user can fit more or fewer hours on screen. Clamped
     * [GUIDE_SCALE_MIN]..[GUIDE_SCALE_MAX]; default 1.0. Written by both the
     * pinch gesture (on gesture end) and the discrete zoom selector in the
     * guide top bar.
     */
    val guideScale: Flow<Float> = store.data.map {
        (it[KEY_GUIDE_SCALE] ?: 1.0).toFloat().coerceIn(GUIDE_SCALE_MIN, GUIDE_SCALE_MAX)
    }
    suspend fun setGuideScale(value: Float) {
        store.edit {
            it[KEY_GUIDE_SCALE] = value.coerceIn(GUIDE_SCALE_MIN, GUIDE_SCALE_MAX).toDouble()
        }
    }

    /**
     * Either "list" or "guide", or empty for "follow form-factor default".
     * Mirrors iOS `@AppStorage("defaultLiveTVView")`. Phase 5 used
     * `rememberSaveable` for view-mode state; Phase 8b moves it to DataStore
     * so the user's choice survives cold start.
     */
    val defaultLiveTVView: Flow<String> = store.data.map { prefs ->
        prefs[KEY_DEFAULT_LIVE_TV_VIEW] ?: ""
    }

    suspend fun setDefaultLiveTVView(value: String) {
        store.edit { it[KEY_DEFAULT_LIVE_TV_VIEW] = value }
    }

    // ── App Behaviors ────────────────────────────────────────────────────

    /**
     * iOS `appBehaviorsSkipLoadingScreen` parity. When true, Bootstrap navigates
     * to MainScaffold immediately and lets data hydrate in the background.
     */
    val skipLoadingScreen: Flow<Boolean> = store.data.map { it[KEY_SKIP_LOADING_SCREEN] ?: false }
    suspend fun setSkipLoadingScreen(value: Boolean) {
        store.edit { it[KEY_SKIP_LOADING_SCREEN] = value }
    }

    /**
     * iOS `appBehaviorsAppleTVChannelFlip` parity. Gates the Player vertical
     * swipe-flip (PlayerScreen.kt) and the future Apple TV-style D-pad nav.
     * Defaults to true so the v1.0 ship matches iOS's default.
     */
    val appleTVChannelFlip: Flow<Boolean> = store.data.map { it[KEY_APPLE_TV_CHANNEL_FLIP] ?: true }
    suspend fun setAppleTVChannelFlip(value: Boolean) {
        store.edit { it[KEY_APPLE_TV_CHANNEL_FLIP] = value }
    }

    /**
     * iOS `appBehaviorsAutoRecoverFrozenStreams` parity (#37, commit fe93531c).
     * When false the live stall watchdogs (stale-position reload + black-screen
     * reload) are disabled so a channel that restarts/stutters at OTA commercial
     * boundaries is left to recover on its own instead of being force-reloaded.
     * The cold-start no-data net (never-started dead stream) stays armed
     * regardless, iOS keeps that lifeline. Default true. Device-local, NOT
     * synced. Read per-tune by AerioExoPlayerHolder.
     */
    val autoRecoverFrozenStreams: Flow<Boolean> =
        store.data.map { it[KEY_AUTO_RECOVER_FROZEN_STREAMS] ?: true }
    suspend fun setAutoRecoverFrozenStreams(value: Boolean) {
        store.edit { it[KEY_AUTO_RECOVER_FROZEN_STREAMS] = value }
    }
    suspend fun autoRecoverFrozenStreamsOnce(): Boolean =
        store.data.first()[KEY_AUTO_RECOVER_FROZEN_STREAMS] ?: true

    /**
     * iOS TMDBPosters parity (Aerio VODService.swift). Opt-in, OFF by default:
     * when on AND a key is set, missing artwork (VOD posters, and later EPG
     * program posters) is filled from the user's OWN free TMDB key. The toggle
     * and key SYNC via Drive (snapshotSyncablePreferences) so they carry across
     * the user's devices -- the same model the app already uses for playlist
     * credentials (buildCredentialsSnapshot), stored in the user's own Drive
     * appData. (Encryption-at-rest for all synced credentials remains the
     * holistic job of task #53.)
     */
    val programPostersTmdbEnabled: Flow<Boolean> =
        store.data.map { it[KEY_PROGRAM_POSTERS_TMDB_ENABLED] ?: false }
    suspend fun setProgramPostersTmdbEnabled(value: Boolean) {
        store.edit { it[KEY_PROGRAM_POSTERS_TMDB_ENABLED] = value }
    }

    /**
     * Dolby (AC3/EAC3) bitstream passthrough over HDMI. Off by default:
     * many TVs decode the bitstream with latency Android reports as zero,
     * which the player cannot compensate, and it shows up as lip-sync drift
     * on live TV. Off means AerioTV decodes Dolby audio in-app and outputs
     * PCM on the latency-compensated path. Device-specific by nature, so
     * deliberately NOT part of the sync snapshot.
     */
    val audioPassthroughEnabled: Flow<Boolean> =
        store.data.map { it[KEY_AUDIO_PASSTHROUGH] ?: false }
    suspend fun setAudioPassthroughEnabled(value: Boolean) {
        store.edit { it[KEY_AUDIO_PASSTHROUGH] = value }
    }

    /**
     * The user's TMDB v3 API key OR v4 read-access token. Empty = unset.
     * Encrypted at rest (audit task #53): stored ciphertext, decrypted on read.
     * Legacy plaintext values written by older builds pass through unchanged.
     */
    val tmdbApiKey: Flow<String> = store.data.map { cipher.decrypt(it[KEY_TMDB_API_KEY]) ?: "" }
    suspend fun setTmdbApiKey(value: String) {
        store.edit { prefs ->
            val trimmed = value.trim()
            if (trimmed.isBlank()) prefs.remove(KEY_TMDB_API_KEY)
            else prefs[KEY_TMDB_API_KEY] = cipher.encrypt(trimmed) ?: trimmed
        }
    }

    /**
     * iOS `debugLoggingEnabled` parity (DeveloperSettingsView line 14). The
     * Settings -> Developer screen flips this on/off; the DebugLogger
     * singleton reads it on startup and on every change to know whether to
     * persist log lines to disk. Defaults off so a stock install never
     * burns storage on logs the user didn't ask for.
     */
    val debugLoggingEnabled: Flow<Boolean> = store.data.map { it[KEY_DEBUG_LOGGING_ENABLED] ?: false }
    suspend fun setDebugLoggingEnabled(value: Boolean) {
        store.edit { it[KEY_DEBUG_LOGGING_ENABLED] = value }
    }

    /**
     * iOS `appBehaviorsAutoResumeLastChannel` parity. Stub for now (Android
     * has no mini-player surface yet). Stored anyway so a future port can
     * flip it on without losing the user's prior choice.
     */
    val autoResumeLastChannel: Flow<Boolean> = store.data.map { it[KEY_AUTO_RESUME_LAST_CHANNEL] ?: false }
    suspend fun setAutoResumeLastChannel(value: Boolean) {
        store.edit { it[KEY_AUTO_RESUME_LAST_CHANNEL] = value }
    }

    /**
     * Last-played channel id. Written by PlayerScreen on every channel-flip
     * and read once by AerioTVNavHost on cold boot. Empty string means
     * "nothing to resume" (first launch / after Settings -> Change playlist).
     */
    val lastWatchedChannelId: Flow<String> = store.data.map { it[KEY_LAST_WATCHED_CHANNEL_ID] ?: "" }
    suspend fun setLastWatchedChannelId(value: String) {
        store.edit { prefs ->
            if (value.isBlank()) prefs.remove(KEY_LAST_WATCHED_CHANNEL_ID)
            else prefs[KEY_LAST_WATCHED_CHANNEL_ID] = value
        }
    }

    /**
     * Last app version the user dismissed the What's New sheet for. Compared
     * against [com.aeriotv.android.BuildConfig.VERSION_NAME] on cold launch;
     * a mismatch means the user just upgraded and the WhatsNewSheet pops once.
     * Blank = never seen, treated as "first-ever install" - we seed the value
     * silently and don't show the sheet (the onboarding flow is the more
     * relevant first-launch surface).
     */
    val lastSeenWhatsNewVersion: Flow<String> = store.data.map {
        it[KEY_LAST_SEEN_WHATSNEW_VERSION] ?: ""
    }
    suspend fun setLastSeenWhatsNewVersion(value: String) {
        store.edit { prefs -> prefs[KEY_LAST_SEEN_WHATSNEW_VERSION] = value }
    }
    suspend fun lastSeenWhatsNewVersionOnce(): String =
        store.data.first()[KEY_LAST_SEEN_WHATSNEW_VERSION].orEmpty()

    /**
     * LRU list of recently-played channel ids, most-recent first. Mirrors iOS
     * RecentChannelsStore (@AppStorage `recentChannelIDs`). Stored newline-
     * delimited to preserve order (a Preferences string Set would not).
     * Capped at [RECENT_CHANNELS_CAP]; the AddToMultiview sheet shows the
     * first few as its "Recent" section. PlayerScreen records on each flip.
     */
    val recentChannelIds: Flow<List<String>> = store.data.map { prefs ->
        val raw = prefs[KEY_RECENT_CHANNEL_IDS] ?: ""
        if (raw.isBlank()) emptyList()
        else raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotBlank) }
    }

    /**
     * Promote [channelId] to the front of the recents list, de-duplicating and
     * capping at [RECENT_CHANNELS_CAP]. No-op for blanks.
     */
    suspend fun recordRecentChannel(channelId: String) {
        val id = channelId.trim()
        if (id.isBlank()) return
        store.edit { prefs ->
            val existing = (prefs[KEY_RECENT_CHANNEL_IDS] ?: "")
                .split('\n')
                .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            val reordered = (listOf(id) + existing.filterNot { it == id }).take(RECENT_CHANNELS_CAP)
            prefs[KEY_RECENT_CHANNEL_IDS] = reordered.joinToString("\n")
        }
    }

    /**
     * Hidden group titles from Manage Groups. Newline-delimited list since
     * group names can include any character except newline. Empty string =
     * "no groups hidden", which is the default and matches iOS canon (all
     * groups visible at first launch).
     */
    val hiddenGroups: Flow<Set<String>> = store.data.map { prefs ->
        val raw = prefs[KEY_HIDDEN_GROUPS] ?: ""
        if (raw.isBlank()) emptySet()
        else raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet()
    }
    suspend fun setHiddenGroups(groups: Set<String>) {
        store.edit { prefs ->
            if (groups.isEmpty()) prefs.remove(KEY_HIDDEN_GROUPS)
            else prefs[KEY_HIDDEN_GROUPS] = groups.joinToString("\n")
        }
    }

    /**
     * Live TV group display order + sort mode (Manage Groups reorder; Android
     * enhancement, no iOS equivalent since iOS groups are source-ordered).
     * [groupOrder] is the user's manual order as a newline-delimited list of
     * group NAMES, preserving order (NOT a set). It is authoritative only when
     * [groupSortMode] is "Manual"; "Alphabetical" sorts A-Z at display time and
     * "Default" keeps the playlist's first-occurrence order. The saved order is
     * always reconciled against the live group list at display time, so renamed
     * or removed groups drop out and brand-new groups append.
     */
    val groupOrder: Flow<List<String>> = store.data.map { prefs ->
        val raw = prefs[KEY_GROUP_ORDER] ?: ""
        if (raw.isBlank()) emptyList()
        else raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotBlank) }
    }
    suspend fun setGroupOrder(order: List<String>) {
        store.edit { prefs ->
            if (order.isEmpty()) prefs.remove(KEY_GROUP_ORDER)
            else prefs[KEY_GROUP_ORDER] = order.joinToString("\n")
        }
    }
    val groupSortMode: Flow<String> = store.data.map { prefs ->
        prefs[KEY_GROUP_SORT_MODE] ?: "Default"
    }
    suspend fun setGroupSortMode(mode: String) {
        store.edit { prefs -> prefs[KEY_GROUP_SORT_MODE] = mode }
    }

    /**
     * Hidden VOD group titles, separately per Movies and Series. Same storage
     * shape as [hiddenGroups] above (newline-delimited); same semantics
     * (empty = nothing hidden, all visible). Mirrors iOS MoviesView's
     * `hiddenMovieGroups` UserDefaults key + TVShowsView's
     * `hiddenSeriesGroups` (StreamingAPIs MoviesView.swift:74,
     * TVShowsView.swift:27). Surfaced via ManageGroupsSheet from the On
     * Demand tab and consumed inside OnDemandViewModel to filter the lists.
     */
    val hiddenMovieGroups: Flow<Set<String>> = store.data.map { prefs ->
        val raw = prefs[KEY_HIDDEN_MOVIE_GROUPS] ?: ""
        if (raw.isBlank()) emptySet()
        else raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet()
    }
    suspend fun setHiddenMovieGroups(groups: Set<String>) {
        store.edit { prefs ->
            if (groups.isEmpty()) prefs.remove(KEY_HIDDEN_MOVIE_GROUPS)
            else prefs[KEY_HIDDEN_MOVIE_GROUPS] = groups.joinToString("\n")
        }
    }
    val hiddenSeriesGroups: Flow<Set<String>> = store.data.map { prefs ->
        val raw = prefs[KEY_HIDDEN_SERIES_GROUPS] ?: ""
        if (raw.isBlank()) emptySet()
        else raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet()
    }
    suspend fun setHiddenSeriesGroups(groups: Set<String>) {
        store.edit { prefs ->
            if (groups.isEmpty()) prefs.remove(KEY_HIDDEN_SERIES_GROUPS)
            else prefs[KEY_HIDDEN_SERIES_GROUPS] = groups.joinToString("\n")
        }
    }
    suspend fun autoResumeLastChannelOnce(): Boolean =
        store.data.first()[KEY_AUTO_RESUME_LAST_CHANNEL] ?: false
    suspend fun lastWatchedChannelIdOnce(): String =
        store.data.first()[KEY_LAST_WATCHED_CHANNEL_ID].orEmpty()

    /**
     * iOS `defaultTab` parity. Stores the AppTab enum name. Empty string means
     * "follow iOS default" (Live TV). MainScaffold reads this once on the
     * first composition after bootstrap completes.
     */
    val defaultTab: Flow<String> = store.data.map { it[KEY_DEFAULT_TAB] ?: "" }
    suspend fun setDefaultTab(value: String) {
        store.edit { it[KEY_DEFAULT_TAB] = value }
    }

    // ── Network ──────────────────────────────────────────────────────────

    /** iOS `networkTimeout` parity. Seconds. 5-60 step 5. Default 15 (iOS). */
    val networkTimeoutSecs: Flow<Double> = store.data.map { it[KEY_NETWORK_TIMEOUT] ?: 15.0 }
    suspend fun setNetworkTimeoutSecs(value: Double) {
        store.edit { it[KEY_NETWORK_TIMEOUT] = value }
    }

    /** iOS `maxRetries` parity. 0-10. Default 3 (iOS). */
    val maxRetries: Flow<Int> = store.data.map { it[KEY_MAX_RETRIES] ?: 3 }
    suspend fun setMaxRetries(value: Int) {
        store.edit { it[KEY_MAX_RETRIES] = value }
    }

    /**
     * iOS `streamBufferSize` parity. One of "small" / "default" / "large" /
     * "xlarge" matching the cache-time tiers in MPVPlayerView. PlayerScreen
     * passes the resolved milliseconds into MPV at init time.
     */
    val streamBufferSize: Flow<String> = store.data.map { it[KEY_STREAM_BUFFER_SIZE] ?: "default" }
    suspend fun setStreamBufferSize(value: String) {
        store.edit { it[KEY_STREAM_BUFFER_SIZE] = value }
    }

    /**
     * iOS `epgWindowHours` parity. How many hours wide the EPG Guide's
     * horizontal time strip spans. One of 6/12/24/36/48/72; the sentinel
     * value 0 means "All available" (Guide spans from now to the latest
     * loaded programme end). Default 24 — keeps the scroll manageable on a
     * phone while covering the rest of the day. The Guide always shows 1h of
     * history before "now" regardless of this value.
     */
    val epgWindowHours: Flow<Int> = store.data.map { it[KEY_EPG_WINDOW_HOURS] ?: 24 }
    suspend fun setEpgWindowHours(value: Int) {
        store.edit { it[KEY_EPG_WINDOW_HOURS] = value }
    }

    // ── Multiview ────────────────────────────────────────────────────────

    /**
     * iOS `multiviewAudioFocusStyle` parity. One of "centerIcon" (default)
     * / "grayPersistent" / "themeFading". Controls how the multiview grid
     * indicates which tile owns audio.
     */
    val multiviewAudioFocusStyle: Flow<String> = store.data.map {
        it[KEY_MULTIVIEW_AUDIO_FOCUS_STYLE] ?: "centerIcon"
    }
    suspend fun setMultiviewAudioFocusStyle(value: String) {
        store.edit { it[KEY_MULTIVIEW_AUDIO_FOCUS_STYLE] = value }
    }

    /**
     * iOS `multiviewTilePadding` parity. Adds gaps between tiles. Default ON
     * (iOS 56fe163ad flipped the default to 8pt gutters); users who set it
     * explicitly keep their choice.
     */
    val multiviewTilePadding: Flow<Boolean> = store.data.map {
        it[KEY_MULTIVIEW_TILE_PADDING] ?: true
    }
    suspend fun setMultiviewTilePadding(value: Boolean) {
        store.edit { it[KEY_MULTIVIEW_TILE_PADDING] = value }
    }

    /** iOS `multiviewTileCornersRounded` parity. Rounds tile corners. */
    val multiviewTileCornersRounded: Flow<Boolean> = store.data.map {
        it[KEY_MULTIVIEW_TILE_CORNERS_ROUNDED] ?: false
    }
    suspend fun setMultiviewTileCornersRounded(value: Boolean) {
        store.edit { it[KEY_MULTIVIEW_TILE_CORNERS_ROUNDED] = value }
    }

    /**
     * iOS `multiviewPerfWarningSuppressed` parity (issue #46). Set by the
     * "Don't Show Again" button on the soft-limit performance warning;
     * device-local, never reset (no Settings toggle on iOS either).
     */
    val multiviewPerfWarningSuppressed: Flow<Boolean> = store.data.map {
        it[KEY_MULTIVIEW_PERF_WARNING_SUPPRESSED] ?: false
    }
    suspend fun setMultiviewPerfWarningSuppressed(value: Boolean) {
        store.edit { it[KEY_MULTIVIEW_PERF_WARNING_SUPPRESSED] = value }
    }

    /**
     * iOS `multiviewLayoutMode` parity. One of "auto" (default) / "evenGrid" /
     * "spotlight" / "heroCorner". Selectable per-session from the tile context
     * menu; persisted so the choice survives a relaunch. Unknown values fall
     * back to "auto".
     */
    val multiviewLayoutMode: Flow<String> = store.data.map {
        it[KEY_MULTIVIEW_LAYOUT_MODE] ?: "auto"
    }
    suspend fun setMultiviewLayoutMode(value: String) {
        store.edit { it[KEY_MULTIVIEW_LAYOUT_MODE] = value }
    }

    // ── Channel Collections (issue #45) ─────────────────────────────────

    /**
     * Lenient decoder for the collections blob. `ignoreUnknownKeys = true` so a
     * FUTURE schema field (added in a later app version) survives a downgrade:
     * the older build ignores the unknown key instead of failing the decode and
     * treating the whole blob as empty. Paired with the no-overwrite-on-failure
     * guard in [updateChannelCollections], this makes a user's curated
     * collections resistant to accidental wipes.
     */
    private val collectionsJson = Json { ignoreUnknownKeys = true }

    /**
     * iOS `ChannelCollectionsStore` parity: the whole collection list as one
     * JSON blob (iOS key "channelCollections" in UserDefaults). Device-local;
     * deliberately NOT Drive-synced, matching iOS's not-in-KVS v1. A corrupt
     * blob decodes to an empty list rather than crashing the filter row.
     */
    val channelCollections: Flow<List<ChannelCollection>> = store.data.map { prefs ->
        val raw = prefs[KEY_CHANNEL_COLLECTIONS]
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { collectionsJson.decodeFromString<List<ChannelCollection>>(raw) }
                .getOrDefault(emptyList())
        }
    }

    /**
     * Atomic read-modify-write for every collection mutation (create /
     * delete / placement / membership). One writer inside store.edit means
     * two rapid menu actions can never lose an update to a stale snapshot.
     *
     * Data-safety: if a NON-blank blob fails to decode (corrupt, or written by
     * an incompatible future schema), the mutation is ABORTED rather than
     * overwriting the blob with a partial list -- otherwise a single decode
     * regression would silently destroy every saved collection.
     */
    suspend fun updateChannelCollections(
        transform: (List<ChannelCollection>) -> List<ChannelCollection>,
    ) {
        store.edit { prefs ->
            val raw = prefs[KEY_CHANNEL_COLLECTIONS]
            val current: List<ChannelCollection> = when {
                raw.isNullOrBlank() -> emptyList()
                else -> {
                    val decoded = runCatching {
                        collectionsJson.decodeFromString<List<ChannelCollection>>(raw)
                    }.getOrNull()
                    // Non-blank but undecodable: do not clobber it with a
                    // partial write; leave the stored blob untouched.
                    if (decoded == null) return@edit
                    decoded
                }
            }
            val next = transform(current)
            if (next.isEmpty()) {
                prefs.remove(KEY_CHANNEL_COLLECTIONS)
            } else {
                prefs[KEY_CHANNEL_COLLECTIONS] = collectionsJson.encodeToString(next)
            }
        }
    }

    // ── Category Palette ────────────────────────────────────────────────
    //
    // Master enable + per-bucket hex overrides + per-bucket enable + custom
    // JSON list. Mirrors iOS @AppStorage keys `enableCategoryColors`,
    // `categoryColor.<suffix>`, `categoryBucketEnabled.<suffix>`,
    // `customCategoryColors.v1`. The whole snapshot is exposed as a single
    // Flow so consumers can collectAsState once per screen and call
    // CategoryPaletteState.tintFor inline without observing 23 separate keys.

    val categoryPalette: Flow<CategoryPaletteState> = store.data.map { prefs ->
        val master = prefs[KEY_CATEGORY_MASTER_ENABLE] ?: true
        val overrides = ProgramCategory.entries.mapNotNull { bucket ->
            prefs[stringPreferencesKey(bucket.hexStorageKey)]?.let { bucket.storageSuffix to it }
        }.toMap()
        val enabledFlags = ProgramCategory.entries.mapNotNull { bucket ->
            prefs[booleanPreferencesKey(bucket.enabledStorageKey)]?.let { bucket.storageSuffix to it }
        }.toMap()
        val customRaw = prefs[KEY_CATEGORY_CUSTOM_JSON]
        val custom: List<CustomCategoryEntry> = if (customRaw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { Json.decodeFromString<List<CustomCategoryEntry>>(customRaw) }
                .getOrDefault(emptyList())
        }
        CategoryPaletteState(
            masterEnabled = master,
            overrides = overrides,
            enabledFlags = enabledFlags,
            custom = custom,
        )
    }

    suspend fun setCategoryColorsEnabled(value: Boolean) {
        store.edit { it[KEY_CATEGORY_MASTER_ENABLE] = value }
    }

    suspend fun setCategoryBucketHex(bucket: ProgramCategory, hex: String?) {
        store.edit { prefs ->
            val key = stringPreferencesKey(bucket.hexStorageKey)
            if (hex.isNullOrBlank()) prefs.remove(key)
            else prefs[key] = hex.uppercase().removePrefix("#").take(6)
        }
    }

    suspend fun setCategoryBucketEnabled(bucket: ProgramCategory, enabled: Boolean) {
        store.edit { it[booleanPreferencesKey(bucket.enabledStorageKey)] = enabled }
    }

    suspend fun resetCategoryPalette() {
        store.edit { prefs ->
            ProgramCategory.entries.forEach { bucket ->
                prefs.remove(stringPreferencesKey(bucket.hexStorageKey))
            }
        }
    }

    suspend fun setCustomCategories(list: List<CustomCategoryEntry>) {
        store.edit { prefs ->
            if (list.isEmpty()) {
                prefs.remove(KEY_CATEGORY_CUSTOM_JSON)
            } else {
                prefs[KEY_CATEGORY_CUSTOM_JSON] = Json.encodeToString(list)
            }
        }
    }

    // ── Drive Sync ──────────────────────────────────────────────────────
    //
    // Mirrors iOS Settings > iCloud Sync. Per-category toggles control which
    // snapshots get pushed/pulled on each manual sync. Last push/pull
    // timestamps drive the UI's "Last synced 5 min ago" caption.

    val syncMasterEnabled: Flow<Boolean> = store.data.map { it[KEY_SYNC_MASTER] ?: false }
    suspend fun setSyncMasterEnabled(value: Boolean) {
        store.edit { it[KEY_SYNC_MASTER] = value }
    }

    /**
     * Audit task #48: master toggle for the periodic PlaylistRefreshWorker.
     * Default `true` so a fresh install gets the warm-cache benefit without
     * the user having to opt in. Network Settings surfaces the toggle so
     * users on a metered/restricted connection can switch it off.
     */
    val backgroundRefreshEnabled: Flow<Boolean> =
        store.data.map { it[KEY_BG_REFRESH_ENABLED] ?: true }
    suspend fun setBackgroundRefreshEnabled(value: Boolean) {
        store.edit { it[KEY_BG_REFRESH_ENABLED] = value }
    }

    /**
     * iOS `bgRefreshIntervalMins` parity (Aerio Settings:3299). How often the
     * PlaylistRefreshWorker re-fetches channels + EPG when
     * [backgroundRefreshEnabled] is on. Default 360 minutes (6 hours) matches
     * the prior hardcoded `PlaylistRefreshWorker.PERIOD_HOURS = 6L` so
     * upgrading users see no behaviour change unless they pick a different
     * interval. Range: 60 minutes (Android WorkManager minimum is 15min but
     * we cap at 60 so users can't accidentally drain a battery) up to 2880
     * minutes (48h). Stored as minutes for iOS parity even though
     * WorkManager accepts hours.
     */
    val backgroundRefreshIntervalMins: Flow<Int> =
        store.data.map { it[KEY_BG_REFRESH_INTERVAL_MINS] ?: 360 }
    suspend fun setBackgroundRefreshIntervalMins(value: Int) {
        // Clamp to the valid range so the UI selector can't ship a value
        // that WorkManager will reject (Periodic Work requires >= 15min;
        // we conservatively floor at 60 to keep battery use reasonable).
        val clamped = value.coerceIn(60, 2880)
        store.edit { it[KEY_BG_REFRESH_INTERVAL_MINS] = clamped }
    }

    fun syncCategoryEnabled(category: SyncCategory): Flow<Boolean> = store.data.map { prefs ->
        prefs[booleanPreferencesKey(category.enabledStorageKey())] ?: true
    }
    suspend fun setSyncCategoryEnabled(category: SyncCategory, value: Boolean) {
        store.edit { it[booleanPreferencesKey(category.enabledStorageKey())] = value }
    }

    val syncAccountEmail: Flow<String> = store.data.map { it[KEY_SYNC_ACCOUNT_EMAIL] ?: "" }
    /** One-shot read of the saved sync account email. Blank when the user has
     * never signed in to Drive, used to gate silent re-authorization. */
    suspend fun syncAccountEmailOnce(): String = syncAccountEmail.first()

    /**
     * Persist the Drive access token + its (estimated) expiry so a signed-in
     * session survives process death. The access token is short-lived (~1h),
     * so [expiryMs] is a conservative wall-clock deadline after which callers
     * should refresh rather than trust the cached value.
     */
    suspend fun saveSyncToken(token: String, expiryMs: Long) {
        // Encrypted at rest (audit task #53). Device-local OAuth token, never
        // part of the Drive snapshot, so plain Keystore encryption is enough.
        val stored = cipher.encrypt(token) ?: token
        store.edit { prefs ->
            prefs[KEY_SYNC_ACCESS_TOKEN] = stored
            prefs[KEY_SYNC_TOKEN_EXPIRY] = expiryMs
        }
    }

    /** Saved (token, expiryMs) pair, or null when none is stored. */
    suspend fun syncTokenOnce(): Pair<String, Long>? {
        val prefs = store.data.first()
        val raw = prefs[KEY_SYNC_ACCESS_TOKEN]?.takeIf { it.isNotBlank() } ?: return null
        // decrypt() returns the legacy plaintext unchanged, the real token for
        // ciphertext, or null if the Keystore key was lost -> treat as signed out.
        val token = cipher.decrypt(raw)?.takeIf { it.isNotBlank() } ?: return null
        val expiry = prefs[KEY_SYNC_TOKEN_EXPIRY] ?: 0L
        return token to expiry
    }

    suspend fun clearSyncToken() {
        store.edit { prefs ->
            prefs.remove(KEY_SYNC_ACCESS_TOKEN)
            prefs.remove(KEY_SYNC_TOKEN_EXPIRY)
        }
    }
    suspend fun setSyncAccountEmail(value: String) {
        store.edit { prefs ->
            if (value.isBlank()) prefs.remove(KEY_SYNC_ACCOUNT_EMAIL)
            else prefs[KEY_SYNC_ACCOUNT_EMAIL] = value
        }
    }

    val syncLastPushAt: Flow<Long> = store.data.map { it[KEY_SYNC_LAST_PUSH] ?: 0L }
    suspend fun setSyncLastPushAt(value: Long) {
        store.edit { it[KEY_SYNC_LAST_PUSH] = value }
    }

    /**
     * One-time guard for the post-upgrade pass that re-encrypts existing
     * plaintext playlist credentials at rest (audit task #53). Idempotent: the
     * pass is safe to re-run if the flag never persists (decrypt -> cleartext ->
     * re-encrypt), this just avoids the redundant write on every cold start.
     */
    suspend fun credentialsEncryptedOnce(): Boolean =
        store.data.first()[KEY_CREDS_ENCRYPTED_V1] ?: false
    suspend fun setCredentialsEncrypted(value: Boolean) {
        store.edit { it[KEY_CREDS_ENCRYPTED_V1] = value }
    }

    /**
     * Whether the one-time "your server credentials sync to your Drive in
     * cleartext" disclosure has been shown (audit task #53). Server credentials
     * are encrypted at rest on-device, but the Drive AppData snapshot carries
     * them in cleartext so any of the user's devices can restore them with no
     * re-typing; this flag gates a single up-front notice of that trust model.
     */
    val credentialsSyncDisclosed: Flow<Boolean> =
        store.data.map { it[KEY_CREDENTIALS_SYNC_DISCLOSED] ?: false }
    suspend fun setCredentialsSyncDisclosed(value: Boolean) {
        store.edit { it[KEY_CREDENTIALS_SYNC_DISCLOSED] = value }
    }

    val syncLastPullAt: Flow<Long> = store.data.map { it[KEY_SYNC_LAST_PULL] ?: 0L }

    /**
     * True once THIS INSTALL has completed at least one Drive pull. Gates
     * every automatic push (periodic worker, Sync Now's push leg): a fresh
     * blank install that signs in must never shove empty snapshots over a
     * populated Drive backup (user report: "my saved config keeps getting
     * overwritten with a blank app"). Device-local, never synced.
     */
    val syncInitialPullDone: Flow<Boolean> =
        store.data.map { it[KEY_SYNC_INITIAL_PULL_DONE] ?: false }
    suspend fun setSyncInitialPullDone(value: Boolean) {
        store.edit { it[KEY_SYNC_INITIAL_PULL_DONE] = value }
    }
    suspend fun setSyncLastPullAt(value: Long) {
        store.edit { it[KEY_SYNC_LAST_PULL] = value }
    }

    /**
     * Snapshot the keys we sync via Drive — the user-facing UI bits, not
     * device-local network/buffer settings. Returns a string-encoded map so
     * the wire format stays type-agnostic for cross-platform compatibility.
     */
    suspend fun snapshotSyncablePreferences(): Map<String, String> {
        val data = store.data.first()
        val out = mutableMapOf<String, String>()
        data[KEY_SELECTED_THEME]?.let { out["selectedTheme"] = it }
        data[KEY_DEFAULT_TAB]?.let { out["defaultTab"] = it }
        // NOTE: defaultLiveTVView is intentionally NOT synced -- it is a per-device
        // preference (the right default is form-factor specific: TV -> Guide,
        // phone -> List). Syncing one global value made a phone's "list" clobber a
        // TV's "guide" across devices. iOS keeps it per-device (@AppStorage) too.
        data[KEY_SKIP_LOADING_SCREEN]?.let { out["skipLoadingScreen"] = it.toString() }
        data[KEY_APPLE_TV_CHANNEL_FLIP]?.let { out["appleTVChannelFlip"] = it.toString() }
        data[KEY_AUTO_RESUME_LAST_CHANNEL]?.let { out["autoResumeLastChannel"] = it.toString() }
        // TMDB poster fallback: sync the toggle + the user's own key via Drive
        // (their own Drive appData) so it carries across their devices, matching
        // how playlist credentials sync.
        data[KEY_PROGRAM_POSTERS_TMDB_ENABLED]?.let { out["programPostersTmdbEnabled"] = it.toString() }
        // Stored encrypted; the Drive snapshot carries the cleartext key so the
        // user's other devices can use it (decrypt here, the receiver re-encrypts
        // in applySyncedPreferences). A corrupt/undecryptable value is omitted.
        data[KEY_TMDB_API_KEY]?.let { cipher.decrypt(it)?.let { clear -> out["tmdbApiKey"] = clear } }
        data[KEY_CATEGORY_MASTER_ENABLE]?.let { out["enableCategoryColors"] = it.toString() }
        data[KEY_CATEGORY_CUSTOM_JSON]?.let { out["customCategoryColors.v1"] = it }
        // Audit task #52: broaden Drive sync to match iOS coverage. iOS
        // syncs hidden groups, accent color choice, and the custom accent
        // hex string as part of the Preferences snapshot. Network /
        // device-local prefs (homeSsids, buffer size, DVR folder, network
        // timeout) intentionally stay un-synced because they're device
        // specific.
        data[KEY_HIDDEN_GROUPS]?.let { out["hiddenGroups.v1"] = it }
        data[KEY_GROUP_ORDER]?.let { out["groupOrder.v1"] = it }
        data[KEY_GROUP_SORT_MODE]?.let { out["groupSortMode.v1"] = it }
        data[KEY_USE_CUSTOM_ACCENT]?.let { out["useCustomAccent"] = it.toString() }
        data[KEY_CUSTOM_ACCENT_HEX]?.let { out["customAccentHex"] = it }
        ProgramCategory.entries.forEach { bucket ->
            data[stringPreferencesKey(bucket.hexStorageKey)]?.let { out[bucket.hexStorageKey] = it }
            data[booleanPreferencesKey(bucket.enabledStorageKey)]?.let { out[bucket.enabledStorageKey] = it.toString() }
        }
        return out
    }

    /** Reverse of [snapshotSyncablePreferences]. Best-effort decode. */
    suspend fun applySyncedPreferences(keys: Map<String, String>) {
        store.edit { prefs ->
            keys["selectedTheme"]?.let { prefs[KEY_SELECTED_THEME] = it }
            keys["defaultTab"]?.let { prefs[KEY_DEFAULT_TAB] = it }
            // defaultLiveTVView is per-device now (see snapshotSyncablePreferences);
            // ignore any legacy value carried in an older Drive snapshot so it can
            // never re-clobber this device's form-factor default.
            keys["skipLoadingScreen"]?.toBooleanStrictOrNull()?.let { prefs[KEY_SKIP_LOADING_SCREEN] = it }
            keys["appleTVChannelFlip"]?.toBooleanStrictOrNull()?.let { prefs[KEY_APPLE_TV_CHANNEL_FLIP] = it }
            keys["autoResumeLastChannel"]?.toBooleanStrictOrNull()?.let { prefs[KEY_AUTO_RESUME_LAST_CHANNEL] = it }
            keys["programPostersTmdbEnabled"]?.toBooleanStrictOrNull()?.let { prefs[KEY_PROGRAM_POSTERS_TMDB_ENABLED] = it }
            // Re-encrypt the incoming cleartext key for storage at rest.
            keys["tmdbApiKey"]?.let { prefs[KEY_TMDB_API_KEY] = cipher.encrypt(it) ?: it }
            keys["enableCategoryColors"]?.toBooleanStrictOrNull()?.let { prefs[KEY_CATEGORY_MASTER_ENABLE] = it }
            keys["customCategoryColors.v1"]?.let { prefs[KEY_CATEGORY_CUSTOM_JSON] = it }
            // Audit task #52: receive the broadened keys.
            keys["hiddenGroups.v1"]?.let { prefs[KEY_HIDDEN_GROUPS] = it }
            keys["groupOrder.v1"]?.let { prefs[KEY_GROUP_ORDER] = it }
            keys["groupSortMode.v1"]?.let { prefs[KEY_GROUP_SORT_MODE] = it }
            keys["useCustomAccent"]?.toBooleanStrictOrNull()?.let { prefs[KEY_USE_CUSTOM_ACCENT] = it }
            keys["customAccentHex"]?.let { prefs[KEY_CUSTOM_ACCENT_HEX] = it }
            ProgramCategory.entries.forEach { bucket ->
                keys[bucket.hexStorageKey]?.let { prefs[stringPreferencesKey(bucket.hexStorageKey)] = it }
                keys[bucket.enabledStorageKey]?.toBooleanStrictOrNull()?.let {
                    prefs[booleanPreferencesKey(bucket.enabledStorageKey)] = it
                }
            }
        }
    }

    // ── In-app updater (github flavor) ───────────────────────────────────
    // Device-local bookkeeping for the GitHub-releases self-updater. NONE of
    // these belong in snapshotSyncablePreferences (per-device state).

    /** uptime-independent wall clock of the last automatic update check. */
    val updateLastCheckAt: Flow<Long> = store.data.map { it[KEY_UPDATE_LAST_CHECK_AT] ?: 0L }
    suspend fun setUpdateLastCheckAt(value: Long) {
        store.edit { it[KEY_UPDATE_LAST_CHECK_AT] = value }
    }
    suspend fun updateLastCheckAtOnce(): Long = store.data.first()[KEY_UPDATE_LAST_CHECK_AT] ?: 0L

    /** versionName the user chose "Later" on; the launch prompt skips it.
     *  Manual checks in Settings ignore the skip. */
    val updateSkippedVersion: Flow<String> = store.data.map { it[KEY_UPDATE_SKIPPED_VERSION] ?: "" }
    suspend fun setUpdateSkippedVersion(value: String) {
        store.edit { prefs ->
            if (value.isBlank()) prefs.remove(KEY_UPDATE_SKIPPED_VERSION)
            else prefs[KEY_UPDATE_SKIPPED_VERSION] = value
        }
    }
    suspend fun updateSkippedVersionOnce(): String =
        store.data.first()[KEY_UPDATE_SKIPPED_VERSION].orEmpty()

    /** JSON-encoded PendingUpdate (staged APK path + expected version). Written
     *  BEFORE the unknown-sources Settings trip and BEFORE the install-session
     *  commit, because both can kill this process; the next launch resumes from
     *  it. Blank = nothing pending. */
    val updatePendingJson: Flow<String> = store.data.map { it[KEY_UPDATE_PENDING] ?: "" }
    suspend fun setUpdatePendingJson(value: String) {
        store.edit { prefs ->
            if (value.isBlank()) prefs.remove(KEY_UPDATE_PENDING)
            else prefs[KEY_UPDATE_PENDING] = value
        }
    }
    suspend fun updatePendingJsonOnce(): String = store.data.first()[KEY_UPDATE_PENDING].orEmpty()

    /** Set by PackageReplacedReceiver after an in-place update lands; cleared
     *  on the next launch once the new version has booted (bookkeeping only). */
    suspend fun setUpdateCompletedVersion(value: String) {
        store.edit { prefs ->
            if (value.isBlank()) prefs.remove(KEY_UPDATE_COMPLETED_VERSION)
            else prefs[KEY_UPDATE_COMPLETED_VERSION] = value
        }
    }
    suspend fun updateCompletedVersionOnce(): String =
        store.data.first()[KEY_UPDATE_COMPLETED_VERSION].orEmpty()

    // ── DVR ──────────────────────────────────────────────────────────────

    /** Live Rewind (task #143): master enable. Off until the user
     *  accepts the onboarding/update prompt (P2) or flips the toggle. */
    val liveRewindEnabled: kotlinx.coroutines.flow.Flow<Boolean> =
        store.data.map { it[KEY_LIVE_REWIND_ENABLED] ?: false }
    suspend fun setLiveRewindEnabled(value: Boolean) {
        store.edit { it[KEY_LIVE_REWIND_ENABLED] = value }
    }

    /** Live Rewind: rewind depth in minutes (15/30/60/120, default 30). */
    val liveRewindDepthMinutes: kotlinx.coroutines.flow.Flow<Int> =
        store.data.map { it[KEY_LIVE_REWIND_DEPTH_MIN] ?: 30 }
    suspend fun setLiveRewindDepthMinutes(value: Int) {
        store.edit { it[KEY_LIVE_REWIND_DEPTH_MIN] = value }
    }

    /** Live Rewind P2: the one-time feature prompt has been answered
     *  (either way). Never shown again once set. */
    val liveRewindPromptSeen: kotlinx.coroutines.flow.Flow<Boolean> =
        store.data.map { it[KEY_LIVE_REWIND_PROMPT_SEEN] ?: false }
    suspend fun setLiveRewindPromptSeen(value: Boolean) {
        store.edit { it[KEY_LIVE_REWIND_PROMPT_SEEN] = value }
    }

    /** Live Rewind P2: retention for buffered video from past sessions,
     *  in hours (1/6/12/24/72/168 presets or custom, default 24). */
    val liveRewindRetentionHours: kotlinx.coroutines.flow.Flow<Int> =
        store.data.map { it[KEY_LIVE_REWIND_RETENTION_HOURS] ?: 24 }
    suspend fun setLiveRewindRetentionHours(value: Int) {
        store.edit { it[KEY_LIVE_REWIND_RETENTION_HOURS] = value.coerceIn(1, 24 * 30) }
    }

    /** Live Rewind P2: total on-disk budget across sessions, in GB
     *  (default 10). Oldest segments are evicted first when exceeded. */
    val liveRewindBudgetGB: kotlinx.coroutines.flow.Flow<Int> =
        store.data.map { it[KEY_LIVE_REWIND_BUDGET_GB] ?: 10 }
    suspend fun setLiveRewindBudgetGB(value: Int) {
        store.edit { it[KEY_LIVE_REWIND_BUDGET_GB] = value.coerceIn(1, 500) }
    }

    /** iOS `dvrMaxLocalStorageMB` parity. Default 10 GB. */
    val dvrMaxLocalStorageMB: Flow<Int> = store.data.map { it[KEY_DVR_MAX_LOCAL_STORAGE_MB] ?: 10_240 }
    suspend fun setDvrMaxLocalStorageMB(value: Int) {
        store.edit { it[KEY_DVR_MAX_LOCAL_STORAGE_MB] = value }
    }

    /** iOS `dvrDefaultPreRollMins` parity. */
    val dvrDefaultPreRollMins: Flow<Int> = store.data.map { it[KEY_DVR_DEFAULT_PRE_ROLL] ?: 0 }
    suspend fun setDvrDefaultPreRollMins(value: Int) {
        store.edit { it[KEY_DVR_DEFAULT_PRE_ROLL] = value }
    }

    /** iOS `dvrDefaultPostRollMins` parity. */
    val dvrDefaultPostRollMins: Flow<Int> = store.data.map { it[KEY_DVR_DEFAULT_POST_ROLL] ?: 0 }
    suspend fun setDvrDefaultPostRollMins(value: Int) {
        store.edit { it[KEY_DVR_DEFAULT_POST_ROLL] = value }
    }

    /**
     * Custom DVR output folder. Empty string means "use default
     * getExternalFilesDir(Recordings)". Otherwise this is a SAF tree URI the
     * user picked via ACTION_OPEN_DOCUMENT_TREE; we hold persistable RW
     * permission for it for the lifetime of the install.
     */
    val dvrCustomFolderUri: Flow<String> = store.data.map { it[KEY_DVR_CUSTOM_FOLDER_URI] ?: "" }
    suspend fun setDvrCustomFolderUri(value: String) {
        store.edit { prefs ->
            if (value.isBlank()) prefs.remove(KEY_DVR_CUSTOM_FOLDER_URI)
            else prefs[KEY_DVR_CUSTOM_FOLDER_URI] = value
        }
    }

    /** Synchronous read used by LocalRecordingService at recording-start time. */
    suspend fun dvrCustomFolderUriOnce(): String =
        store.data.first()[KEY_DVR_CUSTOM_FOLDER_URI].orEmpty()

    /**
     * Persisted genre/category strings keyed by recording id (e.g.
     * "server-79"). Dispatcharr does NOT reliably expose a completed
     * recording's genre after its EPG programme ages out of the live cache
     * (the /api/epg/programs list strips categories and ignores tvg_id
     * filtering, the recording object carries no category, and program ids
     * are inconsistent). So the DVR ViewModel persists each category the
     * moment it IS resolvable (while the recording is airing or recent and
     * its programme is still in the cache) and seeds it back on later loads,
     * so completed rows keep their pill. Mirrors how iOS effectively retains
     * the category. Stored as a single JSON object (id -> category) so the
     * map stays atomic and category values can contain commas/slashes.
     * Device-local; deliberately NOT part of the Drive sync snapshot (it is
     * a per-install cache, the recordings themselves are server state).
     */
    val recordingCategories: Flow<Map<String, String>> = store.data.map { prefs ->
        decodeRecordingCategories(prefs[KEY_DVR_RECORDING_CATEGORIES])
    }

    /** One-shot read of the whole persisted recordingId -> category map. */
    suspend fun recordingCategoriesOnce(): Map<String, String> =
        decodeRecordingCategories(store.data.first()[KEY_DVR_RECORDING_CATEGORIES])

    /**
     * Persist a resolved category for a single recording id. No-op for a
     * blank id or a blank category (we never store blanks, an absent key
     * already means "unknown"), and a no-op when the stored value already
     * matches so we don't churn the DataStore on every 30s refresh. Suspends
     * on the DataStore IO dispatcher, safe to fire-and-forget from a
     * background coroutine.
     */
    suspend fun setRecordingCategory(id: String, category: String) {
        val cleanId = id.trim()
        val cleanCat = category.trim()
        if (cleanId.isBlank() || cleanCat.isBlank()) return
        store.edit { prefs ->
            val current = decodeRecordingCategories(prefs[KEY_DVR_RECORDING_CATEGORIES])
            if (current[cleanId] == cleanCat) return@edit
            val updated = current.toMutableMap().apply { this[cleanId] = cleanCat }
            prefs[KEY_DVR_RECORDING_CATEGORIES] = Json.encodeToString(updated)
        }
    }

    private fun decodeRecordingCategories(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, String>>(raw) }
            .getOrDefault(emptyMap())
    }

    /**
     * iOS DVR Settings "Keep device awake during recording" toggle. Default
     * ON (iOS parity). When on, LocalRecordingService holds a partial
     * WakeLock for the duration of an active local recording so the CPU
     * doesn't doze and stall the in-flight download. Reads synchronously at
     * recording start via [dvrKeepAwakeOnce].
     */
    val dvrKeepAwakeDuringRecording: Flow<Boolean> =
        store.data.map { it[KEY_DVR_KEEP_AWAKE] ?: true }
    suspend fun setDvrKeepAwakeDuringRecording(value: Boolean) {
        store.edit { it[KEY_DVR_KEEP_AWAKE] = value }
    }
    suspend fun dvrKeepAwakeOnce(): Boolean =
        store.data.first()[KEY_DVR_KEEP_AWAKE] ?: true

    private companion object {
        /** Max entries kept in [recentChannelIds]. iOS keeps a short LRU. */
        const val RECENT_CHANNELS_CAP = 15
        val KEY_RECENT_CHANNEL_IDS = stringPreferencesKey("recent_channel_ids")
        val KEY_SELECTED_THEME = stringPreferencesKey("selected_theme")
        val KEY_USE_CUSTOM_ACCENT = booleanPreferencesKey("use_custom_accent")
        val KEY_SHOW_CHANNEL_LOGOS = booleanPreferencesKey("ui_show_channel_logos")
        val KEY_SHOW_CHANNEL_NUMBERS = booleanPreferencesKey("ui_show_channel_numbers")
        val KEY_PLAYER_ASPECT_MODE = stringPreferencesKey("player_aspect_mode")
        val KEY_CUSTOM_ACCENT_HEX = stringPreferencesKey("custom_accent_hex")
        val KEY_DEFAULT_LIVE_TV_VIEW = stringPreferencesKey("default_live_tv_view")
        val KEY_SKIP_LOADING_SCREEN = booleanPreferencesKey("app_behaviors_skip_loading_screen")
        val KEY_DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val KEY_APPLE_TV_CHANNEL_FLIP = booleanPreferencesKey("app_behaviors_apple_tv_channel_flip")
        val KEY_AUTO_RECOVER_FROZEN_STREAMS =
            booleanPreferencesKey("app_behaviors_auto_recover_frozen_streams")
        // Synced via Drive (snapshotSyncablePreferences) -- the user's own key.
        val KEY_PROGRAM_POSTERS_TMDB_ENABLED =
            booleanPreferencesKey("app_behaviors_program_posters_tmdb_enabled")
        val KEY_TMDB_API_KEY = stringPreferencesKey("tmdb_api_key")
        // Device-specific (depends on the TV / receiver); never synced.
        val KEY_AUDIO_PASSTHROUGH = booleanPreferencesKey("audio_passthrough_enabled")
        val KEY_SYNC_INITIAL_PULL_DONE = booleanPreferencesKey("sync_initial_pull_done")
        // In-app updater (github flavor); device-local, never synced.
        val KEY_UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")
        val KEY_UPDATE_SKIPPED_VERSION = stringPreferencesKey("update_skipped_version")
        val KEY_UPDATE_PENDING = stringPreferencesKey("update_pending")
        val KEY_UPDATE_COMPLETED_VERSION = stringPreferencesKey("update_completed_version")
        val KEY_AUTO_RESUME_LAST_CHANNEL = booleanPreferencesKey("app_behaviors_auto_resume_last_channel")
        val KEY_LAST_WATCHED_CHANNEL_ID = stringPreferencesKey("last_watched_channel_id")
        val KEY_LAST_SEEN_WHATSNEW_VERSION = stringPreferencesKey("last_seen_whatsnew_version")
        val KEY_HIDDEN_GROUPS = stringPreferencesKey("hidden_groups")
        val KEY_GROUP_ORDER = stringPreferencesKey("group_order")
        val KEY_GROUP_SORT_MODE = stringPreferencesKey("group_sort_mode")
        // Per-tab VOD group filters. iOS persists these under the global
        // UserDefaults keys `hiddenMovieGroups` / `hiddenSeriesGroups`; we
        // namespace into our DataStore the same way the live-TV
        // `hidden_groups` key does.
        val KEY_HIDDEN_MOVIE_GROUPS = stringPreferencesKey("hidden_movie_groups")
        val KEY_HIDDEN_SERIES_GROUPS = stringPreferencesKey("hidden_series_groups")
        val KEY_DISPLAY_SCALE_MOVIES = doublePreferencesKey("display_scale_movies")
        val KEY_DISPLAY_SCALE_LIVE_TV = doublePreferencesKey("display_scale_live_tv")
        val KEY_GUIDE_SCALE = doublePreferencesKey("guide_scale")
        val KEY_DEFAULT_TAB = stringPreferencesKey("default_tab")
        val KEY_NETWORK_TIMEOUT = doublePreferencesKey("network_timeout_secs")
        val KEY_MAX_RETRIES = intPreferencesKey("max_retries")
        val KEY_STREAM_BUFFER_SIZE = stringPreferencesKey("stream_buffer_size")
        val KEY_EPG_WINDOW_HOURS = intPreferencesKey("epg_window_hours")
        val KEY_MULTIVIEW_AUDIO_FOCUS_STYLE = stringPreferencesKey("multiview_audio_focus_style")
        val KEY_MULTIVIEW_TILE_PADDING = booleanPreferencesKey("multiview_tile_padding")
        val KEY_MULTIVIEW_TILE_CORNERS_ROUNDED = booleanPreferencesKey("multiview_tile_corners_rounded")
        val KEY_MULTIVIEW_LAYOUT_MODE = stringPreferencesKey("multiview_layout_mode")
        val KEY_MULTIVIEW_PERF_WARNING_SUPPRESSED =
            booleanPreferencesKey("multiview_perf_warning_suppressed")
        val KEY_CHANNEL_COLLECTIONS = stringPreferencesKey("channel_collections")
        val KEY_DVR_MAX_LOCAL_STORAGE_MB = intPreferencesKey("dvr_max_local_storage_mb")
        val KEY_DVR_DEFAULT_PRE_ROLL = intPreferencesKey("dvr_default_pre_roll_mins")
        val KEY_DVR_DEFAULT_POST_ROLL = intPreferencesKey("dvr_default_post_roll_mins")
        val KEY_LIVE_REWIND_ENABLED = booleanPreferencesKey("live_rewind_enabled")
        val KEY_LIVE_REWIND_DEPTH_MIN = intPreferencesKey("live_rewind_depth_minutes")
        val KEY_LIVE_REWIND_PROMPT_SEEN = booleanPreferencesKey("live_rewind_prompt_seen")
        val KEY_LIVE_REWIND_RETENTION_HOURS = intPreferencesKey("live_rewind_retention_hours")
        val KEY_LIVE_REWIND_BUDGET_GB = intPreferencesKey("live_rewind_budget_gb")
        val KEY_DVR_CUSTOM_FOLDER_URI = stringPreferencesKey("dvr_custom_folder_uri")
        val KEY_DVR_KEEP_AWAKE = booleanPreferencesKey("dvr_keep_awake_during_recording")
        // JSON object {recordingId: category}. Device-local cache so completed
        // recordings keep their genre pill after the programme leaves the EPG
        // window; deliberately NOT in snapshotSyncablePreferences.
        val KEY_DVR_RECORDING_CATEGORIES = stringPreferencesKey("dvr_recording_categories")
        val KEY_CATEGORY_MASTER_ENABLE = booleanPreferencesKey(CategoryPaletteState.MASTER_ENABLED_KEY)
        val KEY_CATEGORY_CUSTOM_JSON = stringPreferencesKey(CategoryPaletteState.CUSTOM_KEY)
        val KEY_SYNC_MASTER = booleanPreferencesKey("sync_master_enabled")
        val KEY_SYNC_ACCOUNT_EMAIL = stringPreferencesKey("sync_account_email")
        val KEY_SYNC_LAST_PUSH = longPreferencesKey("sync_last_push_at")
        val KEY_SYNC_LAST_PULL = longPreferencesKey("sync_last_pull_at")
        val KEY_SYNC_ACCESS_TOKEN = stringPreferencesKey("sync_access_token")
        val KEY_SYNC_TOKEN_EXPIRY = longPreferencesKey("sync_token_expiry")
        val KEY_CREDS_ENCRYPTED_V1 = booleanPreferencesKey("creds_encrypted_v1")
        val KEY_CREDENTIALS_SYNC_DISCLOSED = booleanPreferencesKey("credentials_sync_disclosed")
        val KEY_BG_REFRESH_ENABLED = booleanPreferencesKey("background_refresh_enabled")
        val KEY_BG_REFRESH_INTERVAL_MINS = intPreferencesKey("background_refresh_interval_mins")
    }
}
