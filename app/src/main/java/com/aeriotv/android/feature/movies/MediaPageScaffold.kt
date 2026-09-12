package com.aeriotv.android.feature.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset
import kotlinx.coroutines.launch

/**
 * The phone media page (Logan 2026-09-09: one template for Movies, TV Shows
 * and DVR). Top to bottom: room under the status bar, one card deck per
 * section (Continue Watching, then Watchlist or Recently Recorded), any
 * extra full-width rows, the library header (title, count, search / sort /
 * filter circles), the search field and its extras while searching, the
 * pill row, the three-column poster grid, and the alphabet rail once the
 * grid owns the display. The pages own their data and cards; this owns the
 * layout, spacing and scroll behaviour.
 */
class PageDeck<T>(
    val title: String,
    val items: List<T>,
    val key: (T) -> Any,
    val card: @Composable (T) -> Unit,
)

/** A full-width row placed between the decks and the library header. */
class PageRow(val key: String, val content: @Composable () -> Unit)

@Composable
fun <T> MediaPageScaffold(
    gridState: LazyGridState,
    compact: Boolean,
    decks: List<PageDeck<*>>,
    rows: List<PageRow> = emptyList(),
    headerTitle: String,
    headerCount: Int,
    sortMenu: @Composable () -> Unit,
    onSort: () -> Unit,
    onFilter: (() -> Unit)? = null,
    // Search (absent on pages without it): the header circle toggles the
    // pill field under the header; the X clears and closes it.
    searchEnabled: Boolean = false,
    searchActive: Boolean = false,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onSearchToggle: () -> Unit = {},
    searchPlaceholder: String = "Search",
    isSearching: Boolean = false,
    searchExtras: List<PageRow> = emptyList(),
    pills: List<String> = emptyList(),
    selectedPill: String? = null,
    onPill: (String?) -> Unit = {},
    gridItems: List<T>,
    gridKey: (T) -> Any,
    cell: @Composable (T) -> Unit,
    emptyContent: @Composable () -> Unit,
    railLetters: Set<Char> = emptySet(),
    railIndexOf: (Char) -> Int = { -1 },
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    /** Full-width content under the grid (the TMDB attribution on the pages
     *  that render TMDB art and metadata). Not focusable, and placed AFTER the
     *  cells so it never shifts the leading-item indices the rail and the
     *  search scroll depend on. */
    footer: (@Composable () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val bottomInset = LocalTabBarBottomInset.current
    val visibleDecks = decks.filter { it.items.isNotEmpty() }
    // Full-span items before the grid cells: room, decks, rows, header,
    // (search), (search extras), (pills).
    val headerIndex = 1 + visibleDecks.size + rows.size
    val leadingCount = headerIndex + 1 + (if (searchActive) 1 else 0) +
        (if (isSearching) searchExtras.size else 0) + (if (!isSearching && pills.isNotEmpty()) 1 else 0)
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Opening search scrolls the header (and the field under it) to the top
    // so the results land in view, then focuses the field for the keyboard.
    LaunchedEffect(searchActive) {
        if (searchActive) {
            gridState.animateScrollToItem(headerIndex)
            runCatching { searchFocus.requestFocus() }
        }
    }
    // Rail only once the library owns the display: the header and pill rows
    // have scrolled off (iPhone rule).
    val railVisible by remember(leadingCount, gridItems.size, headerIndex, isSearching) {
        derivedStateOf {
            compact && !isSearching && gridItems.size >= 9 &&
                gridState.firstVisibleItemIndex >= headerIndex + 2
        }
    }
    // Bottom room while searching: only what is needed to let the header
    // reach the top given the rows the results fill, so the list stops at
    // the last result instead of scrolling into blank space.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val searchRoom: Dp = run {
        val viewport = configuration.screenHeightDp.dp
        val cellW = (configuration.screenWidthDp.dp - 18.dp - 18.dp - 16.dp) / 3
        val rowH = cellW * 1.5f + 62.dp + 16.dp
        val gridRows = (gridItems.size + 2) / 3
        val content = 56.dp + 72.dp + rowH * gridRows + bottomInset + 16.dp
        (viewport - content).coerceAtLeast(0.dp)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = if (compact) GridCells.Fixed(3) else GridCells.Adaptive(minSize = 120.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    // Symmetric margins so the posters sit centered; the
                    // alphabet rail overlays the right edge.
                    start = 18.dp, end = 18.dp,
                    top = 0.dp, bottom = bottomInset + 16.dp + (if (searchActive) searchRoom else 0.dp),
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "room", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(22.dp)) }
                visibleDecks.forEach { deck ->
                    item(key = "deck:" + deck.title, span = { GridItemSpan(maxLineSpan) }) {
                        DeckSection(deck, compact)
                    }
                }
                rows.forEach { row ->
                    item(key = "row:" + row.key, span = { GridItemSpan(maxLineSpan) }) { row.content() }
                }
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    LibraryHeader(
                        title = headerTitle,
                        count = headerCount,
                        onTitleTap = { scope.launch { gridState.animateScrollToItem(1) } },
                        onSearch = if (searchEnabled) onSearchToggle else null,
                        onSort = onSort,
                        onFilter = onFilter,
                        sortMenu = sortMenu,
                    )
                }
                if (searchActive) {
                    item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                        SearchPillField(
                            query = query,
                            placeholder = searchPlaceholder,
                            onQueryChange = onQueryChange,
                            onClose = { onQueryChange(""); onSearchToggle() },
                            modifier = Modifier.focusRequester(searchFocus),
                        )
                    }
                }
                if (isSearching) {
                    searchExtras.forEach { row ->
                        item(key = "extra:" + row.key, span = { GridItemSpan(maxLineSpan) }) { row.content() }
                    }
                }
                if (!isSearching && pills.isNotEmpty()) {
                    item(key = "pills", span = { GridItemSpan(maxLineSpan) }) {
                        EdgeToEdgePillRow {
                            item(key = "all") { GenrePill("All", selectedPill == null) { onPill(null) } }
                            items(pills.size, key = { pills[it] }) { i ->
                                GenrePill(pills[i], selectedPill == pills[i]) { onPill(if (selectedPill == pills[i]) null else pills[i]) }
                            }
                        }
                    }
                }
                if (gridItems.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { emptyContent() }
                    }
                }
                items(gridItems, key = gridKey) { item -> cell(item) }
                if (footer != null) {
                    item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { footer() }
                    }
                }
            }
        }
        if (railVisible) {
            AlphabetRail(
                available = railLetters,
                onLetter = { letter ->
                    val idx = railIndexOf(letter)
                    if (idx >= 0) scope.launch { gridState.scrollToItem(leadingCount + idx) }
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
            )
        }
    }
}

