package com.aeriotv.android.feature.livetv

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import com.aeriotv.android.feature.main.LocalTvTopNavFocusRequester
import com.aeriotv.android.ui.LocalCanRecordToServer
import com.aeriotv.android.core.preferences.GUIDE_SCALE_MAX
import com.aeriotv.android.core.preferences.GUIDE_SCALE_MIN
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.aeriotv.android.feature.favorites.FavoritesViewModel
import com.aeriotv.android.feature.multiview.MultiviewStoreHandle
import com.aeriotv.android.feature.multiview.rememberMultiviewStoreHandle
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import com.aeriotv.android.feature.reminders.RemindersViewModel
import com.aeriotv.android.feature.settings.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
@OptIn(ExperimentalMaterial3Api::class)
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
    // Audit task #22: staged-channel banner. When the user has added at
    // least one channel to Multiview (via the row context menu / long
    // press), surface a "X channels staged" pill with a Play button at
    // the top of the guide so they can launch without backing out to the
    // dedicated tab.
    val stagedMultiview by multiviewStore.selected.collectAsStateWithLifecycle()
    // tvOS-style guide controls: a search field toggle + a group on/off filter.
    val hiddenGroups by settingsVm.hiddenGroups.collectAsStateWithLifecycle(initialValue = emptySet())
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

    var programInfoTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }

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
    val allGroupNames by remember(state.channels) {
        derivedStateOf {
            // First-occurrence order so groups follow the channel ordering
            // (which the server/playlist defines), NOT alphabetical. distinct()
            // preserves encounter order.
            state.channels.asSequence()
                .map { it.groupTitle }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
        }
    }
    // Visible chips: all groups minus the ones hidden via the filter picker.
    val groups by remember(state.channels) {
        derivedStateOf {
            listOf(PlaylistViewModel.ALL_GROUPS) + allGroupNames.filter { it !in hiddenGroups }
        }
    }

    val filteredChannels by remember(state.channels, state.selectedGroup) {
        derivedStateOf {
            val query = state.searchQuery.trim()
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
                .sortedWith(compareBy({ it.channelNumber?.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.name.lowercase() }))
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
            val padMs = 30L * 60_000L
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isTv) 24.dp else 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .clickable(onClick = onLaunchMultiview)
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
                TextButton(onClick = onLaunchMultiview) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isTv) 52.dp else 56.dp)
                .padding(horizontal = if (isTv) 24.dp else 4.dp),
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
            // Search toggle: reveals an inline channel-name search field.
            IconButton(
                onClick = {
                    searchActive = !searchActive
                    if (!searchActive) viewModel.onSearchQueryChange("")
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search channels",
                    tint = if (searchActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            // Filter toggle: opens the group on/off picker (Manage Groups).
            IconButton(
                onClick = { showManageGroups = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.FilterList,
                    contentDescription = "Filter groups",
                    tint = if (hiddenGroups.isEmpty())
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(if (isTv) 12.dp else 6.dp))
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
                    contentPadding = PaddingValues(end = if (isTv) 24.dp else 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(groups, key = { it }) { group ->
                        val pillSelected = state.selectedGroup == group
                        FilterChip(
                            selected = pillSelected,
                            onClick = { viewModel.onGroupSelected(group) },
                            label = {
                                Text(
                                    group,
                                    style = if (isTv) MaterialTheme.typography.titleMedium
                                    else MaterialTheme.typography.labelLarge,
                                )
                            },
                            // tvOS TVGroupPill (ChannelListView.swift:2830): unselected
                            // pills are FILLED capsules (Color.elevatedBackground), not
                            // outlined -- selected fills with accentPrimary + dark text.
                            // Drop the FilterChip outline on TV and give the unselected
                            // state a subtle elevated fill so the row reads as a strip
                            // of soft pills exactly like the tvOS guide.
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = if (isTv)
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                else Color.Transparent,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            border = if (isTv) null else FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = pillSelected,
                            ),
                        )
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
        val backScope = androidx.compose.runtime.rememberCoroutineScope()
        var showExitDialog by remember { mutableStateOf(false) }
        val activity = LocalContext.current.findActivity()
        androidx.activity.compose.BackHandler(enabled = !showExitDialog) {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
            if (!atTop) {
                backScope.launch { listState.animateScrollToItem(0) }
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
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // Treat the grid as one focus group so D-pad DOWN from the chips
                // / top bar reliably descends into the programme cells on TV.
                .focusGroup()
                .then(
                    if (topNavRequester != null) {
                        Modifier.focusProperties { up = topNavRequester }
                    } else Modifier
                )
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
            items(items = filteredChannels, key = { it.id }) { channel ->
                val programmes = state.epgByChannel[channel.guideMatchKey].orEmpty()
                ChannelGuideRow(
                    channel = channel,
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
                        programInfoTarget = programme.toInfoTarget(channel.name, channel.dispatcharrChannelId)
                    },
                    onProgrammeRecord = { programme ->
                        recordTarget = programme.toInfoTarget(channel.name, channel.dispatcharrChannelId)
                    },
                    isFavorite = channel.id in favoriteIds,
                    onToggleFavorite = { favoritesVm.toggle(channel) },
                    palette = palette,
                    multiviewStore = multiviewStore,
                    showLogo = showChannelLogos,
                )
                HorizontalDivider(color = guideDivider, thickness = 0.5.dp)
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
            )
        } else {
            ManageGroupsSheet(
                allGroups = allGroupNames,
                hiddenGroups = hiddenGroups,
                onSave = { settingsVm.setHiddenGroups(it) },
                onDismiss = { showManageGroups = false },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelGuideRow(
    channel: M3UChannel,
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

    // Compact rail sizing. On TV we keep it tight (narrow rail, small logo) so
    // more channels fit; legibility comes from the name/cell text, not bulk.
    val numberStyle = if (isTv) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall
    // tvOS minWidth 38pt -> 19dp proportional, but a 4-digit number ("1444") at
    // labelMedium needs ~24dp; 28dp accommodates 4 digits with margin. Phone
    // keeps the compact 22dp.
    val numberWidth = if (isTv) 28.dp else 22.dp
    // logoBox/logoImage are the PHONE rail's square logo. TV uses a landscape
    // logo-over-name VStack (the isTv branch below) so the channel name gets its
    // own full-width line and shows in full, mirroring tvOS channelLabel.
    val logoBox = 36.dp
    val logoImage = 32.dp
    // TV name uses labelMedium (10.8sp at the 0.9 type scale) - the closest match
    // to tvOS's 18pt name (~9sp proportional on 540dp). bodyMedium would be ~17%
    // bigger than tvOS proportional. Phone keeps labelMedium.
    val nameStyle = if (isTv) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelMedium
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
                .combinedClickable(
                    onClick = onChannelClick,
                    onLongClick = { railMenuOpen = true; railMenuGuard.arm() },
                )
                // tvOS channelLabel uses .padding(.horizontal, 8) on a 240pt
                // column -> proportional 4dp on the 120dp Android-TV column;
                // phone keeps the 8dp.
                .padding(horizontal = if (isTv) 4.dp else 8.dp),
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
                Spacer(Modifier.width(8.dp))
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
                    if (rawEnd <= visibleStartMs || rawStart >= visibleEndMs) return@forEachIndexed
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
                    val isLive = programme.startMillis <= nowMillis && programme.endMillis > nowMillis
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
                    ProgrammeCell(
                        programme = programme,
                        channelName = channel.name,
                        channelId = channel.id,
                        widthDp = wDp,
                        isLive = isLive,
                        liveProgress = liveProgress,
                        isTv = isTv,
                        activeReminderKeys = activeReminderKeys,
                        remindersVm = remindersVm,
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProgrammeCell(
    programme: EPGProgramme,
    channelName: String,
    channelId: String,
    widthDp: androidx.compose.ui.unit.Dp,
    isLive: Boolean,
    /** Elapsed fraction (0..1) of the now-airing program, or null if not live.
     * Drives the bottom progress bar that marks the currently-airing cell. */
    liveProgress: Float?,
    isTv: Boolean,
    activeReminderKeys: Set<String>,
    remindersVm: RemindersViewModel,
    categoryTint: androidx.compose.ui.graphics.Color?,
    modifier: Modifier,
    onPlay: () -> Unit,
    onShowInfo: () -> Unit,
    onRecord: () -> Unit,
    canAddToMultiview: Boolean,
    inMultiview: Boolean,
    onToggleMultiview: () -> Unit,
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
    Column(
        modifier = modifier
            .width(widthDp)
            .fillMaxHeight()
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
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                // Single tap/click plays the channel; the program-info sheet +
                // actions live behind a long press (the menu below). iOS parity.
                onClick = onPlay,
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
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            DropdownMenuItem(
                text = { Text("Program Info") },
                onClick = menuGuard.wrap {
                    menuOpen = false
                    onShowInfo()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(if (isReminderSet) "Cancel Reminder" else "Set Reminder")
                },
                onClick = menuGuard.wrap {
                    menuOpen = false
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
            if (canAddToMultiview) {
                DropdownMenuItem(
                    text = {
                        Text(if (inMultiview) "Remove from Multiview" else "Add to Multiview")
                    },
                    onClick = menuGuard.wrap {
                        menuOpen = false
                        onToggleMultiview()
                    },
                )
            }
            if (programme.endMillis > System.currentTimeMillis() && LocalCanRecordToServer.current) {
                val recordLabel = if (isLive) "Record from Now" else "Record"
                DropdownMenuItem(
                    text = { Text(recordLabel) },
                    onClick = menuGuard.wrap {
                        menuOpen = false
                        onRecord()
                    },
                )
            }
        }
        if (isTv) {
            // tvOS cell: bold title + 1-line program description + a time-range
            // line, matching EPGGuideView.swift:3146 cellContent. Title turns
            // white on focus (over the bright fill); description dims to a
            // soft white, time-range dims further.
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
            Text(
                text = timeRange,
                style = MaterialTheme.typography.labelSmall,
                color = if (focused) Color.White.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
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

/** Convert a millisecond span to its dp width on the (already scaled) time axis. */
private fun msToDp(ms: Long, hourWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    (hourWidth.value * (ms.toFloat() / MS_PER_HOUR_F)).dp
