package com.aeriotv.android.feature.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.feature.channels.ChannelRow
import com.aeriotv.android.feature.collections.AddToCollectionFlow
import com.aeriotv.android.feature.collections.CollectionsMenuContext
import com.aeriotv.android.feature.collections.CollectionsViewModel
import com.aeriotv.android.feature.livetv.ProgramInfoSheet
import com.aeriotv.android.feature.livetv.RecordProgramSheet
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.playlist.nowPlaying
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.feature.settings.rememberIsTvDevice
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Favorites tab. Mirrors iOS FavoritesView (ChannelListView.swift:2596): the
 * exact same dense row + long-press menu the Live TV tab uses, scoped to
 * channels the user has starred. Reuses [ChannelRow] (made `internal` for
 * this share), so the currently-playing EPG line, expandable upcoming-list
 * chevron, and the Remove from Favorites / Program Info / Record from Now
 * long-press menu all behave identically on both tabs.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FavoritesTabContent(
    modifier: Modifier = Modifier,
    onChannelClick: (M3UChannel) -> Unit,
    favoritesVm: FavoritesViewModel = hiltViewModel(),
    playlistVm: PlaylistViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val favorites by favoritesVm.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlistState by playlistVm.state.collectAsStateWithLifecycle()
    val palette by settingsVm.categoryPalette.collectAsStateWithLifecycle(
        initialValue = CategoryPaletteState.Default,
    )
    val showChannelLogos by settingsVm.showChannelLogos.collectAsStateWithLifecycle(initialValue = true)
    val showChannelNumbers by settingsVm.showChannelNumbers.collectAsStateWithLifecycle(initialValue = true)
    val isTv = rememberIsTvDevice()
    val favoriteIds by remember(favorites) {
        derivedStateOf { favorites.asSequence().map { it.channelId }.toHashSet() }
    }

    // Join the persisted favorites rows against the currently-loaded playlist
    // channels. Stale rows that no longer match anything fall away here so
    // the tab body never renders an empty placeholder while the bottom-bar
    // count says otherwise (Phase 57 handles tab visibility too).
    val channels = remember(favorites, playlistState.channels) {
        val byId = playlistState.channels.associateBy { it.id }
        favorites.mapNotNull { byId[it.channelId] }
    }

    var programInfoTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }

    // Channel Collections (#45): iOS surfaces "Add to Collection…" +
    // "Remove from All Collections" on favorites rows too. Favorites is never
    // a collection VIEW (iOS clears activeFilterCollectionID on appear), so
    // activeCollectionId is null -> the row only ever offers the "all" remove.
    val collectionsVm: CollectionsViewModel = hiltViewModel()
    val collections by collectionsVm.collections.collectAsStateWithLifecycle(initialValue = emptyList())
    var collectionPickerFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val collectionsMenu = remember(collections) {
        CollectionsMenuContext(
            collections = collections,
            activeCollectionId = null,
            onOpenPicker = { chId, chName, _, _ -> collectionPickerFor = chId to chName },
            onRemoveMember = collectionsVm::removeMember,
            onRemoveFromAll = collectionsVm::removeFromAll,
        )
    }

    // Local working copy so the drag preview updates fluidly without hitting
    // Room on every onMove frame. Committed to the favorites table on drag end
    // via favoritesVm.applyOrder. Re-synced whenever the derived channel list
    // changes (toggle elsewhere, playlist reload). Mirrors PlaylistsScreen +
    // iOS `favoriteOrder`.
    val lazyListState = rememberLazyListState()
    var workingOrder by remember(channels) { mutableStateOf(channels) }
    LaunchedEffect(channels) { workingOrder = channels }
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        workingOrder = workingOrder.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Favorites",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        if (channels.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.StarOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "No Favorites",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Long-press a channel in Live TV and tap Add to Favorites.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            // 104dp bottom clears the MainScaffold NavigationBar so the
            // last favorite stays tappable / draggable.
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = workingOrder, key = { it.id }) { channel ->
                ReorderableItem(reorderState, key = channel.id) { _ ->
                    val programmes = playlistState.epgByChannel[channel.guideMatchKey].orEmpty()
                    val nowProgramme = programmes.nowPlaying()
                    ChannelRow(
                        channel = channel,
                        nowProgramme = nowProgramme,
                        programmes = programmes,
                        isFavorite = channel.id in favoriteIds,
                        onPlay = { onChannelClick(channel) },
                        onToggleFavorite = { favoritesVm.toggle(channel) },
                        onShowProgramInfo = { programInfoTarget = it },
                        onShowRecord = { recordTarget = it },
                        palette = palette,
                        showLogo = showChannelLogos,
                        showNumber = showChannelNumbers,
                        collectionsMenu = collectionsMenu,
                        reorderHandle = {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Drag to reorder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .draggableHandle(
                                        onDragStopped = {
                                            favoritesVm.applyOrder(workingOrder.map { it.id })
                                        },
                                    )
                                    .size(24.dp),
                            )
                        },
                    )
                }
            }
        }
    }

    programInfoTarget?.let { target ->
        ProgramInfoSheet(
            target = target,
            onDismiss = { programInfoTarget = null },
        )
    }
    recordTarget?.let { target ->
        RecordProgramSheet(
            target = target,
            onDismiss = { recordTarget = null },
        )
    }
    collectionPickerFor?.let { (chId, chName) ->
        AddToCollectionFlow(
            channelId = chId,
            channelName = chName,
            isTv = isTv,
            collections = collections,
            onToggleMember = collectionsVm::toggleMember,
            onCreate = collectionsVm::create,
            onClose = { collectionPickerFor = null },
        )
    }
}
