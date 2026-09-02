package com.aeriotv.android.feature.livetv.grid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.text.input.ImeAction
import com.aeriotv.android.feature.channels.SortMenu
import com.aeriotv.android.feature.collections.AddToCollectionFlow
import com.aeriotv.android.feature.collections.CollectionPill
import com.aeriotv.android.feature.livetv.LiveTvPillsRow
import com.aeriotv.android.feature.livetv.LiveTvTopBar
import com.aeriotv.android.feature.livetv.ManageGroupsSheet
import com.aeriotv.android.feature.livetv.RetainedChannelsAction
import com.aeriotv.android.feature.livetv.RetainedChannelsViewModel
import com.aeriotv.android.feature.livetv.TvGroupPicker
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.canReplay
import com.aeriotv.android.core.data.db.entity.reminderKey
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.core.guide.GuideCatalog
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import com.aeriotv.android.feature.collections.CollectionsViewModel
import com.aeriotv.android.feature.dvr.DvrViewModel
import com.aeriotv.android.feature.miniplayer.MiniPlayerSession
import com.aeriotv.android.feature.miniplayer.MiniPlayerViewModel
import com.aeriotv.android.feature.favorites.FavoritesViewModel
import com.aeriotv.android.feature.livetv.EmptyGroupNotice
import com.aeriotv.android.feature.livetv.GroupSortMode
import com.aeriotv.android.feature.livetv.GuideGroupSidebarPane
import com.aeriotv.android.feature.livetv.LiveTVViewMode
import com.aeriotv.android.feature.livetv.ProgramInfoSheet
import com.aeriotv.android.feature.livetv.RecordProgramSheet
import com.aeriotv.android.feature.livetv.computeDisplayChannels
import com.aeriotv.android.feature.livetv.orderGroups
import com.aeriotv.android.feature.livetv.rememberLiveTvFormFactor
import com.aeriotv.android.feature.multiview.rememberMultiviewStoreHandle
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.reminders.RemindersViewModel
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.ui.LocalCanRecordToServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Host for the rebuilt guide (developer toggle "New guide renderer"). Same
 * public contract as GuideScreen so LiveTVTabContent can swap them. First
 * cut deliberately omits: the docked group sidebar, collection pills, record
 * dots, mini-player promotion on OK, the search field. Those return in the
 * cut-over phase once the grid itself passes the acceptance gates.
 */
