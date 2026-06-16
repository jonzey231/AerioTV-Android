package com.aeriotv.android.feature.livetv

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Star
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import com.aeriotv.android.feature.main.LocalTvTopNavFocusRequester
import com.aeriotv.android.feature.miniplayer.MiniPlayerSession
import com.aeriotv.android.feature.miniplayer.MiniPlayerViewModel
import com.aeriotv.android.ui.LocalCanRecordToServer
import com.aeriotv.android.core.preferences.GUIDE_SCALE_MAX
import com.aeriotv.android.core.preferences.GUIDE_SCALE_MIN
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import com.aeriotv.android.core.pip.findActivity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.db.entity.reminderKey
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.feature.favorites.FavoritesViewModel
import com.aeriotv.android.feature.settings.dpadFocusRing
import com.aeriotv.android.feature.settings.rememberIsTvDevice
import com.aeriotv.android.feature.multiview.MultiviewStoreHandle
import com.aeriotv.android.feature.multiview.rememberMultiviewStoreHandle
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.aeriotv.android.feature.reminders.RemindersViewModel
import com.aeriotv.android.feature.settings.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check

/**
 * EPG Guide grid (channels on Y, time on X). Mirrors iOS EPGGuideView.
 *
 * Layout:
 *   +---------+--------------------- horizontalScroll -------------------->
 *   | (corner)|  10:00 | 11:00 | 12:00 | 13:00 | 14:00 | ...
 *   +---------+----------------------------------------------------------
 *   | [logo]  |  [programme]  [programme]      [programme]
 *   | ESPN HD |
 *   +---------+----------------------------------------------------------
 *   | [logo]  |       [programme]    [programme]    [programme]
 *   | TBS HD  |
 *   +---------+----------------------------------------------------------
 *
 * A single horizontalScroll state is shared across the time-header row and every
 * channel row so they pan together. Programmes are absolutely positioned in
 * their row via Modifier.offset, with x derived from
 *   (programme.start - windowStart) / msPerDp.
 *
 * "Now" indicator: a 2dp vertical cyan line drawn over the programme strip at
 * the current-time x position, recomputed every minute.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun GuideScreen(
    onChannelClick: (M3UChannel) -> Unit,
    viewMode: LiveTVViewMode,
    canToggleViewMode: Boolean,
    onToggleViewMode: () -> Unit,
    onLaunchMultiview: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favoritesVm: FavoritesViewModel = hiltViewModel()
    val favoritesList by favoritesVm.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds = remember(favoritesList) { favoritesList.map { it.channelId }.toSet() }
    val settingsVm: SettingsViewModel = hiltViewModel()
    val palette by settingsVm.categoryPalette.collectAsStateWithLifecycle(initialValue = CategoryPaletteState.Default)
    val epgWindowHours by settingsVm.epgWindowHours.collectAsStateWithLifecycle(initialValue = 24)
    val showChannelLogos by settingsVm.showChannelLogos.collectAsStateWithLifecycle(initialValue = true)
    val multiviewStore = rememberMultiviewStoreHandle()
    // Observe the mini-player session so the guide's Back handler can stand
    // down while the mini is showing (see the BackHandler below for why).
    val miniPlayerVm: MiniPlayerViewModel = hiltViewModel()
    val miniState by miniPlayerVm.state.collectAsStateWithLifecycle()
    val miniActive = miniState is MiniPlayerSession.State.Active
    // Audit task #22: staged-channel banner. When the user has added at
    // least one channel to Multiview (via the row context menu / long
    // press), surface a "X channels staged" pill with a Play button at
    // the top of the guide so they can launch without backing out to the
    // dedicated tab.
    val stagedMultiview by multiviewStore.selected.collectAsStateWithLifecycle()
    // The "Play Multiview" staging banner sits above the control row + grid. On
    // TV the D-pad can't reliably climb the grid -> pills -> banner chain (UP
    // stalls on the group pills), leaving the banner -- and thus launching
    // Multiview -- unreachable. So when channels first get staged, pull focus to
    // the banner's Play button: the user presses OK to launch, or D-pad DOWN to
    // stage more. Keyed on the empty<->staged transition so it only grabs focus
    // on the FIRST add, not every subsequent one.
    val multiviewBannerFocus = remember { FocusRequester() }
    val isTvDevice = rememberIsTvDevice()
    // Guards the banner's launch clicks: armed by the focus pull below (the
    // Streamer's spurious OK-release lands on the newly-focused banner and
    // would launch Multiview instantly) and re-armed on each real launch so
    // an impatient double OK cannot navigate twice.
    val bannerGuard = com.aeriotv.android.core.tv.rememberTvMenuGuard()
    // Keyed on the staged COUNT (not just empty<->non-empty) so focus returns to
    // the banner after EVERY add -- you stage several channels (each add bounces
    // focus back to Play), then press OK to launch. Without this, only the first
    // add reached the banner and a multi-tile Multiview was impossible to launch.
    LaunchedEffect(stagedMultiview.size) {
        if (isTvDevice && stagedMultiview.isNotEmpty()) {
            bannerGuard.arm()
            // The banner composes the same frame the staged set flips non-empty;
            // retry briefly so the requester is attached before we focus it.
            repeat(10) {
                if (runCatching { multiviewBannerFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(16L)
            }
        }
    }
    // tvOS-style guide controls: a search field toggle + a group on/off filter.
    val hiddenGroups by settingsVm.hiddenGroups.collectAsStateWithLifecycle(initialValue = emptySet())
    val groupSortModeRaw by settingsVm.groupSortMode.collectAsStateWithLifecycle(initialValue = "Default")
    val groupOrder by settingsVm.groupOrder.collectAsStateWithLifecycle(initialValue = emptyList())
    val groupSortMode = GroupSortMode.from(groupSortModeRaw)
    var searchActive by remember { mutableStateOf(false) }
    var showManageGroups by remember { mutableStateOf(false) }

    // Guide timeline zoom (iOS guideScale). `liveScale` tracks the in-flight
    // pinch and the discrete selector; it's seeded from the persisted value and
    // re-synced whenever that changes (e.g. selector tap or another screen).
    // The pinch commits to the store on gesture end so we hit DataStore once
    // per gesture, not every frame.
    val storedScale by settingsVm.guideScale.collectAsStateWithLifecycle(initialValue = 1f)
    var liveScale by remember { mutableStateOf(storedScale) }
    LaunchedEffect(storedScale) { liveScale = storedScale }
    val scale = liveScale.coerceIn(GUIDE_SCALE_MIN, GUIDE_SCALE_MAX)
    // Android TV: keep rows DENSE so more channels fit without scrolling (like
    // the tvOS guide), and keep the channel rail narrow. Legibility comes from
    // text sizing inside the cells/rail, not from oversized rows. Phone/tablet
    // keep the GuideMetrics defaults.
    val isTv = rememberLiveTvFormFactor().isTv
    // tvOS pixelsPerHour = 600pt on a 1920x1080pt canvas; the proportional value
    // on the 960x540dp Android-TV canvas is 300 dp/hour (= 600 * 0.5). Phone keeps
    // the GuideMetrics.HOUR_WIDTH base (320dp), scaled by guideScale.
    val scaledHourWidth = (if (isTv) 300.dp else GuideMetrics.HOUR_WIDTH) * scale

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // The channel rail must stay a small slice of the viewport or it eats the
    // programme grid. tvOS uses channelColumnWidth = 240pt / 1920 = 12.5% of width;
    // on the 960dp-wide Android-TV canvas that is 120dp. Phone branches unchanged
    // (the 168dp default was ~half the screen on a compact width — phones, and the
    // Fold cover screen at ~344dp — so we clamp to a narrower rail there).
    val railWidth = when {
        isTv -> 120.dp
        screenWidthDp < 600 -> 118.dp
        else -> GuideMetrics.RAIL_WIDTH
    }
    // Row + time-header sized to tvOS PROPORTIONS on the 960x540dp Android-TV
    // canvas (NOT copied from tvOS point values, which would be ~2x too big).
    // tvOS rowHeight 110pt / 1080 = 10.19% -> 540dp * 0.1019 = 55dp;
    // timeHeader 50pt / 1080 = 4.63% -> 540dp * 0.0463 = 25dp.
    val rowHeight = if (isTv) 55.dp else GuideMetrics.ROW_HEIGHT
    val headerHeight = if (isTv) 25.dp else GuideMetrics.HEADER_HEIGHT
    // tvOS draws the guide grid separators as cyan (accentPrimary) hairlines, not
    // neutral gray; mirror that on TV so the grid reads as one continuous surface.
    // Phone keeps the existing gray surfaceVariant divider.
    val guideDivider = if (isTv) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surfaceVariant
    // Channel-to-channel separator: stronger than the header hairline so two
    // adjacent rows with the SAME category tint still read as distinct rows
    // (user request -- the 0.5dp / 15% line was nearly invisible between
    // same-colored channels).
    val guideRowDivider = if (isTv) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        else MaterialTheme.colorScheme.outline
    val guideRowDividerThickness = if (isTv) 0.75.dp else 1.dp

    var programInfoTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    // TV focus-on-dismiss for the Program Info / Record dialogs (user report:
    // Back from Program Info parked D-pad focus on the nav pills, not the
    // cell that opened it). The originating cell's row index + time column
    // are captured when the dialog opens; bumping the tick on dismissal
    // drives the refocus effect inside the grid, where guideNav and
    // listState are in scope.
    var dialogFocusOrigin by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var dialogFocusRestoreTick by remember { mutableStateOf(0) }

    // Tick "now" forward every 30s so the indicator + currently-airing tinting stay
    // accurate without forcing the whole tree to recompose on every frame.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    // Guide window: 1h of history before "now", then `epgWindowHours` ahead.
    // The user picks the span in Settings > Network > EPG Window
    // (6/12/24/36/48/72h or "All available"). iOS `epgWindowHours` parity.
    val windowStart = remember(nowMillis) {
        // Floor `now` to the start of the current hour to keep header labels clean.
        val hourMs = 3_600_000L
        (nowMillis / hourMs) * hourMs - hourMs
    }
    // "All available" (sentinel 0) spans from windowStart to the latest loaded
    // programme end, clamped to a 6h floor so a thin EPG still scrolls. A
    // numeric hour value is just that many hours wide.
    val windowDurationMs = remember(epgWindowHours, state.epgByChannel, windowStart) {
        if (epgWindowHours > 0) {
            epgWindowHours.toLong() * 3_600_000L
        } else {
            val latestEnd = state.epgByChannel.values.asSequence()
                .flatten()
                .maxOfOrNull { it.endMillis } ?: (windowStart + 24L * 3_600_000L)
            (latestEnd - windowStart).coerceAtLeast(6L * 3_600_000L)
        }
    }

    // Full set of group names (the Manage Groups picker lists hidden ones too
    // so they can be turned back on).
    val allGroupNames by remember(state.channels, groupSortMode, groupOrder) {
        derivedStateOf {
            // First-occurrence order so groups follow the channel ordering
            // (which the server/playlist defines), NOT alphabetical. distinct()
            // preserves encounter order. The user's Manage Groups sort
            // preference (Default / A-Z / Manual) is then applied on top.
            val sourceOrder = state.channels.asSequence()
                .map { it.groupTitle }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            orderGroups(sourceOrder, groupSortMode, groupOrder)
        }
    }
    // Visible chips: all groups minus the ones hidden via the filter picker.
    // Keyed on allGroupNames (not just state.channels) so a group sort/order
    // change re-creates this derivedStateOf and the pills follow the new order.
    val groups by remember(allGroupNames, hiddenGroups) {
        derivedStateOf {
            listOf(PlaylistViewModel.ALL_GROUPS) + allGroupNames.filter { it !in hiddenGroups }
        }
    }

    val filteredChannels by remember(state.channels, state.selectedGroup, allGroupNames, groupSortMode) {
        derivedStateOf {
            val query = state.searchQuery.trim()
            // When a non-default group order (A-Z / Manual) is active, cluster the
            // "All" guide rows by that group order so the channels follow the
            // groups (primary key = group index, then channel number). A specific
            // group view or Default order keeps the flat numeric sort.
            val clusterByGroup = query.isEmpty() &&
                state.selectedGroup == PlaylistViewModel.ALL_GROUPS &&
                groupSortMode != GroupSortMode.Default
            val groupRankIndex = if (clusterByGroup) {
                allGroupNames.withIndex().associate { (i, g) -> g to i }
            } else {
                emptyMap()
            }
            state.channels.asSequence()
                .filter { ch ->
                    when {
                        state.selectedGroup != PlaylistViewModel.ALL_GROUPS ->
                            ch.groupTitle.equals(state.selectedGroup, ignoreCase = true)
                        // In "All", hide channels whose group is toggled off --
                        // unless searching, where hidden groups stay findable
                        // (matches the List view behaviour).
                        query.isNotEmpty() -> true
                        else -> ch.groupTitle !in hiddenGroups
                    }
                }
                .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
                .sortedWith(
                    compareBy(
                        { if (clusterByGroup) groupRankIndex[it.groupTitle] ?: Int.MAX_VALUE else 0 },
                        { it.channelNumber?.toDoubleOrNull() ?: Double.MAX_VALUE },
                        { it.name.lowercase() },
                    ),
                )
                .toList()
        }
    }

    // Half-hour labels on the 10-foot guide (tvOS parity: 7:00 / 7:30 / 8:00);
    // hourly on phone where the axis is narrower. tvOS formats the axis labels
    // with `dateFormat = "h:mma"` + amSymbol="am"/pmSymbol="pm" -> lowercase,
    // NO space ("2:00pm"). Android's SimpleDateFormat emits uppercase "PM" by
    // default, so override the am/pm symbols to match tvOS exactly.
    val timeFormatter = remember(isTv) {
        if (isTv) {
            val symbols = java.text.DateFormatSymbols(Locale.getDefault()).apply {
                amPmStrings = arrayOf("am", "pm")
            }
            SimpleDateFormat("h:mma", symbols).apply { timeZone = TimeZone.getDefault() }
        } else {
            SimpleDateFormat("h a", Locale.getDefault()).apply { timeZone = TimeZone.getDefault() }
        }
    }
    // tvOS shows the CURRENT time in the top-left corner cell above the channel
    // rail (the "2:22 PM" box), in the device's locale-aware h:mm format with
    // uppercase AM/PM -- distinct from the lowercase axis labels. Refreshes with
    // the 30s nowMillis tick.
    val cornerTimeFormatter = remember {
        SimpleDateFormat("h:mm a", Locale.getDefault()).apply { timeZone = TimeZone.getDefault() }
    }

    // Horizontal scroll state shared across the time-header row + every channel
    // row so they pan together.
    val horizontalScrollState = rememberScrollState()

    // Active reminder keys, collected ONCE here instead of per-cell. Each
    // ProgrammeCell checks membership in this set rather than subscribing its
    // own observeIsSet() flow -- with hundreds of cells those per-cell flows +
    // collectAsState were a real recompose/scroll cost (iOS reads
    // ReminderManager.shared directly for the same reason).
    val remindersVm: RemindersViewModel = hiltViewModel()
    val reminders by remindersVm.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeReminderKeys = remember(reminders) {
        reminders.mapTo(HashSet<String>()) { it.reminderKey }
    }

    // Horizontal viewport clipping (iOS EPGGuideView.programRow viewport filter):
    // each row composes ONLY the programme cells overlapping the visible time
    // window (+/- 30 min pad), not every programme across the whole 6-72h window.
    // The visible window is derived from the shared horizontal scroll offset +
    // the strip viewport width, snapped to a 5-min bucket so rows re-filter only
    // occasionally (not every scroll frame); the 30-min pad pre-renders cells
    // just off-screen so scrolling never reveals a gap before the next refilter.
    val density = LocalDensity.current
    val hourWidthPx = with(density) { scaledHourWidth.toPx() }.coerceAtLeast(1f)
    val stripViewportPx = with(density) {
        (screenWidthDp.dp - railWidth).coerceAtLeast(1.dp).toPx()
    }
    val visibleWindow by remember(windowStart, hourWidthPx, stripViewportPx) {
        derivedStateOf {
            val bucketMs = 5L * 60_000L
            val padMs = GUIDE_VIEWPORT_PAD_MS
            val scrollPx = horizontalScrollState.value.toFloat()
            val startMs = windowStart + (scrollPx / hourWidthPx * 3_600_000f).toLong()
            val snappedStart = (startMs / bucketMs) * bucketMs
            val spanMs = (stripViewportPx / hourWidthPx * 3_600_000f).toLong()
            (snappedStart - padMs) to (snappedStart + spanMs + padMs)
        }
    }

    // iOS GuideStore audit P3 #12: Rolling Prefetch trigger. Every time the
    // visible window shifts past 4h of cached-edge proximity, ask the VM to
    // fire a force-refresh. The VM itself handles single-flight + a 60s
    // cooldown so a slow horizontal scroll won't spam the worker.
    LaunchedEffect(Unit) {
        snapshotFlow { visibleWindow.second }
            .distinctUntilChanged()
            .collect { viewportEndMs -> viewModel.maybePrefetchUpcoming(viewportEndMs) }
    }

    // tvOS positions the now-line ~1/5 of the way across the grid so the
    // currently-airing programmes sit at the LEFT edge instead of the centre
    // (EPGGuideView "scroll back to now" default). Two payoffs that match the
    // tvOS guide the user referenced:
    //   1. Now-airing cells start at the left, not floating mid-grid.
    //   2. D-pad DOWN stays stable: every row's now-airing cell shares the
    //      same left edge, so focus no longer jumps between a wide 3h cell in
    //      one row and a narrow 30m cell in the next.
    // The shared horizontalScrollState pans all rows together, so one scroll
    // does it for the whole grid. Keyed only on the channels-loaded
    // transition so it positions the grid each time the guide opens but never
    // yanks the user back to "now" mid-browse (e.g. when the hour ticks over
    // and windowStart shifts).
    LaunchedEffect(filteredChannels.isNotEmpty()) {
        if (filteredChannels.isEmpty()) return@LaunchedEffect
        // Wait for the row content to establish a scroll range (it's 24h wide
        // by default, far wider than the viewport, so maxValue settles > 0
        // within a frame or two). Bail if the content somehow fits (no scroll
        // needed) so we never suspend forever.
        val maxScroll = withTimeoutOrNull(1500L) {
            snapshotFlow { horizontalScrollState.maxValue }.first { it > 0 }
        } ?: return@LaunchedEffect
        val nowOffsetPx =
            ((System.currentTimeMillis() - windowStart).toFloat() / 3_600_000f) * hourWidthPx
        // Place "now" at ~20% from the left (matches the tvOS screenshots:
        // ~30 min of past visible to the left of the now-line).
        val target = (nowOffsetPx - stripViewportPx * 0.20f)
            .toInt()
            .coerceIn(0, maxScroll)
        horizontalScrollState.scrollTo(target)
    }

    // The host Scaffold sets contentWindowInsets = WindowInsets(0,0,0,0), so each
    // tab owns its own status-bar top inset (the List view gets it free from
    // CenterAlignedTopAppBar). The Guide's header is a bare control Row, so apply
    // the inset here or the controls + group pills render UNDER the status bar
    // (clock/battery overlap the All/Sports pills). No-op on Android TV (no status
    // bar). Matches ChannelListScreen's top-inset behaviour.
    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        // Audit task #22: multiview staging banner. Visible only when at
        // least one channel has been added to Multiview. Tapping the Play
        // button launches the dedicated Multiview screen; tapping the chip
        // body itself does the same so D-pad-focused users on TV can press
        // OK from any column of the row.
        if (stagedMultiview.isNotEmpty()) {
            val labelCount = stagedMultiview.size
            val label = "$labelCount Multiview channel${if (labelCount == 1) "" else "s"} staged"
            // Guarded launch: swallow the spurious OK-release after the focus
            // pull, and re-arm on a real launch so a second OK during tile
            // spin-up is swallowed too (launchSingleTop is the backstop).
            val launchMultiviewGuarded = bannerGuard.wrap {
                bannerGuard.arm()
                onLaunchMultiview()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isTv) 24.dp else 12.dp, vertical = 8.dp)
                    .focusRequester(multiviewBannerFocus)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .dpadFocusRing(
                        shape = RoundedCornerShape(14.dp),
                        washTint = MaterialTheme.colorScheme.primary,
                    )
                    .clickable(onClick = launchMultiviewGuarded)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.ViewList,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                // Android-only Clear affordance: tvOS wipes the previous pile
                // on entering staging mode, but this banner persists across
                // tabs (singleton store), so the user needs a one-press way to
                // abandon a staged set without launching it.
                TextButton(
                    onClick = bannerGuard.wrap { multiviewStore.clear() },
                    modifier = Modifier.dpadFocusRing(
                        shape = RoundedCornerShape(50),
                        washTint = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        text = "Clear",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(
                    onClick = launchMultiviewGuarded,
                    modifier = Modifier.dpadFocusRing(
                        shape = RoundedCornerShape(50),
                        washTint = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        text = "Play Multiview",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        // Jump-to-now scroller. Coroutine-driven so animateScrollTo can
        // suspend; matches iOS EPGGuideView's "scroll back to now" button
        // which snaps the time axis so the now-indicator sits ~1/4 of the
        // way across the viewport.
        // Control + filter row, mirroring the tvOS guide: List/Guide switcher,
        // search toggle, and a group on/off filter on the left, then the
        // channel-group pills (or an inline search field) filling the rest.
        // Control circle sizing. TV values are tvOS-pt x 0.5 (the 960dp canvas
        // is half tvOS's 1920pt, so the same physical size => all the group
        // pills fit across the row exactly like tvOS, no horizontal scroll).
        val controlCircle = if (isTv) 30.dp else 24.dp
        val controlIcon = if (isTv) 16.dp else 18.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isTv) 44.dp else 56.dp)
                .padding(horizontal = if (isTv) 20.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // List / Guide view switcher.
            if (canToggleViewMode) {
                IconButton(
                    onClick = onToggleViewMode,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (viewMode == LiveTVViewMode.Guide)
                            Icons.Filled.ViewList else Icons.Filled.CalendarMonth,
                        contentDescription = if (viewMode == LiveTVViewMode.Guide) "Switch to List" else "Switch to Guide",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // Search toggle: reveals an inline channel-name search field. On TV
            // it has NO resting fill or ring -- just the bare glyph -- and only
            // shows the SAME 2dp white ring as a focused program cell WHEN it is
            // focused (active = search open fills it accent regardless).
            val searchInteraction = remember { MutableInteractionSource() }
            val searchFocused by searchInteraction.collectIsFocusedAsState()
            FilledTonalIconButton(
                onClick = {
                    searchActive = !searchActive
                    if (!searchActive) viewModel.onSearchQueryChange("")
                },
                modifier = Modifier
                    .size(controlCircle)
                    .then(
                        if (isTv && searchFocused)
                            Modifier.border(2.dp, Color.White, CircleShape)
                        else Modifier,
                    ),
                interactionSource = searchInteraction,
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = when {
                        searchActive -> MaterialTheme.colorScheme.primary
                        isTv && searchFocused -> Color.White.copy(alpha = 0.15f)
                        isTv -> Color.Transparent
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    },
                    contentColor = if (searchActive) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search channels",
                    modifier = Modifier.size(controlIcon),
                )
            }
            // Gap between the two control circles.
            Spacer(Modifier.width(if (isTv) 8.dp else 8.dp))
            // Filter toggle: opens the group on/off picker (Manage Groups).
            // Same treatment as Search: no resting fill/ring on TV, white focus
            // ring only when focused; active (some groups hidden) fills accent.
            val filterActive = hiddenGroups.isNotEmpty()
            val filterInteraction = remember { MutableInteractionSource() }
            val filterFocused by filterInteraction.collectIsFocusedAsState()
            FilledTonalIconButton(
                onClick = { showManageGroups = true },
                modifier = Modifier
                    .size(controlCircle)
                    .then(
                        if (isTv && filterFocused)
                            Modifier.border(2.dp, Color.White, CircleShape)
                        else Modifier,
                    ),
                interactionSource = filterInteraction,
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = when {
                        filterActive -> MaterialTheme.colorScheme.primary
                        isTv && filterFocused -> Color.White.copy(alpha = 0.15f)
                        isTv -> Color.Transparent
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    },
                    contentColor = if (filterActive) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.FilterList,
                    contentDescription = "Filter groups",
                    modifier = Modifier.size(controlIcon),
                )
            }
            Spacer(Modifier.width(if (isTv) 10.dp else 6.dp))
            if (searchActive) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    placeholder = { Text("Search channels") },
                    singleLine = true,
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            } else if (groups.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(end = if (isTv) 20.dp else 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (isTv) 5.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(groups, key = { it }) { group ->
                        val pillSelected = state.selectedGroup == group
                        val pillInteraction = remember { MutableInteractionSource() }
                        val pillFocused by pillInteraction.collectIsFocusedAsState()
                        if (isTv) {
                            // Custom TV pill at tvOS-pt x 0.5 metrics: 11sp label
                            // (labelMedium), 12dp h-padding, 30dp tall. Material's
                            // FilterChip forces ~16dp internal padding which made
                            // each pill too wide to fit them all like tvOS; this
                            // tight capsule fits the whole group row with no scroll.
                            // Unfocused = soft filled capsule (no ring); focused =
                            // the SAME 2dp white ring as a program cell.
                            Box(
                                modifier = Modifier
                                    .height(30.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (pillSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    )
                                    .then(
                                        if (pillFocused)
                                            Modifier.border(2.dp, Color.White, CircleShape)
                                        else Modifier,
                                    )
                                    .clickable(
                                        interactionSource = pillInteraction,
                                        indication = null,
                                    ) { viewModel.onGroupSelected(group) }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    group,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (pillSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        } else {
                            FilterChip(
                                selected = pillSelected,
                                onClick = { viewModel.onGroupSelected(group) },
                                interactionSource = pillInteraction,
                                label = {
                                    Text(group, style = MaterialTheme.typography.labelLarge)
                                },
                                shape = CircleShape,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Transparent,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = pillSelected,
                                ),
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = guideDivider, thickness = 0.5.dp)

        // Time-header row: the corner over the channel rail shows the CURRENT
        // time (tvOS "2:22 PM" corner cell), then horizontally-scrolled hour
        // labels. The corner uses the rail's surface fill (tvOS cardBackground)
        // so it reads as part of the sticky left column, and a semibold
        // monospaced-digit clock to match EPGGuideView.swift's corner.
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(railWidth)
                    .height(headerHeight)
                    .background(
                        if (isTv) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.background,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isTv) {
                    Text(
                        text = cornerTimeFormatter.format(java.util.Date(nowMillis)),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .horizontalScroll(horizontalScrollState)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                val hourCount = (windowDurationMs / 3_600_000L).toInt()
                // TV shows half-hour columns (tvOS parity); phone stays hourly.
                // Total width is unchanged (slotWidth * slotCount == hour total),
                // so programme-cell alignment via msToDp is unaffected.
                val slotMs = if (isTv) 1_800_000L else 3_600_000L
                val slotWidth = if (isTv) scaledHourWidth / 2 else scaledHourWidth
                val slotCount = (windowDurationMs / slotMs).toInt()
                Row(
                    modifier = Modifier
                        .width(scaledHourWidth * hourCount)
                        .height(headerHeight),
                ) {
                    for (i in 0 until slotCount) {
                        val slotStart = windowStart + i * slotMs
                        Box(
                            modifier = Modifier
                                .width(slotWidth)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = timeFormatter.format(java.util.Date(slotStart)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = guideDivider, thickness = 0.5.dp)

        // Audit task #21: when the user comes back from the player, scroll the
        // guide to the channel they just watched (iOS LiveTV behaviour). Keyed
        // on (lastWatched, channels-loaded) so the scroll fires both on cold
        // launch once channels arrive AND when a fresh lastWatched id flows in
        // after the player closes. If the user is currently filtering and the
        // last-watched channel isn't in the filtered list, indexOfFirst returns
        // -1 and we no-op - the visible filter wins.
        val listState = rememberLazyListState()
        val lastWatchedId by settingsVm.lastWatchedChannelId
            .collectAsStateWithLifecycle(initialValue = "")
        LaunchedEffect(lastWatchedId, filteredChannels.isNotEmpty()) {
            if (lastWatchedId.isBlank() || filteredChannels.isEmpty()) return@LaunchedEffect
            // On TV, when the mini-player is showing (we just backed out of the
            // fullscreen player), the focus-on-return effect below -- keyed on the
            // mini-player session -- owns BOTH the scroll and the D-pad focus.
            // Skip here so the two effects don't fight over listState.
            if (miniActive && isTv) return@LaunchedEffect
            val idx = filteredChannels.indexOfFirst { it.id == lastWatchedId }
            if (idx >= 0) {
                listState.animateScrollToItem(idx)
            }
        }

        // Two-step Back behaviour on the Live TV guide:
        //  1) Anywhere below the top row -> scroll the guide back to channel 0
        //     and keep the user in the app. Mirrors how the iOS / tvOS Guide
        //     scrolls Home-to-top on Menu / Back rather than dismissing.
        //  2) Already at the top -> surface a confirmation dialog before we
        //     actually exit AerioTV, so an accidental Back doesn't drop the
        //     user out of the app mid-watch.
        //
        // BackHandler is wired LAST in this composable so it takes priority
        // over any outer handlers (sheets, dialogs) -- Compose dispatches
        // back events in LIFO order, so the innermost active enabled handler
        // claims the event.
        //
        // EXCEPTION: while the mini-player is showing this handler STANDS DOWN
        // (enabled = !miniActive). Backing out of the fullscreen player
        // re-composes GuideScreen, which re-registers THIS BackHandler AFTER
        // the root-level TvMiniPlayerOverlay's -- so by LIFO it would otherwise
        // win and scroll-to-top / show the exit dialog instead of letting the
        // mini's own BackHandler close it (verified on-device: Back surfaced
        // the "Exit AerioTV?" dialog while the mini stayed up). Disabling it
        // here hands the Back to the overlay's handler, which dismisses the
        // mini cleanly; once the mini is Hidden this re-enables so the next
        // Back scrolls the guide to the top, then the next shows the exit
        // dialog -- matching the requested tvOS-style ladder.
        // guideNav drives D-pad vertical focus; declared HERE (above the
        // BackHandler) so the scroll-to-top Back branch can re-focus the top
        // channel instead of leaving focus on the "All" group pill.
        val guideNav = remember { GuideVerticalNavState() }
        val navScope = rememberCoroutineScope()
        val backScope = androidx.compose.runtime.rememberCoroutineScope()
        var showExitDialog by remember { mutableStateOf(false) }
        val activity = LocalContext.current.findActivity()
        androidx.activity.compose.BackHandler(enabled = !showExitDialog && !miniActive) {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
            if (!atTop) {
                backScope.launch {
                    listState.animateScrollToItem(0)
                    // Land focus on the top channel's NOW cell, not the "All"
                    // group pill (user-reported: Back scrolled to top but left
                    // focus on the pill row).
                    if (isTv) guideNav.focusChannelAtNow(0, nowMillis, listState)
                }
            } else {
                showExitDialog = true
            }
        }
        if (showExitDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { androidx.compose.material3.Text("Exit AerioTV?") },
                text = { androidx.compose.material3.Text("Are you sure you want to leave the app?") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showExitDialog = false
                        activity?.finish()
                    }) {
                        androidx.compose.material3.Text(
                            "Exit",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showExitDialog = false }) {
                        androidx.compose.material3.Text("Cancel")
                    }
                },
            )
        }

        // Audit task #57: when the guide is showing on TV, route D-pad UP off
        // the top row to the section pills (LocalTvTopNavFocusRequester),
        // rather than letting the `focusGroup` below trap focus inside the
        // grid. `null` on phone (the CompositionLocal is unset off-TV) so
        // the .then(...) call is a no-op.
        val topNavRequester = LocalTvTopNavFocusRequester.current
        // EPG vertical-nav model (GH D-pad fixes). On TV the guide owns D-pad
        // UP/DOWN explicitly instead of relying on Compose's default focus
        // search, which had two failure modes:
        //   Bug 1 (timeline jumps on channel up/down): the default search lands
        //     focus on whatever cell happens to overlap, then bring-into-view
        //     scrolls the SHARED horizontal timeline to align it -> every row
        //     lurches sideways. We instead move focus to the cell in the
        //     prev/next row at the SAME anchor time (same on-screen column) and
        //     suppress horizontal scroll for that move.
        //   Bug 2 (fast UP escapes to the nav pill mid-list): when UP outran
        //     LazyColumn composition the row above wasn't composed yet, the
        //     search found nothing focusable, and focus fell through to
        //     `up = topNavRequester`. We instead scroll the target row into
        //     existence first, then focus it -- so UP only reaches the nav pill
        //     when already on channel index 0.
        // Focus-on-return (TV): backing out of the fullscreen player leaves the
        // mini-player Active over the guide. Land D-pad focus on the watched
        // channel's NOW cell so the user keeps channel-surfing with UP/DOWN
        // instead of focus resting on the Live TV nav pill. Keyed on the mini
        // session, so it fires once when state flips to Active (a fresh
        // GuideScreen composition on the nav-return). moveFocusToChannel scrolls
        // the row in (scrollToItem composes it instantly) then polls its focus
        // handler for up to 8 frames, so it never races row composition;
        // seedAnchor(now) makes the focused cell the live programme.
        if (isTv) {
            LaunchedEffect(miniState, filteredChannels.isNotEmpty()) {
                val active = miniState as? MiniPlayerSession.State.Active
                    ?: return@LaunchedEffect
                if (filteredChannels.isEmpty()) return@LaunchedEffect
                val idx = filteredChannels.indexOfFirst { it.id == active.channel.id }
                if (idx < 0) return@LaunchedEffect
                // Re-assert focus until it sticks on the watched channel's NOW
                // cell. The top-nav focusRestorer fires on the fresh nav-return
                // composition and would win a single one-shot request (the old
                // delay(96)+single move landed on the Live TV pill
                // intermittently); focusChannelAtNow retries until
                // focusedChannelIndex == idx.
                guideNav.focusChannelAtNow(idx, nowMillis, listState)
            }
            // Focus-on-dismiss for the Program Info / Record dialogs: re-land
            // D-pad focus on the exact cell (row + time column) that opened
            // the dialog. Same re-assert-until-it-sticks machinery as the
            // return-from-player path above, because the top-nav
            // focusRestorer competes for focus on the dialog teardown frame.
            LaunchedEffect(dialogFocusRestoreTick) {
                if (dialogFocusRestoreTick == 0) return@LaunchedEffect
                val (rowIndex, anchorMs) = dialogFocusOrigin ?: return@LaunchedEffect
                guideNav.focusChannelAt(rowIndex, anchorMs, listState)
            }
        }
        // GH #5: anchor a focused cell's LEADING edge so an oversized programme
        // (wider than the timeline viewport) doesn't fling the horizontal scroll
        // to its END on D-pad focus. Flows down into each row's horizontalScroll;
        // vertical row scrolling is unaffected (rows are shorter than the viewport).
        // The spec also returns 0 while a vertical move is in flight (see Bug 1).
        val bringIntoViewSpec = remember {
            GuideLeadingEdgeBringIntoViewSpec { guideNav.suppressHorizontalScroll }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // Treat the grid as one focus group so D-pad DOWN from the chips
                // / top bar reliably descends into the programme cells on TV.
                .focusGroup()
                .focusProperties {
                    // UP from the top row escapes to the nav pills (when present).
                    if (topNavRequester != null) up = topNavRequester
                    // The grid is full-width: there is nothing to its left or
                    // right to focus, so horizontal focus must NEVER leave it.
                    // Without this, pressing RIGHT at the edge of the currently
                    // composed cells (the next cell sits just past the viewport
                    // pad and is not yet laid out) made the default 2D focus
                    // search exit the group and jump UP to the Search button.
                    // Cancelling the L/R exit keeps focus on the edge cell; the
                    // widened compose pad below keeps the next cell ready so
                    // RIGHT/LEFT normally just steps cell-to-cell.
                    exit = { direction ->
                        if (direction == FocusDirection.Left || direction == FocusDirection.Right)
                            FocusRequester.Cancel
                        else FocusRequester.Default
                    }
                }
                // EPG vertical-nav interception (TV only -- a touch device never
                // delivers these key events, so this is inert off-TV even without
                // an isTv guard). Fires on the way DOWN the tree, before the
                // focusable cells, so we can claim UP/DOWN and steer focus
                // ourselves. LEFT/RIGHT/CENTER fall through untouched, preserving
                // horizontal timeline nav + OK-to-play.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // LEFT/RIGHT: the user IS navigating the timeline, so release
                    // the shared scroll lock. Then, if the FOCUSED program is wider
                    // than the viewport and still has content off-screen in the
                    // press direction, PAGE the timeline ~one screen WITHIN the
                    // program (keeping it focused) instead of letting focus leap a
                    // full long block (e.g. a 6-hour "season is over" cell) to the
                    // next program in one press. Once the program's edge is on
                    // screen, fall through so focus moves to the adjacent cell as
                    // usual. Normal-length programs always fall straight through.
                    if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                        guideNav.allowHorizontalScroll()
                        val cellStartPx = guideNav.focusedCellStartPx
                        val cellEndPx = guideNav.focusedCellEndPx
                        if (guideNav.focusedChannelIndex >= 0 && cellEndPx > cellStartPx) {
                            val scroll = horizontalScrollState.value
                            val maxScroll = horizontalScrollState.maxValue
                            val hours = (windowDurationMs / 3_600_000L).toInt().coerceAtLeast(1)
                            val contentPx = with(density) { (scaledHourWidth * hours).toPx() }
                            val viewportPx = (contentPx - maxScroll).coerceAtLeast(1f)
                            val epsilon = with(density) { 8.dp.toPx() }
                            val page = viewportPx * 0.85f
                            val right = event.key == Key.DirectionRight
                            if (right && cellEndPx > scroll + viewportPx + epsilon && scroll < maxScroll) {
                                val target = (scroll + page).toInt().coerceIn(0, maxScroll)
                                navScope.launch { horizontalScrollState.animateScrollTo(target) }
                                return@onPreviewKeyEvent true
                            }
                            if (!right && cellStartPx < scroll - epsilon && scroll > 0) {
                                val target = (scroll - page).toInt().coerceIn(0, maxScroll)
                                navScope.launch { horizontalScrollState.animateScrollTo(target) }
                                return@onPreviewKeyEvent true
                            }
                        }
                        // Focus is not on a grid cell yet (fresh entry from the
                        // chrome: focusedChannelIndex == -1). Let the default search
                        // drive the descent INTO a cell, exactly like the UP/DOWN
                        // handler does for cur < 0. (Change B keeps the focused cell
                        // composed during navigation, so this -1 state only happens
                        // on entry, never from a mid-scroll orphan.)
                        if (guideNav.focusedChannelIndex < 0) return@onPreviewKeyEvent false
                        // On a cell: step focus EXPLICITLY to the adjacent composed
                        // cell. If there is one, we moved; if not (edge of the
                        // composed window), CONSUME so Compose's default 2D
                        // directional focus search never runs -- that search is the
                        // only path that escalates out of the grid focusGroup to the
                        // chrome (it is inert to guard via exit=Cancel because a 2D
                        // DirectionRight never consults exit on this Compose version).
                        // Consuming keeps focus on the current edge cell; the next
                        // press retries once the pad recomposes the neighbour.
                        val right = event.key == Key.DirectionRight
                        guideNav.stepHorizontal(forward = right)
                        return@onPreviewKeyEvent true
                    }
                    val delta = when (event.key) {
                        Key.DirectionDown -> 1
                        Key.DirectionUp -> -1
                        else -> return@onPreviewKeyEvent false
                    }
                    // THROTTLE held-key fast-scroll to a controllable rate. The
                    // system fires key AUTO-REPEAT events (repeatCount > 0) every
                    // ~50ms while the D-pad is held, so without a cap a "single
                    // press" held even ~0.5s blasted through 7-8 channels before the
                    // user could react. The INITIAL press (repeatCount == 0) always
                    // moves -- so a tap is exactly one channel and stays snappy --
                    // while repeats step only once per HOLD_SCROLL_MIN_INTERVAL_MS,
                    // so a long hold gives a smooth fast-scroll you can stop on.
                    // (Confirmed via logcat: one event = exactly one channel, so the
                    // over-scroll was the unthrottled auto-repeat, not a cascade.)
                    val nowMs = android.os.SystemClock.uptimeMillis()
                    if (event.nativeKeyEvent.repeatCount != 0 &&
                        nowMs - guideNav.lastVerticalMoveAtMs < HOLD_SCROLL_MIN_INTERVAL_MS
                    ) {
                        return@onPreviewKeyEvent true
                    }
                    // While a vertical move is in flight, focusedChannelIndex
                    // churns to -1 between the outgoing cell's unfocus and the
                    // incoming cell's focus. Read the in-flight TARGET instead so
                    // a rapid / long-press UP keeps stepping channel-by-channel
                    // and only escapes to the nav pill from the genuine top
                    // (Bug 4: fast UP read -1 and fell through to the nav
                    // focusProperties).
                    val cur = when {
                        guideNav.verticalMoveInFlight && guideNav.pendingTargetIndex >= 0 ->
                            guideNav.pendingTargetIndex
                        guideNav.focusedChannelIndex >= 0 -> guideNav.focusedChannelIndex
                        // Focus was ORPHANED mid-scroll (a cell disposed out from
                        // under it) -- recover from the STICKY last row instead of
                        // reading -1 and escaping. This is the rapid-UP-jumps-to-the
                        // -Live-TV-tab bug: the 48ms verticalMoveInFlight window
                        // lapses between fast presses, focusedChannelIndex reads -1,
                        // and the old code fell through to the nav focusProperties.
                        // lastFocusedChannelIndex never blips to -1, so we keep
                        // stepping. (It is only -1 on genuine fresh entry, where the
                        // grid key handler is not firing yet anyway.)
                        else -> guideNav.lastFocusedChannelIndex
                    }
                    // No cell focused yet (fresh entry from the chips/top bar):
                    // let the default search drive the first descent.
                    if (cur < 0) return@onPreviewKeyEvent false
                    val target = cur + delta
                    // UP from the very top channel -> do NOT consume, so focus
                    // falls through to `up = topNavRequester` and escapes to the
                    // Live TV nav pill (Bug 2's intended top-of-guide behaviour).
                    if (target < 0) return@onPreviewKeyEvent false
                    // DOWN past the last channel -> nothing below; swallow so we
                    // don't wrap or escape downward.
                    if (target > filteredChannels.lastIndex) return@onPreviewKeyEvent true
                    // In range: consume and move focus to the time-aligned cell in
                    // the target row, composing it first if it scrolled off. Hold
                    // the timeline still for the whole move (released on the next
                    // LEFT/RIGHT) so a wide cell's bring-into-view never pans it.
                    guideNav.lastVerticalMoveAtMs = nowMs
                    guideNav.beginVerticalMove()
                    navScope.launch {
                        guideNav.moveFocusToChannel(target, listState)
                    }
                    true
                }
                // Pinch-to-zoom the timeline. Custom detector that only acts on
                // two-or-more pointers so single-finger pans still reach the
                // LazyColumn's vertical scroll + the rows' horizontal scroll.
                // Commits to DataStore once per gesture (on lift).
                .pointerInput(Unit) {
                    awaitEachGesture {
                        var didZoom = false
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    liveScale = (liveScale * zoom)
                                        .coerceIn(GUIDE_SCALE_MIN, GUIDE_SCALE_MAX)
                                    didZoom = true
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (didZoom) settingsVm.setGuideScale(liveScale)
                    }
                },
        ) {
            itemsIndexed(items = filteredChannels, key = { _, ch -> ch.id }) { channelIndex, channel ->
                val programmes = state.epgByChannel[channel.guideMatchKey].orEmpty()
                ChannelGuideRow(
                    channel = channel,
                    channelIndex = channelIndex,
                    guideNav = guideNav,
                    programmes = programmes,
                    windowStart = windowStart,
                    windowDurationMs = windowDurationMs,
                    hourWidth = scaledHourWidth,
                    nowMillis = nowMillis,
                    visibleStartMs = visibleWindow.first,
                    visibleEndMs = visibleWindow.second,
                    isTv = isTv,
                    railWidth = railWidth,
                    rowHeight = rowHeight,
                    horizontalScrollState = horizontalScrollState,
                    activeReminderKeys = activeReminderKeys,
                    remindersVm = remindersVm,
                    onChannelClick = { onChannelClick(channel) },
                    onProgrammeClick = { programme ->
                        // Capture the originating cell (row + clipped time
                        // column) so dismissal can restore D-pad focus to it.
                        dialogFocusOrigin =
                            channelIndex to programme.startMillis.coerceAtLeast(windowStart)
                        programInfoTarget = programme.toInfoTarget(channel.name, channel.dispatcharrChannelId)
                    },
                    onProgrammeRecord = { programme ->
                        dialogFocusOrigin =
                            channelIndex to programme.startMillis.coerceAtLeast(windowStart)
                        recordTarget = programme.toInfoTarget(channel.name, channel.dispatcharrChannelId)
                    },
                    isFavorite = channel.id in favoriteIds,
                    onToggleFavorite = { favoritesVm.toggle(channel) },
                    palette = palette,
                    multiviewStore = multiviewStore,
                    showLogo = showChannelLogos,
                )
                HorizontalDivider(color = guideRowDivider, thickness = guideRowDividerThickness)
            }
        }
        }
    }

    programInfoTarget?.let { target ->
        ProgramInfoSheet(
            target = target,
            onDismiss = {
                programInfoTarget = null
                // Hand D-pad focus back to the cell that opened the dialog
                // (the grid-side LaunchedEffect keyed on this tick does the
                // actual focus work). No-op on phone.
                if (isTv) dialogFocusRestoreTick++
            },
        )
    }
    recordTarget?.let { target ->
        RecordProgramSheet(
            target = target,
            onDismiss = {
                recordTarget = null
                if (isTv) dialogFocusRestoreTick++
            },
        )
    }
    if (showManageGroups) {
        if (isTv) {
            // D-pad-driven centered picker on TV; the touch bottom sheet on phone.
            TvGroupPicker(
                allGroups = allGroupNames,
                hiddenGroups = hiddenGroups,
                onToggle = { group, visible ->
                    settingsVm.setHiddenGroups(
                        if (visible) hiddenGroups - group else hiddenGroups + group,
                    )
                },
                onDismiss = { showManageGroups = false },
                reorderEnabled = true,
                sortMode = groupSortMode,
                onSortModeChange = { settingsVm.setGroupSortMode(it.name) },
                onReorder = { settingsVm.setGroupOrder(it) },
            )
        } else {
            ManageGroupsSheet(
                allGroups = allGroupNames,
                hiddenGroups = hiddenGroups,
                onSave = { settingsVm.setHiddenGroups(it) },
                onDismiss = { showManageGroups = false },
                reorderEnabled = true,
                sortMode = groupSortMode,
                onSortModeChange = { settingsVm.setGroupSortMode(it.name) },
                onReorder = { settingsVm.setGroupOrder(it) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelGuideRow(
    channel: M3UChannel,
    channelIndex: Int,
    guideNav: GuideVerticalNavState,
    programmes: List<EPGProgramme>,
    windowStart: Long,
    windowDurationMs: Long,
    hourWidth: androidx.compose.ui.unit.Dp,
    nowMillis: Long,
    visibleStartMs: Long,
    visibleEndMs: Long,
    isTv: Boolean,
    railWidth: androidx.compose.ui.unit.Dp,
    rowHeight: androidx.compose.ui.unit.Dp,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
    activeReminderKeys: Set<String>,
    remindersVm: RemindersViewModel,
    onChannelClick: () -> Unit,
    onProgrammeClick: (EPGProgramme) -> Unit,
    onProgrammeRecord: (EPGProgramme) -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    palette: CategoryPaletteState,
    multiviewStore: MultiviewStoreHandle,
    showLogo: Boolean = true,
) {
    val multiviewSelected by multiviewStore.selected.collectAsStateWithLifecycle()
    val inMultiview = multiviewSelected.any { it.id == channel.id }
    val atCap = multiviewSelected.size >= multiviewStore.maxTiles && !inMultiview
    val context = LocalContext.current
    var railMenuOpen by remember { mutableStateOf(false) }
    val railMenuGuard = com.aeriotv.android.core.tv.rememberTvMenuGuard()
    // Stable FocusRequester per programme (keyed by start time, unique within a
    // channel). The guide's vertical-nav handler asks this row to focus the cell
    // spanning a given anchor time; that needs a requester it can drive even
    // though the visible cell set churns as the timeline scrolls.
    val cellRequesters = remember(channel.id) { mutableMapOf<Long, FocusRequester>() }
    // Spans of the CURRENTLY composed cells, rebuilt each composition (cleared at
    // the top of the strip layout below, refilled as cells render). The row-focus
    // handler picks the entry whose span contains the anchor time (or the
    // nearest), so a channel up/down lands in the same time column.
    val visibleCellSpans = remember { mutableListOf<CellSpan>() }

    // Compact rail sizing. On TV we keep it tight (narrow rail, small logo) so
    // more channels fit; legibility comes from the name/cell text, not bulk.
    val numberStyle = if (isTv) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall
    // Channel numbers run up to 5 digits on large IPTV playlists. Size the
    // column for "12345" at the rail's type scale so nothing clips (24dp/28dp
    // rendered "5200" as "520", user reports). The few extra dp for low
    // numbers is a fair trade for never truncating a channel number.
    val numberWidth = if (isTv) 40.dp else 36.dp
    // logoBox/logoImage are the PHONE rail's square logo. TV uses a landscape
    // logo-over-name VStack (the isTv branch below) so the channel name gets its
    // own full-width line and shows in full, mirroring tvOS channelLabel.
    val logoBox = 36.dp
    val logoImage = 32.dp
    // TV name uses labelSmall (~9.9sp at the 0.9 type scale) - a touch smaller
    // than labelMedium (10.8sp) per user request to free a little more rail width
    // for long names while staying legible at 10 feet. Phone keeps labelMedium.
    val nameStyle = if (isTv) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
    ) {
        // Sticky-left channel rail. Tap plays the channel; long-press opens the
        // favorites toggle. Mirrors iOS EPGGuideView channel-rail .contextMenu
        // (EPGGuideView.swift:2823). Wrapped in a Box so the tvOS-style trailing
        // accentPrimary separator can overlay the rail's right edge.
        Box(
            modifier = Modifier
                .width(railWidth)
                .fillMaxHeight(),
        ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                // tvOS rail uses a solid card background; phone keeps the lighter
                // translucent fill so the programme strip shows faintly behind it.
                .background(
                    if (isTv) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                )
                // TV: the channel rail must NOT be a D-pad focus target. tvOS
                // focuses programme cells, not the channel column; leaving the
                // rail focusable let vertical nav (and disposal-relocation) land
                // focus on the channel card instead of a programme. canFocus=false
                // removes it from focus traversal while keeping the tap (phone
                // touch) working. Must precede the clickable that it modifies.
                .focusProperties { if (isTv) canFocus = false }
                .combinedClickable(
                    onClick = onChannelClick,
                    onLongClick = { railMenuOpen = true; railMenuGuard.arm() },
                )
                // tvOS channelLabel uses .padding(.horizontal, 8) on a 240pt
                // column -> proportional 4dp on the 120dp Android-TV column;
                // phone keeps the 8dp.
                .padding(start = if (isTv) 2.dp else 8.dp, end = if (isTv) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isTv) {
                // tvOS GuideChannelButton.channelLabel (EPGGuideView.swift:3018):
                // channel number on the left, then a Column with the logo over the
                // name. The name sits on its OWN line spanning the full remaining
                // column width (single line, like tvOS lineLimit(1)) so it reads in
                // full instead of being squeezed in beside the number + logo. Names
                // longer than the column truncate, exactly like tvOS.
                channel.channelNumber?.let { num ->
                    Text(
                        text = num.toString(),
                        style = numberStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.width(numberWidth),
                    )
                }
                Spacer(Modifier.width(3.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // iOS Issue #28: omit the channel logo when "Show Channel
                    // Logos" is off so the name takes the full space.
                    if (showLogo) {
                    // Landscape logo box, tvOS 72x48pt proportional on the 540dp
                    // canvas -> 36x24dp (matching the channel column's tvOS ratios).
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (channel.tvgLogo.isNotBlank()) {
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            // Phase 174: decode at 128x128 px so the
                            // downsample to ~60x36 px display happens
                            // via GPU bilinear rather than CPU
                            // box-filter. Sharper text/glyphs in
                            // logos that would otherwise look mushy.
                            AsyncImage(
                                model = coil3.request.ImageRequest.Builder(ctx)
                                    .data(channel.tvgLogo)
                                    .size(128, 128)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(3.dp),
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
                    Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        text = channel.name,
                        style = nameStyle,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                channel.channelNumber?.let { num ->
                    Text(
                        text = num.toString(),
                        style = numberStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(numberWidth),
                    )
                }
                Spacer(Modifier.width(4.dp))
                if (showLogo) {
                Box(
                    modifier = Modifier
                        .size(logoBox)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    if (channel.tvgLogo.isNotBlank()) {
                        val ctx2 = androidx.compose.ui.platform.LocalContext.current
                        // Phase 174: sharper logo decode (see neighbour
                        // AsyncImage rationale above).
                        AsyncImage(
                            model = coil3.request.ImageRequest.Builder(ctx2)
                                .data(channel.tvgLogo)
                                .size(128, 128)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.size(logoImage),
                        )
                    } else {
                        Text(
                            text = channel.name.take(2).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = channel.name,
                    style = nameStyle,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = railMenuOpen,
                onDismissRequest = { railMenuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                DropdownMenuItem(
                    text = {
                        Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites")
                    },
                    onClick = railMenuGuard.wrap {
                        railMenuOpen = false
                        onToggleFavorite()
                    },
                )
                if (channel.url.isNotBlank()) {
                    DropdownMenuItem(
                        enabled = !atCap,
                        text = {
                            Text(
                                text = when {
                                    inMultiview -> "Remove from Multiview"
                                    atCap -> "Multiview full"
                                    else -> "Add to Multiview"
                                },
                            )
                        },
                        onClick = railMenuGuard.wrap {
                            railMenuOpen = false
                            if (!atCap || inMultiview) multiviewStore.toggle(channel)
                        },
                    )
                }
            }
        }
            // tvOS trailing rail separator (accentPrimary.opacity(0.2)) so the
            // sticky channel column reads as a distinct surface from the grid.
            if (isTv) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
                )
            }
        }

        visibleCellSpans.clear()

        // Programme strip - horizontally scrolled with the header.
        Box(
            modifier = Modifier
                .horizontalScroll(horizontalScrollState)
                .fillMaxHeight(),
        ) {
            val totalWidth = hourWidth * (windowDurationMs / 3_600_000L).toInt()
            Box(modifier = Modifier.width(totalWidth).fillMaxHeight()) {
                // Programme cells, positioned by offset.
                val windowEnd = windowStart + windowDurationMs
                programmes.forEachIndexed { index, programme ->
                    val rawStart = programme.startMillis
                    val rawEnd = programme.endMillis
                    // Window clip: skip programmes entirely outside the guide span.
                    if (rawEnd <= windowStart) return@forEachIndexed
                    if (rawStart >= windowEnd) return@forEachIndexed
                    // Viewport clip: skip programmes outside the visible scroll
                    // window (+/- pad). Keeps each row to a handful of composed
                    // cells instead of all-in-window; the parent Box stays
                    // totalWidth so the scroll range is unchanged.
                    // EXCEPTION: the row that currently owns focus composes ALL of
                    // its cells within the window, not just the viewport pad. A
                    // horizontal scroll (page / bring-into-view) would otherwise move
                    // the focused cell -- or its step target -- past the pad and
                    // dispose it mid-move, orphaning focus; Compose then relocates
                    // focus to the chrome above (the RIGHT/UP focus-loss + escape
                    // bug). Keeping the whole focused row alive means focus always
                    // has a live cell to step onto. Only the single focused row pays
                    // this; every other row stays clipped to the pad.
                    val rowIsFocused = channelIndex == guideNav.lastFocusedChannelIndex
                    if (!rowIsFocused &&
                        (rawEnd <= visibleStartMs || rawStart >= visibleEndMs)
                    ) return@forEachIndexed
                    val clippedStart = rawStart.coerceAtLeast(windowStart)
                    // Anti-overlap: clamp the cell end to the next programme's start
                    // so a feed with overlapping entries doesn't paint cells on top
                    // of each other (iOS clamps to nextProgramStart).
                    val nextStart = programmes.getOrNull(index + 1)?.startMillis
                    val clippedEnd = rawEnd.coerceAtMost(windowEnd)
                        .let { e -> if (nextStart != null) minOf(e, maxOf(nextStart, clippedStart)) else e }
                    val xDp = msToDp(clippedStart - windowStart, hourWidth)
                    // Floor the width so a malformed ~1-min programme still renders a
                    // tappable sliver instead of a zero-width cell (iOS max(20,...)).
                    val wDp = msToDp(clippedEnd - clippedStart, hourWidth).coerceAtLeast(6.dp)
                    val isLive = !programme.isPlaceholder &&
                        programme.startMillis <= nowMillis && programme.endMillis > nowMillis
                    // Elapsed fraction (0..1) for the now-airing cell's progress
                    // bar, recomputed only on the 30s nowMillis tick. Null for
                    // non-live cells so they don't recompose on the tick (the
                    // Phase 125 now-tick isolation still holds: only the one live
                    // cell per row gets a changing value). Measured against the
                    // CLIPPED (visible) span, not the full program, so the fill
                    // edge tracks the now-line and never overfills a left-clipped
                    // cell (a program that began before the visible window). For a
                    // fully-visible cell clippedStart == programme.start, so this
                    // equals the true program progress.
                    val liveProgress: Float? = if (isLive) {
                        val span = (clippedEnd - clippedStart).toFloat()
                        if (span > 0f) {
                            ((nowMillis - clippedStart) / span).coerceIn(0f, 1f)
                        } else null
                    } else null
                    // Vertical-nav plumbing: a stable requester for this programme
                    // and a record of its visible span so the row handler can focus
                    // it by anchor time. The anchor reported on focus is the cell's
                    // clipped start (the column it occupies on screen).
                    val cellRequester = cellRequesters.getOrPut(programme.startMillis) { FocusRequester() }
                    visibleCellSpans.add(CellSpan(clippedStart, clippedEnd, cellRequester))
                    ProgrammeCell(
                        programme = programme,
                        channelName = channel.name,
                        channelId = channel.id,
                        widthDp = wDp,
                        cellStartDp = xDp,
                        isLive = isLive,
                        liveProgress = liveProgress,
                        isTv = isTv,
                        horizontalScrollState = horizontalScrollState,
                        activeReminderKeys = activeReminderKeys,
                        remindersVm = remindersVm,
                        focusRequester = cellRequester,
                        channelIndex = channelIndex,
                        anchorTimeMs = clippedStart,
                        guideNav = guideNav,
                        // Dispatcharr bulk grid drops <category>; fall back
                        // to the channel's group title so guide cells still
                        // tint even before any per-program lazy enrichment
                        // hits. Mirrors the same fallback in ChannelRow.
                        categoryTint = palette.tintFor(
                            rawCategory = programme.category,
                            isLive = isLive,
                            fallback = channel.groupTitle,
                        ),
                        modifier = Modifier.offset(x = xDp),
                        onPlay = onChannelClick,
                        onShowInfo = { onProgrammeClick(programme) },
                        onRecord = { onProgrammeRecord(programme) },
                        canAddToMultiview = channel.url.isNotBlank() && (!atCap || inMultiview),
                        inMultiview = inMultiview,
                        onToggleMultiview = { multiviewStore.toggle(channel) },
                        isFavorite = isFavorite,
                        onToggleFavorite = onToggleFavorite,
                    )
                }
                // "Now" indicator vertical line, only drawn when "now" falls
                // inside the window. tvOS draws it in red (statusLive); phone
                // keeps the cyan accent.
                if (nowMillis in (windowStart + 1)..(windowStart + windowDurationMs - 1)) {
                    val nowX = msToDp(nowMillis - windowStart, hourWidth)
                    Box(
                        modifier = Modifier
                            .offset(x = nowX)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(
                                if (isTv) Color(0xFFFF4757) else MaterialTheme.colorScheme.primary,
                            ),
                    )
                }
            }
        }
    }

    // Register this row's "focus the cell at the anchor time" hook with the
    // guide's vertical-nav state. The handler reads `visibleCellSpans` lazily
    // (it's mutated in place each composition), so it always sees the cells that
    // are currently composed when a channel up/down lands here.
    DisposableEffect(channelIndex) {
        guideNav.registerRow(channelIndex) { anchorTimeMs ->
            val spans = visibleCellSpans
            if (spans.isEmpty()) return@registerRow false
            // Prefer the cell whose [start, end) contains the anchor time; if the
            // anchor falls in a gap (no EPG there), snap to the nearest cell so a
            // channel with sparser data still keeps focus on this row.
            val containing = spans.firstOrNull { anchorTimeMs in it.startMs until it.endMs }
            val target = containing ?: spans.minByOrNull { span ->
                when {
                    anchorTimeMs < span.startMs -> span.startMs - anchorTimeMs
                    anchorTimeMs >= span.endMs -> anchorTimeMs - span.endMs + 1
                    else -> 0L
                }
            }
            if (target == null) return@registerRow false
            // requestFocus() throws if the node isn't attached yet (row composed
            // but not laid out). The caller retries for a few frames, so swallow
            // and report failure rather than crash.
            runCatching { target.requester.requestFocus() }.isSuccess
        }
        // Explicit LEFT/RIGHT: step to the adjacent COMPOSED cell in this row.
        guideNav.registerRowHorizontal(channelIndex) { forward, fromMs ->
            val spans = visibleCellSpans.sortedBy { it.startMs }
            if (spans.isEmpty()) return@registerRowHorizontal false
            // The cell the user is currently in (contains fromMs), else the last
            // one starting at/before fromMs (anchor sitting in a gap), else first.
            var cur = spans.indexOfFirst { fromMs in it.startMs until it.endMs }
            if (cur < 0) cur = spans.indexOfLast { it.startMs <= fromMs }
            if (cur < 0) cur = 0
            val target = spans.getOrNull(if (forward) cur + 1 else cur - 1)
                ?: return@registerRowHorizontal false
            runCatching { target.requester.requestFocus() }.isSuccess
        }
        onDispose {
            guideNav.unregisterRow(channelIndex)
            guideNav.unregisterRowHorizontal(channelIndex)
        }
    }
}

/** A composed programme cell's visible time span + its focus handle, used by the
 *  guide's vertical-nav handler to focus the cell at a given anchor time. */
