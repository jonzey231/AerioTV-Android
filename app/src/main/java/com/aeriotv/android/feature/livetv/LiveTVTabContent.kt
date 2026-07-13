package com.aeriotv.android.feature.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 * The launch view is the persisted "Default Live TV View" choice (Settings ->
 * App Behaviors): an explicit List/Guide wins, "Automatic" (empty) falls back to
 * the form-factor default. The in-screen List/Guide button is a SESSION-only
 * override that does NOT persist, so it can't clobber the Drive-synced default
 * across devices.
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
    onPlayCatchup: (String, String, String, Long, Long, String, String) -> Unit = { _, _, _, _, _, _, _ -> },
    // REQUIRED, no hiltViewModel() default (2026-07-12 field report): this
    // composable lives under the MAIN nav entry, so a bare default resolved
    // against MAIN's store and minted a SECOND PlaylistViewModel besides the
    // PLAYLIST_GRAPH-scoped one. Its init re-ran the whole channel+EPG
    // bootstrap on every cold launch (doubled Room reads, two in-memory EPG
    // maps, a GC storm + first-minute jank on the Streamer) and the guide ran
    // on a different state timeline than the scaffold. Callers must thread
    // the graph-scoped instance down.
    viewModel: PlaylistViewModel,
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val formFactor = rememberLiveTvFormFactor()
    val stored by settingsVm.defaultLiveTVView.collectAsStateWithLifecycle(initialValue = "")
    val scale by settingsVm.displayScaleLiveTV.collectAsStateWithLifecycle(initialValue = 1.0f)

    // Resolved DEFAULT view. An explicit Settings choice (Settings -> App
    // Behaviors -> Default Live TV View) wins; "Automatic" (blank) falls back to
    // the form-factor default (compact phone -> List, tablet / TV -> Guide).
    val resolvedDefault = when (stored.lowercase()) {
        "list" -> LiveTVViewMode.List
        "guide" -> LiveTVViewMode.Guide
        else -> formFactor.defaultMode
    }
    // The List / Guide switch is offered on every form factor (parity with tvOS,
    // which puts a List / Guide button at the left of the Live TV control row).
    // It is a SESSION-only override: it flips the current view WITHOUT persisting,
    // so casual toggling never rewrites the Drive-synced default (a List toggle on
    // a phone used to clobber the Guide default on a TV). The persisted default
    // lives solely in the Settings choice. Re-seeds whenever the resolved default
    // changes -- a Settings edit, or the pref syncing in after cold start.
    var mode by rememberSaveable(resolvedDefault) { mutableStateOf(resolvedDefault) }
    val canToggle = formFactor.supportsToggle
    val toggleMode: () -> Unit = {
        mode = if (mode == LiveTVViewMode.List) LiveTVViewMode.Guide else LiveTVViewMode.List
    }

    // EPG-search / aeriotv://guide deep-link jump: force the SESSION view to Guide
    // so the target programme's cell exists for GuideScreen to scroll/focus (it
    // collects the same flow). This is the session-scoped replacement for the old
    // setDefaultLiveTVView("guide") in MainScaffold, which persisted + synced.
    // Deduped by request key -- guideJumpRequests is replay=1, so without this the
    // last jump would re-force Guide on every Live TV re-entry and stomp a session
    // List toggle. rememberSaveable survives tab switches but resets on cold start,
    // so a cold-start deep link still forces Guide as intended.
    var consumedJumpKey by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        viewModel.guideJumpRequests.collect { jump ->
            val key = "${jump.channelId}@${jump.startMillis}"
            if (key != consumedJumpKey) {
                consumedJumpKey = key
                mode = LiveTVViewMode.Guide
            }
        }
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
