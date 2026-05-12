package com.aeriotv.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
}
