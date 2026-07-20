package com.aeriotv.android.feature.livetv

import android.widget.Toast
import androidx.compose.ui.draw.clipToBounds
import kotlin.math.abs
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import com.aeriotv.android.core.pip.findActivity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.canReplay
import com.aeriotv.android.core.data.db.entity.reminderKey
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.core.ui.EpgFlagsRow
import com.aeriotv.android.core.ui.LocalShowEpgBadges
import com.aeriotv.android.core.ui.SeasonEpisodePill
import com.aeriotv.android.core.ui.epgFlags
import com.aeriotv.android.core.ui.seasonEpisodeLabel
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.feature.collections.AddToCollectionFlow
import com.aeriotv.android.feature.collections.CollectionPill
import com.aeriotv.android.feature.collections.CollectionsMenuContext
import com.aeriotv.android.feature.collections.CollectionsViewModel
import com.aeriotv.android.feature.favorites.FavoritesViewModel
import com.aeriotv.android.feature.settings.dpadFocusRing
import com.aeriotv.android.feature.settings.rememberIsTvDevice
import com.aeriotv.android.feature.multiview.MultiviewStoreHandle
import com.aeriotv.android.feature.multiview.rememberMultiviewStoreHandle
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.playlist.SortMode
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
    onOpenSearch: () -> Unit = {},
    /** Catch-up (task #133/#136): play a resolved timeshift URL in the
     *  recording player, carrying the programme window + panel timezone the
     *  player needs to rebuild the URL for scrub-seeks. */
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
    val guideContext = LocalContext.current
    // Catch-up (task #133): resolve a past programme to its timeshift URL off
    // the main thread, then play; failures (no XC password on the Dispatcharr
    // user, no archive, transport) surface as a toast.
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
                    android.widget.Toast.makeText(
                        guideContext,
                        t.message ?: "Catch-up is unavailable for this programme.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }
    val favoritesVm: FavoritesViewModel = hiltViewModel()
    val favoritesList by favoritesVm.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds = remember(favoritesList) { favoritesList.map { it.channelId }.toSet() }
    val settingsVm: SettingsViewModel = hiltViewModel()
    val palette by settingsVm.categoryPalette.collectAsStateWithLifecycle(initialValue = CategoryPaletteState.Default)
    val epgWindowHours by settingsVm.epgWindowHours.collectAsStateWithLifecycle(initialValue = 24)
    val showChannelLogos by settingsVm.showChannelLogos.collectAsStateWithLifecycle(initialValue = true)
    // Remote Control A2: live button map (guide context slots).
    val remoteMap by settingsVm.remoteControlMap.collectAsStateWithLifecycle(
        initialValue = com.aeriotv.android.core.remote.RemoteControlMap.DEFAULT,
    )
    val showChannelNumbers by settingsVm.showChannelNumbers.collectAsStateWithLifecycle(initialValue = true)
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
    // #10 tvOS parity: HOLD Left in the grid jumps D-pad focus to the "All"
    // group pill. This requester is attached to that pill (the first item in
    // the group row) and fired from the grid's key handler.
    val allPillFocus = remember { FocusRequester() }
    // Remote Control A2: late-bound handle so the (earlier-composed)
    // pills-row hold-Left handler can dispatch through the guide action
    // executor declared further down (it depends on nav scopes created
    // later in composition).
    val guideActionDispatcher = remember {
        androidx.compose.runtime.mutableStateOf<((com.aeriotv.android.core.remote.GuideRemoteAction) -> Boolean)?>(null)
    }
    // Streamer field report 2026-07-12: D-pad DOWN from the top nav landed on
    // whichever group pill sat geometrically under the Live TV tab ("General"),
    // not the SELECTED pill. This requester rides the currently selected group
    // pill; the pill row's focusProperties.enter redirects a DOWN entry to it.
    // The attach counter guards the redirect: a LazyRow item that is scrolled
    // out of composition has no bound requester, and returning an unbound
    // requester from enter would throw - count > 0 means the selected pill is
    // actually composed. Counter (not Bool) because on a selection change the
    // new pill's DisposableEffect can run before the old one's onDispose.
    val selectedPillFocus = remember { FocusRequester() }
    val selectedPillAttachCount = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // #10/#14 overshoot fix: once a HOLD Left lands focus on the "All" pill, the
    // still-held Left keeps auto-repeating and Compose focus traversal walks
    // straight PAST All onto the leading Guide/Search/List circles. Pin focus to
    // All for the duration of the hold: while this is true the top control row's
    // onPreviewKeyEvent (parent of BOTH the circles and the pill row) swallows
    // every further Left and re-requests All, until the key is RELEASED
    // (ACTION_UP) or the safety timeout fires. Mirrors the tvOS leftHoldPinningAll
    // flag (ChannelListView.swift).
    var leftHoldPinningAll by remember { mutableStateOf(false) }
    // Backstop so focus is never permanently trapped on All if the key-release
    // event is missed: unpin after 2.5s. During an actual ongoing hold events
    // keep arriving, so the pin re-engages on the next repeat -- this is a
    // self-heal for a dropped release, not a hard cap on hold length.
    LaunchedEffect(leftHoldPinningAll) {
        if (leftHoldPinningAll) {
            delay(2_500L)
            leftHoldPinningAll = false
        }
    }
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

    // Channel Collections (#45): user-named channel groupings rendered as
    // extra filter pills (placement "beginning" = before All, "end" = after
    // the last group) with a "collection:<id>" selectedGroup sentinel. The
    // menu context threads through ChannelGuideRow into each ProgrammeCell's
    // long-press menu (Add to Collection / contextual Remove).
    val collectionsVm: CollectionsViewModel = hiltViewModel()
    val collections by collectionsVm.collections.collectAsStateWithLifecycle(initialValue = emptyList())
    var collectionPickerFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    // The guide cell (row index + anchor time) the picker was opened from, so
    // closing it can restore D-pad focus there (dialogFocusOrigin/Tick are
    // declared below, hence this staging state). Consumed in the
    // AddToCollectionFlow onClose.
    var collectionPickerOrigin by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    val collectionsMenu = remember(collections, state.selectedGroup) {
        CollectionsMenuContext(
            collections = collections,
            activeCollectionId = ChannelCollection.idFromToken(state.selectedGroup),
            onOpenPicker = { chId, chName, idx, anchor ->
                if (idx >= 0) collectionPickerOrigin = idx to anchor
                collectionPickerFor = chId to chName
            },
            onRemoveMember = collectionsVm::removeMember,
            onRemoveFromAll = collectionsVm::removeFromAll,
        )
    }

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
    // GH #25: on TV the guide also honors Appearance > Display Scale >
    // "Live TV List" ("even at 125% the guide is hard to see" from across
    // the room). 150/175% trades density for size: rows, rail, hour width,
    // and cell text all grow together, so the grid shows fewer, LARGER
    // items. Phone keeps 1x here (pinch zoom already covers the phone).
    val liveTvDisplayScale by settingsVm.displayScaleLiveTV
        .collectAsStateWithLifecycle(initialValue = 1f)
    val tvComfortScale = if (isTv) liveTvDisplayScale.coerceIn(0.85f, 1.75f) else 1f

    // tvOS pixelsPerHour = 600pt on a 1920x1080pt canvas; the proportional value
    // on the 960x540dp Android-TV canvas is 300 dp/hour (= 600 * 0.5). Phone keeps
    // the GuideMetrics.HOUR_WIDTH base (320dp), scaled by guideScale.
    val scaledHourWidth = (if (isTv) 300.dp else GuideMetrics.HOUR_WIDTH) * scale * tvComfortScale

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // The channel rail must stay a small slice of the viewport or it eats the
    // programme grid. tvOS uses channelColumnWidth = 240pt / 1920 = 12.5% of width;
    // on the 960dp-wide Android-TV canvas that is 120dp. iOS uses a FIXED 100pt
    // column on every iPhone AND iPad (EPGGuideView.swift channelColumnWidth,
    // no size-class branching) and fits it by stacking the rail cell vertically
    // (logo over a one-line name over the number); the phone cell below mirrors
    // that stack, so the phone/tablet rail can match the iOS width. 78dp matches
    // iOS EPGGuideView's compact-width channelColumnWidth (78pt) so the phone
    // guide reads as dense as iPhone (104dp ate ~30% of a Fold's outer screen
    // vs iPhone's ~18%). The old side-by-side number+logo+name cell needed 168dp
    // (user report). Unlike iOS the width deliberately ignores the guide zoom
    // [scale], which on Android scales the time axis only.
    val railWidth = if (isTv) 120.dp * tvComfortScale else 78.dp
    // Row + time-header sized to tvOS PROPORTIONS on the 960x540dp Android-TV
    // canvas (NOT copied from tvOS point values, which would be ~2x too big).
    // tvOS rowHeight 110pt / 1080 = 10.19% -> 540dp * 0.1019 = 55dp;
    // timeHeader 50pt / 1080 = 4.63% -> 540dp * 0.0463 = 25dp.
    val rowHeight = if (isTv) 55.dp * tvComfortScale else GuideMetrics.ROW_HEIGHT
    val headerHeight = if (isTv) 25.dp * tvComfortScale else GuideMetrics.HEADER_HEIGHT
    // tvOS draws the guide grid separators as cyan (accentPrimary) hairlines, not
    // neutral gray; mirror that on TV so the grid reads as one continuous surface.
    // Phone keeps the existing gray surfaceVariant divider.
    val guideDivider = if (isTv) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surfaceVariant
    // Channel-to-channel separator: stronger than the header hairline so two
    // adjacent rows with the SAME category tint still read as distinct rows
    // (user request -- the 0.5dp / 15% line was nearly invisible between
    // same-colored channels). Phone now shares the accent hairline: with the
    // flat Emby-style phone cells (visual-parity polish) the divider is the
    // only row separation, same as TV, and the old Material `outline` gray
    // was the one off-palette line in the guide.
    val guideRowDivider = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
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

    // Guide window: the playlist's retained catch-up history before "now"
    // (epgHistoryHours, task #135: epgRetentionDays * 24 once the deferred
    // history merge lands; 1h during cold-launch paint), then `epgWindowHours`
    // ahead. The forward span comes from Settings > Network > EPG Window
    // (6/12/24/36/48/72h or "All available"). iOS `epgWindowHours` parity.
    val historyMs = state.epgHistoryHours.coerceAtLeast(1).toLong() * 3_600_000L
    val windowStart = remember(nowMillis, historyMs) {
        // Floor `now` to the start of the current hour to keep header labels clean.
        val hourMs = 3_600_000L
        (nowMillis / hourMs) * hourMs - historyMs
    }
    // "All available" (sentinel 0) spans from windowStart to the latest loaded
    // programme end, clamped to a 6h floor so a thin EPG still scrolls. A
    // numeric hour value spans that many hours past "now" (the history span
    // is added on top so back-scroll never eats the forward window).
    val windowDurationMs = remember(epgWindowHours, state.epgByChannel, windowStart, historyMs) {
        if (epgWindowHours > 0) {
            historyMs + epgWindowHours.toLong() * 3_600_000L
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
            // Drop any provider group literally named "All": it would collide
            // with the ALL_GROUPS sentinel and crash the pill LazyRow with a
            // duplicate key (and conflate selection with the sentinel).
            listOf(PlaylistViewModel.ALL_GROUPS) +
                allGroupNames.filter {
                    it !in hiddenGroups && !it.equals(PlaylistViewModel.ALL_GROUPS, ignoreCase = true)
                }
        }
    }

    val filteredChannels by remember(
        state.channels, state.selectedGroup, allGroupNames, groupSortMode, collections,
        state.sortMode, favoriteIds,
    ) {
        derivedStateOf {
            val query = state.searchQuery.trim()
            // #45: a "collection:<id>" sentinel filters to exactly the curated
            // members. The user picked them explicitly, so hidden-group
            // exclusion is bypassed (iOS filterChannels). A dangling sentinel
            // (collection deleted mid-view) falls through to showing
            // everything, exactly like iOS.
            val activeCollection = ChannelCollection.idFromToken(state.selectedGroup)
                ?.let { cid -> collections.firstOrNull { it.id == cid } }
            val collectionMembers = activeCollection?.memberIds?.toSet()
            // Treat the sentinel as a collection filter ONLY when it doesn't
            // collide with a real provider group literally named "collection:x"
            // (that group is in allGroupNames and must still filter normally).
            // A dangling sentinel (collection deleted mid-view) isn't a real
            // group, so it stays a collection filter and shows everything.
            val collectionSelected =
                state.selectedGroup.startsWith(ChannelCollection.TOKEN_PREFIX) &&
                    allGroupNames.none { it == state.selectedGroup }
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
                        collectionSelected -> collectionMembers?.contains(ch.id) ?: true
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
                // Same Sort menu contract as the List view (user report: the
                // Number / Name / Favorites toggle was missing from the
                // Guide): group clustering stays the primary key, then the
                // selected mode orders within it.
                .sortedWith(
                    when (state.sortMode) {
                        SortMode.ByName -> compareBy(
                            { if (clusterByGroup) groupRankIndex[it.groupTitle] ?: Int.MAX_VALUE else 0 },
                            { it.name.lowercase() },
                        )
                        SortMode.FavoritesFirst -> compareBy(
                            { if (clusterByGroup) groupRankIndex[it.groupTitle] ?: Int.MAX_VALUE else 0 },
                            { it.id !in favoriteIds }, // false (favorited) sorts first
                            { it.channelNumber?.toDoubleOrNull() ?: Double.MAX_VALUE },
                            { it.name.lowercase() },
                        )
                        SortMode.ByNumber -> compareBy(
                            { if (clusterByGroup) groupRankIndex[it.groupTitle] ?: Int.MAX_VALUE else 0 },
                            { it.channelNumber?.toDoubleOrNull() ?: Double.MAX_VALUE },
                            { it.name.lowercase() },
                        )
                    },
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

    // iOS-parity guide fling. On iPhone a horizontal flick settles to a bounded,
    // velocity-projected offset over a short fixed duration (predictedEndTranslation
    // + easeOut ~0.25s), so it moves a deliberate chunk and STOPS instead of
    // coasting. Compose's default fling is an uncapped decay that keeps sliding
    // until friction stops it, which read as uncontrolled next to iOS. Touch-only:
    // the TV timeline moves by D-pad, which never triggers a fling.
    val guideFling = remember(hourWidthPx) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                if (kotlin.math.abs(initialVelocity) < 1f) return initialVelocity
                // Project the flick roughly proportional to velocity (UIKit-style),
                // capped so one flick never travels more than ~3 hours, then settle
                // over 260ms with a decelerate curve. The 0.15 factor + cap are the
                // knobs to tune the "feel" on-device.
                val projected = (initialVelocity * 0.15f)
                    .coerceIn(-hourWidthPx * 3f, hourWidthPx * 3f)
                var last = 0f
                androidx.compose.animation.core.animate(
                    initialValue = 0f,
                    targetValue = projected,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 260,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                ) { value, _ ->
                    last += scrollBy(value - last)
                }
                return 0f
            }
        }
    }
    val stripViewportPx = with(density) {
        (screenWidthDp.dp - railWidth).coerceAtLeast(1.dp).toPx()
    }
    val visibleWindow by remember(windowStart, hourWidthPx, stripViewportPx) {
        derivedStateOf {
            // Task #188: 30-min buckets (was 5). Every bucket crossing during
            // an animated horizontal step re-emits this window and recomposes
            // EVERY visible row; a single step gliding across a wide cell
            // crossed 6-12 five-minute buckets = 6-12 full-grid passes, the
            // dominant slice of the measured 85ms-median horizontal frames.
            // Correctness is unaffected: rows compose GUIDE_VIEWPORT_PAD_MS
            // (90 min) beyond the window on both sides, so a 30-min snap can
            // never reveal an uncomposed region.
            val bucketMs = 30L * 60_000L
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
    // Task #136: initial scroll-to-now, run once per guide session PLUS a
    // re-anchor when windowStart shifts while the strip still sits at our
    // own anchor (see the effect comment below - slow first-launch hydration
    // can publish a wider history window after the first anchor).
    // epgHistoryHours is normally published with the playlist row itself
    // (before the guide composes), so windowStart is stable from the first
    // frame and the only wait is for the strip to measure its full scroll
    // range. rememberSaveable survives a nav push/pop (catch-up / recording
    // playback), so returning from a player keeps the timeline where the user
    // left it (rememberScrollState restores the px value) instead of yanking
    // back to now.
    var didScrollToNow by rememberSaveable { mutableStateOf(false) }
    // Pixel offset of OUR last programmatic anchor. -1 = unknown (e.g. after
    // a nav return, where rememberScrollState restored the user's spot and we
    // must not touch it). Deliberately NOT saveable.
    var autoAnchorPx by remember { mutableStateOf(-1) }
    // Re-keyed on windowStart (2026-07-12 Streamer field report): on a slow
    // first-post-install launch the deferred history merge publishes a wider
    // epgHistoryHours AFTER the initial anchor, windowStart leaps back, and
    // the preserved pixel offset silently points at a different wall-clock -
    // the guide sat hours off "now" over empty future cells. When the window
    // shifts and the strip is still EXACTLY where our anchor put it (the
    // user hasn't scrolled), re-anchor on the new timeline; a user-moved
    // strip is never yanked.
    // Re-keyed on tvComfortScale (Android TV Live TV List display scale): the
    // scale change alters hourWidthPx but leaves the horizontal scroll OFFSET
    // (px) untouched, so the same px now maps to a different wall-clock time -
    // the guide silently jumped hours/days into an empty window and the whole
    // programme grid blanked (user video 2026-07-14). Re-anchoring to now on a
    // scale change (only while the strip still sits at our own anchor, i.e. the
    // user hasn't scrolled away) re-maps the offset for the new hourWidthPx.
    // tvComfortScale is 1f and constant on phones, so this never re-fires there
    // and never fights a live pinch (guideScale is deliberately NOT a key).
    LaunchedEffect(filteredChannels.isNotEmpty(), windowStart, tvComfortScale) {
        if (filteredChannels.isEmpty()) return@LaunchedEffect
        if (didScrollToNow &&
            (autoAnchorPx < 0 || horizontalScrollState.value != autoAnchorPx)
        ) {
            return@LaunchedEffect
        }
        val nowOffsetPx =
            ((System.currentTimeMillis() - windowStart).toFloat() / 3_600_000f) * hourWidthPx
        // Place "now" at ~20% from the left (matches the tvOS screenshots:
        // ~30 min of past visible to the left of the now-line).
        val wanted = (nowOffsetPx - stripViewportPx * 0.20f).toInt().coerceAtLeast(0)
        // Wait for the strip to establish a scroll range that can hold the
        // target (it needs a frame or two to measure the multi-day window);
        // fall back to whatever range exists at timeout.
        val maxScroll = withTimeoutOrNull(2500L) {
            snapshotFlow { horizontalScrollState.maxValue }.first { it >= wanted }
        } ?: horizontalScrollState.maxValue.takeIf { it > 0 } ?: return@LaunchedEffect
        didScrollToNow = true
        autoAnchorPx = wanted.coerceIn(0, maxScroll)
        horizontalScrollState.scrollTo(autoAnchorPx)
        // Slow first launch: the timeout above can fire while the multi-day
        // strip is still measuring, clamping the anchor short of "now". Keep
        // following maxValue growth and re-anchoring, but ONLY while the strip
        // still sits exactly where we put it - the first user scroll (or a
        // vertical-nav alignment) breaks the loop and the position is theirs.
        while (autoAnchorPx < wanted) {
            val grown = withTimeoutOrNull(2500L) {
                snapshotFlow { horizontalScrollState.maxValue }.first { it > autoAnchorPx }
            } ?: break
            if (horizontalScrollState.value != autoAnchorPx) break
            autoAnchorPx = wanted.coerceIn(0, grown)
            horizontalScrollState.scrollTo(autoAnchorPx)
        }
    }

    // The host Scaffold sets contentWindowInsets = WindowInsets(0,0,0,0), so each
    // tab owns its own status-bar top inset (the List view gets it free from
    // CenterAlignedTopAppBar). The Guide's header is a bare control Row, so apply
    // the inset here or the controls + group pills render UNDER the status bar
    // (clock/battery overlap the All/Sports pills). No-op on Android TV (no status
    // bar). Matches ChannelListScreen's top-inset behaviour.
    // #45: the Add-to-Collection picker + chained New Collection name dialog,
    // opened from a programme cell's long-press menu. Dialogs render in their
    // own window, so composing here (outside the Column) is layout-neutral.
    collectionPickerFor?.let { (chId, chName) ->
        AddToCollectionFlow(
            channelId = chId,
            channelName = chName,
            isTv = isTv,
            collections = collections,
            onToggleMember = collectionsVm::toggleMember,
            onCreate = collectionsVm::create,
            onClose = {
                val origin = collectionPickerOrigin
                collectionPickerFor = null
                collectionPickerOrigin = null
                // Restore D-pad focus to the originating guide cell on TV;
                // without it Compose's fallback parks focus on the top nav
                // pills (same machinery the Program Info / Record dialogs use).
                if (isTv && origin != null) {
                    dialogFocusOrigin = origin
                    dialogFocusRestoreTick++
                }
            },
        )
    }

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
        // #45: collection pills join the group row -- placement "beginning"
        // renders before All, "end" after the last group (iOS
        // beginningCollections / endCollections). One shared renderer keeps
        // select / manage behavior identical across the TV row and the shared
        // phone pills row.
        val collectionPillItem: @Composable (ChannelCollection) -> Unit = { c ->
            val token = ChannelCollection.token(c.id)
            CollectionPill(
                collection = c,
                selected = state.selectedGroup == token,
                isTv = isTv,
                onSelect = { viewModel.onGroupSelected(token) },
                onSetPlacement = { p -> collectionsVm.setPlacement(c.id, p) },
                onDelete = {
                    // Mirror the hidden-group reset: if we're filtered
                    // to this collection, drop back to All before its
                    // pill vanishes (iOS collectionManageActions).
                    if (state.selectedGroup == token) {
                        viewModel.onGroupSelected(PlaylistViewModel.ALL_GROUPS)
                    }
                    collectionsVm.delete(c.id)
                },
            )
        }
        // Phone/tablet: the Guide adopts the List view's two-row header (user
        // request): a centered "Live TV" title bar with the view-toggle /
        // global-search / channel-search / sort actions on the trailing edge,
        // then the filter + group pills on their own full-width row below.
        // The pills previously shared one row with four leading icon circles,
        // which left them little width on compact screens. TV keeps the
        // single tvOS-style control row (its focus/key-event model lives on
        // that Row). LiveTvTopBar (shared with ChannelListScreen) keeps the
        // title truly screen-centered by shrinking the action cluster on
        // narrow displays like the Fold cover screen.
        if (!isTv) {
            LiveTvTopBar(
                actionCount = if (canToggleViewMode) 4 else 3,
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
                // User report: the Sort by Number / Name / Favorites menu was
                // missing from the Guide. Same menu, same ViewModel state as
                // the List view; the guide's row ordering honors it below.
                com.aeriotv.android.feature.channels.SortMenu(
                    currentMode = state.sortMode,
                    onSelect = viewModel::onSortModeChange,
                    buttonSize = buttonSize,
                    iconSize = iconSize,
                )
            }
            if (searchActive) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = true,
                    placeholder = { Text("Search channels") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (state.searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    } else null,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                )
            }
            // Filter + pills: the SAME shared row the List view renders, so
            // the two views match pixel-for-pixel (user report: the Tune
            // button and pills shifted slightly between views). Gated like
            // the List view: hidden when there is only the All group and no
            // collections.
            if (groups.size > 1 || collections.isNotEmpty()) {
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
        // Control + filter row (TV only), mirroring the tvOS guide:
        // List/Guide switcher, search toggle, and a group on/off filter on
        // the left, then the channel-group pills (or an inline search field)
        // filling the rest. Phone renders the shared LiveTvPillsRow above
        // instead.
        // Control circle sizing. TV values are tvOS-pt x 0.5 (the 960dp canvas
        // is half tvOS's 1920pt, so the same physical size => all the group
        // pills fit across the row exactly like tvOS, no horizontal scroll).
        // controlCircle sizes the search/filter focus ring + active fill (the
        // resting TV button is transparent -- just the glyph), so shrinking it
        // tightens the white focus ring without changing the resting look. 26dp
        // keeps a clean ring around the 16dp glyph while leaving ~9dp clearance
        // in the 44dp row so the ring no longer crowds the pills above / time
        // axis below.
        if (isTv) {
        val controlCircle = 26.dp
        val controlIcon = 16.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The control strip is a tight 44dp on TV to keep the pills / time
                // axis from crowding. A Material OutlinedTextField, however, needs
                // its ~56dp min height or the entered text clips vertically, so let
                // the row grow to 56dp WHILE the inline channel-search field is
                // shown.
                .height(if (!searchActive) 44.dp else 56.dp)
                .padding(horizontal = 20.dp)
                // #10/#14 overshoot fix: pin a HOLD Left to the "All" pill. This
                // handler sits on the common ancestor of BOTH the leading control
                // circles AND the group-pill row, so it keeps seeing Left even
                // after focus lands on All -- swallowing further repeats and
                // re-requesting All until the key is released. A short/step Left
                // (no long-press, repeatCount < threshold) falls through as normal
                // single-pill navigation. Inert on touch (no key events).
                .onPreviewKeyEvent { event ->
                    if (!isTv || event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyUp -> {
                            // Release ends the pin; don't consume the up itself.
                            leftHoldPinningAll = false
                            false
                        }
                        KeyEventType.KeyDown -> {
                            // Remote Control A2: guide leftLong slot. Default =
                            // focusGroupPills (today's All-pill jump + pin).
                            // NONE opts the interception out entirely; other
                            // actions fire once at the threshold and swallow
                            // until release (the pin without the focus glue).
                            val leftLongAction =
                                remoteMap.guideAction(com.aeriotv.android.core.remote.RemoteSlot.LEFT_LONG)
                            when {
                                leftLongAction == com.aeriotv.android.core.remote.GuideRemoteAction.NONE -> false
                                leftHoldPinningAll -> {
                                    if (leftLongAction == com.aeriotv.android.core.remote.GuideRemoteAction.FOCUS_GROUP_PILLS) {
                                        runCatching { allPillFocus.requestFocus() }
                                    }
                                    true
                                }
                                event.nativeKeyEvent.isLongPress ||
                                    event.nativeKeyEvent.repeatCount >= HOLD_LEFT_ALL_PILL_REPEAT -> {
                                    leftHoldPinningAll = true
                                    if (leftLongAction == com.aeriotv.android.core.remote.GuideRemoteAction.FOCUS_GROUP_PILLS) {
                                        runCatching { allPillFocus.requestFocus() }
                                    } else {
                                        guideActionDispatcher.value?.invoke(leftLongAction)
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        else -> false
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // List / Guide view switcher, to the LEFT of Search. TV-only: it
            // uses the same bare-glyph + white-focus-ring circle as the
            // Search / Filter controls (parity with tvOS, which offers a
            // List / Guide switch here); the phone toggle lives in the app
            // bar above.
            if (canToggleViewMode && isTv) {
                val switchesToList = viewMode == LiveTVViewMode.Guide
                val toggleIcon = if (switchesToList)
                    Icons.Filled.ViewList else Icons.Filled.CalendarMonth
                val toggleDesc = if (switchesToList) "Switch to List" else "Switch to Guide"
                run {
                    val toggleInteraction = remember { MutableInteractionSource() }
                    val toggleFocused by toggleInteraction.collectIsFocusedAsState()
                    Box(
                        modifier = Modifier.clickable(
                            interactionSource = toggleInteraction,
                            indication = null,
                        ) { onToggleViewMode() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(controlCircle)
                                .clip(CircleShape)
                                .background(
                                    if (toggleFocused) Color.White.copy(alpha = 0.15f)
                                    else Color.Transparent,
                                )
                                .then(
                                    if (toggleFocused)
                                        Modifier.border(2.dp, Color.White, CircleShape)
                                    else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = toggleIcon,
                                contentDescription = toggleDesc,
                                modifier = Modifier.size(controlIcon),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
            // Search toggle: reveals an inline channel-name search field. On TV
            // it has NO resting fill or ring -- just the bare glyph -- and only
            // shows the SAME 2dp white ring as a focused program cell WHEN it is
            // focused (active = search open fills it accent regardless).
            // TV-only: the phone search toggle lives in the app bar above.
            if (isTv) {
            val searchInteraction = remember { MutableInteractionSource() }
            val searchFocused by searchInteraction.collectIsFocusedAsState()
            val searchContainer = when {
                searchActive -> MaterialTheme.colorScheme.primary
                isTv && searchFocused -> Color.White.copy(alpha = 0.15f)
                isTv -> Color.Transparent
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
            // Hand-rolled circle, NOT FilledTonalIconButton: Material's icon button
            // enforces a 48dp interactive minimum, so a .border() on it landed on
            // that inflated node and the white focus ring rendered far larger than
            // controlCircle (a visible gap to the fill) and bled into the rows
            // above/below. Here the ring + fill are an inner Box sized EXACTLY to
            // controlCircle; the outer clickable + phone-only padding keeps a
            // comfortable tap target without inflating the ring.
            Box(
                modifier = Modifier
                    .clickable(
                        interactionSource = searchInteraction,
                        indication = null,
                    ) {
                        searchActive = !searchActive
                        if (!searchActive) viewModel.onSearchQueryChange("")
                    }
                    .padding(if (isTv) 0.dp else 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(controlCircle)
                        .clip(CircleShape)
                        .background(searchContainer)
                        .then(
                            if (isTv && searchFocused)
                                Modifier.border(2.dp, Color.White, CircleShape)
                            else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search channels",
                        modifier = Modifier.size(controlIcon),
                        tint = if (searchActive) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Global Search (parity #41): opens the full Search screen (movies /
            // shows / EPG programmes). Distinct from the channel-name filter to
            // its left -- a globe-magnifier icon so it doesn't read as a
            // duplicate search box. TV-only: the phone globe lives in the app
            // bar above.
            val globalSearchInteraction = remember { MutableInteractionSource() }
            val globalSearchFocused by globalSearchInteraction.collectIsFocusedAsState()
            Box(
                modifier = Modifier
                    .clickable(interactionSource = globalSearchInteraction, indication = null) { onOpenSearch() }
                    .padding(if (isTv) 0.dp else 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(controlCircle)
                        .clip(CircleShape)
                        .background(
                            when {
                                isTv && globalSearchFocused -> Color.White.copy(alpha = 0.15f)
                                isTv -> Color.Transparent
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                        )
                        .then(
                            if (isTv && globalSearchFocused) Modifier.border(2.dp, Color.White, CircleShape)
                            else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.TravelExplore,
                        contentDescription = "Search",
                        modifier = Modifier.size(controlIcon),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Gap between the two control circles.
            Spacer(Modifier.width(8.dp))
            }
            // Filter toggle: opens the group on/off picker (Manage Groups).
            // TV: same treatment as Search, no resting fill/ring, white focus
            // ring only when focused; active (some groups hidden) fills accent.
            // Phone: the List view's 36dp Tune circle + hidden-groups warning
            // dot, so both Live TV views lead their pill row identically.
            val filterActive = hiddenGroups.isNotEmpty()
            if (isTv) {
            val filterInteraction = remember { MutableInteractionSource() }
            val filterFocused by filterInteraction.collectIsFocusedAsState()
            val filterContainer = when {
                filterActive -> MaterialTheme.colorScheme.primary
                filterFocused -> Color.White.copy(alpha = 0.15f)
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .clickable(
                        interactionSource = filterInteraction,
                        indication = null,
                    ) { showManageGroups = true },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(controlCircle)
                        .clip(CircleShape)
                        .background(filterContainer)
                        .then(
                            if (filterFocused)
                                Modifier.border(2.dp, Color.White, CircleShape)
                            else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = "Filter groups",
                        modifier = Modifier.size(controlIcon),
                        tint = if (filterActive) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            }
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
            } else if (groups.size > 1 || collections.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .weight(1f)
                        // The HOLD-Left-to-"All" gesture (and its overshoot pin)
                        // now lives on the parent control Row's onPreviewKeyEvent
                        // so it keeps working after focus lands on the All pill.
                        .fillMaxHeight()
                        // Streamer field report 2026-07-12: DOWN from the top nav
                        // used to land on whichever pill sat geometrically under
                        // the Live TV tab ("General"). Route a DOWN entry to the
                        // SELECTED pill instead (tvOS model). Guarded by the
                        // attach counter: if the selected pill is scrolled out of
                        // the LazyRow's composition its requester is unbound, and
                        // the default geometric landing is better than a throw.
                        .focusProperties {
                            enter = { direction ->
                                if (direction == FocusDirection.Down &&
                                    selectedPillAttachCount.intValue > 0
                                ) selectedPillFocus
                                else FocusRequester.Default
                            }
                        },
                    contentPadding = PaddingValues(end = if (isTv) 20.dp else 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (isTv) 5.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(
                        collections.filter { it.placement == ChannelCollection.PLACEMENT_BEGINNING },
                        key = { "coll_${it.id}" },
                    ) { c -> collectionPillItem(c) }
                    items(groups, key = { "grp_$it" }) { group ->
                        val pillSelected = state.selectedGroup == group
                        val pillInteraction = remember { MutableInteractionSource() }
                        val pillFocused by pillInteraction.collectIsFocusedAsState()
                        if (isTv && pillSelected) {
                            // Marks the selected pill as composed so the row's
                            // focusProperties.enter DOWN-redirect knows the
                            // requester below is safely bound.
                            androidx.compose.runtime.DisposableEffect(Unit) {
                                selectedPillAttachCount.intValue++
                                onDispose { selectedPillAttachCount.intValue-- }
                            }
                        }
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
                                    // #10: the "All" pill is the hold-Left focus
                                    // target (tvOS parity). Only it gets the
                                    // requester; the other pills stay untouched.
                                    .then(
                                        if (group == PlaylistViewModel.ALL_GROUPS)
                                            Modifier.focusRequester(allPillFocus)
                                        else Modifier,
                                    )
                                    // Selected pill carries the DOWN-entry target
                                    // (see focusProperties.enter on the row).
                                    .then(
                                        if (pillSelected)
                                            Modifier.focusRequester(selectedPillFocus)
                                        else Modifier,
                                    )
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
                        }
                    }
                    items(
                        collections.filter { it.placement != ChannelCollection.PLACEMENT_BEGINNING },
                        key = { "coll_${it.id}" },
                    ) { c -> collectionPillItem(c) }
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
                    .horizontalScroll(horizontalScrollState, flingBehavior = guideFling)
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
        // on (lastWatched, channels-loaded). Only ids written by THIS
        // session's playback scroll the guide - the id persisted by a
        // PREVIOUS session is consumed silently on cold launch (user report
        // 2026-07-12, Streamer: guide opened scrolled to a days-old channel
        // with nothing playing). If the user is currently filtering and the
        // last-watched channel isn't in the filtered list, indexOfFirst
        // returns -1 and we no-op - the visible filter wins.
        val listState = rememberLazyListState()
        val lastWatchedId by settingsVm.lastWatchedChannelId
            .collectAsStateWithLifecycle(initialValue = "")
        // Task #136: consume each lastWatched id ONCE. Without this, ANY
        // recomposition of the guide after a nav-return (e.g. backing out of a
        // catch-up / recording playback that never touched lastWatched)
        // re-fires the effect with the same stale id and yanks the list away
        // from where the user was browsing. rememberSaveable survives the
        // guide leaving composition while the MAIN nav entry stays on the
        // stack, which is exactly the return-from-player case.
        var consumedLastWatchedId by rememberSaveable { mutableStateOf("") }
        LaunchedEffect(lastWatchedId, filteredChannels.isNotEmpty()) {
            if (lastWatchedId.isBlank() || filteredChannels.isEmpty()) return@LaunchedEffect
            if (lastWatchedId == consumedLastWatchedId) return@LaunchedEffect
            // Fresh process: the first non-blank id is the one persisted by a
            // previous session. Consume it WITHOUT scrolling so a cold launch
            // always opens at the top of the guide.
            if (consumedLastWatchedId.isEmpty()) {
                consumedLastWatchedId = lastWatchedId
                return@LaunchedEffect
            }
            // On TV, when the mini-player is showing (we just backed out of the
            // fullscreen player), the focus-on-return effect below -- keyed on the
            // mini-player session -- owns BOTH the scroll and the D-pad focus.
            // Skip here so the two effects don't fight over listState.
            if (miniActive && isTv) return@LaunchedEffect
            consumedLastWatchedId = lastWatchedId
            val idx = filteredChannels.indexOfFirst { it.id == lastWatchedId }
            if (idx >= 0) {
                listState.animateScrollToItem(idx)
            }
        }

        // Two-step Back behaviour on the Live TV guide:
        //  1) Anywhere below the top row -> scroll the guide back to channel 0
        //     and keep the user in the app. Mirrors how the iOS / tvOS Guide
        //     scrolls Home-to-top on Menu / Back rather than dismissing.
        //  2) Already at the top -> finish the activity (exit to the launcher),
        //     matching tvOS where Menu at the top channel falls through to the
        //     Apple TV Home. No in-app confirmation dialog (#10).
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
        // Task #138: keep guideNav's viewport clamp current so a freshly-
        // focused left-clipped cell anchors to the on-screen column, not its
        // (possibly hours-past, with catch-up history) programme start.
        // Re-key on the SAME inputs as `visibleWindow`'s remember. Without
        // windowStart here, this effect captured the FIRST derivedStateOf and kept
        // reading it after windowStart leaped (cold-launch historyMs 1h -> full
        // catch-up retention as the EPG finishes loading). viewportStartMs then
        // froze days away from the real visible column, and onCellFocused's
        // `coerceAtLeast(viewportStartMs)` dragged the nav anchor past every
        // composed cell -- so D-pad Right/Left dead-ended (focusAdjacent found no
        // next span). Restarting on windowStart / hourWidthPx / stripViewportPx
        // rebinds to the current window so the anchor tracks the focused cell.
        LaunchedEffect(guideNav, windowStart, hourWidthPx, stripViewportPx) {
            // Task #185 ROOT CAUSE of "guide gets away": the nav anchor must
            // track the TRUE on-screen edge, not the padded compose window
            // (feeding the padded start let vertical moves land on cells
            // wholly left of the viewport: invisible focus ring + timeline
            // dragged backward; Streamer 50-down field trace 2026-07-17).
            //
            // Task #188 REGRESSION FIX (Logan, ch 111/113 focus loss): after
            // visibleWindow moved to 30-min buckets for recomposition cost,
            // deriving the anchor from it quantized the anchor up to 30 min
            // into the past -- rapid vertical bursts landed focus on a cell
            // that ENDED before the visible edge, and the reveal dragged the
            // timeline into the past (same symptom class #185 fixed). The
            // nav anchor now computes the UNSNAPPED edge straight from the
            // scroll position: full precision for navigation, while the
            // render window keeps its coarse buckets for the perf win.
            snapshotFlow {
                val scrollPx = horizontalScrollState.value.toFloat()
                windowStart + (scrollPx / hourWidthPx * 3_600_000f).toLong()
            }
                .distinctUntilChanged()
                .collect { guideNav.viewportStartMs = it }
        }
        val navScope = rememberCoroutineScope()
        val backScope = androidx.compose.runtime.rememberCoroutineScope()
        val activity = LocalContext.current.findActivity()

        // Remote Control A2: guide-context action executor. Wrappers over
        // the SAME plumbing the Back ladder / hold gestures use, so a
        // remapped button behaves identically to the built-in path.
        // Returns false for actions the guide cannot run here (caller
        // falls through). PROGRAM_INFO / OPEN_SEARCH land with the
        // channel-list overlay pass.
        val dispatchGuideAction: (com.aeriotv.android.core.remote.GuideRemoteAction) -> Boolean = act@{ action ->
            when (action) {
                com.aeriotv.android.core.remote.GuideRemoteAction.JUMP_TO_NOW -> {
                    val nowOffsetPx =
                        ((System.currentTimeMillis() - windowStart).toFloat() / 3_600_000f) * hourWidthPx
                    val anchorPx = (nowOffsetPx - stripViewportPx * 0.20f).toInt()
                        .coerceIn(0, horizontalScrollState.maxValue)
                    backScope.launch {
                        horizontalScrollState.animateScrollTo(anchorPx)
                        autoAnchorPx = anchorPx
                        if (isTv) {
                            guideNav.focusChannelAtNow(
                                guideNav.lastFocusedChannelIndex.coerceAtLeast(0),
                                nowMillis,
                                listState,
                            )
                        }
                    }
                    true
                }
                com.aeriotv.android.core.remote.GuideRemoteAction.JUMP_TO_TOP -> {
                    backScope.launch {
                        listState.animateScrollToItem(0)
                        if (isTv) guideNav.focusChannelAtNow(0, nowMillis, listState)
                    }
                    true
                }
                com.aeriotv.android.core.remote.GuideRemoteAction.FOCUS_GROUP_PILLS -> {
                    runCatching { allPillFocus.requestFocus() }.isSuccess
                }
                com.aeriotv.android.core.remote.GuideRemoteAction.TIMELINE_BACK,
                com.aeriotv.android.core.remote.GuideRemoteAction.TIMELINE_FORWARD,
                -> {
                    // Logan 2026-07-20: hold-Left default. Pages the shared
                    // timeline ~0.85 viewport (same stride as the oversized-
                    // cell paging) into the past for catch-up browsing, or
                    // forward. Back-to-now stays one Back press away (the
                    // ladder's snap branch).
                    val dir = if (action == com.aeriotv.android.core.remote.GuideRemoteAction.TIMELINE_BACK) -1 else +1
                    val page = (stripViewportPx * 0.85f).toInt()
                    val target = (horizontalScrollState.value + dir * page)
                        .coerceIn(0, horizontalScrollState.maxValue)
                    if (target != horizontalScrollState.value) {
                        backScope.launch { horizontalScrollState.animateScrollTo(target) }
                    }
                    true
                }
                com.aeriotv.android.core.remote.GuideRemoteAction.PAGE_UP -> {
                    navScope.launch { guideNav.pageVertical(-1, filteredChannels.lastIndex, listState) }
                    true
                }
                com.aeriotv.android.core.remote.GuideRemoteAction.PAGE_DOWN -> {
                    navScope.launch { guideNav.pageVertical(+1, filteredChannels.lastIndex, listState) }
                    true
                }
                com.aeriotv.android.core.remote.GuideRemoteAction.RESUME_PLAYER -> {
                    if (miniActive) miniPlayerVm.session.requestResume()
                    true
                }
                com.aeriotv.android.core.remote.GuideRemoteAction.NONE -> true
                else -> false
            }
        }
        androidx.compose.runtime.SideEffect { guideActionDispatcher.value = dispatchGuideAction }

        androidx.activity.compose.BackHandler(enabled = !miniActive) {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
            // Task #185 (user request): after browsing hours into the future or
            // past there was NO way back to "now" short of stepping cell-by-cell
            // (hold-LEFT abandons the grid for the All pill entirely). Extend
            // the Back ladder: timeline-away-from-now -> snap the timeline back
            // to the left-aligned now anchor and refocus the CURRENT row's now
            // cell; then (next press) scroll to the top channel; then exit.
            // The anchor is recomputed fresh (autoAnchorPx goes stale as wall
            // clock advances); half an hourWidth of drift still counts as "at
            // now" so Back doesn't burn a press micro-correcting.
            val nowOffsetPx =
                ((System.currentTimeMillis() - windowStart).toFloat() / 3_600_000f) * hourWidthPx
            val nowAnchorPx = (nowOffsetPx - stripViewportPx * 0.20f).toInt()
                .coerceIn(0, horizontalScrollState.maxValue)
            val awayFromNow =
                kotlin.math.abs(horizontalScrollState.value - nowAnchorPx) > hourWidthPx.toInt() / 2
            if (awayFromNow) {
                backScope.launch {
                    horizontalScrollState.animateScrollTo(nowAnchorPx)
                    autoAnchorPx = nowAnchorPx
                    // Refocus the row the user was on at its now cell. The
                    // horizontal-reveal window is deliberately NOT opened, so
                    // the landing focus cannot nudge the timeline we just
                    // placed (matches the vertical-move no-scroll model).
                    if (isTv) {
                        guideNav.focusChannelAtNow(
                            guideNav.lastFocusedChannelIndex.coerceAtLeast(0),
                            nowMillis,
                            listState,
                        )
                    }
                }
            } else if (!atTop) {
                backScope.launch {
                    listState.animateScrollToItem(0)
                    // Land focus on the top channel's NOW cell, not the "All"
                    // group pill (user-reported: Back scrolled to top but left
                    // focus on the pill row).
                    if (isTv) guideNav.focusChannelAtNow(0, nowMillis, listState)
                }
            } else {
                // #10 tvOS parity: at the top channel Menu/Back exits to the
                // launcher (Apple TV Home has no confirm), so finish straight
                // away -- the scroll-to-top above is the soft first step.
                activity?.finish()
            }
        }
        // tvOS parity: DOUBLE-Back on the corner mini jumps the guide to the
        // top channel (the mini keeps playing). Same scroll + focus as the
        // Back-at-not-top branch above.
        LaunchedEffect(Unit) {
            miniPlayerVm.session.guideTopRequests.collect {
                listState.animateScrollToItem(0)
                if (isTv) guideNav.focusChannelAtNow(0, nowMillis, listState)
            }
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
            // GH #15: focus restore after PlayerScreen's hidden-window self-pop
            // (HOME -> resume). MainActivity.onStop dismissed the mini, so the
            // miniState effect above bails and nothing re-focuses the guide --
            // and Chromecast/Google TV drops Compose focus across the
            // stop/restart, leaving hold-Left (and the rest of the D-pad focus
            // path) dead. Consume the one-shot request PlayerScreen set right
            // before popping and re-land focus on the watched channel's NOW
            // cell; if the filter hides it, the All pill (inside the control
            // row's hold-Left handler subtree) is the fallback. Keyed on
            // channels-loaded so a slow cold list doesn't strand the request;
            // consumeGuideFocusRestore() is one-shot + age-capped, so normal
            // guide entries and tab switches never steal focus here.
            LaunchedEffect(filteredChannels.isNotEmpty()) {
                if (filteredChannels.isEmpty()) return@LaunchedEffect
                if (!miniPlayerVm.session.consumeGuideFocusRestore()) return@LaunchedEffect
                // Mini somehow Active again: the miniState effect above owns
                // focus; the request is already consumed, just stand down.
                if (miniState is MiniPlayerSession.State.Active) return@LaunchedEffect
                val idx = filteredChannels.indexOfFirst { it.id == lastWatchedId }
                if (idx >= 0) {
                    guideNav.focusChannelAtNow(idx, nowMillis, listState)
                } else {
                    runCatching { allPillFocus.requestFocus() }
                }
            }
        }
        // EPG-search guide jump (iOS commit e3ccf439d consumePendingGuideJump).
        // Warm path: a Search EPG result was tapped in-process; the VM emits a
        // GuideProgram(channelId=guideMatchKey, startMillis). Resolve the row in
        // filteredChannels by guideMatchKey (the same 3-way-matched key the grid
        // and epgByChannel use), then reuse focusChannelAt to scroll + (on TV)
        // focus the cell at the programme's start column. Idempotent: a key that
        // isn't in the current filtered list (e.g. its group is still hidden
        // mid-load) simply no-ops; selectedGroup was already reset to All by
        // requestGuideJump, so once channels settle the row resolves. NON-TV
        // gated so the scroll runs on every form factor; D-pad focus only on TV.
        LaunchedEffect(Unit) {
            viewModel.guideJumpRequests.collect { jump ->
                // Re-read live state at consume time (channels may have just
                // loaded). filteredChannels is a Compose State read inside the
                // suspending collect, so it re-reads on each emission.
                val idx = filteredChannels.indexOfFirst { it.guideMatchKey == jump.channelId }
                if (idx < 0) return@collect
                // Let the guide's own scroll-to-now / focus-on-return settle
                // first (iOS sleeps 250ms before scrolling).
                kotlinx.coroutines.delay(250L)
                if (isTv) {
                    guideNav.focusChannelAt(idx, jump.startMillis, listState)
                } else {
                    listState.animateScrollToItem(idx)
                }
            }
        }
        // Pull-to-refresh parity with the List view (user report: it only
        // worked in List view). Same contract as ChannelListScreen: the
        // spinner tracks state.isLoading and refreshPlaylist() re-fetches the
        // channel list, then chains the EPG refresh. TV renders the grid bare:
        // there is no pull gesture on D-pad, and the indicator would hang
        // mid-screen while a background refresh runs.
        // The vertical axis gets an EXPLICIT minimal-scroll spec. History:
        // GuideLeadingEdgeBringIntoViewSpec used to wrap this LazyColumn, but
        // LocalBringIntoViewSpec applies to EVERY axis of every scrollable
        // beneath it, so the spec's vertical-move x-pin also ran against the
        // VERTICAL axis and glued the focused row to the TOP of the guide
        // from the first D-pad DOWN (user report: "scrolling is stuck to the
        // top row"). That spec now wraps each row's horizontal programme
        // strip only (see ChannelGuideRow). But simply falling back to the
        // default is not enough either: on television uiMode Compose's
        // DEFAULT spec pivots the focused item to ~1/3 of the viewport, so
        // focus parked three rows from the top and the list scrolled under
        // it. The minimal spec below gives the classic guide model the user
        // asked for: focus WALKS down the screen to the bottom row first,
        // and only then does the guide scroll, a row per press, from there.
        val guideGrid: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides GuideVerticalMinimalBringIntoViewSpec,
        ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                    // Remote Control A2: extended keys in the guide (FF/RW,
                    // Ch+/Ch-, Play/Pause on remotes that have them) route
                    // through the guide-context map. Non-repeat only so a held
                    // key doesn't machine-gun page turns.
                    if (isTv && event.nativeKeyEvent.repeatCount == 0) {
                        val mediaSlot = when (event.key) {
                            Key.MediaFastForward -> com.aeriotv.android.core.remote.RemoteSlot.FFWD
                            Key.MediaRewind -> com.aeriotv.android.core.remote.RemoteSlot.REWIND
                            Key.ChannelUp -> com.aeriotv.android.core.remote.RemoteSlot.CHANNEL_UP
                            Key.ChannelDown -> com.aeriotv.android.core.remote.RemoteSlot.CHANNEL_DOWN
                            Key.MediaPlayPause, Key.MediaPlay -> com.aeriotv.android.core.remote.RemoteSlot.PLAY_PAUSE
                            else -> null
                        }
                        if (mediaSlot != null) {
                            val action = remoteMap.guideAction(mediaSlot)
                            if (action != com.aeriotv.android.core.remote.GuideRemoteAction.NONE &&
                                dispatchGuideAction(action)
                            ) return@onPreviewKeyEvent true
                        }
                    }
                    // LEFT/RIGHT: the user IS navigating the timeline, so release
                    // the shared scroll lock. Then, if the FOCUSED program is wider
                    // than the viewport and still has content off-screen in the
                    // press direction, PAGE the timeline ~one screen WITHIN the
                    // program (keeping it focused) instead of letting focus leap a
                    // full long block (e.g. a 6-hour "season is over" cell) to the
                    // next program in one press. Once the program's edge is on
                    // screen, fall through so focus moves to the adjacent cell as
                    // usual. Normal-length programs always fall straight through.
                    // #10 tvOS parity: HOLD Left ~0.5s -> jump focus to the
                    // "All" group pill. The framework flags FLAG_LONG_PRESS on
                    // the auto-repeat event at the long-press timeout (~500ms,
                    // matching tvOS's 0.5s recognizer); repeatCount >= 4 is the
                    // same threshold as a belt-and-suspenders fallback. A tap
                    // sends neither (repeatCount 0, no long-press flag), so this
                    // never fires on the short-Left timeline scroll. Firing moves
                    // focus out of the grid, so it only fires once per hold.
                    if (event.key == Key.DirectionLeft &&
                        (event.nativeKeyEvent.isLongPress ||
                            event.nativeKeyEvent.repeatCount >= HOLD_LEFT_ALL_PILL_REPEAT)
                    ) {
                        runCatching { allPillFocus.requestFocus() }
                        return@onPreviewKeyEvent true
                    }
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
                                navScope.launch {
                                    // Task #188: short page animation (layout-based scroll,
                                    // see GuideLeadingEdgeBringIntoViewSpec.scrollAnimationSpec).
                                    horizontalScrollState.animateScrollTo(
                                        target,
                                        androidx.compose.animation.core.tween(durationMillis = 100),
                                    )
                                }
                                return@onPreviewKeyEvent true
                            }
                            if (!right && cellStartPx < scroll - epsilon && scroll > 0) {
                                val target = (scroll - page).toInt().coerceIn(0, maxScroll)
                                navScope.launch {
                                    // Task #188: short page animation (layout-based scroll,
                                    // see GuideLeadingEdgeBringIntoViewSpec.scrollAnimationSpec).
                                    horizontalScrollState.animateScrollTo(
                                        target,
                                        androidx.compose.animation.core.tween(durationMillis = 100),
                                    )
                                }
                                return@onPreviewKeyEvent true
                            }
                        }
                        // Focus is not on a grid cell. Two distinct cases:
                        // (a) ORPHANED mid-browse (a cell disposed out from under
                        //     focus): lastFocusedChannelIndex still knows the row.
                        //     Task #185: re-assert focus there at the current
                        //     anchor and CONSUME -- falling through to the default
                        //     2D search from an orphan is the path that focused an
                        //     off-screen past cell and dragged the whole timeline
                        //     backward (Streamer field trace 2026-07-17).
                        // (b) genuine fresh entry from the chrome (lastFocused is
                        //     -1 too): let the default search drive the descent.
                        if (guideNav.focusedChannelIndex < 0) {
                            val recoverRow = guideNav.lastFocusedChannelIndex
                            if (recoverRow >= 0 && !guideNav.verticalMoveInFlight) {
                                navScope.launch {
                                    guideNav.moveFocusToChannel(recoverRow, listState)
                                }
                                return@onPreviewKeyEvent true
                            }
                            return@onPreviewKeyEvent false
                        }
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
                    // while repeats step on a rate that ACCELERATES with hold
                    // duration (GH #29): ~8/s for the first second, ~16/s to 2.5s,
                    // then ~25/s, so big channel lists are traversable without
                    // giving up the stop-on-target control of the initial rate.
                    // Hold duration comes free from the native event's downTime.
                    // (Confirmed via logcat: one event = exactly one channel, so the
                    // over-scroll was the unthrottled auto-repeat, not a cascade.)
                    val nowMs = android.os.SystemClock.uptimeMillis()
                    val heldMs = event.nativeKeyEvent.eventTime - event.nativeKeyEvent.downTime
                    val holdInterval = when {
                        heldMs < 1_000L -> HOLD_SCROLL_MIN_INTERVAL_MS
                        heldMs < 2_500L -> HOLD_SCROLL_ACCEL_INTERVAL_MS
                        else -> HOLD_SCROLL_TURBO_INTERVAL_MS
                    }
                    if (event.nativeKeyEvent.repeatCount != 0 &&
                        nowMs - guideNav.lastVerticalMoveAtMs < holdInterval
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
                    // the target row, composing it first if it scrolled off. Capture
                    // where the outgoing cell's leading edge currently sits on screen
                    // (content px - scroll, clamped to the viewport) so the landing
                    // cell left-aligns to the SAME x: the highlight's left edge stays
                    // put and the shared timeline slides under it instead of the
                    // highlight jumping to each program's true start (the "stable
                    // highlight" model). Null when no cell is focused (fresh entry),
                    // which keeps the default bring-into-view.
                    guideNav.lastVerticalMoveAtMs = nowMs
                    val outgoingStartPx = guideNav.focusedCellStartPx
                    val leadingEdgeTargetPx = if (outgoingStartPx >= 0)
                        (outgoingStartPx - horizontalScrollState.value).toFloat().coerceAtLeast(0f)
                    else null
                    guideNav.beginVerticalMove(leadingEdgeTargetPx)
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
            // Key by url, not the non-unique "m3u:<tvg-id>" id (GH #31 crash fix).
            itemsIndexed(items = filteredChannels, key = { _, ch -> ch.url }) { channelIndex, channel ->
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
                    textScale = tvComfortScale,
                    horizontalScrollState = horizontalScrollState,
                    guideFling = guideFling,
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
                    showNumber = showChannelNumbers,
                    collectionsMenu = collectionsMenu,
                    onProgrammeWatch = if (channel.hasCatchup) {
                        { prog -> onCatchupResolve(channel, prog) }
                    } else {
                        null
                    },
                )
                HorizontalDivider(color = guideRowDivider, thickness = guideRowDividerThickness)
            }
        }
        // Single continuous now-line across the whole grid (2026-07-12,
        // Apple-parity request): the old per-row copy restarted inside every
        // row, so the row dividers sliced it into dashes. Drawn ONCE over the
        // LazyColumn in screen space, tracking the shared timeline scroll in
        // the placement phase (no recomposition while panning); clipped to
        // the programme-strip area so it never paints over the channel rail.
        // A plain Box with no pointer/focus modifiers: touch passes through
        // and D-pad focus never sees it.
        if (nowMillis in (windowStart + 1)..(windowStart + windowDurationMs - 1)) {
            Box(
                modifier = Modifier
                    .padding(start = railWidth)
                    .fillMaxSize()
                    .clipToBounds(),
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            val nowPx =
                                ((nowMillis - windowStart).toFloat() / 3_600_000f) * hourWidthPx
                            androidx.compose.ui.unit.IntOffset(
                                (nowPx - horizontalScrollState.value).toInt(),
                                0,
                            )
                        }
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(
                            if (isTv) Color(0xFFFF4757)
                            else MaterialTheme.colorScheme.primary,
                        ),
                )
            }
        }
        }
        }
        }
        if (isTv) {
            guideGrid()
        } else {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { viewModel.refreshPlaylist() },
                modifier = Modifier.fillMaxSize(),
            ) { guideGrid() }
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
    /** GH #25: multiplies the row's TEXT (sp only, via a fontScale override)
     *  so the TV comfort scale grows rail + cell type along with the row
     *  metrics. 1f on phone. */
    textScale: Float = 1f,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
    guideFling: FlingBehavior,
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
    /** GH #19: when false the channel-number text is omitted from the rail. */
    showNumber: Boolean = true,
    collectionsMenu: CollectionsMenuContext? = null,
    /** Catch-up (task #133): non-null when this channel has a replayable
     *  archive; invoked with a PAST programme the user chose to watch. Each
     *  cell additionally gates on its own programme falling inside the
     *  retention window (see [canReplay]). */
    onProgrammeWatch: ((EPGProgramme) -> Unit)? = null,
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

    // Whether THIS row currently owns D-pad focus (used by the viewport-clip
    // exception in the strip below). guideNav.lastFocusedChannelIndex is a
    // mutableStateOf; reading it DIRECTLY in the per-cell loop subscribed every
    // composed row to the raw index, so a single channel up/down recomposed
    // EVERY visible row (each re-running its forEachIndexed: category tint, live
    // progress, msToDp...). Wrapping the comparison in a structural-equality
    // derivedStateOf scopes each row's subscription to a Boolean, so only the
    // two rows whose focused-ness actually flips recompose on a vertical move.
    val rowIsFocused by remember(channelIndex) {
        derivedStateOf { channelIndex == guideNav.lastFocusedChannelIndex }
    }

    // Compact rail sizing. On TV we keep it tight (narrow rail, small logo) so
    // more channels fit; legibility comes from the name/cell text, not bulk.
    // The number column is TV-only: the phone cell stacks its number UNDER the
    // name (iOS channelLabel) so it needs no reserved width. Channel numbers
    // run up to 5 digits on large IPTV playlists; size the TV column for
    // "12345" at the rail's type scale so nothing clips (24dp/28dp rendered
    // "5200" as "520", user reports).
    val numberStyle = MaterialTheme.typography.labelMedium
    // Rail name is labelSmall on both form factors: ~9.9sp at the TV 0.9 type
    // scale (per user request, frees rail width for long names) and 11sp on
    // phone, matching the iOS channelLabel 10pt name.
    val nameStyle = MaterialTheme.typography.labelSmall
    // GH #25: grow TEXT with the TV comfort scale. fontScale multiplies sp
    // only, so every Text inside the row (rail name/number + programme cell
    // title/description/time) scales while the dp/px layout math -- viewport
    // clip, focus spans, scroll offsets -- is untouched.
    val rowBaseDensity = LocalDensity.current
    val rowDensity = if (textScale == 1f) rowBaseDensity else androidx.compose.ui.unit.Density(
        rowBaseDensity.density,
        rowBaseDensity.fontScale * textScale,
    )
    CompositionLocalProvider(LocalDensity provides rowDensity) {
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
                // GH #19: number column collapses entirely when numbers are
                // off, handing its width to the logo + name column.
                if (showNumber) {
                    channel.channelNumber?.let { num ->
                        Text(
                            text = num.toString(),
                            style = numberStyle,
                            // iOS parity: rail numbers on the dim textTertiary
                            // rung (EPGGuideView channelLabel .textTertiary).
                            color = MaterialTheme.colorScheme.tertiary,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            // tvOS parity (rail-match pass): .frame(minWidth: 38,
                            // alignment: .trailing) + fixedSize -> a 19dp MINIMUM
                            // that grows for 4-5 digit numbers. The old fixed
                            // 40dp reserved double tvOS's width and squeezed the
                            // name column into truncating ("NBC Sports ...").
                            modifier = Modifier.widthIn(min = 19.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // iOS Issue #28: omit the channel logo when "Show Channel
                    // Logos" is off so the name takes the full space.
                    if (showLogo) {
                    // Landscape logo, tvOS 72x48pt proportional on the 540dp
                    // canvas -> 36x24dp (matching the channel column's tvOS
                    // ratios). tvOS parity (rail-match pass): a REAL logo
                    // floats directly on the rail background like tvOS's
                    // CachedLogoImage -- no rounded box, no backing fill, no
                    // inner padding (the dark chip behind every logo was an
                    // Android-only invention). The boxed treatment survives
                    // ONLY as the no-logo placeholder, mirroring tvOS's
                    // guidePlaceholder rounded rect.
                    if (channel.tvgLogo.isNotBlank()) {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        // Phase 174: decode at 128x128 px so the downsample to
                        // display size happens via GPU bilinear rather than
                        // CPU box-filter. Sharper text/glyphs in logos.
                        AsyncImage(
                            model = coil3.request.ImageRequest.Builder(ctx)
                                .data(channel.tvgLogo)
                                .size(128, 128)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.size(width = 36.dp, height = 24.dp),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
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
                // iOS EPGGuideView channelLabel (EPGGuideView.swift:3459): a
                // centered stack of logo (40x28) over a single-line name over
                // the channel number, which is how iOS holds the rail at 100pt
                // on iPhone AND iPad. The old side-by-side number+logo+name row
                // needed the 168dp rail that ate a quarter of an unfolded-Fold
                // guide (user report) and left long names ~20dp on the cover
                // screen.
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // "Show Channel Logos" off hides the logo so the name takes
                    // the full space. (Android extension: the iOS GUIDE ignores
                    // that toggle and gates on logoURL presence only; the
                    // Android guide honored the toggle before this restyle, so
                    // keep it.)
                    if (showLogo) {
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 28.dp)
                            .clip(RoundedCornerShape(4.dp))
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
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp),
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
                    Spacer(Modifier.height(3.dp))
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
                    // GH #19: the number line collapses when the toggle is off.
                    if (showNumber) {
                        channel.channelNumber?.let { num ->
                            Text(
                                text = num.toString(),
                                // iOS channelLabel number: 8pt bold textTertiary
                                // under the name. lineHeight override because the
                                // style's 16sp line box would float the number
                                // ~6dp below the name (iOS hugs it, spacing 1)
                                // and could clip the stack out of the 80dp row
                                // at large accessibility font scales.
                                style = numberStyle,
                                fontSize = 9.sp,
                                lineHeight = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                            )
                        }
                    }
                }
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
            // iOS #35: guide-rail favorite star, top-trailing on the rail cell.
            // Phone/tablet: ALWAYS visible and tap-toggles -- subtle outline when
            // off, gold when on (iOS: star / star.fill, statusWarning #FFA502).
            // The old display-only star + hidden long-press was the real cause
            // of the recurring "can't add favorites" reports, so the visible
            // affordance IS the feature. Its own clickable sits on top of the
            // rail's combinedClickable, so a star tap never also plays the
            // channel. TV mirrors tvOS: display-only gold star on favorited
            // rows (the rail is deliberately not a D-pad target; the toggle
            // lives in the rail long-press menu).
            if (isTv) {
                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFA502),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 3.dp, end = 5.dp)
                            .size(10.dp),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onToggleFavorite() }
                        .padding(6.dp),
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (isFavorite) {
                            "Remove ${channel.name} from Favorites"
                        } else {
                            "Add ${channel.name} to Favorites"
                        },
                        tint = if (isFavorite) Color(0xFFFFA502)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }

        visibleCellSpans.clear()

        // GH #5 / stable-highlight: the custom bring-into-view spec (leading
        // edge anchoring + the vertical-move x-pin) must ONLY govern this
        // HORIZONTAL strip. It used to be provided around the whole guide
        // LazyColumn, where its vertical-move branch also hijacked the
        // VERTICAL axis and glued the focused row to the top of the guide
        // (user report: scrolling stuck to the top row).
        val stripBringIntoViewSpec = remember(guideNav) {
            GuideLeadingEdgeBringIntoViewSpec(
                verticalMoveLeadingEdgeTargetPx = { guideNav.leadingEdgeTargetPx },
                // Reveal window: fresh for ~800ms after the last explicit
                // LEFT/RIGHT press (auto-repeat keeps refreshing it). Long
                // enough that a legitimate reveal animation finishes; every
                // focus event outside it leaves the timeline untouched.
                horizontalNavActive = {
                    android.os.SystemClock.uptimeMillis() -
                        guideNav.lastHorizontalNavAtMs < 800L
                },
            )
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides stripBringIntoViewSpec) {
        // Programme strip - horizontally scrolled with the header.
        Box(
            modifier = Modifier
                .horizontalScroll(horizontalScrollState, flingBehavior = guideFling)
                .fillMaxHeight(),
        ) {
            val totalWidth = run {
                val raw = hourWidth * (windowDurationMs / 3_600_000L).toInt()
                // Compose caps a fixed layout dimension at ~262143px. A wide EPG
                // window at a high display scale (Live TV List 150-175% on
                // Android TV) pushes hourWidth x total-hours past that, which
                // SILENTLY blanks the entire programme strip -- cells render
                // nothing while the channel rail + time header (which don't hang
                // off this giant box) survive. Clamp the scroll-range width to a
                // safe px budget so the strip always measures; cells beyond it
                // are simply unreachable by horizontal scroll (many days out at
                // that zoom) while every visible / near-now cell still renders.
                val maxDp = with(LocalDensity.current) { GUIDE_MAX_STRIP_PX.toDp() }
                if (raw > maxDp) {
                    android.util.Log.w(
                        "GuideScreen",
                        "programme strip width $raw exceeds the ${maxDp} Compose layout cap " +
                            "(scale x wide EPG window); clamping so the grid still renders",
                    )
                    maxDp
                } else {
                    raw
                }
            }
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
                    // rowIsFocused is hoisted to the row body as a derivedStateOf
                    // (see above) so reading it here doesn't subscribe this row to
                    // every lastFocusedChannelIndex change.
                    if (!rowIsFocused &&
                        (rawEnd <= visibleStartMs || rawStart >= visibleEndMs)
                    ) return@forEachIndexed
                    // Task #188: BOUND the focused-row exception. Composing the
                    // focused row's ENTIRE window made its per-press cost scale
                    // with EPG size (alturismo's large EPG = worst case; a
                    // dense channel landing = the 350-900ms vertical hitch).
                    // A halo of 2x the padded visible span on each side keeps
                    // every cell a page-jump (0.85 viewport) or bring-into-view
                    // step could land on alive -- the focus-orphan bug the
                    // exception exists for cannot reach cells 2+ windows away,
                    // because the column snap and retarget logic always pull
                    // focus back toward the viewport first.
                    if (rowIsFocused) {
                        val haloMs = (visibleEndMs - visibleStartMs) * 2
                        if (rawEnd <= visibleStartMs - haloMs ||
                            rawStart >= visibleEndMs + haloMs
                        ) return@forEachIndexed
                    }
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
                    // key() gives each cell COMPOSITION IDENTITY (2026-07-12
                    // Streamer field trace): without it, the rowIsFocused flip
                    // to all-cells emission (or an EPG history merge) rebinds
                    // the k-th slot POSITIONALLY, silently moving the FOCUSED
                    // LayoutNode onto a different programme (day-old history
                    // cells). Compose's in-flight bring-into-view then retargets
                    // to the rebound node every frame and drags the shared
                    // timeline days into the past. Unique per row after
                    // dedupSameAiring.
                    androidx.compose.runtime.key(programme.startMillis) {
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
                        collectionsMenu = collectionsMenu,
                        onWatchCatchup = if (
                            onProgrammeWatch != null &&
                            channel.canReplay(programme, nowMillis)
                        ) {
                            { onProgrammeWatch(programme) }
                        } else {
                            null
                        },
                    )
                    }
                }
                // The now-line is no longer drawn per row: the row dividers
                // sliced it into dashes. A single continuous line lives as a
                // grid-level overlay in guideGrid (Apple-parity, 2026-07-12).
            }
        }
        }
    }
    } // CompositionLocalProvider (GH #25 row text scale)

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
            // Low-rate diagnostics (one line per user-paced vertical landing):
            // which cell the anchor resolved to and how many spans were
            // composed. Field evidence for wrong-column landings (2026-07-12
            // Streamer: a row-down snapped to the far-right cell).
            // Debug builds only (task #188): the string build includes two
            // O(spans) scans and sat directly on the per-press path.
            if (com.aeriotv.android.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "GuideNav",
                    "land row=$channelIndex anchor=$anchorTimeMs " +
                        "cell=${target.startMs}..${target.endMs} " +
                        "contained=${containing != null} spans=${spans.size} " +
                        "spanRange=${spans.minOf { it.startMs }}..${spans.maxOf { it.endMs }}",
                )
            }
            // requestFocus() throws if the node isn't attached yet (row composed
            // but not laid out). The caller retries for a few frames, so swallow
            // and report failure rather than crash.
            runCatching { target.requester.requestFocus() }.isSuccess
        }
        // Explicit LEFT/RIGHT: step to the adjacent COMPOSED cell in this row.
        // Task #188: visibleCellSpans is built in programme-start order (the
        // composition loop walks the start-sorted programme list), so the old
        // per-press sortedBy COPY was a pure allocation + O(n log n) tax on
        // the hot path. Use the list directly.
        guideNav.registerRowHorizontal(channelIndex) { forward, fromMs ->
            val spans = visibleCellSpans
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
    collectionsMenu: CollectionsMenuContext? = null,
    /** Catch-up (task #133): non-null when THIS programme is replayable from
     *  the channel's archive; adds a "Watch" action at the top of the cell
     *  menu on both TV and phone. */
    onWatchCatchup: (() -> Unit)? = null,
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
    // focus ring for D-pad clarity. Visual-parity polish: the PHONE guide now
    // matches too (iOS renders the same flat cells with 1px seams on iPhone,
    // EPGGuideView cellGap/rowGap = 1, no radius); the old rounded, floating,
    // bordered phone cell was Android-only chrome.
    val cellShape = if (focused) RoundedCornerShape(4.dp) else RectangleShape
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
    // tvOS focus ring is a 4pt WHITE inset border (Emby style, line 3339);
    // match it 1:1 at 4dp. The old "proportional" 2dp ring was too thin to
    // find at 10 feet when focus sat on a NOW-AIRING cell: the airing fill is
    // already light, so a hairline white ring on a light 2-hour-wide cell
    // read as "focus disappeared" (Streamer field report 2026-07-12). Phone
    // keeps the cyan accent border when focused; at rest there is NO border
    // on either platform (iOS cells are borderless fills, live conveyed by
    // the brighter fill alone).
    val cellBorderColor = if (isTv) Color.White else MaterialTheme.colorScheme.primary
    val cellBorderWidth = when {
        !focused -> 0.dp
        isTv -> 4.dp
        else -> 3.dp
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
        // iOS parity (EPGGuideView leadingClip): as a program scrolls off the
        // left, keep its text pinned to the visible left edge AND re-truncate it
        // to the shrinking visible width so it always ends in a clean ellipsis,
        // instead of the old Modifier.offset which slid a fixed-width block and
        // hard-clipped it at the cell edge. iOS does this by growing the title's
        // leading padding; here a layout-phase re-measure shrinks maxWidth by the
        // scrolled-off amount and re-places the (now-narrower) text at that inset.
        // Reads the scroll in the LAYOUT phase, so panning re-measures only the
        // ~visible cells (single-line text = cheap) without recomposing.
        Modifier.layout { measurable, constraints ->
            val cellLeftPx = cellStartDp.roundToPx()
            val cellWidthPx = widthDp.roundToPx()
            val shift = (horizontalScrollState.value - cellLeftPx).coerceIn(0, cellWidthPx)
            val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else cellWidthPx
            val avail = (maxW - shift).coerceAtLeast(0)
            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = avail))
            layout((placeable.width + shift).coerceAtMost(maxW), placeable.height) {
                placeable.place(shift, 0)
            }
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
            // Both platforms: a 1dp trailing seam so adjacent flat cells stay
            // distinct (iOS cellGap = 1). The phone's old 4dp vertical float
            // went with its rounded-card cell.
            .padding(end = 1.dp)
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
        // Built lazily ONLY while the long-press menu is open. This buildList plus
        // its up-to-5 TvMenuAction objects, their captured lambdas, and the
        // System.currentTimeMillis() call below previously ran on EVERY cell
        // recomposition -- including the cells that recompose on each D-pad focus
        // move -- even though the result is consumed only inside the `menuOpen`
        // branches further down. Gating on menuOpen eliminates that per-cell
        // allocation churn during navigation; the list rebuilds fresh (with the
        // current lambdas) the moment the menu opens.
        val menuActions = if (menuOpen) buildList {
            // Catch-up (task #133): a replayable PAST programme leads with
            // Watch -- the primary action for a show that already aired.
            onWatchCatchup?.let { watch ->
                add(TvMenuAction("Watch", Icons.Outlined.Replay) { watch() })
            }
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
            // #45: collection actions (iOS tvOS guide-cell dialog order:
            // ... Multiview, then Add to Collection, then the contextual
            // remove). The remove row follows iOS's rule exactly: viewing a
            // collection the channel is IN -> remove from that one; viewing
            // no collection while it's in any -> remove from all; otherwise
            // no remove row.
            collectionsMenu?.let { cm ->
                add(
                    TvMenuAction("Add to Collection…", Icons.Outlined.Folder) {
                        cm.onOpenPicker(channelId, channelName, channelIndex, anchorTimeMs)
                    },
                )
                val active = cm.activeCollectionId
                    ?.let { id -> cm.collections.firstOrNull { it.id == id } }
                if (active != null && channelId in active.memberIds) {
                    add(
                        TvMenuAction(
                            "Remove from ${active.name}",
                            Icons.Outlined.Folder,
                            destructive = true,
                        ) { cm.onRemoveMember(active.id, channelId) },
                    )
                } else if (cm.activeCollectionId == null &&
                    cm.collections.any { channelId in it.memberIds }
                ) {
                    add(
                        TvMenuAction(
                            "Remove from All Collections",
                            Icons.Outlined.Folder,
                            destructive = true,
                        ) { cm.onRemoveFromAll(channelId) },
                    )
                }
            }
            // iOS parity: a LIVE program can always be recorded (coerced to a
            // local device recording inside RecordProgramSheet when the account
            // isn't a Dispatcharr admin). A FUTURE program can only be scheduled
            // server-side, so it stays gated on canRecordToServer. Keep the
            // not-yet-ended gate either way.
            val notEnded = programme.endMillis > System.currentTimeMillis()
            val showRecord = notEnded && (isLive || canRecordToServer)
            if (showRecord) {
                add(
                    TvMenuAction(
                        if (isLive) "Record from Now" else "Record",
                        Icons.Outlined.FiberManualRecord,
                    ) { onRecord() },
                )
            }
        } else emptyList()
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    // Catch-up: Dispatcharr-style rewind-clock glyph marking a
                    // past programme that can be replayed from the archive.
                    if (onWatchCatchup != null) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = "Catch-up available",
                            tint = if (focused) Color.White.copy(alpha = 0.85f)
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Text(
                        text = programme.title.ifBlank { "No info" },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (focused) Color.White else MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // GH #34: the XMLTV <sub-title> (episode / sports-match name,
                // e.g. "Team A vs Team B") is what distinguishes back-to-back
                // programmes with the same generic title; surface it in the cell.
                programme.subTitle?.takeIf { it.isNotBlank() }?.let { sub ->
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (focused) Color.White.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                    // Time range plus the season/episode pill and badge flags,
                    // folded onto one line so the height-limited TV cell does
                    // not gain a row. Trailing badges clip first on narrow cells.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = timeRange,
                            style = MaterialTheme.typography.labelSmall,
                            // iOS parity: guide-cell time lines sit on the
                            // textTertiary rung (EPGGuideView tvOS branch), a
                            // full step dimmer than the description.
                            color = if (focused) Color.White.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (LocalShowEpgBadges.current) {
                            SeasonEpisodePill(programme.seasonEpisodeLabel(), compact = true)
                            EpgFlagsRow(programme.epgFlags(), compact = true)
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = contentSticky,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    // Catch-up: same rewind-clock glyph as the TV cell.
                    if (onWatchCatchup != null) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = "Catch-up available",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Text(
                        text = programme.title.ifBlank { "No info" },
                        style = MaterialTheme.typography.labelMedium,
                        color = titleColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // GH #34: surface the XMLTV <sub-title> (match/episode name).
                programme.subTitle?.takeIf { it.isNotBlank() }?.let { sub ->
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (programme.description.isNotBlank()) {
                    Text(
                        text = programme.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Badges sit BELOW the title and description so the cell reads
                // cleanly top-down instead of a busy middle row.
                if (!programme.isPlaceholder && LocalShowEpgBadges.current) {
                    val flags = programme.epgFlags()
                    val seLabel = programme.seasonEpisodeLabel()
                    if (flags.isNotEmpty() || seLabel != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            SeasonEpisodePill(seLabel, compact = true)
                            EpgFlagsRow(flags, compact = true)
                        }
                    }
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
    // Matched to iOS EPGGuideView phone metrics for density parity: timeHeader
    // 32pt, row 72pt.
    val HEADER_HEIGHT = 32.dp
    val ROW_HEIGHT = 72.dp
    /** Base (1.0x) width of one hour column. Scaled by guideScale at render. */
    val HOUR_WIDTH = 320.dp
}

private const val MS_PER_HOUR_F = 3_600_000f

/** Compose packs a fixed layout dimension into ~262143px (Constraints). The
 *  programme strip is a single Box spanning the ENTIRE EPG window
 *  (hourWidth x total-hours); a wide window at a high Live TV display scale
 *  overruns that cap and silently blanks the whole strip on Android TV. Clamp
 *  the strip width to this safe budget (well under the limit, and still large
 *  enough that the now-line and days of programmes on either side stay
 *  scrollable at max zoom). */
private const val GUIDE_MAX_STRIP_PX = 240_000f

/** Minimum gap between channel up/down moves driven by a HELD D-pad key
 *  (auto-repeat). The initial press always moves (a tap is one channel); held
 *  repeats are rate-limited to this so a long hold fast-scrolls at a
 *  controllable ~8 channels/sec instead of the system's ~20/sec repeat rate
 *  blasting 7-8 rows in one press. Tune up for a slower hold-scroll. */
private const val HOLD_SCROLL_MIN_INTERVAL_MS = 120L

/** GH #29: hold-to-accelerate tiers. After 1s of continuous hold the step
 *  interval halves (~16 ch/s), after 2.5s it drops again (~25 ch/s -- every
 *  other system auto-repeat). Tap and short-hold behavior is unchanged. */
private const val HOLD_SCROLL_ACCEL_INTERVAL_MS = 60L
private const val HOLD_SCROLL_TURBO_INTERVAL_MS = 40L

/** D-pad LEFT auto-repeat count that counts as a "hold" for the tvOS
 *  return-to-All-pill gesture (#10). Android starts auto-repeating ~400ms after
 *  the initial press, then ~1 count / 50ms, so 4 ~= a half-second hold, matching
 *  tvOS's 0.5s UILongPressGestureRecognizer. A tap only ever sends count 0, so
 *  the short-Left timeline scroll never trips it. */
private const val HOLD_LEFT_ALL_PILL_REPEAT = 4

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
 *    focuses its cell at the anchor time; [leadingEdgeTargetPx] pins that cell's
 *    leading edge to the outgoing highlight's x so the highlight stays put while
 *    the shared timeline slides under it (the "stable highlight" model).
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

    /** Raw start time (ms) of the cell that actually holds focus -- unlike
     *  [anchorTimeMs] it is never clamped forward to the viewport, so horizontal
     *  Left/Right stepping keys off where focus truly is and cannot dead-end even
     *  if the anchor is momentarily out of the composed span range. */
    var focusedCellAnchorMs: Long = Long.MIN_VALUE
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

    /** On-screen x (relative to the strip viewport start) that the highlighted
     *  cell's LEADING EDGE should stay pinned to during a channel up/down move,
     *  or null when no vertical move is in flight. The
     *  [GuideLeadingEdgeBringIntoViewSpec] reads it and scrolls the shared
     *  timeline so the newly-focused cell left-aligns here, keeping the highlight
     *  put while the time axis slides under it (the "stable highlight" model).
     *  Captured from the outgoing cell so the first press doesn't snap. A plain
     *  volatile field, not Compose state -- the spec reads it imperatively. */
    @Volatile
    var leadingEdgeTargetPx: Float? = null
        private set

    /** Live viewport start time (ms), fed by the guide's visible-window flow.
     *  Task #138: a freshly-focused LEFT-CLIPPED cell reports its programme
     *  start, which with 7-day catch-up history can sit hours in the past;
     *  adopting it raw made the next vertical move target the row cell
     *  containing that PAST time and drag the timeline into history. The
     *  anchor adopted on a fresh focus is clamped to this, restoring the
     *  original "column the highlight occupies on screen" semantics. */
    @Volatile
    var viewportStartMs: Long = Long.MIN_VALUE

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

    /** Begin a vertical (channel up/down) move. [leadingEdgeTargetPx] is the
     *  on-screen x to pin the landing cell's leading edge to (captured from the
     *  outgoing cell) so the highlight's left edge stays put; null keeps the
     *  default bring-into-view (used by programmatic focus / focus-on-return). A
     *  null target while a sweep is already in flight is ignored so a rapid
     *  auto-repeat holds the first captured x for the whole hold. */
    fun beginVerticalMove(leadingEdgeTargetPx: Float? = null) {
        // Capture the pin x at the START of a sweep (no move currently in flight)
        // and HOLD it across a rapid auto-repeat so the pin can't drift toward a
        // mid-animation position on each repeat. A programmatic move (null target)
        // always clears the pin so its focus uses the default bring-into-view. The
        // pin is NOT released on the per-move timer below (the left-align scroll is
        // animated and outlives it); it is refreshed by the next fresh press or
        // dropped on the next LEFT/RIGHT (allowHorizontalScroll).
        if (leadingEdgeTargetPx == null) {
            this.leadingEdgeTargetPx = null
        } else if (!verticalMoveInFlight) {
            this.leadingEdgeTargetPx = leadingEdgeTargetPx
        }
        verticalMoveInFlight = true
    }

    /** Pin the navigation anchor to a specific time before a programmatic focus.
     *  Focus-on-return seeds 'now' so the focused cell is the live programme
     *  rather than the window-edge cell the Long.MIN_VALUE default would pick. */
    fun seedAnchor(timeMs: Long) {
        anchorTimeMs = timeMs
    }

    /** Wall-clock of the last explicit LEFT/RIGHT press. The bring-into-view
     *  spec only reveals a focused cell while this is fresh (see
     *  horizontalNavActive at the spec construction) - every OTHER focus
     *  change (grid entry, vertical channel moves, focus restore, node
     *  rebinds) leaves the timeline untouched, matching tvOS where the time
     *  axis never moves because of focus. */
    var lastHorizontalNavAtMs: Long = 0L

    /** The user pressed LEFT/RIGHT (or clicked): drop the vertical-move pin so
     *  normal horizontal bring-into-view (leading-edge / minimal-nudge) runs,
     *  and open the short reveal window the spec checks. */
    fun allowHorizontalScroll() {
        leadingEdgeTargetPx = null
        lastHorizontalNavAtMs = android.os.SystemClock.uptimeMillis()
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
        // Step from the ACTUAL focused cell's start, not the vertical-nav anchor.
        // The anchor is clamped forward to viewportStartMs for a left-clipped cell
        // (column preservation across channel moves); if viewportStartMs is ever
        // stale/far-off, that clamp could push the anchor past every composed span
        // and dead-end the step. The focused cell is always inside this row's spans,
        // so keying off it makes Left/Right robust.
        val fromMs = if (focusedCellAnchorMs != Long.MIN_VALUE) focusedCellAnchorMs else anchorTimeMs
        if (i < 0 || fromMs == Long.MIN_VALUE) return false
        return rowHorizontalHandlers[i]?.focusAdjacent(forward, fromMs) ?: false
    }

    /** Called by a cell when it gains focus. Records which channel row owns
     *  focus and the time column the user is in. */
    fun onCellFocused(channelIndex: Int, cellStartMs: Long) {
        focusedChannelIndex = channelIndex
        lastFocusedChannelIndex = channelIndex
        // Raw column of the cell that actually holds focus (never clamped forward),
        // so horizontal stepping keys off where focus really is.
        focusedCellAnchorMs = cellStartMs
        // Preserve the navigation column across vertical (channel up/down) moves.
        // A handler-driven focus sets programmaticFocusPending first; we consume it
        // and keep the existing anchor instead of snapping to this cell's start.
        // Only a user horizontal / fresh-entry focus (flag false) adopts a new
        // column. (Bug 1: without this the anchor drifts up to an hour per press.)
        if (programmaticFocusPending) {
            programmaticFocusPending = false
        } else {
            anchorTimeMs = if (viewportStartMs != Long.MIN_VALUE) {
                cellStartMs.coerceAtLeast(viewportStartMs)
            } else {
                cellStartMs
            }
        }
        // Debug builds only (task #188): fired on EVERY cell focus.
        if (com.aeriotv.android.BuildConfig.DEBUG) {
            android.util.Log.i(
                "GuideNav",
                "focused row=$channelIndex cellStart=$cellStartMs " +
                    "anchor=$anchorTimeMs viewportStart=$viewportStartMs",
            )
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
            // End the move a few frames after landing -- but only if this is still
            // the most recent move (a rapid auto-repeat keeps the sweep in flight
            // until the final landing). NonCancellable so a superseding press that
            // cancels this coroutine still runs the reset. NOTE: this does NOT drop
            // leadingEdgeTargetPx -- the left-align scroll is animated and outlives
            // 48ms, so the pin is held (refreshed by the next fresh press, dropped
            // on the next LEFT/RIGHT) or an early release would truncate the scroll
            // and leave the highlight un-pinned.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.delay(48L)
                if (gen == moveGeneration) {
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
    /** Remote Control A2: page the focused channel up/down by one
     *  viewport of rows (FF/RW guide paging). Reuses the
     *  standard vertical move so the lane model + anchor landing hold. */
    suspend fun pageVertical(direction: Int, lastChannelIndex: Int, listState: LazyListState) {
        if (lastChannelIndex < 0) return
        val visible = (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
        val cur = when {
            verticalMoveInFlight && pendingTargetIndex >= 0 -> pendingTargetIndex
            focusedChannelIndex >= 0 -> focusedChannelIndex
            else -> lastFocusedChannelIndex
        }.coerceAtLeast(0)
        val target = (cur + direction * visible).coerceIn(0, lastChannelIndex)
        if (target != cur) {
            beginVerticalMove()
            moveFocusToChannel(target, listState)
        }
    }

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
        // Task #185, reference-guide lane model (Emby 50-down field study
        // 2026-07-17): hold the focused row as the SECOND visible row (one
        // context row above), so vertical navigation scrolls continuously and
        // the focus ring never rides the top/bottom screen edge half-clipped.
        // The old nearest-edge behaviour parked the ring on the bottom fold
        // and only scrolled when focus was about to fall off, which read as
        // sudden unpredictable jumps. At the top of the list this clamps to 0
        // (rows 0/1 focus without scrolling); at the bottom the list runs out
        // of scroll range and the focused row naturally rides lower, exactly
        // like the reference guide.
        //
        // Task #188: skip the scroll when the list already sits exactly on
        // the target. scrollToItem forces a LazyColumn re-layout even when
        // positionally a no-op, which was a measurable slice of EVERY
        // vertical press (the genuine row-reveal work stays, but the
        // already-positioned case is now free).
        val target = (index - 1).coerceAtLeast(0)
        if (listState.firstVisibleItemIndex != target ||
            listState.firstVisibleItemScrollOffset != 0
        ) {
            listState.scrollToItem(target)
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
 * [verticalMoveLeadingEdgeTargetPx] drives the "stable highlight" model on a
 * D-pad UP/DOWN move. While a vertical move is in flight it returns the on-screen
 * x (relative to the strip viewport start) at which the highlight's leading edge
 * should stay; the spec then scrolls the SHARED timeline so the newly-focused
 * cell's leading edge lands there. That keeps the highlighted cell's LEFT EDGE
 * fixed while the time axis slides under it, instead of the old freeze that left
 * the highlight jumping to each program's true (wildly varying) start position.
 * It returns null on LEFT/RIGHT and when no vertical move is in flight, where the
 * leading-edge / minimal-nudge logic below runs exactly as before.
 */
/**
 * Vertical bring-into-view for the guide's channel LazyColumn: the classic
 * "walk to the edge, then scroll" model. A fully visible focused row never
 * scrolls; a row past an edge gets the minimal nudge that reveals it. This
 * exists because Compose's DEFAULT spec on television uiMode instead pivots
 * the focused item to ~1/3 of the viewport, which parked the guide highlight
 * three rows from the top while the list scrolled under it (user request:
 * focus should reach the bottom row before the guide scrolls).
 */
@OptIn(ExperimentalFoundationApi::class)
private object GuideVerticalMinimalBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        if (offset >= 0f && offset + size <= containerSize) return 0f
        return if (offset < 0f) offset else offset + size - containerSize
    }
}

@OptIn(ExperimentalFoundationApi::class)
private class GuideLeadingEdgeBringIntoViewSpec(
    private val verticalMoveLeadingEdgeTargetPx: () -> Float?,
    private val horizontalNavActive: () -> Boolean,
) : BringIntoViewSpec {
    // Task #188: short reveal animation. Compose's horizontalScroll is
    // LAYOUT-based (unlike SwiftUI's GPU offset transform on tvOS), so every
    // animation frame re-lays-out every visible row's 240k-px strip; the
    // default spec's ~300ms glide = ~18 such frames per D-pad step and was
    // the largest slice of the measured 69-85ms median horizontal frames.
    // ~100ms keeps a readable hint of motion at a third of the layout passes.
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        androidx.compose.animation.core.tween(durationMillis = 100)

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        // Vertical (channel up/down) move in flight: do NOT scroll the
        // timeline at all (GH #24). The previous model left-aligned the
        // newly-focused cell's start to the old highlight x, which shifted
        // the shared timeline left/right by the difference in programme
        // start times on EVERY channel move -- "the guide lurches side to
        // side as you scroll" -- and dragged the now-bar with it. The
        // vertical-nav handler always lands focus on a cell that is
        // already composed at the anchor column, so the time axis (and
        // the now-bar) stay rock steady and only the highlight moves;
        // the sticky programme title keeps left-clipped names readable.
        verticalMoveLeadingEdgeTargetPx()?.let { _ ->
            return 0f
        }
        // tvOS model (2026-07-12, after two field reports of the timeline
        // "jumping" on focus): FOCUS never moves the timeline. Only an
        // explicit LEFT/RIGHT navigation - a short window refreshed by every
        // press, including auto-repeat - may reveal the newly focused cell.
        // Grid entry, vertical moves, focus restores and node rebinds all
        // land with the time axis untouched; the sticky programme title
        // keeps left-clipped names readable. (A 25%-visibility threshold
        // tried first was defeated by an airing cell with 24% showing.)
        if (!horizontalNavActive()) return 0f
        // Already fully visible: no scroll.
        if (offset >= 0f && offset + size <= containerSize) return 0f
        // Oversized cell (wider than the viewport): if ANY part already overlaps
        // the viewport, do NOT scroll. The sticky program title keeps the name
        // pinned + readable at the visible left edge, so focusing a long program
        // must never fling the timeline (previously it slammed the cell's leading
        // edge to the viewport start -- the "Right rapidly scrolls right / Left
        // jumps to the beginning of the EPG" report). Only an oversized cell that
        // is ENTIRELY off one side gets a minimal nudge onto screen.
        val distance = if (size > containerSize) {
            when {
                offset < containerSize && offset + size > 0f -> 0f   // overlaps viewport: stay put
                offset >= containerSize -> offset                     // entirely off the right: leading edge -> start
                else -> offset + size - containerSize                 // entirely off the left: trailing edge -> end
            }
        } else {
            // Normal cell during explicit L/R navigation: minimal reveal
            // (leading edge if off the start, trailing edge if off the end).
            if (offset < 0f) offset else offset + size - containerSize
        }
        // Invariant (2026-07-12 Streamer field trace): a focus change may never
        // move the shared timeline more than ~one viewport. Every legitimate
        // focus target is a composed cell within the +-90min compose pad, so a
        // larger request means the focused node was positionally REBOUND to a
        // far-away programme (see the key() note at the cell emission) and an
        // in-flight bring-into-view is retargeting to it - following it drags
        // the timeline days into catch-up history. Refuse instead.
        return if (abs(distance) > containerSize) 0f else distance
    }
}
