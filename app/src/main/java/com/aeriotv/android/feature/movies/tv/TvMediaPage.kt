package com.aeriotv.android.feature.movies.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import com.aeriotv.android.feature.main.LocalTabIsActive
import com.aeriotv.android.feature.main.LocalTvChromeCollapsed
import com.aeriotv.android.feature.main.LocalTvTabEntryFocus
import com.aeriotv.android.feature.main.LocalTvTopNavFocusRequester
import com.aeriotv.android.ui.tv.TvActionCircle
import com.aeriotv.android.ui.tv.TvChrome
import com.aeriotv.android.ui.tv.TvKeyboardOnOkHost
import com.aeriotv.android.ui.tv.TvPill
import com.aeriotv.android.ui.tv.TvSearchCapsule
import com.aeriotv.android.ui.tv.tvFocusScale
import com.aeriotv.android.ui.tv.vodGridDpadFallback
import kotlinx.coroutines.launch

/*
 * The Android TV media page: ONE template for Movies, TV Shows and DVR
 * (Logan 2026-09-09: no separate templates), ported from the tvOS
 * MoviesView / DVRView layouts. tvOS lays out on a 1080-point canvas and a
 * 1080p Android TV is ~540 dp tall, so every tvOS number is halved:
 *
 *   overscan 80 -> 40 | hero 420 tall -> 210, side inset 16 -> 8, corner
 *   24 -> 12, copy inset 44 -> 22 | page width 62% when more than one page,
 *   spacing 8 -> 4, dots 10 -> 5 | section spacing 28 -> 14 | shelf and
 *   grid left edge 44 + 16 -> 30 in from the overscan edge | grid columns
 *   7 (VOD) / 5 (DVR), column spacing 24 -> 12, row spacing 48 -> 24 (DVR
 *   44 -> 22) | pills 22 pt -> 11 sp, spacing 12 -> 6 | header title 24 ->
 *   12 sp, count 20 -> 10 sp, circles 60 -> 30 | rail 72 -> 36 wide, cells
 *   32 -> 16.
 *
 * Focus: Down from the tab bar lands on the hero's primary button; Up from
 * the hero returns to the bar; Left from the grid's first column reaches
 * the alphabet rail and Right leaves it again; Down past the last built
 * grid row scrolls the next row in (vodGridDpadFallback). The tab bar
 * collapses once the hero has scrolled off and grows back when focus
 * reaches it. BACK while scrolled snaps to the top and refocuses the hero.
 */

/**
 * Which cell opened the detail (or player) now on top of a TV page, per
 * page. In-process only: the tab is disposed while the route sits on top,
 * and its rememberSaveable slot did not come back on the Streamer, so the
 * key lives here and TvMediaPage restores scroll + focus from it.
 */
object TvReturnMemory {
    val pending = androidx.compose.runtime.mutableStateMapOf<String, Any>()
    /** The grid's (firstVisibleItemIndex, firstVisibleItemScrollOffset) at the
     *  moment that cell was opened. tvOS never disposes the tab, so the page
     *  comes back at the exact offset (MoviesView.swift:221, 259-279); this is
     *  how Android reconstructs it. Not observed: read once on return. */
    val pendingOffset = HashMap<String, Pair<Int, Int>>()
}

object TvPage {
    val overscan: Dp = 40.dp
    val heroInset: Dp = 8.dp
    val contentInset: Dp = 30.dp
    val sectionSpacing: Dp = 14.dp
    val columnSpacing: Dp = 12.dp
    val heroHeight: Dp = 210.dp
}

data class TvHeroButton(
    val label: String,
    val icon: ImageVector,
    val primary: Boolean = false,
    val onClick: () -> Unit,
)

/** One hero page: art, copy and the action row (tvOS MoviesHero / DVRHero). */
data class TvHeroPage(
    val key: Any,
    val title: String,
    val artUrl: String?,
    val buttons: List<TvHeroButton>,
    val subtitle: String? = null,
    /** "Recording now" / "Continue watching" line above the title. */
    val eyebrow: String? = null,
    val eyebrowColor: Color = Color.Unspecified,
    /** tvOS: only "Recording now" carries the dot; "Continue watching" is plain text. */
    val eyebrowDot: Boolean = false,
    /** Channel logo shown at the trailing side when there is no art. */
    val logoUrl: String? = null,
    val meta: List<String> = emptyList(),
    val rating: String? = null,
    val plot: String? = null,
    /** Long press on the primary button (Watchlist / remove entries). */
    val longPressActions: List<TvMenuAction> = emptyList(),
)

/** A horizontal shelf between the hero and the library (Watchlist, Recording Now, Scheduled ...). */
class TvShelf<T>(
    val title: String,
    val items: List<T>,
    val key: (T) -> Any,
    val cardWidth: Dp,
    val card: @Composable (item: T, modifier: Modifier) -> Unit,
)

