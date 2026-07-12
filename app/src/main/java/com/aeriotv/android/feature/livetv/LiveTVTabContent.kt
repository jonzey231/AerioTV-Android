package com.aeriotv.android.feature.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.feature.channels.ChannelListScreen
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.ui.scale.WithDisplayScale

/**
 * Entry point for the Live TV tab. Picks the appropriate sub-screen
 * (List vs Guide) based on form factor + the user's runtime toggle.
 * Mirrors iOS ChannelListView dispatcher logic (`ChannelListView.swift:348`).
 *
 * Phase 8b: view-mode preference moved from `rememberSaveable` to DataStore
 * via [SettingsViewModel] so the user's List/Guide choice survives cold start.
 * An empty stored value falls back to the form-factor default.
 */
@Composable
fun LiveTVTabContent(
    onChannelClick: (M3UChannel) -> Unit,
    modifier: Modifier = Modifier,
    onLaunchMultiview: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    /** Catch-up (task #133/#136): play a resolved timeshift URL in the
     *  recording player, carrying programme window + panel timezone for
     *  scrub-seek URL rebuilds. Guide-only (the List view surfaces no past
     *  programmes). */
    onPlayCatchup: (String, String, Long, Long, String, String) -> Unit = { _, _, _, _, _, _ -> },
    viewModel: PlaylistViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val formFactor = rememberLiveTvFormFactor()
    val stored by settingsVm.defaultLiveTVView.collectAsStateWithLifecycle(initialValue = "")
    val scale by settingsVm.displayScaleLiveTV.collectAsStateWithLifecycle(initialValue = 1.0f)

    // The List / Guide switch is offered on every form factor, including Android
    // TV (parity with tvOS, which puts a List / Guide button at the left of the
    // Live TV control row). TV defaults to Guide but honors a saved "list" choice
    // so the preference survives cold start, same as phone / tablet.
    val mode = when (stored.lowercase()) {
        "list" -> LiveTVViewMode.List
        "guide" -> LiveTVViewMode.Guide
        else -> if (formFactor.isTv) LiveTVViewMode.Guide else formFactor.defaultMode
    }
    val canToggle = formFactor.supportsToggle
    val toggleMode: () -> Unit = {
        val next = if (mode == LiveTVViewMode.List) LiveTVViewMode.Guide else LiveTVViewMode.List
        settingsVm.setDefaultLiveTVView(next.storageKey())
    }

    // iOS canon scopes the "Live TV List" Display Scale slider to List mode
    // only (the Guide grid is a strict-pitch layout that the user shouldn't
    // be free-scaling). Match that scoping rule here.
    when (mode) {
        LiveTVViewMode.List -> WithDisplayScale(scale = scale) {
            ChannelListScreen(
                onChannelClick = onChannelClick,
                viewModel = viewModel,
                modifierWrap = modifier,
                viewMode = mode,
                canToggleViewMode = canToggle,
                onToggleViewMode = toggleMode,
                onOpenSearch = onOpenSearch,
                onPlayCatchup = onPlayCatchup,
            )
        }
        LiveTVViewMode.Guide -> GuideScreen(
            onChannelClick = onChannelClick,
            viewModel = viewModel,
            modifier = modifier,
            viewMode = mode,
            canToggleViewMode = canToggle,
            onToggleViewMode = toggleMode,
            onLaunchMultiview = onLaunchMultiview,
            onOpenSearch = onOpenSearch,
            onPlayCatchup = onPlayCatchup,
        )
    }
}

private fun LiveTVViewMode.storageKey(): String = when (this) {
    LiveTVViewMode.List -> "list"
    LiveTVViewMode.Guide -> "guide"
}
