package com.aeriotv.android.feature.main

import com.aeriotv.android.core.data.db.entity.dispatcharrCanViewDvr
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberSmartRecord
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.playback.AerioExoPlayerHolder
import com.aeriotv.android.feature.dvr.DvrTabContent
import com.aeriotv.android.feature.dvr.DvrViewModel
import com.aeriotv.android.feature.favorites.FavoritesTabContent
import com.aeriotv.android.feature.favorites.FavoritesViewModel
import com.aeriotv.android.feature.livetv.LiveTVTabContent
import com.aeriotv.android.feature.livetv.rememberLiveTvFormFactor
import com.aeriotv.android.feature.miniplayer.MiniPlayerRow
import com.aeriotv.android.feature.miniplayer.MiniPlayerSession
import com.aeriotv.android.feature.miniplayer.MiniPlayerViewModel
import com.aeriotv.android.feature.onboarding.ChooseSourceTypeScreen
import com.aeriotv.android.feature.onboarding.ConfigureSourceScreen
import com.aeriotv.android.feature.ondemand.OnDemandTabContent
import com.aeriotv.android.feature.ondemand.OnDemandViewModel
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.playlist.nowPlaying
import com.aeriotv.android.feature.settings.AddMoreCategoriesScreen
import com.aeriotv.android.feature.settings.AddPlaylistWizardStep
import com.aeriotv.android.feature.settings.AppBehaviorsSettingsScreen
import com.aeriotv.android.feature.settings.AppearanceSettingsScreen
import com.aeriotv.android.feature.settings.DeveloperSettingsScreen
import com.aeriotv.android.feature.settings.DvrSettingsScreen
import com.aeriotv.android.feature.settings.MultiviewSettingsScreen
import com.aeriotv.android.feature.settings.NetworkSettingsScreen
import com.aeriotv.android.feature.settings.SettingsRootContent
import com.aeriotv.android.feature.settings.DefaultSettingsPaneSelection
import com.aeriotv.android.feature.settings.SettingsRoute
import com.aeriotv.android.feature.settings.SettingsScreen
import com.aeriotv.android.feature.settings.SettingsSection
import com.aeriotv.android.feature.settings.SettingsSubScreenPlaceholder
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset
import com.aeriotv.android.ui.adaptive.prefersTopTabBar
import com.aeriotv.android.ui.adaptive.topTabBarScale
import com.aeriotv.android.ui.adaptive.rememberViewport
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.feature.settings.SettingsTvRailHost
import com.aeriotv.android.feature.settings.SettingsTwoPaneHost
import com.aeriotv.android.feature.settings.rememberSettingsNavState
import com.aeriotv.android.feature.settings.rememberSettingsPaneSelection
import com.aeriotv.android.feature.settings.visibleSettingsSections
import com.aeriotv.android.ui.tv.tvFocusScale

/**
 * App-scoped [FocusRequester] for the Android TV top tab bar's "current"
 * focusable surface (the Row of pills, with [Modifier.focusRestorer] so a
 * re-entry restores the previously-focused pill).
 *
 * Section-level composables (GuideScreen first) read this and attach
 * `Modifier.focusProperties { up = it }` to their top-most focusable so
 * D-pad UP from the top row of the guide jumps focus back to the pills
 * instead of being trapped inside the `focusGroup()` (audit task #57).
 *
 * `null` on phone shell - the CompositionLocal is only filled on TV.
 */
val LocalTvTopNavFocusRequester = staticCompositionLocalOf<FocusRequester?> { null }

/** True while any pill in the Android TV top tab bar holds focus. Tab
 *  content reads it so a launch-focus grab never yanks focus out of the bar
 *  while the user is still walking it (Logan 2026-09-02: Live TV -> Favorites
 *  -> the Favorites guide stole focus mid-walk, every further Right went
 *  into the grid). */
val LocalTvTopNavHasFocus = staticCompositionLocalOf<androidx.compose.runtime.State<Boolean>> {
    androidx.compose.runtime.mutableStateOf(false)
}

/** Where D-pad DOWN from the tab bar should land inside the current tab.
 *  A tab sets it (On Demand: its current sub-tab pill) and clears it on
 *  dispose; null keeps Compose's geometric search. Consulted by the bar's
 *  Down exit, which is honoured where a group's onEnter was not. */
val LocalTvTabEntryFocus = staticCompositionLocalOf<androidx.compose.runtime.MutableState<FocusRequester?>> {
    androidx.compose.runtime.mutableStateOf(null)
}

/**
 * TV chrome-collapse channel. Content screens write `true` while the user is
 * scrolled down a long surface (the On Demand poster grids first) and the top
 * tab bar shrinks out of the way so an extra poster row fits on screen.
 *
 * The bar is never UNMOUNTED for this: its pills are the target of
 * FocusRequester.requestFocus() calls (the content's D-pad UP exit redirect),
 * which throws on a detached node, so an AnimatedVisibility-style hide would
 * crash the first UP press while collapsed. [collapsibleChrome] instead
 * shrinks the bar to a 1px, alpha-0 strip; the pills stay attached and
 * focusable, and focus arriving on the bar expands it back.
 *
 * `null` on the phone shell -- only the TV shell provides a state.
 */
val LocalTvChromeCollapsed =
    staticCompositionLocalOf<androidx.compose.runtime.MutableState<Boolean>?> { null }

/**
 * Top-level scaffold once a playlist is loaded. Mirrors iOS MainTabView with the
 * caveat that tabs are CONDITIONAL on content (see [visibleTabs]) - matching
 * iOS, the test-server screenshots show only 4 tabs not 5.
 */