/** Everything a grid cell needs from the page: the focus plumbing it must apply to its root. */
class TvCellScope(
    val modifier: Modifier,
    val focusRequester: FocusRequester?,
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun <T> TvMediaPage(
    gridState: LazyGridState,
    heroPages: List<TvHeroPage>,
    shelves: List<TvShelf<*>>,
    headerTitle: String,
    headerCount: Int,
    columns: Int,
    gridRowSpacing: Dp,
    sortActions: List<TvMenuAction>,
    onFilter: (() -> Unit)? = null,
    filterActive: Boolean = false,
    searchEnabled: Boolean = false,
    searchActive: Boolean = false,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onSearchToggle: () -> Unit = {},
    onClearSearch: () -> Unit = {},
    searchPlaceholder: String = "Search",
    isSearching: Boolean = false,
    searchExtras: List<@Composable () -> Unit> = emptyList(),
    pills: List<String> = emptyList(),
    selectedPill: String? = null,
    onPill: (String?) -> Unit = {},
    gridItems: List<T>,
    gridKey: (T) -> Any,
    cell: @Composable (item: T, scope: TvCellScope) -> Unit,
    emptyContent: @Composable () -> Unit,
    isLoading: Boolean = false,
    railLetters: Set<Char> = emptySet(),
    railIndexOf: (Char) -> Int = { -1 },
    railMinimumCount: Int = 1,
    /** Return-focus requester for the grid cell opened last (BACK from a detail). */
    cellReturnRequester: (T) -> FocusRequester? = { null },
    /** BACK from a detail: the key of the cell that opened it. The page scrolls
     *  that row in once the library is rebuilt (the list arrives empty on the
     *  first frame after a return, which clamps the saved scroll to the top)
     *  and refocuses the cell, then calls [onReturnHandled]. */
    returnKey: Any? = null,
    /** Where the grid stood when [returnKey] was opened (index, offset px):
     *  restored verbatim when it is still in range, else the row-park below. */
    returnOffset: Pair<Int, Int>? = null,
    onReturnHandled: () -> Unit = {},
    /** tvOS Movies gates the rail on an empty search field (MoviesView.swift:1854);
     *  DVR does NOT (DVRView.swift:517), so it is a parameter, not an invariant. */
    hideRailWhileSearching: Boolean = true,
    /** Content offset at which the tab bar hides. tvOS: flat 560 pt / 280 dp for
     *  Movies and TV Shows (MoviesView.swift:379-382); DVR 620 pt / 310 dp with a
     *  hero, 260 pt / 130 dp without (DVRView.swift:863). */
    barHideThreshold: Dp = 280.dp,
    /** A filter surface is open over the page: on close, focus returns to the
     *  Filter circle (tvOS fullScreenCover never tore the tab's focus state
     *  down, MoviesView.swift:3503). */
    filterOpen: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val topNav = LocalTvTopNavFocusRequester.current
    val tabEntry = LocalTvTabEntryFocus.current
    val chromeCollapsed = LocalTvChromeCollapsed.current
    val visibleShelves = shelves.filter { it.items.isNotEmpty() }
    val hasHero = heroPages.isNotEmpty()

    // Full-span items before the grid cells: hero, shelves, header,
    // (search extras), (pills).
    val headerIndex = 1 + (if (hasHero) 1 else 0) + visibleShelves.size
    val pillRow = !isSearching && pills.isNotEmpty()
    val leadingCount = headerIndex + 1 + (if (isSearching) searchExtras.size else 0) + (if (pillRow) 1 else 0)

    val heroPrimary = remember { FocusRequester() }
    val firstCell = remember { FocusRequester() }
    /** The All pill: where the pill row is entered from above or below (Logan 2026-09-10). */
    val allPill = remember { FocusRequester() }
    // ------------------------------------------------------------------
    // ONE owner of focus scrolling (Logan 2026-09-11).
    //
    // Every focusable on this page does exactly one thing when it gains
    // focus: it sends a PageAnchor into a CONFLATED channel. A single
    // coroutine (the owner, below) consumes that channel, cancels whatever
    // move is running, computes ONE absolute target offset and animates the
    // grid there with animate() + scrollBy at PreventUserInput. Nothing
    // else on the page may scroll it: the bring-into-view spec around the
    // whole grid returns 0 unconditionally, and the shelf and pill LazyRows
    // carry their own HORIZONTAL specs so they never inherit a vertical one.
    //
    // TARGETS ARE ON-SCREEN POSITIONS, NOT PAGE DISTANCES.
    // The Apple TV numbers Logan measured (points halved) are content
    // offsets on the tvOS DVR tab: 54 dp with the first shelf card focused,
    // 92 dp with Sort focused, 129 dp with the All pill focused, and the
    // library header flush with the top edge with the first grid row
    // focused. Those distances only reproduce the tvOS picture on tvOS
    // geometry (the Android leading block is taller), so they are converted
    // here into WHERE THE FOCUSED ROW'S TOP SITS ON SCREEN, which does port.
    //
    // tvOS DVR page, one hero plus one shelf (DVRView.swift, points, at
    // content offset 0; the ScrollView's own .padding(.top, 190) at :775,
    // VStack spacing sectionSpacing = 28 at :661/:865):
    //   catcher top      190   (h 8,   DVRView.swift:704)
    //   hero top         226   (h 420, :312/:1403)
    //   shelf top        674   (h 326) = title 24 pt semibold line 29
    //                          (Typography.swift:13) + VStack spacing 8
    //                          + row (card 249 + .padding(.vertical, 20)
    //                          x2 = 289); card = 340 wide 16:9 art 191.25
    //                          + spacing 8 + title line 26 + 2 + meta line
    //                          21.5 (:1774-1815, :908, :932)
    //   header top      1028   (h 60, the 60 pt circles, HomeView.swift:3661)
    //   pills top       1100   (h 76) = header + 60 + VStack spacing 12
    //                          (:949); pill 22 pt line 26 + 13 x 2 = 52,
    //                          plus .padding(.vertical, 12) (:1001)
    //   grid row 0 top  1204 + .padding(.vertical, 20) = 1224 (:1157-1181)
    //
    // Halve to dp and subtract the measured offset:
    //   Y_shelf  = 674/2  - 54  = 337 - 54  = 283 dp
    //   Y_header = 1028/2 - 92  = 514 - 92  = 422 dp
    //   Y_pills  = 1100/2 - 129 = 550 - 129 = 421 dp
    //   header flush with the top means offset 514 dp, so the first grid
    //   row sits at 1224/2 - 514 = 612 - 514 = 98 dp.
    // Cross-check: those four are ONE rule. Shelf block 163 dp tall ends at
    // 446 (94 dp clear of the 540 dp bottom edge), header 30 dp ends at 452
    // (88 clear), pill row 38 dp ends at 459 (81 clear), and the first grid
    // row starts 98 dp under the top. That is the tvOS focus engine keeping
    // the focused row roughly 100 dp clear of the edge it is moving toward,
    // which is also the deep-grid rule Logan measured.
    //
    // So for any anchor: target = restTopOf(anchorIndex) - Y, clamped to
    // [0, maxScroll].
    //
    // A DOWNWARD hop only ever scrolls DOWN: the table position describes
    // arriving from ABOVE, so when the candidate is already behind the page
    // the anchor falls back to the minimum move and a row that is comfortably
    // on screen does not move at all. Up onto the Header is the same shape
    // with a 24 dp top margin instead of 100.
    //
    // Every DOWNWARD target is additionally at least the bottom-clear target
    // (Logan 2026-09-11): the minimum offset that leaves the focused row's
    // BOTTOM 100 dp above the bottom edge of the grid viewport, which is the
    // same "100 dp clear of the edge it moves toward" rule the cross-check
    // above found. The Y positions were derived from tvOS ROW HEIGHTS, so an
    // Android row that is taller than its tvOS counterpart (the Movies
    // Watchlist shelf is 269 dp against the tvOS DVR shelf's 163 dp) would
    // otherwise sit with its card text under the bottom edge. For a shelf the
    // row is the whole shelf block, title plus cards.
    //
    // Up and down use the same Y except: Header seats 24 dp
    // under the top on the way up; Shelf(0) on the way up goes to 0 when the
    // shelf block fits under the hero at rest, else parks the shelf 86 dp
    // under the top; Hero is always 0; later shelves and grid rows use the
    // 100 dp edge clamp on the way up (the minimum move).
    // ------------------------------------------------------------------
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gridRowSpacingPx = with(density) { gridRowSpacing.roundToPx() }
    val gridBottomPaddingPx = with(density) { 40.dp.roundToPx() }
    val yShelfPx = with(density) { 283.dp.roundToPx() }
    val yHeaderPx = with(density) { 422.dp.roundToPx() }
    val yPillsPx = with(density) { 421.dp.roundToPx() }
    val headerUpGapPx = with(density) { 24.dp.roundToPx() }
    val shelfUpGapPx = with(density) { 86.dp.roundToPx() }
    val edgeMarginPx = with(density) { 100.dp.roundToPx() }
    /** Emission order of the full-span rows (must match the grid below exactly). */
    val leadingKeys: List<Any> = remember(hasHero, visibleShelves.map { it.title }, isSearching, searchExtras.size, pillRow, gridItems.isEmpty()) {
        buildList {
            add("catcher")
            if (hasHero) add("hero")
            visibleShelves.forEach { add("shelf:" + it.title) }
            add("header")
            if (isSearching) repeat(searchExtras.size) { add("extra:$it") }
            if (pillRow) add("pills")
            if (gridItems.isEmpty()) add("empty")
        }
    }
    // ONE geometry snapshot, published as a single State so every consumer
    // sees a consistent set. Heights are keyed by the row's own key and are
    // never cleared: a key survives a structure change (a shelf arriving
    // late, search opening). The table is rebuilt whenever a measured height,
    // the structure or the at-rest viewport changes.
    val leadingHeightByKey = remember { HashMap<Any, Int>() }
    val geometry = remember { mutableStateOf<TvPageGeometry?>(null) }
    val chromeScroll = com.aeriotv.android.feature.main.LocalTvChromeScroll.current
    /** Pixels the page has scrolled, from the first visible row whose rest top is known. */
    fun scrollOffsetPx(): Int? {
        val g = geometry.value ?: return null
        gridState.layoutInfo.visibleItemsInfo.forEach { item ->
            g.restTopOf(item.index)?.let { return it - item.offset.y }
        }
        return null
    }
    val inputsState = androidx.compose.runtime.rememberUpdatedState(
        TvPageInputs(
            headerIndex = headerIndex,
            leadingCount = leadingCount,
            columns = columns,
            itemCount = gridItems.size,
            hasHero = hasHero,
            shelfCount = visibleShelves.size,
        )
    )
    /** Largest legal offset, or null when the content height is not knowable
     *  yet (at rest on Movies the leading block is taller than the viewport,
     *  so the pills and the first cells have never been measured). A null
     *  limit must NOT stop a move: the TvScrollAtBound guard inside the
     *  animation stops it at the real bound instead. */
    fun maxScrollPx(): Int? {
        val g = geometry.value ?: return null
        val base = g.gridTopPx ?: return null
        val live = inputsState.value
        val rows = if (live.itemCount == 0) 0 else (live.itemCount + live.columns - 1) / live.columns
        if (rows > 0 && g.cellHeight <= 0) return null
        val content = base + if (rows == 0) 0 else rows * (g.cellHeight + gridRowSpacingPx) - gridRowSpacingPx
        val viewport = gridState.layoutInfo.viewportSize.height
        if (viewport <= 0) return null
        return (content + gridBottomPaddingPx - viewport).coerceAtLeast(0)
    }
    LaunchedEffect(gridState, leadingKeys, columns, gridRowSpacingPx) {
        var cellHeight = 0
        var viewportAtTop = 0
        var dirty = true
        snapshotFlow { gridState.layoutInfo }.collect { info ->
            info.visibleItemsInfo.forEach { item ->
                if (item.size.height <= 0) return@forEach
                if (item.index < leadingCount) {
                    if (leadingHeightByKey.put(item.key, item.size.height) != item.size.height) dirty = true
                } else if (cellHeight != item.size.height) {
                    cellHeight = item.size.height
                    dirty = true
                }
            }
            // The grid's viewport is taller once the tab bar has collapsed;
            // "fits at the top" must use the room the page has AT the top.
            if (gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 &&
                info.viewportSize.height > 0 && viewportAtTop != info.viewportSize.height
            ) {
                viewportAtTop = info.viewportSize.height
                dirty = true
            }
            if (dirty) {
                dirty = false
                val tops = IntArray(leadingCount + 1) { -1 }
                val heights = IntArray(leadingCount + 1) { -1 }
                var top = 0
                var i = 0
                while (i <= leadingCount) {
                    tops[i] = top
                    if (i == leadingCount) break
                    val h = leadingKeys.getOrNull(i)?.let { leadingHeightByKey[it] } ?: break
                    heights[i] = h
                    top += h + gridRowSpacingPx
                    i++
                }
                geometry.value = TvPageGeometry(
                    leadingTops = tops,
                    leadingHeights = heights,
                    cellHeight = cellHeight,
                    viewportAtTop = viewportAtTop,
                    rowSpacingPx = gridRowSpacingPx,
                    columns = columns,
                )
            }
            // No anchor: LEAVE the previous value. Writing Int.MAX_VALUE hid
            // the bar because we lost track of where we are (Movies spec D11).
            // This is the owner's once-per-frame publish of the live offset.
            scrollOffsetPx()?.let { chromeScroll?.value = it }
        }
    }
    DisposableEffect(Unit) { onDispose { chromeScroll?.value = 0 } }

    // The anchors every focusable sends, and the one coroutine that moves
    // the page. CONFLATED: a burst of presses collapses to the newest.
    val anchors = remember { kotlinx.coroutines.channels.Channel<TvPageAnchor>(kotlinx.coroutines.channels.Channel.CONFLATED) }
    val sendAnchor: (TvPageAnchor) -> Unit = remember(anchors) { { anchors.trySend(it) } }
    LaunchedEffect(gridState, density) {
        /** The smallest offset that leaves a row's BOTTOM 100 dp clear of the
         *  bottom edge. Every downward target is at least this: the tvOS Y
         *  positions were derived from tvOS row heights, and an Android row
         *  that is taller (the 2:3 Watchlist shelf, 269 dp against tvOS's
         *  163 dp) would otherwise sit with its text under the bottom edge
         *  (Logan 2026-09-11). */
        fun bottomClear(top: Int, height: Int, viewport: Int): Int = top + height - viewport + edgeMarginPx
        /** The minimum move that satisfies the margins: nothing at all when the
         *  row already sits between them (what the tvOS engine does). */
        fun minimumMove(current: Int, top: Int, height: Int, viewport: Int, topMargin: Int = edgeMarginPx): Int {
            val upper = top - topMargin
            val lower = top + height - viewport + edgeMarginPx
            return when {
                current > upper -> upper
                current < lower -> lower
                else -> current
            }
        }
        /** A downward hop only ever scrolls DOWN. The table positions describe
         *  arriving from above (the row was below its Y, or under the bottom
         *  edge); a row that is already comfortably on screen must not be
         *  dragged back up to its table position (Logan 2026-09-11: All pill,
         *  Search, All pill sent the page to the very top). */
        fun downTarget(current: Int, top: Int, height: Int, viewport: Int, table: Int): Int {
            val candidate = maxOf(table, bottomClear(top, height, viewport))
            return if (candidate > current) candidate else minimumMove(current, top, height, viewport)
        }
        /** The ONE table: every anchor's absolute target offset, or null when
         *  the geometry it needs is not measured yet. */
        fun targetFor(
            target: TvPageAnchor,
            g: TvPageGeometry,
            live: TvPageInputs,
            current: Int,
            viewport: Int,
            down: Boolean,
            spec: (Int, androidx.compose.animation.core.Easing) -> Unit,
        ): Int? = when (target) {
            is TvPageAnchor.Hero -> {
                // From deeper than one viewport the tvOS snap is the
                // long curve; from within one viewport it is the hop.
                if (current > viewport) spec(600, androidx.compose.animation.core.EaseInOut)
                0
            }
            is TvPageAnchor.Shelf -> {
                val index = 1 + (if (live.hasHero) 1 else 0) + target.ordinal
                val top = g.restTopOf(index)
                val height = g.leadingHeightOf(index)
                when {
                    top == null -> null
                    down -> downTarget(current, top, height ?: 0, viewport, top - yShelfPx)
                    target.ordinal == 0 -> {
                        // Up onto the first shelf: the page goes to the
                        // top when the whole shelf block fits under the
                        // hero at rest, else the shelf parks 86 dp down.
                        val room = g.viewportAtTop.takeIf { it > 0 } ?: viewport
                        if (height == null || top + height <= room) 0 else top - shelfUpGapPx
                    }
                    else -> minimumMove(current, top, height ?: 0, viewport)
                }
            }
            is TvPageAnchor.Header -> g.restTopOf(live.headerIndex)?.let { top ->
                val height = g.leadingHeightOf(live.headerIndex) ?: 0
                // Up onto the header: seat it 24 dp under the top edge only
                // when it is closer than that, never scroll back DOWN to the
                // seat (Logan 2026-09-11: All pill to Search nudged the page
                // the wrong way).
                if (down) downTarget(current, top, height, viewport, top - yHeaderPx)
                else minimumMove(current, top, height, viewport, topMargin = headerUpGapPx)
            }
            is TvPageAnchor.Pills -> {
                val index = live.leadingCount - 1
                val top = g.restTopOf(index)
                val height = g.leadingHeightOf(index)
                when {
                    top == null -> null
                    down -> downTarget(current, top, height ?: 0, viewport, top - yPillsPx)
                    // Up from row 0 onto the pills: the minimum move
                    // that leaves the pill row 100 dp clear of the top
                    // edge, never a snap.
                    else -> minimumMove(current, top, height ?: 0, viewport)
                }
            }
            is TvPageAnchor.GridRow -> {
                val index = live.leadingCount + target.row * live.columns
                val top = g.restTopOf(index)
                when {
                    top == null -> null
                    target.seatAtTop -> {
                        // Rail letter jump: the row seats at the top
                        // minus 24 dp, on the tvOS 0.25 s curve.
                        spec(250, androidx.compose.animation.core.EaseInOut)
                        top - headerUpGapPx
                    }
                    down && target.row == 0 -> downTarget(
                        current, top, g.cellHeight, viewport,
                        g.restTopOf(live.headerIndex) ?: (top - edgeMarginPx),
                    )
                    else -> minimumMove(current, top, g.cellHeight, viewport)
                }
            }
            is TvPageAnchor.Restore, is TvPageAnchor.Rail -> null
        }

        var running: kotlinx.coroutines.Job? = null
        var currentAnchor: TvPageAnchor? = null
        for (target in anchors) {
            // The rail letter jump moves the page AND then focuses the
            // letter's first cell; that cell's own focus callback sends a
            // plain GridRow for the same row mid-move. It is the same
            // destination, so it must not cancel the seat move.
            val active = currentAnchor
            if (running?.isActive == true && active is TvPageAnchor.GridRow && active.seatAtTop &&
                target is TvPageAnchor.GridRow && !target.seatAtTop && target.row == active.row
            ) continue
            running?.let { job -> job.cancel(); runCatching { job.join() } }
            val previous = currentAnchor
            currentAnchor = target
            running = launch {
                if (target is TvPageAnchor.Restore) {
                    // Return from a detail: put the page back where it was,
                    // instantly (tvOS never disposed the tab), through the
                    // owner so nothing else is animating at the same time.
                    runCatching { gridState.scrollToItem(target.index, target.offsetPx) }
                    return@launch
                }
                val down = target.order() > (previous?.order() ?: -1)
                var duration = 300
                var easing: androidx.compose.animation.core.Easing = androidx.compose.animation.core.EaseOut
                var goal = 0
                var current = 0
                var ready = false
                // ONE null policy: the rows this target depends on may not be
                // measured yet. Wait a frame for them, up to 10, then give up
                // silently. Never animateScrollToItem, never scrollToItem.
                var attempt = 0
                while (attempt < 10 && !ready) {
                    attempt++
                    val g = geometry.value
                    val live = inputsState.value
                    val now = if (g == null) null else scrollOffsetPx()
                    val viewport = gridState.layoutInfo.viewportSize.height
                    if (g == null || now == null || viewport <= 0) { withFrameNanos { }; continue }
                    duration = 300
                    easing = androidx.compose.animation.core.EaseOut
                    val raw: Int? = targetFor(target, g, live, now, viewport, down) { d, e -> duration = d; easing = e }
                    if (raw == null) { withFrameNanos { }; continue }
                    // Clamp only when a real limit is known; otherwise the
                    // bound guard in the animation does the job.
                    goal = raw.coerceAtLeast(0).let { g0 -> maxScrollPx()?.let { g0.coerceAtMost(it) } ?: g0 }
                    current = now
                    ready = true
                }
                if (!ready) return@launch
                if (goal == current) return@launch
                if (target is TvPageAnchor.Hero) {
                    // Bring the bar back before the page moves: one relayout,
                    // then a scroll over a page that no longer changes height.
                    chromeCollapsed?.value = false
                    chromeScroll?.value = 0
                    withFrameNanos { }
                }
                try {
                gridState.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) {
                    var last = 0f
                    androidx.compose.animation.core.animate(
                        initialValue = 0f,
                        targetValue = (goal - current).toFloat(),
                        animationSpec = tween(durationMillis = duration, easing = easing),
                    ) { value, _ ->
                        val delta = value - last
                        last = value
                        // A target past the bound would otherwise run the
                        // whole tween consuming nothing (report D section 7).
                        if (delta != 0f && scrollBy(delta) == 0f) throw TvScrollAtBound
                    }
                }
                } catch (_: TvScrollAtBound) {
                    // Reached the end of the content: stop, do not snap.
                }
            }
            // Warm the rows the next hop will need, without blocking it.
            when (target) {
                is TvPageAnchor.Pills -> com.aeriotv.android.ui.tv.tvPrefetchStrategyFor(gridState)
                    ?.requestLines(listOf(inputsState.value.leadingCount, inputsState.value.leadingCount + 1)) { }
                is TvPageAnchor.GridRow -> com.aeriotv.android.ui.tv.tvPrefetchStrategyFor(gridState)
                    ?.requestLines(listOf(inputsState.value.leadingCount + target.row + 1, inputsState.value.leadingCount + target.row + 2)) { }
                else -> Unit
            }
        }
    }
    val cellRequesters = remember { HashMap<Any, FocusRequester>() }
    var sortOpen by remember { mutableStateOf(false) }
    val menuGuard = rememberTvMenuGuard()

    // Down from the tab bar lands on the hero's primary button. tvOS does
    // it with an 8 pt catcher above the hero that forwards focus; the same
    // strip lives here as the grid's first item, so the shell's geometric
    // Down finds it first whatever pill it leaves from. (The shell's
    // requestFocus-and-cancel exit hook is NOT used: a request that lands
    // on a not-yet-built lazy item fails silently and the cancel then
    // strands focus in the bar.) Without a hero the first shelf card or
    // grid cell is the target.
    val firstShelfCard = remember { FocusRequester() }
    val entry = when {
        hasHero -> heroPrimary
        visibleShelves.isNotEmpty() -> firstShelfCard
        else -> firstCell
    }
    DisposableEffect(Unit) {
        tabEntry.value = null
        onDispose { }
    }

    // Closing search: park focus on the Search circle FIRST, then remove the
    // field. The order is load-bearing on tvOS (clearSearch,
    // MoviesView.swift:2452-2464): removing the field while it held focus
    // parked focus on the hero, whose scroll-to-top rule then fired.
    val searchCircle = remember { FocusRequester() }
    val closeOrToggleSearch: () -> Unit = {
        if (searchActive) runCatching { searchCircle.requestFocus() }
        onSearchToggle()
    }
    // Filter surface closing: focus returns to the Filter circle, which the
    // tvOS fullScreenCover got for free (MoviesView.swift:3503).
    val filterCircle = remember { FocusRequester() }
    val filterWasOpen = remember { mutableStateOf(false) }
    LaunchedEffect(filterOpen) {
        if (filterOpen) filterWasOpen.value = true
        else if (filterWasOpen.value) {
            filterWasOpen.value = false
            repeat(10) {
                if (runCatching { filterCircle.requestFocus() }.getOrNull() == true) return@LaunchedEffect
                withFrameNanos { }
            }
        }
    }

    // Tab bar: fully present until the content offset passes the threshold,
    // then hidden, and the hide is COMMITTED only once the scroll rests;
    // the way up returns it instantly (tvOS MoviesView.swift:1762-1787,
    // DVRView.swift:813-820, threshold :863). Nothing is proportional.
    val scrolled by remember(gridState) { derivedStateOf { gridState.firstVisibleItemIndex > 1 } }
    val barHideThresholdPx = with(density) { barHideThreshold.roundToPx() }
    if (chromeCollapsed != null) {
        LaunchedEffect(gridState, barHideThresholdPx) {
            snapshotFlow {
                ((scrollOffsetPx() ?: 0) >= barHideThresholdPx) to gridState.isScrollInProgress
            }.collect { (want, scrolling) ->
                if (!want) chromeCollapsed.value = false
                else if (!scrolling) chromeCollapsed.value = true
            }
        }
        DisposableEffect(Unit) { onDispose { chromeCollapsed.value = false } }
    }
    // BACK closes an open search first (tvOS: Menu closes the field before
    // anything else), then, while scrolled, snaps to the top and refocuses
    // the hero (the guide's ladder); at the top the handlers stand down so
    // BACK reaches the shell.
    androidx.activity.compose.BackHandler(enabled = LocalTabIsActive.current && searchEnabled && searchActive) {
        closeOrToggleSearch()
    }
    androidx.activity.compose.BackHandler(enabled = LocalTabIsActive.current && scrolled && !(searchEnabled && searchActive)) {
        scope.launch {
            // Through the owner like every other move; 40 frames because
            // the snap from deep in the library runs 600 ms.
            sendAnchor(TvPageAnchor.Hero)
            repeat(40) {
                if (runCatching { entry.requestFocus() }.isSuccess) return@launch
                withFrameNanos { }
            }
        }
    }

    // Return from a detail: scroll the opened cell's row in and refocus it
    // once the items exist; the initial-focus fallback (tab pill) lands
    // first, this takes focus back when the cell composes. Bounded at ~5 s.
    val hasItems = gridItems.isNotEmpty()
    val restoreGapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.roundToPx() }
    LaunchedEffect(returnKey, hasItems) {
        val key = returnKey ?: return@LaunchedEffect
        if (!hasItems) return@LaunchedEffect
        val idx = gridItems.indexOfFirst { gridKey(it) == key }
        if (idx < 0) { onReturnHandled(); return@LaunchedEffect }
        // Exact offset first (tvOS returns pixel-identical); the row park is
        // the fallback when the recorded index no longer exists after a
        // library change.
        val exact = returnOffset?.takeIf { it.first < leadingCount + gridItems.size }
        if (exact != null) {
            sendAnchor(TvPageAnchor.Restore(exact.first, exact.second))
        } else {
            // tvOS parks the row 24 pt under the top edge, not flush with it.
            sendAnchor(TvPageAnchor.Restore(leadingCount + (idx / columns) * columns, -restoreGapPx))
        }
        repeat(150) {
            withFrameNanos { }
            if (runCatching { cellRequesters[key]?.requestFocus() }.getOrNull() == true) {
                onReturnHandled()
                return@LaunchedEffect
            }
        }
        onReturnHandled()
    }

    // Rail: shown while the grid or the rail itself holds focus, once the
    // library is large enough to need it (tvOS: DVR needs more than 15).
    // The focused cell's key (null when focus is elsewhere). Tracked per key
    // so the old cell's focus-loss callback, which can fire AFTER the new
    // cell's gain, never clears the flag for a move inside the grid.
    // Read ONLY inside the rail slot below: every grid-cell focus change
    // writes focusedCellKey, and reading it here recomposed the whole page
    // (12 to 25 ms Compose spikes at the start of each press in the
    // Streamer frame stats, 2026-09-10).
    val focusedCellKeyState = remember { mutableStateOf<Any?>(null) }
    val railHasFocusState = remember { mutableStateOf(false) }
    // tvOS Movies hides the rail while searching (MoviesView.swift:1854);
    // DVR has no such gate (DVRView.swift:517), hence the parameter.
    val railAllowed = (!isSearching || !hideRailWhileSearching) &&
        railLetters.isNotEmpty() && gridItems.size >= railMinimumCount
    /** The rail's '#' cell: Left from a first-column grid cell lands here. */
    val railHash = remember { FocusRequester() }
    val railShownState = remember { mutableStateOf(false) }

    TvKeyboardOnOkHost {
    Box(modifier = Modifier.fillMaxSize()) {
        // The owner moves the page, so the grid's own bring-into-view is off
        // everywhere: it was the second mover behind every "notchy" hop
        // (reports C section 6, D section 1a). Deeper grid rows are handled
        // by the owner's GridRow rule, so it stays off there too. The shelf
        // and pill LazyRows re-provide their OWN horizontal specs below.
        CompositionLocalProvider(LocalBringIntoViewSpec provides TvNoBringIntoViewSpec) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier
                .fillMaxSize(),
            // The library cells sit at the content inset; the full-width rows
            // bleed back over this start padding (fullSpan below) so every
            // cell measures the same width. Padding only the first column
            // made column-one posters narrower (Logan 2026-09-10).
            // The grid spans the screen so shelves can scroll under both edges
            // (tvOS; Logan 2026-09-10): the overscan lives in this padding and
            // the full-width rows bleed over it.
            // Columns line up with the header and the shelves (tvOS: grid at
            // 142 pt = 71 dp, header and shelf at 140 pt = 70 dp;
            // MoviesView.swift:2083-2089, :2597). start 70 + end 48 leaves
            // 960 - 118 = 842 dp, exactly 7 x 110 dp with 6 x 12 dp spacing.
            contentPadding = PaddingValues(start = TvPage.overscan + TvPage.contentInset, end = TvPage.overscan + TvPage.heroInset, top = 0.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(gridRowSpacing),
            horizontalArrangement = Arrangement.spacedBy(TvPage.columnSpacing),
        ) {
            fullSpan("catcher") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .onFocusChanged {
                            if (it.isFocused) {
                                scope.launch {
                                    repeat(6) {
                                        if (runCatching { entry.requestFocus() }.getOrNull() == true) return@launch
                                        withFrameNanos { }
                                    }
                                }
                            }
                        }
                        .focusable(),
                )
            }
            if (hasHero) {
                // The hero's own 14 dp section gap plus the grid's row spacing
                // doubled the gap under it (tvOS has one 28 pt gap), pushing
                // the first shelf's posters off the bottom at rest (Logan
                // 2026-09-10). The hero row gives back the row spacing plus
                // 10 dp so the shelf sits fully in view.
                fullSpan("hero", trimBottom = gridRowSpacing + 10.dp) {
                    TvHeroCarousel(
                        pages = heroPages,
                        primaryRequester = heroPrimary,
                        upTarget = topNav,
                        // tvOS: any hero button gaining focus while the page
                        // is scrolled snaps the page back to the top.
                        onButtonFocused = { sendAnchor(TvPageAnchor.Hero) },
                        modifier = Modifier.padding(start = TvPage.overscan, end = TvPage.overscan, bottom = TvPage.sectionSpacing),
                    )
                }
            }
            visibleShelves.forEachIndexed { si, shelf ->
                fullSpan("shelf:" + shelf.title) {
                    TvShelfRow(
                        shelf = shelf,
                        firstCardRequester = if (si == 0) firstShelfCard else null,
                        // tvOS: a card of the FIRST shelf gaining focus while
                        // the page is scrolled brings the page to the top so
                        // the hero is fully back (Apple TV Up walk 2026-09-10).
                        // The snap to the top only when focus ENTERS the
                        // shelf from below; Left and Right inside it must not
                        // move the page (rapid-press recording 2026-09-10:
                        // each press nudged the page down and snapped it up).
                        onCardFocused = { sendAnchor(TvPageAnchor.Shelf(si)) },
                        modifier = Modifier.padding(bottom = TvPage.sectionSpacing),
                    )
                }
            }
            fullSpan("header") {
                Column(
                    modifier = Modifier
                        .padding(start = TvPage.overscan + TvPage.contentInset, end = TvPage.overscan + TvPage.heroInset)
                        .onFocusChanged { if (it.hasFocus) sendAnchor(TvPageAnchor.Header) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            headerTitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground, maxLines = 1,
                        )
                        Text(
                            headerCount.toString(), fontSize = 10.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(end = 5.dp),
                        )
                        if (searchEnabled && searchActive) {
                            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                            TvSearchCapsule(
                                query = query,
                                onQueryChange = onQueryChange,
                                placeholder = searchPlaceholder,
                                // The IME's search key closes the keyboard and
                                // drops focus onto the results.
                                onSearch = {
                                    keyboard?.hide()
                                    focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                                },
                            )
                        }
                        if (searchEnabled) {
                            TvActionCircle(
                                icon = Icons.Filled.Search, contentDescription = if (searchActive) "Close search" else "Search",
                                selected = searchActive, onClick = closeOrToggleSearch,
                                modifier = Modifier.focusRequester(searchCircle),
                            )
                            if (isSearching) {
                                TvActionCircle(icon = Icons.Filled.Close, contentDescription = "Clear search", onClick = onClearSearch)
                            }
                        }
                        if (sortActions.isNotEmpty()) {
                            TvActionCircle(icon = Icons.Filled.SwapVert, contentDescription = "Sort", onClick = { sortOpen = true })
                        }
                        if (onFilter != null) {
                            TvActionCircle(
                                icon = Icons.Filled.FilterList, contentDescription = "Filter",
                                selected = filterActive, onClick = onFilter,
                                modifier = Modifier.focusRequester(filterCircle),
                            )
                        }
                    }
                    // The row's space is RESERVED whether or not the spinner
                    // is showing: the header changing height mid-walk moved
                    // every rest position below it by 26 dp and the owner's
                    // targets went with it (report B item 9).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp).height(12.dp),
                    ) {
                        if (isLoading && gridItems.isNotEmpty()) {
                            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(6.dp))
                            Text("Updating", fontSize = 9.sp, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
            if (isSearching) {
                searchExtras.forEachIndexed { i, extra ->
                    fullSpan("extra:$i") {
                        Box(modifier = Modifier.padding(start = TvPage.overscan + TvPage.contentInset, top = 6.dp)) { extra() }
                    }
                }
            }
            if (pillRow) {
                fullSpan("pills") {
                    // Its own HORIZONTAL spec: inheriting the page's vertical
                    // one made an off-screen pill unreachable (report C 4.3).
                    val pillSpec = with(androidx.compose.ui.platform.LocalDensity.current) {
                        remember(this) { com.aeriotv.android.ui.tv.TvHorizontalBringIntoViewSpec(marginPx = (TvPage.overscan + TvPage.contentInset).toPx()) }
                    }
                    CompositionLocalProvider(LocalBringIntoViewSpec provides pillSpec) {
                    // Entering the row from the sort circle above or the grid
                    // below always lands on All (Logan 2026-09-10).
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(start = TvPage.overscan + TvPage.contentInset, end = TvPage.overscan + TvPage.heroInset),
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .fillMaxWidth()
                            .onFocusChanged { if (it.hasFocus) sendAnchor(TvPageAnchor.Pills) }
                            .focusProperties {
                                @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                                run { enter = { allPill } }
                            }
                            .focusGroup(),
                    ) {
                        item(key = "all") { TvPill("All", selectedPill == null, onClick = { onPill(null) }, modifier = Modifier.focusRequester(allPill)) }
                        items(pills.size, key = { pills[it] }) { i ->
                            val p = pills[i]
                            TvPill(p, selectedPill == p, onClick = { onPill(if (selectedPill == p) null else p) })
                        }
                    }
                    }
                }
            }
            if (gridItems.isEmpty()) {
                fullSpan("empty") {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { emptyContent() }
                }
            }
            itemsIndexed(items = gridItems, key = { _, it -> gridKey(it) }) { index, item ->
                val k = gridKey(item)
                val requester = remember(k) { cellRequesters.getOrPut(k) { FocusRequester() } }
                val cellModifier = Modifier
                    .focusRequester(requester)
                    .then(if (index == 0) Modifier.focusRequester(firstCell) else Modifier)
                    // Top row: Up lands on the All pill, not whatever the
                    // geometric search picks above the row (it skipped the
                    // pill group and reached the sort circle, Logan 2026-09-10).
                    .then(if (pillRow && index < columns) Modifier.focusProperties { up = allPill } else Modifier)
                    .onFocusChanged {
                        if (it.isFocused) { focusedCellKeyState.value = k; sendAnchor(TvPageAnchor.GridRow(index / columns)) }
                        else if (focusedCellKeyState.value == k) focusedCellKeyState.value = null
                    }
                    .onPreviewKeyEvent { ev ->
                        // Left off the first column reaches the rail's '#'
                        // deterministically instead of relying on the
                        // geometric search (Movies spec D8). Only while the
                        // rail is on screen; otherwise the event passes.
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft &&
                            index % columns == 0 && railShownState.value
                        ) {
                            if (runCatching { railHash.requestFocus() }.getOrNull() == true) return@onPreviewKeyEvent true
                        }
                        vodGridDpadFallback(ev, index, gridItems.size, gridState, focusManager, scope) { i ->
                            cellRequesters.getOrPut(gridKey(gridItems[i])) { FocusRequester() }
                        }
                    }
                cell(item, TvCellScope(modifier = cellModifier, focusRequester = cellReturnRequester(item)))
            }
        }
        }

        // tvOS parks the rail at the grid's top edge, so it only reads once
        // the library has scrolled to the top of the screen; here the rail
        // waits for the header line to reach the top (Logan 2026-09-10).
        val railVisible by remember(railAllowed, headerIndex) {
            derivedStateOf {
                railAllowed && gridState.firstVisibleItemIndex >= headerIndex &&
                    (focusedCellKeyState.value != null || railHasFocusState.value)
            }
        }
        // 200 ms hide grace (tvOS MoviesView.swift:2160-2175,
        // DVRView.swift:639-651): the grid to rail handoff clears grid focus
        // first, and without the grace the rail blinks mid-hop.
        LaunchedEffect(railVisible) {
            if (railVisible) railShownState.value = true
            else { kotlinx.coroutines.delay(200); if (!railVisible) railShownState.value = false }
        }
        TvRailSlot(
            visible = { railShownState.value },
            // tvOS parks the rail 80 pt (40 dp) BELOW vertical centre: the eye
            // reads it against the poster rows, which start below the top edge
            // (MoviesView.swift:2143-2151).
            modifier = Modifier.align(Alignment.CenterStart).offset(y = 40.dp),
        ) {
            TvAlphabetRail(
                available = railLetters,
                hashRequester = railHash,
                onFocusChanged = { railHasFocusState.value = it },
                // tvOS: the click scrolls at 0.25 s easeInOut and 0.45 s later
                // moves focus onto the letter's first card, so the press lands
                // the user in the grid (DVRView.swift:615-635,
                // MoviesView.swift:1912-1952). Right goes back to the last
                // poster, Up from # to the hero.
                onLetter = { letter ->
                    val idx = railIndexOf(letter)
                    if (idx >= 0) scope.launch {
                        // The move goes through the owner (seat the row at the
                        // top minus 24 dp, 250 ms EaseInOut); focus follows it.
                        sendAnchor(TvPageAnchor.GridRow(idx / columns, seatAtTop = true))
                        val key = gridItems.getOrNull(idx)?.let(gridKey)
                        if (key != null) repeat(30) {
                            if (runCatching { cellRequesters[key]?.requestFocus() }.getOrNull() == true) return@launch
                            withFrameNanos { }
                        }
                    }
                },
                onExitRight = {
                    val key = focusedCellKeyState.value
                    val last = key?.let { cellRequesters[it] }
                    if (last == null || runCatching { last.requestFocus() }.getOrNull() != true) {
                        runCatching { firstCell.requestFocus() }
                    }
                },
                onExitUp = { runCatching { entry.requestFocus() } },
                modifier = Modifier.padding(start = 17.dp),
            )
        }
    }
    }

    if (sortOpen) {
        TvActionMenuDialog(title = "Sort", actions = sortActions, guard = menuGuard, onDismiss = { sortOpen = false })
    }
}

