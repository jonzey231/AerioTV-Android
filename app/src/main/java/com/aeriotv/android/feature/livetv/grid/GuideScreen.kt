package com.aeriotv.android.feature.livetv.grid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.text.input.ImeAction
import com.aeriotv.android.feature.collections.AddToCollectionFlow
import com.aeriotv.android.feature.collections.CollectionPill
import com.aeriotv.android.feature.livetv.LiveTvPhoneCircle
import com.aeriotv.android.feature.livetv.LiveTvPhoneHeaderRow
import com.aeriotv.android.feature.livetv.PhoneGroupDrawerHost
import com.aeriotv.android.feature.livetv.ManageGroupsSheet
import com.aeriotv.android.feature.livetv.RetainedChannelsAction
import com.aeriotv.android.feature.livetv.RetainedChannelsViewModel
import com.aeriotv.android.feature.livetv.TvGroupPicker
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.aeriotv.android.feature.player.MiniPlayerChrome
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
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
import com.aeriotv.android.core.data.db.entity.dispatcharrVersionAtLeast
import com.aeriotv.android.core.data.db.entity.resolveGuideDays
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

    val guideScale by settingsVm.guideScale.collectAsStateWithLifecycle()
    val displayScaleLiveTv by settingsVm.displayScaleLiveTV.collectAsStateWithLifecycle()
    val hiddenGroups by settingsVm.hiddenGroups.collectAsStateWithLifecycle()
    val groupSortModeRaw by settingsVm.groupSortMode.collectAsStateWithLifecycle()
    val groupOrder by settingsVm.groupOrder.collectAsStateWithLifecycle()
    val groupSortMode = GroupSortMode.from(groupSortModeRaw)
    // Logan 2026-09-14: Recently Watched is a synthetic group, unchecked in
    // Manage Groups until the user shows it. "Hidden" is the one mechanism, so
    // the pref folds into the hidden set the sheets and the filters see.
    val recentGroupVisible by settingsVm.recentGroupVisible.collectAsStateWithLifecycle()
    val recentChannelIds by settingsVm.recentChannelIds.collectAsStateWithLifecycle(initialValue = emptyList())
    val effectiveHidden = remember(hiddenGroups, recentGroupVisible) {
        if (recentGroupVisible) hiddenGroups
        else hiddenGroups + com.aeriotv.android.feature.playlist.PlaylistViewModel.RECENT_GROUP
    }
    val favoritesOrNull by favoritesVm.all.collectAsStateWithLifecycle()
    val favoritesList = favoritesOrNull ?: emptyList()
    val favoriteIds = remember(favoritesList) { favoritesList.mapTo(HashSet()) { it.channelId } }
    val reminders by remindersVm.all.collectAsStateWithLifecycle()
    val reminderKeys = remember(reminders) { reminders.mapTo(HashSet()) { it.reminderKey } }
    val collections by collectionsVm.collections.collectAsStateWithLifecycle()
    val stagedMultiview by multiviewStore.selected.collectAsStateWithLifecycle(initialValue = emptyList())
    val groupSelector by settingsVm.guideGroupSelector.collectAsStateWithLifecycle()
    val sidebarGroupMode = isTv && groupSelector == "sidebar" && !favoritesOnly
    // Sidebar layout (Logan 2026-09-14): "shift" docks the pane beside the
    // grid, which narrows instead of being covered; "overlay" keeps the scrim.
    val guideSidebarLayout by settingsVm.guideSidebarLayout.collectAsStateWithLifecycle()
    val sidebarShiftMode = isTv && guideSidebarLayout == "shift"
    // Phone group selector (Logan 2026-09-05, Apple parity): drawer by
    // default, pills on request. Separate preference from the TV's.
    val phoneGroupSelector by settingsVm.phoneGroupSelector.collectAsStateWithLifecycle()
    val phoneSidebarMode = !isTv && phoneGroupSelector != "pills" && !favoritesOnly
    var phoneDrawerOpen by remember { mutableStateOf(false) }
    val remoteMap by settingsVm.remoteControlMap.collectAsStateWithLifecycle()
    val tabActive = com.aeriotv.android.feature.main.LocalTabIsActive.current
    var groupSidebarOpen by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    com.aeriotv.android.ui.search.CloseSearchOnLeave(searchActive) {
        searchActive = false
        viewModel.onSearchQueryChange("")
    }
    var collectionPickerFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showManageGroups by remember { mutableStateOf(false) }
    var sidebarOriginalGroup by remember { mutableStateOf<String?>(null) }
    // Manage Groups opened from the sidebar's header button: its dismissal
    // returns to the still-open sidebar instead of closing it.
    var manageGroupsFromSidebar by remember { mutableStateOf(false) }
    // Bumped when the sidebar should pull focus back onto its active row.
    var sidebarRefocusRequest by remember { mutableStateOf(0) }

    val tvComfortScale = if (isTv) displayScaleLiveTv.coerceIn(0.85f, 1.75f) else 1f
    // System font size times the app Text Size (the root LocalDensity carries
    // both), so TV rows grow with the text they hold.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    // Phone / tablet rows keep their fixed canon heights at 100% and grow only
    // with the app Text Size (not the system font size, unchanged from before).
    val appTextScale = com.aeriotv.android.ui.scale.LocalAppTextScale.current
    val hourWidth = if (isTv) 300.dp * guideScale * tvComfortScale else 320.dp * guideScale
    val railWidth = if (isTv) 120.dp * tvComfortScale else 78.dp
    // Phone cells carry the subtitle and two description lines (Logan
    // 2026-09-05, EPGGuideView.swift:3415: 98pt on the phone idiom, 72 on
    // the iPad), so they are taller. Phone = smallest width under 600dp,
    // so a rotated phone keeps the tall rows like the iPhone does.
    val isPhoneIdiom = !isTv && LocalConfiguration.current.smallestScreenWidthDp < 600
    // Channel Preview (tvOS "Live TV Layout", Logan 2026-09-05): a banner
    // above the guide carries the focused program; rows keep title + tags
    // and are shorter (tvOS 96 vs 110 pt).
    val liveTvLayout by settingsVm.liveTvLayout.collectAsStateWithLifecycle()
    val previewMode = isTv && liveTvLayout == "preview"
    // Subtext Size grows rows only (never shrinks them) by the share of the
    // row that holds secondary lines (subtitle, description, time).
    val subtextGrowth = com.aeriotv.android.ui.scale.LocalSubtextScale.current.let { s ->
        1f + (s - 1f).coerceAtLeast(0f) * (if (!isTv && isPhoneIdiom) GUIDE_PHONE_SUBTEXT_SHARE else GUIDE_SUBTEXT_SHARE)
    }
    // PHYSICAL PARITY WITH APPLE TV (Logan 2026-09-17). Apple TV points are
    // about 2x the Streamer's dp on the same physical screen (1080 pt tall vs
    // 540 dp), so Apple's 132 pt row is 66 dp here and its 22 pt band is 11 dp.
    // The TV base heights ARE those totals, band included: the band is carved
    // out of the row, never added on top of it. Preview mode is Apple's 96 pt
    // row, which is 48 dp here and is the mode Logan compares against, so the
    // Streamer shows the same seven rows as the Apple TV guide does
    // (measured 2026-09-17).
    //
    // THE TV ROW DOES NOT GROW FOR THE BAND (Logan 2026-09-16, Streamer).
    // The band is CARVED OUT of the row the guide already had, exactly as
    // Apple TV does it: Apple's 132 pt row is the row INCLUDING its band, not
    // 132 pt plus a band. Adding the 22 dp band on top here took the Streamer
    // row from 96 px to 140 px and dropped the guide from 7 visible channels
    // to under 5, because the Android TV guide viewport is proportionally
    // shorter than Apple's (the header, the filter row and the tab bar take
    // more of it), so the row count, not the row height, is what has to match.
    // The phone / tablet column still takes its small net growth: there the
    // band replaces a number line that used to sit under the logo.
    val rowHeight = (if (isTv) (if (previewMode) 48.dp else 66.dp) * tvComfortScale * fontScale else if (isPhoneIdiom) 98.dp * appTextScale else 72.dp * appTextScale) * subtextGrowth +
        (if (isTv) 0.dp else com.aeriotv.android.feature.livetv.grid.GUIDE_PHONE_ROW_BAND_GROWTH)
    val headerHeight = if (isTv) 25.dp * tvComfortScale * fontScale else 32.dp * appTextScale

    // Clock: 30 s tick for the now-line and the airing tint.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000L); nowMs = System.currentTimeMillis() } }

    val allGroupNames = remember(state.channels, groupSortMode, groupOrder, favoriteIds.isNotEmpty()) {
        com.aeriotv.android.feature.livetv.GuideMemo.get(
            "groupNames",
            listOf(com.aeriotv.android.feature.livetv.GuideMemo.Ref(state.channels), groupSortMode, groupOrder, favoriteIds.isNotEmpty()),
        ) {
            val sourceOrder = state.channels.asSequence().map { it.groupTitle }.filter { it.isNotBlank() }.distinct().toList()
            orderGroups(
                sourceOrder, groupSortMode, groupOrder,
                hasFavorites = favoriteIds.isNotEmpty(), hasRecent = true,
            )
        }
    }
    val groups = remember(allGroupNames, effectiveHidden) {
        com.aeriotv.android.feature.livetv.groupTokens(
            allGroupNames.filter { it !in effectiveHidden },
            effectiveHidden,
        )
    }
    val fallbackGroup = com.aeriotv.android.feature.livetv.fallbackGroupToken(groups)
    // GH #80: a selection that just got hidden (including All) lands on the
    // first pill still shown.
    // GH #81: Favorites is synthetic, so it only enters `groups` once the
    // favorites table has loaded. Resetting while it is still null threw away a
    // restored Favorites selection on every cold launch, which is why the
    // default group never stuck.
    LaunchedEffect(groups, state.selectedGroup, favoritesOrNull == null) {
        val sel = state.selectedGroup
        val favoritesPending = sel == com.aeriotv.android.feature.playlist.PlaylistViewModel.FAVORITES_GROUP &&
            favoritesOrNull == null
        if (sel !in groups && !sel.startsWith(ChannelCollection.TOKEN_PREFIX) &&
            !favoritesPending && allGroupNames.isNotEmpty()
        ) {
            viewModel.onGroupSelected(fallbackGroup, persist = false)
        }
    }
    // Pills: collections placed at the beginning, then the groups, then the rest.
    val pillItems = remember(groups, collections) {
        val begin = collections.filter { it.placement == ChannelCollection.PLACEMENT_BEGINNING }
        val end = collections.filter { it.placement != ChannelCollection.PLACEMENT_BEGINNING }
        begin.map { ChannelCollection.token(it.id) to it.name } +
            // Tokens (Favorites, All) read through the shared label map; the
            // raw "__favorites__" token showed on the Streamer (2026-09-10).
            groups.map { it to com.aeriotv.android.feature.livetv.groupDisplayName(it) } +
            end.map { ChannelCollection.token(it.id) to it.name }
    }
    val groupedChannels by produceState(
        initialValue = computeDisplayChannels(
            state.channels, state.selectedGroup, state.searchQuery, state.sortMode,
            allGroupNames, groupSortMode, effectiveHidden, favoriteIds, collections, recentChannelIds,
        ),
        state.channels, state.selectedGroup, state.searchQuery, state.sortMode,
        allGroupNames, groupSortMode, effectiveHidden, favoriteIds, collections, recentChannelIds,
    ) {
        value = withContext(Dispatchers.Default) {
            computeDisplayChannels(
                state.channels, state.selectedGroup, state.searchQuery, state.sortMode,
                allGroupNames, groupSortMode, effectiveHidden, favoriteIds, collections, recentChannelIds,
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

    // Grid window: the playlist's Guide Days setting (epgRetentionDays) in
    // BOTH directions (Logan 2026-09-11); the retired Settings > Network
    // "Guide Window" preference no longer shapes the timeline.
    // Loaded EPG span in whole days either side of now, for the Jump To sheet
    // and (on All Available) for the timeline extent itself.
    val (epgDaysBack, epgDaysAhead) = remember(state.epgByChannel) {
        var minStart = Long.MAX_VALUE; var maxEnd = Long.MIN_VALUE
        for (list in state.epgByChannel.values) {
            list.firstOrNull()?.let { if (it.startMillis < minStart) minStart = it.startMillis }
            list.lastOrNull()?.let { if (it.endMillis > maxEnd) maxEnd = it.endMillis }
        }
        val nowMs0 = System.currentTimeMillis()
        val day = 86_400_000L
        val back = if (minStart == Long.MAX_VALUE) 0 else ((nowMs0 - minStart + day - 1) / day).toInt().coerceAtLeast(0)
        val ahead = if (maxEnd == Long.MIN_VALUE) 1 else ((maxEnd - nowMs0 + day - 1) / day).toInt().coerceAtLeast(1)
        back to ahead
    }
    // A Dispatcharr 0.30+ server keeps many days and ensureGuideForward
    // fetches the jumped day on demand, so Jump To offers exactly the
    // playlist's Guide Days there, back and ahead (Logan 2026-09-11); the
    // 14-day ceiling below is a Jump To sheet constraint, not a fetch limit.
    // Other sources are limited to the guide their XMLTV actually carries.
    val guideDaysOffered = state.playlist?.let { pl ->
        val dispatcharr = pl.sourceType == com.aeriotv.android.core.data.SourceType.DispatcharrApiKey.name ||
            pl.sourceType == com.aeriotv.android.core.data.SourceType.DispatcharrUserPass.name
        if (dispatcharr && pl.dispatcharrVersionAtLeast("0.30.0"))
            resolveGuideDays(pl.epgRetentionDays)
        else null
    }
    // The catalog only decodes the days in view now (PlaylistViewModel
    // guideLaunchSpanDays), so on All Available the offer comes from what the
    // CACHE holds, not from what happens to be loaded; jumping to one of those
    // days widens the catalog through ensureGuideRange below.
    val epgDaysAheadOffered = guideDaysOffered ?: maxOf(epgDaysAhead, state.epgCachedDaysAhead)
    val epgDaysBackOffered = guideDaysOffered ?: maxOf(epgDaysBack, state.epgCachedDaysBack)
    // Extent: a fixed Guide Days setting drives both directions from the
    // setting; All Available (and non-Dispatcharr sources) follow the guide
    // that is actually loaded.
    val historyHours = (guideDaysOffered?.times(24)
        ?: minOf(state.epgHistoryHours, epgDaysBack * 24)).coerceAtLeast(1)
    val forwardHours = (guideDaysOffered?.times(24) ?: (epgDaysAhead * 24)).coerceAtLeast(3)
    // Guide jump-to-day (Roman via Discord 2026-09-06; Apple parity): the
    // target instant while a jump is active. The window grows to hold it
    // (plus three hours of room), the view model fetches the missing days,
    // and the jump ends on its own once the now line scrolls back on screen.
    var jumpTargetMs by remember { mutableStateOf<Long?>(null) }
    var pendingJumpScroll by remember { mutableStateOf(false) }
    var showJumpSheet by remember { mutableStateOf(false) }
    val jumpWindowEnd = jumpTargetMs?.let { (it + 3 * 3_600_000L) / QUANTUM_MS * QUANTUM_MS + QUANTUM_MS }
    // A jump backwards has to widen the window and the catalog the same way a
    // forward one does: launch only decodes today plus/minus a day
    // (PlaylistViewModel guideLaunchSpanDays), so the cached day the user
    // picked is in Room but not yet in memory.
    val jumpWindowStart = jumpTargetMs?.let { (it - 3 * 3_600_000L) / QUANTUM_MS * QUANTUM_MS }
    // Quantized to 15 min so re-entering the tab within that window reuses
    // the memoized rows instead of rebuilding them for a new "now".
    val windowStartMs = remember(historyHours, jumpWindowStart) {
        minOf(
            (System.currentTimeMillis() - historyHours * 3_600_000L) / QUANTUM_MS * QUANTUM_MS,
            jumpWindowStart ?: Long.MAX_VALUE,
        )
    }
    val windowEndMs = remember(forwardHours, jumpWindowEnd) {
        maxOf(
            (System.currentTimeMillis() + forwardHours * 3_600_000L) / QUANTUM_MS * QUANTUM_MS + QUANTUM_MS,
            jumpWindowEnd ?: 0L,
        )
    }
    LaunchedEffect(jumpWindowStart, jumpWindowEnd) {
        if (jumpWindowStart != null || jumpWindowEnd != null) {
            viewModel.ensureGuideRange(jumpWindowStart ?: 0L, jumpWindowEnd ?: 0L)
        }
    }
    val grid = remember { GuideGridState(initialViewportStartMs = System.currentTimeMillis() - 15 * 60_000L) }
    val rows = remember(displayChannels, state.epgByChannel, windowStartMs, windowEndMs) {
        com.aeriotv.android.feature.livetv.GuideMemo.get(
            "rows",
            listOf(
                com.aeriotv.android.feature.livetv.GuideMemo.Ref(displayChannels),
                com.aeriotv.android.feature.livetv.GuideMemo.Ref(state.epgByChannel),
                windowStartMs, windowEndMs,
            ),
        ) { GuideGridRows(displayChannels, state.epgByChannel as? GuideCatalog, windowStartMs, windowEndMs) }
    }
    LaunchedEffect(rows) {
        grid.installRows(rows)
        // Land the jump once the rows reach far enough to hold it.
        val target = jumpTargetMs
        // Both edges, now that a backward jump also widens the window.
        if (pendingJumpScroll && target != null &&
            rows.windowEndMs >= target && rows.windowStartMs <= target
        ) {
            grid.scrollViewportTo(target - grid.leadMs)
            pendingJumpScroll = false
        }
    }
    // Auto-end (Logan's rule): scrolling back until now is on screen ends the jump.
    LaunchedEffect(jumpTargetMs) {
        if (jumpTargetMs == null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { grid.viewportStartMs }.collect { vs ->
            if (!pendingJumpScroll) {
                val now = System.currentTimeMillis()
                if (now in vs..(vs + grid.viewportDurationMs)) jumpTargetMs = null
            }
        }
    }
    val jumpLabel = remember(jumpTargetMs) {
        jumpTargetMs?.let { java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date(it)) }
    }
    val startJump: (Long) -> Unit = { target ->
        jumpTargetMs = target
        pendingJumpScroll = true
        if (grid.rows.windowEndMs >= target && grid.rows.windowStartMs <= target) {
            grid.scrollViewportTo(target - grid.leadMs); pendingJumpScroll = false
        }
    }
    val snapToNow: () -> Unit = { jumpTargetMs = null; pendingJumpScroll = false; grid.anchorToNow(System.currentTimeMillis()) }

    val gridFocus = remember { FocusRequester() }
    val pillsFocus = remember { FocusRequester() }
    val bannerFocus = remember { FocusRequester() }
    var clockSelectTrigger by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val previewProgram: EPGProgramme? = if (previewMode) grid.focusedCell() else null
    val previewChannel: M3UChannel? = if (previewMode && grid.focusRow in 0 until grid.rows.size) grid.rows.channel(grid.focusRow) else null
    if (previewMode) {
        val pl = state.playlist
        com.aeriotv.android.feature.livetv.grid.ActivePlaylistBase.baseUrl = pl?.urlString
        com.aeriotv.android.feature.livetv.grid.ActivePlaylistBase.playlistId = pl?.id
    }
    var programInfoTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var menuFor by remember { mutableStateOf<Pair<M3UChannel, EPGProgramme>?>(null) }
    val menuGuard = rememberTvMenuGuard()
    // Start catch-up playback of one already-aired cell. Shared by the grid's
    // primary action (single tap / OK) and the long-press menu's "Watch from
    // Start", so both go through one resolve + navigate path.
    val startCatchup: (M3UChannel, EPGProgramme) -> Unit = { channel, cell ->
        viewModel.playCatchup(channel, cell) { result ->
            result.onSuccess { r ->
                // TV: remember the launched cell and the timeline so the
                // guide that composes again after the replay lands back here.
                if (isTv) GuideCatchupReturn.set(channel.id, cell.startMillis, grid.viewportStartMs)
                onPlayCatchup(channel.id, r.url, cell.title, cell.startMillis, cell.endMillis, r.panelTimeZoneId, r.channelUuid.orEmpty())
            }
        }
    }
    val guideFocusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // Land focus on the grid on entry (TV).
    // Launch focus belongs to the grid. The tab host places its own initial
    // focus on the nav pill a beat after we compose (it used to lose that race
    // only because the old guide composed first), so keep asking for about a
    // second until the grid actually holds focus.
    var gridHasFocus by remember { mutableStateOf(false) }
    // True while ANYTHING inside the guide holds focus (grid, pills, banner,
    // sidebar pane). The stranded-focus watchdog below keys off this, not off
    // gridHasFocus, so it can never steal focus from the guide's own chrome
    // (that is the GH #185 runaway shape).
    var guideHasFocus by remember { mutableStateOf(false) }
    val topNavHasFocus = com.aeriotv.android.feature.main.LocalTvTopNavHasFocus.current
    // Trace (AerioFocus): every gate that can keep focus out of the grid or
    // block a vertical step, as one string. Logged on change (a [GUIDE] gates
    // line) and appended to the grid's [KEY] and focus lines.
    val traceContext = androidx.compose.ui.platform.LocalContext.current
    val exoWindowModeFlow = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            traceContext.applicationContext,
            com.aeriotv.android.feature.main.MainScaffoldEntryPoint::class.java,
        ).exoWindowState().mode
    }
    val exoWindowMode by exoWindowModeFlow.collectAsStateWithLifecycle()
    val traceGates: () -> String = {
        "gates[tabActive=$tabActive gridHasFocus=$gridHasFocus" +
            " sidebarOpen=$groupSidebarOpen sidebarMode=$sidebarGroupMode layout=${if (sidebarShiftMode) "shift" else "overlay"}" +
            " gridFocusEnabled=${!(sidebarShiftMode && groupSidebarOpen && !gridHasFocus)}" +
            " manageGroups=$showManageGroups menu=${menuFor != null} info=${programInfoTarget != null} record=${recordTarget != null}" +
            " jump=$showJumpSheet collectionPicker=${collectionPickerFor != null} search=$searchActive" +
            " mini=$miniActive exoWindow=$exoWindowMode clockTrigger=$clockSelectTrigger" +
            " rows=${rows.size} focusRow=${grid.focusRow} topNavHasFocus=${topNavHasFocus.value}" +
            " guideHasFocus=$guideHasFocus]"
    }
    if (isTv) {
        val gateKey = traceGates().substringBefore(" rows=")
        LaunchedEffect(gateKey) { com.aeriotv.android.ui.tv.TvFocusTrace.guide("gates ${traceGates()}") }
    }
    LaunchedEffect(isTv, rows.isEmpty, tabActive) {
        if (!tabActive) return@LaunchedEffect
        if (isTv && !rows.isEmpty) {
            repeat(12) { attempt ->
                if (gridHasFocus) {
                    if (attempt > 0) com.aeriotv.android.ui.tv.TvFocusTrace.guide("refocus after=launch-loop result=success attempts=$attempt")
                    return@LaunchedEffect
                }
                // The user is walking the tab bar (selection follows focus
                // there): leave focus in the bar; Down brings them in.
                if (topNavHasFocus.value) {
                    com.aeriotv.android.ui.tv.TvFocusTrace.guide("refocus after=launch-loop result=skipped reason=topNavHasFocus attempts=$attempt")
                    return@LaunchedEffect
                }
                // GH #90: a held Left that minimized the player also opened
                // the group sidebar as the guide composed; the sidebar owns
                // focus then, and this loop must not pull it back to the grid.
                if (groupSidebarOpen) {
                    com.aeriotv.android.ui.tv.TvFocusTrace.guide("refocus after=launch-loop result=skipped reason=sidebarOpen attempts=$attempt")
                    return@LaunchedEffect
                }
                runCatching { gridFocus.requestFocus() }
                delay(100L)
            }
            com.aeriotv.android.ui.tv.TvFocusTrace.guide("refocus after=launch-loop result=${if (gridHasFocus) "success" else "failure"} attempts=12 ${traceGates()}")
        }
    }
    // Down out of the top nav has to land somewhere deterministic. The bar's
    // onExit only cancels the default geometric move when the tab published an
    // entry point, and Live TV never did: the guide fills the screen UNDER the
    // overlaid bar, so its focus rect is not "below" the bar and the geometric
    // search found nothing. Frankie B. 2026-09-15: Down from tab:LiveTV was
    // declined twice in a row (guide-screen(focus-not-in-grid)) with focus
    // stuck in the bar. Publish the guide's entry point, mirroring the Up
    // chain: the pill row when it is there, otherwise the grid itself.
    val tabEntryFocus = com.aeriotv.android.feature.main.LocalTvTabEntryFocus.current
    val pillsShownForEntry = isTv && !sidebarGroupMode && !favoritesOnly && pillItems.isNotEmpty()
    androidx.compose.runtime.DisposableEffect(isTv, tabActive, pillsShownForEntry) {
        if (isTv && tabActive) tabEntryFocus.value = if (pillsShownForEntry) pillsFocus else gridFocus
        onDispose { if (tabEntryFocus.value === pillsFocus || tabEntryFocus.value === gridFocus) tabEntryFocus.value = null }
    }

    // Stranded-focus watchdog (Frankie B. 2026-09-15). A failed hand-off out of
    // the grid (Up from the clock when the pill requester is unattached and the
    // bar's onEnter lands nowhere), or a rows rebuild that disposes the focused
    // node, can leave focus on NOTHING: [FOCUS] grid:r0 -> none with no owner
    // after it, and from there every D-pad key is declined. Reclaim only when
    // focus is outside the guide AND outside the nav, i.e. genuinely nowhere,
    // and only while no overlay is up, so this never fights the single-owner
    // model or pulls focus off chrome that legitimately has it.
    if (isTv) {
        val stranded = tabActive && !rows.isEmpty && !guideHasFocus && !topNavHasFocus.value &&
            !groupSidebarOpen && !showManageGroups && !showJumpSheet && !searchActive &&
            menuFor == null && programInfoTarget == null && recordTarget == null && collectionPickerFor == null
        LaunchedEffect(stranded) {
            if (!stranded) return@LaunchedEffect
            // Let a legitimate hand-off (dialog opening, route change, the
            // bar claiming focus a frame later) settle before assuming a trap.
            delay(350L)
            if (guideHasFocus || topNavHasFocus.value) return@LaunchedEffect
            val ok = runCatching { gridFocus.requestFocus() }.getOrDefault(false)
            com.aeriotv.android.ui.tv.TvFocusTrace.guide("refocus after=stranded result=$ok ${traceGates()}")
        }
    }

    // Logan 2026-09-02: backing out of a full-screen channel lands the guide
    // on the channel that is still playing (now in the mini player), not
    // wherever the grid was before. Keyed on the mini's channel so it fires
    // on the fullscreen -> mini hand-off and stays quiet otherwise.
    LaunchedEffect(miniChannelId, rows.isEmpty, tabActive) {
        if (!tabActive) return@LaunchedEffect
        val id = miniChannelId ?: return@LaunchedEffect
        if (!isTv || rows.isEmpty) return@LaunchedEffect
        val found = grid.focusChannel(id)
        val requested = if (found) runCatching { gridFocus.requestFocus() }.getOrDefault(false) else false
        if (isTv) {
            androidx.compose.runtime.withFrameNanos { }
            com.aeriotv.android.ui.tv.TvFocusTrace.guide("refocus after=mini-channel channel=$id found=$found requested=$requested gridHasFocus=$gridHasFocus cell=${guideTraceCell(grid)} ${traceGates()}")
        }
    }

    // Returning from a catch-up replay launched from this guide: the player
    // route disposed the guide, so the grid state above is fresh (row 0, now).
    // Put the launched cell and the panned timeline back, snapped (no ease),
    // and hold grid focus against the tab bar's one-shot initial pill pull,
    // which lands a few frames after the guide recomposes.
    LaunchedEffect(rows.isEmpty, tabActive) {
        if (!isTv || !tabActive || rows.isEmpty) return@LaunchedEffect
        val ret = GuideCatchupReturn.consume() ?: return@LaunchedEffect
        val row = grid.rows.indexOfChannel(ret.channelId)
        if (row < 0) {
            com.aeriotv.android.ui.tv.TvFocusTrace.guide("refocus after=catchup-return channel=${ret.channelId} found=false ${traceGates()}")
            return@LaunchedEffect
        }
        grid.scrollViewportTo(ret.viewportStartMs, animated = false)
        grid.focusRowAt(row, ret.cellStartMs)
        var held = 0
        var attempts = 0
        while (attempts < 20 && held < 6) {
            if (gridHasFocus) {
                held++
            } else {
                held = 0
                runCatching { gridFocus.requestFocus() }
            }
            attempts++
            delay(100L)
        }
        com.aeriotv.android.ui.tv.TvFocusTrace.guide("refocus after=catchup-return channel=${ret.channelId} found=true result=${if (gridHasFocus) "success" else "failure"} attempts=$attempts cell=${guideTraceCell(grid)} viewportStart=${grid.viewportStartMs} ${traceGates()}")
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
                if (state.selectedGroup == token) viewModel.onGroupSelected(fallbackGroup)
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
        } else runCatching { pillsFocus.requestFocus() }.getOrDefault(false)
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
            com.aeriotv.android.core.remote.GuideRemoteAction.RECORD -> {
                val row = grid.focusRow; val cell = grid.focusedCell()
                // Same gate as the program menu's Record item.
                if (row >= 0 && cell != null && !cell.isPlaceholder && cell.endMillis > nowMs &&
                    (nowMs in cell.startMillis until cell.endMillis || canRecordToServer)
                ) {
                    val ch = grid.rows.channel(row)
                    recordTarget = cell.toInfoTarget(ch.name, ch.dispatcharrChannelId)
                }
                true
            }
            com.aeriotv.android.core.remote.GuideRemoteAction.OPEN_SEARCH -> { onOpenSearch(); true }
            com.aeriotv.android.core.remote.GuideRemoteAction.JUMP_TO_DAY -> { showJumpSheet = true; true }
            else -> false
        }
    }
    // Sidebar group selection (Logan 2026-09-14, Apple TV parity with
    // ChannelListView dismissGuideSidebar): focus PREVIEWS a group in memory
    // (persist = false). OK or Right commits (persists) and closes. Every
    // non-commit close (Back, Manage Groups dismissal, tab change, focus
    // leaving) puts back the group the sidebar opened with. Opening Manage
    // Groups does NOT restore: like Apple the preview stays behind the sheet
    // and the restore waits for the sidebar to actually close.
    val restoreSidebarGroup: () -> Unit = {
        sidebarOriginalGroup?.takeIf { it != state.selectedGroup }?.let { viewModel.onGroupSelected(it, persist = false) }
    }
    val previewSidebarGroup: (String) -> Unit = { token ->
        // A debounce that lands after close or commit, or while the Manage
        // Groups sheet is up, must not apply a group.
        if (groupSidebarOpen && !showManageGroups && sidebarOriginalGroup != null && token != state.selectedGroup) {
            viewModel.onGroupSelected(token, persist = false)
        }
    }
    val commitSidebarGroup: (String) -> Unit = { token ->
        sidebarOriginalGroup = null
        // Always persist: an equal token may only have been previewed.
        viewModel.onGroupSelected(token)
        groupSidebarOpen = false
        runCatching { gridFocus.requestFocus() }
    }
    val openSidebarManageGroups: () -> Unit = {
        manageGroupsFromSidebar = true
        showManageGroups = true
    }
    // Row the sidebar refocuses after Manage Groups: the committed group, or
    // All-style fallback when that group was just hidden (Apple
    // manageGroupsSheet onDismiss resets to fallbackGroup the same way).
    val sidebarActiveToken = (sidebarOriginalGroup ?: state.selectedGroup).let { token ->
        if (token in groups || token.startsWith(ChannelCollection.TOKEN_PREFIX)) token else fallbackGroup
    }
    LaunchedEffect(sidebarActiveToken) {
        // Keep Back's restore target in step with a hidden-group fallback.
        if (sidebarOriginalGroup != null && sidebarOriginalGroup != sidebarActiveToken && allGroupNames.isNotEmpty()) {
            sidebarOriginalGroup = sidebarActiveToken
        }
    }
    // Safety net for every non-commit close: restore, then drop the snapshot.
    LaunchedEffect(groupSidebarOpen) {
        if (groupSidebarOpen) return@LaunchedEffect
        manageGroupsFromSidebar = false
        restoreSidebarGroup()
        sidebarOriginalGroup = null
    }
    LaunchedEffect(tabActive) {
        if (!tabActive && groupSidebarOpen) groupSidebarOpen = false
    }
    val sidebarPlaylistId = state.playlist?.id
    LaunchedEffect(sidebarPlaylistId) {
        // The new playlist restores its own saved group; the old snapshot
        // belongs to the previous playlist and must not be put back.
        if (groupSidebarOpen) { sidebarOriginalGroup = null; groupSidebarOpen = false }
    }
    BackHandler(enabled = tabActive && groupSidebarOpen) {
        restoreSidebarGroup()
        sidebarOriginalGroup = null
        groupSidebarOpen = false
        runCatching { gridFocus.requestFocus() }
    }

    // With a mini player up, Back belongs to the player host (expand / close),
    // exactly as the old guide: the ladder must never finish the Activity
    // while a stream is playing.
    BackHandler(enabled = tabActive && !miniActive && !groupSidebarOpen && menuFor == null && programInfoTarget == null && recordTarget == null) {
        when (grid.back(nowMs)) {
            GuideGridState.BackStep.RESTORED_NOW_AND_TOP, GuideGridState.BackStep.TOP -> Unit
            GuideGridState.BackStep.NONE -> {
                // Logan 2026-09-01: Back at the top of the guide must never close
                // the app. At now and the top row it is a consumed no-op and the
                // group stays as picked, matching Apple TV's Menu (now + top only).
            }
        }
    }

    // Phone drawer rows: collections where their pill placement puts them,
    // around the Favorites / All / group tokens (Apple PhoneGroupDrawer).
    val groupLabelFor: (String) -> String = { token ->
        ChannelCollection.idFromToken(token)
            ?.let { id -> collections.firstOrNull { it.id == id }?.name }
            ?: com.aeriotv.android.feature.livetv.groupSidebarLabel(token)
    }

    var guideTopPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { guideTopPx = it.positionInRoot().y }
        .onFocusChanged { guideHasFocus = it.hasFocus }
        .onPreviewKeyEvent { e ->
            // Trace only, never consumes: a D-pad key inside the guide that the
            // grid node will not see (focus is on the banner, pills, sidebar...).
            if (isTv && !gridHasFocus && e.type == KeyEventType.KeyDown) {
                val name = when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> "Center"
                    else -> com.aeriotv.android.ui.tv.TvFocusTrace.nameOf(e)
                }
                if (name != null) com.aeriotv.android.ui.tv.TvFocusTrace.key(name, false, "guide-screen(focus-not-in-grid) ${traceGates()}")
            }
            false
        }) {
    Row(modifier = Modifier.fillMaxSize()) {
    if (groupSidebarOpen && !isTv) {
        GuideGroupSidebarPane(
            groups = groups,
            selectedToken = state.selectedGroup,
            topOffset = 0.dp,
            onCommit = commitSidebarGroup,
            onManageGroups = openSidebarManageGroups,
            hiddenGroupCount = hiddenGroups.size,
        )
    }
    Column(modifier = Modifier.weight(1f).fillMaxSize().then(if (isTv) Modifier else Modifier.statusBarsPadding())) {
        if (!isTv) {
            // Phone / tablet: NO title bar (Logan 2026-09-05, Apple parity).
            // The header row carries the groups control, the pills or the
            // active group name + count, Search, Sort and the List / Guide
            // toggle; the Column above already applies the status-bar inset.
            val retainedVm: RetainedChannelsViewModel = hiltViewModel()
            LiveTvPhoneHeaderRow(
                sidebarMode = phoneSidebarMode,
                activeGroupLabel = groupLabelFor(state.selectedGroup),
                channelCount = displayChannels.size,
                onOpenGroups = { phoneDrawerOpen = true },
                hiddenGroupsCount = hiddenGroups.size,
                onManageGroups = { showManageGroups = true },
                showPills = !favoritesOnly && (groups.size > 1 || collections.isNotEmpty() || hiddenGroups.isNotEmpty()),
                groups = groups,
                selectedGroup = state.selectedGroup,
                onSelectGroup = { viewModel.onGroupSelected(it) },
                collections = collections,
                collectionPillItem = collectionPillItem,
                searchActive = searchActive,
                onToggleSearch = { searchActive = !searchActive; if (!searchActive) viewModel.onSearchQueryChange("") },
                extraActions = {
                    RetainedChannelsAction(
                        viewModel = retainedVm, buttonSize = 38.dp, iconSize = 18.dp,
                        onJumpToChannel = { id -> state.channels.firstOrNull { it.id == id }?.let(onChannelClick) },
                    )
                },
                sortMode = state.sortMode,
                onSortModeChange = viewModel::onSortModeChange,
                onJumpToDay = { showJumpSheet = true },
                canToggleViewMode = canToggleViewMode,
                showingGuide = viewMode == LiveTVViewMode.Guide,
                onToggleViewMode = onToggleViewMode,
            )
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
        }
        if (previewMode) {
            val topNav = com.aeriotv.android.feature.main.LocalTvTopNavFocusRequester.current
            val pillsShown = !sidebarGroupMode && !favoritesOnly
            GuidePreviewBanner(
                program = previewProgram,
                channel = previewChannel,
                nowMs = nowMs,
                onOpenInfo = {
                    previewProgram?.let { cell ->
                        programInfoTarget = cell.toInfoTarget(previewChannel?.name ?: "", previewChannel?.dispatcharrChannelId)
                    }
                },
                descriptionFocus = bannerFocus,
                downTarget = if (pillsShown) pillsFocus else gridFocus,
                upTarget = topNav,
                // tvOS: Down from the description lands on the clock when
                // there is no pill row between them.
                onDown = if (pillsShown) null else ({ clockSelectTrigger += 1; true }),
                miniActive = miniActive,
                // tvOS pulls the banner 28 pt up under the tab bar so eight
                // rows still fit (ChannelListView, Logan 2026-09-05); halved.
                modifier = Modifier.layout { measurable, constraints ->
                    val lift = GuidePreviewBanner.tvLift.roundToPx()
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, (placeable.height - lift).coerceAtLeast(0)) { placeable.placeRelative(0, -lift) }
                },
            )
        }
        if (isTv && !sidebarGroupMode && !favoritesOnly) GroupPills(
            onManageGroups = { showManageGroups = true },
            hiddenGroupCount = hiddenGroups.size,
            items = pillItems,
            selected = state.selectedGroup,
            onSelect = { viewModel.onGroupSelected(it) },
            rowFocus = pillsFocus,
            onDown = { runCatching { gridFocus.requestFocus() }.getOrDefault(false) },
            leadInset = railWidth,
        )
        // Channel Preview OFF + corner mini Active: the mini has no banner art
        // card to share a baseline with, so it gets its OWN slot here -- a
        // reserved band immediately above the time header whose measured bottom
        // IS the timeline top. The mini's bottom edge is anchored to that
        // (published below), so the timeline and every channel row start below
        // the mini instead of being drawn under it (Logan 2026-09-13).
        val reserveMiniSlot = isTv && !previewMode && miniActive
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
        androidx.compose.runtime.DisposableEffect(reserveMiniSlot) {
            onDispose { if (reserveMiniSlot) MiniPlayerChrome.timelineTopPx.value = 0f }
        }
        // Shift guide: the pane docks at the grid's top edge and only the
        // grid (time header + rows) narrows; the banner, pills and mini slot
        // above keep the full width. The pane slides in over its own slot, so
        // the grid width changes once instead of re-laying rows every frame.
        Row(modifier = Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(
            visible = sidebarShiftMode && groupSidebarOpen,
            enter = androidx.compose.animation.slideInHorizontally(
                animationSpec = androidx.compose.animation.core.tween(180),
            ) { -it },
            exit = androidx.compose.animation.ExitTransition.None,
        ) {
            GuideGroupSidebarPane(
                groups = groups,
                selectedToken = state.selectedGroup,
                topOffset = 0.dp,
                onPreview = previewSidebarGroup,
                onCommit = commitSidebarGroup,
                refocusToken = sidebarActiveToken,
                refocusRequest = sidebarRefocusRequest,
                onManageGroups = openSidebarManageGroups,
                hiddenGroupCount = hiddenGroups.size,
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
        if (rows.isEmpty && favoritesOnly && favoritesOrNull == null) {
            // Favorites not loaded yet: draw nothing rather than flash the
            // empty-group notice for a frame (Streamer 2026-09-03).
        } else if (rows.isEmpty) {
            EmptyGroupNotice(
                isSearching = state.searchQuery.isNotBlank(),
                onShowAllChannels = { viewModel.onGroupSelected(fallbackGroup) },
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
                onPlay = { channel, cell ->
                    // Already-aired + within the catch-up window: play it from
                    // the start right away (no menu first). The rail tap hands
                    // us the cell airing NOW, so tapping the logo still tunes live.
                    if (!cell.isPlaceholder && channel.canReplay(cell, nowMs)) startCatchup(channel, cell)
                    // OK on the channel already in the corner mini promotes the
                    // mini to fullscreen instead of re-tuning the same stream.
                    else if (isTv && miniChannelId == channel.id) miniPlayerVm.session.requestResume()
                    else onChannelClick(channel)
                },
                onOpenMenu = { channel, cell -> menuFor = channel to cell; menuGuard.arm() },
                jumpLabel = jumpLabel,
                onClockTap = snapToNow,
                onClockLongPress = { showJumpSheet = true },
                // Favorites (TV): no pills above the grid, so UP must leave
                // through the content group's exit redirect (which lands on
                // the SELECTED tab's pill); a direct request on the bar's
                // requester landed on the leftmost pill and, with
                // selection-follows-focus, switched to Live TV.
                onLeaveTop = {
                    when {
                        // Channel Preview with no pill row: the banner's
                        // description is the next stop above the clock.
                        previewMode && (favoritesOnly || sidebarGroupMode) && previewProgram?.description?.isNotBlank() == true ->
                            runCatching { bannerFocus.requestFocus() }.getOrDefault(false)
                        favoritesOnly -> guideFocusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
                        sidebarGroupMode -> false
                        else -> runCatching { pillsFocus.requestFocus() }.getOrDefault(false)
                    }
                },
                compact = previewMode,
                clockSelectTrigger = clockSelectTrigger,
                remoteAction = { slot -> remoteMap.guideAction(slot, sidebarGroupMode) },
                onHostAction = hostAction,
                focusRequester = gridFocus,
                // Shift guide: the docked pane owns focus; the grid rejoins on
                // close. Disabled only once focus has left the grid, so turning
                // it off never clears focus onto the shell (the nav bar selects
                // on focus) before the pane's rows claim it.
                focusEnabled = !(sidebarShiftMode && groupSidebarOpen && !gridHasFocus),
                onGridFocusChanged = { gridHasFocus = it },
                traceGates = traceGates,
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
    }
    }
    // tvOS drawer (ChannelListView 2026-09-05): the rail overlays the guide
    // under the time header, the rest of the tab dims 45%, the grid does not
    // shift. Right or OK commit, Back reverts (handlers unchanged). The Shift
    // guide sidebar layout docks the pane in the guide Column instead.
    if (isTv) {
        // Under the time header wherever it sits: below the Channel Preview
        // banner (lifted 14 dp under the bar) when that layout is on. Drawn
        // in the shell's full-screen slot so the scrim dims the whole screen,
        // nav bar included (tvOS); the pane is offset by the guide's own top.
        val drawerTop = headerHeight + (if (previewMode) GuidePreviewBanner.height - 14.dp else 0.dp)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val guideTop = with(density) { guideTopPx.toDp() }
        val drawerSlot = com.aeriotv.android.feature.main.LocalTvFullScreenOverlay.current
        val drawerOverlay: @Composable () -> Unit = {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            GuideGroupSidebarPane(
                groups = groups,
                selectedToken = state.selectedGroup,
                topOffset = guideTop + drawerTop,
                onPreview = previewSidebarGroup,
                onCommit = commitSidebarGroup,
                refocusToken = sidebarActiveToken,
                refocusRequest = sidebarRefocusRequest,
                onManageGroups = openSidebarManageGroups,
                hiddenGroupCount = hiddenGroups.size,
            )
        }
        if (drawerSlot != null || sidebarShiftMode) {
            // As with Jump To: the shell reseats focus (Refresh circle) once
            // the overlay is gone, after the commit asked for the grid. Shift
            // guide needs the same retry: the grid turns focusable again only
            // on the next composition, and its width settles (re-clamping the
            // viewport) before focus lands.
            var drawerWasOpen by remember { mutableStateOf(false) }
            LaunchedEffect(groupSidebarOpen) {
                if (groupSidebarOpen) { drawerWasOpen = true; return@LaunchedEffect }
                if (!drawerWasOpen) return@LaunchedEffect
                drawerWasOpen = false
                repeat(3) { androidx.compose.runtime.withFrameNanos { } }
                val requested = runCatching { gridFocus.requestFocus() }.getOrDefault(false)
                androidx.compose.runtime.withFrameNanos { }
                com.aeriotv.android.ui.tv.TvFocusTrace.guide("refocus after=sidebar-close requested=$requested result=${if (gridHasFocus) "success" else "failure"} ${traceGates()}")
            }
        }
        if (sidebarShiftMode) {
            // Docked beside the grid in the guide Column above; nothing here.
        } else if (drawerSlot != null) {
            androidx.compose.runtime.DisposableEffect(groupSidebarOpen) {
                if (groupSidebarOpen) drawerSlot.value = drawerOverlay
                else if (drawerSlot.value === drawerOverlay) drawerSlot.value = null
                onDispose { if (drawerSlot.value === drawerOverlay) drawerSlot.value = null }
            }
        } else if (groupSidebarOpen) {
            drawerOverlay()
        }
    }
    // TV Jump To: drawn in the shell's full-screen slot so the scrim covers
    // the tab bar too; drawn here when no shell provides one.
    val fullScreenSlot = com.aeriotv.android.feature.main.LocalTvFullScreenOverlay.current
    val jumpOverlay: @Composable () -> Unit = {
        com.aeriotv.android.feature.livetv.GuideJumpTvOverlay(
            daysBack = epgDaysBackOffered.coerceIn(0, 14),
            daysAhead = epgDaysAheadOffered.coerceIn(1, 14),
            onJump = startJump,
            onBackToNow = snapToNow,
            onDismiss = { showJumpSheet = false; runCatching { gridFocus.requestFocus() } },
        )
    }
    if (isTv && fullScreenSlot != null) {
        androidx.compose.runtime.DisposableEffect(showJumpSheet) {
            fullScreenSlot.value = if (showJumpSheet) jumpOverlay else null
            onDispose { if (fullScreenSlot.value === jumpOverlay) fullScreenSlot.value = null }
        }
        // The slot's teardown drops focus onto the shell's first focusable
        // (the Refresh circle) AFTER onDismiss asked for the grid; ask again
        // once the overlay is gone.
        var jumpWasOpen by remember { mutableStateOf(false) }
        LaunchedEffect(showJumpSheet) {
            if (showJumpSheet) { jumpWasOpen = true; return@LaunchedEffect }
            if (!jumpWasOpen) return@LaunchedEffect
            jumpWasOpen = false
            repeat(3) { androidx.compose.runtime.withFrameNanos { } }
            val requested = runCatching { gridFocus.requestFocus() }.getOrDefault(false)
            androidx.compose.runtime.withFrameNanos { }
            com.aeriotv.android.ui.tv.TvFocusTrace.guide("refocus after=jump-close requested=$requested result=${if (gridHasFocus) "success" else "failure"} ${traceGates()}")
        }
    } else if (showJumpSheet && isTv) {
        jumpOverlay()
    }
    if (!isTv) {
        val drawerTokens = remember(groups, collections) {
            collections.filter { it.placement == ChannelCollection.PLACEMENT_BEGINNING }.map { ChannelCollection.token(it.id) } +
                groups +
                collections.filter { it.placement != ChannelCollection.PLACEMENT_BEGINNING }.map { ChannelCollection.token(it.id) }
        }
        PhoneGroupDrawerHost(
            open = phoneDrawerOpen,
            onDismiss = { phoneDrawerOpen = false },
            tokens = drawerTokens,
            selected = state.selectedGroup,
            labelFor = groupLabelFor,
            onSelect = { viewModel.onGroupSelected(it) },
            // A drag in the drawer is a manual order (the saved order is only
            // read in Manual mode), so the mode flips with the first drag.
            onReorder = { order ->
                settingsVm.setGroupSortMode(GroupSortMode.Manual.name)
                settingsVm.setGroupOrder(order)
            },
            onManageGroups = { phoneDrawerOpen = false; showManageGroups = true },
            hiddenGroupCount = hiddenGroups.size,
        )
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
                allGroups = allGroupNames, hiddenGroups = effectiveHidden,
                onDismiss = {
                    showManageGroups = false
                    if (manageGroupsFromSidebar && groupSidebarOpen) {
                        // Apple TV parity: only the sheet closes. The sidebar
                        // stays open with focus on the active group row; its
                        // own preview / commit / Back rules carry on from there.
                        manageGroupsFromSidebar = false
                        sidebarRefocusRequest++
                    } else {
                        // Opened elsewhere: close the sidebar too, since leaving
                        // it open with focus in the grid made a later held Left
                        // a no-op (already "open"). Logan 2026-09-02.
                        manageGroupsFromSidebar = false
                        restoreSidebarGroup(); sidebarOriginalGroup = null; groupSidebarOpen = false
                        runCatching { gridFocus.requestFocus() }
                    }
                },
                reorderEnabled = true, sortMode = groupSortMode,
                onSortModeChange = { settingsVm.setGroupSortMode(it.name) },
                // Only meaningful with the Sidebar Menu group selector.
                sidebarLayout = if (groupSelector == "sidebar") guideSidebarLayout else null,
                onSidebarLayoutChange = { settingsVm.setGuideSidebarLayout(it) },
                onCommit = { hidden, order ->
                    com.aeriotv.android.feature.livetv.applyManagedGroups(
                        hidden, hiddenGroups, recentGroupVisible,
                        setHiddenGroups = { settingsVm.setHiddenGroups(it) },
                        setRecentVisible = { settingsVm.setRecentGroupVisible(it) },
                    )
                    order?.let { settingsVm.setGroupOrder(it) }
                },
            )
        } else {
            ManageGroupsSheet(
                allGroups = allGroupNames, hiddenGroups = effectiveHidden,
                onSave = { committed ->
                    com.aeriotv.android.feature.livetv.applyManagedGroups(
                        committed, hiddenGroups, recentGroupVisible,
                        setHiddenGroups = { settingsVm.setHiddenGroups(it) },
                        setRecentVisible = { settingsVm.setRecentGroupVisible(it) },
                    )
                },
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
        val watchFromStart: () -> Unit = { startCatchup(channel, cell) }
        val reminderSet = key in reminderKeys
        val toggleReminder: () -> Unit = {
            if (reminderSet) remindersVm.cancelReminder(key)
            else remindersVm.setReminder(channel.name, cell.title, cell.startMillis, cell.endMillis, channel.id)
        }
        if (!isTv) {
            // Phone and tablet: Material 3 modal bottom sheet (the platform's
            // long-press action surface; Logan 2026-09-09), items in the iPhone
            // context-menu order. Jump To lives on the clock cell here.
            val sheetActions = buildList {
                if (isLive && channel.url.isNotBlank()) add(TvMenuAction("Watch", Icons.Filled.PlayArrow) { onChannelClick(channel) })
                else if (replayable) add(TvMenuAction("Watch from Start", Icons.Outlined.History, onClick = watchFromStart))
                add(TvMenuAction(if (isFavorite) "Remove from Favorites" else "Add to Favorites", if (isFavorite) Icons.Outlined.StarOutline else Icons.Filled.Star) { favoritesVm.toggle(channel) })
                add(TvMenuAction(if (inMultiview) "Remove from Multiview" else "Add to Multiview", Icons.Outlined.GridView, enabled = canAddToMultiview) { multiviewStore.toggle(channel) })
                add(TvMenuAction("Add Channel to Collection", Icons.Outlined.CreateNewFolder) { collectionPickerFor = channel.id to channel.name })
                if (!cell.isPlaceholder) {
                    add(TvMenuAction("Program Info", Icons.Outlined.Info) { programInfoTarget = cell.toInfoTarget(channel.name, channel.dispatcharrChannelId) })
                    if (canRecord) add(TvMenuAction(if (isLive) "Record from Now" else "Record", Icons.Outlined.FiberManualRecord) { recordTarget = cell.toInfoTarget(channel.name, channel.dispatcharrChannelId) })
                    if (cell.startMillis > nowMs) add(TvMenuAction(if (reminderSet) "Cancel Reminder" else "Set Reminder", Icons.Outlined.Notifications, onClick = toggleReminder))
                }
            }
            com.aeriotv.android.feature.livetv.LiveTvActionSheet(
                title = cell.title,
                subtitle = channel.name,
                actions = sheetActions,
                onDismiss = { menuFor = null },
            )
        } else {
        val actions = buildList {
            add(TvMenuAction(if (isFavorite) "Remove from Favorites" else "Add to Favorites") { favoritesVm.toggle(channel) })
            add(TvMenuAction(if (inMultiview) "Remove from Multiview" else "Add to Multiview", enabled = canAddToMultiview) { multiviewStore.toggle(channel) })
            add(TvMenuAction("Add Channel to Collection") { collectionPickerFor = channel.id to channel.name })
            // Jump To and Back to Now live on the clock cell now (tvOS parity).
            if (!cell.isPlaceholder) {
                add(TvMenuAction("Program Info") { programInfoTarget = cell.toInfoTarget(channel.name, channel.dispatcharrChannelId) })
                if (canRecord) add(TvMenuAction(if (isLive) "Record from Now" else "Record") { recordTarget = cell.toInfoTarget(channel.name, channel.dispatcharrChannelId) })
                if (replayable) add(TvMenuAction("Watch from Start", onClick = watchFromStart))
                if (cell.startMillis > nowMs) {
                    add(TvMenuAction(if (reminderSet) "Cancel Reminder" else "Set Reminder", onClick = toggleReminder))
                }
            }
        }
        TvActionMenuDialog(title = cell.title, actions = actions, guard = menuGuard, onDismiss = { menuFor = null })
        }
    }
    if (showJumpSheet && !isTv) {
        com.aeriotv.android.feature.livetv.GuideJumpSheet(
            // Days follow the EPG that is actually loaded (Logan 2026-09-10),
            // back no further than the grid's own history window.
            daysBack = epgDaysBackOffered.coerceIn(0, 14),
            daysAhead = epgDaysAheadOffered.coerceIn(1, 14),
            onJump = startJump,
            onBackToNow = snapToNow,
            onDismiss = { showJumpSheet = false; runCatching { gridFocus.requestFocus() } },
        )
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
    /** Focus entry for the whole row: lands on the selected pill. */
    rowFocus: FocusRequester,
    onDown: () -> Boolean,
    /** Logan 2026-09-03: pills mode had no way to show/hide groups (the
     *  sidebar has its Manage button, the pill row had none). Trailing pill. */
    onManageGroups: () -> Unit = {},
    hiddenGroupCount: Int = 0,
    /** tvOS: the first pill's left edge sits on the program column. */
    leadInset: androidx.compose.ui.unit.Dp = 12.dp,
) {
    val listState = rememberLazyListState()
    val topNav = com.aeriotv.android.feature.main.LocalTvTopNavFocusRequester.current
    // The row's focus entry used to be a requester on the FIRST pill only.
    // LazyRow disposes pills scrolled out of view, so once the user walked
    // right and picked a later group the first pill was gone, the requester
    // was unattached, and Up from the guide clock went nowhere (the user was
    // stuck on the clock). The requester now sits on the row itself (a focus
    // group) and entry is routed to the SELECTED pill, the tvOS behaviour.
    val selectedIndex = items.indexOfFirst { it.first == selected }
    val selectedPillFocus = remember { FocusRequester() }
    var rowHasFocus by remember { mutableStateOf(false) }
    // A selection made from OUTSIDE the row (sidebar, remote shortcut) glides
    // the new pill into view. The row is never scrolled when focus merely
    // leaves it: where the user left it is where it stays.
    LaunchedEffect(selectedIndex) {
        if (rowHasFocus || selectedIndex < 0) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) {
            val viewport = listState.layoutInfo.viewportEndOffset
            listState.animateScrollToItem(selectedIndex, scrollOffset = -(viewport / 3))
        }
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(start = leadInset, end = 12.dp, top = 6.dp, bottom = 6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .focusRequester(rowFocus)
            .onFocusChanged { rowHasFocus = it.hasFocus }
            // focusProperties must precede focusGroup() to apply to the group.
            .focusProperties {
                if (topNav != null) up = topNav
                onEnter = {
                    // Selected pill when it is in view; otherwise the default
                    // search picks the first visible pill, so entry always
                    // lands inside the row as the user left it.
                    if (listState.layoutInfo.visibleItemsInfo.any { it.index == selectedIndex }) {
                        selectedPillFocus.requestFocus()
                    }
                }
            }
            .focusGroup(),
    ) {
        items(items, key = { it.first }) { (group, label) ->
            // TV chrome canon (ui/tv/TvChrome.kt): capsule, accent fill when
            // selected, white ring focused+selected / accent ring focused+
            // unselected. One focus target only (clickable() inside TvPill).
            val interaction = remember { MutableInteractionSource() }
            com.aeriotv.android.ui.tv.TvPill(
                label = label,
                selected = group == selected,
                onClick = { onSelect(group) },
                icon = if (group == com.aeriotv.android.feature.playlist.PlaylistViewModel.FAVORITES_GROUP) Icons.Filled.Star else null,
                interactionSource = interaction,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .then(if (group == selected) Modifier.focusRequester(selectedPillFocus) else Modifier)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) onDown() else false
                    },
            )
        }
        // tvOS (GH #57): the round Manage Groups button sits AFTER the last
        // group so it reads as an action on the row, not another chip.
        item(key = "__manage__") {
            com.aeriotv.android.feature.livetv.TvManageGroupsCircle(
                hiddenGroupsCount = hiddenGroupCount,
                onClick = onManageGroups,
                modifier = Modifier
                    .padding(start = 0.dp)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) onDown() else false
                    },
            )
        }
    }
}

private const val QUANTUM_MS = 15 * 60_000L

/**
 * The guide cell a TV catch-up replay was launched from, plus the timeline
 * start at launch. Set just before the player route opens and consumed once
 * by the guide that composes after it pops. Process memory only.
 */
internal object GuideCatchupReturn {
    data class Target(val channelId: String, val cellStartMs: Long, val viewportStartMs: Long)

    @Volatile private var pending: Target? = null

    fun set(channelId: String, cellStartMs: Long, viewportStartMs: Long) {
        pending = Target(channelId, cellStartMs, viewportStartMs)
    }

    fun consume(): Target? = pending.also { pending = null }
}

/** Share of a phone guide row (98dp) taken by secondary lines; drives Subtext Size growth. */
internal const val GUIDE_PHONE_SUBTEXT_SHARE = 0.6f

/** Share of a tablet / TV guide row taken by secondary lines. */
internal const val GUIDE_SUBTEXT_SHARE = 0.45f