@Composable
fun MainScaffold(
    onChannelClick: (M3UChannel) -> Unit,
    /** Companion "Controlling <TV>" card tap: open the remote-controls screen
     *  for this channel even when "tap stays on list" (GH #85) would otherwise
     *  re-tune in place; the card is the way BACK to the controls. */
    onOpenCompanionRemote: (M3UChannel) -> Unit = onChannelClick,
    onMovieClick: (String) -> Unit = {},
    onSeriesClick: (Int) -> Unit = {},
    onEpisodeResume: (String) -> Unit = {},
    onResumeMovie: (String) -> Unit = {},
    /** (playbackUrl, title, dispatcharrRecordingId or -1). */
    onPlayRecording: (String, String, Int) -> Unit = { _, _, _ -> },
    /** Catch-up (task #136): url, title, progStartMillis, progEndMillis, panelTz. */
    onPlayCatchup: (String, String, String, Long, Long, String, String) -> Unit = { _, _, _, _, _, _, _ -> },
    onLaunchMultiview: () -> Unit = {},
    onWatchLive: (String, String, Boolean, Long, Int?) -> Unit = { _, _, _, _, _ -> },
    onWatchFromBeginning: (String, String, Boolean, Long, Int?, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onOpenSearch: () -> Unit = {},
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Keep Recent Channels Live: app-bar indicator + dialog live HERE (not
    // in GuideScreen) so the TV circle is part of the nav bar's existing
    // focus row and works from every content tab.
    val retainedVm: com.aeriotv.android.feature.livetv.RetainedChannelsViewModel = hiltViewModel()
    val retainedList by retainedVm.retained.collectAsStateWithLifecycle()
    var showRetainedDialog by remember { mutableStateOf(false) }
    if (showRetainedDialog) {
        com.aeriotv.android.feature.livetv.RetainedChannelsDialog(
            viewModel = retainedVm,
            onJumpToChannel = { id ->
                state.channels.firstOrNull { it.id == id }?.let(onChannelClick)
            },
            onDismiss = { showRetainedDialog = false },
        )
    }
    val favoritesVm: FavoritesViewModel = hiltViewModel()
    // null until the DB emits: on the phone, backing out of the player
    // recomposes this scaffold from scratch and an empty INITIAL list read as
    // "no favorites" for a frame, the Favorites tab vanished, and the tabs
    // fallback below bounced a Favorites-first user to Live TV (GH #81
    // follow-up, PsykoRider 2026-09-02).
    val favoritesOrNull by favoritesVm.all.collectAsStateWithLifecycle()
    val favorites = favoritesOrNull ?: emptyList()
    // Show the Favorites tab only when the user has at least one favorite
    // that ALSO exists in the active playlist. The raw DB count would keep
    // the tab pinned to the bottom bar after a playlist switch left stale
    // orphan rows pointing at channel ids that no longer exist - the user
    // would see the tab, tap it, and find an empty "No Favorites" body
    // even though the DB count was non-zero.
    // GH #81: an EMPTY channel list is "still loading" (playlist refresh,
    // return from the player, sync apply), not "no favorites". Dropping the
    // flag there removed the Favorites tab for a frame, and the tabs
    // fallback below bounced the user to Live TV every time. Keep the last
    // verdict until channels are back; an empty favorites list is a real
    // DB emission and still retires the tab.
    var lastRenderableFavorites by rememberSaveable { mutableStateOf(false) }
    val hasRenderableFavorites = remember(favoritesOrNull, state.channels) {
        if (favoritesOrNull == null) return@remember lastRenderableFavorites
        if (favorites.isEmpty()) return@remember false
        if (state.channels.isEmpty()) return@remember lastRenderableFavorites
        val visibleIds = state.channels.asSequence().map { it.id }.toHashSet()
        favorites.any { it.channelId in visibleIds }.also { lastRenderableFavorites = it }
    }
    // Dynamic On Demand + DVR tabs (iOS MainTabView.hasVOD / hasRecordings
    // parity). Both ViewModels are hoisted here so the tabs can appear / vanish
    // based on actual content, NOT just the source type. hiltViewModel() resolves
    // to the SAME instance the tab body uses (shared ViewModelStoreOwner), so this
    // adds no duplicate fetch; it just makes the eager load drive tab visibility.
    val onDemandVm: OnDemandViewModel = hiltViewModel()
    // Collect only the bits the scaffold needs, de-duplicated: the catalog
    // walk emits a new state per page (~100 pages after launch) and collecting
    // the whole state here recomposed the entire scaffold, every tab included,
    // on each one (Streamer 2026-09-03: "every tab redraws" for the first
    // 30-45 s).
    val onDemandState by remember(onDemandVm) {
        onDemandVm.state
            .map { VodPresence.from(it) }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = VodPresence.from(onDemandVm.state.value))
    // Per-playlist VOD opt-in gate (iOS HomeView.swift:317 servers.filter
    // { $0.supportsVOD && $0.vodEnabled }). Read directly off the active
    // playlist so the tab hides immediately when the user toggles "Fetch On
    // Demand from this playlist" OFF at Add / Edit time, without waiting for
    // the next OnDemandViewModel refresh to flip unsupportedSource. If the
    // playlist hasn't loaded yet the default is true so we don't suppress
    // the tab on a slow cold launch.
    val activePlaylistVodEnabled = viewModel.state
        .collectAsStateWithLifecycle().value.playlist?.vodEnabled ?: true
    // hasVOD: any movie/series loaded, OR still loading its library. The loading
    // bridge keeps the tab from flickering "absent -> present" on cold launch /
    // source switch; a source that finishes with zero VOD hides the tab entirely.
    val hasVodContent = activePlaylistVodEnabled && !onDemandState.unsupportedSource && (
        onDemandState.hasMovies ||
            onDemandState.hasSeries ||
            onDemandState.isLoading ||
            onDemandState.isLoadingSeries ||
            // XC: the cheap probe found categories but deferred the heavy walk
            // until the tab is opened -- show the tab on the probe result alone.
            onDemandState.hasDeferredXtreamContent
        )
    val dvrVm: DvrViewModel = hiltViewModel()
    val dvrState by dvrVm.state.collectAsStateWithLifecycle()
    // hasRecordings: at least one recording (scheduled / recording / completed,
    // server or local) for the active source. Scheduling from the guide makes the
    // tab appear; deleting the last recording makes it disappear.
    // hasRecordingsHint: persisted last-session verdict so the tab shows from
    // the first frame after launch / playlist switch instead of popping in when
    // the server list arrives; retires as soon as the real list loads.
    // Dispatcharr 0.30 dvr_access "none": server recordings are not
    // listable; only a local recording in progress shows the tab.
    val dvrListable = state.playlist?.dispatcharrCanViewDvr() != false
    val hasRecordings = (dvrListable && (dvrState.recordings.isNotEmpty() || dvrState.hasRecordingsHint)) ||
        dvrState.isLocalRecordingActive
    // Sticky tabs (Streamer 2026-09-03, "every tab flashes"): the loaders
    // behind DVR and On Demand briefly report "nothing" while they transition
    // (On Demand dropped out for 0.8 s between its movie and series passes;
    // DVR flickers on its list refresh). When that happens while the user is
    // on that pill, the focused pill is removed, Compose drops focus to the
    // leftmost control, selection follows to Live TV and the tab flashes. A
    // tab that has been shown stays for the rest of the playlist session;
    // only a real user action retires it (favorites emptied, On Demand
    // switched off for the playlist, or an unsupported source).
    val stickyTabs = remember(state.playlist?.id) { mutableSetOf<AppTab>() }
    val tabs = run {
        val live = visibleTabs(
            hasFavorites = hasRenderableFavorites,
            hasVod = hasVodContent,
            hasRecordings = hasRecordings,
        )
        stickyTabs += live
        if (favoritesOrNull?.isEmpty() == true) stickyTabs -= AppTab.Favorites
        if (!activePlaylistVodEnabled || onDemandState.unsupportedSource) stickyTabs -= AppTab.OnDemand
        visibleTabs(
            hasFavorites = AppTab.Favorites in stickyTabs,
            hasVod = AppTab.OnDemand in stickyTabs,
            hasRecordings = AppTab.DVR in stickyTabs,
        )
    }
    val miniPlayerVm: MiniPlayerViewModel = hiltViewModel()
    val miniPlayerState by miniPlayerVm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val exoHolder = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            MainScaffoldEntryPoint::class.java,
        ).exoPlayerHolder()
    }
    // GH #33 re-entry: an app-wide "Now Casting" mini controller above the tab
    // bar so the user can re-open the cast remote after leaving the player.
    val castSender = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            MainScaffoldEntryPoint::class.java,
        ).castSender()
    }
    val castState by castSender.state.collectAsStateWithLifecycle()
    val castContent by castSender.content.collectAsStateWithLifecycle()
    val castIsPlaying by castSender.isPlaying.collectAsStateWithLifecycle()
    // GH #33 companion remote: same-pattern "Controlling <TV>" indicator card +
    // tap-to-reopen-the-remote, mirroring the Now-Casting card below.
    val companionRemote = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            MainScaffoldEntryPoint::class.java,
        ).companionRemote()
    }
    val companionConn by companionRemote.connection.collectAsStateWithLifecycle()
    val companionIsPlaying by companionRemote.isPlaying.collectAsStateWithLifecycle()
    val companionNowPlaying by companionRemote.nowPlaying.collectAsStateWithLifecycle()
    val companionChannelId by companionRemote.currentChannelId.collectAsStateWithLifecycle()
    // Enrich the companion's channel anchor with name/logo/current programme
    // for the media notification and the card; re-evaluated each minute so
    // the programme rolls over on the hour.
    LaunchedEffect(companionChannelId, state.channels, state.epgByChannel) {
        val id = companionChannelId
        if (id == null) { companionRemote.setNowPlayingDetails(null); return@LaunchedEffect }
        val ch = state.channels.firstOrNull { it.id == id }
        if (ch == null) { companionRemote.setNowPlayingDetails(null); return@LaunchedEffect }
        while (true) {
            val now = state.epgByChannel[ch.guideMatchKey]?.nowPlaying()
            companionRemote.setNowPlayingDetails(
                com.aeriotv.android.core.cast.companion.CompanionRemoteController.NowPlayingDetails(
                    channelId = id,
                    channelName = ch.name,
                    logoUrl = ch.tvgLogo.takeIf { it.isNotBlank() },
                    programmeTitle = now?.title,
                    programmeStartMs = now?.startMillis ?: 0L,
                    programmeEndMs = now?.endMillis ?: 0L,
                ),
            )
            kotlinx.coroutines.delay(60_000L)
        }
    }
    val companionDetails by companionRemote.details.collectAsStateWithLifecycle()
    // GH #33: browse for controllable AerioTV TVs at the SCAFFOLD level (phone
    // only; the TV is a host, not a client) so the floating "Control TV" pill
    // can appear without opening a channel first. Refcounted with the in-player
    // chooser's start/stop.
    val companionDiscovery = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            MainScaffoldEntryPoint::class.java,
        ).companionDiscovery()
    }
    if (!rememberLiveTvFormFactor().isTv) {
        DisposableEffect(Unit) {
            companionDiscovery.start()
            onDispose { companionDiscovery.stop() }
        }
    }
    val companionDevices by companionDiscovery.devices.collectAsStateWithLifecycle()
    var showCompanionPicker by remember { mutableStateOf(false) }
    // Poll pause state from the held ExoPlayer so the mini-player's
    // Pause/Play icon stays accurate when the notification action /
    // BT button toggles playback elsewhere.
    var miniPaused by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(miniPlayerState) {
        if (miniPlayerState !is com.aeriotv.android.feature.miniplayer.MiniPlayerSession.State.Active) return@LaunchedEffect
        while (true) {
            miniPaused = exoHolder.isPaused()
            kotlinx.coroutines.delay(500L)
        }
    }

    val settingsVm: SettingsViewModel = hiltViewModel()
    val defaultTabPref by settingsVm.defaultTab.collectAsStateWithLifecycle(initialValue = "")

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.LiveTV) }
    var initialTabApplied by rememberSaveable { mutableStateOf(false) }

    // Honour the saved defaultTab once its tab is actually available. On Demand /
    // DVR now materialise a beat after launch (their content loads async), so we
    // must NOT latch on the empty initial pref or while the target tab is still
    // missing - otherwise a "default = On Demand" preference would be dropped on
    // the floor before the tab appeared. We latch only when the target is applied;
    // a manual tab tap also latches (see onSelect / NavigationBarItem onClick) so
    // the default never overrides a deliberate user choice. Empty pref = Live TV
    // (already the initial selection), so there's nothing to apply.
    LaunchedEffect(defaultTabPref, tabs) {
        if (initialTabApplied || defaultTabPref.isEmpty()) return@LaunchedEffect
        val target = AppTab.entries.firstOrNull { it.name == defaultTabPref }
        when {
            target == null -> initialTabApplied = true
            target in tabs -> {
                selectedTab = target
                initialTabApplied = true
            }
            // else: target tab not present yet (content still loading); keep
            // waiting - this effect re-fires when `tabs` changes.
        }
    }

    // If the currently-selected tab disappears (e.g. user clears playlist, sourceType
    // changes), fall back to Live TV.
    LaunchedEffect(tabs) {
        // Search is never IN `tabs` (it's the floating bar button, not a
        // pill) but is a perfectly valid selection - don't bounce it.
        if (selectedTab !in tabs && selectedTab != AppTab.Search) {
            selectedTab = AppTab.LiveTV
        }
    }

    // EPG-search guide jump (iOS: MainTabView switches to .liveTV +
    // ChannelListView sets showGuideView=true). When a Search EPG result is
    // tapped (warm path) or an aeriotv://guide deep link is consumed (cold
    // path re-emits through requestGuideJump), select the Live TV tab.
    // LiveTVTabContent collects the same SharedFlow to force its SESSION view to
    // Guide (so the target cell exists to scroll/focus), and GuideScreen collects
    // it too (replay=1) to do the scroll + focus. This used to write the persisted
    // defaultLiveTVView("guide") here, which both failed to switch when the view
    // was already resolving to Guide and clobbered the Drive-synced default across
    // devices; forcing the session view instead avoids both.
    LaunchedEffect(Unit) {
        viewModel.guideJumpRequests.collect {
            selectedTab = AppTab.LiveTV
            initialTabApplied = true
        }
    }

    // Back from any secondary tab returns to the HOME tab instead of exiting
    // the app. Home is the user's Default Tab when it is set and present
    // (GH #81: a Favorites-first user expects Back to land on Favorites,
    // not Live TV), else Live TV. On the home tab this handler is disabled
    // so Back falls through to the default (mini-player / exit). The
    // Settings sub-screen BackHandler in SettingsTabContent is composed
    // DEEPER and is enabled only while a sub-screen is open, so it takes
    // priority there; this only fires on a tab root.
    val homeTab = AppTab.entries.firstOrNull { it.name == defaultTabPref }
        ?.takeIf { it in tabs && it != AppTab.Search } ?: AppTab.LiveTV
    // TV: the leaving tab's content nodes vanish, and Compose's fallback
    // hands focus to the LEFTMOST pill (Live TV) while the home tab is
    // selected (Logan 2026-09-02 screenshot). Ask for the home pill instead;
    // the bar treats a non-UP focus arrival as a fallback, so selection does
    // not follow it.
    // Focus the home pill BEFORE the tab switches (the pill already exists),
    // so the leaving content's nodes vanish with focus already parked there
    // and no fallback flashes the Live TV pill (Logan: "less than a quarter
    // of a second on Live TV before settling on Favorites").
    val focusPill = remember { mutableStateOf<((AppTab) -> Unit)?>(null) }
    androidx.activity.compose.BackHandler(enabled = selectedTab != homeTab) {
        focusPill.value?.invoke(homeTab)
        selectedTab = homeTab
        initialTabApplied = true
    }

    // iOS BackgroundWork activity pill (HomeView.swift). ORs the content-fetch
    // flags so the "Syncing" indicator shows while the channel list, EPG/guide,
    // or On Demand library is still loading -- an activity light, NOT a
    // cross-device-sync status. Vanishes the moment the flags clear.
    val syncLabels = remember(
        state.isLoading,
        state.isEpgLoading,
        onDemandState.isLoading,
        onDemandState.isLoadingSeries,
    ) {
        buildList {
            if (state.isLoading) add("Loading channels")
            if (state.isEpgLoading) add("Loading guide")
            if (onDemandState.isLoading) add("Loading Movies")
            if (onDemandState.isLoadingSeries) add("Loading Series")
        }
    }
    val anyBackgroundWork = syncLabels.isNotEmpty()

    // iOS Issue #24: when the app returns to the foreground, refresh the guide
    // if it has gone stale (>30min). Skip the first ON_START (cold launch
    // already loads the EPG) so a normal launch never double-fetches.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var sawFirstStart by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                if (sawFirstStart) viewModel.refreshEpgIfStale() else sawFirstStart = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Android TV / Google TV: a 10-foot top tab bar instead of the phone
    // bottom NavigationBar. D-pad friendly, overscan-safe, and mirrors the
    // tvOS TabView. Phone / tablet / fold keep the bottom nav below.
    val isTv = rememberLiveTvFormFactor().isTv
    if (isTv) {
        // tvOS layout parity (Archie 2026-05-28 reference shot): when the
        // mini-player is active, the top chrome (centered nav tabs +
        // sync pill on the left + group filter pills below) does NOT
        // shift down. The mini-player sits at the top-right in the empty
        // space alongside the centered nav tabs. They coexist because the
        // nav pill row is centered (Live TV / On Demand / Settings ~250dp
        // wide) and the mini is 210dp wide aligned to the right edge -- on
        // a 960dp-wide canvas there's ~250dp of empty space between them.
        // An earlier revision shoved everything down 184dp; that was wrong.
        // Audit #57: a single FocusRequester bound to the tab-bar Row. The
        // Row uses focusRestorer() so re-entry from a section restores the
        // pill the user last focused (typically the currently-selected one).
        // Section composables read it via LocalTvTopNavFocusRequester and
        // route D-pad UP from their topmost focusable here.
        val topNavRequester = remember { FocusRequester() }
        // Timestamp of the last D-pad UP press, read by TvTopTabBar to tell a
        // DELIBERATE bar entry (user pressed UP from the content; selection
        // should follow the focused pill immediately) from an involuntary
        // focus FALLBACK (a sub-screen transition removed the focused node and
        // Compose handed focus to the leftmost pill; selecting there would
        // yank the user to Live TV). Fallbacks are never preceded by UP.
        val lastUpKeyMs = remember { longArrayOf(0L) }
        // One FocusRequester per pill, shared between the bar (which binds
        // them) and the content's exit redirect below (which targets the
        // SELECTED pill directly, not the bar, so no entry heuristics apply).
        // + Search: not a pill, but the floating bar button needs a requester
        // for the same entry/exit focus redirects the pills use.
        val pillRequesters = remember(tabs) {
            (tabs + AppTab.Search).associateWith { FocusRequester() }
        }
        androidx.compose.runtime.SideEffect {
            focusPill.value = { tab -> runCatching { pillRequesters[tab]?.requestFocus() } }
        }
        // Chrome-collapse channel: long content surfaces (the On Demand grids)
        // set this true while scrolled down so the tab bar shrinks away. See
        // LocalTvChromeCollapsed for why the bar collapses instead of unmounting.
        val chromeCollapsed = remember { mutableStateOf(false) }
        val topNavHasFocusState: androidx.compose.runtime.MutableState<Boolean> = remember { mutableStateOf(false) }
        val tabEntryFocus: androidx.compose.runtime.MutableState<FocusRequester?> = remember { mutableStateOf(null) }
        CompositionLocalProvider(
            LocalTvTopNavFocusRequester provides topNavRequester,
            LocalTvTopNavHasFocus provides topNavHasFocusState,
            LocalTvTabEntryFocus provides tabEntryFocus,
            LocalTvChromeCollapsed provides chromeCollapsed,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
            // Left edge the nav bar actually occupies. Declared at BOX scope,
            // not inside the Column: the bar that reports it and the hint
            // overlay that consumes it are siblings of each other here.
            var navLeftEdgePx by remember { mutableIntStateOf(0) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                            lastUpKeyMs[0] = android.os.SystemClock.uptimeMillis()
                        }
                        false
                    },
            ) {
                // Collapse the bar only while the content reports a scrolled
                // state AND no pill holds focus: the UP-from-content redirect
                // (focusProperties onExit below) lands focus on the selected
                // pill even at 1px, which flips barHasFocus and grows the bar
                // back so the user can see what they're navigating.
                var barHasFocus by remember { mutableStateOf(false) }
                val barFraction by animateFloatAsState(
                    targetValue = if (chromeCollapsed.value && !barHasFocus) 0f else 1f,
                    animationSpec = tween(durationMillis = 250),
                    label = "tvTopBarCollapse",
                )
                Box(
                    modifier = Modifier
                        .onFocusChanged { barHasFocus = it.hasFocus; topNavHasFocusState.value = it.hasFocus }
                        .collapsibleChrome(barFraction),
                ) {
                    TvTopTabBar(
                        retainedCount = retainedList.size,
                        onRetainedClick = { showRetainedDialog = true },
                        onRefresh = { viewModel.refreshPlaylist() },
                        refreshing = anyBackgroundWork,
                        tabs = tabs,
                        selected = selectedTab,
                        onSelect = { selectedTab = it; initialTabApplied = true },
                        focusRequester = topNavRequester,
                        tabEntryFocus = tabEntryFocus,
                        lastUpKeyMs = lastUpKeyMs,
                        pillRequesters = pillRequesters,
                        onLeftEdgeChanged = { navLeftEdgePx = it },
                    )
                }
                // Reserve a band below the nav for the top-left gesture hints so
                // the group pills / guide grid sit clear of them:
                //  - Mini active: 90dp -- the right-aligned corner video (~148dp
                //    tall from y=12) needs it, and all THREE hints fit under it.
                //  - Idle Live TV: a small gap so the TWO-line hint stack has
                //    room between the nav bar and the group pills.
                //  - Other tabs / fullscreen (Pending): none (no hints shown).
                val miniActive = miniPlayerState is MiniPlayerSession.State.Active
                val topHintGap = when {
                    miniActive -> 78.dp
                    // Idle Live TV: the two 8sp hint chips float at the nav bar's
                    // height and only need a slim band under it. 40dp over-reserved
                    // and pushed the guide grid down enough to clip the 7th channel
                    // row (tvOS fits 7). 16dp still clears the idle hint stack while
                    // reclaiming a full row. During background work the hints are
                    // pushed DOWN to top=60dp to clear the Syncing pill, so reserve
                    // the larger band then to avoid the pills overlapping them
                    // (transient -- normal idle usage shows all 7 rows).
                    selectedTab == AppTab.LiveTV &&
                        miniPlayerState !is MiniPlayerSession.State.Pending ->
                        if (anyBackgroundWork) 40.dp else 16.dp
                    else -> 0.dp
                }
                if (topHintGap > 0.dp) {
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.height(topHintGap),
                    )
                }
                MainTabContent(
                    selectedTab = selectedTab,
                    tabs = tabs,
                    onChannelClick = onChannelClick,
                    onMovieClick = onMovieClick,
                    onSeriesClick = onSeriesClick,
                    onEpisodeResume = onEpisodeResume,
                    onResumeMovie = onResumeMovie,
                    onPlayRecording = onPlayRecording,
                    onPlayCatchup = onPlayCatchup,
                    onLaunchMultiview = onLaunchMultiview,
                    onWatchLive = onWatchLive,
                    onWatchFromBeginning = onWatchFromBeginning,
                    onOpenSearch = onOpenSearch,
                    onSelectTab = { selectedTab = it; initialTabApplied = true },
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // UP leaving the tab content must land on the SELECTED
                        // tab's pill. Geometric 2D search used to hit whichever
                        // pill sat above the focused column (On Demand over the
                        // centered Settings form) and selection-follows-focus
                        // switched tabs (user report). The bar's own onEnter
                        // does not intercept a direct child hit, so the
                        // redirect lives on the content group's exit instead.
                        .focusGroup()
                        .focusProperties {
                            onExit = {
                                if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Up) {
                                    pillRequesters[selectedTab]?.requestFocus()
                                }
                            }
                        },
                )
            }
            // iOS "Syncing" pill, top-left. The centered nav pills + right-edge
            // mini-player leave this corner clear. Non-focusable on TV (see
            // SyncActivityPill) so it never steals D-pad focus.
            SyncActivityPill(
                active = anyBackgroundWork,
                labels = syncLabels,
                isTv = true,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 24.dp, top = 18.dp),
            )
            // #10 tvOS Menu/Back gesture hints (HomeView guideMenuHint parity).
            // Rendered at the Home level -- NOT inside the guide -- so they land
            // in the top-left corner at the nav bar's height, left of the
            // centered tab bar and ABOVE the group pills, exactly like tvOS.
            // Gated to the Live TV tab and to idle-or-mini (Pending == the
            // fullscreen player is up, which draws its own player hints). A1
            // (resume) only while the mini is Active; drops below the sync pill
            // when background work is running (tvOS isAnyBackgroundWork branch).
            if (selectedTab == AppTab.LiveTV &&
                miniPlayerState !is MiniPlayerSession.State.Pending
            ) {
                // Hard width budget: the gutter between this column's start
                // padding and the nav bar's measured left edge, less a 12dp
                // gap. Logan 2026-08-10: a fixed 320dp cap let the chips run
                // under the bar. Before the first measurement (and if the bar
                // is ever absent) fall back to the old cap.
                val hintStartPad = 24.dp
                val density = LocalDensity.current
                val hintMaxWidth = if (navLeftEdgePx > 0) {
                    (with(density) { navLeftEdgePx.toDp() } - hintStartPad - 12.dp)
                        .coerceAtLeast(160.dp)
                } else {
                    320.dp
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = hintStartPad, top = if (anyBackgroundWork) 60.dp else 18.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                ) {
                    // Compressed copy (Logan 2026-07-20: the chips were
                    // bleeding into the grid). Terse "gesture -> result"
                    // phrasing, capped to a couple of lines total.
                    if (miniPlayerState is MiniPlayerSession.State.Active) {
                        TvGuideHintChip("Play/Pause = resume  ·  Hold Right = close mini", hintMaxWidth)
                    }
                    TvGuideHintChip("Double Back = top channel", hintMaxWidth)
                    // Dynamic hints (Remote Control initiative): copy follows
                    // the user's effective map + guide selector mode, so a
                    // remapped button never advertises a stale gesture.
                    val hintSettingsVm: com.aeriotv.android.feature.settings.SettingsViewModel =
                        hiltViewModel()
                    val hintMap by hintSettingsVm.remoteControlMap.collectAsStateWithLifecycle(
                        initialValue = com.aeriotv.android.core.remote.RemoteControlMap.DEFAULT,
                    )
                    val hintGroupSelector by hintSettingsVm.guideGroupSelector
                        .collectAsStateWithLifecycle(initialValue = "pills")
                    // One nav chip. Sidebar mode claims the hold-Left gesture
                    // outright (Logan 2026-08-06: short Left must scroll the
                    // EPG naturally), so the mapped hold-Left action is not
                    // advertised there - it never fires in that mode.
                    val navHints = buildList {
                        if (hintGroupSelector == "sidebar") {
                            // Short Left opens the sidebar too, so "Left" alone
                            // is both true and shorter than "Left / Hold Left".
                            add("Left = groups")
                        } else {
                            com.aeriotv.android.core.remote.RemoteControlHints
                                .guideHoldLeftShort(hintMap)?.let { add(it) }
                        }
                    }
                    if (navHints.isNotEmpty()) {
                        TvGuideHintChip(navHints.joinToString("  ·  "), hintMaxWidth)
                    }
                }
            }
            }
        }
        return
    }

    // GH #20: auto-hide the floating tab pill while scrolling down, reveal on
    // scroll up. A NestedScrollConnection on the content host sees every
    // tab's Lazy*/ScrollView deltas without hoisting any per-tab scroll
    // state; it only OBSERVES (returns Offset.Zero) so list scrolling is
    // untouched, and only reads vertical deltas so the guide's horizontal
    // timeline can't toggle the bar. Hide needs a deliberate ~48dp downward
    // pull; reveal is eager (~12dp up) plus any tab switch. Only the pill
    // slides away -- the floating MiniPlayerRow card above it stays put,
    // since hiding an actively playing stream's controls would orphan it.
    var bottomBarVisible by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    val bottomBarScrollConnection = remember(density) {
        val hidePx = with(density) { 48.dp.toPx() }
        val showPx = with(density) { 12.dp.toPx() }
        object : NestedScrollConnection {
            // Distance accumulated in the current direction; direction flips
            // reset the opposite counter so slow jittery drags near a
            // threshold can't oscillate the bar.
            private var downDistance = 0f
            private var upDistance = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy < -0.5f) {
                    downDistance += -dy
                    upDistance = 0f
                    if (downDistance > hidePx) bottomBarVisible = false
                } else if (dy > 0.5f) {
                    upDistance += dy
                    downDistance = 0f
                    if (upDistance > showPx) bottomBarVisible = true
                }
                return Offset.Zero
            }
        }
    }
    // Switching tabs must always reveal the bar: the new tab may be short
    // enough that no upward scroll is possible to bring it back.
    LaunchedEffect(selectedTab) { bottomBarVisible = true }

    // Tablets put the tab bar on TOP, in normal flow rather than overlaying
    // (see Viewport.prefersTopTabBar). Two consequences handled below: the tab
    // screens stop reserving bottom space for a pill that is no longer there,
    // and the top bar consumes the status-bar inset so each screen's own
    // TopAppBar does not apply it a second time.
    val viewport = rememberViewport()
    val topTabBar = viewport.prefersTopTabBar
    val tabBarScale = viewport.topTabBarScale

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        // Each tab owns its own TopAppBar with its own status-bar inset. Without
        // overriding here, Scaffold would also add a status-bar top inset to the
        // content padding, producing a ~30dp empty gap above every TopAppBar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // iOS 26 parity: no Scaffold bottomBar. The tab bar is a floating pill
        // OVERLAYING the content (below), so every tab keeps the full screen
        // height and content scrolls behind the pill, exactly like the iPhone
        // app's Liquid-Glass bar. Tab screens already reserve ~104dp of bottom
        // content padding, so their last rows scroll clear of the pill.
    ) { padding ->
      androidx.compose.runtime.CompositionLocalProvider(
          LocalTabBarBottomInset provides if (topTabBar) 16.dp else 104.dp,
      ) {
      androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
        if (topTabBar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 8.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                TabletTopTabBar(
                    tabs = tabs,
                    selected = selectedTab,
                    onSelect = { selectedTab = it; initialTabApplied = true },
                    scale = tabBarScale,
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(padding)
                .then(
                    // The bar above already applied the status-bar inset; without
                    // consuming it here every tab's TopAppBar would add it again.
                    if (topTabBar) {
                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    } else Modifier,
                )
                // GH #20: observe every tab's scroll for the bottom-bar hide.
                .nestedScroll(bottomBarScrollConnection),
        ) {
            MainTabContent(
                selectedTab = selectedTab,
                tabs = tabs,
                onChannelClick = onChannelClick,
                onMovieClick = onMovieClick,
                onSeriesClick = onSeriesClick,
                onEpisodeResume = onEpisodeResume,
                onResumeMovie = onResumeMovie,
                onPlayRecording = onPlayRecording,
                onPlayCatchup = onPlayCatchup,
                onLaunchMultiview = onLaunchMultiview,
                onWatchLive = onWatchLive,
                onWatchFromBeginning = onWatchFromBeginning,
                onOpenSearch = onOpenSearch,
                onSelectTab = { selectedTab = it; initialTabApplied = true },
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
            )
            // iOS "Syncing" pill, top-left over content (below the status bar).
            // Tappable on phone -> background-activity details.
            SyncActivityPill(
                active = anyBackgroundWork,
                labels = syncLabels,
                isTv = false,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 8.dp),
            )
            // Bottom overlay: floating mini-player card above the floating tab
            // pill. The mini stays put while the pill slides away on scroll
            // (GH #20) so an active stream's controls are never hidden.
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // GH #33 re-entry: while a Cast Connect session is active, show a
                // "Now Casting" card above the tab bar (phone only -- TV is the
                // receiver). Tapping it re-enters the player for the cast channel,
                // which renders the full CastRemoteOverlay. The local mini-player
                // is suppressed while casting (casting stops local playback), so
                // these two cards never stack.
                val casting = castState is com.aeriotv.android.core.cast.AerioCastSender.State.Connected
                val activeCastContent = castContent
                // Only LIVE content has a re-entry target today (the live cast
                // remote). VOD casting has no phone remote yet, so don't show a
                // card whose tap would dead-end (wire this on when VOD cast lands).
                val castReentrySupported = activeCastContent?.kind ==
                    com.aeriotv.android.core.cast.AerioCastReceiverController.Kind.LIVE
                if (casting && activeCastContent != null && castReentrySupported && !isTv) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                RoundedCornerShape(20.dp),
                            ),
                    ) {
                        com.aeriotv.android.feature.miniplayer.CastMiniController(
                            title = activeCastContent.title,
                            deviceName = (castState as? com.aeriotv.android.core.cast.AerioCastSender.State.Connected)?.deviceName,
                            artUri = activeCastContent.artUri,
                            isPlaying = castIsPlaying,
                            onTap = {
                                // Match by id first; fall back to name because a
                                // cast resumed after an app restart only recovers
                                // the channel TITLE as mediaId (the receiver's
                                // bridged MediaSession drops our id/customData) --
                                // GH #33, so the tap still re-enters the right
                                // channel instead of dead-ending.
                                val mediaId = activeCastContent.mediaId
                                val bare = mediaId.substringAfter(':', mediaId)
                                val ch = state.channels.firstOrNull { it.id == mediaId }
                                    ?: state.channels.firstOrNull { it.id.substringAfter(':', it.id) == bare }
                                    ?: state.channels.firstOrNull { it.name == mediaId }
                                    ?: state.channels.firstOrNull { it.name.equals(activeCastContent.title, ignoreCase = true) }
                                if (ch != null) onChannelClick(ch)
                                else android.widget.Toast.makeText(
                                    context,
                                    // GH #86: the tap used to do nothing when the
                                    // cast channel is not in the active playlist.
                                    "That channel isn't in the current playlist",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onTogglePlayPause = { castSender.togglePlayPause() },
                            onStop = { castSender.stopCasting() },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // GH #33: round floating "Control a TV" button above the right
                // end of the tab bar -- appears when a controllable AerioTV TV
                // is discovered on the LAN OR a Google Cast route exists, so the
                // phone can be a remote / start a cast without opening a channel
                // first (task #255: this used to be companion-only while the
                // player's picker also listed cast devices). Hidden while
                // already controlling / casting (their cards take over).
                if ((companionDevices.isNotEmpty() ||
                        castState !is com.aeriotv.android.core.cast.AerioCastSender.State.Unavailable) &&
                    companionConn !is com.aeriotv.android.core.cast.companion
                        .CompanionRemoteController.Conn.Connected &&
                    !casting && !isTv
                ) {
                    Box(
                        Modifier.fillMaxWidth().padding(end = 16.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        CompanionControlFab(
                            onClick = { showCompanionPicker = true },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                // GH #33 companion remote: "Controlling <TV>" card, same chrome as
                // the Now-Casting card above. Tap -> reopen the remote; play/pause
                // -> TV transport; x -> disconnect from the TV. Local playback is
                // independent of controlling a TV, so this may coexist with the
                // local mini-player card below (they stack).
                val companionTv = companionConn
                    as? com.aeriotv.android.core.cast.companion.CompanionRemoteController.Conn.Connected
                if (companionTv != null && !isTv) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                RoundedCornerShape(20.dp),
                            ),
                    ) {
                        com.aeriotv.android.feature.miniplayer.CastMiniController(
                            title = companionDetails?.programmeTitle?.takeIf { it.isNotBlank() }
                                ?: companionDetails?.channelName
                                ?: companionNowPlaying.ifBlank { companionTv.name ?: "AerioTV" },
                            deviceName = companionTv.name,
                            artUri = companionDetails?.logoUrl,
                            isPlaying = companionIsPlaying,
                            onTap = {
                                // Same re-entry as the cast card: open the player
                                // for the channel this phone last sent to the TV;
                                // PlayerScreen renders the full remote overlay in
                                // companion mode. Falls back to the tracked title.
                                val ch = state.channels.firstOrNull { it.id == companionChannelId }
                                    ?: state.channels.firstOrNull { it.name == companionNowPlaying }
                                if (ch != null) onOpenCompanionRemote(ch)
                            },
                            onTogglePlayPause = { companionRemote.togglePlayPause() },
                            onStop = { companionRemote.disconnect() },
                            subtitle = "Controlling ${companionTv.name ?: "TV"}",
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                val miniState = miniPlayerState
                // Phase 139 / audit #22: on TV the mini-player is a top-right
                // video window (TvMiniPlayerOverlay, mounted at NavHost root).
                // Suppress the phone-style card so we don't double-render the
                // same session. GH #33: also suppress it while casting so it can
                // never stack under the Now-Casting card (a local mini session
                // that was Active before the cast started would otherwise show).
                if (miniState is MiniPlayerSession.State.Active && !isTv && !casting) {
                    val channel = miniState.channel
                    val nowProgramme = state.epgByChannel[channel.guideMatchKey]?.nowPlaying()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                RoundedCornerShape(20.dp),
                            ),
                    ) {
                        MiniPlayerRow(
                            channel = channel,
                            nowProgramme = nowProgramme,
                            isPaused = miniPaused,
                            onResume = {
                                val resumed = miniPlayerVm.resumeChannel()
                                if (resumed != null) onChannelClick(resumed)
                            },
                            onTogglePause = {
                                exoHolder.setPaused(!exoHolder.isPaused())
                                miniPaused = exoHolder.isPaused()
                            },
                            onDismiss = {
                                miniPlayerVm.dismiss()
                                exoHolder.destroy()
                                com.aeriotv.android.core.playback.AerioMediaPlaybackService
                                    .stop(context)
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (!topTabBar) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = bottomBarVisible,
                        enter = androidx.compose.animation.slideInVertically { it } +
                            androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.slideOutVertically { it } +
                            androidx.compose.animation.fadeOut(),
                    ) {
                        FloatingTabBar(
                            tabs = tabs,
                            selected = selectedTab,
                            onSelect = { selectedTab = it; initialTabApplied = true },
                        )
                    }
                }
            }
        }
      }
      }
    }

    // Task #255: the floating "Control TV" pill opens the SAME unified picker
    // as the player's cast button (Google Cast routes + AerioTV companion TVs
    // with inline pairing), replacing the old companion-only dialog that made
    // cast targets invisible outside the player.
    if (showCompanionPicker) {
        // Force a fresh query sweep the moment the picker opens: the Fold's
        // WiFi misses mid-browse announcements, so a long-running browse can
        // be stale-by-omission (a TV that came up after the browse started).
        LaunchedEffect(Unit) { companionDiscovery.refresh() }
        com.aeriotv.android.feature.cast.CastRouteChooserDialog(
            sender = castSender,
            companionRemote = companionRemote,
            companionDiscovery = companionDiscovery,
            onDismiss = {
                // Abandon an in-flight/unpaired companion attempt so it
                // doesn't linger.
                if (companionConn !is com.aeriotv.android.core.cast.companion
                        .CompanionRemoteController.Conn.Connected &&
                    companionConn !is com.aeriotv.android.core.cast.companion
                        .CompanionRemoteController.Conn.Idle
                ) {
                    companionRemote.disconnect()
                }
                showCompanionPicker = false
            },
        )
    }
    // A fresh connection made from the picker -> close it; the Controlling card
    // + player remote take over.
    LaunchedEffect(companionConn) {
        if (companionConn is com.aeriotv.android.core.cast.companion
                .CompanionRemoteController.Conn.Connected) {
            showCompanionPicker = false
        }
    }
}

/**
 * GH #33: round floating button above the right end of the tab bar -- entry
 * to control a discovered TV. Matches the card chrome (surface + faint
 * primary border) rather than a filled pill, mirroring the iOS glass FAB.
 */
@Composable
private fun CompanionControlFab(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                androidx.compose.foundation.shape.CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Tv,
            contentDescription = "Control a TV",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * iOS 26 parity: the phone tab bar as a centered, floating, rounded pill
 * (the iPhone app's Liquid-Glass bar) instead of a full-width Material
 * NavigationBar. Wrap-content width, surface fill with the shared accent
 * hairline, selected tab gets a soft accent capsule behind icon + label.
 */
/**
 * Tablet top tab bar, matched to iPad's (Phase 4 reference capture, 2026-08-04).
 *
 * Deliberately NOT the phone pill scaled up. iPad's top bar is text-only, hugs
 * its labels instead of distributing them across the window, stands about half
 * the phone bar's height, and marks the selection with a lighter neutral fill
 * plus accent text rather than an accent wash. Reproducing those proportions is
 * the whole point -- an icon-over-label stack at this height reads as a phone
 * bar that wandered to the top of a tablet.
 *
 * [scale] comes from Viewport.topTabBarScale so the bar holds its share of the
 * screen from an 8-inch tablet up to a 13-inch one.
 */
@Composable
private fun TabletTopTabBar(
    tabs: List<AppTab>,
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                CircleShape,
            )
            .padding(all = 4.dp * scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val isSel = tab == selected
            Text(
                text = tab.label,
                fontSize = 17.sp * scale,
                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                // iPad: the selected tab is a LIGHTER neutral fill with accent
                // text, not an accent-tinted fill.
                color = if (isSel) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (isSel) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        else Color.Transparent,
                    )
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 18.dp * scale, vertical = 7.dp * scale),
            )
        }
    }
}

@Composable
private fun FloatingTabBar(
    tabs: List<AppTab>,
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 2026-07-12 (user report: mistapping channels behind the pill when
    // changing tabs): sized up to the iPhone bar's proportions - the pill
    // now spans the width minus side margins with evenly distributed,
    // taller tab targets instead of a compact wrap-content cluster.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                RoundedCornerShape(36.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val isSel = tab == selected
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else Color.Transparent,
                    )
                    .clickable { onSelect(tab) }
                    .padding(vertical = 9.dp),
            ) {
                Icon(
                    imageVector = if (isSel) tab.iconSelected else tab.iconUnselected,
                    contentDescription = tab.label,
                    tint = if (isSel) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSel) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** tvOS guide gesture-hint capsule (HomeView.guideMenuHint parity): near-white
 *  text (white@0.9) on a black@0.4 pill with tight padding so the bubble hugs
 *  the text. 8sp keeps them small like tvOS AND narrow enough that the longest
 *  line clears the centered top-nav in the top-left corner (Android's TV density
 *  renders sp larger than tvOS points). Non-interactive; state-gated by the caller. */
@Composable
private fun TvGuideHintChip(text: String, maxWidth: Dp) {
    Text(
        text = text,
        fontSize = 8.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.9f),
        // Wrap rather than truncate: [maxWidth] is a hard cap measured off the
        // nav bar, and there is empty height between the bar and the guide, so
        // a long remapped hint should stay readable instead of losing its tail.
        maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Shared body for both the phone (bottom-nav Scaffold) and TV (top-tab) shells.
 * [modifier] carries the per-shell insets: Scaffold content padding on phone,
 * a weight + fill on TV.
 */
@Composable
private fun MainTabContent(
    selectedTab: AppTab,
    /** Tabs currently present; only these are kept alive. */
    tabs: List<AppTab>,
    onChannelClick: (M3UChannel) -> Unit,
    onMovieClick: (String) -> Unit,
    onSeriesClick: (Int) -> Unit,
    onEpisodeResume: (String) -> Unit,
    onResumeMovie: (String) -> Unit,
    onPlayRecording: (String, String, Int) -> Unit,
    onPlayCatchup: (String, String, String, Long, Long, String, String) -> Unit,
    onLaunchMultiview: () -> Unit,
    onWatchLive: (String, String, Boolean, Long, Int?) -> Unit,
    onWatchFromBeginning: (String, String, Boolean, Long, Int?, Boolean) -> Unit,
    onOpenSearch: () -> Unit = {},
    // Lets a tab's own content change the selected tab (the TV Search tab's
    // Back returns to Live TV). Latches initialTabApplied at the call sites,
    // same as a manual pill press.
    onSelectTab: (AppTab) -> Unit = {},
    // The PLAYLIST_GRAPH-scoped PlaylistViewModel, threaded down so no tab
    // self-resolves a bare hiltViewModel() against the MAIN entry's store.
    // That default silently minted a SECOND PlaylistViewModel whose init ran
    // the whole channel+EPG bootstrap again (2026-07-12 Streamer report:
    // doubled Room reads, two 24k-row EPG maps, GC storm + jank for the
    // first minute of every cold launch, and the guide running on a
    // different state timeline than the scaffold).
    viewModel: PlaylistViewModel,
    modifier: Modifier = Modifier,
) {
    // Keep visited tabs alive (Logan 2026-09-03: "why does every tab have to
    // reload?"): every tab present in [tabs] that has been shown once stays
    // composed; the inactive ones are measured at zero size and never placed,
    // so they neither draw nor take focus, and their Back handlers and focus
    // pulls are gated on [LocalTabIsActive]. A tab absent from [tabs] (no
    // favorites, VOD or recordings) is not composed at all. Search is the
    // floating screen and is never kept.
    val visited = rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver(
        save = { it.map { t -> t.name } },
        restore = { names -> names.mapNotNull { n -> AppTab.entries.firstOrNull { it.name == n } }.toMutableStateList() },
    )) { mutableStateListOf<AppTab>() }
    if (selectedTab != AppTab.Search && selectedTab !in visited) visited.add(selectedTab)
    val keepAliveTabs = tabs.filter { it in visited }
    Box(modifier = modifier) {
        val render: @Composable (AppTab) -> Unit = { tab -> when (tab) {
            AppTab.LiveTV -> {
    LiveTVTabContent(
                    onChannelClick = onChannelClick,
                    onLaunchMultiview = onLaunchMultiview,
                    onOpenSearch = onOpenSearch,
                    // Catch-up (task #133/#136): a resolved timeshift URL plays
                    // through the recording-player route with programme window +
                    // panel tz for the scrubbable timeline.
                    onPlayCatchup = onPlayCatchup,
                    viewModel = viewModel,
                )
            }
            AppTab.Favorites -> {
    FavoritesTabContent(onChannelClick = onChannelClick)
            }
            AppTab.DVR -> {
    DvrTabContent(
                    onPlayRecording = onPlayRecording,
                    onWatchLive = onWatchLive,
                    onWatchFromBeginning = onWatchFromBeginning,
                )
            }
            AppTab.OnDemand -> {
    OnDemandTabContent(
                    onMovieClick = { movie -> onMovieClick(movie.uuid) },
                    onSeriesClick = { series -> onSeriesClick(series.id) },
                    onEpisodeResume = onEpisodeResume,
                    onResumeMovie = onResumeMovie,
                    onOpenSearch = onOpenSearch,
                )
            }
            AppTab.Settings -> {
    SettingsTabContent(playlistViewModel = viewModel)
                // TV-only tab (Logan 2026-08-06): the same global Search screen the
                // phone reaches via the app-bar globe, hosted in place. EPG results
                // go through requestGuideJump, whose scaffold collector already
                // switches to Live TV and jumps the guide; movie/series results use
                // the same detail routes as On Demand. No back arrow (the pushed
                // route keeps its own) and no field auto-focus - with
                // selection-follows-focus on the nav bar, a focus grab here would
                // yank the user out of the bar the moment the pill highlights.
            }
            AppTab.Search -> {
    com.aeriotv.android.feature.search.SearchScreen(
                    onBack = { onSelectTab(AppTab.LiveTV) },
                    onEpgResult = { channelKey, startMillis ->
                        viewModel.requestGuideJump(channelKey, startMillis)
                    },
                    onMovieClick = onMovieClick,
                    onSeriesClick = onSeriesClick,
                    showBackButton = false,
                    autoFocusField = false,
                )
            }
            else -> Unit
        } }
        if (selectedTab == AppTab.Search) {
            render(AppTab.Search)
        }
        // Each kept tab lives in ONE slot (keyed by tab, fixed order) and only
        // its modifier flips between hidden and shown: moving a tab between
        // two different parents would dispose and rebuild it, which is the
        // reload this exists to avoid. BackHandlers inside are gated on
        // LocalTabIsActive, so composition order does not matter for Back.
        keepAliveTabs.forEach { tab ->
            val active = tab == selectedTab
            androidx.compose.runtime.key(tab) {
                CompositionLocalProvider(LocalTabIsActive provides active) {
                    Box(if (active) Modifier.fillMaxSize() else Modifier.keepAliveHidden()) { render(tab) }
                }
            }
        }
    }
}

/**
 * 10-foot top navigation for Android TV. A horizontal, D-pad-traversable row of
 * pill tabs with overscan-safe margins. Selection follows focus (tvOS TabView
 * behaviour): landing on a tab switches to it; pressing DOWN drops into content.
 *
 * Audit task #57: [focusRequester] is attached to the pill Row and combined
 * with [Modifier.focusRestorer]. When a section calls `focusRequester
 * .requestFocus()` (via the D-pad UP route from the guide), focus lands on
 * the previously-focused pill rather than the first one - so the user comes
 * back exactly where they left.
 */
@Composable
private fun TvTopTabBar(
    tabs: List<AppTab>,
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    focusRequester: FocusRequester,
    tabEntryFocus: androidx.compose.runtime.State<FocusRequester?>? = null,
    lastUpKeyMs: LongArray = longArrayOf(0L),
    pillRequesters: Map<AppTab, FocusRequester> = emptyMap(),
    /** Refresh circle (Logan 2026-08-06): TV's stand-in for pull-to-refresh.
     *  Re-fetches channels + EPG so Dispatcharr-side group/channel edits show
     *  up without a trip through Settings > playlist. */
    onRefresh: () -> Unit = {},
    refreshing: Boolean = false,
    /** Reports the leftmost pixel the bar actually occupies (the action
     *  circles when shown, otherwise the centered capsule) so the top-left
     *  gesture hints can size themselves to the real gutter instead of a
     *  hard-coded guess. Logan 2026-08-10: the hint pill grew into the bar. */
    onLeftEdgeChanged: (Int) -> Unit = {},
    /** Keep Recent Channels Live: count of flipped-away channels still
     *  buffering. > 0 shows the indicator circle beside Refresh/Search -
     *  the one spot that is focus-reachable on every content tab (the
     *  guide's pill-row placement is skipped in sidebar group mode, and
     *  a standalone strip was not reachable by D-pad; field 2026-08-31). */
    retainedCount: Int = 0,
    onRetainedClick: () -> Unit = {},
) {
    // Selection-follows-focus, but committed ONLY for focus moves BETWEEN pills
    // (real D-pad traversal of the bar), never for focus ENTERING the bar from
    // outside. That entry case is exactly how the focus fallback used to bounce
    // the user to Live TV: drilling into a Settings sub-screen removes the
    // focused row, Compose hands focus to the first focusable in the tree (the
    // leftmost Live TV pill), and a straight onFocus -> onSelect switched the
    // tab, unmounting the sub-screen. We "arm" on the first pill focus after the
    // bar gains focus and only commit on subsequent in-bar moves; the
    // post-composition requestFocus that pulls focus back into the content can't
    // win that race on its own, so the guard lives here where it is deterministic.
    var navHasFocus by remember { mutableStateOf(false) }
    var focusedTab by remember { mutableStateOf<AppTab?>(null) }
    var armed by remember { mutableStateOf(false) }
    // Arm one frame AFTER the bar gains focus, so the focus that ENTERS the bar
    // (the same-frame pill focus, including the involuntary fallback) is never
    // treated as a deliberate selection. Deferring a frame makes this order
    // independent of whether the Row's or the pill's onFocusChanged fires first.
    LaunchedEffect(navHasFocus) {
        if (navHasFocus) {
            androidx.compose.runtime.withFrameNanos { }
            armed = true
        } else {
            armed = false
        }
    }
    LaunchedEffect(focusedTab) {
        val cur = focusedTab ?: return@LaunchedEffect
        // Deliberate bar ENTRY (a fresh D-pad UP) selects immediately too, so
        // highlighting a pill is always enough; the armed gate alone made the
        // first-focused pill need an extra move or OK (user report).
        val deliberateEntry =
            android.os.SystemClock.uptimeMillis() - lastUpKeyMs[0] < 400L
        if ((armed || deliberateEntry) && cur != selected) {
            // E-5 (perf campaign 2026-08-19): SETTLE before committing. Only
            // one tab branch is composed at a time, so each intermediate
            // commit while walking the pill row serially cold-builds and
            // disposes an entire tab (guide, On Demand grid, DVR) on the
            // Streamer's single big core. 250ms is under the "did it react"
            // threshold for a resting selection but longer than a pill-walk
            // step, so pass-through pills never build. This LaunchedEffect is
            // keyed on focusedTab: moving to the next pill cancels the
            // pending commit with the coroutine.
            kotlinx.coroutines.delay(250L)
            onSelect(cur)
        }
    }

    // Focus entering the bar by ANY route (the guide's routed UP, or plain
    // geometric 2D search from tabs that don't wire the requester) must land
    // on the SELECTED tab's pill. Geometric entry used to land on whichever
    // pill sat above the content column (On Demand over the centered Settings
    // form), and with selection-follows-focus that instantly switched tabs
    // (user report). The per-pill requesters + the group's entry redirect
    // replace focusRestorer: with selection following focus, the selected
    // pill IS the last-focused pill in every normal flow.
    // tvOS-style floating nav: the tabs are grouped into one centered, rounded
    // "segmented" capsule over the app background (no full-width surface toolbar
    // strip), so the bar reads as a polished pill group rather than a heavy bar.
    // The action circles hide on Settings (Logan 2026-08-06: they belong to
    // the content tabs - Live TV / DVR / On Demand). They stay while the
    // Search screen itself is up so its circle can show the selected fill.
    val showActionCircles = selected != AppTab.Settings
    // Cold start: TV initial focus falls on the LEFTMOST focusable, which is
    // now the Refresh circle - pull it onto the selected pill (the
    // pre-circle behavior, and an accidental OK there refreshed instead of
    // doing nothing). The system grants initial focus when the WINDOW gains
    // focus, which lands after composition - a plain one-frame LaunchedEffect
    // lost that race (Streamer 2026-08-06) - so key the one-shot pull on the
    // window-focus edge and run it a frame later.
    var initialPillFocusPulled by remember { mutableStateOf(false) }
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        if (windowFocused && !initialPillFocusPulled) {
            initialPillFocusPulled = true
            androidx.compose.runtime.withFrameNanos { }
            runCatching { pillRequesters[selected]?.requestFocus() }
        }
    }
    // Custom layout so the PILL CAPSULE is centered on the SCREEN (Logan
    // 2026-08-06: adding the circles to a shared centered row shoved the
    // pills off-center) and the circles hang off its left edge without
    // affecting its position.
    //
    // The circles live OUTSIDE the capsule's focus group, on purpose. Bar
    // entry arrives as a direct requestFocus on the capsule row, which
    // IGNORES enter/onEnter redirects and lands on the group's first
    // focusable - with a circle inside the group, entry never reached the
    // selected pill and the armed gate (correctly) refused to treat entry
    // focus as a selection, so Search could never open (Streamer
    // 2026-08-06). Out here the capsule's focus contract is exactly the
    // pre-circle one, and each circle is a plain focusable CLICK target
    // that D-pad Left reaches geometrically.
    androidx.compose.ui.layout.Layout(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 12.dp),
        content = {
            Row(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    // focusProperties describes the focus target BELOW it in
                    // the chain, so it must precede focusGroup() to apply to
                    // the group (after it, the enter/exit callbacks never ran).
                    .focusProperties {
                        onEnter = {
                            pillRequesters[selected]?.requestFocus()
                        }
                        // Down into the tab: land where the tab asked to
                        // (On Demand's current sub-tab pill), else default.
                        onExit = {
                            if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Down) {
                                val target = tabEntryFocus?.value
                                if (target != null && runCatching { target.requestFocus() }.isSuccess) {
                                    // The default geometric move would run
                                    // after this and land elsewhere.
                                    cancelFocusChange()
                                }
                            }
                        }
                    }
                    .focusGroup()
                    // Row-level hasFocus stays true while focus moves between pills
                    // and only flips false when focus leaves the bar entirely, so it
                    // is the reliable "is the user in the bar" signal for [armed].
                    .onFocusChanged { navHasFocus = it.hasFocus }
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                // 6dp still leaves headroom for the focused pill's 1.04x paint-only
                // grow (graphicsLayer does not relayout); at 3dp the widest pill
                // visually collided. Trimmed from 8dp to narrow the bar.
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    TvTab(
                        tab = tab,
                        selected = tab == selected,
                        onFocused = { focusedTab = tab },
                        modifier = pillRequesters[tab]?.let { Modifier.focusRequester(it) } ?: Modifier,
                    )
                }
            }
            if (showActionCircles) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Refresh (TV's pull-to-refresh stand-in), then Search -
                    // the most-used action sits closest to the pills, one
                    // Left press away.
                    TvBarCircleButton(
                        icon = Icons.Filled.Refresh,
                        contentDescription = "Refresh channels and guide",
                        onClick = onRefresh,
                        spinning = refreshing,
                    )
                    if (retainedCount > 0) {
                        TvBarCircleButton(
                            icon = Icons.Filled.FiberSmartRecord,
                            contentDescription = "$retainedCount channels kept live",
                            onClick = onRetainedClick,
                        )
                    }
                    TvBarCircleButton(
                        icon = AppTab.Search.iconSelected,
                        contentDescription = AppTab.Search.label,
                        selected = selected == AppTab.Search,
                        onClick = { onSelect(AppTab.Search) },
                        modifier = pillRequesters[AppTab.Search]
                            ?.let { Modifier.focusRequester(it) } ?: Modifier,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val capsule = measurables[0].measure(loose)
        val circles = measurables.getOrNull(1)?.measure(loose)
        val width = constraints.maxWidth
        val height = maxOf(capsule.height, circles?.height ?: 0)
        layout(width, height) {
            val capsuleX = (width - capsule.width) / 2
            capsule.placeRelative(capsuleX, (height - capsule.height) / 2)
            val circlesX = capsuleX - 10.dp.roundToPx() - (circles?.width ?: 0)
            circles?.placeRelative(circlesX, (height - circles.height) / 2)
            // Publish the real left edge for the hint chips. Writing snapshot
            // state here is safe: it feeds a SIBLING overlay, never this bar,
            // so it cannot drive a layout loop, and an equal write is a no-op.
            onLeftEdgeChanged(if (circles != null) circlesX else capsuleX)
        }
    }
}

/**
 * A floating action circle beside the pill capsule (Search, Refresh). Unlike
 * the pills these are plain BUTTONS: focus highlights (white ring, app
 * convention) and OK acts. They deliberately do NOT select on focus - they
 * sit outside the capsule's focus group and its armed machinery, so
 * focus-driven action here would fire on every accidental Left past the
 * bar's edge. [selected] paints solid primary while the button's content is
 * the one on screen (Search); [spinning] rotates the glyph while its action
 * is in flight (Refresh).
 */
@Composable
private fun TvBarCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    spinning: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primary
            focused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
            else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
        },
        label = "tvBarCircleBackground",
    )
    val foreground by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.onPrimary
            focused -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "tvBarCircleForeground",
    )
    // Spin only while asked: the infinite transition exists (and ticks
    // frames) solely inside this branch, so an idle bar animates nothing.
    val rotation = if (spinning) {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(
            label = "tvBarCircleSpin",
        )
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(
                    durationMillis = 1000,
                    easing = androidx.compose.animation.core.LinearEasing,
                ),
            ),
            label = "tvBarCircleSpinAngle",
        ).value
    } else {
        0f
    }
    Box(
        modifier = modifier
            .tvFocusScale(focused, focusedScale = 1.04f)
            .clip(CircleShape)
            .background(background)
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            // Matches the pill height (18dp icon + 6dp vertical pad + the
            // capsule's own 4dp) so the circle centers cleanly beside it.
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = foreground,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

@Composable
private fun TvTab(
    tab: AppTab,
    selected: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    // SELECTED owns the solid fill; FOCUS is the white ring (the app-wide
    // guide-pill convention). The old scheme gave focused-not-selected a
    // solid fill brighter than the selected tab, so re-entering the bar lit
    // two pills as "active" at once.
    val background by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primary
            focused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
            else -> Color.Transparent
        },
        label = "tvTabBackground",
    )
    val foreground by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.onPrimary
            focused -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "tvTabForeground",
    )
    Row(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            // 1.04x (not the 1.08x default): the pills sit 8dp apart and the
            // paint-only grow must stay inside that gap.
            .tvFocusScale(focused, focusedScale = 1.04f)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            // Transparent (not absent) border at rest keeps the measured pill
            // size constant so nothing shifts when focus arrives.
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(18.dp),
            )
            .focusable()
            // Trimmed (13->10 h / 20->18 icon) to narrow the whole centered nav
            // bar so its right edge clears the enlarged corner mini-player.
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (selected) tab.iconSelected else tab.iconUnselected,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = tab.label,
            color = foreground,
            // Bumped from titleSmall to match the tvOS nav-bar scale.
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * Collapses a TV chrome strip (the top tab bar here, the On Demand segment
 * pills on their tab) to [visibleFraction] of its measured height, fading the
 * pixels in step. The strip stays MOUNTED throughout: its children are
 * FocusRequester targets and requestFocus() on a detached node throws, so the
 * hide must be geometric, not compositional. Height is floored at 1px so the
 * strip also stays reachable by plain D-pad UP focus search when fully
 * collapsed; focus arriving is the signal the callers use to expand it again.
 *
 * Internal (not private) because the On Demand tab applies the same treatment
 * to its Movies/Series pills; the behavior must stay identical in both spots.
 * Also drives the phone bottom NavigationBar's scroll auto-hide (GH #20),
 * where the height collapse is what lets Scaffold hand the space to content.
 */