/** Its own recomposition scope: the rail shows and hides without touching the page. */
@Composable
private fun TvRailSlot(visible: () -> Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // tvOS: show .easeOut(0.3), hide .easeIn(0.2), both .move(edge: .leading)
    // + .opacity (MoviesView.swift:2160-2175, DVRView.swift:639-651).
    androidx.compose.animation.AnimatedVisibility(
        visible = visible(),
        modifier = modifier,
        enter = androidx.compose.animation.slideInHorizontally(
            animationSpec = tween(durationMillis = 300, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
        ) { -it } + androidx.compose.animation.fadeIn(animationSpec = tween(durationMillis = 300)),
        exit = androidx.compose.animation.slideOutHorizontally(
            animationSpec = tween(durationMillis = 200, easing = androidx.compose.animation.core.FastOutLinearInEasing),
        ) { -it } + androidx.compose.animation.fadeOut(animationSpec = tween(durationMillis = 200)),
    ) { content() }
}

/** A full-width grid row that reclaims the grid's start inset (see contentPadding in TvMediaPage). */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.fullSpan(
    key: Any,
    /** Height the row gives back so the next row sits closer (the grid's row spacing still applies). */
    trimBottom: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        val bleedStart = TvPage.overscan + TvPage.contentInset
        val bleedEnd = TvPage.overscan + TvPage.heroInset
        Box(
            modifier = Modifier.layout { measurable, constraints ->
                val extraStart = bleedStart.roundToPx()
                val extra = extraStart + bleedEnd.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(
                        minWidth = (constraints.minWidth + extra).coerceAtMost(constraints.maxWidth + extra),
                        maxWidth = constraints.maxWidth + extra,
                    ),
                )
                val height = (placeable.height - trimBottom.roundToPx()).coerceAtLeast(0)
                layout(constraints.maxWidth, height) { placeable.place(-extraStart, 0) }
            },
        ) { content() }
    }
}

