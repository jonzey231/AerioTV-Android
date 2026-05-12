package com.aeriotv.android.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.category.CustomCategoryEntry
import com.aeriotv.android.core.category.ProgramCategory
import com.aeriotv.android.ui.theme.AppTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "aerio_prefs")

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
     * iOS `appBehaviorsAutoResumeLastChannel` parity. Stub for now (Android
     * has no mini-player surface yet). Stored anyway so a future port can
     * flip it on without losing the user's prior choice.
     */
    val autoResumeLastChannel: Flow<Boolean> = store.data.map { it[KEY_AUTO_RESUME_LAST_CHANNEL] ?: false }
    suspend fun setAutoResumeLastChannel(value: Boolean) {
        store.edit { it[KEY_AUTO_RESUME_LAST_CHANNEL] = value }
    }

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

    /** iOS `multiviewTilePadding` parity. Adds gaps between tiles. */
    val multiviewTilePadding: Flow<Boolean> = store.data.map {
        it[KEY_MULTIVIEW_TILE_PADDING] ?: false
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

    // ── DVR ──────────────────────────────────────────────────────────────

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

    private companion object {
        val KEY_SELECTED_THEME = stringPreferencesKey("selected_theme")
        val KEY_DEFAULT_LIVE_TV_VIEW = stringPreferencesKey("default_live_tv_view")
        val KEY_SKIP_LOADING_SCREEN = booleanPreferencesKey("app_behaviors_skip_loading_screen")
        val KEY_APPLE_TV_CHANNEL_FLIP = booleanPreferencesKey("app_behaviors_apple_tv_channel_flip")
        val KEY_AUTO_RESUME_LAST_CHANNEL = booleanPreferencesKey("app_behaviors_auto_resume_last_channel")
        val KEY_DEFAULT_TAB = stringPreferencesKey("default_tab")
        val KEY_NETWORK_TIMEOUT = doublePreferencesKey("network_timeout_secs")
        val KEY_MAX_RETRIES = intPreferencesKey("max_retries")
        val KEY_STREAM_BUFFER_SIZE = stringPreferencesKey("stream_buffer_size")
        val KEY_MULTIVIEW_AUDIO_FOCUS_STYLE = stringPreferencesKey("multiview_audio_focus_style")
        val KEY_MULTIVIEW_TILE_PADDING = booleanPreferencesKey("multiview_tile_padding")
        val KEY_MULTIVIEW_TILE_CORNERS_ROUNDED = booleanPreferencesKey("multiview_tile_corners_rounded")
        val KEY_DVR_MAX_LOCAL_STORAGE_MB = intPreferencesKey("dvr_max_local_storage_mb")
        val KEY_DVR_DEFAULT_PRE_ROLL = intPreferencesKey("dvr_default_pre_roll_mins")
        val KEY_DVR_DEFAULT_POST_ROLL = intPreferencesKey("dvr_default_post_roll_mins")
        val KEY_CATEGORY_MASTER_ENABLE = booleanPreferencesKey(CategoryPaletteState.MASTER_ENABLED_KEY)
        val KEY_CATEGORY_CUSTOM_JSON = stringPreferencesKey(CategoryPaletteState.CUSTOM_KEY)
    }
}
