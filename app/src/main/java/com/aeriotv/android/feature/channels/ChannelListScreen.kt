package com.aeriotv.android.feature.channels

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.feature.collections.AddToCollectionFlow
import com.aeriotv.android.feature.collections.CollectionPill
import com.aeriotv.android.feature.collections.CollectionsMenuContext
import com.aeriotv.android.feature.collections.CollectionsViewModel
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.feature.favorites.FavoritesViewModel
import com.aeriotv.android.feature.settings.rememberIsTvDevice
import com.aeriotv.android.feature.livetv.LiveTVViewMode
import com.aeriotv.android.feature.livetv.ManageGroupsSheet
import com.aeriotv.android.feature.livetv.ProgramInfoSheet
import com.aeriotv.android.feature.livetv.RecordProgramSheet
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.playlist.SortMode
import com.aeriotv.android.feature.playlist.nowPlaying
import com.aeriotv.android.feature.reminders.RemindersViewModel
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.core.data.db.entity.reminderKey
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favoritesVm: FavoritesViewModel = hiltViewModel()
    val favoritesList by favoritesVm.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds = remember(favoritesList) { favoritesList.map { it.channelId }.toSet() }
    val settingsVm: SettingsViewModel = hiltViewModel()
    val palette by settingsVm.categoryPalette.collectAsStateWithLifecycle(initialValue = CategoryPaletteState.Default)
    val hiddenGroups by settingsVm.hiddenGroups.collectAsStateWithLifecycle(initialValue = emptySet())
    val showChannelLogos by settingsVm.showChannelLogos.collectAsStateWithLifecycle(initialValue = true)
    val showChannelNumbers by settingsVm.showChannelNumbers.collectAsStateWithLifecycle(initialValue = true)
    val groupSortModeRaw by settingsVm.groupSortMode.collectAsStateWithLifecycle(initialValue = "Default")
    val groupOrder by settingsVm.groupOrder.collectAsStateWithLifecycle(initialValue = emptyList())
    val groupSortMode = com.aeriotv.android.feature.livetv.GroupSortMode.from(groupSortModeRaw)

    var programInfoTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var manageGroupsOpen by remember { mutableStateOf(false) }
    // Search is a reveal-on-tap button (parity with Guide view), not an
    // always-on field, so the channel list gets full height until the user
    // opts into searching. Closing it clears the query.
    var searchActive by remember { mutableStateOf(false) }
    val isTv = rememberIsTvDevice()

    // Channel Collections (#45): user-named channel groupings as extra filter
    // pills + row-menu actions. Mirrors the guide's wiring; both screens share
    // the "collection:<id>" selectedGroup sentinel, so a collection picked in
    // one view stays active in the other.
    val collectionsVm: CollectionsViewModel = hiltViewModel()
    val collections by collectionsVm.collections.collectAsStateWithLifecycle(initialValue = emptyList())
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
    val allGroupsRaw by remember(state.channels, groupSortMode, groupOrder) {
        derivedStateOf {
            val sourceOrder = state.channels.asSequence()
                .map { it.groupTitle }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            // Apply the user's Manage Groups sort preference (Default / A-Z /
            // Manual) on top of the source order.
            com.aeriotv.android.feature.livetv.orderGroups(sourceOrder, groupSortMode, groupOrder)
        }
    }

    val groups by remember(allGroupsRaw, hiddenGroups) {
        derivedStateOf {
            // Drop any provider group literally named "All" -- it collides with
            // the ALL_GROUPS sentinel and crashes the pill LazyRow on a
            // duplicate key (#45 review).
            val visible = allGroupsRaw.filterNot {
                it in hiddenGroups || it.equals(PlaylistViewModel.ALL_GROUPS, ignoreCase = true)
            }
            listOf(PlaylistViewModel.ALL_GROUPS) + visible
        }
    }

    val filtered by remember(
        state.channels, state.searchQuery, state.selectedGroup, state.sortMode,
        favoriteIds, hiddenGroups, allGroupsRaw, groupSortMode, collections,
    ) {
        derivedStateOf {
            val query = state.searchQuery.trim()
            // #45: a "collection:<id>" sentinel filters to exactly the curated
            // members, bypassing hidden groups (the user picked them
            // explicitly); a dangling sentinel shows everything (iOS).
            val activeCollection = ChannelCollection.idFromToken(state.selectedGroup)
                ?.let { cid -> collections.firstOrNull { it.id == cid } }
            val collectionMembers = activeCollection?.memberIds?.toSet()
            // Only a collection filter when it doesn't collide with a real
            // provider group named "collection:x" (#45 review).
            val collectionSelected =
                state.selectedGroup.startsWith(ChannelCollection.TOKEN_PREFIX) &&
                    allGroupsRaw.none { it == state.selectedGroup }
            val byGroupAndSearch = state.channels.asSequence()
                .filter {
                    // "All" still respects hidden-group filters so toggling a group off
                    // truly hides its channels from the default list. The user can find
                    // them again via search (which ignores the hidden set).
                    when {
                        collectionSelected -> collectionMembers?.contains(it.id) ?: true
                        state.selectedGroup == PlaylistViewModel.ALL_GROUPS && query.isEmpty() ->
                            it.groupTitle !in hiddenGroups
                        state.selectedGroup == PlaylistViewModel.ALL_GROUPS -> true
                        else -> it.groupTitle.equals(state.selectedGroup, ignoreCase = true)
                    }
                }
                .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
                .toList()
            // When the user has chosen a non-default group order (A-Z or Manual),
            // cluster the "All" list by that group order so the channels follow
            // the groups (iOS sorts by group index then channel number). A
            // specific-group view or Default order keeps the flat sort. Blank /
            // unknown groups sort last. Within each group the chosen channel
            // sort applies as the secondary key.
            val clusterByGroup = query.isEmpty() &&
                state.selectedGroup == PlaylistViewModel.ALL_GROUPS &&
                groupSortMode != com.aeriotv.android.feature.livetv.GroupSortMode.Default
            val groupRankIndex = if (clusterByGroup) {
                allGroupsRaw.withIndex().associate { (i, g) -> g to i }
            } else {
                emptyMap()
            }
            val groupRank: (com.aeriotv.android.core.data.M3UChannel) -> Int =
                { ch -> if (clusterByGroup) groupRankIndex[ch.groupTitle] ?: Int.MAX_VALUE else 0 }
            when (state.sortMode) {
                SortMode.ByNumber -> byGroupAndSearch.sortedWith(
                    compareBy(groupRank, { it.channelNumber?.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.name.lowercase() }),
                )
                SortMode.ByName -> byGroupAndSearch.sortedWith(
                    compareBy(groupRank, { it.name.lowercase() }),
                )
                SortMode.FavoritesFirst -> byGroupAndSearch.sortedWith(
                    compareBy(
                        groupRank,
                        { it.id !in favoriteIds }, // false (favorited) sorts before true
                        { it.channelNumber?.toDoubleOrNull() ?: Double.MAX_VALUE },
                        { it.name.lowercase() },
                    ),
                )
            }
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

    Column(modifier = modifierWrap.fillMaxSize()) {
        // CenterAlignedTopAppBar (not the standard TopAppBar) centers the
        // title within the bar regardless of action button width on the
        // trailing edge, matching iOS `.navigationBarTitleDisplayMode(.inline)`
        // which iOS UIKit centers automatically (ChannelListView.swift:191).
        // Android TV drops the "Live TV" title bar entirely (wasted 10-foot
        // space): the Guide / Search / Sort controls move down onto the group-pill
        // control row (see below). Phone / tablet keep the titled app bar.
        if (!isTv) com.aeriotv.android.feature.livetv.LiveTvTopBar(
            actionCount = if (canToggleViewMode) 4 else 3,
            // The old CenterAlignedTopAppBar applied the status-bar inset
            // itself; LiveTvTopBar is inset-neutral (the Guide's Column
            // already statusBarsPadding()s), so THIS screen adds it here or
            // the bar rides up under the camera cutout (user report).
            modifier = Modifier.statusBarsPadding(),
        ) { buttonSize, iconSize ->
            if (canToggleViewMode) {
                IconButton(onClick = onToggleViewMode, modifier = Modifier.size(buttonSize)) {
                    Icon(
                        imageVector = if (viewMode == LiveTVViewMode.Guide)
                            Icons.Filled.ViewList else Icons.Filled.CalendarMonth,
                        contentDescription = if (viewMode == LiveTVViewMode.Guide)
                            "Switch to List" else "Switch to Guide",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
            // Global Search (parity #41): the full Search screen (movies /
            // shows / EPG). Distinct from the channel-name filter beside it.
            IconButton(onClick = onOpenSearch, modifier = Modifier.size(buttonSize)) {
                Icon(
                    imageVector = Icons.Filled.TravelExplore,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(iconSize),
                )
            }
            IconButton(
                onClick = {
                    searchActive = !searchActive
                    if (!searchActive) viewModel.onSearchQueryChange("")
                },
                modifier = Modifier.size(buttonSize),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = if (searchActive) "Close search" else "Search channels",
                    tint = if (searchActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(iconSize),
                )
            }
            SortMenu(
                currentMode = state.sortMode,
                onSelect = viewModel::onSortModeChange,
                buttonSize = buttonSize,
                iconSize = iconSize,
            )
        }

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
        val chipsVisible by remember {
            derivedStateOf { listState.firstVisibleItemIndex == 0 }
        }
        AnimatedVisibility(
            // Also show the pill row when collections exist even if there's
            // only the "All" group, else a collection filter is inescapable
            // on a groupless playlist (#45 review). Mirrors GuideScreen.
            visible = chipsVisible && (isTv || groups.size > 1 || collections.isNotEmpty()),
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // On Android TV the List has no top app bar, so the Guide / Search /
                // Sort controls live here, to the LEFT of the Filter (Manage Groups)
                // button, ahead of the group pills (parity with the Guide control row).
                if (isTv) {
                    if (canToggleViewMode) {
                        item {
                            ListControlCircle(
                                icon = Icons.Filled.CalendarMonth,
                                contentDescription = "Switch to Guide",
                                onClick = onToggleViewMode,
                            )
                        }
                    }
                    item {
                        ListControlCircle(
                            icon = Icons.Outlined.Search,
                            contentDescription = if (searchActive) "Close search" else "Search channels",
                            active = searchActive,
                            onClick = {
                                searchActive = !searchActive
                                if (!searchActive) viewModel.onSearchQueryChange("")
                            },
                        )
                    }
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
                // #45: collection pills join the group row -- placement
                // "beginning" renders before All, "end" after the last group.
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
                        label = { Text(group, style = MaterialTheme.typography.labelLarge) },
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
        val channelList: @Composable () -> Unit = {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // 104dp bottom clears the MainScaffold NavigationBar so the
                // final channel row stays fully visible above the tab bar.
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = 104.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = filtered, key = { it.id }) { channel ->
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
                    )
                }
            }
        }
        if (isTv) {
            channelList()
        } else {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { viewModel.refreshPlaylist() },
                modifier = Modifier.fillMaxSize(),
            ) {
                channelList()
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
    if (manageGroupsOpen) {
        ManageGroupsSheet(
            allGroups = allGroupsRaw,
            hiddenGroups = hiddenGroups,
            onSave = { settingsVm.setHiddenGroups(it) },
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
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        if (circular) {
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
                                MaterialTheme.colorScheme.primary
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
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val menuGuard = com.aeriotv.android.core.tv.rememberTvMenuGuard()
    val isTv = rememberIsTvDevice()
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
            .clip(RoundedCornerShape(12.dp))
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
                    RoundedCornerShape(12.dp),
                ) else Modifier.border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    RoundedCornerShape(12.dp),
                ),
            ),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                // GH #19: the whole 28dp number column collapses when numbers
                // are off, so the logo/name reclaim the width.
                if (showNumber) {
                    Box(
                        modifier = Modifier.width(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        channel.channelNumber?.let { num ->
                            Text(
                                text = num.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                // iOS parity: numbers sit on the DIM textTertiary
                                // rung (ChannelListView.swift iOSRow .textTertiary)
                                // so they recede behind name/title.
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }

                // Channel logo — matches iOS ChannelListView.swift:1754-1758
                // `CachedLogoImage(width: 38, height: 26)` on iPhone. The
                // logo sits directly on the row's gradient surface with no
                // background tile: tvg-logo art is already designed to be
                // overlaid on dark surfaces (most ship transparent PNGs or
                // tightly-cropped raster), and the iOS canon screenshot the
                // user provided shows ESPN/NBC/NFL logos floating directly
                // on the card. The container is wider than tall so wider
                // logos like NBC Sports fit comfortably without cropping.
                if (showLogo) {
                    Box(
                        modifier = Modifier
                            .width(50.dp)
                            .height(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (channel.tvgLogo.isNotBlank()) {
                            AsyncImage(
                                model = channel.tvgLogo,
                                contentDescription = null,
                                // Fit preserves aspect ratio and centers within
                                // the 50x32 container, matching iOS SwiftUI's
                                // default Image.resizable + scaledToFit() pair.
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .width(50.dp)
                                    .height(32.dp),
                            )
                        } else {
                            Text(
                                text = channel.name.take(2).uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = nowProgramme?.title ?: " ",
                        style = MaterialTheme.typography.bodyMedium,
                        // iOS parity: the program-title line is accent at 0.85,
                        // not full strength (ChannelListView.swift MarqueeText
                        // accentPrimary.opacity(0.85)).
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = nowProgramme?.description?.takeIf { it.isNotBlank() } ?: " ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(4.dp))
                    if (nowProgramme != null) {
                        EpgProgressBar(nowProgramme)
                    } else {
                        // Transparent placeholder keeps the 2 dp progress-
                        // bar slot reserved so all rows align even when
                        // EPG hasn't loaded for this channel.
                        Spacer(Modifier.height(2.dp))
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    nowProgramme?.let {
                        Text(
                            text = formatRemaining(it),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            // No menuGuard.arm(): this is a plain click, so
                            // there is no held OK key whose release could
                            // auto-pick the first menu item.
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreHoriz,
                                contentDescription = "More",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
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
                            if (channel.url.isNotBlank() && channel.dispatcharrChannelId != null) {
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
            } else DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = null,
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = {
                        Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites")
                    },
                    onClick = menuGuard.wrap {
                        menuOpen = false
                        onToggleFavorite()
                    },
                )
                // #45: Add to Collection + the contextual remove (iOS
                // cardMenuButtons order: right after Favorites).
                collectionsMenu?.let { cm ->
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = { Text("Add to Collection…") },
                        onClick = menuGuard.wrap {
                            menuOpen = false
                            cm.onOpenPicker(channel.id, channel.name, -1, 0L)
                        },
                    )
                    val active = cm.activeCollectionId
                        ?.let { id -> cm.collections.firstOrNull { it.id == id } }
                    if (active != null && channel.id in active.memberIds) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Remove from ${active.name}",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = menuGuard.wrap {
                                menuOpen = false
                                cm.onRemoveMember(active.id, channel.id)
                            },
                        )
                    } else if (cm.activeCollectionId == null &&
                        cm.collections.any { channel.id in it.memberIds }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Remove from All Collections",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = menuGuard.wrap {
                                menuOpen = false
                                cm.onRemoveFromAll(channel.id)
                            },
                        )
                    }
                }
                if (nowProgramme != null) {
                    DropdownMenuItem(
                        text = { Text("Program Info") },
                        onClick = menuGuard.wrap {
                            menuOpen = false
                            onShowProgramInfo(nowProgramme.toInfoTarget(channel.name, channel.dispatcharrChannelId))
                        },
                    )
                }
                // Record row only surfaces for Dispatcharr-sourced channels
                // (the only path that has server-side scheduling today).
                // Showing-then-toasting on M3U / Xtream rows was bad UX --
                // the user tapped, got a "DVR requires Dispatcharr" toast,
                // then had no way to actually start a recording. Mirrors
                // iOS canon: the channel long-press menu hides irrelevant
                // actions instead of greying them out for context-free
                // sources.
                if (channel.url.isNotBlank() && channel.dispatcharrChannelId != null) {
                    val recordLabel = if (nowProgramme != null) "Record from Now" else "Record"
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.FiberManualRecord,
                                contentDescription = null,
                                tint = Color(0xFFFF4757),
                            )
                        },
                        text = { Text(recordLabel) },
                        onClick = menuGuard.wrap {
                            menuOpen = false
                            recordFromMenu()
                        },
                    )
                }
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            ChannelGuidePanel(
                channelName = channel.name,
                channelId = channel.id,
                channelDispatcharrId = channel.dispatcharrChannelId,
                programmes = programmes,
                onShowProgramInfo = onShowProgramInfo,
                onShowRecord = onShowRecord,
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
    channelName: String,
    channelId: String,
    channelDispatcharrId: Int?,
    programmes: List<EPGProgramme>,
    onShowProgramInfo: (ProgramInfoTarget) -> Unit,
    onShowRecord: (ProgramInfoTarget) -> Unit,
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

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        if (upcoming.isEmpty()) {
            Text(
                text = "No upcoming schedule available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        } else {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                upcoming.forEach { programme ->
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
                Text(
                    text = programme.title.ifBlank { "–" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (programme.description.isNotBlank()) {
                    Text(
                        text = programme.description,
                        style = MaterialTheme.typography.bodySmall,
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
        // One source of truth for the long-press actions; rendered as an
        // anchored DropdownMenu on phone and as the shared centered
        // TvActionMenuDialog on TV (reachable via the Favorites tab).
        val menuActions = buildList {
            add(TvMenuAction("Program Info", Icons.Outlined.Info) { onTap() })
            add(
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
            add(TvMenuAction("Record", Icons.Outlined.FiberManualRecord) { onShowRecord() })
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
        } else {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                // Phone menu stays text-only, exactly as it was before the
                // actions list was hoisted; the icons are TV-dialog chrome.
                menuActions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = menuGuard.wrap {
                            menuOpen = false
                            action.onClick()
                        },
                    )
                }
            }
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
    val timeFormat = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    val start = timeFormat.format(java.util.Date(programme.startMillis))
    val end = timeFormat.format(java.util.Date(programme.endMillis))
    return "$start – $end"
}