// MARK: hero

@Composable
private fun TvHeroCarousel(
    pages: List<TvHeroPage>,
    primaryRequester: FocusRequester,
    upTarget: FocusRequester?,
    onButtonFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    if (index > pages.lastIndex) index = 0
    // Not BoxWithConstraints: that subcomposes BOTH hero cards on every
    // measure pass, which on a scrolling LazyVerticalGrid meant rebuilding
    // the hero every frame (11 to 14 ms of Compose per frame in the
    // Streamer frame stats, 2026-09-10). The width is measured once.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    Box(modifier = modifier.fillMaxWidth().onSizeChanged { if (it.width != widthPx) widthPx = it.width }) {
        val maxWidth = with(density) { widthPx.toDp() }
        val spacing = 4.dp
        val pageWidth = if (pages.size > 1) maxWidth * 0.62f else maxWidth
        val shift by animateDpAsState(
            targetValue = -(pageWidth + spacing) * index,
            animationSpec = tween(350),
            label = "tvHeroShift",
        )
        Column {
            // No scroll container (tvOS rule): an offset row of pages, the
            // focused button's page wins, edge catchers are not needed
            // because focus search walks straight into the next page's
            // first button.
            // The row must be as wide as ALL pages: a Row measures each
            // child into the width left over, so with the parent's width
            // page 1 shrank and pages 2 and 3 collapsed to nothing (blank
            // hero on the Streamer, 2026-09-10). requiredWidth overrides the
            // incoming constraint; wrapContentWidth(unbounded) keeps the
            // oversize row anchored at the start instead of centered.
            val total = pageWidth * pages.size + spacing * (pages.size - 1).coerceAtLeast(0)
            Row(
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .requiredWidth(total)
                    .offset(x = shift),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                pages.forEachIndexed { i, page ->
                    TvHeroCard(
                        page = page,
                        width = pageWidth,
                        primaryRequester = if (i == index) primaryRequester else null,
                        upTarget = upTarget,
                        onButtonFocused = { index = i; onButtonFocused() },
                    )
                }
            }
            if (pages.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    pages.indices.forEach { i ->
                        Box(
                            modifier = Modifier.size(5.dp).clip(CircleShape).background(
                                if (i == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvHeroCard(
    page: TvHeroPage,
    width: Dp,
    primaryRequester: FocusRequester?,
    upTarget: FocusRequester?,
    onButtonFocused: () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.background
    var menuOpen by remember { mutableStateOf(false) }
    val guard = rememberTvMenuGuard()
    Box(
        modifier = Modifier
            .semantics(mergeDescendants = true) {}
            .width(width)
            .height(TvPage.heroHeight)
            .padding(horizontal = TvPage.heroInset),
    ) {
        // Only the ART is clipped to the rounded shape; the fades and copy
        // draw unclipped over it. Clipping the whole stack left a faint
        // seam along the corner where the art showed through the
        // anti-aliased edge (tvOS b3cd364; Logan 2026-09-10 on the Streamer).
        Box(
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface),
        ) {
            if (!page.artUrl.isNullOrBlank()) {
                AsyncImage(model = sizedArt(page.artUrl, width - TvPage.heroInset * 2, TvPage.heroHeight), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (!page.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = page.logoUrl, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(0.28f).fillMaxHeight().align(Alignment.CenterEnd).padding(14.dp).alpha(0.9f),
                )
            }
        }
        // tvOS fades the art with two masks; the same stops painted as
        // background-colored gradients.
        // Fill rate on the Streamer (frame stats 2026-09-10: 10 to 12 ms
        // of GPU per static frame, 18 to 22 while scrolling): the fades are
        // drawn only where they are not transparent. The leading fade is
        // opaque across its first 12 percent, so that band is a plain fill
        // and the blend runs over the remaining 78 percent up to the 0.9
        // stop; the bottom fade covers only its lower 45 percent.
        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(0.12f).background(bg))
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.9f)
                .padding(start = 0.dp)
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color.Transparent, 0.133f to bg, 0.42f to bg.copy(alpha = 0.92f), 0.78f to bg.copy(alpha = 0.35f), 1.0f to bg.copy(alpha = 0.08f),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .background(Brush.verticalGradient(0.0f to Color.Transparent, 1.0f to bg)),
        )
        Column(
            // tvOS copySpacing 12 pt / copyInset 44 pt halved is 6 / 22; the
            // Roboto line heights run taller than SF, so 4 / 18 keeps the
            // eyebrow, title, subtitle, meta, three plot lines and the 30 dp
            // button row inside the 210 dp card without clipping any of
            // them (Logan 2026-09-10: the plot was cut mid-line).
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 18.dp).widthIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            page.eyebrow?.let { eyebrow ->
                val c = if (page.eyebrowColor == Color.Unspecified) MaterialTheme.colorScheme.primary else page.eyebrowColor
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (page.eyebrowDot) Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(c))
                    Text(eyebrow, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, color = c)
                }
            }
            Text(
                page.title, fontSize = 26.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            page.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (page.meta.isNotEmpty() || !page.rating.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        page.meta.joinToString(" · "), fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    page.rating?.takeIf { it.isNotBlank() }?.let { r ->
                        Text(
                            (if (page.meta.isEmpty()) "" else " · ") + "★ $r", fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1,
                        )
                    }
                }
            }
            page.plot?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                // requiredHeight: an overflowing copy column once squeezed
                // this row (pills at 23 dp, the options circle an oval);
                // the row can never be squeezed again.
                modifier = Modifier.padding(top = 2.dp).requiredHeight(30.dp),
            ) {
                // The hero menu is the right-most options circle, not a
                // long press on Resume (Logan 2026-09-10, all platforms).
                page.buttons.forEachIndexed { i, b ->
                    TvHeroButtonView(
                        button = b,
                        modifier = Modifier
                            .then(if (b.primary && primaryRequester != null) Modifier.focusRequester(primaryRequester) else Modifier)
                            .then(if (upTarget != null) Modifier.focusProperties { up = upTarget } else Modifier)
                            .onFocusChanged { if (it.isFocused) onButtonFocused() },
                    )
                }
                if (page.longPressActions.isNotEmpty()) {
                    // The nav circles' size and shape (30 dp), the hero
                    // pills' colour and ring (Logan 2026-09-10).
                    TvHeroButtonView(
                        button = TvHeroButton("", Icons.Filled.MoreHoriz, onClick = { menuOpen = true }),
                        modifier = Modifier
                            .then(if (upTarget != null) Modifier.focusProperties { up = upTarget } else Modifier)
                            .onFocusChanged { if (it.isFocused) onButtonFocused() },
                    )
                }
            }
        }
    }
    if (menuOpen) {
        TvActionMenuDialog(title = page.title, actions = page.longPressActions, guard = guard, onDismiss = { menuOpen = false })
    }
}