internal fun Modifier.collapsibleChrome(visibleFraction: Float): Modifier = this
    .graphicsLayer { alpha = visibleFraction }
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val collapsedHeight =
            (placeable.height * visibleFraction).toInt().coerceAtLeast(1)
        layout(placeable.width, collapsedHeight) {
            placeable.placeRelative(0, 0)
        }
    }

/**
 * Routes that replace the WHOLE Settings host rather than rendering in a pane
 * (plan B2's TV takeover set). Shared so the focus contract and the rail host
 * cannot disagree about what counts as a takeover.
 */
private fun isSettingsTakeover(route: SettingsRoute): Boolean =
    route is SettingsRoute.LogViewer ||
        route is SettingsRoute.Licenses ||
        route is SettingsRoute.EditPlaylist ||
        route is SettingsRoute.AddPlaylist ||
        route is SettingsRoute.AddMoreCategories

@Composable
private fun SettingsTabContent(
    // The PLAYLIST_GRAPH-scoped instance, threaded from MainTabContent. A
    // bare hiltViewModel() here resolved against the MAIN entry's store and
    // minted a second PlaylistViewModel (full bootstrap re-run) the moment
    // Settings opened - same leak as LiveTVTabContent's old default.
    playlistViewModel: PlaylistViewModel,
) {
    // Phase B2: one saveable back stack replaces the six independent booleans
    // plus `section`. "Innermost" is now literal (stack order) instead of being
    // encoded implicitly in the ORDER of the BackHandler and content `when`
    // branches, which had to be kept in agreement by hand. rememberSaveable
    // also means rotation, fold posture changes, and process death restore the
    // position, which plain `remember` could not.
    val nav = rememberSettingsNavState()
    val route = nav.current
    // Phase B3: the tablet list-detail host. Expanded width alone is not the
    // gate -- a landscape phone is "expanded" but far too short -- and TV keeps
    // the stacked layout until B4 lands the rail with its focus contract.
    val isTvDevice = rememberIsTvDevice()
    val paneEligible = rememberViewport().isTwoPaneEligible
    // Same eligibility, two hosts: touch tablets get the tap sidebar (B3), TV
    // gets the 10-foot rail with its focus contract (B4).
    val tabletTwoPane = paneEligible && !isTvDevice
    val tvRail = paneEligible && isTvDevice
    val twoPane = tabletTwoPane || tvRail
    var selection by rememberSettingsPaneSelection(nav = nav, twoPane = twoPane, initial = if (tvRail) SettingsRoute.Root else DefaultSettingsPaneSelection)
    // True for the route the pane is BASELINED on; pushes above it render as
    // ordinary screens and keep their own back affordance.
    val inPane = twoPane && route == null
    val updaterEnabled = hiltViewModel<com.aeriotv.android.feature.update.UpdateViewModel>()
        .isEnabled
    val addPlaylistStep: AddPlaylistStep = when (val r = route) {
        is SettingsRoute.AddPlaylist -> when (val st = r.step) {
            is AddPlaylistWizardStep.ChooseType -> AddPlaylistStep.ChooseType
            is AddPlaylistWizardStep.Configure -> AddPlaylistStep.Configure(st.sourceType)
        }
        else -> AddPlaylistStep.None
    }
    val playlistVm = playlistViewModel
    val playlistState by playlistVm.state.collectAsStateWithLifecycle()
    // Watch for a playlist id flip while we're inside the Add Playlist flow;
    // that means the user's onboarding Save succeeded and the new row was
    // promoted active. Close the embedded flow.
    val startId = remember(addPlaylistStep) {
        if (addPlaylistStep != AddPlaylistStep.None) playlistState.playlist?.id else null
    }
    LaunchedEffect(playlistState.playlist?.id) {
        if (addPlaylistStep != AddPlaylistStep.None &&
            startId != null &&
            playlistState.playlist?.id != startId
        ) {
            nav.pop()
        }
    }
    androidx.activity.compose.BackHandler(enabled = nav.canPop) {
        val cur = nav.current
        // The wizard's stages advance IN PLACE rather than nesting, so Back
        // from Configure returns to ChooseType instead of leaving the flow.
        if (cur is SettingsRoute.AddPlaylist && cur.step is AddPlaylistWizardStep.Configure) {
            nav.replaceTop(SettingsRoute.AddPlaylist(AddPlaylistWizardStep.ChooseType))
        } else {
            nav.pop()
        }
    }
    // TV focus retention. The top nav uses selection-follows-focus (focusing
    // the Live TV pill switches to the guide). When the user clicks a settings
    // row, the sub-screen replaces the list and the focused row is removed --
    // Compose then falls focus back to the first focusable in the tree, the
    // nav pill row, whose Live TV pill grabs focus and bounces the user to the
    // guide (the "clicking any setting goes back to the guide" bug). Fix: pull
    // focus INTO the settings content whenever a sub-screen appears so it never
    // lands on the nav. Keyed on the visible sub-screen; the root list (key
    // null) is left alone so the pill -> DOWN -> list traversal is unchanged.
    val settingsContentFocus = remember { FocusRequester() }
    // Plan B2: "fire it only for full-screen takeovers entering or exiting".
    // In a two-pane host a push renders INSIDE the pane with the rail still up,
    // so pulling focus to this outer group would land it on the rail's first
    // row and orphan the push (traced on the Streamer 2026-08-05). The rail
    // host moves focus into the pane itself for those; this stays responsible
    // for the stacked layouts and for takeovers.
    val subScreenKey: String? = when {
        !twoPane -> nav.focusKey
        route != null && isSettingsTakeover(route) -> nav.focusKey
        else -> null
    }
    var prevSubScreenKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(subScreenKey) {
        // Pull focus into the content both when a sub-screen OPENS and when it
        // CLOSES back to the root list, so focus never lingers on a nav pill
        // (where the involuntary fallback parks it). Skip the very first
        // composition (prev == cur == null) so switching INTO Settings still
        // lands on the Settings pill, preserving the pill -> DOWN -> list flow.
        val entering = subScreenKey != null
        val exitingToRoot = subScreenKey == null && prevSubScreenKey != null
        if (entering || exitingToRoot) runCatching { settingsContentFocus.requestFocus() }
        prevSubScreenKey = subScreenKey
    }
    // Phase B3: one renderer for a route, used by BOTH the stacked phone layout
    // and the tablet host's detail pane, so the two can never diverge.
    val renderRoute: @Composable (SettingsRoute?) -> Unit = { r ->
    when (r) {
        null -> SettingsScreen(
            onSectionClick = { nav.push(SettingsRoute.Section(it)) },
            onOpenPlaylistDetail = { id -> nav.push(SettingsRoute.PlaylistDetail(id)) },
            onOpenPlaylists = { nav.push(SettingsRoute.Playlists) },
            onAddPlaylist = {
                nav.push(SettingsRoute.AddPlaylist(AddPlaylistWizardStep.ChooseType))
            },
            onOpenLicenses = { nav.push(SettingsRoute.Licenses) },
            viewModel = playlistVm,
        )
        is SettingsRoute.About -> SettingsScreen(
            onSectionClick = { nav.push(SettingsRoute.Section(it)) },
            onOpenLicenses = { nav.push(SettingsRoute.Licenses) },
            viewModel = playlistVm,
            content = SettingsRootContent.AboutOnly,
        )
        is SettingsRoute.Root -> SettingsScreen(
            onSectionClick = { nav.push(SettingsRoute.Section(it)) },
            onOpenPlaylistDetail = { id -> nav.push(SettingsRoute.PlaylistDetail(id)) },
            onOpenPlaylists = { nav.push(SettingsRoute.Playlists) },
            onAddPlaylist = {
                nav.push(SettingsRoute.AddPlaylist(AddPlaylistWizardStep.ChooseType))
            },
            onOpenLicenses = { nav.push(SettingsRoute.Licenses) },
            viewModel = playlistVm,
        )
        is SettingsRoute.AddPlaylist -> when (val st = r.step) {
            is AddPlaylistWizardStep.Configure -> ConfigureSourceScreen(
                sourceType = st.sourceType,
                onBack = {
                    nav.replaceTop(SettingsRoute.AddPlaylist(AddPlaylistWizardStep.ChooseType))
                },
                viewModel = playlistVm,
            )
            is AddPlaylistWizardStep.ChooseType -> ChooseSourceTypeScreen(
                onBack = { nav.pop() },
                onChoose = { type ->
                    // Start a FRESH draft so the add creates a NEW row and can't
                    // carry over the active server's bootstrap-prefilled API key
                    // (which would win over typed user/pass and re-add the active
                    // server's account). Mirrors the onboarding CHOOSE_TYPE path.
                    playlistVm.startNewSource(type)
                    nav.replaceTop(
                        SettingsRoute.AddPlaylist(AddPlaylistWizardStep.Configure(type)),
                    )
                },
            )
        }
        is SettingsRoute.Playlists ->
            if (inPane) {
                // As a PANE this is the sidebar's Playlists item, so it shows
                // the root's playlist block (switch / open / Add / Manage);
                // Manage Playlists then pushes the reorder page above it.
                SettingsScreen(
                    onSectionClick = { nav.push(SettingsRoute.Section(it)) },
                    onOpenPlaylistDetail = { id ->
                        nav.push(SettingsRoute.PlaylistDetail(id))
                    },
                    onOpenPlaylists = { nav.push(SettingsRoute.Playlists) },
                    onAddPlaylist = {
                        nav.push(SettingsRoute.AddPlaylist(AddPlaylistWizardStep.ChooseType))
                    },
                    viewModel = playlistVm,
                    content = SettingsRootContent.PlaylistsOnly,
                )
            } else {
                com.aeriotv.android.feature.settings.PlaylistsScreen(
                    onBack = { nav.pop() },
                    onAddPlaylist = {
                        nav.push(SettingsRoute.AddPlaylist(AddPlaylistWizardStep.ChooseType))
                    },
                    onOpenPlaylistDetail = { id ->
                        nav.push(SettingsRoute.PlaylistDetail(id))
                    },
                    viewModel = playlistVm,
                )
            }
        is SettingsRoute.EditPlaylist -> com.aeriotv.android.feature.settings.EditPlaylistScreen(
            onBack = { nav.pop() },
            viewModel = playlistVm,
        )
        is SettingsRoute.PlaylistDetail ->
            com.aeriotv.android.feature.settings.PlaylistDetailScreen(
                onBack = { nav.pop() },
                onEdit = { nav.push(SettingsRoute.EditPlaylist(r.playlistId)) },
                playlistId = r.playlistId,
                viewModel = playlistVm,
            )
        is SettingsRoute.AddMoreCategories -> AddMoreCategoriesScreen(onBack = { nav.pop() })
        is SettingsRoute.LogViewer -> com.aeriotv.android.feature.settings.LogViewerScreen(
            onBack = { nav.pop() },
        )
        is SettingsRoute.Licenses -> com.aeriotv.android.feature.settings.LicensesScreen(
            onBack = { nav.pop() },
        )
        is SettingsRoute.Section -> when (r.section) {
            SettingsSection.Appearance -> AppearanceSettingsScreen(
                onBack = { nav.pop() },
                onOpenAddMoreCategories = { nav.push(SettingsRoute.AddMoreCategories) },
            )
            SettingsSection.AppBehaviors -> AppBehaviorsSettingsScreen(onBack = { nav.pop() })
            SettingsSection.Multiview -> MultiviewSettingsScreen(onBack = { nav.pop() })
            SettingsSection.Network -> NetworkSettingsScreen(onBack = { nav.pop() })
            SettingsSection.RemoteControl ->
                com.aeriotv.android.feature.settings.RemoteControlSettingsScreen(
                    onBack = { nav.pop() },
                )
            SettingsSection.AppUpdates ->
                com.aeriotv.android.feature.settings.AppUpdatesScreen(onBack = { nav.pop() })
            SettingsSection.Sync -> com.aeriotv.android.feature.settings.SyncSettingsScreen(
                onBack = { nav.pop() },
            )
            SettingsSection.DvrSettings -> DvrSettingsScreen(onBack = { nav.pop() })
            SettingsSection.Developer -> DeveloperSettingsScreen(
                onBack = { nav.pop() },
                onOpenLogViewer = { nav.push(SettingsRoute.LogViewer) },
            )
        }
    }
    }

    Box(modifier = Modifier.focusRequester(settingsContentFocus).focusGroup()) {
        if (tvRail) {
            SettingsTvRailHost(
                selection = selection,
                onSelect = { picked ->
                    nav.popToRoot()
                    selection = picked
                },
                pushed = route,
                sections = visibleSettingsSections(
                    isTv = true,
                    updaterEnabled = updaterEnabled,
                ),
                activePlaylistName = playlistState.playlist?.name,
                // Plan B2: the TV takeover set. These keep the whole screen so
                // their keyboard / IME plumbing is untouched.
                takeover = ::isSettingsTakeover,
                detail = { renderRoute(it) },
            )
        } else if (tabletTwoPane) {
            // Rev 2: sidebar browsing mutates `selection` and never pushes, so
            // Back can only unwind real pushes.
            SettingsTwoPaneHost(
                selection = selection,
                onSelect = { picked ->
                    // Leaving a pane abandons anything pushed above it; the new
                    // pane starts at its own baseline (Apple rail behavior).
                    nav.popToRoot()
                    selection = picked
                },
                pushed = route,
                sections = visibleSettingsSections(
                    isTv = isTvDevice,
                    updaterEnabled = updaterEnabled,
                ),
                activePlaylistName = playlistState.playlist?.name,
                // Log lines want the whole width; everything else fits a pane.
                takeover = { it is SettingsRoute.LogViewer || it is SettingsRoute.Licenses },
                detail = { renderRoute(it) },
            )
        } else {
            // Dispatch on the stack's top route. Ordering that used to be
            // implicit in branch position (the log viewer had to sit ABOVE
            // Developer to win) is now just push order.
            renderRoute(route)
        }
    }
}

