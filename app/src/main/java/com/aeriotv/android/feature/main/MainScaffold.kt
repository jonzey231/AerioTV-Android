package com.aeriotv.android.feature.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.border
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aeriotv.android.feature.livetv.rememberLiveTvFormFactor
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.playback.AerioExoPlayerHolder
import com.aeriotv.android.feature.dvr.DvrTabContent
import com.aeriotv.android.feature.dvr.DvrViewModel
import com.aeriotv.android.feature.favorites.FavoritesTabContent
import com.aeriotv.android.feature.favorites.FavoritesViewModel
import com.aeriotv.android.feature.livetv.LiveTVTabContent
import com.aeriotv.android.feature.miniplayer.MiniPlayerRow
import com.aeriotv.android.feature.miniplayer.MiniPlayerSession
import com.aeriotv.android.feature.miniplayer.MiniPlayerViewModel
import com.aeriotv.android.feature.onboarding.ChooseSourceTypeScreen
import com.aeriotv.android.feature.onboarding.ConfigureSourceScreen
import com.aeriotv.android.feature.ondemand.OnDemandTabContent
import com.aeriotv.android.feature.ondemand.OnDemandViewModel
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.playlist.nowPlaying
import com.aeriotv.android.feature.settings.AppBehaviorsSettingsScreen
import com.aeriotv.android.feature.settings.AddMoreCategoriesScreen
import com.aeriotv.android.feature.settings.AppearanceSettingsScreen
import com.aeriotv.android.feature.settings.DeveloperSettingsScreen
import com.aeriotv.android.feature.settings.DvrSettingsScreen
import com.aeriotv.android.feature.settings.MultiviewSettingsScreen
import com.aeriotv.android.feature.settings.NetworkSettingsScreen
import com.aeriotv.android.feature.settings.SettingsScreen
import com.aeriotv.android.feature.settings.SettingsSection
import com.aeriotv.android.feature.settings.SettingsSubScreenPlaceholder
import com.aeriotv.android.feature.settings.SettingsViewModel
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
    onMovieClick: (String) -> Unit = {},
    onSeriesClick: (Int) -> Unit = {},
    onEpisodeResume: (String) -> Unit = {},
    onResumeMovie: (String) -> Unit = {},
    onPlayRecording: (String, String) -> Unit = { _, _ -> },
    /** Catch-up (task #136): url, title, progStartMillis, progEndMillis, panelTz. */
    onPlayCatchup: (String, String, Long, Long, String, String) -> Unit = { _, _, _, _, _, _ -> },
    onLaunchMultiview: () -> Unit = {},
    onWatchLive: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onWatchFromBeginning: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onOpenSearch: () -> Unit = {},
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favoritesVm: FavoritesViewModel = hiltViewModel()
    val favorites by favoritesVm.all.collectAsStateWithLifecycle(initialValue = emptyList())
    // Show the Favorites tab only when the user has at least one favorite
    // that ALSO exists in the active playlist. The raw DB count would keep
    // the tab pinned to the bottom bar after a playlist switch left stale
    // orphan rows pointing at channel ids that no longer exist - the user
    // would see the tab, tap it, and find an empty "No Favorites" body
    // even though the DB count was non-zero.
    val hasRenderableFavorites = remember(favorites, state.channels) {
        if (favorites.isEmpty() || state.channels.isEmpty()) return@remember false
        val visibleIds = state.channels.asSequence().map { it.id }.toHashSet()
        favorites.any { it.channelId in visibleIds }
    }
    // Dynamic On Demand + DVR tabs (iOS MainTabView.hasVOD / hasRecordings
    // parity). Both ViewModels are hoisted here so the tabs can appear / vanish
    // based on actual content, NOT just the source type. hiltViewModel() resolves
    // to the SAME instance the tab body uses (shared ViewModelStoreOwner), so this
    // adds no duplicate fetch; it just makes the eager load drive tab visibility.
    val onDemandVm: OnDemandViewModel = hiltViewModel()
    val onDemandState by onDemandVm.state.collectAsStateWithLifecycle()
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
        onDemandState.movies.isNotEmpty() ||
            onDemandState.series.isNotEmpty() ||
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
    val hasRecordings = dvrState.recordings.isNotEmpty()
    val tabs = visibleTabs(
        hasFavorites = hasRenderableFavorites,
        hasVod = hasVodContent,
        hasRecordings = hasRecordings,
    )
    val miniPlayerVm: MiniPlayerViewModel = hiltViewModel()
    val miniPlayerState by miniPlayerVm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val exoHolder = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            MainScaffoldEntryPoint::class.java,
        ).exoPlayerHolder()
    }
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
        if (selectedTab !in tabs) selectedTab = AppTab.LiveTV
    }

    // EPG-search guide jump (iOS: MainTabView switches to .liveTV +
    // ChannelListView sets showGuideView=true). When a Search EPG result is
    // tapped (warm path) or an aeriotv://guide deep link is consumed (cold
    // path re-emits through requestGuideJump), force guide mode so EPG cells
    // exist to scroll/focus to, then select the Live TV tab. GuideScreen
    // collects the same SharedFlow (replay=1) to do the scroll + focus.
    LaunchedEffect(Unit) {
        viewModel.guideJumpRequests.collect {
            settingsVm.setDefaultLiveTVView("guide")
            selectedTab = AppTab.LiveTV
            initialTabApplied = true
        }
    }

    // Back from any secondary tab (Favorites / DVR / On Demand / Settings)
    // returns to Live TV instead of exiting the app -- Live TV is the home tab.
    // On Live TV this handler is disabled so Back falls through to the default
    // (mini-player / exit). The Settings sub-screen BackHandler in
    // SettingsTabContent is composed DEEPER and is enabled only while a
    // sub-screen is open, so it takes priority there; this only fires on a
    // tab root.
    androidx.activity.compose.BackHandler(enabled = selectedTab != AppTab.LiveTV) {
        selectedTab = AppTab.LiveTV
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
        val pillRequesters = remember(tabs) { tabs.associateWith { FocusRequester() } }
        // Chrome-collapse channel: long content surfaces (the On Demand grids)
        // set this true while scrolled down so the tab bar shrinks away. See
        // LocalTvChromeCollapsed for why the bar collapses instead of unmounting.
        val chromeCollapsed = remember { mutableStateOf(false) }
        CompositionLocalProvider(
            LocalTvTopNavFocusRequester provides topNavRequester,
            LocalTvChromeCollapsed provides chromeCollapsed,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
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
                        .onFocusChanged { barHasFocus = it.hasFocus }
                        .collapsibleChrome(barFraction),
                ) {
                    TvTopTabBar(
                        tabs = tabs,
                        selected = selectedTab,
                        onSelect = { selectedTab = it; initialTabApplied = true },
                        focusRequester = topNavRequester,
                        lastUpKeyMs = lastUpKeyMs,
                        pillRequesters = pillRequesters,
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
                    selectedTab == AppTab.LiveTV &&
                        miniPlayerState !is MiniPlayerSession.State.Pending -> 40.dp
                    else -> 0.dp
                }
                if (topHintGap > 0.dp) {
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.height(topHintGap),
                    )
                }
                MainTabContent(
                    selectedTab = selectedTab,
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
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 24.dp, top = if (anyBackgroundWork) 60.dp else 18.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                ) {
                    if (miniPlayerState is MiniPlayerSession.State.Active) {
                        TvGuideHintChip("Press Menu/Back or Play/Pause to resume playback.")
                        TvGuideHintChip("Hold right on remote to close the mini player.")
                    }
                    TvGuideHintChip("Double press Menu/Back to return to top channel.")
                    TvGuideHintChip("Hold left on remote to return to the All group pill.")
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // GH #20: observe every tab's scroll for the bottom-bar hide.
                .nestedScroll(bottomBarScrollConnection),
        ) {
            MainTabContent(
                selectedTab = selectedTab,
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
                val miniState = miniPlayerState
                // Phase 139 / audit #22: on TV the mini-player is a top-right
                // video window (TvMiniPlayerOverlay, mounted at NavHost root).
                // Suppress the phone-style card so we don't double-render the
                // same session.
                if (miniState is MiniPlayerSession.State.Active && !isTv) {
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

/**
 * iOS 26 parity: the phone tab bar as a centered, floating, rounded pill
 * (the iPhone app's Liquid-Glass bar) instead of a full-width Material
 * NavigationBar. Wrap-content width, surface fill with the shared accent
 * hairline, selected tab gets a soft accent capsule behind icon + label.
 */
@Composable
private fun FloatingTabBar(
    tabs: List<AppTab>,
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                RoundedCornerShape(32.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val isSel = tab == selected
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else Color.Transparent,
                    )
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = if (isSel) tab.iconSelected else tab.iconUnselected,
                    contentDescription = tab.label,
                    tint = if (isSel) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelSmall,
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
private fun TvGuideHintChip(text: String) {
    Text(
        text = text,
        fontSize = 8.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.9f),
        modifier = Modifier
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
    onChannelClick: (M3UChannel) -> Unit,
    onMovieClick: (String) -> Unit,
    onSeriesClick: (Int) -> Unit,
    onEpisodeResume: (String) -> Unit,
    onResumeMovie: (String) -> Unit,
    onPlayRecording: (String, String) -> Unit,
    onPlayCatchup: (String, String, Long, Long, String, String) -> Unit,
    onLaunchMultiview: () -> Unit,
    onWatchLive: (String, String, Boolean) -> Unit,
    onWatchFromBeginning: (String, String, Boolean) -> Unit,
    onOpenSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        when (selectedTab) {
            AppTab.LiveTV -> LiveTVTabContent(
                onChannelClick = onChannelClick,
                onLaunchMultiview = onLaunchMultiview,
                onOpenSearch = onOpenSearch,
                // Catch-up (task #133/#136): a resolved timeshift URL plays
                // through the recording-player route with programme window +
                // panel tz for the scrubbable timeline.
                onPlayCatchup = onPlayCatchup,
            )
            AppTab.Favorites -> FavoritesTabContent(onChannelClick = onChannelClick)
            AppTab.DVR -> DvrTabContent(
                onPlayRecording = onPlayRecording,
                onWatchLive = onWatchLive,
                onWatchFromBeginning = onWatchFromBeginning,
            )
            AppTab.OnDemand -> OnDemandTabContent(
                onMovieClick = { movie -> onMovieClick(movie.uuid) },
                onSeriesClick = { series -> onSeriesClick(series.id) },
                onEpisodeResume = onEpisodeResume,
                onResumeMovie = onResumeMovie,
                onOpenSearch = onOpenSearch,
            )
            AppTab.Settings -> SettingsTabContent()
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
    lastUpKeyMs: LongArray = longArrayOf(0L),
    pillRequesters: Map<AppTab, FocusRequester> = emptyMap(),
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
        if ((armed || deliberateEntry) && cur != selected) onSelect(cur)
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusGroup()
                .focusProperties {
                    onEnter = {
                        pillRequesters[selected]?.requestFocus()
                    }
                }
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

@Composable
private fun SettingsTabContent() {
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    var addMoreOpen by remember { mutableStateOf(false) }
    var playlistDetailOpen by remember { mutableStateOf(false) }
    var editPlaylistOpen by remember { mutableStateOf(false) }
    var playlistsOpen by remember { mutableStateOf(false) }
    var logViewerOpen by remember { mutableStateOf(false) }
    var addPlaylistStep by remember { mutableStateOf<AddPlaylistStep>(AddPlaylistStep.None) }
    val playlistVm: PlaylistViewModel = hiltViewModel()
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
            addPlaylistStep = AddPlaylistStep.None
        }
    }
    androidx.activity.compose.BackHandler(
        enabled = section != null || addMoreOpen || playlistDetailOpen ||
            editPlaylistOpen || playlistsOpen || logViewerOpen ||
            addPlaylistStep != AddPlaylistStep.None,
    ) {
        when {
            addPlaylistStep is AddPlaylistStep.Configure -> addPlaylistStep = AddPlaylistStep.ChooseType
            addPlaylistStep is AddPlaylistStep.ChooseType -> addPlaylistStep = AddPlaylistStep.None
            playlistsOpen -> playlistsOpen = false
            editPlaylistOpen -> editPlaylistOpen = false
            playlistDetailOpen -> playlistDetailOpen = false
            addMoreOpen -> addMoreOpen = false
            logViewerOpen -> logViewerOpen = false
            else -> section = null
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
    val subScreenKey: String? = when {
        addPlaylistStep is AddPlaylistStep.Configure -> "configure"
        addPlaylistStep is AddPlaylistStep.ChooseType -> "choosetype"
        playlistsOpen -> "playlists"
        editPlaylistOpen -> "edit"
        playlistDetailOpen -> "detail"
        addMoreOpen -> "addmore"
        logViewerOpen -> "log"
        section != null -> "section-$section"
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
    Box(modifier = Modifier.focusRequester(settingsContentFocus).focusGroup()) {
    when {
        addPlaylistStep is AddPlaylistStep.Configure -> ConfigureSourceScreen(
            sourceType = (addPlaylistStep as AddPlaylistStep.Configure).sourceType,
            onBack = { addPlaylistStep = AddPlaylistStep.ChooseType },
            viewModel = playlistVm,
        )
        addPlaylistStep is AddPlaylistStep.ChooseType -> ChooseSourceTypeScreen(
            onBack = { addPlaylistStep = AddPlaylistStep.None },
            onChoose = { type ->
                // Start a FRESH draft so the add creates a NEW row and can't
                // carry over the active server's bootstrap-prefilled API key
                // (which would win over typed user/pass and re-add the active
                // server's account). Mirrors the onboarding CHOOSE_TYPE path.
                playlistVm.startNewSource(type)
                addPlaylistStep = AddPlaylistStep.Configure(type)
            },
        )
        playlistsOpen -> com.aeriotv.android.feature.settings.PlaylistsScreen(
            onBack = { playlistsOpen = false },
            onAddPlaylist = { addPlaylistStep = AddPlaylistStep.ChooseType },
            onOpenPlaylistDetail = { playlistDetailOpen = true },
        )
        editPlaylistOpen -> com.aeriotv.android.feature.settings.EditPlaylistScreen(
            onBack = { editPlaylistOpen = false },
        )
        playlistDetailOpen -> com.aeriotv.android.feature.settings.PlaylistDetailScreen(
            onBack = { playlistDetailOpen = false },
            onEdit = { editPlaylistOpen = true },
        )
        addMoreOpen -> AddMoreCategoriesScreen(onBack = { addMoreOpen = false })
        section == null -> SettingsScreen(
            onSectionClick = { section = it },
            onOpenPlaylistDetail = { playlistDetailOpen = true },
            onOpenPlaylists = { playlistsOpen = true },
            onAddPlaylist = { addPlaylistStep = AddPlaylistStep.ChooseType },
        )
        section == SettingsSection.Appearance -> AppearanceSettingsScreen(
            onBack = { section = null },
            onOpenAddMoreCategories = { addMoreOpen = true },
        )
        section == SettingsSection.AppBehaviors -> AppBehaviorsSettingsScreen(onBack = { section = null })
        section == SettingsSection.Multiview -> MultiviewSettingsScreen(onBack = { section = null })
        section == SettingsSection.Network -> NetworkSettingsScreen(onBack = { section = null })
        section == SettingsSection.AppUpdates ->
            com.aeriotv.android.feature.settings.AppUpdatesScreen(onBack = { section = null })
        section == SettingsSection.Sync -> com.aeriotv.android.feature.settings.SyncSettingsScreen(
            onBack = { section = null },
        )
        section == SettingsSection.DvrSettings -> DvrSettingsScreen(onBack = { section = null })
        logViewerOpen -> com.aeriotv.android.feature.settings.LogViewerScreen(
            onBack = { logViewerOpen = false },
        )
        section == SettingsSection.Developer -> DeveloperSettingsScreen(
            onBack = { section = null },
            onOpenLogViewer = { logViewerOpen = true },
        )
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
}

/** EntryPoint accessor so MainScaffold can drive pause/destroy on the held
 * MPV instance without routing through a ViewModel. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface MainScaffoldEntryPoint {
    fun exoPlayerHolder(): AerioExoPlayerHolder
    fun exoWindowState(): com.aeriotv.android.feature.player.ExoWindowState
}

/** Two-step Add Playlist flow embedded in the Settings tab. None = closed. */
private sealed interface AddPlaylistStep {
    data object None : AddPlaylistStep
    data object ChooseType : AddPlaylistStep
    data class Configure(val sourceType: com.aeriotv.android.core.data.SourceType) : AddPlaylistStep
}
