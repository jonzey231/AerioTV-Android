package com.aeriotv.android.feature.livetv.grid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
fun GuideScreen2(
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
    val sidebarGroupMode = isTv && groupSelector == "sidebar"
    val remoteMap by settingsVm.remoteControlMap.collectAsStateWithLifecycle(initialValue = com.aeriotv.android.core.remote.RemoteControlMap.DEFAULT)
    var groupSidebarOpen by remember { mutableStateOf(false) }
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
    val displayChannels by produceState(
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
    LaunchedEffect(isTv, rows.isEmpty) {
        if (isTv && !rows.isEmpty) {
            repeat(10) { if (runCatching { gridFocus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(16L) }
        }
    }

    val openGroupMenu: () -> Boolean = {
        if (sidebarGroupMode) {
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
            hiddenGroupCount = hiddenGroups.size,
        )
    }
    Column(modifier = Modifier.weight(1f).fillMaxSize()) {
        if (!sidebarGroupMode) GroupPills(
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
                onLeaveTop = { if (sidebarGroupMode) false else runCatching { pillsFocus.requestFocus() }.isSuccess },
                remoteAction = { slot -> remoteMap.guideAction(slot) },
                holdLeftOpensGroups = sidebarGroupMode,
                onHostAction = hostAction,
                focusRequester = gridFocus,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    }

    menuFor?.let { (channel, cell) ->
        val notEnded = cell.endMillis > nowMs
        val isLive = nowMs in cell.startMillis until cell.endMillis
        val inMultiview = stagedMultiview.any { it.id == channel.id }
        val key = reminderKey(channel.name, cell.title, cell.startMillis)
        val actions = buildList {
            if (!cell.isPlaceholder && channel.canReplay(cell, nowMs)) {
                add(TvMenuAction(label = "Watch from start") {
                    menuFor = null
                    viewModel.playCatchup(channel, cell) { result ->
                        result.onSuccess { r ->
                            onPlayCatchup(channel.id, r.url, cell.title, cell.startMillis, cell.endMillis, r.panelTimeZoneId, r.channelUuid.orEmpty())
                        }
                    }
                })
            }
            if (!cell.isPlaceholder) {
                add(TvMenuAction(label = "Program info") {
                    menuFor = null
                    programInfoTarget = cell.toInfoTarget(channel.name, channel.dispatcharrChannelId)
                })
            }
            if (!cell.isPlaceholder && notEnded && (isLive || canRecordToServer)) {
                add(TvMenuAction(label = "Record") {
                    menuFor = null
                    recordTarget = cell.toInfoTarget(channel.name, channel.dispatcharrChannelId)
                })
            }
            if (!cell.isPlaceholder && cell.startMillis > nowMs) {
                if (key in reminderKeys) add(TvMenuAction(label = "Cancel reminder") { menuFor = null; remindersVm.cancelReminder(key) })
                else add(TvMenuAction(label = "Set reminder") {
                    menuFor = null
                    remindersVm.setReminder(channel.name, cell.title, cell.startMillis, cell.endMillis, channel.id)
                })
            }
            add(TvMenuAction(label = if (channel.id in favoriteIds) "Remove from favorites" else "Add to favorites") {
                menuFor = null; favoritesVm.toggle(channel)
            })
            if (channel.url.isNotBlank()) {
                add(TvMenuAction(label = if (inMultiview) "Remove from Multiview" else "Add to Multiview") {
                    menuFor = null; multiviewStore.toggle(channel)
                })
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
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        items(items, key = { it.first }) { (group, label) ->
            var focused by remember { mutableStateOf(false) }
            val isSelected = group == selected
            val colors = MaterialTheme.colorScheme
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .then(if (group == items.firstOrNull()?.first) Modifier.focusRequester(firstPillFocus) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) onDown() else false
                    }
                    .focusable()
                    .background(
                        when {
                            isSelected -> colors.primary.copy(alpha = 0.35f)
                            else -> colors.surfaceVariant.copy(alpha = 0.5f)
                        },
                        RoundedCornerShape(15.dp),
                    )
                    .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(15.dp))
                    .clickable { onSelect(group) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}
