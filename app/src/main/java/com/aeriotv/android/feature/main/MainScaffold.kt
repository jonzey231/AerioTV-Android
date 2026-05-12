package com.aeriotv.android.feature.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.playback.MPVPlayerHolder
import com.aeriotv.android.core.playback.PlaybackService
import com.aeriotv.android.feature.dvr.DvrTabContent
import com.aeriotv.android.feature.favorites.FavoritesTabContent
import com.aeriotv.android.feature.favorites.FavoritesViewModel
import com.aeriotv.android.feature.livetv.LiveTVTabContent
import com.aeriotv.android.feature.miniplayer.MiniPlayerRow
import com.aeriotv.android.feature.miniplayer.MiniPlayerSession
import com.aeriotv.android.feature.miniplayer.MiniPlayerViewModel
import com.aeriotv.android.feature.onboarding.ChooseSourceTypeScreen
import com.aeriotv.android.feature.onboarding.ConfigureSourceScreen
import com.aeriotv.android.feature.ondemand.OnDemandTabContent
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
 * Top-level scaffold once a playlist is loaded. Mirrors iOS MainTabView with the
 * caveat that tabs are CONDITIONAL on content (see [visibleTabs]) - matching
 * iOS, the test-server screenshots show only 4 tabs not 5.
 */
@Composable
fun MainScaffold(
    onChannelClick: (M3UChannel) -> Unit,
    onMovieClick: (String) -> Unit = {},
    onSeriesClick: (Int) -> Unit = {},
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
    val tabs = visibleTabs(state, hasFavorites = hasRenderableFavorites)
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

    // First composition: honour the saved defaultTab if it's in the visible set.
    // After that, user taps on the bottom nav win.
    LaunchedEffect(defaultTabPref, tabs) {
        if (!initialTabApplied && defaultTabPref.isNotEmpty()) {
            val target = AppTab.entries.firstOrNull { it.name == defaultTabPref }
            if (target != null && target in tabs) {
                selectedTab = target
            }
            initialTabApplied = true
        } else if (defaultTabPref.isEmpty()) {
            initialTabApplied = true
        }
    }

    // If the currently-selected tab disappears (e.g. user clears playlist, sourceType
    // changes), fall back to Live TV.
    LaunchedEffect(tabs) {
        if (selectedTab !in tabs) selectedTab = AppTab.LiveTV
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
                if (miniState is MiniPlayerSession.State.Active) {
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
                            onClick = { selectedTab = tab },
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
        when (selectedTab) {
            AppTab.LiveTV -> LiveTVTabContent(
                onChannelClick = onChannelClick,
                modifier = Modifier.padding(padding),
            )
            AppTab.Favorites -> FavoritesTabContent(
                modifier = Modifier.padding(padding),
                onChannelClick = onChannelClick,
            )
            AppTab.DVR -> DvrTabContent(modifier = Modifier.padding(padding))
            AppTab.OnDemand -> OnDemandTabContent(
                modifier = Modifier.padding(padding),
                onMovieClick = { movie -> onMovieClick(movie.uuid) },
                onSeriesClick = { series -> onSeriesClick(series.id) },
            )
            AppTab.Settings -> SettingsTabContent()
        }
    }
}

@Composable
private fun SettingsTabContent() {
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    var addMoreOpen by remember { mutableStateOf(false) }
    var playlistDetailOpen by remember { mutableStateOf(false) }
    var editPlaylistOpen by remember { mutableStateOf(false) }
    var playlistsOpen by remember { mutableStateOf(false) }
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
            editPlaylistOpen || playlistsOpen || addPlaylistStep != AddPlaylistStep.None,
    ) {
        when {
            addPlaylistStep is AddPlaylistStep.Configure -> addPlaylistStep = AddPlaylistStep.ChooseType
            addPlaylistStep is AddPlaylistStep.ChooseType -> addPlaylistStep = AddPlaylistStep.None
            playlistsOpen -> playlistsOpen = false
            editPlaylistOpen -> editPlaylistOpen = false
            playlistDetailOpen -> playlistDetailOpen = false
            addMoreOpen -> addMoreOpen = false
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
        section == SettingsSection.Developer -> DeveloperSettingsScreen(onBack = { section = null })
    }
}

/**
 * Mirror of iOS's dynamic tab-visibility rule. Always-on tabs: Live TV, Settings.
 * Conditional tabs:
 *  - DVR: source type supports recordings (Dispatcharr or Xtream).
 *  - On Demand: source type serves VOD (Dispatcharr or Xtream).
 *  - Favorites: when the user has favorited at least one channel.
 *    Stub for now (always false until the Favorites store lands in Phase 5+).
 *
 * Raw M3U URLs surface only Live TV + Settings - those sources cannot enumerate
 * recordings or VOD catalogues, so the conditional tabs would be empty.
 */
internal fun visibleTabs(
    state: PlaylistViewModel.UiState,
    hasFavorites: Boolean = false,
): List<AppTab> {
    val sourceType = SourceType.entries.firstOrNull { it.name == state.playlist?.sourceType }
        ?: SourceType.M3uUrl
    val sourceServesDvrAndVod = when (sourceType) {
        SourceType.DispatcharrApiKey,
        SourceType.DispatcharrUserPass,
        SourceType.XtreamCodes -> true
        SourceType.M3uUrl -> false
    }
    return buildList {
        add(AppTab.LiveTV)
        if (hasFavorites) add(AppTab.Favorites)
        if (sourceServesDvrAndVod) add(AppTab.DVR)
        if (sourceServesDvrAndVod) add(AppTab.OnDemand)
        add(AppTab.Settings)
    }
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