/** tvOS MoviesHeroButton: 30 dp capsule, 13 dp side padding, white ring on the primary and accent ring on the rest. */
@Composable
fun TvHeroButtonView(
    button: TvHeroButton,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val fill = if (button.primary) colors.primary else colors.surfaceVariant
    val ink = if (button.primary) colors.onPrimary else colors.onSurface
    Row(
        modifier = modifier
            .tvFocusScale(focused, focusedScale = 1.04f)
            // required: a squeezed parent must not flatten the capsule.
            .then(if (button.label.isEmpty()) Modifier.requiredSize(30.dp) else Modifier.requiredHeight(30.dp))
            .clip(CircleShape)
            .background(fill)
            .border(
                width = TvChrome.ringWidth,
                color = when {
                    !focused -> Color.Transparent
                    button.primary -> Color.White
                    else -> colors.primary
                },
                shape = CircleShape,
            )
            .combinedClickable(interactionSource = interaction, indication = null, onClick = button.onClick, onLongClick = onLongClick)
            // tvOS MoviesHeroButton: an icon-only button is a 30 dp circle (14 pt sides halved).
            .padding(horizontal = if (button.label.isEmpty()) 0.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (button.label.isEmpty()) Arrangement.Center else Arrangement.spacedBy(4.dp),
    ) {
        Icon(button.icon, contentDescription = if (button.label.isEmpty()) "Options" else null, tint = ink, modifier = Modifier.size(if (button.label.isEmpty()) 12.dp else 11.dp))
        if (button.label.isNotEmpty()) Text(button.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
    }
}

// MARK: shelves

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun <T> TvShelfRow(
    shelf: TvShelf<T>,
    firstCardRequester: FocusRequester?,
    onCardFocused: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            shelf.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = TvPage.overscan + TvPage.contentInset, bottom = 4.dp),
        )
        // All the shelf's on-screen cards are prepared while the shelf is
        // still below the fold (the default prefetches two).
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        val rowState = androidx.compose.foundation.lazy.rememberLazyListState(
            prefetchStrategy = remember { androidx.compose.foundation.lazy.LazyListPrefetchStrategy(nestedPrefetchItemCount = 6) },
        )
        // Its own horizontal spec: the page's spec holds the page still while
        // a shelf card has focus, which also stopped the ROW from bringing a
        // partly visible card in (Logan 2026-09-10). The card is kept a
        // content inset clear of the row's edges, minimal distance.
        val rowSpec = with(androidx.compose.ui.platform.LocalDensity.current) {
            remember(this) { com.aeriotv.android.ui.tv.TvHorizontalBringIntoViewSpec(marginPx = (TvPage.overscan + TvPage.contentInset).toPx()) }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(TvPage.columnSpacing),
            contentPadding = PaddingValues(start = TvPage.overscan + TvPage.contentInset, end = TvPage.overscan + TvPage.heroInset, top = 6.dp, bottom = 6.dp),
        ) {
            items(shelf.items.size, key = { shelf.key(shelf.items[it]) }) { i ->
                shelf.card(
                    shelf.items[i],
                    Modifier
                        .width(shelf.cardWidth)
                        .then(if (i == 0 && firstCardRequester != null) Modifier.focusRequester(firstCardRequester) else Modifier)
                        .then(if (onCardFocused != null) Modifier.onFocusChanged { if (it.hasFocus) onCardFocused() } else Modifier),
                )
            }
        }
        }
    }
}