private class CellSpan(
    val startMs: Long,
    val endMs: Long,
    val requester: FocusRequester,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProgrammeCell(
    programme: EPGProgramme,
    channelName: String,
    channelId: String,
    widthDp: androidx.compose.ui.unit.Dp,
    /** This cell's left edge in the scroll content (dp from the window start).
     *  Used to pin the title to the visible left edge for long programs that
     *  began before the viewport (a left-clipped cell has cellStartDp == 0). */
    cellStartDp: androidx.compose.ui.unit.Dp,
    isLive: Boolean,
    /** Elapsed fraction (0..1) of the now-airing program, or null if not live.
     * Drives the bottom progress bar that marks the currently-airing cell. */
    liveProgress: Float?,
    isTv: Boolean,
    horizontalScrollState: androidx.compose.foundation.ScrollState? = null,
    activeReminderKeys: Set<String>,
    remindersVm: RemindersViewModel,
    focusRequester: FocusRequester,
    /** Index of this cell's channel row + the time column it occupies, reported
     *  to [guideNav] on focus so D-pad up/down can stay in the same column. */
    channelIndex: Int,
    anchorTimeMs: Long,
    guideNav: GuideVerticalNavState,
    categoryTint: androidx.compose.ui.graphics.Color?,
    modifier: Modifier,
    onPlay: () -> Unit,
    onShowInfo: () -> Unit,
    onRecord: () -> Unit,
    canAddToMultiview: Boolean,
    inMultiview: Boolean,
    onToggleMultiview: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val menuGuard = com.aeriotv.android.core.tv.rememberTvMenuGuard()
    val key = remember(programme, channelName) {
        reminderKey(channelName, programme.title, programme.startMillis)
    }
    // Membership check against the set hoisted in GuideScreen -- no per-cell flow.
    val isReminderSet = key in activeReminderKeys
    var focused by remember { mutableStateOf(false) }
    val cellDensity = androidx.compose.ui.platform.LocalDensity.current
    // Short time-range label ("7:00 - 7:30"), shown on the TV guide cell beneath
    // the title to match the tvOS Emby-style cell (title + time range).
    val cellTimeFmt = remember { SimpleDateFormat("h:mm", Locale.getDefault()) }
    val timeRange = remember(programme.startMillis, programme.endMillis) {
        "${cellTimeFmt.format(java.util.Date(programme.startMillis))} - " +
            cellTimeFmt.format(java.util.Date(programme.endMillis))
    }
    // tvOS guide cells are flat, full-height "Emby-style" rectangles separated
    // only by the row hairlines: NO rounded corners or always-on border at rest;
    // focus / live / future are conveyed purely by fill brightness, plus a thin
    // focus ring for D-pad clarity. Phone keeps the rounded, bordered card cell.
    val cellShape = when {
        !isTv -> RoundedCornerShape(6.dp)
        focused -> RoundedCornerShape(4.dp)
        else -> RectangleShape
    }
    val phoneBaseBg = categoryTint ?: if (isLive)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    else
        MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
    val cellBg = if (isTv) {
        // tvOS cellBackground (EPGGuideView.swift:3791) with category tint OFF:
        // focused = white.opacity(0.25), live = white.opacity(0.12),
        // future/past = white.opacity(0.05). Neutral white overlays on the dark
        // grid -- NOT cyan -- so a row reads as one calm surface with the
        // now-airing cell a touch brighter. Category tint stays suppressed on
        // TV (Archie: the "three different blues per row" busyness); the white
        // ramp is exactly what the tvOS guide shows.
        when {
            focused -> Color.White.copy(alpha = 0.25f)
            isLive -> Color.White.copy(alpha = 0.12f)
            else -> Color.White.copy(alpha = 0.05f)
        }
    } else {
        if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.32f) else phoneBaseBg
    }
    val phoneBaseBorder = if (isLive)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    else
        MaterialTheme.colorScheme.surfaceVariant
    // tvOS focus ring is a 4pt WHITE inset border (Emby style, line 3339); the
    // Android-TV proportional is 2dp white. Phone keeps the cyan accent border.
    val cellBorderColor = if (focused) {
        if (isTv) Color.White else MaterialTheme.colorScheme.primary
    } else phoneBaseBorder
    val cellBorderWidth = if (isTv) {
        if (focused) 2.dp else 0.dp
    } else {
        if (focused) 3.dp else 0.5.dp
    }
    // Phone title keeps the cyan live tint; the tvOS title stays neutral (turning
    // white only on focus, like the source).
    val titleColor = if (isLive)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.onBackground
    // Long-program title pinning: keep the program's text block glued to the
    // visible left edge of the guide as its cell scrolls left, so a currently-
    // airing program that began before the viewport (left-clipped, so
    // cellStartDp == 0) never has its title cut off by the guide's left edge --
    // it keeps bumping right as time progresses. Also covers no-EPG placeholder
    // cells (which sit at cellStartDp == 0 too). The shift is bounded only by the
    // cell's OWN width: the cell already clips its content (clip(cellShape)), so
    // the block can never paint over the next program, and the block rides along
    // until the cell (the program) fully scrolls off the left. offset {} runs in
    // the placement phase, so panning re-places without recomposing.
    val contentSticky = if (horizontalScrollState != null) {
        Modifier.offset {
            val cellLeftPx = cellStartDp.roundToPx()
            val cellWidthPx = widthDp.roundToPx()
            val shift = (horizontalScrollState.value - cellLeftPx).coerceIn(0, cellWidthPx)
            androidx.compose.ui.unit.IntOffset(shift, 0)
        }
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .width(widthDp)
            .fillMaxHeight()
            // Vertical-nav handle: the guide's D-pad up/down handler focuses this
            // cell by anchor time via this requester. Must precede the focusable
            // (combinedClickable) below so the requester binds to that node.
            .focusRequester(focusRequester)
            .then(
                // TV: a 1dp trailing seam so adjacent flat cells stay distinct.
                // Phone: the original inset that floats the rounded card.
                if (isTv) Modifier.padding(end = 1.dp)
                else Modifier.padding(start = 1.dp, end = 1.dp, top = 4.dp, bottom = 4.dp),
            )
            // NOTE: deliberately NO per-cell focus scale here. The guide renders
            // 50-100 cells at once; a graphicsLayer (from tvFocusScale) on every
            // one makes the Streamer GPU composite that many render layers every
            // frame, which made D-pad navigation laggy. The cheap border + bg
            // highlight below is the guide's focus cue; the springy grow lives
            // only on the sparse surfaces (nav pills, On Demand posters, rows).
            .clip(cellShape)
            .background(cellBg)
            .then(
                if (cellBorderWidth > 0.dp)
                    Modifier.border(cellBorderWidth, cellBorderColor, cellShape)
                else Modifier,
            )
            .onFocusChanged {
                focused = it.isFocused
                // Record which channel row + time column owns focus so the
                // guide's vertical-nav handler can step to the same column in
                // the next/prev row.
                if (it.isFocused) {
                    guideNav.onCellFocused(channelIndex, anchorTimeMs)
                    // Record this cell's content-px span so the key handler can
                    // page WITHIN a long program rather than leaping to the next.
                    val startPx = with(cellDensity) { cellStartDp.roundToPx() }
                    val endPx = with(cellDensity) { (cellStartDp + widthDp).roundToPx() }
                    guideNav.setFocusedCellBounds(startPx, endPx)
                } else guideNav.onCellUnfocused(channelIndex)
            }
            .combinedClickable(
                // Single tap/click plays the channel; the program-info sheet +
                // actions live behind a long press (the menu below). iOS parity.
                // menuGuard.wrap: the spurious OK-release after a TV long-press
                // can land back on this cell and would otherwise start playback.
                onClick = menuGuard.wrap(onPlay),
                onLongClick = { menuOpen = true; menuGuard.arm() },
            )
            // tvOS programCell uses .padding(.horizontal, 8) / .padding(.vertical, 6)
            // on a 110pt row -> proportional 4dp/3dp on the 55dp Android-TV row.
            // Phone keeps the original 8/6 inset.
            .padding(
                horizontal = if (isTv) 4.dp else 8.dp,
                vertical = if (isTv) 3.dp else 6.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // One source of truth for the long-press actions; rendered as an
        // anchored DropdownMenu on phone (a thumb-distance idiom) and as the
        // shared centered TvActionMenuDialog on TV.
        val canRecordToServer = LocalCanRecordToServer.current
        val menuActions = buildList {
            add(TvMenuAction("Program Info", Icons.Outlined.Info) { onShowInfo() })
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
            add(
                TvMenuAction(
                    if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                    Icons.Outlined.Star,
                ) { onToggleFavorite() },
            )
            if (canAddToMultiview) {
                add(
                    TvMenuAction(
                        if (inMultiview) "Remove from Multiview" else "Add to Multiview",
                        Icons.Outlined.GridView,
                    ) { onToggleMultiview() },
                )
            }
            if (programme.endMillis > System.currentTimeMillis() && canRecordToServer) {
                add(
                    TvMenuAction(
                        if (isLive) "Record from Now" else "Record",
                        Icons.Outlined.FiberManualRecord,
                    ) { onRecord() },
                )
            }
        }
        if (isTv) {
            if (menuOpen) {
                TvActionMenuDialog(
                    title = programme.title.ifBlank { channelName },
                    actions = menuActions,
                    guard = menuGuard,
                    onDismiss = {
                        menuOpen = false
                        // Back from the menu must put D-pad focus back on this
                        // cell: the dialog window held focus, and without the
                        // re-request Compose's fallback can park it on the nav
                        // pills. Harmless when an action opens a follow-up
                        // dialog (Program Info / Record): the refocus lands in
                        // the host window underneath it, and that dialog
                        // restores again via dialogFocusRestoreTick on its own
                        // dismissal. The Multiview staging banner's focus pull
                        // still wins afterwards (it retries by LaunchedEffect).
                        runCatching { focusRequester.requestFocus() }
                    },
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
        // The text block is wrapped in one column carrying [contentSticky] so the
        // title + description + time all stay pinned together at the visible left
        // edge for a left-clipped long program (and measure their own width for
        // the clamp). Column wraps its content width, so a short title over a wide
        // (long-program) cell leaves plenty of room to slide right.
        if (isTv) {
            // tvOS cell: bold title + 1-line program description + a time-range
            // line, matching EPGGuideView.swift:3146 cellContent. Title turns
            // white on focus (over the bright fill); description dims to a
            // soft white, time-range dims further.
            Column(
                modifier = contentSticky,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = programme.title.ifBlank { "No info" },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) Color.White else MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (programme.description.isNotBlank()) {
                    Text(
                        text = programme.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (focused) Color.White.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!programme.isPlaceholder) {
                    Text(
                        text = timeRange,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (focused) Color.White.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Column(
                modifier = contentSticky,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = programme.title.ifBlank { "No info" },
                    style = MaterialTheme.typography.labelMedium,
                    color = titleColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (programme.description.isNotBlank()) {
                    Text(
                        text = programme.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Currently-airing progress bar: a thin elapsed-time track pinned to the
        // bottom edge of the live cell. The weighted spacer pushes it down; non-
        // live cells pass liveProgress == null and render nothing here.
        liveProgress?.let { frac ->
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}


/** Layout constants for the guide grid. [HOUR_WIDTH] is the unscaled base; the
 * guide zoom (iOS guideScale) multiplies it and everything time-axis derives
 * from the scaled value via [msToDp]. */
private object GuideMetrics {
    val RAIL_WIDTH = 168.dp
    val HEADER_HEIGHT = 36.dp
    val ROW_HEIGHT = 80.dp
    /** Base (1.0x) width of one hour column. Scaled by guideScale at render. */
    val HOUR_WIDTH = 320.dp
}

private const val MS_PER_HOUR_F = 3_600_000f

/** Minimum gap between channel up/down moves driven by a HELD D-pad key
 *  (auto-repeat). The initial press always moves (a tap is one channel); held
 *  repeats are rate-limited to this so a long hold fast-scrolls at a
 *  controllable ~8 channels/sec instead of the system's ~20/sec repeat rate
 *  blasting 7-8 rows in one press. Tune up for a slower hold-scroll. */
private const val HOLD_SCROLL_MIN_INTERVAL_MS = 120L

/** Off-screen pre-render pad (each side) for the horizontal viewport clip. Cells
 *  whose visible span lies entirely within this pad are composed but not actually
 *  on-screen; the vertical-nav nearest-cell fallback excludes them so a channel
 *  up/down never lands focus on a row's off-viewport pad (M3). 90min (was 30):
 *  D-pad RIGHT/LEFT needs the NEXT cell already composed so focus can step onto
 *  it; at 30min the next program often sat just past the pad and unrendered, so
 *  the focus search found no horizontal neighbour. Pairs with the grid's
 *  L/R focus-exit cancel so horizontal nav never escapes to the chrome. */
private const val GUIDE_VIEWPORT_PAD_MS = 90L * 60_000L

/** Convert a millisecond span to its dp width on the (already scaled) time axis. */
private fun msToDp(ms: Long, hourWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    (hourWidth.value * (ms.toFloat() / MS_PER_HOUR_F)).dp

/**
 * Drives D-pad UP/DOWN through the EPG grid on Android TV.
 *
 * The guide is one [LazyColumn] of rows that all share a single horizontal
 * scroll. Letting Compose's default focus search handle channel up/down had two
 * bugs (see the call site for the full write-up): it scrolled the SHARED
 * timeline sideways to align whatever cell it landed on (Bug 1), and when a fast
 * UP outran row composition it leaked focus to the top nav pill (Bug 2).
 *
 * This holder keeps the focus deterministic:
 *  - Every composed row registers a [RowFocusHandler] keyed by its channel index
 *    ([registerRow]); the handler can focus the cell at a given anchor time.
 *  - Each programme cell reports focus + its leading-edge time via
 *    [onCellFocused], which records the focused channel index and the anchor
 *    time (the column the user is "in").
 *  - [moveFocusToChannel] scrolls the target row into existence if needed, then
 *    focuses its cell at the anchor time, with [suppressHorizontalScroll] held
 *    true across the move so the shared timeline never pans.
 */
private class GuideVerticalNavState {
    /** Index (into the filtered channel list) of the row whose cell is focused;
     *  -1 when focus is outside the grid. Read by the key handler. */
    var focusedChannelIndex by mutableStateOf(-1)
        private set

    /** STICKY last focused row index: set on every cell focus, NEVER reset to -1.
     *  Drives the "compose the whole focused row" clip exception. Using the
     *  churning [focusedChannelIndex] there created a feedback loop (it blips to
     *  -1 -> row reverts to clipped -> the focused cell disposes -> more churn ->
     *  escape). This stable value keeps the focused row fully composed across the
     *  blips so the focused cell never disposes and focus is never orphaned. */
    var lastFocusedChannelIndex by mutableStateOf(-1)
        private set

    /** uptimeMillis of the last vertical (channel up/down) move. Plain field, not
     *  observable state -- only the key handler reads/writes it to throttle the
     *  held-key fast-scroll to [HOLD_SCROLL_MIN_INTERVAL_MS]. */
    var lastVerticalMoveAtMs = 0L

    /** True from [beginVerticalMove] until the move coroutine settles. Lets the
     *  key handler keep stepping channel-by-channel on rapid / long-press UP
     *  even while [focusedChannelIndex] is transiently -1 mid-move (Bug 4). */
    var verticalMoveInFlight by mutableStateOf(false)
        private set

    /** The channel index the in-flight vertical move is heading to. The key
     *  handler reads THIS (not the churning [focusedChannelIndex]) while a move
     *  is in flight, so a fast UP coalesces to the next channel up instead of
     *  reading -1 and escaping to the nav pill. -1 when no move is in flight. */
    var pendingTargetIndex by mutableStateOf(-1)
        private set

    /** Anchor time (ms) the user is navigating along -- the start time of the
     *  focused cell, clamped to the guide window. Vertical moves target the cell
     *  in the next/prev row that spans this time, keeping the on-screen column
     *  fixed. */
    var anchorTimeMs: Long = Long.MIN_VALUE
        private set

    /** Content-coordinate px bounds (x relative to the scroll origin = window
     *  start) of the currently-focused cell, set on focus. The key handler reads
     *  these to PAGE the timeline within a long program instead of leaping a full
     *  6-hour block to the next one. -1 when no cell is focused. */
    var focusedCellStartPx: Int = -1
        private set
    var focusedCellEndPx: Int = -1
        private set

    fun setFocusedCellBounds(startPx: Int, endPx: Int) {
        focusedCellStartPx = startPx
        focusedCellEndPx = endPx
    }

    /** While true the [GuideLeadingEdgeBringIntoViewSpec] returns 0 so a
     *  vertical move never scrolls the shared horizontal timeline. Set true at
     *  the start of a channel up/down move and held until the user next presses
     *  LEFT/RIGHT (a deliberate horizontal move). A plain volatile flag, not a
     *  Compose state -- the spec reads it imperatively, not in composition. */
    @Volatile
    var suppressHorizontalScroll: Boolean = false
        private set

    /** True only between a programmatic (vertical-move) requestFocus and the
     *  onCellFocused it triggers, so that focus is treated as column-preserving
     *  and does NOT overwrite [anchorTimeMs]. Consumed by onCellFocused, or
     *  cleared if the focus request fails. Without this the anchor drifts to each
     *  landed cell's programme start (Bug 1). Main-thread only, so a plain var. */
    private var programmaticFocusPending: Boolean = false

    /** Bumped at the start of each vertical move so only the latest move's tail
     *  releases the timeline freeze -- a rapid auto-repeat holds it until the
     *  final landing instead of an earlier move's finally releasing it early. */
    private var moveGeneration: Int = 0

    /** Begin a vertical (channel up/down) move: freeze the shared timeline so the
     *  focus-driven bring-into-view of a wide cell can't pan it sideways. */
    fun beginVerticalMove() {
        suppressHorizontalScroll = true
        verticalMoveInFlight = true
    }

    /** Pin the navigation anchor to a specific time before a programmatic focus.
     *  Focus-on-return seeds 'now' so the focused cell is the live programme
     *  rather than the window-edge cell the Long.MIN_VALUE default would pick. */
    fun seedAnchor(timeMs: Long) {
        anchorTimeMs = timeMs
    }

    /** The user pressed LEFT/RIGHT (or clicked): release the timeline so normal
     *  horizontal navigation scrolls again. */
    fun allowHorizontalScroll() {
        suppressHorizontalScroll = false
    }

    /** Per-row hook: focus the cell at [anchorTimeMs]; returns true if a cell
     *  was found and focused. */
    fun interface RowFocusHandler {
        fun focusAtTime(anchorTimeMs: Long): Boolean
    }

    private val rowHandlers = mutableMapOf<Int, RowFocusHandler>()

    fun registerRow(channelIndex: Int, handler: RowFocusHandler) {
        rowHandlers[channelIndex] = handler
    }

    fun unregisterRow(channelIndex: Int) {
        rowHandlers.remove(channelIndex)
    }

    /** Per-row hook: focus the cell adjacent to [fromAnchorMs] in the [forward]
     *  direction; returns true if an adjacent COMPOSED cell was focused, false at
     *  the edge of the composed window. LEFT/RIGHT is driven explicitly through
     *  this (mirroring the UP/DOWN model) so the default 2D focus search -- which
     *  escalates out of the grid focusGroup to the chrome when it finds no
     *  in-group neighbour -- is never invoked. */
    fun interface RowHorizontalHandler {
        fun focusAdjacent(forward: Boolean, fromAnchorMs: Long): Boolean
    }

    private val rowHorizontalHandlers = mutableMapOf<Int, RowHorizontalHandler>()

    fun registerRowHorizontal(channelIndex: Int, handler: RowHorizontalHandler) {
        rowHorizontalHandlers[channelIndex] = handler
    }

    fun unregisterRowHorizontal(channelIndex: Int) {
        rowHorizontalHandlers.remove(channelIndex)
    }

    /** Step focus one cell left/right within the focused row. Returns false if
     *  there is no focused row or no adjacent composed cell (caller then consumes
     *  the key so focus stays put instead of escaping to the chrome). */
    fun stepHorizontal(forward: Boolean): Boolean {
        val i = focusedChannelIndex
        if (i < 0 || anchorTimeMs == Long.MIN_VALUE) return false
        return rowHorizontalHandlers[i]?.focusAdjacent(forward, anchorTimeMs) ?: false
    }

    /** Called by a cell when it gains focus. Records which channel row owns
     *  focus and the time column the user is in. */
    fun onCellFocused(channelIndex: Int, cellStartMs: Long) {
        focusedChannelIndex = channelIndex
        lastFocusedChannelIndex = channelIndex
        // Preserve the navigation column across vertical (channel up/down) moves.
        // A handler-driven focus sets programmaticFocusPending first; we consume it
        // and keep the existing anchor instead of snapping to this cell's start.
        // Only a user horizontal / fresh-entry focus (flag false) adopts a new
        // column. (Bug 1: without this the anchor drifts up to an hour per press.)
        if (programmaticFocusPending) {
            programmaticFocusPending = false
        } else {
            anchorTimeMs = cellStartMs
        }
    }

    /** Called by a cell when it loses focus; clears the focused index only if it
     *  still pointed at this cell's row (a sibling may already have claimed it). */
    fun onCellUnfocused(channelIndex: Int) {
        if (focusedChannelIndex == channelIndex) focusedChannelIndex = -1
    }

    /**
     * Move focus to [targetIndex]'s row at the current [anchorTimeMs]. Scrolls
     * the row into the viewport first if it isn't fully visible (so a fast UP/DOWN
     * never loses focus to an uncomposed row), then retries focusing for a few
     * frames while the row composes and registers its handler.
     */
    suspend fun moveFocusToChannel(targetIndex: Int, listState: LazyListState) {
        // The shared timeline is frozen for the duration of this move (set true by
        // beginVerticalMove on the key handler) so the landing cell's bring-into-
        // view can't pan it sideways. We release it in the finally once the move
        // settles, plus a short tail for a late bring-into-view, rather than
        // latching it until the next LEFT/RIGHT -- so OK / Back / jump-to-now can
        // still reveal off-screen cells afterwards (fixes the latch regression).
        val gen = ++moveGeneration
        pendingTargetIndex = targetIndex
        try {
            ensureRowVisible(targetIndex, listState)
            // The row may have only just composed (after a scroll); poll its handler
            // for a handful of frames before giving up.
            var attempts = 0
            while (attempts < 8) {
                val handler = rowHandlers[targetIndex]
                if (handler != null) {
                    // This requestFocus is programmatic: have onCellFocused PRESERVE
                    // the current anchor column rather than adopt the landing cell's
                    // start (the Bug-1 anchor-drift fix).
                    programmaticFocusPending = true
                    if (handler.focusAtTime(anchorTimeMs)) {
                        focusedChannelIndex = targetIndex
                        return
                    }
                    programmaticFocusPending = false
                }
                attempts++
                kotlinx.coroutines.delay(8L)
            }
        } finally {
            // Hold the freeze a few frames so a bring-into-view that lands a frame
            // late is still suppressed, then release -- but only if this is still
            // the most recent move (rapid auto-repeat keeps it frozen until the
            // final landing). NonCancellable so a superseding press that cancels
            // this coroutine still runs the release decision.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.delay(48L)
                if (gen == moveGeneration) {
                    suppressHorizontalScroll = false
                    verticalMoveInFlight = false
                    pendingTargetIndex = -1
                }
            }
        }
    }

    /**
     * Land D-pad focus on [targetIndex]'s NOW cell, re-asserting until it
     * sticks. On return-from-player and after a Back scroll-to-top the top nav
     * bar's focusRestorer competes for focus; a single requestFocus can lose
     * that race (focus ends on the Live TV pill / a group pill), so seed the
     * 'now' column and retry [moveFocusToChannel] until [focusedChannelIndex]
     * actually equals the target.
     */
    suspend fun focusChannelAtNow(targetIndex: Int, nowMs: Long, listState: LazyListState) {
        focusChannelAt(targetIndex, nowMs, listState)
    }

    /** Land D-pad focus on [targetIndex]'s cell at the [anchorMs] time column,
     *  re-asserting until it sticks (same competing-focusRestorer rationale as
     *  [focusChannelAtNow], which delegates here). The dialog focus-restore
     *  path passes the originating cell's own start time so Program Info /
     *  Record dismissal returns focus to the exact cell that opened it. */
    suspend fun focusChannelAt(targetIndex: Int, anchorMs: Long, listState: LazyListState) {
        if (targetIndex < 0) return
        seedAnchor(anchorMs)
        beginVerticalMove()
        repeat(14) {
            moveFocusToChannel(targetIndex, listState)
            if (focusedChannelIndex == targetIndex) return
            kotlinx.coroutines.delay(16L)
        }
    }

    /** Scroll [listState] the minimum amount to bring [index] fully on-screen,
     *  composing it if it was scrolled off entirely. */
    private suspend fun ensureRowVisible(index: Int, listState: LazyListState) {
        var info = listState.layoutInfo
        var item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null) {
            // The row is scrolled fully off an edge. Bring it to the NEAREST
            // edge so a D-pad move advances ONE row at a time, instead of
            // slamming the target to the leading (top) edge -- that was the
            // "guide jumps to N..N+7 when you cross the bottom fold" bug (e.g.
            // ch 1 -> down -> ch 9 suddenly shows 9..16). scrollToItem still
            // composes the row instantly.
            val visible = info.visibleItemsInfo
            val firstVisible = visible.firstOrNull()?.index ?: index
            if (visible.isEmpty() || index < firstVisible) {
                // Off the TOP (moving up) or no anchor: align to the top edge,
                // which is the minimal scroll in that direction.
                listState.scrollToItem(index)
            } else {
                // Off the BOTTOM (moving down): land the target as the LAST
                // visible row so the guide advances a single row.
                listState.scrollToItem((index - visible.size + 1).coerceAtLeast(0))
            }
            // Re-read after composing so the deficit nudge below can finish the
            // job: scrollToItem(last) lands the target at the bottom but leaves it
            // PARTIALLY CLIPPED (its bottom hangs below the fold), which read as
            // "the bottom focused channel is cut off so I can't read it". Falling
            // through to the bottomOverflow nudge pulls the whole row on-screen.
            info = listState.layoutInfo
            item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        }
        // Partially clipped at an edge: nudge by the deficit so the whole row is
        // visible before we focus it.
        val viewportStart = info.viewportStartOffset
        val viewportEnd = info.viewportEndOffset
        val topGap = item.offset - viewportStart
        val bottomOverflow = (item.offset + item.size) - viewportEnd
        when {
            topGap < 0 -> listState.scrollBy(topGap.toFloat())
            bottomOverflow > 0 -> listState.scrollBy(bottomOverflow.toFloat())
        }
    }
}

/**
 * Guide timeline bring-into-view (GH #5). Compose's default spec aligns a
 * focused child's TRAILING edge, which for a programme cell WIDER than the
 * viewport scrolls the timeline all the way to that programme's END -- the
 * disorienting "jump to end-of-line" users reported. This variant pins the
 * LEADING edge for oversized cells (their tail simply overflows right) and
 * otherwise falls back to the default minimal-nudge, so ordinary navigation
 * feels unchanged.
 *
 * [suppressHorizontalScroll] is consulted on every focus-driven bring-into-view:
 * while a D-pad UP/DOWN vertical move is in flight (the guide's own key handler
 * is moving focus to a time-aligned cell in the prev/next row), it returns 0 so
 * the SHARED horizontal timeline does NOT pan. Without this, focusing a long
 * program whose leading edge sits off the left of the viewport would fling every
 * row sideways on a plain channel-up/down -- the "jumps around a lot" report.
 * LEFT/RIGHT navigation leaves the flag false, so horizontal scroll behaves
 * exactly as before.
 */
@OptIn(ExperimentalFoundationApi::class)
private class GuideLeadingEdgeBringIntoViewSpec(
    private val suppressHorizontalScroll: () -> Boolean,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        // Vertical (channel up/down) move in flight: never scroll the shared
        // timeline horizontally. The target cell is chosen at the current
        // anchor time so it is already in the right column; any nudge here
        // would desync every other row.
        if (suppressHorizontalScroll()) return 0f
        // Already fully visible: no scroll.
        if (offset >= 0f && offset + size <= containerSize) return 0f
        // Oversized cell (wider than the viewport): if ANY part already overlaps
        // the viewport, do NOT scroll. The sticky program title keeps the name
        // pinned + readable at the visible left edge, so focusing a long program
        // must never fling the timeline (previously it slammed the cell's leading
        // edge to the viewport start -- the "Right rapidly scrolls right / Left
        // jumps to the beginning of the EPG" report). Only an oversized cell that
        // is ENTIRELY off one side gets a minimal nudge onto screen.
        if (size > containerSize) {
            return when {
                offset < containerSize && offset + size > 0f -> 0f   // overlaps viewport: stay put
                offset >= containerSize -> offset                     // entirely off the right: leading edge -> start
                else -> offset + size - containerSize                 // entirely off the left: trailing edge -> end
            }
        }
        // Normal cell: minimal nudge to bring it just into view (leading if off
        // the start, trailing if off the end).
        return if (offset < 0f) offset else offset + size - containerSize
    }
}
