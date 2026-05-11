package com.aeriotv.android.feature.main

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.feature.channels.ChannelListScreen
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.settings.SettingsScreen

/**
 * Top-level scaffold once a playlist is loaded. Mirrors iOS MainTabView with the
 * caveat that tabs are CONDITIONAL on content (see [visibleTabs]) - matching
 * iOS, the test-server screenshots show only 4 tabs not 5.
 */
@Composable
fun MainScaffold(
    onChannelClick: (M3UChannel) -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tabs = visibleTabs(state)

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.LiveTV) }

    // If the currently-selected tab disappears (e.g. user clears playlist, sourceType
    // changes), fall back to Live TV.
    LaunchedEffect(tabs) {
        if (selectedTab !in tabs) selectedTab = AppTab.LiveTV
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = {
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
        },
    ) { padding ->
        when (selectedTab) {
            AppTab.LiveTV -> ChannelListScreen(
                onChannelClick = onChannelClick,
                modifierWrap = Modifier.padding(padding),
            )
            AppTab.Favorites -> PlaceholderScreen(
                tabLabel = "Favorites",
                hint = "Pin channels for quick access. Coming with the Favorites phase.",
            )
            AppTab.DVR -> PlaceholderScreen(
                tabLabel = "DVR",
                hint = "Schedule and play recordings. Coming with the DVR phase.",
            )
            AppTab.OnDemand -> PlaceholderScreen(
                tabLabel = "On Demand",
                hint = "Movies and series from your server. Coming with the VOD phase.",
            )
            AppTab.Settings -> SettingsScreen()
        }
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
internal fun visibleTabs(state: PlaylistViewModel.UiState): List<AppTab> {
    val sourceType = SourceType.entries.firstOrNull { it.name == state.playlist?.sourceType }
        ?: SourceType.M3uUrl
    val sourceServesDvrAndVod = when (sourceType) {
        SourceType.DispatcharrApiKey,
        SourceType.DispatcharrUserPass,
        SourceType.XtreamCodes -> true
        SourceType.M3uUrl -> false
    }
    val hasFavorites = false // TODO Phase 5: track favorites store size
    return buildList {
        add(AppTab.LiveTV)
        if (hasFavorites) add(AppTab.Favorites)
        if (sourceServesDvrAndVod) add(AppTab.DVR)
        if (sourceServesDvrAndVod) add(AppTab.OnDemand)
        add(AppTab.Settings)
    }
}