// MARK: cards

/**
 * Art decoded at its DISPLAY size. Coil's default precision keeps a
 * 1280 or 1920 px backdrop at full size when the target is 1100 px, and
 * the Streamer's GPU then samples those textures every frame (hero on
 * screen: 16 ms of GPU per frame vs 5.5 without it, frame stats
 * 2026-09-10). EXACT precision scales once on decode.
 */
/** [AsyncImage] that decodes at the size it is laid out at (see [sizedArt]). */
@Composable
private fun SizedArtImage(url: String, contentDescription: String?, contentScale: ContentScale, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Box(modifier = modifier.onSizeChanged { if (it != size) size = it }) {
        if (size.width > 0 && size.height > 0) {
            val request = remember(url, size) {
                coil3.request.ImageRequest.Builder(context)
                    .data(url)
                    .size(coil3.size.Size(size.width, size.height))
                    .precision(coil3.size.Precision.EXACT)
                    .build()
            }
            AsyncImage(model = request, contentDescription = contentDescription, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun sizedArt(url: String, width: Dp, height: Dp): coil3.request.ImageRequest {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(url, width, height, density) {
        val w = with(density) { width.roundToPx() }.coerceAtLeast(1)
        val h = with(density) { height.roundToPx() }.coerceAtLeast(1)
        coil3.request.ImageRequest.Builder(context)
            .data(url)
            .size(coil3.size.Size(w, h))
            .precision(coil3.size.Precision.EXACT)
            .build()
    }
}

/**
 * tvOS VODPosterCard: 2:3 art with 4 dp corners, a 2 dp accent ring and a
 * 1.08 scale while focused, rating badge bottom-end, two centered title
 * lines (fixed height) and the year. Long press opens [longPressActions].
 */
@Composable
fun TvPosterCard(
    title: String,
    year: Int?,
    posterUrl: String?,
    rating: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    longPressActions: List<TvMenuAction> = emptyList(),
    /** Extra overlay on the art (REC badge, progress bar). */
    overlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val guard = rememberTvMenuGuard()
    Column(
        modifier = modifier
            // One accessibility node per card: with an accessibility service
            // listening (tvQuickActions on the Streamer subscribes to every
            // event) Compose diffs the semantics tree every frame; merged
            // cards keep that tree small (trace 2026-09-10).
            .semantics(mergeDescendants = true) {}
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusScale(focused)
            .combinedClickable(
                interactionSource = interaction, indication = null, onClick = onClick,
                onLongClick = if (longPressActions.isNotEmpty()) ({ menuOpen = true }) else null,
            ),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 2.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                ),
        ) {
            if (!posterUrl.isNullOrBlank()) {
                SizedArtImage(posterUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (rating.isNotEmpty()) {
                Text(
                    rating, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            overlay?.invoke(this)
        }
        Text(
            title, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(22.dp),
        )
        Text(
            year?.toString() ?: " ", fontSize = 8.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            maxLines = 1, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        )
    }
    if (menuOpen) {
        TvActionMenuDialog(title = title, actions = longPressActions, guard = guard, onDismiss = { menuOpen = false })
    }
}

/**
 * tvOS DVRRecordingCard: 16:9 art with 6 dp corners and a 2 dp accent ring,
 * a bottom band with the channel logo or name and the duration, a 2 dp
 * progress bar, a REC / day / Partial badge top-start, then the centered
 * title and meta line.
 */
@Composable
fun TvRecordingCard(
    title: String,
    meta: String,
    artUrl: String?,
    logoUrl: String?,
    channelName: String,
    trailing: String,
    progress: Float,
    badge: TvRecordingBadge?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    longPressActions: List<TvMenuAction> = emptyList(),
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val guard = rememberTvMenuGuard()
    Column(
        modifier = modifier
            // One accessibility node per card: with an accessibility service
            // listening (tvQuickActions on the Streamer subscribes to every
            // event) Compose diffs the semantics tree every frame; merged
            // cards keep that tree small (trace 2026-09-10).
            .semantics(mergeDescendants = true) {}
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusScale(focused)
            .combinedClickable(
                interactionSource = interaction, indication = null, onClick = onClick,
                onLongClick = if (longPressActions.isNotEmpty()) ({ menuOpen = true }) else null,
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(6.dp)),
        ) {
            when {
                !artUrl.isNullOrBlank() -> SizedArtImage(artUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                !logoUrl.isNullOrBlank() -> AsyncImage(model = logoUrl, contentDescription = title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(14.dp).alpha(0.9f))
                else -> Text(title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 3, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(45.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = if (progress > 0f) 6.dp else 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!logoUrl.isNullOrBlank() && !artUrl.isNullOrBlank()) {
                        AsyncImage(model = logoUrl, contentDescription = channelName, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 36.dp, height = 18.dp), alignment = Alignment.CenterStart)
                    } else if (channelName.isNotBlank()) {
                        Text(channelName, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(trailing, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                }
            }
            if (progress > 0f) {
                Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(MaterialTheme.colorScheme.primary))
                }
            }
            badge?.let { b ->
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(CircleShape).background(b.fill).padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (b.dot) Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(Color.White))
                    b.icon?.let { Icon(it, contentDescription = null, tint = Color.White, modifier = Modifier.size(8.dp)) }
                    Text(b.label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                }
            }
        }
        Text(
            title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            meta.ifBlank { " " }, fontSize = 9.sp, color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        )
    }
    if (menuOpen) {
        TvActionMenuDialog(title = title, actions = longPressActions, guard = guard, onDismiss = { menuOpen = false })
    }
}

data class TvRecordingBadge(
    val label: String,
    val fill: Color,
    val dot: Boolean = false,
    val icon: ImageVector? = null,
)

// MARK: rail

/**
 * tvOS AlphabetRail: # then A to Z in 16 dp cells, 36 dp wide, focused
 * letter on an accent-25% disc with a white ring; unavailable letters at
 * 35% of the tertiary text color. Entering seats on #, Right leaves.
 */
@Composable
private fun TvAlphabetRail(
    available: Set<Char>,
    /** Hoisted so Left from a first-column grid cell can aim at it. */
    hashRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onLetter: (Char) -> Unit,
    /** tvOS: Right on a letter leaves the rail back to the last poster. */
    onExitRight: () -> Unit,
    /** tvOS: Up from # leaves the rail to the hero. */
    onExitUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val letters = remember { listOf('#') + ('A'..'Z').toList() }
    val hash = hashRequester
    Column(
        modifier = modifier
            .width(36.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            // focusProperties must precede focusGroup to apply to the group.
            .focusProperties { onEnter = { hash.requestFocus() } }
            .focusGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            val isAvailable = letter in available
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .then(if (letter == '#') Modifier.focusRequester(hash) else Modifier)
                    .clip(CircleShape)
                    .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                    .border(1.dp, if (focused) Color.White else Color.Transparent, CircleShape)
                    .focusable(interactionSource = interaction)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.DirectionCenter, Key.Enter -> {
                                // Unavailable letters resolve to the nearest one after, else before.
                                val target = when {
                                    isAvailable -> letter
                                    else -> letters.drop(letters.indexOf(letter)).firstOrNull { it in available }
                                        ?: letters.take(letters.indexOf(letter)).lastOrNull { it in available }
                                }
                                if (target != null) onLetter(target)
                                true
                            }
                            Key.DirectionRight -> { onExitRight(); true }
                            Key.DirectionUp -> if (letter == '#') { onExitUp(); true } else false
                            else -> false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Explicit line height: the theme's 24 sp made the glyph
                // overflow its 16 dp cell and sit below the ring (Logan
                // 2026-09-10).
                Text(
                    letter.toString(), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = when {
                        focused -> Color.White
                        isAvailable -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
                    },
                )
            }
        }
    }
}

// MARK: single-owner scrolling types

/**
 * What the page should be showing. Every focusable sends one of these on
 * focus gain and does nothing else; the owner coroutine in [TvMediaPage]
 * turns it into ONE absolute scroll target. [Rail] carries no position: the
 * alphabet rail is outside the grid and focusing it never scrolls (tvOS
 * keeps the rail a sibling of the ScrollView).
 */
sealed interface TvPageAnchor {
    data object Hero : TvPageAnchor
    data class Shelf(val ordinal: Int) : TvPageAnchor
    data object Header : TvPageAnchor
    data object Pills : TvPageAnchor
    /** [seatAtTop]: the rail letter jump, which parks the row 24 dp under the top edge. */
    data class GridRow(val row: Int, val seatAtTop: Boolean = false) : TvPageAnchor
    /** Return from a detail: put the page back at an exact (index, offset). */
    data class Restore(val index: Int, val offsetPx: Int) : TvPageAnchor
    data object Rail : TvPageAnchor
}

/** Walk order, so the owner knows whether a hop is going down or up. */
internal fun TvPageAnchor.order(): Int = when (this) {
    is TvPageAnchor.Hero -> 0
    is TvPageAnchor.Shelf -> 1 + ordinal
    is TvPageAnchor.Header -> 100
    is TvPageAnchor.Pills -> 101
    is TvPageAnchor.GridRow -> 200 + row
    // Past every row: the next hop after a restore or a rail focus counts as
    // going UP, so it resolves to the minimum move and leaves an exact
    // restore exactly where it was (no re-seating of the header).
    is TvPageAnchor.Restore -> Int.MAX_VALUE
    is TvPageAnchor.Rail -> Int.MAX_VALUE
}

/** The animation hit the end of the content: stop it, do not snap. */
internal object TvScrollAtBound : kotlinx.coroutines.CancellationException("scroll bound")

/** Inputs the owner reads that change with the caller's data, not with the grid state. */
internal data class TvPageInputs(
    val headerIndex: Int,
    val leadingCount: Int,
    val columns: Int,
    val itemCount: Int,
    val hasHero: Boolean,
    val shelfCount: Int,
)

/**
 * One immutable measurement of the page, published as a single State: rest
 * tops of the full-span rows by emission index (-1 = not measured yet), the
 * grid cell height, and the viewport height with the page at the top. Every
 * scroll target is a pure function of this, so no consumer can see half a
 * table.
 */
internal class TvPageGeometry(
    val leadingTops: IntArray,
    val leadingHeights: IntArray,
    val cellHeight: Int,
    val viewportAtTop: Int,
    val rowSpacingPx: Int,
    val columns: Int,
) {
    /** Rest top of the first grid row, or null while the leading block is unmeasured. */
    val gridTopPx: Int? get() = leadingTops.lastOrNull()?.takeIf { it >= 0 }

    fun leadingHeightOf(index: Int): Int? = leadingHeights.getOrNull(index)?.takeIf { it >= 0 }

    /** Where item [index] sits with the page at the top; null when unknowable. */
    fun restTopOf(index: Int): Int? {
        if (index < leadingTops.size) return leadingTops.getOrNull(index)?.takeIf { it >= 0 }
        val base = gridTopPx ?: return null
        if (cellHeight <= 0 || columns <= 0) return null
        return base + ((index - (leadingTops.size - 1)) / columns) * (cellHeight + rowSpacingPx)
    }
}

/** The page owns every vertical move: nothing inside the grid may scroll it. */
@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal object TvNoBringIntoViewSpec : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}
