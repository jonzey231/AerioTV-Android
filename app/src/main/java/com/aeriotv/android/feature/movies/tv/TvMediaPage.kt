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
    onReturnHandled: () -> Unit = {},
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
    // A focus-driven scroll to the top: the bring-into-view spec stands down
    // while it runs (its own request otherwise raced the snap and left the
    // hero a third off screen, Logan 2026-09-10) and a hard snap ends it.
    val snappingToTop = remember { mutableStateOf(false) }
    /** The library header row (title, search, sort, filter) holds focus. */
    val headerFocused = remember { mutableStateOf(false) }
    /** The genre pill row holds focus: Up from the top poster row onto All must not scroll either. */
    val pillsFocused = remember { mutableStateOf(false) }
    // Item tops at scroll zero, recorded whenever the page rests at the
    // top: the snap back up is then one animateScrollBy over the exact
    // distance (tvOS .smooth 0.45 s). animateScrollToItem jumps in
    // viewport-sized chunks over that distance, which read as chunky
    // (Logan 2026-09-10).
    val restTops = remember { HashMap<Int, Int>() }
    val restHeights = remember { HashMap<Int, Int>() }
    val chromeScroll = com.aeriotv.android.feature.main.LocalTvChromeScroll.current
    /** Pixels the page has scrolled, from a visible item whose rest position is known; null when none is. */
    fun scrollOffsetPx(): Int? {
        val anchor = gridState.layoutInfo.visibleItemsInfo.firstOrNull { restTops.containsKey(it.index) } ?: return null
        return restTops.getValue(anchor.index) - anchor.offset.y
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }.collect { info ->
            if (gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0) {
                info.visibleItemsInfo.forEach { restTops[it.index] = it.offset.y; restHeights[it.index] = it.size.height }
            }
            chromeScroll?.value = scrollOffsetPx() ?: Int.MAX_VALUE
        }
    }
    DisposableEffect(Unit) { onDispose { chromeScroll?.value = 0 } }
    // tvOS Down walk, measured on the Apple TV (2026-09-10, points halved):
    // hero -> shelf 54 dp, shelf -> sort 92 dp, sort -> pills 129 dp, pills
    // -> first poster row = the header at the top of the screen. Applied on
    // DOWNWARD zone changes only; further rows use the edge-margin rule.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var zone by remember { mutableIntStateOf(0) }
    val scrollingToTarget = remember { mutableStateOf(false) }
    val enterZone: (Int) -> Unit = { next ->
        val previous = zone
        zone = next
        if (next > previous) {
            val targetPx: Int? = with(density) {
                when (next) {
                    1 -> 54.dp.roundToPx()
                    2 -> 92.dp.roundToPx()
                    3 -> 129.dp.roundToPx()
                    4 -> restTops[headerIndex]
                        ?: scrollOffsetPx()?.let { cur ->
                            gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == headerIndex }?.let { cur + it.offset.y }
                        }
                    else -> null
                }
            }
            val current = scrollOffsetPx()
            if (targetPx != null && current != null && targetPx > current) {
                scope.launch {
                    scrollingToTarget.value = true
                    try {
                        gridState.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) {
                            var previousValue = 0f
                            androidx.compose.animation.core.animate(
                                initialValue = 0f, targetValue = (targetPx - current).toFloat(),
                                animationSpec = tween(durationMillis = 300, easing = androidx.compose.animation.core.EaseOut),
                            ) { value, _ -> scrollBy(value - previousValue); previousValue = value }
                        }
                    } finally { scrollingToTarget.value = false }
                }
            }
        } else if (next == 1 && previous > 1) {
            // handled by revealFirstShelf from the shelf's own focus hook
        } else if (next == 2 && previous > 2) {
            // Up from the pills onto Sort (Logan 2026-09-10): the library
            // header lands just under the top of the screen.
            val targetPx = restTops[headerIndex]?.let { it - with(density) { 24.dp.roundToPx() } }
            val current = scrollOffsetPx()
            if (targetPx != null && current != null && targetPx < current) {
                scope.launch {
                    scrollingToTarget.value = true
                    try {
                        gridState.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) {
                            var previousValue = 0f
                            androidx.compose.animation.core.animate(
                                initialValue = 0f, targetValue = (targetPx - current).toFloat(),
                                animationSpec = tween(durationMillis = 300, easing = androidx.compose.animation.core.EaseOut),
                            ) { value, _ -> scrollBy(value - previousValue); previousValue = value }
                        }
                    } finally { scrollingToTarget.value = false }
                }
            }
        }
    }
    val scrollToTopRef = remember { mutableStateOf<() -> Unit>({}) }
    /**
     * Up from the header onto the first shelf (Apple TV, 2026-09-10): when
     * the whole shelf block fits on screen with the page at the top (DVR's
     * 16:9 cards) the page goes to the top; when it would run off the
     * bottom (a 2:3 Watchlist) the page stops with the shelf row 86 dp
     * under the top edge instead, so the poster and its text are in view.
     */
    val revealFirstShelf: () -> Unit = {
        val shelfIndex = 1 + (if (hasHero) 1 else 0)
        val shelfTop = restTops[shelfIndex]
        val shelfHeight = restHeights[shelfIndex]
            ?: gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == shelfIndex }?.size?.height
        val viewport = gridState.layoutInfo.viewportSize.height
        val fitsAtTop = shelfTop == null || shelfHeight == null || shelfTop + shelfHeight <= viewport
        if (fitsAtTop) {
            scrollToTopRef.value()
        } else {
            val targetPx = shelfTop - with(density) { 86.dp.roundToPx() }
            val current = scrollOffsetPx()
            if (current != null && targetPx < current) {
                scope.launch {
                    scrollingToTarget.value = true
                    try {
                        gridState.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) {
                            var previousValue = 0f
                            androidx.compose.animation.core.animate(
                                initialValue = 0f, targetValue = (targetPx - current).toFloat(),
                                animationSpec = tween(durationMillis = 300, easing = androidx.compose.animation.core.EaseOut),
                            ) { value, _ -> scrollBy(value - previousValue); previousValue = value }
                        }
                    } finally { scrollingToTarget.value = false }
                }
            }
        }
    }
    val scrollToTop: () -> Unit = {
        // One snap at a time: a rapid Up from the shelf onto the hero while
        // the shelf's snap was running cancelled it and restarted from a
        // standstill (two visible jumps, logcat 2026-09-10 22:04). The
        // running snap is already heading to the top.
        if (!snappingToTop.value && (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0)) {
            scope.launch {
                snappingToTop.value = true
                // Bring the bar back before the page moves: one relayout,
                // then a scroll over a page that no longer changes height.
                chromeCollapsed?.value = false
                chromeScroll?.value = 0
                withFrameNanos { }
                // Warm every line above the viewport first (hero, shelves)
                // so nothing composes mid-animation: a hair of scroll wakes
                // the prefetch strategy, then wait for it (150 ms cap).
                val firstRow = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.row ?: 0
                val strategy = com.aeriotv.android.ui.tv.tvPrefetchStrategyFor(gridState)
                if (strategy != null && firstRow > 0) {
                    val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
                    strategy.requestLines((firstRow - 1 downTo 0).toList()) { ready.complete(Unit) }
                    gridState.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) { scrollBy(1f); scrollBy(-1f) }
                    kotlinx.coroutines.withTimeoutOrNull(150) { ready.await() }
                }
                try {
                    val anchor = gridState.layoutInfo.visibleItemsInfo.firstOrNull { restTops.containsKey(it.index) }
                    if (anchor != null) {
                        val distance = (restTops.getValue(anchor.index) - anchor.offset.y).toFloat()
                        // PreventUserInput: the focused card's own bring-into-view
                        // request (even a zero-distance one) took the scroll
                        // mutex and cancelled a Default-priority animateScrollBy
                        // after a few frames, leaving the hard snap to finish
                        // the move (the "caught" animation, Logan 2026-09-10).
                        gridState.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) {
                            var previous = 0f
                            androidx.compose.animation.core.animate(
                                initialValue = 0f, targetValue = -distance,
                                animationSpec = tween(durationMillis = 600, easing = androidx.compose.animation.core.EaseInOut),
                            ) { value, _ ->
                                scrollBy(value - previous)
                                previous = value
                            }
                        }
                    } else {
                        gridState.animateScrollToItem(0)
                    }
                    withFrameNanos { }
                    gridState.scrollToItem(0)
                } finally {
                    snappingToTop.value = false
                }
            }
        }
    }
    scrollToTopRef.value = scrollToTop
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

    // Tab bar collapses once the hero has scrolled off (tvOS hides it past
    // the hero); it grows back the moment focus reaches it.
    val scrolled by remember(gridState) { derivedStateOf { gridState.firstVisibleItemIndex > 1 } }
    if (chromeCollapsed != null) {
        LaunchedEffect(scrolled) { chromeCollapsed.value = scrolled }
        DisposableEffect(Unit) { onDispose { chromeCollapsed.value = false } }
    }
    // BACK closes an open search first (tvOS: Menu closes the field before
    // anything else), then, while scrolled, snaps to the top and refocuses
    // the hero (the guide's ladder); at the top the handlers stand down so
    // BACK reaches the shell.
    androidx.activity.compose.BackHandler(enabled = LocalTabIsActive.current && searchEnabled && searchActive) {
        onSearchToggle()
    }
    androidx.activity.compose.BackHandler(enabled = LocalTabIsActive.current && scrolled && !(searchEnabled && searchActive)) {
        scope.launch {
            gridState.animateScrollToItem(0)
            repeat(10) {
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
        // tvOS parks the row 24 pt under the top edge, not flush with it.
        runCatching { gridState.scrollToItem(leadingCount + (idx / columns) * columns, -restoreGapPx) }
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
    val railAllowed = !isSearching && railLetters.isNotEmpty() && gridItems.size >= railMinimumCount
    val gridTopFor: (Int) -> Int = { row -> leadingCount + row * columns }

    TvKeyboardOnOkHost {
    Box(modifier = Modifier.fillMaxSize()) {
        // tvOS focus scrolling (measured 2026-09-10): 100 dp clear of both edges.
        val edgeSpec = with(androidx.compose.ui.platform.LocalDensity.current) {
            remember(this) {
                com.aeriotv.android.ui.tv.TvEdgeMarginBringIntoViewSpec(
                    marginPx = 100.dp.toPx(),
                    suppressed = { snappingToTop.value || scrollingToTarget.value },
                    holdIfVisible = { headerFocused.value || pillsFocused.value || zone == 1 },
                )
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides edgeSpec) {
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
            contentPadding = PaddingValues(start = TvPage.overscan + TvPage.contentInset - TvPage.heroInset, end = TvPage.overscan, top = 0.dp, bottom = 40.dp),
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
                        onButtonFocused = { enterZone(0); scrollToTop() },
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
                        onCardFocused = { val from = zone; enterZone(1); if (si == 0 && hasHero && from > 1) revealFirstShelf() },
                        modifier = Modifier.padding(bottom = TvPage.sectionSpacing),
                    )
                }
            }
            fullSpan("header") {
                Column(
                    modifier = Modifier
                        .padding(start = TvPage.overscan + TvPage.contentInset, end = TvPage.overscan + TvPage.heroInset)
                        .onFocusChanged { headerFocused.value = it.hasFocus; if (it.hasFocus) enterZone(2) },
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
                                selected = searchActive, onClick = onSearchToggle,
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
                            )
                        }
                    }
                    if (isLoading && gridItems.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
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
                    // Entering the row from the sort circle above or the grid
                    // below always lands on All (Logan 2026-09-10).
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(start = TvPage.overscan + TvPage.contentInset, end = TvPage.overscan + TvPage.heroInset),
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .fillMaxWidth()
                            .onFocusChanged { pillsFocused.value = it.hasFocus; if (it.hasFocus) enterZone(3) }
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
                        if (it.isFocused) { focusedCellKeyState.value = k; enterZone(4) }
                        else if (focusedCellKeyState.value == k) focusedCellKeyState.value = null
                    }
                    .onPreviewKeyEvent { ev ->
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
        TvRailSlot(visible = { railVisible }) {
            TvAlphabetRail(
                available = railLetters,
                onFocusChanged = { railHasFocusState.value = it },
                // tvOS Movies rail: a click scrolls the letter's row to the
                // top of the grid; focus STAYS on the rail. Right goes back
                // to the last poster, Up from # to the hero.
                onLetter = { letter ->
                    val idx = railIndexOf(letter)
                    if (idx >= 0) scope.launch {
                        gridState.animateScrollToItem(gridTopFor(idx / columns), -restoreGapPx)
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
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 17.dp),
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
private fun TvRailSlot(visible: () -> Boolean, content: @Composable () -> Unit) {
    if (visible()) content()
}

/** A full-width grid row that reclaims the grid's start inset (see contentPadding in TvMediaPage). */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.fullSpan(
    key: Any,
    /** Height the row gives back so the next row sits closer (the grid's row spacing still applies). */
    trimBottom: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        val bleedStart = TvPage.overscan + TvPage.contentInset - TvPage.heroInset
        val bleedEnd = TvPage.overscan
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
            remember(this) { com.aeriotv.android.ui.tv.TvEdgeMarginBringIntoViewSpec(marginPx = (TvPage.overscan + TvPage.contentInset).toPx()) }
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
    onFocusChanged: (Boolean) -> Unit,
    onLetter: (Char) -> Unit,
    /** tvOS: Right on a letter leaves the rail back to the last poster. */
    onExitRight: () -> Unit,
    /** tvOS: Up from # leaves the rail to the hero. */
    onExitUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val letters = remember { listOf('#') + ('A'..'Z').toList() }
    val hash = remember { FocusRequester() }
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
