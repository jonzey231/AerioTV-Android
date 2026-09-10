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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.aeriotv.android.ui.tv.TvLargeCardBringIntoViewSpec
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
    var focusedCellKey by remember { mutableStateOf<Any?>(null) }
    var railHasFocus by remember { mutableStateOf(false) }
    val railVisible = !isSearching && railLetters.isNotEmpty() && gridItems.size >= railMinimumCount &&
        (focusedCellKey != null || railHasFocus)
    val gridTopFor: (Int) -> Int = { row -> leadingCount + row * columns }

    TvKeyboardOnOkHost {
    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides TvLargeCardBringIntoViewSpec) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = TvPage.overscan),
            contentPadding = PaddingValues(top = 0.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(gridRowSpacing),
            horizontalArrangement = Arrangement.spacedBy(TvPage.columnSpacing),
        ) {
            item(key = "catcher", span = { GridItemSpan(maxLineSpan) }) {
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
                item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                    TvHeroCarousel(
                        pages = heroPages,
                        primaryRequester = heroPrimary,
                        upTarget = topNav,
                        // tvOS: any hero button gaining focus while the page
                        // is scrolled snaps the page back to the top.
                        onButtonFocused = {
                            if (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0) {
                                scope.launch { gridState.animateScrollToItem(0) }
                            }
                        },
                        modifier = Modifier.padding(bottom = TvPage.sectionSpacing),
                    )
                }
            }
            visibleShelves.forEachIndexed { si, shelf ->
                item(key = "shelf:" + shelf.title, span = { GridItemSpan(maxLineSpan) }) {
                    TvShelfRow(
                        shelf = shelf,
                        firstCardRequester = if (si == 0) firstShelfCard else null,
                        modifier = Modifier.padding(bottom = TvPage.sectionSpacing),
                    )
                }
            }
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.padding(start = TvPage.contentInset, end = TvPage.heroInset)) {
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
                    item(key = "extra:$i", span = { GridItemSpan(maxLineSpan) }) {
                        Box(modifier = Modifier.padding(start = TvPage.contentInset, top = 6.dp)) { extra() }
                    }
                }
            }
            if (pillRow) {
                item(key = "pills", span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(start = TvPage.contentInset, end = TvPage.heroInset),
                        modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth(),
                    ) {
                        item(key = "all") { TvPill("All", selectedPill == null, onClick = { onPill(null) }) }
                        items(pills.size, key = { pills[it] }) { i ->
                            val p = pills[i]
                            TvPill(p, selectedPill == p, onClick = { onPill(if (selectedPill == p) null else p) })
                        }
                    }
                }
            }
            if (gridItems.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { emptyContent() }
                }
            }
            itemsIndexed(items = gridItems, key = { _, it -> gridKey(it) }) { index, item ->
                val k = gridKey(item)
                val requester = remember(k) { cellRequesters.getOrPut(k) { FocusRequester() } }
                val cellModifier = Modifier
                    .padding(
                        start = if (index % columns == 0) TvPage.contentInset - TvPage.heroInset else 0.dp,
                        end = if (index % columns == columns - 1) 0.dp else 0.dp,
                    )
                    .focusRequester(requester)
                    .then(if (index == 0) Modifier.focusRequester(firstCell) else Modifier)
                    .onFocusChanged { if (it.isFocused) focusedCellKey = k else if (focusedCellKey == k) focusedCellKey = null }
                    .onPreviewKeyEvent { ev ->
                        vodGridDpadFallback(ev, index, gridItems.size, gridState, focusManager, scope) { i ->
                            cellRequesters.getOrPut(gridKey(gridItems[i])) { FocusRequester() }
                        }
                    }
                cell(item, TvCellScope(modifier = cellModifier, focusRequester = cellReturnRequester(item)))
            }
        }
        }

        if (railVisible) {
            TvAlphabetRail(
                available = railLetters,
                onFocusChanged = { railHasFocus = it },
                onLetter = { letter ->
                    val idx = railIndexOf(letter)
                    if (idx >= 0) scope.launch {
                        gridState.animateScrollToItem(gridTopFor(idx / columns))
                        repeat(8) {
                            withFrameNanos { }
                            if (runCatching { cellRequesters[gridKey(gridItems[idx])]?.requestFocus() }.isSuccess) return@launch
                        }
                    }
                },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 17.dp),
            )
        }
    }
    }

    if (sortOpen) {
        TvActionMenuDialog(title = "Sort", actions = sortActions, guard = menuGuard, onDismiss = { sortOpen = false })
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
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
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
                AsyncImage(model = page.artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (!page.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = page.logoUrl, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(0.28f).fillMaxHeight().align(Alignment.CenterEnd).padding(14.dp).alpha(0.9f),
                )
            }
        }
        // tvOS fades the art with two masks; the same stops painted as
        // background-colored gradients.
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0.0f to bg, 0.12f to bg, 0.38f to bg.copy(alpha = 0.92f), 0.7f to bg.copy(alpha = 0.35f), 1.0f to bg.copy(alpha = 0.05f),
                ),
            ),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1.0f to bg),
            ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(22.dp).widthIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            page.eyebrow?.let { eyebrow ->
                val c = if (page.eyebrowColor == Color.Unspecified) MaterialTheme.colorScheme.primary else page.eyebrowColor
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (page.eyebrowDot) Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(c))
                    Text(eyebrow, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = c)
                }
            }
            Text(
                page.title, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            page.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (page.meta.isNotEmpty() || !page.rating.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        page.meta.joinToString(" · "), fontSize = 10.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    page.rating?.takeIf { it.isNotBlank() }?.let { r ->
                        Text(
                            (if (page.meta.isEmpty()) "" else " · ") + "★ $r", fontSize = 10.sp, fontWeight = FontWeight.Medium,
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
                modifier = Modifier.padding(top = 2.dp),
            ) {
                page.buttons.forEachIndexed { i, b ->
                    TvHeroButtonView(
                        button = b,
                        modifier = Modifier
                            .then(if (b.primary && primaryRequester != null) Modifier.focusRequester(primaryRequester) else Modifier)
                            .then(if (upTarget != null) Modifier.focusProperties { up = upTarget } else Modifier)
                            .onFocusChanged { if (it.isFocused) onButtonFocused() },
                        onLongClick = if (b.primary && page.longPressActions.isNotEmpty()) ({ menuOpen = true }) else null,
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
            .height(30.dp)
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
            // Measured against the Apple TV (Logan 2026-09-10): the tvOS
            // label renders a ~20 pt cap height in the 60 pt pill, so the
            // Roboto label is 13.5 sp (not the nominal 22 pt halved) with a
            // 13 dp glyph and 11 dp sides to keep the pill width equal.
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(button.icon, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
        Text(button.label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
    }
}

// MARK: shelves

@Composable
private fun <T> TvShelfRow(shelf: TvShelf<T>, firstCardRequester: FocusRequester?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            shelf.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = TvPage.contentInset, bottom = 4.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(TvPage.columnSpacing),
            contentPadding = PaddingValues(start = TvPage.contentInset, end = TvPage.heroInset, top = 6.dp, bottom = 6.dp),
        ) {
            items(shelf.items.size, key = { shelf.key(shelf.items[it]) }) { i ->
                shelf.card(
                    shelf.items[i],
                    Modifier
                        .width(shelf.cardWidth)
                        .then(if (i == 0 && firstCardRequester != null) Modifier.focusRequester(firstCardRequester) else Modifier),
                )
            }
        }
    }
}

// MARK: cards

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
                AsyncImage(model = posterUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
                !artUrl.isNullOrBlank() -> AsyncImage(model = artUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
                        if (ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionCenter || ev.key == Key.Enter)) {
                            // Unavailable letters resolve to the nearest one after, else before.
                            val target = when {
                                isAvailable -> letter
                                else -> letters.drop(letters.indexOf(letter)).firstOrNull { it in available }
                                    ?: letters.take(letters.indexOf(letter)).lastOrNull { it in available }
                            }
                            if (target != null) onLetter(target)
                            true
                        } else false
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter.toString(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
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