/**
 * Mirror of iOS MainTabView's dynamic tab-visibility rule (HomeView.swift
 * hasFavorites / hasRecordings / hasVOD). Always-on tabs: Live TV, Settings.
 * Conditional tabs appear ONLY when there is real content to show, NOT merely
 * because the source type could in theory serve it:
 *  - Favorites: the user has favorited at least one channel in the active playlist.
 *  - DVR: at least one recording exists (scheduled / recording / completed,
 *    server or local) for the active source.
 *  - On Demand: the active source has advertised any VOD (movies or series), or
 *    is still loading its VOD library (loading bridge prevents cold-start flicker).
 *
 * A bare live-TV M3U, or a Dispatcharr/Xtream source with no VOD and no
 * recordings, surfaces only Live TV + Settings - empty tabs never appear.
 */
internal fun visibleTabs(
    hasFavorites: Boolean = false,
    hasVod: Boolean = false,
    hasRecordings: Boolean = false,
): List<AppTab> = buildList {
    add(AppTab.LiveTV)
    if (hasFavorites) add(AppTab.Favorites)
    if (hasRecordings) add(AppTab.DVR)
    if (hasVod) add(AppTab.OnDemand)
    add(AppTab.Settings)
    // AppTab.Search is deliberately NOT a pill: on TV it renders as the
    // floating circle LEFT of Live TV inside TvTopTabBar (Logan 2026-08-06:
    // frequent searchers shouldn't traverse the whole bar), and phones keep
    // their app-bar search entry points instead of a bottom-bar tab.
}

