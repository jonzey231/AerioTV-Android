package com.aeriotv.android.feature.channels

import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import com.aeriotv.android.core.ui.subtitleIsRedundant
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.aeriotv.android.feature.player.MiniPlayerChrome
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import com.aeriotv.android.ui.scale.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.canReplay
import com.aeriotv.android.core.data.db.entity.reminderKey
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.ui.EpgFlagsRow
import com.aeriotv.android.core.ui.LocalShowEpgBadges
import com.aeriotv.android.core.ui.LocalShowProgramSubtitles
import com.aeriotv.android.core.ui.SeasonEpisodePill
import com.aeriotv.android.core.ui.epgFlags
import com.aeriotv.android.core.ui.seasonEpisodeLabel
import com.aeriotv.android.feature.collections.AddToCollectionFlow
import com.aeriotv.android.feature.collections.CollectionPill
import com.aeriotv.android.feature.collections.CollectionsMenuContext
import com.aeriotv.android.feature.collections.CollectionsViewModel
import com.aeriotv.android.feature.favorites.FavoritesViewModel
import com.aeriotv.android.feature.livetv.EmptyGroupNotice
import com.aeriotv.android.feature.livetv.LiveTVViewMode
import com.aeriotv.android.feature.livetv.ManageGroupsSheet
import com.aeriotv.android.feature.livetv.ProgramInfoSheet
import com.aeriotv.android.feature.livetv.RecordProgramSheet
import com.aeriotv.android.feature.multiview.rememberMultiviewStoreHandle
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.playlist.SortMode
import com.aeriotv.android.feature.playlist.nowPlaying
import com.aeriotv.android.feature.reminders.RemindersViewModel
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import kotlinx.coroutines.launch
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(
    onChannelClick: (M3UChannel) -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
    modifierWrap: Modifier = Modifier,
    viewMode: LiveTVViewMode = LiveTVViewMode.List,
    canToggleViewMode: Boolean = false,
    onToggleViewMode: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    /** Catch-up (task #137): play a resolved timeshift URL in the recording
     *  player; same shape as GuideScreen's onPlayCatchup. */
    onPlayCatchup: (
        channelId: String,
        playbackUrl: String,
        title: String,
        progStartMillis: Long,
        progEndMillis: Long,
        panelTz: String,
        channelUuid: String,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val defaultGroupToken by viewModel.defaultGroupToken.collectAsStateWithLifecycle()
    val favoritesVm: FavoritesViewModel = hiltViewModel()
    val favoritesOrNull = favoritesVm.all.collectAsStateWithLifecycle().value
    val favoritesList = favoritesOrNull ?: emptyList()
    val favoriteIds = remember(favoritesList) { favoritesList.map { it.channelId }.toSet() }
    val settingsVm: SettingsViewModel = hiltViewModel()
    val palette by settingsVm.categoryPalette.collectAsStateWithLifecycle(initialValue = CategoryPaletteState.Default)
    val hiddenGroups by settingsVm.hiddenGroups.collectAsStateWithLifecycle(initialValue = emptySet())
    val showChannelLogos by settingsVm.showChannelLogos.collectAsStateWithLifecycle(initialValue = true)
    val showChannelNumbers by settingsVm.showChannelNumbers.collectAsStateWithLifecycle(initialValue = true)
    val groupSortModeRaw by settingsVm.groupSortMode.collectAsStateWithLifecycle(initialValue = "Default")
    val groupOrder by settingsVm.groupOrder.collectAsStateWithLifecycle(initialValue = emptyList())
    val groupSortMode = com.aeriotv.android.feature.livetv.GroupSortMode.from(groupSortModeRaw)
    // Logan 2026-09-14: Recently Watched is a synthetic group, unchecked in
    // Manage Groups until the user shows it; the pref folds into the hidden
    // set so the sheet and the filters keep one mechanism.
    val recentGroupVisible by settingsVm.recentGroupVisible.collectAsStateWithLifecycle()
    val recentChannelIds by settingsVm.recentChannelIds.collectAsStateWithLifecycle(initialValue = emptyList())

    // Catch-up (task #137): resolve a past programme to its timeshift URL,
    // then play; failures surface as a toast (same flow as GuideScreen).
    val listContext = LocalContext.current
    val onCatchupResolve: (M3UChannel, EPGProgramme) -> Unit = { ch, prog ->
        viewModel.playCatchup(ch, prog) { result ->
            result
                .onSuccess { pb ->
                    onPlayCatchup(
                        ch.id, pb.url, prog.title,
                        prog.startMillis, prog.endMillis, pb.panelTimeZoneId,
                        pb.channelUuid.orEmpty(),
                    )
                }
                .onFailure { t ->
                    Toast.makeText(
                        listContext,
                        t.message ?: "Catch-up is unavailable for this programme.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }
    var programInfoTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var manageGroupsOpen by remember { mutableStateOf(false) }
    // Search is a reveal-on-tap button (parity with Guide view), not an
    // always-on field, so the channel list gets full height until the user
    // opts into searching. Closing it clears the query.
    var searchActive by remember { mutableStateOf(false) }
    com.aeriotv.android.ui.search.CloseSearchOnLeave(searchActive) {
        searchActive = false
        viewModel.onSearchQueryChange("")
    }
    val isTv = rememberIsTvDevice()
    // Phone group selector (Logan 2026-09-05, Apple parity): the slide-in
    // drawer by default, the pill strip when the user picks "pills". TV keeps
    // its own guideGroupSelector; this never reads it.
    val phoneGroupSelector by settingsVm.phoneGroupSelector.collectAsStateWithLifecycle(initialValue = "sidebar")
    val phoneSidebarMode = !isTv && phoneGroupSelector != "pills"
    var phoneDrawerOpen by remember { mutableStateOf(false) }
    // Multiview staging for the phone row menu (same store the guide's cell
    // menu toggles).
    val multiviewStore = rememberMultiviewStoreHandle()
    val stagedMultiview by multiviewStore.selected.collectAsStateWithLifecycle(initialValue = emptyList())

    // Channel Collections (#45): user-named channel groupings as extra filter
    // pills + row-menu actions. Mirrors the guide's wiring; both screens share
    // the "collection:<id>" selectedGroup sentinel, so a collection picked in
    // one view stays active in the other.
    val collectionsVm: CollectionsViewModel = hiltViewModel()
    val miniPlayerVm: com.aeriotv.android.feature.miniplayer.MiniPlayerViewModel = hiltViewModel()
    val miniState by miniPlayerVm.state.collectAsStateWithLifecycle()
    val miniActive = miniState is com.aeriotv.android.feature.miniplayer.MiniPlayerSession.State.Active
    val collections by collectionsVm.collections.collectAsStateWithLifecycle()
    var collectionPickerFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val collectionsMenu = remember(collections, state.selectedGroup) {
        CollectionsMenuContext(
            collections = collections,
            activeCollectionId = ChannelCollection.idFromToken(state.selectedGroup),
            onOpenPicker = { chId, chName, _, _ -> collectionPickerFor = chId to chName },
            onRemoveMember = collectionsVm::removeMember,
            onRemoveFromAll = collectionsVm::removeFromAll,
        )
    }

    // Preserve the order groups appear in the source channel list. iOS does
    // this on the parse step (HomeView.swift `fetchM3U` lines 1869-1872) — an
    // empty array seeded with the first-seen groupTitle per channel and
    // `firstIndex(of:)` mapped onto each ChannelDisplayItem.categoryOrder.
    // Earlier Android revisions sorted alphabetically, which scrambled
    // Dispatcharr's curated group ordering. Sequence.distinct() preserves
    // encounter order so dropping the .sortedBy gets us iOS-matching behavior.
    val allGroupsRaw by remember(state.channels, groupSortMode, groupOrder, favoriteIds.isNotEmpty()) {
        derivedStateOf {
            val sourceOrder = state.channels.asSequence()
                .map { it.groupTitle }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            // Apply the user's Manage Groups sort preference (Default / A-Z /
            // Manual) on top of the source order.
            com.aeriotv.android.feature.livetv.orderGroups(
                sourceOrder, groupSortMode, groupOrder,
                hasFavorites = favoriteIds.isNotEmpty(), hasRecent = true,
            )
        }
    }

    val effectiveHidden = remember(hiddenGroups, recentGroupVisible) {
        if (recentGroupVisible) hiddenGroups
        else hiddenGroups + PlaylistViewModel.RECENT_GROUP
    }

    val groups by remember(allGroupsRaw, effectiveHidden) {
        derivedStateOf {
            // Drop any provider group literally named "All" -- it collides with
            // the ALL_GROUPS sentinel and crashes the pill LazyRow on a
            // duplicate key (#45 review).
            val visible = allGroupsRaw.filterNot { it in effectiveHidden }
            com.aeriotv.android.feature.livetv.groupTokens(visible, effectiveHidden)
        }
    }
    // GH #80: All can be hidden; a stranded selection lands on the first pill.
    // GH #81: hold the reset while the favorites table is still loading, or a
    // restored Favorites selection is discarded before it can ever be shown.
    LaunchedEffect(groups, state.selectedGroup, favoritesOrNull == null) {
        val sel = state.selectedGroup
        val favoritesPending = sel == PlaylistViewModel.FAVORITES_GROUP && favoritesOrNull == null
        if (sel !in groups && !sel.startsWith(ChannelCollection.TOKEN_PREFIX) &&
            !favoritesPending && allGroupsRaw.isNotEmpty()
        ) {
            viewModel.onGroupSelected(
                com.aeriotv.android.feature.livetv.fallbackGroupToken(groups),
                persist = false,
            )
        }
    }

    // E-1 stage 2 (perf campaign): shared off-main pipeline; see
    // computeDisplayChannels and the twin call site in GuideScreen.
    val filtered by androidx.compose.runtime.produceState(
        initialValue = com.aeriotv.android.feature.livetv.computeDisplayChannels(
            state.channels, state.selectedGroup, state.searchQuery, state.sortMode,
            allGroupsRaw, groupSortMode, effectiveHidden, favoriteIds, collections, recentChannelIds,
        ),
        state.channels, state.selectedGroup, state.searchQuery, state.sortMode,
        allGroupsRaw, groupSortMode, effectiveHidden, favoriteIds, collections, recentChannelIds,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.aeriotv.android.feature.livetv.computeDisplayChannels(
                state.channels, state.selectedGroup, state.searchQuery, state.sortMode,
                allGroupsRaw, groupSortMode, effectiveHidden, favoriteIds, collections, recentChannelIds,
            )
        }
    }

    // #45: the Add-to-Collection picker + chained New Collection name dialog,
    // opened from a channel row's long-press menu. Dialogs render in their own
    // window, so composing here (outside the Column) is layout-neutral.
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

    // #45: collection pills join the group row -- placement "beginning"
    // renders before All, "end" after the last group. Hoisted so the TV row,
    // the phone header strip and the phone drawer all use it.
    val collectionPillItem: @Composable (ChannelCollection) -> Unit = { c ->
        val token = ChannelCollection.token(c.id)
        CollectionPill(
            collection = c,
            selected = state.selectedGroup == token,
            isTv = isTv,
            onSelect = { viewModel.onGroupSelected(token) },
            onSetPlacement = { p -> collectionsVm.setPlacement(c.id, p) },
            onDelete = {
                if (state.selectedGroup == token) {
                    viewModel.onGroupSelected(PlaylistViewModel.ALL_GROUPS)
                }
                collectionsVm.delete(c.id)
            },
        )
    }
    // Phone drawer rows: collections where their pill placement puts them,
    // around the Favorites / All / group tokens (Apple PhoneGroupDrawer).
    val drawerTokens = remember(groups, collections) {
        collections.filter { it.placement == ChannelCollection.PLACEMENT_BEGINNING }.map { ChannelCollection.token(it.id) } +
            groups +
            collections.filter { it.placement != ChannelCollection.PLACEMENT_BEGINNING }.map { ChannelCollection.token(it.id) }
    }
    val groupLabelFor: (String) -> String = { token ->
        com.aeriotv.android.feature.livetv.groupDisplayName(token, collections)
    }

    Box(modifier = modifierWrap.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Phone / tablet: NO title bar (Logan 2026-09-05, Apple parity with
        // ChannelListView.swift:395, the tab bar already says where we are).
        // One header row carries the groups control, the pills or the active
        // group name, Search, Sort and the List / Guide toggle. It takes the
        // status-bar inset itself so nothing sits under the clock. Android TV
        // has no bar either: its controls live on the group-pill row below.
        // Keep Recent Channels Live: header indicator (shared with the Guide).
        val retainedVm: com.aeriotv.android.feature.livetv.RetainedChannelsViewModel = hiltViewModel()
        if (!isTv) com.aeriotv.android.feature.livetv.LiveTvPhoneHeaderRow(
            sidebarMode = phoneSidebarMode,
            activeGroupLabel = groupLabelFor(state.selectedGroup),
            channelCount = filtered.size,
            onOpenGroups = { phoneDrawerOpen = true },
            hiddenGroupsCount = hiddenGroups.size,
            onManageGroups = { manageGroupsOpen = true },
            // Also show the pill strip when collections exist even if there is
            // only the "All" group, else a collection filter is inescapable on
            // a groupless playlist (#45 review). Mirrors GuideScreen.
            showPills = groups.size > 1 || collections.isNotEmpty() || hiddenGroups.isNotEmpty(),
            groups = groups,
            selectedGroup = state.selectedGroup,
            onSelectGroup = { viewModel.onGroupSelected(it) },
            defaultToken = defaultGroupToken,
            onSetDefault = viewModel::setDefaultGroup,
            collections = collections,
            collectionPillItem = collectionPillItem,
            searchActive = searchActive,
            onToggleSearch = {
                searchActive = !searchActive
                if (!searchActive) viewModel.onSearchQueryChange("")
            },
            modifier = Modifier.statusBarsPadding(),
            extraActions = {
                com.aeriotv.android.feature.livetv.RetainedChannelsAction(
                    viewModel = retainedVm,
                    buttonSize = 38.dp,
                    iconSize = 18.dp,
                    onJumpToChannel = { id ->
                        state.channels.firstOrNull { it.id == id }?.let(onChannelClick)
                    },
                )
            },
            sortMode = state.sortMode,
            onSortModeChange = viewModel::onSortModeChange,
            canToggleViewMode = canToggleViewMode,
            showingGuide = viewMode == LiveTVViewMode.Guide,
            onToggleViewMode = onToggleViewMode,
        )

        if (searchActive) OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text("Search channels") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            // Trailing X clears the query in one tap. iOS UISearchBar has
            // this for free; Compose's OutlinedTextField doesn't, so we
            // surface it conditionally on non-empty input.
            trailingIcon = if (state.searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else null,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
            ),
        )

        // Audit task #51 final piece: collapse the chip Row when the user
        // scrolls past the top of the channel list, restore it when they
        // scroll back to row 0. Listening on the LazyColumn's
        // firstVisibleItemIndex via a hoisted state. The
        // AnimatedVisibility's expand/shrink-vertically gives a smooth
        // height collapse without snapping. Stays expanded on a
        // not-yet-scrolled list (initial state).
        val listState = rememberLazyListState()
        // Re-tap of the Live TV tab while the list view is showing
        // (TabReselect): back to the first channel. Phone and tablet only; TV
        // never emits the event.
        com.aeriotv.android.feature.main.OnTabReselect(
            com.aeriotv.android.feature.main.AppTab.LiveTV,
        ) { listState.animateScrollToItem(0) }
        val chipsVisible by remember {
            derivedStateOf { listState.firstVisibleItemIndex == 0 }
        }
        // Phone pills now live inside the header row above (no collapse on
        // scroll: the header is fixed, as on iOS). TV keeps its own pill row
        // because the Sort / Manage Groups circles live in it.
        if (isTv) AnimatedVisibility(
            visible = chipsVisible,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // On Android TV the List has no top app bar, so Sort leads the
                // pill row. TV guide cleanup (Logan 2026-08-06): the Guide-switch
                // circle is gone (Settings > Default Live TV View is the switch)
                // and the channel-search circle too (the nav bar's Search tab is
                // the one search entry on TV). Sort stays - nothing else covers
                // Number / Name / Favorites ordering - and the Tune circle below
                // stays as TV's only Manage Groups entry until hidden groups
                // sync across devices.
                if (isTv) {
                    item {
                        SortMenu(
                            currentMode = state.sortMode,
                            onSelect = viewModel::onSortModeChange,
                            circular = true,
                        )
                    }
                }
                item {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .clickable { manageGroupsOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = if (hiddenGroups.isEmpty())
                                "Manage groups"
                            else
                                "Manage groups (${hiddenGroups.size} hidden)",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        // iOS parity: warning dot in the top-right indicates
                        // the user has at least one group hidden. Without
                        // this, a user who hid groups months ago has no
                        // visual cue to remember why the chip list looks
                        // short. ManageGroupsButton in Aerio/Design/Components
                        // /ManageGroupsSheet.swift uses the same pattern.
                        if (hiddenGroups.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 6.dp, end = 6.dp)
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFFFFA502)),
                            )
                        }
                    }
                }
                items(
                    collections.filter { it.placement == ChannelCollection.PLACEMENT_BEGINNING },
                    key = { "coll_${it.id}" },
                ) { c -> collectionPillItem(c) }
                items(groups, key = { "grp_$it" }) { group ->
                    // Same capsule treatment as the Guide's phone pills (user
                    // report: the two views' pills didn't match; Material's
                    // default FilterChip is an 8dp rounded rect).
                    FilterChip(
                        selected = state.selectedGroup == group,
                        onClick = { viewModel.onGroupSelected(group) },
                        label = { Text(com.aeriotv.android.feature.livetv.groupDisplayName(group), style = MaterialTheme.typography.labelLarge) },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = state.selectedGroup == group,
                        ),
                    )
                }
                items(
                    collections.filter { it.placement != ChannelCollection.PLACEMENT_BEGINNING },
                    key = { "coll_${it.id}" },
                ) { c -> collectionPillItem(c) }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.surfaceVariant,
            thickness = 0.5.dp,
        )

        // Audit task #51 (partial): pull-to-refresh on the Live TV list.
        // Drags the spinner from the top of the LazyColumn and invokes
        // PlaylistViewModel.refreshPlaylist(), which re-fetches the channel
        // list and the EPG. The spinner stays visible while state.isLoading
        // is true. Pull-to-refresh is a TOUCH idiom, so on Android TV it is
        // skipped entirely (no pull gesture, and the indicator otherwise hangs
        // stuck mid-screen while loading); TV renders the list bare. Phone /
        // tablet keep the swipe-to-refresh affordance.
        // Apple GH #55 (jayegles): swiping left/right on the LIST cycles the
        // selected group pill (left = next, right = previous, clamped at the
        // ends). detectHorizontalDragGestures only wins the pointer contest
        // when the gesture is predominantly horizontal (horizontal touch
        // slop), so vertical scrolling and pull-to-refresh are untouched.
        // Touch idiom: phone/tablet only. A collection filter isn't part of
        // the cycle; the first swipe from one lands on All.
        val currentGroupForSwipe by rememberUpdatedState(state.selectedGroup)
        // 2026-07-12: interactive drag - the list follows the finger while a
        // horizontal group swipe is in flight (rubber-banded when there's no
        // group on that side), and a committed swipe pages the old list
        // off-screen before the new group's list slides in from the opposite
        // edge, so it reads as dragging between pages instead of an instant
        // filter change. Mirrored on iOS.
        val groupDragOffset = remember { androidx.compose.animation.core.Animatable(0f) }
        val groupSwipeScope = rememberCoroutineScope()
        // The translation lives on the LazyColumn; the gesture detector lives
        // on the NON-translated PullToRefreshBox ancestor below. Putting both
        // on the same node broke the drag mid-flight: graphicsLayer shifts the
        // node's local coordinate space, so each snapTo re-mapped the
        // stationary finger and corrupted the drag deltas.
        val groupSwipeModifier = if (isTv) Modifier.fillMaxSize()
        else Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = groupDragOffset.value }
        val groupSwipeGestureModifier = if (isTv) Modifier.fillMaxSize()
        else Modifier
            .fillMaxSize()
            .pointerInput(groups) {
                var totalX = 0f
                // True when a swipe in that direction has a group to land on.
                fun canCycle(forward: Boolean): Boolean {
                    if (groups.size < 2) return false
                    val current = groups.indexOf(currentGroupForSwipe).coerceAtLeast(0)
                    val target = if (forward) current + 1 else current - 1
                    return target in 0..groups.lastIndex
                }
                detectHorizontalDragGestures(
                    onDragStart = { totalX = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        totalX += dragAmount
                        val followed = if (canCycle(forward = totalX < 0)) totalX else totalX / 3f
                        groupSwipeScope.launch { groupDragOffset.snapTo(followed) }
                    },
                    onDragCancel = {
                        groupSwipeScope.launch {
                            groupDragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                        }
                    },
                    onDragEnd = {
                        val threshold = 80.dp.toPx()
                        val forward = totalX < 0
                        val commit = kotlin.math.abs(totalX) >= threshold && canCycle(forward)
                        val width = size.width.toFloat()
                        groupSwipeScope.launch {
                            if (!commit) {
                                groupDragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                return@launch
                            }
                            // Page the old list out, swap the group off-screen,
                            // slide the new one in from the opposite edge.
                            groupDragOffset.animateTo(
                                if (forward) -width else width,
                                tween(durationMillis = 120, easing = FastOutLinearInEasing),
                            )
                            val current = groups.indexOf(currentGroupForSwipe).coerceAtLeast(0)
                            val target = (if (forward) current + 1 else current - 1)
                                .coerceIn(0, groups.lastIndex)
                            if (groups[target] != currentGroupForSwipe) {
                                viewModel.onGroupSelected(groups[target])
                            }
                            groupDragOffset.snapTo(if (forward) width else -width)
                            groupDragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.9f))
                        }
                    },
                )
            }
        val channelList: @Composable () -> Unit = {
            LazyColumn(
                state = listState,
                modifier = groupSwipeModifier,
                // 104dp bottom clears the MainScaffold NavigationBar so the
                // final channel row stays fully visible above the tab bar.
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = LocalTabBarBottomInset.current,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // GH #72: an empty group is a legitimate state (a provider
                // group nothing has been assigned to yet), not an error, so it
                // gets a notice with a way out rather than a blank list. The
                // pill row stays on screen here, so this is comfort rather
                // than rescue; the guide is where it is load-bearing.
                if (filtered.isEmpty()) {
                    item(key = "list.emptyGroup") {
                        EmptyGroupNotice(
                            isSearching = state.searchQuery.isNotBlank(),
                            onShowAllChannels = {
                                viewModel.onGroupSelected(PlaylistViewModel.ALL_GROUPS)
                            },
                        )
                    }
                }
                // Key by url, NOT id: id is "m3u:<tvg-id>", and providers assign
                // the same tvg-id to multiple distinct channels, so id is not
                // unique. Compose LazyColumn hard-crashes on a duplicate key. url
                // is unique per stream (GH #31 follow-up crash fix).
                items(items = filtered, key = { it.url }) { channel ->
                    val programmes = state.epgByChannel[channel.guideMatchKey].orEmpty()
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
                        inMultiview = stagedMultiview.any { it.id == channel.id },
                        onToggleMultiview = if (channel.url.isNotBlank()) {
                            { multiviewStore.toggle(channel) }
                        } else {
                            null
                        },
                        onWatchPast = if (channel.hasCatchup) {
                            { prog -> onCatchupResolve(channel, prog) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        // Corner mini Active on TV: give it its own slot above the list, the
        // same rule the guide applies above its timeline (Logan 2026-09-13).
        // The reserved band's measured bottom is the list's top and the mini's
        // bottom edge, so channel rows never start under the mini.
        val reserveMiniSlot = isTv && miniActive
        if (reserveMiniSlot) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MiniPlayerChrome.miniHeight + MiniPlayerChrome.miniGap)
                    .onGloballyPositioned {
                        MiniPlayerChrome.timelineTopPx.value = it.boundsInRoot().bottom
                    },
            )
        }
        DisposableEffect(reserveMiniSlot) {
            onDispose { if (reserveMiniSlot) MiniPlayerChrome.timelineTopPx.value = 0f }
        }
        if (isTv) {
            channelList()
        } else {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { viewModel.refreshPlaylist() },
                modifier = groupSwipeGestureModifier,
            ) {
                channelList()
            }
        }
    }
    if (!isTv) com.aeriotv.android.feature.livetv.PhoneGroupDrawerHost(
        open = phoneDrawerOpen,
        onDismiss = { phoneDrawerOpen = false },
        tokens = drawerTokens,
        selected = state.selectedGroup,
        defaultToken = defaultGroupToken,
        labelFor = groupLabelFor,
        onSelect = { viewModel.onGroupSelected(it) },
        // A drag in the drawer is a manual order (the saved order is only
        // read in Manual mode), so the mode flips with the first drag.
        onReorder = { order ->
            settingsVm.setGroupSortMode(com.aeriotv.android.feature.livetv.GroupSortMode.Manual.name)
            settingsVm.setGroupOrder(order)
        },
        onManageGroups = { phoneDrawerOpen = false; manageGroupsOpen = true },
        onSetDefault = viewModel::setDefaultGroup,
        hiddenGroupCount = hiddenGroups.size,
    )
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
    if (manageGroupsOpen) {
        ManageGroupsSheet(
            allGroups = allGroupsRaw,
            hiddenGroups = effectiveHidden,
            onSave = { committed ->
                com.aeriotv.android.feature.livetv.applyManagedGroups(
                    committed, hiddenGroups, recentGroupVisible,
                    setHiddenGroups = { settingsVm.setHiddenGroups(it) },
                    setRecentVisible = { settingsVm.setRecentGroupVisible(it) },
                )
            },
            onDismiss = { manageGroupsOpen = false },
            reorderEnabled = true,
            sortMode = groupSortMode,
            onSortModeChange = { settingsVm.setGroupSortMode(it.name) },
            onReorder = { settingsVm.setGroupOrder(it) },
        )
    }
}

