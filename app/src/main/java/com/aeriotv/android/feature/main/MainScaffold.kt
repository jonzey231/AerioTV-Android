package com.aeriotv.android.feature.main

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aeriotv.android.feature.livetv.rememberLiveTvFormFactor
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.playback.MPVPlayerHolder
import com.aeriotv.android.core.playback.PlaybackService
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
 * `null` on phone shell — the CompositionLocal is only filled on TV.
 */
val LocalTvTopNavFocusRequester = staticCompositionLocalOf<FocusRequester?> { null }

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
    onPlayRecording: (String, String) -> Unit = { _, _ -> },
    onLaunchMultiview: () -> Unit = {},
    onWatchLive: (Int) -> Unit = {},
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favoritesVm: FavoritesViewModel = hiltViewModel()
    val favorites by favoritesVm.all.collectAsStateWithLifecycle(initialValue = emptyList())
    // Show the Favorites tab only when the user has at least one favorite
    // that ALSO exists in the active playlist. The raw DB count would keep
    // the tab pinned to the bottom bar after a playlist switch left stale
    // orphan rows pointing at channel ids that no longer exist — the user
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
    // hasVOD: any movie/series loaded, OR still loading its library. The loading
    // bridge keeps the tab from flickering "absent -> present" on cold launch /
    // source switch; a source that finishes with zero VOD hides the tab entirely.
    val hasVodContent = !onDemandState.unsupportedSource && (
        onDemandState.movies.isNotEmpty() ||
            onDemandState.series.isNotEmpty() ||
            onDemandState.isLoading ||
            onDemandState.isLoadingSeries
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
    val mpvHolder = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            MainScaffoldEntryPoint::class.java,
        ).mpvPlayerHolder()
    }
    // Poll pause state from the held MPV instance while the mini-player is
    // visible so the Pause/Play icon stays accurate when the notification
    // action toggles playback. 500ms cadence is cheap and matches VOD chrome.
    var miniPaused by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(miniPlayerState) {
        if (miniPlayerState !is com.aeriotv.android.feature.miniplayer.MiniPlayerSession.State.Active) return@LaunchedEffect
        while (true) {
            miniPaused = mpvHolder.isPaused()
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
        CompositionLocalProvider(LocalTvTopNavFocusRequester provides topNavRequester) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                TvTopTabBar(
                    tabs = tabs,
                    selected = selectedTab,
                    onSelect = { selectedTab = it; initialTabApplied = true },
                    focusRequester = topNavRequester,
                )
                MainTabContent(
                    selectedTab = selectedTab,
                    onChannelClick = onChannelClick,
                    onMovieClick = onMovieClick,
                    onSeriesClick = onSeriesClick,
                    onEpisodeResume = onEpisodeResume,
                    onPlayRecording = onPlayRecording,
                    onLaunchMultiview = onLaunchMultiview,
                    onWatchLive = onWatchLive,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        // Each tab owns its own TopAppBar with its own status-bar inset. Without
        // overriding here, Scaffold would also add a status-bar top inset to the
        // content padding, producing a ~30dp empty gap above every TopAppBar.
        // Bottom + horizontal insets stay system-managed so the navigation bar
        // sits correctly above the system gesture area.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            androidx.compose.foundation.layout.Column {
                val miniState = miniPlayerState
                // Phase 139 / audit #22: on TV the mini-player is a top-right
                // video window (TvMiniPlayerOverlay, mounted at NavHost root).
                // Suppress the phone-style row above the bottom nav so we
                // don't double-render the same session.
                if (miniState is MiniPlayerSession.State.Active && !isTv) {
                    val channel = miniState.channel
                    val nowProgramme = state.epgByChannel[channel.tvgID]?.nowPlaying()
                    MiniPlayerRow(
                        channel = channel,
                        nowProgramme = nowProgramme,
                        isPaused = miniPaused,
                        onResume = {
                            val resumed = miniPlayerVm.resumeChannel()
                            if (resumed != null) onChannelClick(resumed)
                        },
                        onTogglePause = {
                            mpvHolder.setPaused(!mpvHolder.isPaused())
                            miniPaused = mpvHolder.isPaused()
                        },
                        onDismiss = {
                            miniPlayerVm.dismiss()
                            mpvHolder.destroy()
                            PlaybackService.stop(context)
                        },
                    )
                }
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    tabs.forEach { tab ->
                        val selected = tab == selectedTab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { selectedTab = tab; initialTabApplied = true },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.iconSelected else tab.iconUnselected,
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        MainTabContent(
            selectedTab = selectedTab,
            onChannelClick = onChannelClick,
            onMovieClick = onMovieClick,
            onSeriesClick = onSeriesClick,
            onEpisodeResume = onEpisodeResume,
            onPlayRecording = onPlayRecording,
            onLaunchMultiview = onLaunchMultiview,
            onWatchLive = onWatchLive,
            modifier = Modifier.padding(padding),
        )
    }
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
    onPlayRecording: (String, String) -> Unit,
    onLaunchMultiview: () -> Unit,
    onWatchLive: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        when (selectedTab) {
            AppTab.LiveTV -> LiveTVTabContent(
                onChannelClick = onChannelClick,
                onLaunchMultiview = onLaunchMultiview,
            )
            AppTab.Favorites -> FavoritesTabContent(onChannelClick = onChannelClick)
            AppTab.DVR -> DvrTabContent(
                onPlayRecording = onPlayRecording,
                onWatchLive = onWatchLive,
            )
            AppTab.OnDemand -> OnDemandTabContent(
                onMovieClick = { movie -> onMovieClick(movie.uuid) },
                onSeriesClick = { series -> onSeriesClick(series.id) },
                onEpisodeResume = onEpisodeResume,
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
 * the previously-focused pill rather than the first one — so the user comes
 * back exactly where they left.
 */
@Composable
private fun TvTopTabBar(
    tabs: List<AppTab>,
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    focusRequester: FocusRequester,
) {
    // tvOS-style floating nav: the tabs are grouped into one centered, rounded
    // "segmented" capsule over the app background (no full-width surface toolbar
    // strip), so the bar reads as a polished pill group rather than a heavy bar.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusRestorer()
                .clip(RoundedCornerShape(30.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                TvTab(
                    tab = tab,
                    selected = tab == selected,
                    onFocused = { onSelect(tab) },
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
) {
    var focused by remember { mutableStateOf(false) }
    val background = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val foreground = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(26.dp))
            .background(background)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = if (selected) tab.iconSelected else tab.iconUnselected,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = tab.label,
            color = foreground,
            style = MaterialTheme.typography.titleMedium,
        )
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
    when {
        addPlaylistStep is AddPlaylistStep.Configure -> ConfigureSourceScreen(
            sourceType = (addPlaylistStep as AddPlaylistStep.Configure).sourceType,
            onBack = { addPlaylistStep = AddPlaylistStep.ChooseType },
            viewModel = playlistVm,
        )
        addPlaylistStep is AddPlaylistStep.ChooseType -> ChooseSourceTypeScreen(
            onBack = { addPlaylistStep = AddPlaylistStep.None },
            onChoose = { type -> addPlaylistStep = AddPlaylistStep.Configure(type) },
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
            onEditPlaylist = {
                // Editing requires the row to be active so the existing
                // EditPlaylistScreen (which always edits the active playlist
                // — iOS does the same) has the right context. Switch first,
                // then open the editor.
                if (playlistState.playlist?.id != it.id) playlistVm.switchToPlaylist(it.id)
                editPlaylistOpen = true
            },
        )
        section == SettingsSection.Appearance -> AppearanceSettingsScreen(
            onBack = { section = null },
            onOpenAddMoreCategories = { addMoreOpen = true },
        )
        section == SettingsSection.AppBehaviors -> AppBehaviorsSettingsScreen(onBack = { section = null })
        section == SettingsSection.Multiview -> MultiviewSettingsScreen(onBack = { section = null })
        section == SettingsSection.Network -> NetworkSettingsScreen(onBack = { section = null })
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
    fun mpvPlayerHolder(): MPVPlayerHolder
}

/** Two-step Add Playlist flow embedded in the Settings tab. None = closed. */
private sealed interface AddPlaylistStep {
    data object None : AddPlaylistStep
    data object ChooseType : AddPlaylistStep
    data class Configure(val sourceType: com.aeriotv.android.core.data.SourceType) : AddPlaylistStep
}
