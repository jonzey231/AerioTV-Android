package com.aeriotv.android.core.preferences

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
import com.aeriotv.android.core.profiles.ProfileScope
import com.aeriotv.android.core.security.CredentialCipher
import com.aeriotv.android.core.sync.SyncCategory
import com.aeriotv.android.core.sync.SyncItem
import com.aeriotv.android.core.sync.SyncItemCatalog
import com.aeriotv.android.core.sync.SyncScope
import com.aeriotv.android.core.sync.dsync.DsyncSettingsValidator
import com.aeriotv.android.ui.theme.AppTheme
import com.aeriotv.android.ui.theme.AppearanceMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * One channel's learned live start hold-back plus the wall-clock time it was
 * last learned (raised or re-confirmed by a stall). The timestamp is what lets
 * a tune expire a stale value instead of being pinned by one bad session
 * (Logan 2026-09-12).
 */
data class LearnedStartBuffer(val ms: Int, val learnedAtMs: Long)

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
            // One-time reset of the learned live start buffers (2026-09-15).
            // Stalls caused by Dispatcharr stream switches fed the bursty-feed
            // learner and pinned channels at the 10 s start gate, slowing every
            // tune. Future learning ignores switch-window stalls; this clears
            // what was already polluted.
            object : DataMigration<Preferences> {
                private val clearedFlag =
                    booleanPreferencesKey("live_start_buffer_switch_reset_v1")
                override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                    currentData[clearedFlag] != true
                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.toMutablePreferences().apply {
                        remove(stringPreferencesKey("live_start_buffer_ms"))
                        remove(stringPreferencesKey("live_start_buffer_at_ms"))
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

/** App-wide Text Size bounds (Appearance > Text Size). 5% stops, default 1.0. */
const val TEXT_SCALE_MIN = 0.85f
const val TEXT_SCALE_MAX = 1.50f

/** Subtext Size bounds (Appearance > Subtext Size). 5% stops, default 1.0. */
const val SUBTEXT_SCALE_MIN = 0.85f
const val SUBTEXT_SCALE_MAX = 1.50f

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
/**
 * Display-only facts about the Dispatcharr account a playlist connects as,
 * shown on Playlist Detail. No credentials: just the echoed username and the
 * names of the assigned Channel Profiles.
 */
data class DispatcharrAccountFacts(
    val username: String = "",
    val profileNames: List<String> = emptyList(),
)

class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: CredentialCipher,
) {
    private val store get() = context.appDataStore

    /**
     * Profiles (2026-10-16): per-profile keys. The bare key IS the default
     * profile, so nothing moves at migration; a non-default active profile
     * reads and writes "<key>@prof:<profileId>" (ProfileScope.keyName).
     * Flows read through [scopedData] so they re-key when the profile changes.
     * The Drive snapshot below reads the bare keys only (default profile).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scopedData: Flow<Preferences> get() = ProfileScope.token.flatMapLatest { store.data }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Preferences.Key<T>.s(): Preferences.Key<T> {
        val scoped = ProfileScope.keyName(name)
        return if (scoped == name) this else stringPreferencesKey(scoped) as Preferences.Key<T>
    }

    /**
     * A new profile starts with the default profile's settings: every
     * setting Dispatcharr Sync carries ([PROFILE_SETTING_KEYS] and the
     * category color buckets), plus group sort mode and order and Radio Mode
     * by Default. Its favorites, recents and the rest start empty.
     */
    suspend fun seedProfileSettings(profileId: String) {
        store.edit { prefs ->
            copyBareToProfile(prefs, profileId, overwrite = true)
            prefs[KEY_PROFILE_SETTINGS_SEEDED] = (prefs[KEY_PROFILE_SETTINGS_SEEDED] ?: emptySet()) + profileId
        }
    }

    /**
     * One-time migration (2026-10-27, per-profile settings): the global value
     * of each newly profile-scoped setting already IS the default profile's
     * value (bare key); every existing non-default profile is seeded from it
     * once, without overwriting a value the profile already has.
     */
    suspend fun seedExistingProfilesOnce(profileIds: Collection<String>) {
        store.edit { prefs ->
            val done = prefs[KEY_PROFILE_SETTINGS_SEEDED] ?: emptySet()
            val todo = profileIds.filter { it.isNotBlank() && it !in done }
            if (todo.isEmpty()) return@edit
            todo.forEach { copyBareToProfile(prefs, it, overwrite = false) }
            prefs[KEY_PROFILE_SETTINGS_SEEDED] = done + todo
            android.util.Log.i("AppPreferences", "[PROFILE] settings seeded for ${todo.size} existing profile(s)")
        }
    }

    private fun copyBareToProfile(prefs: MutablePreferences, profileId: String, overwrite: Boolean) {
        val names = PROFILE_SETTING_KEYS.map { it.name }.toMutableSet()
        names += listOf(KEY_GROUP_SORT_MODE.name, KEY_GROUP_ORDER.name, KEY_RADIO_MODE_BY_DEFAULT.name)
        ProgramCategory.entries.forEach { names += it.hexStorageKey; names += it.enabledStorageKey }
        val bare = prefs.asMap().filterKeys { it.name in names }
        bare.forEach { (k, v) ->
            val to = ProfileScope.keyName(k.name, profileId)
            if (!overwrite && prefs.asMap().keys.any { it.name == to }) return@forEach
            @Suppress("UNCHECKED_CAST")
            when (v) {
                is Boolean -> prefs[booleanPreferencesKey(to)] = v
                is Int -> prefs[androidx.datastore.preferences.core.intPreferencesKey(to)] = v
                is Long -> prefs[longPreferencesKey(to)] = v
                is Double -> prefs[doublePreferencesKey(to)] = v
                is Float -> prefs[androidx.datastore.preferences.core.floatPreferencesKey(to)] = v
                is String -> prefs[stringPreferencesKey(to)] = v
                is Set<*> -> prefs[androidx.datastore.preferences.core.stringSetPreferencesKey(to)] = v as Set<String>
            }
        }
    }

    /** Remove-profile cleanup: every key written under [profileId]'s suffix. */
    suspend fun deleteProfileScope(profileId: String) {
        val suffix = "@prof:$profileId"
        store.edit { prefs ->
            prefs.asMap().keys.filter { it.name.endsWith(suffix) }.forEach { prefs.remove(it) }
        }
    }

    val selectedTheme: Flow<AppTheme> = scopedData.map { prefs ->
        val raw = prefs[KEY_SELECTED_THEME.s()] ?: AppTheme.Aerio.name
        AppTheme.entries.firstOrNull { it.name == raw } ?: AppTheme.Aerio
    }

    suspend fun setSelectedTheme(theme: AppTheme) {
        store.edit { it[KEY_SELECTED_THEME.s()] = theme.name }
    }

    /**
     * Appearance mode (Dark / Light / System), ORTHOGONAL to [selectedTheme].
     * Stored as the raw wire string ("dark" / "light" / "system"). CRITICAL:
     * the persisted-absence path resolves to [AppearanceMode.Dark] so every
     * existing install (no stored value) is visually unchanged on upgrade.
     * Drive-synced (unlike the per-device defaultLiveTVView) so the choice
     * follows the user across all of their devices, exactly like the custom
     * accent hex.
     */
    val appearanceMode: Flow<AppearanceMode> = scopedData.map { prefs ->
        AppearanceMode.fromWire(prefs[KEY_APPEARANCE_MODE.s()])
    }

    suspend fun setAppearanceMode(mode: AppearanceMode) {
        store.edit { it[KEY_APPEARANCE_MODE.s()] = mode.wire }
    }

    /**
     * Custom accent override (iOS ThemeManager useCustomAccent parity). When
     * the user enables this in Appearance, AerioTVTheme replaces the selected
     * preset's accentPrimary with this hex. Per iOS canon: a 6-char uppercase
     * hex string (without leading '#'). Empty string falls back to the preset's
     * own accent.
     */
    val useCustomAccent: Flow<Boolean> = scopedData.map { it[KEY_USE_CUSTOM_ACCENT.s()] ?: false }
    suspend fun setUseCustomAccent(value: Boolean) {
        store.edit { it[KEY_USE_CUSTOM_ACCENT.s()] = value }
    }

    /**
     * iOS Issue #28 (`ui.showChannelLogos`). When off, the Live TV list hides
     * each channel's logo so long channel names use the full row width.
     * Default ON.
     */
    val showChannelLogos: Flow<Boolean> = scopedData.map { it[KEY_SHOW_CHANNEL_LOGOS.s()] ?: true }
    suspend fun setShowChannelLogos(value: Boolean) {
        store.edit { it[KEY_SHOW_CHANNEL_LOGOS.s()] = value }
    }

    /**
     * Settings > Live TV > Logo Size (Apple `ui.channelLogoSize`). Stored in
     * TENTHS of a percent so the 2.5% stop fits an Int: 25, 50, 100, 150,
     * 200, 250; default 100 (10%). Read relative to the default (100 =
     * today's size), see [com.aeriotv.android.core.ui.liveTvLogoScale].
     * Migration: the first build stored whole percents under
     * `ui_channel_logo_size`; while the tenths key is absent that value is
     * read times ten, and the first write moves it to the tenths key and
     * drops the legacy one.
     */
    val channelLogoSize: Flow<Int> = scopedData.map {
        (it[KEY_CHANNEL_LOGO_SIZE_TENTHS.s()] ?: it[KEY_CHANNEL_LOGO_SIZE_LEGACY]?.times(10) ?: 100)
            .coerceIn(25, 250)
    }
    suspend fun setChannelLogoSize(tenths: Int) {
        store.edit {
            it[KEY_CHANNEL_LOGO_SIZE_TENTHS.s()] = tenths.coerceIn(25, 250)
            it.remove(KEY_CHANNEL_LOGO_SIZE_LEGACY)
        }
    }

    /**
     * GH #19 (`ui.showChannelNumbers` on iOS). When off, the Live TV list and
     * Guide hide the channel number column. Default ON.
     */
    val showChannelNumbers: Flow<Boolean> = scopedData.map { it[KEY_SHOW_CHANNEL_NUMBERS.s()] ?: true }
    suspend fun setShowChannelNumbers(value: Boolean) {
        store.edit { it[KEY_SHOW_CHANNEL_NUMBERS.s()] = value }
    }

    /**
     * GH #73 (ant462): hide the channel NAME text in the Guide's channel rail,
     * leaving logo and number, for users who recognise channels by logo and
     * want the rail as narrow-reading as possible. Default ON.
     */
    val showChannelNames: Flow<Boolean> = scopedData.map { it[KEY_SHOW_CHANNEL_NAMES.s()] ?: true }
    suspend fun setShowChannelNames(value: Boolean) {
        store.edit { it[KEY_SHOW_CHANNEL_NAMES.s()] = value }
    }

    /**
     * Whether the programme SUBTITLE line (the XMLTV sub-title: episode or
     * match name, drawn italic under the title) renders in the Guide cells
     * and the Live TV list. Some EPG providers repeat the description in
     * the sub-title, so the two lines read as duplicates; turning this off
     * leaves title + description. Default ON.
     */
    val showProgramSubtitles: Flow<Boolean> = scopedData.map { it[KEY_SHOW_PROGRAM_SUBTITLES.s()] ?: true }
    suspend fun setShowProgramSubtitles(value: Boolean) {
        store.edit { it[KEY_SHOW_PROGRAM_SUBTITLES.s()] = value }
    }

    /**
     * Settings > Appearance > "Rounded corners in List view". Channel logos
     * and program artwork on the LIST and card surfaces are rounded to match
     * the corner radius of the card they sit in; off makes them square.
     * Movies / TV Shows / DVR poster art is a separate surface and is NOT
     * affected. Default ON. See core/ui/ArtworkCorners.kt.
     *
     * MIGRATION: this deliberately keeps reading the original single-toggle
     * key ("ui_rounded_artwork"), so whatever the user already chose carries
     * straight over into the List toggle and nobody's setting changes.
     */
    val roundedArtwork: Flow<Boolean> = scopedData.map { it[KEY_ROUNDED_ARTWORK.s()] ?: true }
    suspend fun setRoundedArtwork(value: Boolean) {
        store.edit { it[KEY_ROUNDED_ARTWORK.s()] = value }
    }

    /**
     * Settings > Appearance > "Rounded corners in Guide view". Covers ONLY the
     * guide rail logos, at the rail's own small radius. Default OFF: the rail
     * has always drawn square logos and that stays the out-of-box look.
     */
    val roundedArtworkGuide: Flow<Boolean> =
        scopedData.map { it[KEY_ROUNDED_ARTWORK_GUIDE.s()] ?: false }
    suspend fun setRoundedArtworkGuide(value: Boolean) {
        store.edit { it[KEY_ROUNDED_ARTWORK_GUIDE.s()] = value }
    }

    /**
     * Settings > Live TV > "Rounded Program Cells". Rounds the guide's program
     * cells at the rail radius, independent of the logo toggle above (GH #87
     * follow-up: rounded logos with flat cells). Default OFF.
     */
    val roundedProgramCells: Flow<Boolean> =
        scopedData.map { it[KEY_ROUNDED_PROGRAM_CELLS.s()] ?: false }
    suspend fun setRoundedProgramCells(value: Boolean) {
        store.edit { it[KEY_ROUNDED_PROGRAM_CELLS.s()] = value }
    }

    /**
     * Settings > Appearance > "True Black Background" (GH #105). When on, the
     * dark rendition of every theme paints page backgrounds pure black and
     * card / sheet surfaces near-black (#0A0A0A) for OLED screens. Default OFF.
     */
    val trueBlack: Flow<Boolean> =
        scopedData.map { it[KEY_APPEARANCE_TRUE_BLACK.s()] ?: false }
    suspend fun setTrueBlack(value: Boolean) {
        store.edit { it[KEY_APPEARANCE_TRUE_BLACK.s()] = value }
    }

    /**
     * Settings > Appearance > Time Format: "system" (default, follows the
     * device's 24-hour setting), "12", or "24". Drive-synced as "timeFormat",
     * the same key Apple uses.
     */
    val timeFormat: Flow<String> = scopedData.map { it[KEY_TIME_FORMAT.s()] ?: "system" }
    suspend fun setTimeFormat(value: String) {
        store.edit { it[KEY_TIME_FORMAT.s()] = value }
    }

    /**
     * Whether the EPG program badges (LIVE / NEW / PREMIERE / FINALE / REPEAT +
     * season/episode pill) render in the guide, channel list, and info sheet.
     * Default ON. Stored + Drive-synced PER DEVICE TYPE (a separate value for TV
     * and for phone/tablet) so a TV's choice follows the user's TVs and a phone's
     * follows their phones, independently. The UI reads/writes the key matching
     * the current device via [showEpgBadges] / [setShowEpgBadges].
     */
    val showEpgBadgesTv: Flow<Boolean> = scopedData.map { it[KEY_SHOW_EPG_BADGES_TV.s()] ?: true }
    val showEpgBadgesMobile: Flow<Boolean> = scopedData.map { it[KEY_SHOW_EPG_BADGES_MOBILE.s()] ?: true }
    fun showEpgBadges(isTv: Boolean): Flow<Boolean> = if (isTv) showEpgBadgesTv else showEpgBadgesMobile

    /**
     * Per-badge visibility under the master "Show program badges" switch
     * (Logan, 2026-08-19): users pick which pills render - NEW, REPEAT,
     * LIVE, PREMIERE, FINALE. Stored as the set of HIDDEN labels so the
     * default (empty set) shows everything and future badge kinds are
     * visible until explicitly hidden.
     */
    val hiddenEpgBadges: Flow<Set<String>> = scopedData.map { prefs ->
        (prefs[KEY_HIDDEN_EPG_BADGES.s()] ?: "").split('\n')
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet()
    }
    suspend fun setBadgeHidden(label: String, hidden: Boolean) {
        store.edit { prefs ->
            val cur = (prefs[KEY_HIDDEN_EPG_BADGES.s()] ?: "").split('\n')
                .mapNotNull { it.trim().takeIf(String::isNotBlank) }.toMutableSet()
            if (hidden) cur.add(label) else cur.remove(label)
            if (cur.isEmpty()) prefs.remove(KEY_HIDDEN_EPG_BADGES.s())
            else prefs[KEY_HIDDEN_EPG_BADGES.s()] = cur.joinToString("\n")
        }
    }
    suspend fun setShowEpgBadges(isTv: Boolean, value: Boolean) {
        store.edit { it[if (isTv) KEY_SHOW_EPG_BADGES_TV else KEY_SHOW_EPG_BADGES_MOBILE] = value }
    }

    /**
     * Video scale mode for the live and on-demand players: "fit" (default,
     * letterbox or pillarbox) or "fill" (scale up preserving aspect so the
     * shorter dimension fills the screen, cropping the overflow) or "stretch"
     * (aspect ignored). Maps to Media3 AspectRatioFrameLayout
     * RESIZE_MODE_FIT / RESIZE_MODE_ZOOM / RESIZE_MODE_FILL.
     */
    val videoScaleMode: Flow<String> = scopedData.map { it[KEY_VIDEO_SCALE_MODE] ?: "fit" }
    suspend fun setVideoScaleMode(value: String) {
        store.edit { it[KEY_VIDEO_SCALE_MODE] = value }
    }

    val customAccentHex: Flow<String> = scopedData.map { it[KEY_CUSTOM_ACCENT_HEX.s()] ?: "" }
    suspend fun setCustomAccentHex(value: String) {
        store.edit { prefs ->
            val clean = value.trim().removePrefix("#").uppercase().take(6)
            if (clean.isBlank()) prefs.remove(KEY_CUSTOM_ACCENT_HEX.s())
            else prefs[KEY_CUSTOM_ACCENT_HEX.s()] = clean
        }
    }

    /** iOS `displayScaleMovies` parity. 0.85 .. 1.5. Default 1.0. */
    val displayScaleMovies: Flow<Float> = scopedData.map {
        // Logan 2026-09-18: the range is 85% .. 150% on every platform. Values
        // stored while the old 85-175% segments existed are clamped on read so
        // an upgraded device never renders above the shared maximum.
        (it[KEY_DISPLAY_SCALE_MOVIES.s()] ?: 1.0).toFloat().coerceIn(0.85f, 1.5f)
    }
    suspend fun setDisplayScaleMovies(value: Float) {
        store.edit { it[KEY_DISPLAY_SCALE_MOVIES.s()] = value.toDouble() }
    }

    /**
     * App-wide Text Size multiplier (Appearance > Text Size). Applied once at
     * the composition root (and re-applied inside every dialog / sheet / menu
     * window) as a fontScale multiplier, so every sp in the app follows it.
     * [TEXT_SCALE_MIN]..[TEXT_SCALE_MAX], snapped to 5% stops. Default 1.0.
     * Synced via Drive so the reading size follows the user.
     */
    val textScale: Flow<Float> = scopedData.map {
        snapTextScale((it[KEY_TEXT_SCALE.s()] ?: 1.0).toFloat())
    }
    suspend fun setTextScale(value: Float) {
        store.edit { it[KEY_TEXT_SCALE.s()] = snapTextScale(value).toDouble() }
    }

    /**
     * Subtext Size multiplier (Appearance > Subtext Size). Scales ONLY
     * secondary copy (descriptions, subtitles, metadata, captions) on top of
     * [textScale]. Same 85%..150% range and 5% stops. Default 1.0. Synced.
     */
    val subtextScale: Flow<Float> = scopedData.map {
        snapTextScale((it[KEY_SUBTEXT_SCALE.s()] ?: 1.0).toFloat())
    }
    suspend fun setSubtextScale(value: Float) {
        store.edit { it[KEY_SUBTEXT_SCALE.s()] = snapTextScale(value).toDouble() }
    }

    /**
     * Text Contrast (Appearance > Text Contrast). 0.0 = the theme's dimmed /
     * accent-tinted text as designed, 1.0 = plain white (dark) / black (light)
     * text. 10% stops. Default 0.0. Synced.
     */
    val textContrast: Flow<Float> = scopedData.map {
        snapTextContrast((it[KEY_TEXT_CONTRAST.s()] ?: 0.0).toFloat())
    }
    suspend fun setTextContrast(value: Float) {
        store.edit { it[KEY_TEXT_CONTRAST.s()] = snapTextContrast(value).toDouble() }
    }

    /**
     * iOS `displayScaleLiveTV` parity. 0.85 .. 1.5. Default 1.0.
     *
     * Logan 2026-09-18: Live TV caps at 150% like Movies on every platform.
     * Values stored while the old 85-175% segments existed are clamped HERE,
     * at the read point, so an upgraded device never renders above the shared
     * maximum even though its preference file still holds 1.75.
     */
    val displayScaleLiveTV: Flow<Float> = scopedData.map {
        (it[KEY_DISPLAY_SCALE_LIVE_TV.s()] ?: 1.0).toFloat().coerceIn(0.85f, 1.5f)
    }
    suspend fun setDisplayScaleLiveTV(value: Float) {
        store.edit { it[KEY_DISPLAY_SCALE_LIVE_TV.s()] = value.toDouble() }
    }

    /**
     * EPG guide timeline zoom. iOS `guideScale` parity: scales the hour-column
     * width so the user can fit more or fewer hours on screen. Clamped
     * [GUIDE_SCALE_MIN]..[GUIDE_SCALE_MAX]; default 1.0. Written by both the
     * pinch gesture (on gesture end) and the discrete zoom selector in the
     * guide top bar.
     */
    val guideScale: Flow<Float> = scopedData.map {
        (it[KEY_GUIDE_SCALE.s()] ?: 1.0).toFloat().coerceIn(GUIDE_SCALE_MIN, GUIDE_SCALE_MAX)
    }
    suspend fun setGuideScale(value: Float) {
        store.edit {
            it[KEY_GUIDE_SCALE.s()] = value.coerceIn(GUIDE_SCALE_MIN, GUIDE_SCALE_MAX).toDouble()
        }
    }

    /**
     * Either "list" or "guide", or empty for "follow form-factor default".
     * Mirrors iOS `@AppStorage("defaultLiveTVView")`. Phase 5 used
     * `rememberSaveable` for view-mode state; Phase 8b moves it to DataStore
     * so the user's choice survives cold start.
     */
    val defaultLiveTVView: Flow<String> = scopedData.map { prefs ->
        prefs[KEY_DEFAULT_LIVE_TV_VIEW.s()] ?: ""
    }

    suspend fun setDefaultLiveTVView(value: String) {
        store.edit { it[KEY_DEFAULT_LIVE_TV_VIEW.s()] = value }
    }

    /** TV Live TV layout: "basic" (full details in every cell) or "preview"
     *  (Channel Preview banner above the guide, cells keep title and tags).
     *  Mirrors tvOS `@AppStorage("liveTVLayout")`; per device. */
    val liveTvLayout: Flow<String> = scopedData.map { prefs -> prefs[KEY_LIVE_TV_LAYOUT.s()] ?: "basic" }

    suspend fun setLiveTvLayout(value: String) {
        store.edit { it[KEY_LIVE_TV_LAYOUT.s()] = value }
    }

    // ── App Behaviors ────────────────────────────────────────────────────

    /**
     * iOS `appBehaviorsSkipLoadingScreen` parity. When true, Bootstrap navigates
     * to MainScaffold immediately and lets data hydrate in the background.
     */
    val skipLoadingScreen: Flow<Boolean> = scopedData.map { it[KEY_SKIP_LOADING_SCREEN.s()] ?: false }
    suspend fun setSkipLoadingScreen(value: Boolean) {
        store.edit { it[KEY_SKIP_LOADING_SCREEN.s()] = value }
    }

    /**
     * iOS `appBehaviorsAutoRotate` parity (Logan 2026-08-07). Follow the
     * device orientation app-wide on phones/tablets. Default ON. When off,
     * MainActivity locks the activity to its current orientation
     * (SCREEN_ORIENTATION_LOCKED); the player's fullscreen button still
     * forces landscape. Device-local (not in the sync snapshot) - rotation
     * is a per-device preference and TVs ignore it entirely.
     */
    val autoRotate: Flow<Boolean> = scopedData.map { it[KEY_AUTO_ROTATE] ?: true }
    suspend fun setAutoRotate(value: Boolean) {
        store.edit { it[KEY_AUTO_ROTATE] = value }
    }

    /**
     * iOS `appBehaviorsAppleTVChannelFlip` parity. Gates the Player vertical
     * swipe-flip (PlayerScreen.kt) and the future Apple TV-style D-pad nav.
     * Defaults to true so the v1.0 ship matches iOS's default.
     */
    val appleTVChannelFlip: Flow<Boolean> = scopedData.map { it[KEY_APPLE_TV_CHANNEL_FLIP.s()] ?: true }
    suspend fun setAppleTVChannelFlip(value: Boolean) {
        store.edit { it[KEY_APPLE_TV_CHANNEL_FLIP.s()] = value }
    }

    /**
     * In-Player Gestures (phone and tablet only): a vertical slide along one
     * screen edge of the fullscreen player adjusts screen brightness, and the
     * other edge adjusts media volume.
     *
     * Both default OFF: the player already owns the vertical axis (channel
     * flip) and the top strip (swipe down to minimize), so these only exist
     * for users who ask for them. Device-local (not in the sync snapshot) -
     * brightness and volume are per-device, and a TV has neither gesture.
     */
    val playerBrightnessGesture: Flow<Boolean> =
        scopedData.map { it[KEY_PLAYER_BRIGHTNESS_GESTURE.s()] ?: false }
    suspend fun setPlayerBrightnessGesture(value: Boolean) {
        store.edit { it[KEY_PLAYER_BRIGHTNESS_GESTURE.s()] = value }
    }

    val playerVolumeGesture: Flow<Boolean> =
        scopedData.map { it[KEY_PLAYER_VOLUME_GESTURE.s()] ?: false }
    suspend fun setPlayerVolumeGesture(value: Boolean) {
        store.edit { it[KEY_PLAYER_VOLUME_GESTURE.s()] = value }
    }

    /**
     * Which edge brightness lives on: [PLAYER_EDGE_LEFT] or
     * [PLAYER_EDGE_RIGHT]. Volume always takes the other edge.
     */
    val playerBrightnessEdge: Flow<String> =
        scopedData.map { it[KEY_PLAYER_BRIGHTNESS_EDGE.s()] ?: PLAYER_EDGE_LEFT }
    suspend fun setPlayerBrightnessEdge(value: String) {
        store.edit { it[KEY_PLAYER_BRIGHTNESS_EDGE.s()] = value }
    }

    /**
     * TV remote button mapping (Remote Control settings; plan
     * ~/Desktop/AerioTV-Remote-Control-Plan.md). Raw JSON blob in the
     * shared cross-platform schema; decode via RemoteControlMap.fromJson
     * (tolerant of unknown slots/actions). Empty/absent = defaults.
     */
    val remoteControlMap: Flow<String> = scopedData.map { it[KEY_REMOTE_CONTROL_MAP] ?: "" }
    suspend fun setRemoteControlMap(json: String) {
        store.edit { it[KEY_REMOTE_CONTROL_MAP] = json }
    }

    /** Android#114: buttons taught with Learn Button (LearnedButtons JSON).
     *  Device-local and never synced: key codes belong to this box's remote. */
    val learnedRemoteButtons: Flow<Map<Int, com.aeriotv.android.core.remote.LearnedButton>> =
        scopedData.map { com.aeriotv.android.core.remote.LearnedButtons.fromJson(it[KEY_REMOTE_LEARNED_BUTTONS]) }
    suspend fun setLearnedRemoteButtons(buttons: Collection<com.aeriotv.android.core.remote.LearnedButton>) {
        store.edit { it[KEY_REMOTE_LEARNED_BUTTONS] = com.aeriotv.android.core.remote.LearnedButtons.toJson(buttons) }
    }

    /**
     * Whether THIS device shares its remote button map through Drive sync.
     *
     * Discord (Glitzbr, reported on Apple, same shape here): a customised map
     * from one TV landed on another whose remote is a different model, and the
     * only escape was turning off App Preferences sync entirely, losing theme,
     * default tab and the rest with it. The map rides preferences.v1.json, and
     * Android couples one SyncCategory to one Drive file, so this is a slice
     * toggle inside that category rather than a category of its own.
     *
     * Deliberately NOT synced. The whole point is "this device is different",
     * so a device that opts out must not propagate that opt-out and silence
     * the other devices' maps too. Gates BOTH directions: an opted-out device
     * stops publishing its map AND stops accepting one.
     */
    val syncRemoteControlMap: Flow<Boolean> =
        scopedData.map { it[KEY_SYNC_REMOTE_CONTROL_MAP] ?: true }
    suspend fun setSyncRemoteControlMap(value: Boolean) {
        store.edit { it[KEY_SYNC_REMOTE_CONTROL_MAP] = value }
    }

    /**
     * Settings > Player > Radio Mode by Default (Discord request, Apple key
     * radioModeByDefault). Default OFF. When on, every live channel pick goes
     * the declared radio path (RadioSelect.maybeOpen); the long-press Watch
     * still plays video. Stays out of the Drive snapshot unless this device
     * opts in with [syncRadioModeByDefault].
     */
    val radioModeByDefault: Flow<Boolean> =
        scopedData.map { it[KEY_RADIO_MODE_BY_DEFAULT.s()] ?: false }
    suspend fun setRadioModeByDefault(value: Boolean) {
        store.edit { it[KEY_RADIO_MODE_BY_DEFAULT.s()] = value }
    }

    /**
     * Whether THIS device shares Radio Mode by Default through Drive sync.
     * Default OFF (slice of App Preferences, like [syncRemoteControlMap]).
     * Never synced itself; gates both push and pull.
     */
    val syncRadioModeByDefault: Flow<Boolean> =
        scopedData.map { it[KEY_SYNC_RADIO_MODE_BY_DEFAULT] ?: false }
    suspend fun setSyncRadioModeByDefault(value: Boolean) {
        store.edit { it[KEY_SYNC_RADIO_MODE_BY_DEFAULT] = value }
    }

    /**
     * Remote hint strip (Logan 2026-09-11): the single "key  action" line
     * shown under the tab bar on Live TV and as the last row of the player
     * controls. Default ON; the toggle lives at the top of Settings >
     * Remote Control. Synced (Apple uses the name `showRemoteHints`).
     */
    val showRemoteHints: Flow<Boolean> =
        scopedData.map { it[KEY_SHOW_REMOTE_HINTS.s()] ?: true }
    suspend fun setShowRemoteHints(value: Boolean) {
        store.edit { it[KEY_SHOW_REMOTE_HINTS.s()] = value }
    }

    /**
     * Exit AerioTV? confirm focus (community request 2026-10-29): when true
     * the dialog opens with D-pad focus on Exit instead of Cancel, since
     * remote users cancel with Back. Default OFF. Synced as
     * `exitDialogFocusExit`.
     */
    val exitDialogFocusExit: Flow<Boolean> =
        scopedData.map { it[KEY_EXIT_DIALOG_FOCUS_EXIT.s()] ?: false }
    suspend fun setExitDialogFocusExit(value: Boolean) {
        store.edit { it[KEY_EXIT_DIALOG_FOCUS_EXIT.s()] = value }
    }

    /**
     * TV guide group-selector style (Logan 2026-07-20): "pills" = the top
     * group-pill row (the original layout), "sidebar" = a docked left menu
     * opened with a Left press from the currently-airing column. Mutually
     * exclusive - the pills row is hidden while sidebar mode is active.
     */
    val guideGroupSelector: Flow<String> =
        scopedData.map { it[KEY_GUIDE_GROUP_SELECTOR.s()] ?: "pills" }
    suspend fun setGuideGroupSelector(mode: String) {
        store.edit { it[KEY_GUIDE_GROUP_SELECTOR.s()] = mode }
    }

    /**
     * Raw stored value, or null when the user has never had the choice made
     * for them. Drives the one-time seed in AerioTVApplication that makes
     * "sidebar" the default for FRESH installs only; everyone else is pinned
     * to "pills" explicitly so the decision is never re-evaluated. The flow
     * above keeps "pills" as its fallback for the window before the seed runs.
     */
    suspend fun guideGroupSelectorRawOnce(): String? =
        store.data.first()[KEY_GUIDE_GROUP_SELECTOR.s()]

    /**
     * TV guide sidebar layout (Logan 2026-09-14), only meaningful while
     * [guideGroupSelector] is "sidebar": "overlay" (default) = the pane sits
     * over the guide with a dimming scrim, "shift" = the guide grid narrows
     * beside the pane so no program is covered. Synced with the selector.
     */
    val guideSidebarLayout: Flow<String> =
        scopedData.map { it[KEY_GUIDE_SIDEBAR_LAYOUT.s()] ?: "overlay" }
    suspend fun setGuideSidebarLayout(mode: String) {
        store.edit { it[KEY_GUIDE_SIDEBAR_LAYOUT.s()] = mode }
    }

    /**
     * Phone / tablet Live TV group selector (Logan 2026-09-05, Apple parity
     * with `phoneGroupSelectorKey`): "sidebar" (default) = a slide-in group
     * drawer opened from the header's groups button, "pills" = the group pill
     * strip in the header row. Separate from [guideGroupSelector] so each
     * device type keeps its own choice and its own default.
     */
    val phoneGroupSelector: Flow<String> =
        scopedData.map { it[KEY_PHONE_GROUP_SELECTOR.s()] ?: "sidebar" }
    suspend fun setPhoneGroupSelector(mode: String) {
        store.edit { it[KEY_PHONE_GROUP_SELECTOR.s()] = mode }
    }

    /**
     * Phone / tablet (Apple issue #102 parity): with the Sidebar Menu group
     * selector, a swipe in from the start edge opens the group sidebar and the
     * left/right group-change swipes are off. Default false.
     */
    val liveTvEdgeSwipeOpensSidebar: Flow<Boolean> =
        scopedData.map { it[KEY_LIVE_TV_EDGE_SWIPE_OPENS_SIDEBAR.s()] ?: false }
    suspend fun setLiveTvEdgeSwipeOpensSidebar(enabled: Boolean) {
        store.edit { it[KEY_LIVE_TV_EDGE_SWIPE_OPENS_SIDEBAR.s()] = enabled }
    }

    /**
     * Remote Control (Logan spec 2026-07-20): when true, tuning a channel
     * from the Live TV tab starts it in the corner MINI player (the common
     * IPTV-client two-stage tune) instead of fullscreen. Default false =
     * the original straight-to-fullscreen behavior.
     */
    val guideTuneInMini: Flow<Boolean> =
        scopedData.map { it[KEY_GUIDE_TUNE_IN_MINI.s()] ?: false }
    suspend fun setGuideTuneInMini(value: Boolean) {
        store.edit { it[KEY_GUIDE_TUNE_IN_MINI.s()] = value }
    }

    /**
     * GH #38: refresh rate to request at app STARTUP (TV boxes), so the
     * display is already on the user's main content rate before the first
     * tune (one HDMI handshake at launch instead of at first playback).
     * Wire values: "off" (default) | "50" | "59.94" | "60". Device-local -
     * display hardware differs per box, so this never syncs.
     */
    val startupRefreshRate: Flow<String> =
        scopedData.map { it[KEY_STARTUP_REFRESH_RATE] ?: "off" }
    suspend fun setStartupRefreshRate(value: String) {
        store.edit { it[KEY_STARTUP_REFRESH_RATE] = value }
    }

    /**
     * How old the saved Movies / TV Shows library may be before a launch
     * re-sweeps the provider: 0 = every launch, 24 = daily (default),
     * 168 = weekly. Pull to refresh always sweeps. Apple parity:
     * "vodLibraryRefreshHours".
     */
    /** Movies / TV Shows library sort (Apple parity: moviesSortOrder / seriesSortOrder). */
    val moviesSortOrder: Flow<String> = scopedData.map { it[KEY_MOVIES_SORT_ORDER.s()] ?: "titleAZ" }
    suspend fun setMoviesSortOrder(value: String) { store.edit { it[KEY_MOVIES_SORT_ORDER.s()] = value } }
    val seriesSortOrder: Flow<String> = scopedData.map { it[KEY_SERIES_SORT_ORDER.s()] ?: "titleAZ" }
    suspend fun setSeriesSortOrder(value: String) { store.edit { it[KEY_SERIES_SORT_ORDER.s()] = value } }
    val dvrSortOrder: Flow<String> = scopedData.map { it[KEY_DVR_SORT_ORDER.s()] ?: "title" }
    suspend fun setDvrSortOrder(value: String) { store.edit { it[KEY_DVR_SORT_ORDER.s()] = value } }

    val vodLibraryRefreshHours: Flow<Int> =
        scopedData.map { it[KEY_VOD_LIBRARY_REFRESH_HOURS.s()] ?: 24 }
    suspend fun setVodLibraryRefreshHours(hours: Int) {
        store.edit { it[KEY_VOD_LIBRARY_REFRESH_HOURS.s()] = hours }
    }

    /**
     * GH #40: opt-in output-resolution passthrough (TV boxes). When true,
     * playback switches the display mode to the content's resolution class
     * (1080p stream on a 4K panel -> 1080p output) so the TV does the
     * upscaling. Each switch is a real HDMI mode change (brief black flash),
     * which is why this is default OFF. Device-local.
     */
    val matchContentResolution: Flow<Boolean> =
        scopedData.map { it[KEY_MATCH_CONTENT_RESOLUTION] ?: false }
    suspend fun setMatchContentResolution(value: Boolean) {
        store.edit { it[KEY_MATCH_CONTENT_RESOLUTION] = value }
    }

    /**
     * Android#134: switch the display's refresh rate to the measured content
     * rate (50 / 59.94 / 60 class) while keeping the CURRENT resolution.
     * Independent of [matchContentResolution]. Before 0.5.12 the rate switch
     * only ran as part of resolution matching, so an unset key inherits that
     * toggle and existing users keep exactly what they had. Device-local.
     */
    val matchFrameRate: Flow<Boolean> =
        scopedData.map { it[KEY_MATCH_FRAME_RATE] ?: it[KEY_MATCH_CONTENT_RESOLUTION] ?: false }
    suspend fun setMatchFrameRate(value: Boolean) {
        store.edit { it[KEY_MATCH_FRAME_RATE] = value }
    }

    /**
     * Decoded map with the LEGACY MIGRATION applied: a user who had
     * turned "Apple TV Channel Flip" off and has never customized the
     * new map gets upShort/downShort seeded to NONE so their Up/Down
     * keep operating the chrome exactly as before the mapping layer.
     */
    val effectiveRemoteControlMap: Flow<com.aeriotv.android.core.remote.RemoteControlMap> =
        scopedData.map { data ->
            val raw = data[KEY_REMOTE_CONTROL_MAP] ?: ""
            val map = com.aeriotv.android.core.remote.RemoteControlMap.fromJson(raw)
            if (raw.isBlank() && data[KEY_APPLE_TV_CHANNEL_FLIP.s()] == false) {
                map.copy(
                    player = map.player +
                        mapOf(
                            com.aeriotv.android.core.remote.RemoteSlot.UP_SHORT to
                                com.aeriotv.android.core.remote.PlayerRemoteAction.NONE,
                            com.aeriotv.android.core.remote.RemoteSlot.DOWN_SHORT to
                                com.aeriotv.android.core.remote.PlayerRemoteAction.NONE,
                        ),
                )
            } else {
                map
            }
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
        scopedData.map { it[KEY_AUTO_RECOVER_FROZEN_STREAMS.s()] ?: true }
    suspend fun setAutoRecoverFrozenStreams(value: Boolean) {
        store.edit { it[KEY_AUTO_RECOVER_FROZEN_STREAMS.s()] = value }
    }
    suspend fun autoRecoverFrozenStreamsOnce(): Boolean =
        store.data.first()[KEY_AUTO_RECOVER_FROZEN_STREAMS.s()] ?: true

    /**
     * Player Info Card (App Behaviors, Apple-app parity): which elements the
     * in-player program info card draws while the chrome is showing. These
     * affect ONLY that card -- never the guide, channel list, mini player,
     * notifications or cast UI. All default true; device-local, not synced.
     */
    /**
     * Settings > Player > On-Screen Display > Pop Up Info Card on Channel
     * Change (GH #127, Apple key playerShowChannelInfoCard). Off hides only
     * the card that pops up on a channel change; it still shows with the
     * player controls. Default on; synced like the Apple key.
     */
    val playerShowChannelInfoCard: Flow<Boolean> =
        scopedData.map { it[KEY_PLAYER_SHOW_CHANNEL_INFO_CARD.s()] ?: true }
    suspend fun setPlayerShowChannelInfoCard(value: Boolean) {
        store.edit { it[KEY_PLAYER_SHOW_CHANNEL_INFO_CARD.s()] = value }
    }

    /**
     * Settings > Live TV > Program Info Button (GH #127, Apple key
     * guideProgramInfoButton): "off" (default), "current", "all". Phone and
     * tablet guide cells only. Synced like the Apple key.
     */
    val guideProgramInfoButton: Flow<String> =
        scopedData.map { it[KEY_GUIDE_PROGRAM_INFO_BUTTON.s()] ?: GuideProgramInfoButton.OFF }
    suspend fun setGuideProgramInfoButton(value: String) {
        store.edit { it[KEY_GUIDE_PROGRAM_INFO_BUTTON.s()] = value }
    }

    /** Program Info Button > Guide View (Apple parity 2026-10-13). Unset
     *  follows the old single setting: on unless that was Off. */
    val programInfoButtonGuide: Flow<Boolean> = scopedData.map {
        it[KEY_PROGRAM_INFO_BUTTON_GUIDE.s()]
            ?: ((it[KEY_GUIDE_PROGRAM_INFO_BUTTON.s()] ?: GuideProgramInfoButton.OFF) != GuideProgramInfoButton.OFF)
    }
    suspend fun setProgramInfoButtonGuide(value: Boolean) {
        store.edit { it[KEY_PROGRAM_INFO_BUTTON_GUIDE.s()] = value }
    }

    /** Program Info Button > List View: the same button on channel list rows. Default off. */
    val programInfoButtonList: Flow<Boolean> = scopedData.map { it[KEY_PROGRAM_INFO_BUTTON_LIST.s()] ?: false }
    suspend fun setProgramInfoButtonList(value: Boolean) {
        store.edit { it[KEY_PROGRAM_INFO_BUTTON_LIST.s()] = value }
    }

    val playerCardShowChannelLogo: Flow<Boolean> =
        scopedData.map { it[KEY_PLAYER_CARD_CHANNEL_LOGO.s()] ?: true }
    suspend fun setPlayerCardShowChannelLogo(value: Boolean) {
        store.edit { it[KEY_PLAYER_CARD_CHANNEL_LOGO.s()] = value }
    }

    val playerCardShowChannelName: Flow<Boolean> =
        scopedData.map { it[KEY_PLAYER_CARD_CHANNEL_NAME.s()] ?: true }
    suspend fun setPlayerCardShowChannelName(value: Boolean) {
        store.edit { it[KEY_PLAYER_CARD_CHANNEL_NAME.s()] = value }
    }

    val playerCardShowProgramName: Flow<Boolean> =
        scopedData.map { it[KEY_PLAYER_CARD_PROGRAM_NAME.s()] ?: true }
    suspend fun setPlayerCardShowProgramName(value: Boolean) {
        store.edit { it[KEY_PLAYER_CARD_PROGRAM_NAME.s()] = value }
    }

    val playerCardShowProgramTime: Flow<Boolean> =
        scopedData.map { it[KEY_PLAYER_CARD_PROGRAM_TIME.s()] ?: true }
    suspend fun setPlayerCardShowProgramTime(value: Boolean) {
        store.edit { it[KEY_PLAYER_CARD_PROGRAM_TIME.s()] = value }
    }

    val playerCardShowProgramSubtitle: Flow<Boolean> =
        scopedData.map { it[KEY_PLAYER_CARD_PROGRAM_SUBTITLE.s()] ?: true }
    suspend fun setPlayerCardShowProgramSubtitle(value: Boolean) {
        store.edit { it[KEY_PLAYER_CARD_PROGRAM_SUBTITLE.s()] = value }
    }

    val playerCardShowProgramDescription: Flow<Boolean> =
        scopedData.map { it[KEY_PLAYER_CARD_PROGRAM_DESCRIPTION.s()] ?: true }
    suspend fun setPlayerCardShowProgramDescription(value: Boolean) {
        store.edit { it[KEY_PLAYER_CARD_PROGRAM_DESCRIPTION.s()] = value }
    }

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
        scopedData.map { it[KEY_PROGRAM_POSTERS_TMDB_ENABLED.s()] ?: false }
    suspend fun setProgramPostersTmdbEnabled(value: Boolean) {
        store.edit { it[KEY_PROGRAM_POSTERS_TMDB_ENABLED.s()] = value }
    }

    /**
     * Surround sound (AC-3/E-AC-3) bitstream passthrough over HDMI. Off by default:
     * many TVs decode the bitstream with latency Android reports as zero,
     * which the player cannot compensate, and it shows up as lip-sync drift
     * on live TV. Off means AerioTV decodes the surround audio in-app and outputs
     * PCM on the latency-compensated path. Device-specific by nature, so
     * deliberately NOT part of the sync snapshot.
     */
    val audioPassthroughEnabled: Flow<Boolean> =
        scopedData.map { it[KEY_AUDIO_PASSTHROUGH] ?: false }
    suspend fun setAudioPassthroughEnabled(value: Boolean) {
        store.edit { it[KEY_AUDIO_PASSTHROUGH] = value }
    }

    /**
     * The user's TMDB v3 API key OR v4 read-access token. Empty = unset.
     * Encrypted at rest (audit task #53): stored ciphertext, decrypted on read.
     * Legacy plaintext values written by older builds pass through unchanged.
     */
    val tmdbApiKey: Flow<String> = scopedData.map { cipher.decrypt(it[KEY_TMDB_API_KEY]) ?: "" }
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
    val debugLoggingEnabled: Flow<Boolean> = scopedData.map { it[KEY_DEBUG_LOGGING_ENABLED] ?: false }
    suspend fun setDebugLoggingEnabled(value: Boolean) {
        store.edit { it[KEY_DEBUG_LOGGING_ENABLED] = value }
    }

    /**
     * iOS `castForceHEVCTranscode` parity (DeveloperSettingsView, 2026-09-26):
     * re-encode every H.264 cast channel on the phone, HEVC when the receiver
     * presents it, else the H.264 profile below. Per device, never synced.
     */
    /** Developer: request Dispatcharr HLS output for live channels (test). Per device, never synced. */
    val developerForceHls: Flow<Boolean> = scopedData.map { it[KEY_DEVELOPER_FORCE_HLS] ?: false }
    suspend fun setDeveloperForceHls(value: Boolean) {
        store.edit { it[KEY_DEVELOPER_FORCE_HLS] = value }
    }

    /** Dispatcharr hosts (lowercase host:port) whose plain live URL answered
     *  with an HLS playlist (proxy output set to HLS). Learned by ForceHls so
     *  the next tune goes straight to the HLS session path. Per device, never synced. */
    val serverHlsHosts: Flow<Set<String>> = scopedData.map { it[KEY_SERVER_HLS_HOSTS] ?: emptySet() }
    suspend fun setServerHlsHosts(value: Set<String>) {
        store.edit { it[KEY_SERVER_HLS_HOSTS] = value }
    }

    val castForceHevcTranscode: Flow<Boolean> = scopedData.map { it[KEY_CAST_FORCE_HEVC_TRANSCODE] ?: false }
    suspend fun setCastForceHevcTranscode(value: Boolean) {
        store.edit { it[KEY_CAST_FORCE_HEVC_TRANSCODE] = value }
    }

    /**
     * iOS `castTranscodeDownProfile` parity: the H.264 transcode shape when
     * the receiver cannot present the source or HEVC, "720p60" (default) or
     * "1080p30". Per device, never synced.
     */
    val castTranscodeDownProfile: Flow<String> = scopedData.map { it[KEY_CAST_TRANSCODE_DOWN_PROFILE] ?: "720p60" }
    suspend fun setCastTranscodeDownProfile(value: String) {
        store.edit { it[KEY_CAST_TRANSCODE_DOWN_PROFILE] = value }
    }

    /**
     * iOS `appBehaviorsAutoResumeLastChannel` parity. Stub for now (Android
     * has no mini-player surface yet). Stored anyway so a future port can
     * flip it on without losing the user's prior choice.
     */
    val autoResumeLastChannel: Flow<Boolean> = scopedData.map { it[KEY_AUTO_RESUME_LAST_CHANNEL.s()] ?: false }
    suspend fun setAutoResumeLastChannel(value: Boolean) {
        store.edit { it[KEY_AUTO_RESUME_LAST_CHANNEL.s()] = value }
    }

    /**
     * Last-played channel id. Written by PlayerScreen on every channel-flip
     * and read once by AerioTVNavHost on cold boot. Empty string means
     * "nothing to resume" (first launch / after Settings -> Change playlist).
     */
    val lastWatchedChannelId: Flow<String> = scopedData.map { it[KEY_LAST_WATCHED_CHANNEL_ID] ?: "" }
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
    val lastSeenWhatsNewVersion: Flow<String> = scopedData.map {
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
    val recentChannelIds: Flow<List<String>> = scopedData.map { prefs ->
        val raw = prefs[KEY_RECENT_CHANNEL_IDS.s()] ?: ""
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
            val existing = (prefs[KEY_RECENT_CHANNEL_IDS.s()] ?: "")
                .split('\n')
                .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            val reordered = (listOf(id) + existing.filterNot { it == id }).take(RECENT_CHANNELS_CAP)
            prefs[KEY_RECENT_CHANNEL_IDS.s()] = reordered.joinToString("\n")
        }
    }

    /**
     * GH #130: drop one channel from Recently Watched (channel long-press
     * "Remove from Recently Watched"). Logged as [RECENTS].
     */
    suspend fun removeRecentChannel(channelId: String) {
        store.edit { prefs ->
            val existing = (prefs[KEY_RECENT_CHANNEL_IDS.s()] ?: "")
                .split('\n')
                .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            val kept = existing.filterNot { it == channelId }
            prefs[KEY_RECENT_CHANNEL_IDS.s()] = kept.joinToString("\n")
            android.util.Log.i("AerioRecents", "[RECENTS] removed one channel, ${kept.size} left")
        }
    }

    /** GH #130: Settings > Live TV > Clear Recently Watched (after a confirm). */
    suspend fun clearRecentChannels() {
        store.edit { it.remove(KEY_RECENT_CHANNEL_IDS.s()) }
        android.util.Log.i("AerioRecents", "[RECENTS] cleared")
    }

    /**
     * Rewrite recents entries whose id appears in [renames] (old id -> new
     * id), preserving order and de-duplicating in case both forms are somehow
     * present. Returns how many entries changed. Used by the URL-keyed ->
     * EPG-keyed channel-id migration in PlaylistRepository.
     */
    suspend fun renameRecentChannelIds(renames: Map<String, String>): Int {
        var changed = 0
        store.edit { prefs ->
            val existing = (prefs[KEY_RECENT_CHANNEL_IDS.s()] ?: "")
                .split('\n')
                .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            if (existing.isEmpty()) return@edit
            val rewritten = existing.map { id ->
                renames[id]?.also { changed++ } ?: id
            }.distinct()
            if (changed > 0) {
                prefs[KEY_RECENT_CHANNEL_IDS.s()] = rewritten.joinToString("\n")
            }
        }
        return changed
    }

    /**
     * Hidden group titles from Manage Groups. Newline-delimited list since
     * group names can include any character except newline. Empty string =
     * "no groups hidden", which is the default and matches iOS canon (all
     * groups visible at first launch).
     */
    val hiddenGroups: Flow<Set<String>> = scopedData.map { prefs ->
        val raw = prefs[KEY_HIDDEN_GROUPS.s()] ?: ""
        if (raw.isBlank()) emptySet()
        else raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet()
    }
    suspend fun setHiddenGroups(groups: Set<String>) {
        store.edit { prefs ->
            if (groups.isEmpty()) prefs.remove(KEY_HIDDEN_GROUPS.s())
            else prefs[KEY_HIDDEN_GROUPS.s()] = groups.joinToString("\n")
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
    val groupOrder: Flow<List<String>> = scopedData.map { prefs ->
        val raw = prefs[KEY_GROUP_ORDER.s()] ?: ""
        if (raw.isBlank()) emptyList()
        else raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotBlank) }
    }
    suspend fun setGroupOrder(order: List<String>) {
        store.edit { prefs ->
            if (order.isEmpty()) prefs.remove(KEY_GROUP_ORDER.s())
            else prefs[KEY_GROUP_ORDER.s()] = order.joinToString("\n")
        }
    }
    val groupSortMode: Flow<String> = scopedData.map { prefs ->
        prefs[KEY_GROUP_SORT_MODE.s()] ?: "Default"
    }
    suspend fun setGroupSortMode(mode: String) {
        store.edit { prefs -> prefs[KEY_GROUP_SORT_MODE.s()] = mode }
    }

    /**
     * GH #81: the Live TV group the app opens on, per playlist. Stores the RAW
     * group token exactly as the UI uses it, so synthetic groups round-trip
     * like provider groups do: the Favorites sentinel
     * ([PlaylistViewModel.FAVORITES_GROUP] = "__favorites__"), the All sentinel,
     * a "collection:<id>" token, or a provider group name. Before this the
     * selection lived only in PlaylistViewModel.UiState and every process start
     * reverted to All, so a user who had settled on Favorites lost it on
     * relaunch. Scoped per playlist per the per-playlist media rule: group
     * names are playlist-specific, and the restore is validated against the
     * active playlist's live groups.
     *
     * Empty string = nothing saved yet (open on All).
     *
     * Logan 2026-10-28: no landing reads this any more. Cold start, profile
     * switch and playlist activation all open on the Default Group (or All);
     * the token is still written per profile and synced as lastGroup.
     */
    fun liveGroupToken(playlistId: String): Flow<String> =
        scopedData.map { it[keyLiveGroupToken(playlistId)] ?: "" }

    suspend fun liveGroupTokenOnce(playlistId: String): String =
        if (playlistId.isBlank()) "" else store.data.first()[keyLiveGroupToken(playlistId)] ?: ""

    /** [scopeToken] pins the write to the profile the pick was made in
     *  (captured by the caller before any suspension); omitted = current. */
    suspend fun setLiveGroupToken(playlistId: String, token: String, scopeToken: String = ProfileScope.current()) {
        if (playlistId.isBlank()) return
        val key = keyLiveGroupToken(playlistId, scopeToken)
        store.edit { prefs ->
            if (token.isBlank()) prefs.remove(key)
            else prefs[key] = token
        }
    }

    /**
     * GH #81: the user's explicit "Default Group" for Live TV, per playlist.
     * Same raw-token storage as [liveGroupToken] (All / Favorites sentinels, a
     * collection token, or a provider group name). Empty string = "Last used",
     * which is the default and hands the launch group back to
     * [liveGroupToken]'s restore path. Scoped per playlist because group names
     * are playlist-specific (per-playlist media rule).
     */
    fun defaultGroupToken(playlistId: String): Flow<String> =
        scopedData.map { it[keyDefaultGroupToken(playlistId)] ?: "" }

    suspend fun defaultGroupTokenOnce(playlistId: String): String =
        if (playlistId.isBlank()) "" else store.data.first()[keyDefaultGroupToken(playlistId)] ?: ""

    suspend fun setDefaultGroupToken(playlistId: String, token: String) {
        if (playlistId.isBlank()) return
        store.edit { prefs ->
            if (token.isBlank()) prefs.remove(keyDefaultGroupToken(playlistId))
            else prefs[keyDefaultGroupToken(playlistId)] = token
        }
    }

    /**
     * Whether the synthetic "Recently Watched" group is shown, per playlist.
     * Off by default (Logan 2026-09-14): it sits in Manage Groups unchecked
     * until the user checks it, and picking it as the Default Group checks it
     * too. Scoped per playlist alongside [defaultGroupToken].
     */
    fun recentGroupVisible(playlistId: String): Flow<Boolean> =
        scopedData.map { it[keyRecentGroupVisible(playlistId)] ?: false }

    suspend fun setRecentGroupVisible(playlistId: String, visible: Boolean) {
        if (playlistId.isBlank()) return
        store.edit { prefs ->
            if (visible) prefs[keyRecentGroupVisible(playlistId)] = true
            else prefs.remove(keyRecentGroupVisible(playlistId))
        }
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
    val hiddenDvrChannels: Flow<Set<String>> = scopedData.map { prefs ->
        val raw = prefs[KEY_HIDDEN_DVR_CHANNELS] ?: ""
        if (raw.isBlank()) emptySet()
        else raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet()
    }
    suspend fun setHiddenDvrChannels(channels: Set<String>) {
        store.edit { prefs ->
            if (channels.isEmpty()) prefs.remove(KEY_HIDDEN_DVR_CHANNELS)
            else prefs[KEY_HIDDEN_DVR_CHANNELS] = channels.joinToString("\n")
        }
    }
    // Per profile, like [hiddenGroups] (review 2026-10-27): device-wide keys
    // let one profile's Sync pull overwrite another profile's hidden groups.
    val hiddenMovieGroups: Flow<Set<String>> = scopedData.map { prefs ->
        val raw = prefs[KEY_HIDDEN_MOVIE_GROUPS.s()] ?: ""
        if (raw.isBlank()) emptySet()
        else raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet()
    }
    suspend fun setHiddenMovieGroups(groups: Set<String>) {
        store.edit { prefs ->
            if (groups.isEmpty()) prefs.remove(KEY_HIDDEN_MOVIE_GROUPS.s())
            else prefs[KEY_HIDDEN_MOVIE_GROUPS.s()] = groups.joinToString("\n")
        }
    }
    /**
     * Playlists (by [WatchlistStore.scopeFor] scope) whose Movies / TV Shows
     * Filter list has the "Hidden" category checked. It sits with the other
     * category visibility state but is per playlist, like the hidden titles
     * themselves, and is UNCHECKED until the user turns it on.
     */
    val hiddenCategoryShownMovies: Flow<Set<String>> = scopedData.map { prefs ->
        decodeLines(prefs[KEY_HIDDEN_CATEGORY_SHOWN_MOVIES])
    }
    val hiddenCategoryShownSeries: Flow<Set<String>> = scopedData.map { prefs ->
        decodeLines(prefs[KEY_HIDDEN_CATEGORY_SHOWN_SERIES])
    }

    /** Check / uncheck the Hidden category for one playlist scope. */
    suspend fun setHiddenCategoryShown(isMovie: Boolean, scope: String?, shown: Boolean) {
        val key = if (isMovie) KEY_HIDDEN_CATEGORY_SHOWN_MOVIES else KEY_HIDDEN_CATEGORY_SHOWN_SERIES
        val token = scope ?: HIDDEN_CATEGORY_ANY_PLAYLIST
        store.edit { prefs ->
            val current = decodeLines(prefs[key])
            val next = if (shown) current + token else current - token
            if (next.isEmpty()) prefs.remove(key) else prefs[key] = next.joinToString("\n")
        }
    }

    private fun decodeLines(raw: String?): Set<String> =
        if (raw.isNullOrBlank()) emptySet()
        else raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet()

    // Per profile, like [hiddenGroups] (review 2026-10-27): device-wide keys
    // let one profile's Sync pull overwrite another profile's hidden groups.
    val hiddenSeriesGroups: Flow<Set<String>> = scopedData.map { prefs ->
        val raw = prefs[KEY_HIDDEN_SERIES_GROUPS.s()] ?: ""
        if (raw.isBlank()) emptySet()
        else raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet()
    }
    suspend fun setHiddenSeriesGroups(groups: Set<String>) {
        store.edit { prefs ->
            if (groups.isEmpty()) prefs.remove(KEY_HIDDEN_SERIES_GROUPS.s())
            else prefs[KEY_HIDDEN_SERIES_GROUPS.s()] = groups.joinToString("\n")
        }
    }
    suspend fun autoResumeLastChannelOnce(): Boolean =
        store.data.first()[KEY_AUTO_RESUME_LAST_CHANNEL.s()] ?: false
    suspend fun lastWatchedChannelIdOnce(): String =
        store.data.first()[KEY_LAST_WATCHED_CHANNEL_ID].orEmpty()

    /**
     * iOS `defaultTab` parity. Stores the AppTab enum name. Empty string means
     * "follow iOS default" (Live TV). MainScaffold reads this once on the
     * first composition after bootstrap completes.
     */
    val defaultTab: Flow<String> = scopedData.map { it[KEY_DEFAULT_TAB.s()] ?: "" }
    suspend fun setDefaultTab(value: String) {
        store.edit { it[KEY_DEFAULT_TAB.s()] = value }
    }

    // ── Network ──────────────────────────────────────────────────────────

    /** iOS `networkTimeout` parity. Seconds. 5-60 step 5. Default 15 (iOS). */
    val networkTimeoutSecs: Flow<Double> = scopedData.map { it[KEY_NETWORK_TIMEOUT] ?: 15.0 }
    suspend fun setNetworkTimeoutSecs(value: Double) {
        store.edit { it[KEY_NETWORK_TIMEOUT] = value }
    }

    /** iOS `maxRetries` parity. 0-10. Default 3 (iOS). */
    val maxRetries: Flow<Int> = scopedData.map { it[KEY_MAX_RETRIES] ?: 3 }
    suspend fun setMaxRetries(value: Int) {
        store.edit { it[KEY_MAX_RETRIES] = value }
    }

    /**
     * iOS `streamBufferSize` parity. One of "small" / "default" / "large" /
     * "xlarge" matching the cache-time tiers in MPVPlayerView. PlayerScreen
     * passes the resolved milliseconds into MPV at init time.
     */
    val streamBufferSize: Flow<String> = scopedData.map { it[KEY_STREAM_BUFFER_SIZE] ?: "default" }
    suspend fun setStreamBufferSize(value: String) {
        store.edit { it[KEY_STREAM_BUFFER_SIZE] = value }
    }

    /**
     * RETIRED (Logan 2026-09-11). The guide window is governed by the
     * playlist's Guide Days setting (PlaylistEntity.epgRetentionDays) in both
     * directions; the Settings > Network "Guide Window" picker is gone and
     * nothing writes this key any more. Kept readable only so a future
     * migration can inspect what a user had chosen.
     */
    val epgWindowHours: Flow<Int> = scopedData.map { it[KEY_EPG_WINDOW_HOURS] ?: 24 }

    /**
     * Generation stamp for the on-disk EPG cache. Bump
     * PlaylistViewModel.EPG_CACHE_EPOCH whenever a defect could have left
     * existing caches corrupt: the next launch forces one full refetch and
     * re-stamps, so a bad cache cannot outlive the build that produced it.
     *
     * Added for the 0.4.10 per-source merge bug, where each upstream feed
     * deleted the previous one's present+future and the survivor was still
     * recent enough to suppress the network on every relaunch. A heuristic
     * ("does the cache reach past now?") was tried first and proved unusable:
     * one channel with forward data satisfied it while hundreds had none.
     */
    val epgCacheEpoch: Flow<Int> = scopedData.map { it[KEY_EPG_CACHE_EPOCH] ?: 0 }
    suspend fun setEpgCacheEpoch(value: Int) {
        store.edit { it[KEY_EPG_CACHE_EPOCH] = value }
    }

    /**
     * Per-playlist variant of the epoch stamp. The EPG cache itself is
     * per-playlist, so a single global stamp was wrong-by-scope: the active
     * playlist's successful refetch stamped the epoch for EVERY playlist,
     * and a second, still-poisoned playlist inside its freshness TTL was then
     * trusted, the exact history-only guide the epoch exists to purge. Each
     * playlist now proves ITS cache was rebuilt. Reading defaults to 0, so
     * playlists stamped only by the old global key refetch once and re-stamp.
     */
    suspend fun epgCacheEpochFor(playlistId: String): Int =
        store.data.first()[intPreferencesKey("epg_cache_epoch.$playlistId")] ?: 0

    suspend fun setEpgCacheEpochFor(playlistId: String, value: Int) {
        store.edit { it[intPreferencesKey("epg_cache_epoch.$playlistId")] = value }
    }

    // ── Multiview ────────────────────────────────────────────────────────

    /**
     * iOS `multiviewAudioFocusStyle` parity. One of "centerIcon" (default)
     * / "grayPersistent" / "themeFading". Controls how the multiview grid
     * indicates which tile owns audio.
     */
    val multiviewAudioFocusStyle: Flow<String> = scopedData.map {
        it[KEY_MULTIVIEW_AUDIO_FOCUS_STYLE.s()] ?: "centerIcon"
    }
    suspend fun setMultiviewAudioFocusStyle(value: String) {
        store.edit { it[KEY_MULTIVIEW_AUDIO_FOCUS_STYLE.s()] = value }
    }

    /**
     * iOS `multiviewTilePadding` parity. Adds gaps between tiles. Default ON
     * (iOS 56fe163ad flipped the default to 8pt gutters); users who set it
     * explicitly keep their choice.
     */
    val multiviewTilePadding: Flow<Boolean> = scopedData.map {
        it[KEY_MULTIVIEW_TILE_PADDING.s()] ?: true
    }
    suspend fun setMultiviewTilePadding(value: Boolean) {
        store.edit { it[KEY_MULTIVIEW_TILE_PADDING.s()] = value }
    }

    /** iOS `multiviewTileCornersRounded` parity. Rounds tile corners. */
    val multiviewTileCornersRounded: Flow<Boolean> = scopedData.map {
        it[KEY_MULTIVIEW_TILE_CORNERS_ROUNDED.s()] ?: false
    }
    suspend fun setMultiviewTileCornersRounded(value: Boolean) {
        store.edit { it[KEY_MULTIVIEW_TILE_CORNERS_ROUNDED.s()] = value }
    }

    /** Discord request: draw each tile's channel logo on the tile. Default off. */
    val multiviewShowLogos: Flow<Boolean> = scopedData.map {
        it[KEY_MULTIVIEW_SHOW_LOGOS.s()] ?: false
    }
    suspend fun setMultiviewShowLogos(value: Boolean) {
        store.edit { it[KEY_MULTIVIEW_SHOW_LOGOS.s()] = value }
    }

    /** Logo corner: top_left (default), top_right, bottom_left, bottom_right. */
    val multiviewLogoPosition: Flow<String> = scopedData.map {
        it[KEY_MULTIVIEW_LOGO_POSITION.s()] ?: "top_left"
    }
    suspend fun setMultiviewLogoPosition(value: String) {
        store.edit { it[KEY_MULTIVIEW_LOGO_POSITION.s()] = value }
    }

    /** Logo height as a percent of the video picture height, 5 to 25 in steps of 5. Default 10. */
    val multiviewLogoSize: Flow<Int> = scopedData.map {
        (it[KEY_MULTIVIEW_LOGO_SIZE.s()] ?: 10).coerceIn(5, 25)
    }
    suspend fun setMultiviewLogoSize(value: Int) {
        store.edit { it[KEY_MULTIVIEW_LOGO_SIZE.s()] = value.coerceIn(5, 25) }
    }

    /**
     * iOS `multiviewPerfWarningSuppressed` parity (issue #46). Set by the
     * "Don't Show Again" button on the soft-limit performance warning;
     * device-local, never reset (no Settings toggle on iOS either).
     */
    val multiviewPerfWarningSuppressed: Flow<Boolean> = scopedData.map {
        it[KEY_MULTIVIEW_PERF_WARNING_SUPPRESSED] ?: false
    }
    suspend fun setMultiviewPerfWarningSuppressed(value: Boolean) {
        store.edit { it[KEY_MULTIVIEW_PERF_WARNING_SUPPRESSED] = value }
    }

    /** Add to Multiview picker "Show Subtitles" pill: each channel row adds
     *  the airing program's subtitle (Teamarr carries "Team 1 vs Team 2"
     *  there). Remembered across launches; default off. */
    val multiviewPickerShowSubtitles: Flow<Boolean> =
        scopedData.map { it[KEY_MULTIVIEW_PICKER_SHOW_SUBTITLES.s()] ?: false }
    suspend fun setMultiviewPickerShowSubtitles(value: Boolean) {
        store.edit { it[KEY_MULTIVIEW_PICKER_SHOW_SUBTITLES.s()] = value }
    }

    /** Add to Multiview picker "Show Descriptions" pill: each channel row adds
     *  the program description (two lines max). Default off. */
    val multiviewPickerShowDescriptions: Flow<Boolean> =
        scopedData.map { it[KEY_MULTIVIEW_PICKER_SHOW_DESCRIPTIONS.s()] ?: false }
    suspend fun setMultiviewPickerShowDescriptions(value: Boolean) {
        store.edit { it[KEY_MULTIVIEW_PICKER_SHOW_DESCRIPTIONS.s()] = value }
    }

    /**
     * iOS `multiviewLayoutMode` parity. One of "auto" (default) / "evenGrid" /
     * "spotlight" / "heroCorner". Selectable per-session from the tile context
     * menu; persisted so the choice survives a relaunch. Unknown values fall
     * back to "auto".
     */
    val multiviewLayoutMode: Flow<String> = scopedData.map {
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
    val channelCollections: Flow<List<ChannelCollection>> = scopedData.map { prefs ->
        val raw = prefs[KEY_CHANNEL_COLLECTIONS.s()]
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
            val raw = prefs[KEY_CHANNEL_COLLECTIONS.s()]
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
                prefs.remove(KEY_CHANNEL_COLLECTIONS.s())
            } else {
                prefs[KEY_CHANNEL_COLLECTIONS.s()] = collectionsJson.encodeToString(next)
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

    val categoryPalette: Flow<CategoryPaletteState> = scopedData.map { prefs ->
        val master = prefs[KEY_CATEGORY_MASTER_ENABLE.s()] ?: true
        val overrides = ProgramCategory.entries.mapNotNull { bucket ->
            prefs[stringPreferencesKey(ProfileScope.keyName(bucket.hexStorageKey))]?.let { bucket.storageSuffix to it }
        }.toMap()
        val enabledFlags = ProgramCategory.entries.mapNotNull { bucket ->
            prefs[booleanPreferencesKey(ProfileScope.keyName(bucket.enabledStorageKey))]?.let { bucket.storageSuffix to it }
        }.toMap()
        val customRaw = prefs[KEY_CATEGORY_CUSTOM_JSON.s()]
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
        store.edit { it[KEY_CATEGORY_MASTER_ENABLE.s()] = value }
    }

    suspend fun setCategoryBucketHex(bucket: ProgramCategory, hex: String?) {
        store.edit { prefs ->
            val key = stringPreferencesKey(ProfileScope.keyName(bucket.hexStorageKey))
            if (hex.isNullOrBlank()) prefs.remove(key)
            else prefs[key] = hex.uppercase().removePrefix("#").take(6)
        }
    }

    suspend fun setCategoryBucketEnabled(bucket: ProgramCategory, enabled: Boolean) {
        store.edit { it[booleanPreferencesKey(ProfileScope.keyName(bucket.enabledStorageKey))] = enabled }
    }

    suspend fun resetCategoryPalette() {
        store.edit { prefs ->
            ProgramCategory.entries.forEach { bucket ->
                prefs.remove(stringPreferencesKey(ProfileScope.keyName(bucket.hexStorageKey)))
            }
        }
    }

    suspend fun setCustomCategories(list: List<CustomCategoryEntry>) {
        store.edit { prefs ->
            if (list.isEmpty()) {
                prefs.remove(KEY_CATEGORY_CUSTOM_JSON.s())
            } else {
                prefs[KEY_CATEGORY_CUSTOM_JSON.s()] = Json.encodeToString(list)
            }
        }
    }

    // ── Drive Sync ──────────────────────────────────────────────────────
    //
    // Mirrors iOS Settings > iCloud Sync. Per-category toggles control which
    // snapshots get pushed/pulled on each manual sync. Last push/pull
    // timestamps drive the UI's "Last synced 5 min ago" caption.

    val syncMasterEnabled: Flow<Boolean> = scopedData.map { it[KEY_SYNC_MASTER] ?: false }
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
        scopedData.map { it[KEY_BG_REFRESH_ENABLED] ?: true }
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
        scopedData.map { it[KEY_BG_REFRESH_INTERVAL_MINS] ?: 360 }
    suspend fun setBackgroundRefreshIntervalMins(value: Int) {
        // Clamp to the valid range so the UI selector can't ship a value
        // that WorkManager will reject (Periodic Work requires >= 15min;
        // we conservatively floor at 60 to keep battery use reasonable).
        val clamped = value.coerceIn(60, 2880)
        store.edit { it[KEY_BG_REFRESH_INTERVAL_MINS] = clamped }
    }

    /**
     * Apple `bgRefreshType` parity: "interval" repeats on a timer, "time"
     * refreshes once a day at [backgroundRefreshHour]:[backgroundRefreshMinute].
     * Same stored strings as Apple so the two apps read one vocabulary.
     */
    val backgroundRefreshType: Flow<String> =
        scopedData.map { it[KEY_BG_REFRESH_TYPE] ?: "interval" }
    suspend fun setBackgroundRefreshType(value: String) {
        store.edit { it[KEY_BG_REFRESH_TYPE] = if (value == "time") "time" else "interval" }
    }

    /** Apple `bgRefreshHour` / `bgRefreshMinute`; default 08:00 as Apple. */
    val backgroundRefreshHour: Flow<Int> =
        scopedData.map { it[KEY_BG_REFRESH_HOUR] ?: 8 }
    val backgroundRefreshMinute: Flow<Int> =
        scopedData.map { it[KEY_BG_REFRESH_MINUTE] ?: 0 }
    suspend fun setBackgroundRefreshTime(hour: Int, minute: Int) {
        store.edit {
            it[KEY_BG_REFRESH_HOUR] = hour.coerceIn(0, 23)
            it[KEY_BG_REFRESH_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    fun syncCategoryEnabled(category: SyncCategory): Flow<Boolean> = scopedData.map { prefs ->
        prefs[booleanPreferencesKey(category.enabledStorageKey())] ?: true
    }
    suspend fun setSyncCategoryEnabled(category: SyncCategory, value: Boolean) {
        store.edit { it[booleanPreferencesKey(category.enabledStorageKey())] = value }
    }

    val syncAccountEmail: Flow<String> = scopedData.map { it[KEY_SYNC_ACCOUNT_EMAIL] ?: "" }
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

    val syncLastPushAt: Flow<Long> = scopedData.map { it[KEY_SYNC_LAST_PUSH] ?: 0L }
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
        scopedData.map { it[KEY_CREDENTIALS_SYNC_DISCLOSED] ?: false }
    suspend fun setCredentialsSyncDisclosed(value: Boolean) {
        store.edit { it[KEY_CREDENTIALS_SYNC_DISCLOSED] = value }
    }

    val syncLastPullAt: Flow<Long> = scopedData.map { it[KEY_SYNC_LAST_PULL] ?: 0L }

    /**
     * True once THIS INSTALL has completed at least one Drive pull. Gates
     * every automatic push (periodic worker, Sync Now's push leg): a fresh
     * blank install that signs in must never shove empty snapshots over a
     * populated Drive backup (user report: "my saved config keeps getting
     * overwritten with a blank app"). Device-local, never synced.
     */
    val syncInitialPullDone: Flow<Boolean> =
        scopedData.map { it[KEY_SYNC_INITIAL_PULL_DONE] ?: false }
    suspend fun setSyncInitialPullDone(value: Boolean) {
        store.edit { it[KEY_SYNC_INITIAL_PULL_DONE] = value }
    }
    suspend fun setSyncLastPullAt(value: Long) {
        store.edit { it[KEY_SYNC_LAST_PULL] = value }
    }

    // ── Synced settings registry (docs/DispatcharrSyncSchema.md 4.9) ───────
    //
    // One table drives Dispatcharr Sync (`settings` lanes), the Drive
    // preferences snapshot (string-encoded lanes), and the per-item scopes in
    // Sync Categories. `name` is the Dispatcharr schema name (canonical);
    // `drive` is the Drive wire name, which keeps the older spelling where
    // Drive shipped one first (appearanceTrueBlack, playerShowChannelInfoCard,
    // programPostersTmdbEnabled, skipBackSeconds, skipForwardSeconds,
    // customCategoryColors.v1).

    private enum class SKind { B, I, D, S }

    private class Reg(val name: String, val keyName: String, val kind: SKind, val drive: String = name)

    private val registry: List<Reg> by lazy {
        fun r(n: String, k: Preferences.Key<*>, kind: SKind, drive: String = n) = Reg(n, k.name, kind, drive)
        val b = SKind.B; val i = SKind.I; val d = SKind.D; val s = SKind.S
        listOf(
            r("selectedTheme", KEY_SELECTED_THEME, s),
            r("appearanceMode", KEY_APPEARANCE_MODE, s),
            r("trueBlack", KEY_APPEARANCE_TRUE_BLACK, b, drive = "appearanceTrueBlack"),
            r("useCustomAccent", KEY_USE_CUSTOM_ACCENT, b),
            r("customAccentHex", KEY_CUSTOM_ACCENT_HEX, s),
            r("textScale", KEY_TEXT_SCALE, d),
            r("subtextScale", KEY_SUBTEXT_SCALE, d),
            r("textContrast", KEY_TEXT_CONTRAST, d),
            r("timeFormat", KEY_TIME_FORMAT, s),
            r("defaultTab", KEY_DEFAULT_TAB, s),
            r("skipLoadingScreen", KEY_SKIP_LOADING_SCREEN, b),
            r("autoResumeLastChannel", KEY_AUTO_RESUME_LAST_CHANNEL, b),
            r("defaultLiveTvView", KEY_DEFAULT_LIVE_TV_VIEW, s),
            r("guideLayout", KEY_LIVE_TV_LAYOUT, s),
            r("guideGroupSelector", KEY_GUIDE_GROUP_SELECTOR, s),
            r("phoneGroupSelector", KEY_PHONE_GROUP_SELECTOR, s),
            r("guideSidebarLayout", KEY_GUIDE_SIDEBAR_LAYOUT, s),
            r("liveTvEdgeSwipeOpensSidebar", KEY_LIVE_TV_EDGE_SWIPE_OPENS_SIDEBAR, b),
            r("channelListShowLogos", KEY_SHOW_CHANNEL_LOGOS, b),
            r("channelLogoSize", KEY_CHANNEL_LOGO_SIZE_TENTHS, i),
            r("channelListShowNumbers", KEY_SHOW_CHANNEL_NUMBERS, b),
            r("channelListShowNames", KEY_SHOW_CHANNEL_NAMES, b),
            r("channelListShowProgramSubtitles", KEY_SHOW_PROGRAM_SUBTITLES, b),
            r("showEpgBadgesTv", KEY_SHOW_EPG_BADGES_TV, b),
            r("showEpgBadgesMobile", KEY_SHOW_EPG_BADGES_MOBILE, b),
            r("hiddenEpgBadges", KEY_HIDDEN_EPG_BADGES, s),
            r("guideProgramInfoButton", KEY_GUIDE_PROGRAM_INFO_BUTTON, s),
            r("programInfoButtonGuide", KEY_PROGRAM_INFO_BUTTON_GUIDE, b),
            r("programInfoButtonList", KEY_PROGRAM_INFO_BUTTON_LIST, b),
            r("logoCornersList", KEY_ROUNDED_ARTWORK, b),
            r("logoCornersGuide", KEY_ROUNDED_ARTWORK_GUIDE, b),
            r("logoCornersGuideCells", KEY_ROUNDED_PROGRAM_CELLS, b),
            r("displayScaleLiveTv", KEY_DISPLAY_SCALE_LIVE_TV, d),
            r("displayScaleGuide", KEY_GUIDE_SCALE, d),
            r("enableCategoryColors", KEY_CATEGORY_MASTER_ENABLE, b),
            r("customCategories", KEY_CATEGORY_CUSTOM_JSON, s, drive = "customCategoryColors.v1"),
            r("playerInfoCard.showOnChannelChange", KEY_PLAYER_SHOW_CHANNEL_INFO_CARD, b, drive = "playerShowChannelInfoCard"),
            r("playerInfoCard.channelLogo", KEY_PLAYER_CARD_CHANNEL_LOGO, b),
            r("playerInfoCard.channelName", KEY_PLAYER_CARD_CHANNEL_NAME, b),
            r("playerInfoCard.programName", KEY_PLAYER_CARD_PROGRAM_NAME, b),
            r("playerInfoCard.programTime", KEY_PLAYER_CARD_PROGRAM_TIME, b),
            r("playerInfoCard.programSubtitle", KEY_PLAYER_CARD_PROGRAM_SUBTITLE, b),
            r("playerInfoCard.programDescription", KEY_PLAYER_CARD_PROGRAM_DESCRIPTION, b),
            r("liveRewindEnabled", KEY_LIVE_REWIND_ENABLED, b),
            r("liveRewindDepthMinutes", KEY_LIVE_REWIND_DEPTH_MIN, i),
            r("liveRewindKeepRecent", KEY_LIVE_REWIND_KEEP_RECENT, b),
            r("liveRewindKeepCount", KEY_LIVE_REWIND_KEEP_COUNT, i),
            r("liveRewindRetentionHours", KEY_LIVE_REWIND_RETENTION_HOURS, i),
            r("liveRewindBudgetGb", KEY_LIVE_REWIND_BUDGET_GB, i),
            r("skipBack", KEY_SKIP_BACK_SECONDS, i, drive = "skipBackSeconds"),
            r("skipForward", KEY_SKIP_FORWARD_SECONDS, i, drive = "skipForwardSeconds"),
            r("skipWithoutControls", KEY_SKIP_WITHOUT_CONTROLS, b),
            r("autoRecoverFrozenStreams", KEY_AUTO_RECOVER_FROZEN_STREAMS, b),
            r("appleTVChannelFlip", KEY_APPLE_TV_CHANNEL_FLIP, b),
            r("edgeBrightnessGesture", KEY_PLAYER_BRIGHTNESS_GESTURE, b),
            r("edgeVolumeGesture", KEY_PLAYER_VOLUME_GESTURE, b),
            r("brightnessEdge", KEY_PLAYER_BRIGHTNESS_EDGE, s),
            r("multiview.audioFocusStyle", KEY_MULTIVIEW_AUDIO_FOCUS_STYLE, s),
            r("multiview.tilePadding", KEY_MULTIVIEW_TILE_PADDING, b),
            r("multiview.tileCornersRounded", KEY_MULTIVIEW_TILE_CORNERS_ROUNDED, b),
            r("multiview.showLogos", KEY_MULTIVIEW_SHOW_LOGOS, b),
            r("multiview.logoPosition", KEY_MULTIVIEW_LOGO_POSITION, s),
            r("multiview.logoSize", KEY_MULTIVIEW_LOGO_SIZE, i),
            r("multiview.pickerShowSubtitles", KEY_MULTIVIEW_PICKER_SHOW_SUBTITLES, b),
            r("multiview.pickerShowDescriptions", KEY_MULTIVIEW_PICKER_SHOW_DESCRIPTIONS, b),
            r("tmdbPostersEnabled", KEY_PROGRAM_POSTERS_TMDB_ENABLED, b, drive = "programPostersTmdbEnabled"),
            r("vodLibraryRefreshHours", KEY_VOD_LIBRARY_REFRESH_HOURS, i),
            r("displayScaleMovies", KEY_DISPLAY_SCALE_MOVIES, d),
            r("moviesSortOrder", KEY_MOVIES_SORT_ORDER, s),
            r("seriesSortOrder", KEY_SERIES_SORT_ORDER, s),
            r("dvrPreRollMins", KEY_DVR_DEFAULT_PRE_ROLL, i),
            r("dvrPostRollMins", KEY_DVR_DEFAULT_POST_ROLL, i),
            r("dvrDefaultDestination", KEY_DVR_DEFAULT_DESTINATION, s),
            r("dvrSortOrder", KEY_DVR_SORT_ORDER, s),
            r("showRemoteHints", KEY_SHOW_REMOTE_HINTS, b),
            r("exitDialogFocusExit", KEY_EXIT_DIALOG_FOCUS_EXIT, b),
            r("guideTuneInMini", KEY_GUIDE_TUNE_IN_MINI, b),
        ) + ProgramCategory.entries.flatMap { bucket ->
            listOf(Reg(bucket.hexStorageKey, bucket.hexStorageKey, s), Reg(bucket.enabledStorageKey, bucket.enabledStorageKey, b))
        }
    }

    /** Per-playlist settings names and their key bases ("<base><playlistId>"). */
    private val perPlaylist: List<Triple<String, String, SKind>> = listOf(
        Triple("defaultGroup", "default_group_token_", SKind.S),
        Triple("lastGroup", "live_group_token_", SKind.S),
        Triple("recentGroupVisible", "recent_group_visible_", SKind.B),
    )

    private fun Reg.toWire(raw: Any?): kotlinx.serialization.json.JsonPrimitive? = when (raw) {
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(raw)
        is Int -> kotlinx.serialization.json.JsonPrimitive(raw)
        is Long -> kotlinx.serialization.json.JsonPrimitive(raw)
        is Double -> kotlinx.serialization.json.JsonPrimitive(raw)
        is Float -> kotlinx.serialization.json.JsonPrimitive(raw.toDouble())
        // Stored newline-joined; the wire form is one comma-separated string.
        is String -> kotlinx.serialization.json.JsonPrimitive(
            if (name == "hiddenEpgBadges") raw.split('\n').filter { it.isNotBlank() }.joinToString(",") else raw,
        )
        else -> null
    }

    /** A validated wire value as the stored value; null = remove the key. */
    private fun Reg.fromWire(v: kotlinx.serialization.json.JsonPrimitive): Any? = when (kind) {
        SKind.B -> v.content.toBooleanStrictOrNull()
        SKind.I -> v.content.toIntOrNull()
        SKind.D -> v.content.toDoubleOrNull()?.let { x ->
            when (name) {
                "textScale", "subtextScale" -> snapTextScale(x.toFloat()).toDouble()
                "textContrast" -> snapTextContrast(x.toFloat()).toDouble()
                else -> x
            }
        }
        SKind.S -> v.content.let { c ->
            when {
                name == "hiddenEpgBadges" -> c.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { null }
                name == "customAccentHex" || name.startsWith(DsyncSettingsValidator.COLOR_PREFIX) ->
                    c.removePrefix("#").uppercase().ifEmpty { null }
                else -> c
            }
        }
    }

    /** Drive's string encoding of a wire value, and back (typed by [Reg.kind]). */
    private fun Reg.driveToWire(s: String): kotlinx.serialization.json.JsonPrimitive? = when (kind) {
        SKind.B -> s.toBooleanStrictOrNull()?.let { kotlinx.serialization.json.JsonPrimitive(it) }
        SKind.I -> s.toIntOrNull()?.let { kotlinx.serialization.json.JsonPrimitive(it) }
        SKind.D -> s.toDoubleOrNull()?.let { kotlinx.serialization.json.JsonPrimitive(it) }
        SKind.S -> kotlinx.serialization.json.JsonPrimitive(s)
    }

    private fun putRaw(prefs: MutablePreferences, keyName: String, v: Any) {
        @Suppress("UNCHECKED_CAST")
        when (v) {
            is Boolean -> prefs[booleanPreferencesKey(keyName)] = v
            is Int -> prefs[intPreferencesKey(keyName)] = v
            is Long -> prefs[longPreferencesKey(keyName)] = v
            is Double -> prefs[doublePreferencesKey(keyName)] = v
            is Float -> prefs[androidx.datastore.preferences.core.floatPreferencesKey(keyName)] = v
            is String -> prefs[stringPreferencesKey(keyName)] = v
            is Set<*> -> prefs[androidx.datastore.preferences.core.stringSetPreferencesKey(keyName)] = v as Set<String>
        }
    }

    private fun removeRaw(prefs: MutablePreferences, keyName: String) {
        prefs.asMap().keys.filter { it.name == keyName }.forEach { prefs.remove(it) }
    }

    // ── Per-item sync choices (Settings > Sync > Sync Categories) ─────────
    //
    // Device-local, never synced (and never profile scoped). See
    // core/sync/SyncItems.kt for the lanes and the rules.

    private val isTvDevice: Boolean by lazy {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature("android.software.leanback_only")
    }

    private fun categoryOn(p: Preferences, c: SyncCategory): Boolean =
        p[booleanPreferencesKey(c.enabledStorageKey())] ?: true

    /** Data items: on or off (the old category flags stay the source where they exist). */
    private fun itemOn(p: Preferences, item: SyncItem): Boolean {
        item.category?.let { return categoryOn(p, it) }
        return when (item.id) {
            "remoteMap" -> p[KEY_SYNC_REMOTE_CONTROL_MAP] ?: true
            "radioDefault" -> p[KEY_SYNC_RADIO_MODE_BY_DEFAULT] ?: false
            else -> p[booleanPreferencesKey(item.toggleKey)] ?: when (item.id) {
                // Before per-item choices these rode a category toggle.
                "recents", "collections" -> categoryOn(p, SyncCategory.Favorites)
                "hiddenTitles" -> categoryOn(p, SyncCategory.Watchlist)
                "hiddenGroups", "groupOrder" -> categoryOn(p, SyncCategory.Preferences)
                else -> true
            }
        }
    }

    /** Stored scope (raw), or the App Preferences flag when never chosen. */
    private fun storedScope(p: Preferences, item: SyncItem): SyncScope =
        SyncScope.fromWire(p[stringPreferencesKey(item.scopeKey)])
            ?: if (categoryOn(p, SyncCategory.Preferences)) SyncScope.All else SyncScope.Off

    /** The scope this device honors for [item]. */
    private fun scopeOf(p: Preferences, item: SyncItem): SyncScope =
        if (item.isData) (if (itemOn(p, item)) SyncScope.All else SyncScope.Off)
        else storedScope(p, item).effective(isTvDevice)

    private fun scopeOfName(p: Preferences, name: String): SyncScope =
        SyncItemCatalog.itemForName(name)?.let { scopeOf(p, it) } ?: SyncScope.All

    /** True on a TV (the device class for the `tv` lane). */
    val syncDeviceIsTv: Boolean get() = isTvDevice

    /** Every item's scope as this device honors it, by item id. */
    val syncScopes: Flow<Map<String, SyncScope>> = store.data.map { p ->
        SyncItemCatalog.allItems.associate { it.id to scopeOf(p, it) }
    }
    suspend fun syncScopesOnce(): Map<String, SyncScope> = syncScopes.first()
    suspend fun syncItemOnOnce(id: String): Boolean = syncScopesOnce()[id] != SyncScope.Off

    /** Scope of the item carrying settings name [name] (Dispatcharr Sync lanes). */
    fun syncScopeForName(scopes: Map<String, SyncScope>, name: String): SyncScope =
        SyncItemCatalog.itemForName(name)?.let { scopes[it.id] } ?: SyncScope.All

    /**
     * Sets [itemIds] to [scope]. The first write materializes every unset
     * choice so later flips of the App Preferences flag (kept in step below,
     * for older builds and the Drive file gate) never move an untouched item.
     */
    suspend fun setSyncScope(itemIds: Collection<String>, scope: SyncScope) {
        store.edit { p ->
            SyncItemCatalog.allItems.forEach { item ->
                if (item.category != null || item.id == "remoteMap" || item.id == "radioDefault") return@forEach
                if (item.isData) {
                    if (p[booleanPreferencesKey(item.toggleKey)] == null) p[booleanPreferencesKey(item.toggleKey)] = itemOn(p, item)
                } else if (p[stringPreferencesKey(item.scopeKey)] == null) {
                    p[stringPreferencesKey(item.scopeKey)] = storedScope(p, item).wire
                }
            }
            itemIds.mapNotNull(SyncItemCatalog::item).forEach { item ->
                val on = scope != SyncScope.Off
                when {
                    item.category != null -> p[booleanPreferencesKey(item.category.enabledStorageKey())] = on
                    item.id == "remoteMap" -> p[KEY_SYNC_REMOTE_CONTROL_MAP] = on
                    item.id == "radioDefault" -> p[KEY_SYNC_RADIO_MODE_BY_DEFAULT] = on
                    item.isData -> p[booleanPreferencesKey(item.toggleKey)] = on
                    // A device offers only its own class lane; anything else reads as All devices.
                    else -> p[stringPreferencesKey(item.scopeKey)] = scope.effective(isTvDevice).wire
                }
            }
            val prefsRiders = setOf("recents", "collections", "hiddenGroups", "groupOrder", "remoteMap", "radioDefault")
            val anyOn = SyncItemCatalog.allItems.any { item ->
                (!item.isData && storedScope(p, item) != SyncScope.Off) || (item.id in prefsRiders && itemOn(p, item))
            }
            p[booleanPreferencesKey(SyncCategory.Preferences.enabledStorageKey())] = anyOn
        }
        android.util.Log.i("AppPreferences", "[SYNC] scope ${scope.wire} for ${itemIds.joinToString(",")}")
    }

    // ── Drive preferences snapshot (default profile, string lanes) ─────────

    /** The Drive preferences file's lanes: `v` (all devices), `tv`, and `mobile`. */
    data class DriveLanes(
        val v: Map<String, String>,
        val tv: Map<String, String> = emptyMap(),
        val mobile: Map<String, String> = emptyMap(),
    ) {
        fun lane(name: String): Map<String, String> = when (name) {
            SyncScope.LANE_TV -> tv
            SyncScope.LANE_MOBILE -> mobile
            else -> v
        }
    }

    /** Drive data keys in `v`: wire name to (item id, stored key name). */
    private val driveDataKeys: List<Triple<String, String, String>> by lazy {
        listOf(
            Triple("remoteControlMap", "remoteMap", KEY_REMOTE_CONTROL_MAP.name),
            Triple("radioModeByDefault", "radioDefault", KEY_RADIO_MODE_BY_DEFAULT.name),
            Triple("hiddenGroups.v1", "hiddenGroups", KEY_HIDDEN_GROUPS.name),
            Triple("hiddenMovieGroups.v1", "hiddenGroups", KEY_HIDDEN_MOVIE_GROUPS.name),
            Triple("hiddenSeriesGroups.v1", "hiddenGroups", KEY_HIDDEN_SERIES_GROUPS.name),
            Triple("groupOrder.v1", "groupOrder", KEY_GROUP_ORDER.name),
            Triple("groupSortMode.v1", "groupOrder", KEY_GROUP_SORT_MODE.name),
            Triple("recents.v1", "recents", KEY_RECENT_CHANNEL_IDS.name),
            Triple("channelCollections.v1", "collections", KEY_CHANNEL_COLLECTIONS.name),
        )
    }

    private fun lanesCache(p: Preferences): DriveLanes {
        val raw = p[KEY_DRIVE_PREFS_LANES] ?: return DriveLanes(emptyMap())
        return runCatching {
            val o = Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
            fun m(k: String) = (o[k] as? kotlinx.serialization.json.JsonObject)?.mapNotNull { (n, e) ->
                (e as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { n to it }
            }?.toMap().orEmpty()
            DriveLanes(m("v"), m("tv"), m("mobile"))
        }.getOrDefault(DriveLanes(emptyMap()))
    }

    private fun encodeLanes(l: DriveLanes): String = kotlinx.serialization.json.JsonObject(
        mapOf("v" to l.v, "tv" to l.tv, "mobile" to l.mobile).mapValues { (_, m) ->
            kotlinx.serialization.json.JsonObject(m.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })
        },
    ).toString()

    /**
     * Snapshot the keys we sync via Drive (default profile), split into lanes
     * by each item's scope on this device. Device-local settings (buffer
     * size, network timeout, display matching, DVR storage) never ride it,
     * and the TMDB API key is no longer sent (it was cleartext).
     *
     * Drive replaces the whole file, so values this device does not write
     * (items Off here, the other class's lane, other platforms' names) are
     * carried from the last pulled copy, untouched.
     */
    suspend fun snapshotSyncablePreferences(): DriveLanes {
        val p = store.data.first()
        val m = p.asMap().mapKeys { it.key.name }
        val mine = mapOf(SyncScope.LANE_ALL to LinkedHashMap<String, String>(), SyncScope.LANE_TV to LinkedHashMap(), SyncScope.LANE_MOBILE to LinkedHashMap())
        val ownLane = HashMap<String, String?>()
        registry.forEach { r ->
            val lane = scopeOfName(p, r.name).lane(isTvDevice)
            ownLane[r.drive] = lane
            if (lane != null) r.toWire(m[r.keyName])?.let { mine.getValue(lane)[r.drive] = it.content }
        }
        driveDataKeys.forEach { (wire, itemId, keyName) ->
            val on = SyncItemCatalog.item(itemId)?.let { itemOn(p, it) } ?: true
            ownLane[wire] = if (on) SyncScope.LANE_ALL else null
            if (on) (m[keyName] as? Any)?.let { v -> if (v.toString().isNotBlank()) mine.getValue(SyncScope.LANE_ALL)[wire] = v.toString() }
        }
        // Per-playlist group choices travel as one JSON object {playlistId: value}.
        val groupOn = SyncItemCatalog.item("groupOrder")?.let { itemOn(p, it) } ?: true
        perPlaylist.forEach { (name, base, _) ->
            ownLane[name] = if (groupOn) SyncScope.LANE_ALL else null
            if (!groupOn) return@forEach
            val obj = m.entries.mapNotNull { (k, v) ->
                if (!k.startsWith(base) || k.contains("@prof:")) return@mapNotNull null
                val pid = k.removePrefix(base)
                when (v) {
                    is String -> if (v.isBlank()) null else pid to kotlinx.serialization.json.JsonPrimitive(v)
                    is Boolean -> pid to kotlinx.serialization.json.JsonPrimitive(v)
                    else -> null
                }
            }.toMap()
            if (obj.isNotEmpty()) mine.getValue(SyncScope.LANE_ALL)[name] = kotlinx.serialization.json.JsonObject(obj).toString()
        }
        ownLane["tmdbApiKey"] = SyncScope.LANE_ALL // never carried forward
        val cache = lanesCache(p)
        fun merged(lane: String): Map<String, String> =
            cache.lane(lane).filterKeys { ownLane.getOrDefault(it, "?") != lane && !(lane == SyncScope.LANE_ALL && it == "tmdbApiKey") } + mine.getValue(lane)
        return DriveLanes(merged(SyncScope.LANE_ALL), merged(SyncScope.LANE_TV), merged(SyncScope.LANE_MOBILE))
    }

    /** Reverse of [snapshotSyncablePreferences] (default profile). Best-effort decode. */
    suspend fun applySyncedPreferences(lanes: DriveLanes, replace: Boolean = false) {
        store.edit { prefs ->
            prefs[KEY_DRIVE_PREFS_LANES] = encodeLanes(lanes)
            // Explicit Pull from Drive REPLACES (owner decision, Settings
            // phase 3, iPhone parity): a synced preference this device reads
            // but the snapshot does not carry goes back to its default.
            registry.forEach { r ->
                val lane = scopeOfName(prefs, r.name).lane(isTvDevice) ?: return@forEach
                val s = lanes.lane(lane)[r.drive]
                if (s == null) { if (replace) removeRaw(prefs, r.keyName); return@forEach }
                val w = r.driveToWire(s)?.takeIf { DsyncSettingsValidator.accepts(r.name, it) } ?: return@forEach
                val v = r.fromWire(w)
                if (v == null) removeRaw(prefs, r.keyName) else putRaw(prefs, r.keyName, v)
            }
            driveDataKeys.forEach { (wire, itemId, keyName) ->
                val on = SyncItemCatalog.item(itemId)?.let { itemOn(prefs, it) } ?: true
                if (!on) return@forEach
                val s = lanes.v[wire]
                if (s == null) { if (replace) removeRaw(prefs, keyName); return@forEach }
                if (wire == "radioModeByDefault") s.toBooleanStrictOrNull()?.let { prefs[KEY_RADIO_MODE_BY_DEFAULT] = it }
                else prefs[stringPreferencesKey(keyName)] = s
            }
            // Read for one version so an older snapshot still restores the key;
            // it is no longer written (it was cleartext). Re-encrypt at rest.
            lanes.v["tmdbApiKey"]?.let { prefs[KEY_TMDB_API_KEY] = cipher.encrypt(it) ?: it }
            if (SyncItemCatalog.item("groupOrder")?.let { itemOn(prefs, it) } != false) {
                perPlaylist.forEach { (name, base, kind) ->
                    val blob = lanes.v[name]
                    if (blob == null) {
                        if (replace) prefs.asMap().keys.filter { it.name.startsWith(base) && !it.name.contains("@prof:") }.forEach { prefs.remove(it) }
                        return@forEach
                    }
                    val obj = runCatching { Json.parseToJsonElement(blob) as? kotlinx.serialization.json.JsonObject }.getOrNull()
                    obj?.forEach { (pid, e) ->
                        val prim = e as? kotlinx.serialization.json.JsonPrimitive ?: return@forEach
                        if (pid.isBlank() || !DsyncSettingsValidator.accepts(name, prim)) return@forEach
                        if (kind == SKind.B) prim.content.toBooleanStrictOrNull()?.let { prefs[booleanPreferencesKey(base + pid)] = it }
                        else if (prim.content.isBlank()) removeRaw(prefs, base + pid) else prefs[stringPreferencesKey(base + pid)] = prim.content
                    }
                }
            }
        }
    }

    // ── Dispatcharr Sync (docs/DispatcharrSyncSchema.md) ───────────────────

    /**
     * Settings > Sync > Sync with: "off", "drive", or "dispatcharr". Unset
     * follows the Drive master toggle so existing Drive users stay on Drive.
     * Device-local, never synced.
     */
    val syncProvider: Flow<String> = scopedData.map { p ->
        p[KEY_SYNC_PROVIDER] ?: if (p[KEY_SYNC_MASTER] == true) "drive" else "off"
    }
    suspend fun syncProviderOnce(): String = syncProvider.first()
    suspend fun setSyncProvider(value: String) {
        store.edit { it[KEY_SYNC_PROVIDER] = value }
    }

    /** Category toggle read for Dispatcharr Sync (same toggles and defaults as Drive). */
    suspend fun syncCategoryEnabledOnce(category: SyncCategory): Boolean = syncCategoryEnabled(category).first()
    suspend fun syncRemoteControlMapOnce(): Boolean = syncRemoteControlMap.first()
    suspend fun syncRadioModeByDefaultOnce(): Boolean = syncRadioModeByDefault.first()

    /** Schema 4.9 names this platform owns (Android column of the allowlist), in [token]'s scope. */
    private fun dsyncSettingsOf(p: Preferences, playlistId: String?, token: String): Map<String, kotlinx.serialization.json.JsonPrimitive> {
        val out = LinkedHashMap<String, kotlinx.serialization.json.JsonPrimitive>()
        val m = p.asMap().mapKeys { it.key.name }
        registry.forEach { r -> r.toWire(m[ProfileScope.keyName(r.keyName, token)])?.let { out[r.name] = it } }
        // GH #81 Default Group (and the last-used group and the Recently
        // Watched group flag) for the session's playlist; "" = none. Dsync
        // data is already per profile and per playlist, so values go as is.
        if (!playlistId.isNullOrBlank()) perPlaylist.forEach { (name, base, kind) ->
            val raw = m[ProfileScope.keyName(base + playlistId, token)]
            when (kind) {
                SKind.B -> (raw as? Boolean)?.let { out[name] = kotlinx.serialization.json.JsonPrimitive(it) }
                else -> out[name] = kotlinx.serialization.json.JsonPrimitive(raw as? String ?: "")
            }
        }
        return out
    }

    /** Current values of the owned settings names in the active profile's scope. */
    fun dsyncSettings(playlistId: String?): Flow<Map<String, kotlinx.serialization.json.JsonPrimitive>> =
        scopedData.map { dsyncSettingsOf(it, playlistId, ProfileScope.current()) }
    suspend fun dsyncSettingsOnce(playlistId: String?, token: String = ProfileScope.current()): Map<String, kotlinx.serialization.json.JsonPrimitive> =
        dsyncSettingsOf(store.data.first(), playlistId, token)

    /** Every playlist's Default Group token in the active scope (Dsync change detection between sessions). */
    val defaultGroupTokensAll: Flow<Map<String, String>> = scopedData.map { p ->
        val marker = ProfileScope.keyName("default_group_token_")
        p.asMap().entries.filter { it.key.name.startsWith(marker) }.associate { it.key.name to (it.value as? String ?: "") }
    }

    /**
     * Apply pulled settings (already lane-picked and validated) to [token]'s
     * scope only, so a pull for one profile never touches another. Names this
     * platform does not own are ignored.
     */
    suspend fun applyDsyncSettings(
        v: Map<String, kotlinx.serialization.json.JsonPrimitive>,
        playlistId: String?,
        token: String = ProfileScope.current(),
    ) {
        store.edit { p ->
            registry.forEach { r ->
                val w = v[r.name] ?: return@forEach
                if (r.name == "selectedTheme" && AppTheme.entries.none { it.name == w.content }) return@forEach
                val key = ProfileScope.keyName(r.keyName, token)
                val value = r.fromWire(w)
                if (value == null) removeRaw(p, key) else putRaw(p, key, value)
            }
            // An unknown group is stored as is; the launch path falls back when it is not in knownGroups.
            if (!playlistId.isNullOrBlank()) perPlaylist.forEach { (name, base, kind) ->
                val w = v[name] ?: return@forEach
                val key = ProfileScope.keyName(base + playlistId, token)
                if (kind == SKind.B) w.content.toBooleanStrictOrNull()?.let { p[booleanPreferencesKey(key)] = it }
                else if (w.content.isBlank()) removeRaw(p, key) else p[stringPreferencesKey(key)] = w.content
            }
        }
    }

    /** Replace the recents list (Dispatcharr Sync pull), most recent first. */
    suspend fun setRecentChannels(ids: List<String>) {
        val k = KEY_RECENT_CHANNEL_IDS.s()
        store.edit { prefs ->
            val clean = ids.mapNotNull { it.trim().takeIf(String::isNotBlank) }.distinct().take(RECENT_CHANNELS_CAP)
            if (clean.isEmpty()) prefs.remove(k) else prefs[k] = clean.joinToString("\n")
        }
    }

    /** Replace the collections list (Dispatcharr Sync pull). */
    suspend fun setChannelCollections(list: List<ChannelCollection>) {
        val k = KEY_CHANNEL_COLLECTIONS.s()
        store.edit { prefs ->
            if (list.isEmpty()) prefs.remove(k) else prefs[k] = collectionsJson.encodeToString(list)
        }
    }

    // ---- Dispatcharr Sync: reads and writes for ONE profile's scope ----
    // A sync session is bound to the profile token captured at its start.
    // These take that token instead of reading ProfileScope.current(), so a
    // pull or push never lands in (or reads from) another profile's keys.

    private fun scopedString(base: Preferences.Key<String>, token: String) =
        stringPreferencesKey(ProfileScope.keyName(base.name, token))

    private fun splitLines(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList() else raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotBlank) }

    private suspend fun setLines(base: Preferences.Key<String>, token: String, lines: Collection<String>) {
        val k = scopedString(base, token)
        store.edit { prefs -> if (lines.isEmpty()) prefs.remove(k) else prefs[k] = lines.joinToString("\n") }
    }

    suspend fun recentChannelIdsIn(token: String): List<String> =
        splitLines(store.data.first()[scopedString(KEY_RECENT_CHANNEL_IDS, token)])
    suspend fun setRecentChannelsIn(ids: List<String>, token: String) =
        setLines(KEY_RECENT_CHANNEL_IDS, token, ids.mapNotNull { it.trim().takeIf(String::isNotBlank) }.distinct().take(RECENT_CHANNELS_CAP))

    suspend fun hiddenGroupsIn(token: String): Set<String> = splitLines(store.data.first()[scopedString(KEY_HIDDEN_GROUPS, token)]).toSet()
    suspend fun hiddenMovieGroupsIn(token: String): Set<String> = splitLines(store.data.first()[scopedString(KEY_HIDDEN_MOVIE_GROUPS, token)]).toSet()
    suspend fun hiddenSeriesGroupsIn(token: String): Set<String> = splitLines(store.data.first()[scopedString(KEY_HIDDEN_SERIES_GROUPS, token)]).toSet()
    suspend fun setHiddenGroupsIn(live: Set<String>, movies: Set<String>, series: Set<String>, token: String) {
        val kl = scopedString(KEY_HIDDEN_GROUPS, token)
        val km = scopedString(KEY_HIDDEN_MOVIE_GROUPS, token)
        val ks = scopedString(KEY_HIDDEN_SERIES_GROUPS, token)
        store.edit { prefs ->
            if (live.isEmpty()) prefs.remove(kl) else prefs[kl] = live.joinToString("\n")
            if (movies.isEmpty()) prefs.remove(km) else prefs[km] = movies.joinToString("\n")
            if (series.isEmpty()) prefs.remove(ks) else prefs[ks] = series.joinToString("\n")
        }
    }

    suspend fun groupOrderIn(token: String): List<String> = splitLines(store.data.first()[scopedString(KEY_GROUP_ORDER, token)])
    suspend fun groupSortModeIn(token: String): String = store.data.first()[scopedString(KEY_GROUP_SORT_MODE, token)] ?: "Default"
    suspend fun setGroupOrderIn(mode: String, order: List<String>, token: String) {
        val km = scopedString(KEY_GROUP_SORT_MODE, token)
        val ko = scopedString(KEY_GROUP_ORDER, token)
        store.edit { prefs ->
            prefs[km] = mode
            if (order.isEmpty()) prefs.remove(ko) else prefs[ko] = order.joinToString("\n")
        }
    }

    suspend fun channelCollectionsIn(token: String): List<ChannelCollection> {
        val raw = store.data.first()[scopedString(KEY_CHANNEL_COLLECTIONS, token)]
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { collectionsJson.decodeFromString<List<ChannelCollection>>(raw) }.getOrDefault(emptyList())
    }
    suspend fun setChannelCollectionsIn(list: List<ChannelCollection>, token: String) {
        val k = scopedString(KEY_CHANNEL_COLLECTIONS, token)
        store.edit { prefs -> if (list.isEmpty()) prefs.remove(k) else prefs[k] = collectionsJson.encodeToString(list) }
    }

    suspend fun radioModeByDefaultIn(token: String): Boolean =
        store.data.first()[booleanPreferencesKey(ProfileScope.keyName(KEY_RADIO_MODE_BY_DEFAULT.name, token))] ?: false
    suspend fun setRadioModeByDefaultIn(value: Boolean, token: String) {
        val k = booleanPreferencesKey(ProfileScope.keyName(KEY_RADIO_MODE_BY_DEFAULT.name, token))
        store.edit { it[k] = value }
    }

    // ── In-app updater (github flavor) ───────────────────────────────────
    // Device-local bookkeeping for the GitHub-releases self-updater. NONE of
    // these belong in snapshotSyncablePreferences (per-device state).

    /** uptime-independent wall clock of the last automatic update check. */
    val updateLastCheckAt: Flow<Long> = scopedData.map { it[KEY_UPDATE_LAST_CHECK_AT] ?: 0L }
    suspend fun setUpdateLastCheckAt(value: Long) {
        store.edit { it[KEY_UPDATE_LAST_CHECK_AT] = value }
    }
    suspend fun updateLastCheckAtOnce(): Long = store.data.first()[KEY_UPDATE_LAST_CHECK_AT] ?: 0L

    /** versionName the user chose "Later" on; the launch prompt skips it.
     *  Manual checks in Settings ignore the skip. */
    val updateSkippedVersion: Flow<String> = scopedData.map { it[KEY_UPDATE_SKIPPED_VERSION] ?: "" }
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
    val updatePendingJson: Flow<String> = scopedData.map { it[KEY_UPDATE_PENDING] ?: "" }
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
        scopedData.map { it[KEY_LIVE_REWIND_ENABLED.s()] ?: false }
    suspend fun setLiveRewindEnabled(value: Boolean) {
        store.edit { it[KEY_LIVE_REWIND_ENABLED.s()] = value }
    }

    /** Live Rewind: rewind depth in minutes (15/30/60/120, default 30). */
    val liveRewindDepthMinutes: kotlinx.coroutines.flow.Flow<Int> =
        scopedData.map { it[KEY_LIVE_REWIND_DEPTH_MIN.s()] ?: 30 }
    suspend fun setLiveRewindDepthMinutes(value: Int) {
        store.edit { it[KEY_LIVE_REWIND_DEPTH_MIN.s()] = value }
    }

    /** Keep Recent Channels Live (iOS parity): flipped-away channels keep
     *  buffering in the background so flipping back rewinds across the
     *  time away. Off by default: each kept channel is an extra
     *  simultaneous provider connection. */
    val liveRewindKeepRecent: kotlinx.coroutines.flow.Flow<Boolean> =
        scopedData.map { it[KEY_LIVE_REWIND_KEEP_RECENT.s()] ?: false }
    suspend fun setLiveRewindKeepRecent(value: Boolean) {
        store.edit { it[KEY_LIVE_REWIND_KEEP_RECENT.s()] = value }
    }

    /**
     * GH #129 (Apple 5e34cf6 parity): per-playlist Catch-Up Time Offset in
     * minutes, -180..180, default 0. Same key as Apple
     * (`catchupTimeOffsetMinutes.<playlistId>`). Applied only to the times
     * sent to the server (timeshift URL start, Dispatcharr session start,
     * seek re-tunes), never to the guide or saved positions.
     */
    fun catchupTimeOffsetMinutes(playlistId: String): kotlinx.coroutines.flow.Flow<Int> =
        scopedData.map { (it[keyCatchupTimeOffset(playlistId)] ?: 0).coerceIn(-180, 180) }
    suspend fun setCatchupTimeOffsetMinutes(playlistId: String, minutes: Int) {
        store.edit { it[keyCatchupTimeOffset(playlistId)] = minutes.coerceIn(-180, 180) }
    }

    /** Guide rebuild (docs/guide-semantics.md s.4): the identity fingerprint
     *  (GuideIdentityHash) the playlist's EPG cache was last built for. Empty
     *  when never stamped. */
    fun epgIdentityHash(playlistId: String): kotlinx.coroutines.flow.Flow<String> =
        scopedData.map { it[keyEpgIdentityHash(playlistId)] ?: "" }
    suspend fun setEpgIdentityHash(playlistId: String, hash: String) {
        store.edit { it[keyEpgIdentityHash(playlistId)] = hash }
    }

    /** Fingerprint of the last EPG fetch saved to Room (no-op fetch detection). */
    fun epgFetchFingerprint(playlistId: String): kotlinx.coroutines.flow.Flow<String> =
        scopedData.map { it[stringPreferencesKey("epg_fetch_fingerprint_$playlistId")] ?: "" }
    suspend fun setEpgFetchFingerprint(playlistId: String, print: String) {
        store.edit { it[stringPreferencesKey("epg_fetch_fingerprint_$playlistId")] = print }
    }

    /**
     * ETag / Last-Modified from the last COMPLETE parse of an upstream EPG
     * feed, keyed by feed url (guide rebuild: unchanged feeds are not
     * re-downloaded or re-parsed). Either half may be null.
     */
    fun feedValidators(url: String): kotlinx.coroutines.flow.Flow<Pair<String?, String?>> =
        scopedData.map { p ->
            val raw = p[keyFeedValidators(url)] ?: return@map null to null
            val i = raw.indexOf('\n')
            if (i < 0) raw.ifEmpty { null } to null
            else raw.substring(0, i).ifEmpty { null } to raw.substring(i + 1).ifEmpty { null }
        }
    suspend fun setFeedValidators(url: String, etag: String?, lastModified: String?) {
        store.edit { it[keyFeedValidators(url)] = (etag ?: "") + "\n" + (lastModified ?: "") }
    }

    /** Skip Intervals (Logan 2026-09-14): seconds a skip-back control moves
     *  (5/10/15/30/60, default 10). Synced; mirrored into
     *  [com.aeriotv.android.core.ui.SkipIntervals] by the Application. */
    val skipBackSeconds: Flow<Int> = scopedData.map {
        com.aeriotv.android.core.ui.SkipIntervals.sanitize(
            it[KEY_SKIP_BACK_SECONDS.s()],
            com.aeriotv.android.core.ui.SkipIntervals.DEFAULT_BACK_SECONDS,
        )
    }
    suspend fun setSkipBackSeconds(value: Int) {
        store.edit { it[KEY_SKIP_BACK_SECONDS.s()] = value }
    }

    /** Skip Intervals: seconds a skip-forward control moves (default 30). */
    val skipForwardSeconds: Flow<Int> = scopedData.map {
        com.aeriotv.android.core.ui.SkipIntervals.sanitize(
            it[KEY_SKIP_FORWARD_SECONDS.s()],
            com.aeriotv.android.core.ui.SkipIntervals.DEFAULT_FORWARD_SECONDS,
        )
    }
    suspend fun setSkipForwardSeconds(value: Int) {
        store.edit { it[KEY_SKIP_FORWARD_SECONDS.s()] = value }
    }

    /**
     * GH #94 (Apple parity): Settings > Player > "Skip Without Controls".
     * TV only. With the controls hidden, D-pad Left/Right skip back/forward
     * by the Skip intervals instead of opening the timeline. Default OFF.
     * Device-local.
     */
    val skipWithoutControls: Flow<Boolean> =
        scopedData.map { it[KEY_SKIP_WITHOUT_CONTROLS.s()] ?: false }
    suspend fun setSkipWithoutControls(value: Boolean) {
        store.edit { it[KEY_SKIP_WITHOUT_CONTROLS.s()] = value }
    }

    /** How many recent channels stay live (1-5, default 2). */
    val liveRewindKeepCount: kotlinx.coroutines.flow.Flow<Int> =
        scopedData.map { (it[KEY_LIVE_REWIND_KEEP_COUNT.s()] ?: 2).coerceIn(1, 5) }
    suspend fun setLiveRewindKeepCount(value: Int) {
        store.edit { it[KEY_LIVE_REWIND_KEEP_COUNT.s()] = value.coerceIn(1, 5) }
    }

    /** Live Rewind P2: the one-time feature prompt has been answered
     *  (either way). Never shown again once set. */
    val liveRewindPromptSeen: kotlinx.coroutines.flow.Flow<Boolean> =
        scopedData.map { it[KEY_LIVE_REWIND_PROMPT_SEEN] ?: false }
    suspend fun setLiveRewindPromptSeen(value: Boolean) {
        store.edit { it[KEY_LIVE_REWIND_PROMPT_SEEN] = value }
    }

    /** Live Rewind P2: retention for buffered video from past sessions,
     *  in hours (1/6/12/24/72/168 presets or custom, default 24). */
    val liveRewindRetentionHours: kotlinx.coroutines.flow.Flow<Int> =
        scopedData.map { it[KEY_LIVE_REWIND_RETENTION_HOURS.s()] ?: 24 }
    suspend fun setLiveRewindRetentionHours(value: Int) {
        store.edit { it[KEY_LIVE_REWIND_RETENTION_HOURS.s()] = value.coerceIn(1, 24 * 30) }
    }

    /** Live Rewind P2: total on-disk budget across sessions, in GB
     *  (default 10). Oldest segments are evicted first when exceeded. */
    val liveRewindBudgetGB: kotlinx.coroutines.flow.Flow<Int> =
        scopedData.map { it[KEY_LIVE_REWIND_BUDGET_GB.s()] ?: 10 }
    suspend fun setLiveRewindBudgetGB(value: Int) {
        store.edit { it[KEY_LIVE_REWIND_BUDGET_GB.s()] = value.coerceIn(1, 500) }
    }

    /** iOS `dvrMaxLocalStorageMB` parity. Default 10 GB. */
    val dvrMaxLocalStorageMB: Flow<Int> = scopedData.map { it[KEY_DVR_MAX_LOCAL_STORAGE_MB] ?: 10_240 }
    suspend fun setDvrMaxLocalStorageMB(value: Int) {
        store.edit { it[KEY_DVR_MAX_LOCAL_STORAGE_MB] = value }
    }

    /** iOS `dvrDefaultPreRollMins` parity. */
    /** Task #50 (iOS parity): the record sheet's pre-seeded destination.
     *  "server" (default) records on Dispatcharr when the account can;
     *  "local" pre-selects this device even for server-capable accounts.
     *  Device-local like the other DVR settings (the capability gate in the
     *  sheet still forces local for non-admin accounts regardless). */
    val dvrDefaultDestination: Flow<String> =
        scopedData.map { it[KEY_DVR_DEFAULT_DESTINATION.s()] ?: "server" }
    suspend fun setDvrDefaultDestination(value: String) {
        store.edit { it[KEY_DVR_DEFAULT_DESTINATION.s()] = value }
    }

    val dvrDefaultPreRollMins: Flow<Int> = scopedData.map { it[KEY_DVR_DEFAULT_PRE_ROLL.s()] ?: 0 }
    suspend fun setDvrDefaultPreRollMins(value: Int) {
        store.edit { it[KEY_DVR_DEFAULT_PRE_ROLL.s()] = value }
    }

    /** iOS `dvrDefaultPostRollMins` parity. */
    val dvrDefaultPostRollMins: Flow<Int> = scopedData.map { it[KEY_DVR_DEFAULT_POST_ROLL.s()] ?: 0 }
    suspend fun setDvrDefaultPostRollMins(value: Int) {
        store.edit { it[KEY_DVR_DEFAULT_POST_ROLL.s()] = value }
    }

    /**
     * Custom DVR output folder. Empty string means "use default
     * getExternalFilesDir(Recordings)". Otherwise this is a SAF tree URI the
     * user picked via ACTION_OPEN_DOCUMENT_TREE; we hold persistable RW
     * permission for it for the lifetime of the install.
     */
    val dvrCustomFolderUri: Flow<String> = scopedData.map { it[KEY_DVR_CUSTOM_FOLDER_URI] ?: "" }
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
    val recordingCategories: Flow<Map<String, String>> = scopedData.map { prefs ->
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
     * Last authoritative "has any DVR recordings" verdict per playlist id
     * (JSON list of ids that had recordings). The dynamic DVR tab reads this
     * at launch and on playlist switch so it can render IMMEDIATELY instead
     * of popping in seconds later when the server list arrives; the next
     * completed refresh overwrites the hint with fresh truth. Membership in
     * the set means "show the tab optimistically".
     */
    suspend fun dvrTabHintOnce(playlistId: String): Boolean =
        decodeDvrTabHint(store.data.first()[KEY_DVR_TAB_HINT.s()]).contains(playlistId)

    /** No-op when the stored verdict already matches, so the 30s DVR refresh
     *  loop doesn't churn the DataStore. */
    suspend fun setDvrTabHint(playlistId: String, hasRecordings: Boolean) {
        if (playlistId.isBlank()) return
        store.edit { prefs ->
            val current = decodeDvrTabHint(prefs[KEY_DVR_TAB_HINT.s()])
            val updated = if (hasRecordings) current + playlistId else current - playlistId
            if (updated == current) return@edit
            prefs[KEY_DVR_TAB_HINT.s()] = Json.encodeToString(updated.toList())
        }
    }

    private fun decodeDvrTabHint(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching { Json.decodeFromString<List<String>>(raw) }
            .getOrDefault(emptyList())
            .toSet()
    }

    /**
     * Learned live start buffer per channel id (ms). Android analog of the
     * Apple per-channel learned hold-back: a channel whose feed is delivered
     * in bursts (Sky Sports Main Event UHD through Dispatcharr: 6.9-7.5 s gaps
     * every ~40 s while the 30 s average stays at real time) stalls because the
     * gap outran the start cushion, so after such a stall we raise THIS
     * channel's start gate and apply it on the NEXT tune only. Absent key =
     * today's behavior (the 1_200 ms gate).
     *
     * Each value carries the wall-clock time it was last learned: a tune
     * discards a value older than 30 minutes and falls back to the base gate
     * (Logan 2026-09-12), so one bad session cannot pin a channel forever.
     *
     * Device-local and deliberately NOT in snapshotSyncablePreferences: it is
     * learned from this device's network and provider path.
     */
    val liveStartBufferMs: Flow<Map<String, LearnedStartBuffer>> =
        scopedData.map { decodeLearnedStartBuffers(it) }

    /** One-shot read for the player holder's in-memory cache. */
    suspend fun liveStartBuffersOnce(): Map<String, LearnedStartBuffer> =
        decodeLearnedStartBuffers(store.data.first())

    /**
     * Persist a learned start buffer for one channel and return the resulting
     * map so the caller can refresh its cache without waiting for the Flow.
     * Bounded to [LIVE_START_BUFFER_MAX_ENTRIES]: past the bound the whole map
     * is cleared and relearned rather than grown unbounded on a huge panel.
     */
    suspend fun setLiveStartBufferMs(
        channelId: String,
        ms: Int,
        learnedAtMs: Long = System.currentTimeMillis(),
    ): Map<String, LearnedStartBuffer> {
        val id = channelId.trim()
        if (id.isBlank() || ms <= 0) return liveStartBuffersOnce()
        var result: Map<String, LearnedStartBuffer> = emptyMap()
        store.edit { prefs ->
            val current = decodeLiveStartBuffers(prefs[KEY_LIVE_START_BUFFER_MS])
            val currentAt = decodeLiveStartBufferTimes(prefs[KEY_LIVE_START_BUFFER_AT_MS])
            // An unchanged value still rewrites the timestamp: a stall that
            // re-confirms the learned hold-back re-arms its 30 minute life.
            val grown = current + (id to ms)
            val grownAt = currentAt + (id to learnedAtMs)
            val overBound = grown.size > LIVE_START_BUFFER_MAX_ENTRIES
            val updated = if (overBound) mapOf(id to ms) else grown
            val updatedAt = if (overBound) mapOf(id to learnedAtMs) else grownAt
            prefs[KEY_LIVE_START_BUFFER_MS] = Json.encodeToString(updated)
            prefs[KEY_LIVE_START_BUFFER_AT_MS] = Json.encodeToString(updatedAt)
            result = zipLearnedStartBuffers(updated, updatedAt)
        }
        return result
    }

    private fun decodeLiveStartBuffers(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, Int>>(raw) }
            .getOrDefault(emptyMap())
    }

    private fun decodeLiveStartBufferTimes(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, Long>>(raw) }
            .getOrDefault(emptyMap())
    }

    /** Pairs each learned value with its learn time. A value written before the
     *  timestamp key existed gets 0L, which every caller treats as expired. */
    private fun zipLearnedStartBuffers(
        values: Map<String, Int>,
        times: Map<String, Long>,
    ): Map<String, LearnedStartBuffer> =
        values.mapValues { (id, ms) -> LearnedStartBuffer(ms, times[id] ?: 0L) }

    private fun decodeLearnedStartBuffers(prefs: Preferences): Map<String, LearnedStartBuffer> =
        zipLearnedStartBuffers(
            decodeLiveStartBuffers(prefs[KEY_LIVE_START_BUFFER_MS]),
            decodeLiveStartBufferTimes(prefs[KEY_LIVE_START_BUFFER_AT_MS]),
        )

    /**
     * iOS DVR Settings "Keep device awake during recording" toggle. Default
     * ON (iOS parity). When on, LocalRecordingService holds a partial
     * WakeLock for the duration of an active local recording so the CPU
     * doesn't doze and stall the in-flight download. Reads synchronously at
     * recording start via [dvrKeepAwakeOnce].
     */
    val dvrKeepAwakeDuringRecording: Flow<Boolean> =
        scopedData.map { it[KEY_DVR_KEEP_AWAKE] ?: true }
    suspend fun setDvrKeepAwakeDuringRecording(value: Boolean) {
        store.edit { it[KEY_DVR_KEEP_AWAKE] = value }
    }
    suspend fun dvrKeepAwakeOnce(): Boolean =
        store.data.first()[KEY_DVR_KEEP_AWAKE] ?: true

    /**
     * Display-only Dispatcharr account facts for the Playlist Detail
     * "Dispatcharr User Permissions" section (iOS DispatcharrAccountFactsStore
     * parity): the username the server echoed and the names of the account's
     * assigned Channel Profiles.
     *
     * Kept HERE rather than on the playlist row on purpose: everything else the
     * section shows is already persisted in Room, and these two strings are not
     * worth a schema migration. Nothing here is a credential; the facts are
     * cleared on a credential change and on playlist delete, exactly like the
     * capability snapshot.
     *
     * Wire format: "username" then the profile names, all separated by U+001F.
     */
    fun dispatcharrAccountFacts(playlistId: String): Flow<DispatcharrAccountFacts> =
        scopedData.map { decodeAccountFacts(it[keyAccountFacts(playlistId)]) }

    suspend fun dispatcharrAccountFactsOnce(playlistId: String): DispatcharrAccountFacts =
        decodeAccountFacts(store.data.first()[keyAccountFacts(playlistId)])

    suspend fun setDispatcharrAccountFacts(playlistId: String, facts: DispatcharrAccountFacts) {
        val encoded = (listOf(facts.username) + facts.profileNames)
            .joinToString(FACTS_SEPARATOR) { it.replace(FACTS_SEPARATOR, " ") }
        store.edit { it[keyAccountFacts(playlistId)] = encoded }
    }

    suspend fun clearDispatcharrAccountFacts(playlistId: String) {
        store.edit { it.remove(keyAccountFacts(playlistId)) }
    }

    private fun decodeAccountFacts(raw: String?): DispatcharrAccountFacts {
        if (raw.isNullOrEmpty()) return DispatcharrAccountFacts()
        val parts = raw.split(FACTS_SEPARATOR)
        return DispatcharrAccountFacts(
            username = parts.firstOrNull().orEmpty(),
            profileNames = parts.drop(1).filter { it.isNotBlank() },
        )
    }

    private companion object {
        /** Max entries kept in [recentChannelIds]. Sized for the player's
         *  Recently Watched overlay (top 25, Logan 2026-07-20); the
         *  AddToMultiview sheet still shows only its first few. */
        const val RECENT_CHANNELS_CAP = 25
        val KEY_RECENT_CHANNEL_IDS = stringPreferencesKey("recent_channel_ids")
        /** Guide rebuild: per-playlist identity fingerprint the EPG cache was built for. */
        /** Separator for the packed account-facts string (never in a name). */
        const val FACTS_SEPARATOR = ""
        private fun keyAccountFacts(playlistId: String) =
            stringPreferencesKey(ProfileScope.keyName("dispatcharr_account_facts_$playlistId"))
        private fun keyEpgIdentityHash(playlistId: String) = stringPreferencesKey("epg_identity_hash_$playlistId")
        private fun keyCatchupTimeOffset(playlistId: String) =
            androidx.datastore.preferences.core.intPreferencesKey("catchupTimeOffsetMinutes.$playlistId")

        /** GH #81: per-playlist Live TV group token (see [liveGroupToken]). */
        private fun keyLiveGroupToken(playlistId: String, scopeToken: String = ProfileScope.current()) =
            stringPreferencesKey(ProfileScope.keyName("live_group_token_$playlistId", scopeToken))

        /** GH #81: per-playlist Default Group setting (see [defaultGroupToken]). */
        private fun keyDefaultGroupToken(playlistId: String) = stringPreferencesKey(ProfileScope.keyName("default_group_token_$playlistId"))

        /** Per-playlist "Recently Watched" group visibility (see [recentGroupVisible]). */
        private fun keyRecentGroupVisible(playlistId: String) = booleanPreferencesKey(ProfileScope.keyName("recent_group_visible_$playlistId"))
        private fun keyFeedValidators(url: String): androidx.datastore.preferences.core.Preferences.Key<String> {
            val d = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            return stringPreferencesKey("feed_validators_" + d.joinToString("") { "%02x".format(it) })
        }
        val KEY_SELECTED_THEME = stringPreferencesKey("selected_theme")
        val KEY_APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
        val KEY_USE_CUSTOM_ACCENT = booleanPreferencesKey("use_custom_accent")
        val KEY_SHOW_CHANNEL_LOGOS = booleanPreferencesKey("ui_show_channel_logos")
        /** Whole percents, first build of Logo Size only; migrated on read. */
        val KEY_CHANNEL_LOGO_SIZE_LEGACY = intPreferencesKey("ui_channel_logo_size")
        val KEY_CHANNEL_LOGO_SIZE_TENTHS = intPreferencesKey("ui_channel_logo_size_tenths")
        val KEY_SHOW_CHANNEL_NUMBERS = booleanPreferencesKey("ui_show_channel_numbers")
        val KEY_SHOW_CHANNEL_NAMES = booleanPreferencesKey("ui_show_channel_names")
        val KEY_SHOW_PROGRAM_SUBTITLES = booleanPreferencesKey("ui_show_program_subtitles")
        val KEY_ROUNDED_ARTWORK = booleanPreferencesKey("ui_rounded_artwork")
        val KEY_ROUNDED_ARTWORK_GUIDE = booleanPreferencesKey("ui_rounded_artwork_guide")
        val KEY_ROUNDED_PROGRAM_CELLS = booleanPreferencesKey("guide_rounded_program_cells")
        val KEY_APPEARANCE_TRUE_BLACK = booleanPreferencesKey("appearance_true_black")
        val KEY_TIME_FORMAT = stringPreferencesKey("ui_time_format")
        val KEY_HIDDEN_EPG_BADGES = stringPreferencesKey("ui_hidden_epg_badges")
        val KEY_SHOW_EPG_BADGES_TV = booleanPreferencesKey("ui_show_epg_badges_tv")
        val KEY_SHOW_EPG_BADGES_MOBILE = booleanPreferencesKey("ui_show_epg_badges_mobile")
        val KEY_VIDEO_SCALE_MODE = stringPreferencesKey("video_scale_mode")
        val KEY_CUSTOM_ACCENT_HEX = stringPreferencesKey("custom_accent_hex")
        val KEY_DEFAULT_LIVE_TV_VIEW = stringPreferencesKey("default_live_tv_view")
        val KEY_LIVE_TV_LAYOUT = stringPreferencesKey("live_tv_layout")
        val KEY_SKIP_LOADING_SCREEN = booleanPreferencesKey("app_behaviors_skip_loading_screen")
        val KEY_AUTO_ROTATE = booleanPreferencesKey("app_behaviors_auto_rotate")
        val KEY_DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val KEY_CAST_FORCE_HEVC_TRANSCODE = booleanPreferencesKey("cast_force_hevc_transcode")
        val KEY_DEVELOPER_FORCE_HLS = booleanPreferencesKey("developer_force_hls")
        val KEY_SERVER_HLS_HOSTS = androidx.datastore.preferences.core.stringSetPreferencesKey("server_hls_hosts")
        val KEY_CAST_TRANSCODE_DOWN_PROFILE = stringPreferencesKey("cast_transcode_down_profile")
        val KEY_APPLE_TV_CHANNEL_FLIP = booleanPreferencesKey("app_behaviors_apple_tv_channel_flip")
        val KEY_PLAYER_BRIGHTNESS_GESTURE = booleanPreferencesKey("in_player_gesture_brightness")
        val KEY_PLAYER_VOLUME_GESTURE = booleanPreferencesKey("in_player_gesture_volume")
        val KEY_PLAYER_BRIGHTNESS_EDGE = stringPreferencesKey("in_player_gesture_brightness_edge")
        val KEY_REMOTE_CONTROL_MAP = stringPreferencesKey("remote_control_map")
        val KEY_REMOTE_LEARNED_BUTTONS = stringPreferencesKey("remote_learned_buttons")
        val KEY_SYNC_REMOTE_CONTROL_MAP = booleanPreferencesKey("sync_remote_control_map")
        val KEY_RADIO_MODE_BY_DEFAULT = booleanPreferencesKey("radio_mode_by_default")
        val KEY_SYNC_RADIO_MODE_BY_DEFAULT = booleanPreferencesKey("sync_radio_mode_by_default")
        val KEY_GUIDE_GROUP_SELECTOR = stringPreferencesKey("guide_group_selector")
        val KEY_GUIDE_SIDEBAR_LAYOUT = stringPreferencesKey("guide_sidebar_layout")
        val KEY_PHONE_GROUP_SELECTOR = stringPreferencesKey("phone_group_selector")
        val KEY_LIVE_TV_EDGE_SWIPE_OPENS_SIDEBAR = booleanPreferencesKey("live_tv_edge_swipe_opens_sidebar")
        val KEY_GUIDE_TUNE_IN_MINI = booleanPreferencesKey("guide_tune_in_mini")
        val KEY_GUIDE_PROGRAM_INFO_BUTTON = stringPreferencesKey("guideProgramInfoButton")
        val KEY_PROGRAM_INFO_BUTTON_GUIDE = booleanPreferencesKey("programInfoButtonGuide")
        val KEY_PROGRAM_INFO_BUTTON_LIST = booleanPreferencesKey("programInfoButtonList")
        val KEY_PLAYER_SHOW_CHANNEL_INFO_CARD = booleanPreferencesKey("playerShowChannelInfoCard")
        val KEY_SHOW_REMOTE_HINTS = booleanPreferencesKey("show_remote_hints")
        val KEY_EXIT_DIALOG_FOCUS_EXIT = booleanPreferencesKey("exit_dialog_focus_exit")
        val KEY_STARTUP_REFRESH_RATE = stringPreferencesKey("startup_refresh_rate")
        val KEY_VOD_LIBRARY_REFRESH_HOURS = intPreferencesKey("vod_library_refresh_hours")
        val KEY_MOVIES_SORT_ORDER = stringPreferencesKey("movies_sort_order")
        val KEY_SERIES_SORT_ORDER = stringPreferencesKey("series_sort_order")
        val KEY_DVR_SORT_ORDER = stringPreferencesKey("dvr_sort_order")
        val KEY_MATCH_CONTENT_RESOLUTION = booleanPreferencesKey("match_content_resolution")
        val KEY_MATCH_FRAME_RATE = booleanPreferencesKey("match_frame_rate")
        val KEY_AUTO_RECOVER_FROZEN_STREAMS =
            booleanPreferencesKey("app_behaviors_auto_recover_frozen_streams")
        // Player Info Card element toggles (App Behaviors); device-local.
        val KEY_PLAYER_CARD_CHANNEL_LOGO =
            booleanPreferencesKey("player_card_show_channel_logo")
        val KEY_PLAYER_CARD_CHANNEL_NAME =
            booleanPreferencesKey("player_card_show_channel_name")
        val KEY_PLAYER_CARD_PROGRAM_NAME =
            booleanPreferencesKey("player_card_show_program_name")
        val KEY_PLAYER_CARD_PROGRAM_TIME =
            booleanPreferencesKey("player_card_show_program_time")
        val KEY_PLAYER_CARD_PROGRAM_SUBTITLE =
            booleanPreferencesKey("player_card_show_program_subtitle")
        val KEY_PLAYER_CARD_PROGRAM_DESCRIPTION =
            booleanPreferencesKey("player_card_show_program_description")
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
        /** DVR Filter (2026-09-10): channel names hidden from the recordings library. */
        val KEY_HIDDEN_DVR_CHANNELS = stringPreferencesKey("hidden_dvr_channels")
        val KEY_HIDDEN_SERIES_GROUPS = stringPreferencesKey("hidden_series_groups")
        /** Hidden category checked, per playlist scope (Logan 2026-09-14). */
        val KEY_HIDDEN_CATEGORY_SHOWN_MOVIES = stringPreferencesKey("hidden_category_shown_movies")
        val KEY_HIDDEN_CATEGORY_SHOWN_SERIES = stringPreferencesKey("hidden_category_shown_series")
        /** Scope token for a playlist whose URL carries no host. */
        const val HIDDEN_CATEGORY_ANY_PLAYLIST = "*"
        val KEY_DISPLAY_SCALE_MOVIES = doublePreferencesKey("display_scale_movies")
        val KEY_DISPLAY_SCALE_LIVE_TV = doublePreferencesKey("display_scale_live_tv")
        val KEY_GUIDE_SCALE = doublePreferencesKey("guide_scale")
        val KEY_TEXT_SCALE = doublePreferencesKey("text_scale")
        val KEY_SUBTEXT_SCALE = doublePreferencesKey("subtext_scale")
        val KEY_TEXT_CONTRAST = doublePreferencesKey("text_contrast")
        val KEY_DEFAULT_TAB = stringPreferencesKey("default_tab")
        val KEY_NETWORK_TIMEOUT = doublePreferencesKey("network_timeout_secs")
        val KEY_MAX_RETRIES = intPreferencesKey("max_retries")
        val KEY_STREAM_BUFFER_SIZE = stringPreferencesKey("stream_buffer_size")
        val KEY_EPG_WINDOW_HOURS = intPreferencesKey("epg_window_hours")
        val KEY_EPG_CACHE_EPOCH = intPreferencesKey("epg_cache_epoch")
        val KEY_MULTIVIEW_AUDIO_FOCUS_STYLE = stringPreferencesKey("multiview_audio_focus_style")
        val KEY_MULTIVIEW_TILE_PADDING = booleanPreferencesKey("multiview_tile_padding")
        val KEY_MULTIVIEW_TILE_CORNERS_ROUNDED = booleanPreferencesKey("multiview_tile_corners_rounded")
        val KEY_MULTIVIEW_LAYOUT_MODE = stringPreferencesKey("multiview_layout_mode")
        val KEY_MULTIVIEW_SHOW_LOGOS = booleanPreferencesKey("multiview_show_logos")
        val KEY_MULTIVIEW_LOGO_POSITION = stringPreferencesKey("multiview_logo_position")
        val KEY_MULTIVIEW_LOGO_SIZE = intPreferencesKey("multiview_logo_size")
        val KEY_MULTIVIEW_PERF_WARNING_SUPPRESSED =
            booleanPreferencesKey("multiview_perf_warning_suppressed")
        val KEY_MULTIVIEW_PICKER_SHOW_SUBTITLES = booleanPreferencesKey("multiview_picker_show_subtitles")
        val KEY_MULTIVIEW_PICKER_SHOW_DESCRIPTIONS = booleanPreferencesKey("multiview_picker_show_descriptions")
        val KEY_CHANNEL_COLLECTIONS = stringPreferencesKey("channel_collections")
        /** Last pulled Drive preferences lanes, carried forward for values this device does not write. Device-local. */
        val KEY_DRIVE_PREFS_LANES = stringPreferencesKey("drive_prefs_lanes_cache")
        /** Profile ids already seeded with the per-profile settings (2026-10-27 migration). */
        val KEY_PROFILE_SETTINGS_SEEDED = androidx.datastore.preferences.core.stringSetPreferencesKey("profile_settings_seeded_v2")
        val KEY_DVR_MAX_LOCAL_STORAGE_MB = intPreferencesKey("dvr_max_local_storage_mb")
        val KEY_DVR_DEFAULT_PRE_ROLL = intPreferencesKey("dvr_default_pre_roll_mins")
        val KEY_DVR_DEFAULT_POST_ROLL = intPreferencesKey("dvr_default_post_roll_mins")
        val KEY_DVR_DEFAULT_DESTINATION = stringPreferencesKey("dvr_default_destination")
        val KEY_LIVE_REWIND_ENABLED = booleanPreferencesKey("live_rewind_enabled")
        val KEY_LIVE_REWIND_DEPTH_MIN = intPreferencesKey("live_rewind_depth_minutes")
        val KEY_LIVE_REWIND_PROMPT_SEEN = booleanPreferencesKey("live_rewind_prompt_seen")
        val KEY_LIVE_REWIND_RETENTION_HOURS = intPreferencesKey("live_rewind_retention_hours")
        val KEY_LIVE_REWIND_KEEP_RECENT = booleanPreferencesKey("live_rewind_keep_recent")
        val KEY_LIVE_REWIND_KEEP_COUNT = intPreferencesKey("live_rewind_keep_count")
        val KEY_SKIP_BACK_SECONDS = intPreferencesKey("skip_back_seconds")
        val KEY_SKIP_FORWARD_SECONDS = intPreferencesKey("skip_forward_seconds")
        val KEY_SKIP_WITHOUT_CONTROLS = booleanPreferencesKey("skip_without_controls")
        val KEY_LIVE_REWIND_BUDGET_GB = intPreferencesKey("live_rewind_budget_gb")
        val KEY_DVR_CUSTOM_FOLDER_URI = stringPreferencesKey("dvr_custom_folder_uri")
        val KEY_DVR_KEEP_AWAKE = booleanPreferencesKey("dvr_keep_awake_during_recording")
        // JSON object {recordingId: category}. Device-local cache so completed
        // recordings keep their genre pill after the programme leaves the EPG
        // window; deliberately NOT in snapshotSyncablePreferences.
        val KEY_DVR_RECORDING_CATEGORIES = stringPreferencesKey("dvr_recording_categories")
        val KEY_DVR_TAB_HINT = stringPreferencesKey("dvr_tab_hint_playlists")
        // JSON object {channelId: startBufferMs}: learned live start hold-back,
        // device-local (see liveStartBufferMs).
        val KEY_LIVE_START_BUFFER_MS = stringPreferencesKey("live_start_buffer_ms")
        // JSON object {channelId: epochMillis}: when that channel's hold-back was
        // last learned or re-confirmed. Missing entry = expired (old prefs).
        val KEY_LIVE_START_BUFFER_AT_MS = stringPreferencesKey("live_start_buffer_at_ms")
        /** Bound on the learned start-buffer map; past it the map is cleared. */
        const val LIVE_START_BUFFER_MAX_ENTRIES = 200
        val KEY_CATEGORY_MASTER_ENABLE = booleanPreferencesKey(CategoryPaletteState.MASTER_ENABLED_KEY)
        val KEY_CATEGORY_CUSTOM_JSON = stringPreferencesKey(CategoryPaletteState.CUSTOM_KEY)
        val KEY_SYNC_MASTER = booleanPreferencesKey("sync_master_enabled")
        val KEY_SYNC_PROVIDER = stringPreferencesKey("sync_provider")
        val KEY_SYNC_ACCOUNT_EMAIL = stringPreferencesKey("sync_account_email")
        val KEY_SYNC_LAST_PUSH = longPreferencesKey("sync_last_push_at")
        val KEY_SYNC_LAST_PULL = longPreferencesKey("sync_last_pull_at")
        val KEY_SYNC_ACCESS_TOKEN = stringPreferencesKey("sync_access_token")
        val KEY_SYNC_TOKEN_EXPIRY = longPreferencesKey("sync_token_expiry")
        val KEY_CREDS_ENCRYPTED_V1 = booleanPreferencesKey("creds_encrypted_v1")
        val KEY_CREDENTIALS_SYNC_DISCLOSED = booleanPreferencesKey("credentials_sync_disclosed")
        val KEY_BG_REFRESH_ENABLED = booleanPreferencesKey("background_refresh_enabled")
        val KEY_BG_REFRESH_INTERVAL_MINS = intPreferencesKey("background_refresh_interval_mins")
        val KEY_BG_REFRESH_TYPE = stringPreferencesKey("background_refresh_type")
        val KEY_BG_REFRESH_HOUR = intPreferencesKey("background_refresh_hour")
        val KEY_BG_REFRESH_MINUTE = intPreferencesKey("background_refresh_minute")
        /** Settings that are profile scoped (".s()") and carried by Dispatcharr Sync and Drive. */
        val PROFILE_SETTING_KEYS: List<Preferences.Key<*>> by lazy { listOf(
            KEY_SELECTED_THEME,
            KEY_APPEARANCE_MODE,
            KEY_APPEARANCE_TRUE_BLACK,
            KEY_USE_CUSTOM_ACCENT,
            KEY_CUSTOM_ACCENT_HEX,
            KEY_TEXT_SCALE,
            KEY_SUBTEXT_SCALE,
            KEY_TEXT_CONTRAST,
            KEY_TIME_FORMAT,
            KEY_DEFAULT_TAB,
            KEY_SKIP_LOADING_SCREEN,
            KEY_AUTO_RESUME_LAST_CHANNEL,
            KEY_DEFAULT_LIVE_TV_VIEW,
            KEY_LIVE_TV_LAYOUT,
            KEY_GUIDE_GROUP_SELECTOR,
            KEY_PHONE_GROUP_SELECTOR,
            KEY_GUIDE_SIDEBAR_LAYOUT,
            KEY_LIVE_TV_EDGE_SWIPE_OPENS_SIDEBAR,
            KEY_SHOW_CHANNEL_LOGOS,
            KEY_CHANNEL_LOGO_SIZE_TENTHS,
            KEY_SHOW_CHANNEL_NUMBERS,
            KEY_SHOW_CHANNEL_NAMES,
            KEY_SHOW_PROGRAM_SUBTITLES,
            KEY_SHOW_EPG_BADGES_TV,
            KEY_SHOW_EPG_BADGES_MOBILE,
            KEY_HIDDEN_EPG_BADGES,
            KEY_GUIDE_PROGRAM_INFO_BUTTON,
            KEY_PROGRAM_INFO_BUTTON_GUIDE,
            KEY_PROGRAM_INFO_BUTTON_LIST,
            KEY_ROUNDED_ARTWORK,
            KEY_ROUNDED_ARTWORK_GUIDE,
            KEY_ROUNDED_PROGRAM_CELLS,
            KEY_DISPLAY_SCALE_LIVE_TV,
            KEY_GUIDE_SCALE,
            KEY_CATEGORY_MASTER_ENABLE,
            KEY_CATEGORY_CUSTOM_JSON,
            KEY_PLAYER_SHOW_CHANNEL_INFO_CARD,
            KEY_PLAYER_CARD_CHANNEL_LOGO,
            KEY_PLAYER_CARD_CHANNEL_NAME,
            KEY_PLAYER_CARD_PROGRAM_NAME,
            KEY_PLAYER_CARD_PROGRAM_TIME,
            KEY_PLAYER_CARD_PROGRAM_SUBTITLE,
            KEY_PLAYER_CARD_PROGRAM_DESCRIPTION,
            KEY_LIVE_REWIND_ENABLED,
            KEY_LIVE_REWIND_DEPTH_MIN,
            KEY_LIVE_REWIND_KEEP_RECENT,
            KEY_LIVE_REWIND_KEEP_COUNT,
            KEY_LIVE_REWIND_RETENTION_HOURS,
            KEY_LIVE_REWIND_BUDGET_GB,
            KEY_SKIP_BACK_SECONDS,
            KEY_SKIP_FORWARD_SECONDS,
            KEY_SKIP_WITHOUT_CONTROLS,
            KEY_AUTO_RECOVER_FROZEN_STREAMS,
            KEY_APPLE_TV_CHANNEL_FLIP,
            KEY_PLAYER_BRIGHTNESS_GESTURE,
            KEY_PLAYER_VOLUME_GESTURE,
            KEY_PLAYER_BRIGHTNESS_EDGE,
            KEY_MULTIVIEW_AUDIO_FOCUS_STYLE,
            KEY_MULTIVIEW_TILE_PADDING,
            KEY_MULTIVIEW_TILE_CORNERS_ROUNDED,
            KEY_MULTIVIEW_SHOW_LOGOS,
            KEY_MULTIVIEW_LOGO_POSITION,
            KEY_MULTIVIEW_LOGO_SIZE,
            KEY_MULTIVIEW_PICKER_SHOW_SUBTITLES,
            KEY_MULTIVIEW_PICKER_SHOW_DESCRIPTIONS,
            KEY_PROGRAM_POSTERS_TMDB_ENABLED,
            KEY_VOD_LIBRARY_REFRESH_HOURS,
            KEY_DISPLAY_SCALE_MOVIES,
            KEY_MOVIES_SORT_ORDER,
            KEY_SERIES_SORT_ORDER,
            KEY_DVR_DEFAULT_PRE_ROLL,
            KEY_DVR_DEFAULT_POST_ROLL,
            KEY_DVR_DEFAULT_DESTINATION,
            KEY_DVR_SORT_ORDER,
            KEY_SHOW_REMOTE_HINTS,
            KEY_GUIDE_TUNE_IN_MINI,
        ) }
    }
}

/** Clamp to [TEXT_SCALE_MIN]..[TEXT_SCALE_MAX] and snap to the nearest 5% stop. */
fun snapTextScale(value: Float): Float {
    val v = if (value.isNaN()) 1f else value.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
    return kotlin.math.round(v * 20f) / 20f
}

/** Clamp to 0..1 and snap to the nearest 10% stop (Text Contrast). */
fun snapTextContrast(value: Float): Float {
    val v = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
    return kotlin.math.round(v * 10f) / 10f
}

/** Brightness slides on the left edge of the player; volume on the right. */
const val PLAYER_EDGE_LEFT = "left"

/** Brightness slides on the right edge of the player; volume on the left. */
const val PLAYER_EDGE_RIGHT = "right"

/**
 * Settings > Live TV > Program Info Button values (Apple GuideProgramInfoButton,
 * same key and strings): the guide cell info button on phone and tablet.
 */
object GuideProgramInfoButton {
    const val OFF = "off"
    const val CURRENT = "current"
    const val ALL = "all"
    val VALUES = setOf(OFF, CURRENT, ALL)

    /** Whether a cell gets the button for [mode]; [isLive] = airing now. */
    fun shows(mode: String, isLive: Boolean): Boolean = when (mode) {
        CURRENT -> isLive
        ALL -> true
        else -> false
    }
}
