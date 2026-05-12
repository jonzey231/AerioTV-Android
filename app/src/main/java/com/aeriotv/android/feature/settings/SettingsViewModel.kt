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

    val customAccentHex: Flow<String> = prefs.customAccentHex
    fun setCustomAccentHex(value: String) {
        viewModelScope.launch { prefs.setCustomAccentHex(value) }
    }

    val displayScaleLiveTV: Flow<Float> = prefs.displayScaleLiveTV
    fun setDisplayScaleLiveTV(value: Float) {
        viewModelScope.launch { prefs.setDisplayScaleLiveTV(value) }
    }

    val hiddenGroups: Flow<Set<String>> = prefs.hiddenGroups
    fun setHiddenGroups(groups: Set<String>) {
        viewModelScope.launch { prefs.setHiddenGroups(groups) }
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

    val autoResumeLastChannel: Flow<Boolean> = prefs.autoResumeLastChannel
    fun setAutoResumeLastChannel(value: Boolean) {
        viewModelScope.launch { prefs.setAutoResumeLastChannel(value) }
    }

    val lastWatchedChannelId: Flow<String> = prefs.lastWatchedChannelId
    fun setLastWatchedChannelId(value: String) {
        viewModelScope.launch { prefs.setLastWatchedChannelId(value) }
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