/** Round 36dp control button used on the Android TV List control row (Guide
 *  toggle / Search / Sort), styled to match the Filter (Manage Groups) circle. */
@Composable
private fun ListControlCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Shared with GuideScreen's phone app bar, hence not private. The size
 *  params let LiveTvTopBar's shrink-to-fit math drive the button. */
@Composable
internal fun SortMenu(
    currentMode: SortMode,
    onSelect: (SortMode) -> Unit,
    circular: Boolean = false,
    buttonSize: androidx.compose.ui.unit.Dp? = null,
    iconSize: androidx.compose.ui.unit.Dp? = null,
    /** Phone header (Apple phoneCircle): the 38dp onBackground-8% circle. */
    phoneCircle: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        if (phoneCircle) {
            com.aeriotv.android.feature.livetv.LiveTvPhoneCircle(
                icon = Icons.Filled.SwapVert,
                contentDescription = "Sort channels",
                onClick = { expanded = true },
            )
        } else if (circular) {
            ListControlCircle(
                icon = Icons.Filled.SwapVert,
                contentDescription = "Sort channels",
                onClick = { expanded = true },
            )
        } else {
            IconButton(
                onClick = { expanded = true },
                modifier = if (buttonSize != null) Modifier.size(buttonSize) else Modifier,
            ) {
                Icon(
                    imageVector = Icons.Filled.SwapVert,
                    contentDescription = "Sort channels",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = if (iconSize != null) Modifier.size(iconSize) else Modifier,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    // Leading checkmark on the active mode mirrors iOS
                    // SwiftUI Menu's Label("...", systemImage: "checkmark")
                    // pattern. Without this, the only signal for the active
                    // mode was the primary-tint label, which was easy to
                    // miss against the dark menu surface.
                    leadingIcon = if (mode == currentMode) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else null,
                    text = {
                        Text(
                            text = mode.label,
                            color = if (mode == currentMode)
                                MaterialTheme.colorScheme.textAccent
                            else
                                MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (mode == currentMode) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Channel row matching iOS Live TV canon: dense [#][logo] title + EPG metadata
 * stack, with time-remaining + more + chevron on the right. Tap plays. Long-press
 * opens the iOS-canon menu (Favorites / Program Info / Record from Now). Chevron
 * toggles an inline upcoming-programmes panel.
 */
// Phase 57b: bumped to `internal` so the Favorites tab can reuse the
// same dense row + EPG-aware long-press menu the Live TV list uses.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChannelRow(
    channel: M3UChannel,
    nowProgramme: EPGProgramme?,
    programmes: List<EPGProgramme>,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowProgramInfo: (ProgramInfoTarget) -> Unit,
    onShowRecord: (ProgramInfoTarget) -> Unit,
    palette: CategoryPaletteState,
    /** iOS Issue #28: when false the channel logo is omitted so long channel
     *  names use the full row width. */
    showLogo: Boolean = true,
    /** GH #19: when false the channel-number column is omitted, same idea as
     *  showLogo. */
    showNumber: Boolean = true,
    /**
     * Optional leading drag handle, rendered at the very start of the row
     * before the channel number. Only the Favorites tab passes this (to back
     * its drag-to-reorder gesture); Live TV leaves it null so the row is
     * unchanged there. The handle owns the drag gesture so tap-to-play +
     * long-press-menu on the rest of the row stay intact.
     */
    reorderHandle: (@Composable () -> Unit)? = null,
    /** #45: collection actions for the long-press menu (null = not offered,
     *  e.g. the Favorites tab). */
    collectionsMenu: CollectionsMenuContext? = null,
    /** Catch-up (task #137): non-null when this channel has an archive; the
     *  expanded schedule panel then lists recently aired programmes with a
     *  Watch action on the replayable ones. */
    onWatchPast: ((EPGProgramme) -> Unit)? = null,
    /** Phone row menu (Apple cardMenuItems): Add to / Remove from Multiview.
     *  Null = not offered (Favorites tab, no stream URL). */
    onToggleMultiview: (() -> Unit)? = null,
    inMultiview: Boolean = false,
) {
    // iPhone-shaped row on phone and tablet (time on the title line, italic
    // sub-title line); TV keeps the fixed-slot layout.
    val phoneRow = !rememberIsTvDevice()
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val menuGuard = com.aeriotv.android.core.tv.rememberTvMenuGuard()
    val isTv = rememberIsTvDevice()
    // Channel List Options -> Show Channel Names. Hides the name line in the
    // row AND drives how much room the logo slot claims, the same signal the
    // guide rail reads.
    val showChannelName = com.aeriotv.android.core.ui.LocalGuideRailPrefs.current.names
    // Both the number column and the name are hidden: the logo takes the whole
    // row height.
    val growLogoFull = showLogo && !showNumber && !showChannelName

    // Channel-number column width. Measured from the widest number this row
    // could have to show, at the CURRENT Text Size, so the number is always one
    // line and is never truncated. The floor is [ROW_NUMBER_REFERENCE] (four
    // digits plus the .5 sub-channel form); a longer number widens its own row
    // rather than clipping. Text measurement is cheap and cached by the
    // measurer, and the result is remembered per number + text style.
    val numberStyle = MaterialTheme.typography.labelMedium
    val numberMeasurer = rememberTextMeasurer()
    val numberDensity = LocalDensity.current
    val numberText = channel.channelNumber.orEmpty()
    val numberColumnWidth = remember(numberText, numberStyle, numberDensity) {
        val widest =
            if (numberText.length > ROW_NUMBER_REFERENCE.length) numberText
            else ROW_NUMBER_REFERENCE
        val measured = numberMeasurer.measure(
            text = widest,
            style = numberStyle,
            maxLines = 1,
            softWrap = false,
        ).size.width
        with(numberDensity) { measured.toDp() }
    }

    // UNIFORM ROW HEIGHT (Logan 2026-09-16). Every Live TV row is the height
    // of the TALLEST layout - name line, program title, subtitle line, two
    // description lines, the 4 dp gap and the 2 dp progress bar - and a row
    // with less text leaves those slots blank instead of collapsing. Uniform
    // rows mean uniform logo slots, so an event logo is the same size in a
    // row with a long description and in a row with none.
    //
    // Derived from the SAME text styles the row renders with, measured at the
    // current Text Size / Subtext Size, never a hardcoded dp: 150 percent
    // still fits because the measurement grows with it.
    val nameSlotStyle = MaterialTheme.typography.bodyLarge
    val titleSlotStyle = MaterialTheme.typography.bodyMedium
    val bodySlotStyle = MaterialTheme.typography.bodySmall.subtext()
    val subtitlesOn = LocalShowProgramSubtitles.current
    val rowSlots = remember(
        nameSlotStyle, titleSlotStyle, bodySlotStyle, numberDensity,
        showChannelName, phoneRow, subtitlesOn,
    ) {
        fun lines(style: androidx.compose.ui.text.TextStyle, count: Int): Int =
            numberMeasurer.measure(
                text = List(count) { " " }.joinToString("\n"),
                style = style,
                maxLines = count,
            ).size.height
        // Names off: the slot still has to clear the favorite star / catch-up
        // clock, which some rows have and some do not.
        val name = if (showChannelName) lines(nameSlotStyle, 1)
        else with(numberDensity) { ROW_ICON_LINE.roundToPx() }
        val subtitle = if (phoneRow && subtitlesOn) lines(bodySlotStyle, 1) else 0
        val total = name + lines(titleSlotStyle, 1) + subtitle + lines(bodySlotStyle, 2) +
            with(numberDensity) { (ROW_PROGRESS_GAP + ROW_PROGRESS_HEIGHT).roundToPx() }
        with(numberDensity) { ChannelRowSlots(name.toDp(), subtitle > 0, total.toDp()) }
    }

    // LEADING COLUMN WIDTH. The number now sits UNDER the logo, so no state
    // has a separate number column and the logo always gets the whole width.
    //  - both hidden (no number, no name): the full-bleed slot, unchanged.
    //  - logo present: the width the old number column + gap + logo slot
    //    occupied together, so the row's overall layout and the text column
    //    are untouched while the logo itself gets noticeably larger.
    //  - number only (logos off): just the measured number, nothing wider.
    val leadColumnWidth = when {
        !showLogo -> numberColumnWidth
        growLogoFull -> ROW_LOGO_FULL_WIDTH
        // TV: the number column sits BESIDE the logo rather than under it, so
        // the leading column has to carry both (the stacked phone width plus
        // the measured number column and its gap).
        isTv && showNumber ->
            ROW_LEAD_WIDTH + numberColumnWidth + com.aeriotv.android.core.ui.CHANNEL_BADGE_NUMBER_GAP
        else -> ROW_LEAD_WIDTH.coerceAtLeast(numberColumnWidth)
    }
    // Shared by the phone menu item and the TV dialog action: record the
    // now-airing programme, or a 1-hour ad-hoc block when EPG is missing.
    val recordFromMenu = {
        val now = System.currentTimeMillis()
        val target = nowProgramme?.toInfoTarget(channel.name, channel.dispatcharrChannelId)
            ?: ProgramInfoTarget(
                channelName = channel.name,
                title = "${channel.name} live recording",
                startMillis = now,
                endMillis = now + 3_600_000L,
                description = "",
                category = "",
                channelDispatcharrId = channel.dispatcharrChannelId,
            )
        onShowRecord(target)
    }

    // Category gradient runs cyan-of-card → category-tint when a now-playing
    // programme has a recognised category and the user has Category Colors on.
    // Mirrors iPhone canon footer: "cards tint via gradient" — colours the
    // accent edge without flattening the surface.
    //
    // Dispatcharr's bulk EPG grid drops `<category>` so nowProgramme.category
    // is typically blank for Dispatcharr-sourced playlists; the channel's
    // groupTitle ("Sports", "Movies HD", "Kids", "News" etc.) is passed as a
    // fallback so the row still tints when the program-level category isn't
    // available. The per-program lazy fetch in ProgramInfoSheet still wins
    // when the user opens the detail sheet.
    val tint = palette.tintFor(
        rawCategory = nowProgramme?.category,
        isLive = true,
        fallback = channel.groupTitle,
    )
    val baseSurface = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    // iOS parity (ChannelListView row card): the card itself is FLAT, and the
    // category tint is a leading-edge wash that fades out by ~65% of the
    // width (bucket 0.30 -> 0.18 @22% -> 0.06 @45% -> clear @65%), not a
    // full-width gradient into the surface color. The old full-span gradient
    // is what made tinted rows read as "colored cards" instead of cards with
    // a category cue, a visible chunk of the busier-than-iOS feel.
    val tintWash = tint?.let {
        Brush.horizontalGradient(
            0.00f to it.copy(alpha = 0.30f),
            0.22f to it.copy(alpha = 0.18f),
            0.45f to it.copy(alpha = 0.06f),
            0.65f to Color.Transparent,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            // No focus pop-out on TV: the channel row keeps its size and shows
            // focus via the primary border below (a scaled-up list row reads as
            // jumpy at 10 feet). Phone rows never focus, so this was a no-op there.
            .clip(RoundedCornerShape(CHANNEL_ROW_CORNER))
            .background(baseSurface)
            .then(if (tintWash != null) Modifier.background(tintWash) else Modifier)
            .then(
                // D-pad focus ring for the TV List view (the guide is the TV
                // default, but the List/Guide toggle reaches this row too).
                // At rest every card gets iOS's borderSubtle hairline
                // (accent at 0.10) so cards are defined by an edge, not a
                // fill contrast.
                if (focused) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(CHANNEL_ROW_CORNER),
                ) else Modifier.border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    RoundedCornerShape(CHANNEL_ROW_CORNER),
                ),
            ),
    ) {
        Box {
            // IntrinsicSize.Min makes the row measure to the (unchanged) info
            // column, so the row height is still driven by the text column and
            // is identical to before. The leading logo/number column then
            // fills exactly that height.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .combinedClickable(
                        // menuGuard.wrap: the spurious OK-release after a TV
                        // long-press can land back on this row and would
                        // otherwise start playback.
                        onClick = menuGuard.wrap(onPlay),
                        onLongClick = { menuOpen = true; menuGuard.arm() },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (reorderHandle != null) {
                    reorderHandle()
                    Spacer(Modifier.width(8.dp))
                }
                // LEADING COLUMN: logo on top, channel number centered
                // UNDERNEATH it (Logan 2026-09-16). The number never sits
                // beside the logo any more, so it can never compete for width
                // and can never wrap, and the logo gets the column's FULL
                // width in every state. Same stack on phone, tablet and TV,
                // and the guide rail draws the same order.
                // LEADING COLUMN: the SHARED ChannelBadge (core/ui) -
                // logo on top, channel number centered underneath it. The list
                // row keeps the channel name in its text column, so the badge
                // is asked for showName = false here; the guide rail renders
                // the same badge geometry with the name on.
                if (showLogo || showNumber) {
                    com.aeriotv.android.core.ui.ChannelBadge(
                        logoModel = channel.tvgLogo.takeIf { it.isNotBlank() },
                        numberText = channel.channelNumber,
                        nameText = null,
                        showLogo = showLogo,
                        showNumber = showNumber,
                        showName = false,
                        // The surface owns the slot width; the row measures at
                        // IntrinsicSize.Min so the badge fills the height the
                        // text column set.
                        slotWidth = leadColumnWidth,
                        containerCorner = CHANNEL_ROW_CORNER,
                        numberStyle = numberStyle,
                        // iOS parity: numbers sit on the DIM textTertiary rung
                        // so they recede behind the name/title.
                        numberColor = MaterialTheme.colorScheme.tertiary,
                        fallbackText = channel.name.take(2).uppercase(),
                        fallbackStyle = MaterialTheme.typography.labelMedium
                            .copy(fontWeight = FontWeight.Bold),
                        fallbackColor = MaterialTheme.colorScheme.textAccent,
                        // TV: number in its own column on the LEFT, logo
                        // centered beside it. The list row keeps the channel
                        // name in its text column, so on TV the badge is
                        // number-left plus logo-center with no name line.
                        numberOnLeft = isTv,
                    )
                    Spacer(Modifier.width(12.dp))
                }

                // Uniform-height channel-info column. iOS lets the description
                // wrap 0-2 lines, which lets cards spring up and down by
                // ~14 dp depending on EPG. The Android list ended up
                // visually choppy in landscape on phones — every other row
                // a different height — so we lock the slot heights:
                //   * Channel name: always 1 line
                //   * Program title: always 1 line (placeholder space when
                //     no EPG so the column still occupies that slot)
                //   * Description: always exactly 2 lines (minLines == max)
                //   * Progress bar: always 2 dp tall (transparent spacer
                //     when no EPG)
                // Empty slots use a thin-space character so the Text node
                // still measures its proper line height. Mirrors iOS
                // intent (ChannelListView.swift:1782 comment "subtitle slot
                // stays empty") but enforces measurement parity Android-
                // side, which iOS gets for free from its SwiftUI VStack
                // layout pass.
                Column(modifier = Modifier.weight(1f).height(rowSlots.totalHeight)) {
                    // Show Channel Names OFF: the name line is not rendered at
                    // all, so the program title moves up into its place. The
                    // favorite star and catch-up clock stay on this line (they
                    // are channel state, not the name), and with both absent
                    // the Row measures to zero height and disappears.
                    Row(
                        modifier = Modifier.height(rowSlots.nameHeight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showChannelName) {
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        // Favorite star beside the name, like the guide's
                        // channel column (Logan 2026-09-05, Apple
                        // ChannelListView.swift:3064-3070: filled star, 10pt
                        // semibold on compact, warning tint).
                        if (isFavorite) {
                            if (showChannelName) Spacer(Modifier.width(5.dp))
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "Favorite",
                                tint = Color(0xFFFFA502),
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        // Catch-up badge (Logan 2026-07-20, parity with the
                        // guide rail): a small history clock beside the name
                        // whenever this channel has a replayable archive.
                        if (channel.hasCatchup) {
                            if (showChannelName || isFavorite) Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = "Catch-up available",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        // Keep Recent Channels Live: KEPT badge while this
                        // channel's stream is kept open in the background.
                        if (channel.id in com.aeriotv.android.feature.livetv.LocalRetainedChannelIds.current) {
                            Spacer(Modifier.width(6.dp))
                            com.aeriotv.android.core.ui.EpgFlagBadge(
                                com.aeriotv.android.core.ui.EpgFlag("KEPT", MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                    val subtitle = nowProgramme?.subTitle?.takeIf {
                        LocalShowProgramSubtitles.current &&
                            !subtitleIsRedundant(it, nowProgramme.title, nowProgramme.description)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = nowProgramme?.title ?: " ",
                            style = MaterialTheme.typography.bodyMedium,
                            // iOS parity: the program-title line is accent at 0.85,
                            // not full strength (ChannelListView.swift MarqueeText
                            // accentPrimary.opacity(0.85)).
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // iPhone (ChannelListView.swift:3079-3091): time left
                        // sits on the title line before the badges; the trailing
                        // column keeps only the chevron.
                        if (phoneRow && nowProgramme != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = formatRemaining(nowProgramme),
                                style = MaterialTheme.typography.labelMedium.subtext(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        // Feed badges on the now-playing line; kept inline (same
                        // single-line height) so the row's fixed-slot alignment
                        // across channels is preserved.
                        if (LocalShowEpgBadges.current) {
                            nowProgramme?.epgFlags()?.takeIf { it.isNotEmpty() }?.let { flags ->
                                Spacer(Modifier.width(6.dp))
                                EpgFlagsRow(flags)
                            }
                        }
                    }
                    if (rowSlots.hasSubtitleLine) {
                        // iPhone: the sub-title is its own italic line above the
                        // description (ChannelListView.swift:3094-3100).
                        Text(
                            // Blank when this program has no sub-title: the
                            // LINE stays, so the rows below it still align.
                            text = subtitle ?: " ",
                            style = MaterialTheme.typography.bodySmall.subtext(),
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        // GH #34: lead the secondary line with the XMLTV
                        // <sub-title> (match/episode name) so same-title live
                        // events are distinguishable, then the description.
                        // TV keeps it folded into the fixed 2-line slot so the
                        // list's cross-row alignment is preserved; the phone
                        // printed it on its own line above.
                        text = listOfNotNull(
                            subtitle.takeIf { !phoneRow },
                            nowProgramme?.description?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").ifBlank { " " },
                        style = MaterialTheme.typography.bodySmall.subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(ROW_PROGRESS_GAP))
                    if (nowProgramme != null) {
                        EpgProgressBar(nowProgramme)
                    } else {
                        // Transparent placeholder keeps the 2 dp progress-
                        // bar slot reserved so all rows align even when
                        // EPG hasn't loaded for this channel.
                        Spacer(Modifier.height(ROW_PROGRESS_HEIGHT))
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!phoneRow) nowProgramme?.let {
                        Text(
                            text = formatRemaining(it),
                            style = MaterialTheme.typography.labelMedium.subtext(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 2026-07-12 (user): the per-row "More" (…) button was
                    // removed as redundant - long-pressing the row opens the
                    // same menu (the DropdownMenu anchors to the row Box, so
                    // no anchor was lost).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { isExpanded = !isExpanded },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand schedule",
                                tint = if (isExpanded) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // Channel long-press menu, iOS canon (ChannelListView.swift:1873).
            // Rendered as the shared centered TvActionMenuDialog on TV (this
            // row is reachable with a remote via the Favorites tab) and as
            // the anchored DropdownMenu on phone. Same actions either way.
            if (isTv) {
                if (menuOpen) {
                    TvActionMenuDialog(
                        title = channel.name,
                        actions = buildList {
                            add(
                                TvMenuAction(
                                    if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                                    if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                                ) { onToggleFavorite() },
                            )
                            // #45: Add to Collection + the contextual remove
                            // (iOS cardMenuButtons order: right after Favorites).
                            collectionsMenu?.let { cm ->
                                add(
                                    TvMenuAction("Add to Collection…", Icons.Outlined.Folder) {
                                        cm.onOpenPicker(channel.id, channel.name, -1, 0L)
                                    },
                                )
                                val active = cm.activeCollectionId
                                    ?.let { id -> cm.collections.firstOrNull { it.id == id } }
                                if (active != null && channel.id in active.memberIds) {
                                    add(
                                        TvMenuAction(
                                            "Remove from ${active.name}",
                                            Icons.Outlined.Folder,
                                            destructive = true,
                                        ) { cm.onRemoveMember(active.id, channel.id) },
                                    )
                                } else if (cm.activeCollectionId == null &&
                                    cm.collections.any { channel.id in it.memberIds }
                                ) {
                                    add(
                                        TvMenuAction(
                                            "Remove from All Collections",
                                            Icons.Outlined.Folder,
                                            destructive = true,
                                        ) { cm.onRemoveFromAll(channel.id) },
                                    )
                                }
                            }
                            if (nowProgramme != null) {
                                add(
                                    TvMenuAction("Program Info", Icons.Outlined.Info) {
                                        onShowProgramInfo(nowProgramme.toInfoTarget(channel.name, channel.dispatcharrChannelId))
                                    },
                                )
                            }
                            // iOS parity: live channel Record surfaces for any
                            // Dispatcharr account (non-admin lands on-device via
                            // RecordProgramSheet). Server-side admin gating now
                            // lives in the sheet, not here.
                            //
                            // 0.4.3: no longer requires a Dispatcharr channel id.
                            // On-device recording (LocalRecordingService) only
                            // needs the stream URL, so M3U and Xtream channels
                            // record locally exactly like the future-programme
                            // rows in the expanded panel already did. The sheet
                            // hides the Destination toggle when the source can't
                            // schedule server-side, so there is no dead end.
                            if (channel.url.isNotBlank()) {
                                add(
                                    TvMenuAction(
                                        if (nowProgramme != null) "Record from Now" else "Record",
                                        Icons.Outlined.FiberManualRecord,
                                    ) { recordFromMenu() },
                                )
                            }
                        },
                        guard = menuGuard,
                        onDismiss = { menuOpen = false },
                    )
                }
            } else if (menuOpen) {
                // Phone and tablet: Material 3 modal bottom sheet, the same
                // surface the guide cell uses (Logan 2026-09-09). Apple
                // cardMenuItems order (ChannelListView.swift:3217): Watch,
                // Favorites, Multiview, Collection, Program Info, Record.
                com.aeriotv.android.feature.livetv.LiveTvActionSheet(
                    title = channel.name,
                    subtitle = nowProgramme?.title ?: " ",
                    actions = buildList {
                        add(TvMenuAction("Watch", Icons.Filled.PlayArrow) { onPlay() })
                        add(
                            TvMenuAction(
                                if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                                if (isFavorite) Icons.Outlined.Star else Icons.Filled.Star,
                            ) { onToggleFavorite() },
                        )
                        onToggleMultiview?.let { toggle ->
                            add(TvMenuAction(if (inMultiview) "Remove from Multiview" else "Add to Multiview", Icons.Outlined.GridView) { toggle() })
                        }
                        collectionsMenu?.let { cm ->
                            add(TvMenuAction("Add Channel to Collection", Icons.Outlined.Folder) { cm.onOpenPicker(channel.id, channel.name, -1, 0L) })
                            val active = cm.activeCollectionId?.let { id -> cm.collections.firstOrNull { it.id == id } }
                            if (active != null && channel.id in active.memberIds) {
                                add(TvMenuAction("Remove from ${active.name}", Icons.Outlined.Folder, destructive = true) { cm.onRemoveMember(active.id, channel.id) })
                            } else if (cm.activeCollectionId == null && cm.collections.any { channel.id in it.memberIds }) {
                                add(TvMenuAction("Remove from All Collections", Icons.Outlined.Folder, destructive = true) { cm.onRemoveFromAll(channel.id) })
                            }
                        }
                        if (nowProgramme != null) {
                            add(TvMenuAction("Program Info", Icons.Outlined.Info) {
                                onShowProgramInfo(nowProgramme.toInfoTarget(channel.name, channel.dispatcharrChannelId))
                            })
                        }
                        if (channel.url.isNotBlank()) {
                            add(TvMenuAction(if (nowProgramme != null) "Record from Now" else "Record", Icons.Outlined.FiberManualRecord) { recordFromMenu() })
                        }
                    },
                    onDismiss = { menuOpen = false },
                )
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            ChannelGuidePanel(
                channel = channel,
                channelName = channel.name,
                channelId = channel.id,
                channelDispatcharrId = channel.dispatcharrChannelId,
                programmes = programmes,
                onShowProgramInfo = onShowProgramInfo,
                onShowRecord = onShowRecord,
                onWatchPast = onWatchPast,
            )
        }
    }
}

/**
 * Inline upcoming-schedule panel that appears below an expanded ChannelRow.
 * Mirrors iOS `guidePanel` (ChannelListView.swift:2149). Each programme row tap
 * opens ProgramInfoSheet. Long-press opens a Material 3 dropdown with Program
 * Info + Record options, matching the iOS popover at the same code site.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelGuidePanel(
    channel: M3UChannel,
    channelName: String,
    channelId: String,
    channelDispatcharrId: Int?,
    programmes: List<EPGProgramme>,
    onShowProgramInfo: (ProgramInfoTarget) -> Unit,
    onShowRecord: (ProgramInfoTarget) -> Unit,
    onWatchPast: ((EPGProgramme) -> Unit)? = null,
) {
    val now = System.currentTimeMillis()
    val current = programmes.nowPlaying(now)
    val upcoming = remember(programmes, current) {
        programmes
            .asSequence()
            .filter { it.endMillis > now && it != current }
            .sortedBy { it.startMillis }
            .toList()
    }
    // Catch-up (task #137): already-aired programmes, oldest first so the
    // most recent one sits just above the schedule (scroll up = further
    // back in time). Everything retained is listed (Archie: show all
    // available, parity with the guide's history depth); only one channel
    // panel is expanded at a time so the row count stays bounded by the
    // playlist's retention window.
    val recentlyAired = remember(programmes) {
        programmes
            .asSequence()
            .filter { it.endMillis <= now && !it.isPlaceholder }
            .sortedBy { it.startMillis }
            .toList()
    }

    // The panel is its own bounded scroll area so expanding a channel LANDS
    // on the upcoming schedule (first row = the show after the one airing,
    // Archie spec) while all retained history stays reachable by scrolling
    // UP from there. initialFirstVisibleItemIndex points past the aired
    // block; the finite max height is what makes a vertical LazyColumn legal
    // inside the outer channel list.
    val panelState = remember(channelId) {
        LazyListState(firstVisibleItemIndex = recentlyAired.size)
    }
    // Damping (Archie): once the panel hits its top/bottom edge, swallow the
    // leftover drag/fling instead of handing it to the outer channel list,
    // so browsing history can't accidentally scroll the page or trigger
    // pull-to-refresh. Drags that START outside the panel still scroll the
    // outer list normally.
    val panelScrollDamper = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset = available

            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity = available
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        if (recentlyAired.isEmpty() && upcoming.isEmpty()) {
            Text(
                text = "No upcoming schedule available",
                style = MaterialTheme.typography.bodySmall.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        } else {
            LazyColumn(
                state = panelState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .padding(vertical = 6.dp)
                    .nestedScroll(panelScrollDamper),
            ) {
                items(recentlyAired.size) { i ->
                    val programme = recentlyAired[i]
                    val replayable = channel.canReplay(programme, now)
                    val watchPast: (() -> Unit)? = if (replayable && onWatchPast != null) {
                        { onWatchPast(programme) }
                    } else {
                        null
                    }
                    UpcomingProgrammeRow(
                        programme = programme,
                        channelName = channelName,
                        channelId = channelId,
                        isPast = true,
                        replayable = replayable,
                        onWatch = watchPast,
                        // A single tap / OK on a replayable aired programme
                        // plays it from the start; the long-press menu still
                        // carries Program Info and everything else. Rows
                        // outside the archive window keep opening the info sheet.
                        onTap = watchPast
                            ?: { onShowProgramInfo(programme.toInfoTarget(channelName, channelDispatcharrId)) },
                        onShowRecord = { onShowRecord(programme.toInfoTarget(channelName, channelDispatcharrId)) },
                    )
                }
                if (recentlyAired.isNotEmpty() && upcoming.isNotEmpty()) {
                    // Boundary marker (Archie): the panel lands with this row
                    // at the top, so it doubles as the hint that everything
                    // above it is history. Same idiom as a messaging app's
                    // "new messages" separator.
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            )
                            // Up arrows on both sides of the label say
                            // "everything above already aired" (Logan
                            // 2026-09-09, both platforms).
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ArrowUpward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(11.dp),
                                )
                                Text(
                                    text = "Previously aired",
                                    style = MaterialTheme.typography.labelSmall.subtext(),
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Icon(
                                    imageVector = Icons.Outlined.ArrowUpward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                items(upcoming.size) { i ->
                    val programme = upcoming[i]
                    UpcomingProgrammeRow(
                        programme = programme,
                        channelName = channelName,
                        channelId = channelId,
                        onTap = { onShowProgramInfo(programme.toInfoTarget(channelName, channelDispatcharrId)) },
                        onShowRecord = { onShowRecord(programme.toInfoTarget(channelName, channelDispatcharrId)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpcomingProgrammeRow(
    programme: EPGProgramme,
    channelName: String,
    channelId: String,
    onTap: () -> Unit,
    onShowRecord: () -> Unit,
    /** Catch-up (task #137): this row is an already-aired programme. Past
     *  rows drop Set Reminder and Record from the long-press menu. */
    isPast: Boolean = false,
    /** True when the programme is inside the channel's archive window. */
    replayable: Boolean = false,
    /** Plays the programme from the archive; the menu leads with Watch. */
    onWatch: (() -> Unit)? = null,
    remindersVm: RemindersViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val menuGuard = com.aeriotv.android.core.tv.rememberTvMenuGuard()
    val isTv = rememberIsTvDevice()
    val key = remember(programme, channelName) {
        reminderKey(channelName, programme.title, programme.startMillis)
    }
    val isReminderSet by remindersVm.observeIsSet(key)
        .collectAsStateWithLifecycle(initialValue = false)

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    // menuGuard.wrap: the spurious OK-release after a TV
                    // long-press can land back on this row and would
                    // otherwise open the info sheet.
                    onClick = menuGuard.wrap(onTap),
                    onLongClick = { menuOpen = true; menuGuard.arm() },
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Catch-up (task #137): same rewind-clock glyph the guide
                    // cells carry on replayable aired programmes.
                    if (replayable) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = "Catch-up available",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        text = programme.title.ifBlank { "–" },
                        style = MaterialTheme.typography.bodyMedium,
                        // Past rows read a rung dimmer so the history region
                        // is visually distinct from the upcoming schedule.
                        color = if (isPast) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (LocalShowEpgBadges.current) {
                        programme.seasonEpisodeLabel()?.let { seLabel ->
                            Spacer(Modifier.width(6.dp))
                            SeasonEpisodePill(seLabel)
                        }
                        programme.epgFlags().takeIf { it.isNotEmpty() }?.let { flags ->
                            Spacer(Modifier.width(6.dp))
                            EpgFlagsRow(flags)
                        }
                    }
                }
                // GH #34: surface the XMLTV <sub-title> (match/episode name) in
                // the expanded schedule so back-to-back same-title programmes are
                // distinguishable.
                programme.subTitle?.takeIf {
                    LocalShowProgramSubtitles.current && !subtitleIsRedundant(it, programme.title, programme.description)
                }?.let { sub ->
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall.subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (programme.description.isNotBlank()) {
                    Text(
                        text = programme.description,
                        style = MaterialTheme.typography.bodySmall.subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatTimeRange(programme),
                style = MaterialTheme.typography.labelSmall,
                // iOS parity: schedule time ranges are textTertiary
                // (epgEntryRow), a rung dimmer than the description.
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        // One source of truth for the long-press actions; rendered as the
        // Material bottom sheet on phone and as the shared centered
        // TvActionMenuDialog on TV (reachable via the Favorites tab).
        val menuActions = buildList {
            // Catch-up (task #137): a replayable aired programme leads with
            // Watch, mirroring the guide-cell menu.
            onWatch?.let { watch ->
                add(TvMenuAction("Watch", Icons.Outlined.Replay) { watch() })
            }
            add(TvMenuAction("Program Info", Icons.Outlined.Info) { onTap() })
            if (!isPast) add(
                TvMenuAction(
                    if (isReminderSet) "Cancel Reminder" else "Set Reminder",
                    Icons.Outlined.Notifications,
                ) {
                    if (isReminderSet) {
                        remindersVm.cancelReminder(key)
                        Toast.makeText(context, "Reminder cancelled.", Toast.LENGTH_SHORT).show()
                    } else {
                        remindersVm.setReminder(
                            channelName = channelName,
                            programTitle = programme.title,
                            startMillis = programme.startMillis,
                            endMillis = programme.endMillis,
                            channelId = channelId,
                        )
                        Toast.makeText(context, "Reminder set.", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            if (!isPast) add(TvMenuAction("Record", Icons.Outlined.FiberManualRecord) { onShowRecord() })
        }
        if (isTv) {
            if (menuOpen) {
                TvActionMenuDialog(
                    title = programme.title.ifBlank { channelName },
                    actions = menuActions,
                    guard = menuGuard,
                    onDismiss = { menuOpen = false },
                )
            }
        } else if (menuOpen) {
            // Phone and tablet: the same Material 3 modal bottom sheet the
            // guide cell and the channel row use (Logan 2026-09-09).
            com.aeriotv.android.feature.livetv.LiveTvActionSheet(
                title = programme.title.ifBlank { channelName },
                subtitle = channelName,
                actions = menuActions,
                onDismiss = { menuOpen = false },
            )
        }
    }
}

@Composable
private fun EpgProgressBar(programme: EPGProgramme) {
    val now = System.currentTimeMillis()
    val total = (programme.endMillis - programme.startMillis).coerceAtLeast(1L)
    val elapsed = (now - programme.startMillis).coerceAtLeast(0L).coerceAtMost(total)
    val progress = (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        drawStopIndicator = {},
    )
}

private fun formatRemaining(programme: EPGProgramme): String {
    val remainingMs = (programme.endMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    val minutes = remainingMs / 60_000L
    if (minutes <= 0L) return "ending"
    if (minutes < 60L) return "${minutes}m"
    val hours = minutes / 60L
    val leftover = minutes % 60L
    return if (leftover == 0L) "${hours}h" else "${hours}h ${leftover}m"
}

private fun formatTimeRange(programme: EPGProgramme): String {
    val timeFormat = com.aeriotv.android.core.ui.ClockFormat.short()
    val start = timeFormat.format(java.util.Date(programme.startMillis))
    val end = timeFormat.format(java.util.Date(programme.endMillis))
    return "$start – $end"
}

/**
 * The channel row card's OWN corner radius. The card clip, both focus borders
 * and the channel logo all read this one value, so the logo's corners can
 * never drift from the card's (Logan 2026-09-16).
 */
internal val CHANNEL_ROW_CORNER = 12.dp

/**
 * Leading column width whenever a logo is shown and the row is not in the
 * both-hidden full-bleed state: the 28 dp number column + its 8 dp gap + the
 * 50 dp logo slot the row used to spend side by side, now all given to the
 * logo. The row's total leading footprint, and therefore the text column, is
 * unchanged. Never narrower than the measured number underneath it.
 */
private val ROW_LEAD_WIDTH = 86.dp

/**
 * Floor for the measured channel-number column: the shared widest-number
 * reference (four digits plus the decimal sub-channel form Dispatcharr can
 * emit). The column never comes out narrower than this string renders at the
 * user's Text Size.
 */
private const val ROW_NUMBER_REFERENCE =
    com.aeriotv.android.core.ui.CHANNEL_NUMBER_REFERENCE
// Numbers AND names both hidden: the slot is the row's full content height
// (~80 dp at the default Text Size, more when it is scaled up) and this max
// width, so a portrait cover or a square badge has room to scale up while a
// wide logo still fits. The info column keeps its layout to the right.
private val ROW_LOGO_FULL_WIDTH = 96.dp

/** Gap above the progress bar, and the bar's own height. Both reserved on
 *  every row so the bars line up across the list. */
private val ROW_PROGRESS_GAP = 4.dp
private val ROW_PROGRESS_HEIGHT = 2.dp

/** Height the name line still needs when names are OFF: the favorite star /
 *  catch-up clock glyphs live on that line and are per-channel. */
private val ROW_ICON_LINE = 14.dp

/** The row's reserved slots, measured from the row's own text styles. */
private data class ChannelRowSlots(
    val nameHeight: Dp,
    val hasSubtitleLine: Boolean,
    val totalHeight: Dp,
)