/** EntryPoint accessor so MainScaffold can drive pause/destroy on the held
 * MPV instance without routing through a ViewModel. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface MainScaffoldEntryPoint {
    fun exoPlayerHolder(): AerioExoPlayerHolder
    fun exoWindowState(): com.aeriotv.android.feature.player.ExoWindowState
    fun castSender(): com.aeriotv.android.core.cast.AerioCastSender
    fun companionRemote(): com.aeriotv.android.core.cast.companion.CompanionRemoteController
    fun companionDiscovery(): com.aeriotv.android.core.cast.companion.CompanionDiscovery
}

/** Two-step Add Playlist flow embedded in the Settings tab. None = closed. */
private sealed interface AddPlaylistStep {
    data object None : AddPlaylistStep
    data object ChooseType : AddPlaylistStep
    data class Configure(val sourceType: com.aeriotv.android.core.data.SourceType) : AddPlaylistStep
}

/** Scaffold-level view of On Demand state: presence flags only, so page
 *  emissions from the catalog walk do not recompose the scaffold. */
private data class VodPresence(
    val unsupportedSource: Boolean,
    val hasMovies: Boolean,
    val hasSeries: Boolean,
    val isLoading: Boolean,
    val isLoadingSeries: Boolean,
    val hasDeferredXtreamContent: Boolean,
) {
    companion object {
        fun from(s: com.aeriotv.android.feature.ondemand.OnDemandViewModel.UiState) = VodPresence(
            unsupportedSource = s.unsupportedSource,
            hasMovies = s.movies.isNotEmpty(),
            hasSeries = s.series.isNotEmpty(),
            isLoading = s.isLoading,
            isLoadingSeries = s.isLoadingSeries,
            hasDeferredXtreamContent = s.hasDeferredXtreamContent,
        )
    }
}

/** True inside the tab the user is on; false inside a tab kept alive but
 *  hidden. Tab content gates focus pulls and BackHandlers on it. */
val LocalTabIsActive = androidx.compose.runtime.compositionLocalOf { true }

/** Measured at zero size and never placed: no draw, no focus geometry, no
 *  semantics, but the composition (and its state, scroll positions, loaded
 *  data) survives. */
private fun Modifier.keepAliveHidden(): Modifier = this
    .layout { measurable, _ ->
        val placeable = measurable.measure(androidx.compose.ui.unit.Constraints(maxWidth = 0, maxHeight = 0))
        layout(0, 0) { /* deliberately not placed */ }
    }
    .focusProperties { canFocus = false }
    .semantics { invisibleToUser() }
