package com.aeriotv.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.category.CustomCategoryEntry
import com.aeriotv.android.core.category.ProgramCategory
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Settings sub-screens share this ViewModel for read/write access to the
 * DataStore-backed preferences. Keeps each sub-screen stateless and lets
 * AerioTVTheme observe `selectedTheme` from MainActivity at the same time.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
) : ViewModel() {

    // Appearance
    val selectedTheme: Flow<AppTheme> = prefs.selectedTheme
    fun setSelectedTheme(theme: AppTheme) {
        viewModelScope.launch { prefs.setSelectedTheme(theme) }
    }

    val displayScaleMovies: Flow<Float> = prefs.displayScaleMovies
    fun setDisplayScaleMovies(value: Float) {
        viewModelScope.launch { prefs.setDisplayScaleMovies(value) }
    }

    val useCustomAccent: Flow<Boolean> = prefs.useCustomAccent
    fun setUseCustomAccent(value: Boolean) {
        viewModelScope.launch { prefs.setUseCustomAccent(value) }
    }

    val showChannelLogos: Flow<Boolean> = prefs.showChannelLogos
    fun setShowChannelLogos(value: Boolean) {
        viewModelScope.launch { prefs.setShowChannelLogos(value) }
    }

    val playerAspectMode: Flow<String> = prefs.playerAspectMode
    fun setPlayerAspectMode(value: String) {
        viewModelScope.launch { prefs.setPlayerAspectMode(value) }
    }

    /** Cycle Fit -> Zoom -> Fill -> Fit (iOS Issue #26 aspect toggle). */
    fun cyclePlayerAspectMode(current: String) {
        val next = when (current) {
            "fit" -> "zoom"
            "zoom" -> "fill"
            else -> "fit"
        }
        setPlayerAspectMode(next)
    }

    val customAccentHex: Flow<String> = prefs.customAccentHex
    fun setCustomAccentHex(value: String) {
        viewModelScope.launch { prefs.setCustomAccentHex(value) }
    }

    val displayScaleLiveTV: Flow<Float> = prefs.displayScaleLiveTV
    fun setDisplayScaleLiveTV(value: Float) {
        viewModelScope.launch { prefs.setDisplayScaleLiveTV(value) }
    }

    /** EPG guide timeline zoom (iOS guideScale). Written by pinch + the discrete selector. */
    val guideScale: Flow<Float> = prefs.guideScale
    fun setGuideScale(value: Float) {
        viewModelScope.launch { prefs.setGuideScale(value) }
    }

    val hiddenGroups: Flow<Set<String>> = prefs.hiddenGroups
    fun setHiddenGroups(groups: Set<String>) {
        viewModelScope.launch { prefs.setHiddenGroups(groups) }
    }

    // VOD group filters (iOS MoviesView hiddenMovieGroups / TVShowsView
    // hiddenSeriesGroups). Surfaced via ManageGroupsSheet from the On Demand
    // tab; applied in MoviesSubScreen / SeriesSubScreen to filter the lists.
    val hiddenMovieGroups: Flow<Set<String>> = prefs.hiddenMovieGroups
    fun setHiddenMovieGroups(groups: Set<String>) {
        viewModelScope.launch { prefs.setHiddenMovieGroups(groups) }
    }
    val hiddenSeriesGroups: Flow<Set<String>> = prefs.hiddenSeriesGroups
    fun setHiddenSeriesGroups(groups: Set<String>) {
        viewModelScope.launch { prefs.setHiddenSeriesGroups(groups) }
    }

    val homeSsids: Flow<Set<String>> = prefs.homeSsids
    fun setHomeSsids(ssids: Set<String>) {
        viewModelScope.launch { prefs.setHomeSsids(ssids) }
    }

    // App Behaviors
    val skipLoadingScreen: Flow<Boolean> = prefs.skipLoadingScreen
    fun setSkipLoadingScreen(value: Boolean) {
        viewModelScope.launch { prefs.setSkipLoadingScreen(value) }
    }

    val appleTVChannelFlip: Flow<Boolean> = prefs.appleTVChannelFlip
    fun setAppleTVChannelFlip(value: Boolean) {
        viewModelScope.launch { prefs.setAppleTVChannelFlip(value) }
    }

    // Developer
    val debugLoggingEnabled: Flow<Boolean> = prefs.debugLoggingEnabled
    fun setDebugLoggingEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setDebugLoggingEnabled(value) }
    }

    val autoResumeLastChannel: Flow<Boolean> = prefs.autoResumeLastChannel
    fun setAutoResumeLastChannel(value: Boolean) {
        viewModelScope.launch { prefs.setAutoResumeLastChannel(value) }
    }

    val lastWatchedChannelId: Flow<String> = prefs.lastWatchedChannelId
    fun setLastWatchedChannelId(value: String) {
        viewModelScope.launch { prefs.setLastWatchedChannelId(value) }
    }

    /** LRU recent channel ids (most-recent first). Powers the AddToMultiview "Recent" section. */
    val recentChannelIds: Flow<List<String>> = prefs.recentChannelIds
    fun recordRecentChannel(channelId: String) {
        viewModelScope.launch { prefs.recordRecentChannel(channelId) }
    }

    val defaultTab: Flow<String> = prefs.defaultTab
    fun setDefaultTab(value: String) {
        viewModelScope.launch { prefs.setDefaultTab(value) }
    }

    // Live TV view-mode persistence (Phase 5 hand-off — migrated from
    // rememberSaveable in LiveTVViewMode.kt to DataStore in Phase 8b).
    val defaultLiveTVView: Flow<String> = prefs.defaultLiveTVView
    fun setDefaultLiveTVView(value: String) {
        viewModelScope.launch { prefs.setDefaultLiveTVView(value) }
    }

    // Network (Phase 8c)
    val networkTimeoutSecs: Flow<Double> = prefs.networkTimeoutSecs
    fun setNetworkTimeoutSecs(value: Double) {
        viewModelScope.launch { prefs.setNetworkTimeoutSecs(value) }
    }

    val maxRetries: Flow<Int> = prefs.maxRetries
    fun setMaxRetries(value: Int) {
        viewModelScope.launch { prefs.setMaxRetries(value) }
    }

    val streamBufferSize: Flow<String> = prefs.streamBufferSize
    fun setStreamBufferSize(value: String) {
        viewModelScope.launch { prefs.setStreamBufferSize(value) }
    }

    val epgWindowHours: Flow<Int> = prefs.epgWindowHours
    fun setEpgWindowHours(value: Int) {
        viewModelScope.launch { prefs.setEpgWindowHours(value) }
    }

    // Audit task #48: master toggle for the periodic PlaylistRefreshWorker.
    // The Application collects this Flow and registers/cancels the unique
    // periodic work whenever the user flips it.
    val backgroundRefreshEnabled: Flow<Boolean> = prefs.backgroundRefreshEnabled
    fun setBackgroundRefreshEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setBackgroundRefreshEnabled(value) }
    }

    // Multiview (Phase 11c)
    val multiviewAudioFocusStyle: Flow<String> = prefs.multiviewAudioFocusStyle
    fun setMultiviewAudioFocusStyle(value: String) {
        viewModelScope.launch { prefs.setMultiviewAudioFocusStyle(value) }
    }

    val multiviewTilePadding: Flow<Boolean> = prefs.multiviewTilePadding
    fun setMultiviewTilePadding(value: Boolean) {
        viewModelScope.launch { prefs.setMultiviewTilePadding(value) }
    }

    val multiviewTileCornersRounded: Flow<Boolean> = prefs.multiviewTileCornersRounded
    fun setMultiviewTileCornersRounded(value: Boolean) {
        viewModelScope.launch { prefs.setMultiviewTileCornersRounded(value) }
    }

    // DVR (Phase 9b-3)
    val dvrMaxLocalStorageMB: Flow<Int> = prefs.dvrMaxLocalStorageMB
    fun setDvrMaxLocalStorageMB(value: Int) {
        viewModelScope.launch { prefs.setDvrMaxLocalStorageMB(value) }
    }

    val dvrDefaultPreRollMins: Flow<Int> = prefs.dvrDefaultPreRollMins
    fun setDvrDefaultPreRollMins(value: Int) {
        viewModelScope.launch { prefs.setDvrDefaultPreRollMins(value) }
    }

    val dvrDefaultPostRollMins: Flow<Int> = prefs.dvrDefaultPostRollMins
    fun setDvrDefaultPostRollMins(value: Int) {
        viewModelScope.launch { prefs.setDvrDefaultPostRollMins(value) }
    }

    val dvrCustomFolderUri: Flow<String> = prefs.dvrCustomFolderUri
    fun setDvrCustomFolderUri(value: String) {
        viewModelScope.launch { prefs.setDvrCustomFolderUri(value) }
    }

    val dvrKeepAwakeDuringRecording: Flow<Boolean> = prefs.dvrKeepAwakeDuringRecording
    fun setDvrKeepAwakeDuringRecording(value: Boolean) {
        viewModelScope.launch { prefs.setDvrKeepAwakeDuringRecording(value) }
    }

    // Category Palette (Phase 15)
    val categoryPalette: Flow<CategoryPaletteState> = prefs.categoryPalette
    fun setCategoryColorsEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setCategoryColorsEnabled(value) }
    }
    fun setCategoryBucketHex(bucket: ProgramCategory, hex: String?) {
        viewModelScope.launch { prefs.setCategoryBucketHex(bucket, hex) }
    }
    fun setCategoryBucketEnabled(bucket: ProgramCategory, enabled: Boolean) {
        viewModelScope.launch { prefs.setCategoryBucketEnabled(bucket, enabled) }
    }
    fun resetCategoryPalette() {
        viewModelScope.launch { prefs.resetCategoryPalette() }
    }
    fun setCustomCategories(list: List<CustomCategoryEntry>) {
        viewModelScope.launch { prefs.setCustomCategories(list) }
    }
}