@Composable
private fun <T> DeckSection(deck: PageDeck<T>, compact: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Text(
            deck.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (compact) {
            // iPhone parity: the deck is the plain content width and clips at
            // its bounds, widened over both 18 dp grid margins so the trailing
            // cards peek out to the screen edge; the front card's left edge
            // lines up with the grid (lead inset 8 + deck margin 10 = 18).
            Box(modifier = Modifier.layout { measurable, constraints ->
                val extra = 18.dp.roundToPx() + 18.dp.roundToPx()
                val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = 0))
                layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-18.dp.roundToPx(), 0) }
            }) {
                PhoneCardDeck(items = deck.items, cardHeight = 220.dp, key = deck.key, leadInset = 8.dp) { item, _ -> deck.card(item) }
            }
        } else {
            val pagerState = androidx.compose.foundation.pager.rememberPagerState { deck.items.size }
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                pageSize = androidx.compose.foundation.pager.PageSize.Fill,
                pageSpacing = 8.dp,
                modifier = Modifier.fillMaxWidth(),
                pageContent = { i -> Box(modifier = Modifier.fillMaxWidth(0.62f)) { deck.card(deck.items[i]) } },
            )
        }
    }
}

@Composable
private fun LibraryHeader(
    title: String,
    count: Int,
    onTitleTap: () -> Unit,
    onSearch: (() -> Unit)?,
    onSort: () -> Unit,
    onFilter: (() -> Unit)?,
    sortMenu: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.clickable(onClick = onTitleTap),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                count.toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 1.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onSearch != null) HeaderCircle(Icons.Filled.Search, "Search", onSearch)
            Box { HeaderCircle(Icons.Filled.SwapVert, "Sort", onSort); sortMenu() }
            if (onFilter != null) HeaderCircle(Icons.Filled.FilterList, "Filter", onFilter)
        }
    }
}

@Composable
private fun HeaderCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(20.dp))
    }
}

/**
 * Pill field like the iPhone search bar: filled surfaceVariant, no outline
 * in either state, search glyph leading, filled-circle X trailing, 44 dp
 * tall. BasicTextField because OutlinedTextField enforces a 56 dp minimum.
 */
@Composable
private fun SearchPillField(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.foundation.text.BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        decorationBox = { inner ->
            androidx.compose.material3.OutlinedTextFieldDefaults.DecorationBox(
                value = query,
                innerTextField = inner,
                enabled = true,
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                interactionSource = interaction,
                placeholder = { Text(placeholder, maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingIcon = {
                    androidx.compose.material3.IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Cancel, contentDescription = "Clear and close search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
                contentPadding = androidx.compose.material3.OutlinedTextFieldDefaults.contentPadding(top = 0.dp, bottom = 0.dp),
                container = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                },
            )
        },
    )
}

/** Section title used by extra rows so they match the deck titles. */
@Composable
fun PageSectionTitle(text: String) {
    Text(
        text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}