@Composable
fun GuideScreen(
    onChannelClick: (M3UChannel) -> Unit,
    viewMode: LiveTVViewMode,
    canToggleViewMode: Boolean,
    onToggleViewMode: () -> Unit,
    onLaunchMultiview: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onPlayCatchup: (
        channelId: String,
        playbackUrl: String,
        title: String,
        progStartMillis: Long,
        progEndMillis: Long,
        panelTz: String,
        channelUuid: String,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    modifier: Modifier = Modifier,
    /** Favorites tab on TV (Logan 2026-09-02): the grid shows only the
     *  starred channels in their favorites order; no group pills, no
     *  sidebar, no search / sort. */
    favoritesOnly: Boolean = false,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val favoritesVm: FavoritesViewModel = hiltViewModel()
    val remindersVm: RemindersViewModel = hiltViewModel()
    val collectionsVm: CollectionsViewModel = hiltViewModel()
    val multiviewStore = rememberMultiviewStoreHandle()
    val miniPlayerVm: MiniPlayerViewModel = hiltViewModel()
    val miniState by miniPlayerVm.state.collectAsStateWithLifecycle()
    val miniActive = miniState is MiniPlayerSession.State.Active
    val miniChannelId = (miniState as? MiniPlayerSession.State.Active)?.channel?.id
    val dvrVm: DvrViewModel = hiltViewModel()
    val dvrState by dvrVm.state.collectAsStateWithLifecycle()
    val recordingWindows = remember(dvrState.recordings) {
        val out = HashMap<Int, MutableList<LongRange>>()
        for (rec in dvrState.recordings) {
            val st = rec.effectiveStatus()
            val chId = rec.dispatcharrChannelId ?: continue
            if (st == DvrViewModel.Recording.Status.Scheduled || st == DvrViewModel.Recording.Status.Recording) {
                out.getOrPut(chId) { ArrayList() }.add(rec.startMillis until rec.endMillis)
            }
        }
        out as Map<Int, List<LongRange>>
    }
    val isTv = rememberLiveTvFormFactor().isTv
    val context = LocalContext.current
    val canRecordToServer = LocalCanRecordToServer.current

    val windowHours by settingsVm.epgWindowHours.collectAsStateWithLifecycle(initialValue = 24)
    val guideScale by settingsVm.guideScale.collectAsStateWithLifecycle(initialValue = 1f)
    val displayScaleLiveTv by settingsVm.displayScaleLiveTV.collectAsStateWithLifecycle(initialValue = 1f)
    val hiddenGroups by settingsVm.hiddenGroups.collectAsStateWithLifecycle(initialValue = emptySet())
    val groupSortModeRaw by settingsVm.groupSortMode.collectAsStateWithLifecycle(initialValue = "Default")
    val groupOrder by settingsVm.groupOrder.collectAsStateWithLifecycle(initialValue = emptyList())
    val groupSortMode = GroupSortMode.from(groupSortModeRaw)
    val favoritesList by favoritesVm.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds = remember(favoritesList) { favoritesList.mapTo(HashSet()) { it.channelId } }
    val reminders by remindersVm.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val reminderKeys = remember(reminders) { reminders.mapTo(HashSet()) { it.reminderKey } }
    val collections by collectionsVm.collections.collectAsStateWithLifecycle(initialValue = emptyList())
    val stagedMultiview by multiviewStore.selected.collectAsStateWithLifecycle(initialValue = emptyList())
    val groupSelector by settingsVm.guideGroupSelector.collectAsStateWithLifecycle(initialValue = "pills")
    val sidebarGroupMode = isTv && groupSelector == "sidebar" && !favoritesOnly
    val remoteMap by settingsVm.remoteControlMap.collectAsStateWithLifecycle(initialValue = com.aeriotv.android.core.remote.RemoteControlMap.DEFAULT)
    var groupSidebarOpen by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var collectionPickerFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showManageGroups by remember { mutableStateOf(false) }
    var sidebarOriginalGroup by remember { mutableStateOf<String?>(null) }

    val tvComfortScale = if (isTv) displayScaleLiveTv.coerceIn(0.85f, 1.75f) else 1f
    val fontScale = LocalConfiguration.current.fontScale
    val hourWidth = if (isTv) 300.dp * guideScale * tvComfortScale else 320.dp * guideScale
    val railWidth = if (isTv) 120.dp * tvComfortScale else 78.dp
    val rowHeight = if (isTv) 55.dp * tvComfortScale * fontScale else 72.dp
    val headerHeight = if (isTv) 25.dp * tvComfortScale * fontScale else 32.dp

    // Clock: 30 s tick for the now-line and the airing tint.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000L); nowMs = System.currentTimeMillis() } }

    val allGroupNames = remember(state.channels, groupSortMode, groupOrder) {
        val sourceOrder = state.channels.asSequence().map { it.groupTitle }.filter { it.isNotBlank() }.distinct().toList()
        orderGroups(sourceOrder, groupSortMode, groupOrder)
    }
    val groups = remember(allGroupNames, hiddenGroups) {
        listOf(PlaylistViewModel.ALL_GROUPS) + allGroupNames.filter {
            it !in hiddenGroups && !it.equals(PlaylistViewModel.ALL_GROUPS, ignoreCase = true)
        }
    }
    // Pills: collections placed at the beginning, then the groups, then the rest.
    val pillItems = remember(groups, collections) {
        val begin = collections.filter { it.placement == ChannelCollection.PLACEMENT_BEGINNING }
        val end = collections.filter { it.placement != ChannelCollection.PLACEMENT_BEGINNING }
        begin.map { ChannelCollection.token(it.id) to it.name } +
            groups.map { it to it } +
            end.map { ChannelCollection.token(it.id) to it.name }
    }
    val groupedChannels by produceState(
        initialValue = computeDisplayChannels(
            state.channels, state.selectedGroup, state.searchQuery, state.sortMode,
            allGroupNames, groupSortMode, hiddenGroups, favoriteIds, collections,
        ),
        state.channels, state.selectedGroup, state.searchQuery, state.sortMode,
        allGroupNames, groupSortMode, hiddenGroups, favoriteIds, collections,
    ) {
        value = withContext(Dispatchers.Default) {
            computeDisplayChannels(
                state.channels, state.selectedGroup, state.searchQuery, state.sortMode,
                allGroupNames, groupSortMode, hiddenGroups, favoriteIds, collections,
            )
        }
    }
    // favoritesOnly: the starred channels in the user's favorites order,
    // joined against the loaded playlist so stale rows fall away.
    val favoriteChannels = remember(favoritesList, state.channels) {
        val byId = state.channels.associateBy { it.id }
        favoritesList.mapNotNull { byId[it.channelId] }
    }
    val displayChannels = if (favoritesOnly) favoriteChannels else groupedChannels

    // Grid window: history back to the retention edge, forward to the EPG window.
    val historyHours = state.epgHistoryHours.coerceAtLeast(1)
    val forwardHours = if (windowHours <= 0) 48 else windowHours.coerceAtLeast(3)
    val windowStartMs = remember(historyHours) { System.currentTimeMillis() - historyHours * 3_600_000L }
    val windowEndMs = remember(forwardHours) { System.currentTimeMillis() + forwardHours * 3_600_000L }
    val grid = remember { GuideGridState(initialViewportStartMs = System.currentTimeMillis() - 15 * 60_000L) }
    val rows = remember(displayChannels, state.epgByChannel, windowStartMs, windowEndMs) {
        GuideGridRows(displayChannels, state.epgByChannel as? GuideCatalog, windowStartMs, windowEndMs)
    }
    LaunchedEffect(rows) { grid.installRows(rows) }

    val gridFocus = remember { FocusRequester() }
    val pillsFocus = remember { FocusRequester() }
    var programInfoTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var menuFor by remember { mutableStateOf<Pair<M3UChannel, EPGProgramme>?>(null) }
    val menuGuard = rememberTvMenuGuard()

    // Land focus on the grid on entry (TV).
    // Launch focus belongs to the grid. The tab host places its own initial
    // focus on the nav pill a beat after we compose (it used to lose that race
    // only because the old guide composed first), so keep asking for about a
    // second until the grid actually holds focus.
    var gridHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(isTv, rows.isEmpty) {
        if (isTv && !rows.isEmpty) {
            repeat(12) {
                if (gridHasFocus) return@LaunchedEffect
                // GH #90: a held Left that minimized the player also opened
                // the group sidebar as the guide composed; the sidebar owns
                // focus then, and this loop must not pull it back to the grid.
                if (groupSidebarOpen) return@LaunchedEffect
                runCatching { gridFocus.requestFocus() }
                delay(100L)
            }
        }
    }
    // Logan 2026-09-02: backing out of a full-screen channel lands the guide
    // on the channel that is still playing (now in the mini player), not
    // wherever the grid was before. Keyed on the mini's channel so it fires
    // on the fullscreen -> mini hand-off and stays quiet otherwise.
    LaunchedEffect(miniChannelId, rows.isEmpty) {
        val id = miniChannelId ?: return@LaunchedEffect
        if (!isTv || rows.isEmpty) return@LaunchedEffect
        if (grid.focusChannel(id)) runCatching { gridFocus.requestFocus() }
    }

    val collectionPillItem: @Composable (ChannelCollection) -> Unit = { c ->
        val token = ChannelCollection.token(c.id)
        CollectionPill(
            collection = c,
            selected = state.selectedGroup == token,
            isTv = isTv,
            onSelect = { viewModel.onGroupSelected(token) },
            onSetPlacement = { p -> collectionsVm.setPlacement(c.id, p) },
            onDelete = {
                if (state.selectedGroup == token) viewModel.onGroupSelected(PlaylistViewModel.ALL_GROUPS)
                collectionsVm.delete(c.id)
            },
        )
    }
    val openGroupMenu: () -> Boolean = {
        if (favoritesOnly) false
        else if (sidebarGroupMode) {
            sidebarOriginalGroup = state.selectedGroup
            groupSidebarOpen = true
            true
        } else runCatching { pillsFocus.requestFocus() }.isSuccess
    }
    val hostAction: (com.aeriotv.android.core.remote.GuideRemoteAction) -> Boolean = { action ->
        when (action) {
            com.aeriotv.android.core.remote.GuideRemoteAction.FOCUS_GROUP_PILLS -> openGroupMenu()
            com.aeriotv.android.core.remote.GuideRemoteAction.RESUME_PLAYER -> { if (miniActive) miniPlayerVm.session.requestResume(); true }
            com.aeriotv.android.core.remote.GuideRemoteAction.CLOSE_MINI_PLAYER -> { if (miniActive) miniPlayerVm.session.dismiss(); true }
            com.aeriotv.android.core.remote.GuideRemoteAction.PROGRAM_INFO -> {
                val row = grid.focusRow; val cell = grid.focusedCell()
                if (row >= 0 && cell != null && !cell.isPlaceholder) {
                    val ch = grid.rows.channel(row)
                    programInfoTarget = cell.toInfoTarget(ch.name, ch.dispatcharrChannelId)
                }
                true
            }
            com.aeriotv.android.core.remote.GuideRemoteAction.OPEN_SEARCH -> { onOpenSearch(); true }
            else -> false
        }
    }
    BackHandler(enabled = groupSidebarOpen) {
        sidebarOriginalGroup?.takeIf { it != state.selectedGroup }?.let { viewModel.onGroupSelected(it) }
        groupSidebarOpen = false
        runCatching { gridFocus.requestFocus() }
    }

    // With a mini player up, Back belongs to the player host (expand / close),
    // exactly as the old guide: the ladder must never finish the Activity
    // while a stream is playing.
    BackHandler(enabled = !miniActive && !groupSidebarOpen && menuFor == null && programInfoTarget == null && recordTarget == null) {
        when (grid.back(nowMs)) {
            GuideGridState.BackStep.RESTORED_NOW_AND_TOP, GuideGridState.BackStep.TOP -> Unit
            GuideGridState.BackStep.NONE -> {
                // Logan 2026-09-01: Back at the top of the guide must never close
                // the app. Last rung resets a filtered group to All; at All it
                // is a no-op (consumed) so the app stays up.
                if (state.selectedGroup != PlaylistViewModel.ALL_GROUPS) viewModel.onGroupSelected(PlaylistViewModel.ALL_GROUPS)
            }
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
    if (groupSidebarOpen) {
        GuideGroupSidebarPane(
            groups = groups,
            selectedToken = state.selectedGroup,
            topOffset = 0.dp,
            onPreview = { token -> viewModel.onGroupSelected(token) },
            onCommit = { token ->
                if (token != state.selectedGroup) viewModel.onGroupSelected(token)
                groupSidebarOpen = false
                runCatching { gridFocus.requestFocus() }
            },
            onManageGroups = { showManageGroups = true },
            hiddenGroupCount = hiddenGroups.size,
        )
    }
    Column(modifier = Modifier.weight(1f).fillMaxSize().then(if (isTv) Modifier else Modifier.statusBarsPadding())) {
        if (!isTv) {
            val retainedVm: RetainedChannelsViewModel = hiltViewModel()
            val retainedList by retainedVm.retained.collectAsStateWithLifecycle()
            LiveTvTopBar(
                actionCount = (if (canToggleViewMode) 4 else 3) + (if (retainedList.isNotEmpty()) 1 else 0),
            ) { buttonSize, iconSize ->
                RetainedChannelsAction(
                    viewModel = retainedVm, buttonSize = buttonSize, iconSize = iconSize,
                    onJumpToChannel = { id -> state.channels.firstOrNull { it.id == id }?.let(onChannelClick) },
                )
                if (canToggleViewMode) {
                    IconButton(onClick = onToggleViewMode, modifier = Modifier.size(buttonSize)) {
                        Icon(
                            imageVector = if (viewMode == LiveTVViewMode.Guide) Icons.Filled.ViewList else Icons.Filled.CalendarMonth,
                            contentDescription = if (viewMode == LiveTVViewMode.Guide) "Switch to List" else "Switch to Guide",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(iconSize),
                        )
                    }
                }
                IconButton(onClick = onOpenSearch, modifier = Modifier.size(buttonSize)) {
                    Icon(Icons.Filled.TravelExplore, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(iconSize))
                }
                IconButton(
                    onClick = { searchActive = !searchActive; if (!searchActive) viewModel.onSearchQueryChange("") },
                    modifier = Modifier.size(buttonSize),
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = if (searchActive) "Close search" else "Search channels",
                        tint = if (searchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(iconSize),
                    )
                }
                SortMenu(currentMode = state.sortMode, onSelect = viewModel::onSortModeChange, buttonSize = buttonSize, iconSize = iconSize)
            }
            if (searchActive) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = true,
                    placeholder = { Text("Search channels") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (state.searchQuery.isNotEmpty()) {
                        { IconButton(onClick = { viewModel.onSearchQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } }
                    } else null,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(imeAction = ImeAction.Search),
                )
            }
            if (groups.size > 1 || collections.isNotEmpty() || hiddenGroups.isNotEmpty()) {
                LiveTvPillsRow(
                    groups = groups,
                    selectedGroup = state.selectedGroup,
                    onSelectGroup = { viewModel.onGroupSelected(it) },
                    collections = collections,
                    hiddenGroupsCount = hiddenGroups.size,
                    onManageGroups = { showManageGroups = true },
                    collectionPillItem = collectionPillItem,
                )
            }
        }
        if (isTv && !sidebarGroupMode && !favoritesOnly) GroupPills(
            items = pillItems,
            selected = state.selectedGroup,
            onSelect = { viewModel.onGroupSelected(it) },
            firstPillFocus = pillsFocus,
            onDown = { runCatching { gridFocus.requestFocus() }.isSuccess },
        )
        if (rows.isEmpty) {
            EmptyGroupNotice(
                isSearching = state.searchQuery.isNotBlank(),
                onShowAllChannels = { viewModel.onGroupSelected(PlaylistViewModel.ALL_GROUPS) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val gridContent: @Composable () -> Unit = {
            GuideGrid(
                state = grid,
                nowMs = nowMs,
                hourWidth = hourWidth,
                rowHeight = rowHeight,
                railWidth = railWidth,
                headerHeight = headerHeight,
                favoriteIds = favoriteIds,
                recordingWindows = recordingWindows,
                isTv = isTv,
                onPlay = { channel, _ ->
                    // OK on the channel already in the corner mini promotes the
                    // mini to fullscreen instead of re-tuning the same stream.
                    if (isTv && miniChannelId == channel.id) miniPlayerVm.session.requestResume()
                    else onChannelClick(channel)
                },
                onOpenMenu = { channel, cell -> menuFor = channel to cell; menuGuard.arm() },
                onLeaveTop = { if (sidebarGroupMode || favoritesOnly) false else runCatching { pillsFocus.requestFocus() }.isSuccess },
                remoteAction = { slot -> remoteMap.guideAction(slot) },
                holdLeftOpensGroups = sidebarGroupMode,
                onHostAction = hostAction,
                focusRequester = gridFocus,
                onGridFocusChanged = { gridHasFocus = it },
                modifier = Modifier.fillMaxSize(),
            )
            }
            if (isTv) gridContent() else PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { viewModel.refreshPlaylist() },
                modifier = Modifier.fillMaxSize(),
            ) { gridContent() }
        }
    }
    }

    collectionPickerFor?.let { (chId, chName) ->
        AddToCollectionFlow(
            channelId = chId,
            channelName = chName,
            isTv = isTv,
            collections = collections,
            onToggleMember = collectionsVm::toggleMember,
            onCreate = collectionsVm::create,
            onClose = { collectionPickerFor = null; runCatching { gridFocus.requestFocus() } },
        )
    }

    if (showManageGroups) {
        if (isTv) {
            TvGroupPicker(
                allGroups = allGroupNames, hiddenGroups = hiddenGroups,
                onDismiss = { showManageGroups = false; runCatching { gridFocus.requestFocus() } },
                reorderEnabled = true, sortMode = groupSortMode,
                onSortModeChange = { settingsVm.setGroupSortMode(it.name) },
                onCommit = { hidden, order ->
                    if (hidden != hiddenGroups) settingsVm.setHiddenGroups(hidden)
                    order?.let { settingsVm.setGroupOrder(it) }
                },
            )
        } else {
            ManageGroupsSheet(
                allGroups = allGroupNames, hiddenGroups = hiddenGroups,
                onSave = { settingsVm.setHiddenGroups(it) },
                onDismiss = { showManageGroups = false },
                reorderEnabled = true, sortMode = groupSortMode,
                onSortModeChange = { settingsVm.setGroupSortMode(it.name) },
                onReorder = { settingsVm.setGroupOrder(it) },
            )
        }
    }

    menuFor?.let { (channel, cell) ->
        val notEnded = cell.endMillis > nowMs
        val isLive = nowMs in cell.startMillis until cell.endMillis
        val inMultiview = stagedMultiview.any { it.id == channel.id }
        val key = reminderKey(channel.name, cell.title, cell.startMillis)
        val isFavorite = channel.id in favoriteIds
        val atCap = stagedMultiview.size >= 4
        val canAddToMultiview = channel.url.isNotBlank() && (!atCap || inMultiview)
        val canRecord = notEnded && (isLive || canRecordToServer)
        val replayable = !cell.isPlaceholder && channel.canReplay(cell, nowMs)
        // Apple TV order (Logan 2026-09-02): Favorites, Multiview, Collection,
        // Program Info, Record from Now, then the Android-only extras.
        val actions = buildList {
            add(TvMenuAction(if (isFavorite) "Remove from Favorites" else "Add to Favorites") { favoritesVm.toggle(channel) })
            add(TvMenuAction(if (inMultiview) "Remove from Multiview" else "Add to Multiview", enabled = canAddToMultiview) { multiviewStore.toggle(channel) })
            add(TvMenuAction("Add to Collection...") { collectionPickerFor = channel.id to channel.name })
            if (!cell.isPlaceholder) {
                add(TvMenuAction("Program Info") { programInfoTarget = cell.toInfoTarget(channel.name, channel.dispatcharrChannelId) })
                if (canRecord) add(TvMenuAction(if (isLive) "Record from Now" else "Record") { recordTarget = cell.toInfoTarget(channel.name, channel.dispatcharrChannelId) })
                if (replayable) add(TvMenuAction("Watch from Start") {
                    viewModel.playCatchup(channel, cell) { result ->
                        result.onSuccess { r ->
                            onPlayCatchup(channel.id, r.url, cell.title, cell.startMillis, cell.endMillis, r.panelTimeZoneId, r.channelUuid.orEmpty())
                        }
                    }
                })
                if (cell.startMillis > nowMs) {
                    val set = key in reminderKeys
                    add(TvMenuAction(if (set) "Cancel Reminder" else "Set Reminder") {
                        if (set) remindersVm.cancelReminder(key)
                        else remindersVm.setReminder(channel.name, cell.title, cell.startMillis, cell.endMillis, channel.id)
                    })
                }
            }
        }
        TvActionMenuDialog(title = cell.title, actions = actions, guard = menuGuard, onDismiss = { menuFor = null })
    }
    programInfoTarget?.let { target ->
        ProgramInfoSheet(target = target, onDismiss = { programInfoTarget = null; runCatching { gridFocus.requestFocus() } })
    }
    recordTarget?.let { target ->
        RecordProgramSheet(target = target, onDismiss = { recordTarget = null; runCatching { gridFocus.requestFocus() } })
    }
}

@Composable
private fun GroupPills(
    items: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    firstPillFocus: FocusRequester,
    onDown: () -> Boolean,
) {
    val listState = rememberLazyListState()
    val topNav = com.aeriotv.android.feature.main.LocalTvTopNavFocusRequester.current
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.fillMaxWidth().height(44.dp).focusProperties { if (topNav != null) up = topNav },
    ) {
        items(items, key = { it.first }) { (group, label) ->
            // One focus target only: clickable() already contributes it, and a
            // separate focusable() nested a second one (first OK moved focus
            // inward and drew the default square ripple, the second OK
            // selected). Same pattern as the phone LiveTvTopBar pills. The
            // clip keeps the focus/pressed drawing inside the capsule.
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            val isSelected = group == selected
            val colors = MaterialTheme.colorScheme
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .then(if (group == items.firstOrNull()?.first) Modifier.focusRequester(firstPillFocus) else Modifier)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) onDown() else false
                    }
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        when {
                            isSelected -> colors.primary.copy(alpha = 0.35f)
                            else -> colors.surfaceVariant.copy(alpha = 0.5f)
                        },
                        RoundedCornerShape(15.dp),
                    )
                    .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(15.dp))
                    .clickable(interactionSource = interaction, indication = null) { onSelect(group) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}
