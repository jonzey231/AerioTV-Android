package com.aeriotv.android.feature.movies.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.feature.movies.MediaHeroPage
import com.aeriotv.android.feature.movies.MediaItem
import com.aeriotv.android.feature.movies.MediaKind
import com.aeriotv.android.feature.movies.MediaSortOrder
import com.aeriotv.android.feature.movies.formatRating
import com.aeriotv.android.ui.tv.TvPill

/** Poster column width on the 960 dp Streamer canvas: (960 - 70 - 48 - 6 * 12) / 7. */
internal val TV_POSTER_WIDTH = 110.dp

/**
 * Movies / TV Shows on Android TV: the shared [TvMediaPage] fed by the same
 * data MediaTabContent builds for the phone (Continue Watching hero pages,
 * Watchlist, the sorted library, search hits and provider pills). tvOS
 * MoviesView legacyContent is the reference: hero carousel, Watchlist
 * shelf, library header with search / sort / filter circles, genre pills,
 * seven-column poster grid, alphabet rail.
 */
@Composable
internal fun TvMediaTab(
    kind: MediaKind,
    gridState: LazyGridState,
    heroPages: List<MediaHeroPage>,
    watchlistPages: List<MediaHeroPage>,
    backdrops: Map<String, String?>,
    library: List<MediaItem>,
    gridItems: List<MediaItem>,
    available: Set<Char>,
    isSearching: Boolean,
    searchActive: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onClearSearch: () -> Unit,
    personMatchName: String?,
    showProviderPills: Boolean,
    providerIds: List<Int>,
    providerNames: Map<Int, String>,
    selectedProviderId: Int?,
    onSelectProvider: (Int?) -> Unit,
    genrePills: List<String>,
    selectedGenre: String?,
    onGenre: (String?) -> Unit,
    sortOrder: MediaSortOrder,
    onSort: (MediaSortOrder) -> Unit,
    onFilter: () -> Unit,
    filterActive: Boolean,
    /** The Filter sheet is open over the page: focus returns to the circle on close. */
    filterOpen: Boolean,
    watchlistKeys: Set<String>,
    onToggleWatchlist: (MediaItem) -> Unit,
    onRemoveWatchlist: (String) -> Unit,
    onRemoveProgress: (String) -> Unit,
    onPlay: (videoId: String, title: String) -> Unit,
    /** "Play from Beginning": start at 0 and KEEP the Continue Watching row. */
    onPlayFromStart: (videoId: String, title: String) -> Unit,
    onOpen: (MediaItem) -> Unit,
    isLoading: Boolean,
    libraryPending: Boolean,
    isSearchBusy: Boolean,
) {
    val pageId = kind.name
    val open: (MediaItem) -> Unit = { item ->
        TvReturnMemory.pending[pageId] = item.key
        // Come back at the exact offset, not with the row parked at the top
        // (tvOS never disposes the tab, MoviesView.swift:221, 259-279).
        TvReturnMemory.pendingOffset[pageId] =
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        onOpen(item)
    }

    fun heroFor(page: MediaHeroPage, watchlist: Boolean): TvHeroPage {
        val item = page.item
        val videoId = page.key.removePrefix("cw:")
        val play: () -> Unit = if (watchlist) ({
            item?.movieUuid?.let { onPlay(it, page.title) } ?: item?.let(open)
        }) else ({ onPlay(videoId, page.title) })
        val details: () -> Unit = { item?.let(open) }
        val meta = buildList {
            page.year?.let { add(it.toString()) }
            if ((page.season ?: 0) > 0) add("S${page.season} E${page.episode ?: 0}")
            // tvOS: VODSeries.durationText is always empty, so a TV Shows
            // hero never carries a runtime (VODModels.swift:1288).
            if (kind != MediaKind.TVShows) {
                page.durationSecs?.takeIf { it > 0 }?.let { s -> add(if (s >= 3600) "${s / 3600} h ${(s % 3600) / 60} min" else "${s / 60} min") }
            }
            page.genre?.split(',', '/', '|')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        val buttons = buildList {
            add(TvHeroButton(if (page.hasProgress) "Resume" else "Play", Icons.Filled.PlayArrow, primary = true, onClick = play))
            // tvOS plays at 0 and LEAVES WatchProgress intact
            // (MoviesView.swift:1507-1511): the row must survive.
            if (page.hasProgress) {
                add(TvHeroButton("Play from Beginning", Icons.Filled.Replay) { onPlayFromStart(videoId, page.title) })
            }
            add(TvHeroButton("Details", Icons.Outlined.Info, onClick = details))
        }
        val longPress = buildList {
            if (item != null) add(TvMenuAction(if (item.key in watchlistKeys) "Remove from Watchlist" else "Add to Watchlist") { onToggleWatchlist(item) })
            if (!watchlist) add(TvMenuAction("Remove from Continue Watching", destructive = true) { onRemoveProgress(videoId) })
            else if (item != null) add(TvMenuAction("Remove from Watchlist", destructive = true) { onRemoveWatchlist(item.key) })
        }
        return TvHeroPage(
            key = page.key, title = page.title, artUrl = backdrops[page.key] ?: page.artUrl,
            buttons = buttons, meta = meta, rating = formatRating(page.rating).ifEmpty { null },
            plot = page.plot, longPressActions = longPress,
        )
    }

    fun libraryFallbackHero(): List<TvHeroPage> {
        val first = library.firstOrNull() ?: return emptyList()
        return listOf(
            TvHeroPage(
                key = "lib:" + first.key, title = first.title, artUrl = first.posterUrl,
                meta = listOfNotNull(first.year?.toString()), rating = formatRating(first.rating).ifEmpty { null },
                buttons = listOf(
                    TvHeroButton("Play", Icons.Filled.PlayArrow, primary = true) { first.movieUuid?.let { onPlay(it, first.title) } ?: open(first) },
                    TvHeroButton("Details", Icons.Outlined.Info) { open(first) },
                ),
                longPressActions = listOf(
                    TvMenuAction(if (first.key in watchlistKeys) "Remove from Watchlist" else "Add to Watchlist") { onToggleWatchlist(first) },
                ),
            ),
        )
    }

    // tvOS: with nothing in progress the hero shows the first library title
    // (Android has no addedAt yet, so it is library.first, not the newest).
    // Mid-sweep hold (MoviesView.swift:1042-1046): while the library is still
    // loading and nothing has resolved, KEEP the pages already on screen
    // instead of swapping the carousel for a single static card.
    val heldHero = remember { mutableStateOf(emptyList<TvHeroPage>()) }
    val tvHero = remember(heroPages, backdrops, library, watchlistKeys, isLoading) {
        if (!isLoading || heroPages.isNotEmpty() || heldHero.value.isEmpty()) {
            heldHero.value = if (heroPages.isNotEmpty()) {
                heroPages.map { heroFor(it, watchlist = false) }
            } else {
                libraryFallbackHero()
            }
        }
        heldHero.value
    }
    val watchlistShelf = TvShelf(
        title = "Watchlist",
        items = watchlistPages,
        key = { it.key },
        cardWidth = TV_POSTER_WIDTH,
    ) { page, modifier ->
        val item = page.item
        TvPosterCard(
            title = page.title, year = page.year, posterUrl = page.artUrl, rating = formatRating(page.rating),
            onClick = { item?.let(open) }, modifier = modifier,
            longPressActions = listOfNotNull(
                item?.let { TvMenuAction("Details") { open(it) } },
                item?.let { TvMenuAction("Remove from Watchlist", destructive = true) { onRemoveWatchlist(it.key) } },
            ),
        )
    }
    val sortActions = MediaSortOrder.entries.map { order ->
        TvMenuAction(if (order == sortOrder) order.label + "  ✓" else order.label) { onSort(order) }
    }
    val extras = buildList<@Composable () -> Unit> {
        if (personMatchName != null) add {
            Text(
                "Includes titles with $personMatchName", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (showProviderPills) add {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 8.dp), modifier = Modifier.fillMaxWidth()) {
                item(key = "all") { TvPill("All Providers", selectedProviderId == null, onClick = { onSelectProvider(null) }) }
                items(providerIds.size, key = { providerIds[it] }) { i ->
                    val pid = providerIds[i]
                    TvPill(providerNames[pid] ?: "Provider $pid", selectedProviderId == pid, onClick = { onSelectProvider(if (selectedProviderId == pid) null else pid) })
                }
            }
        }
    }

    TvMediaPage(
        gridState = gridState,
        heroPages = tvHero,
        shelves = listOf(watchlistShelf),
        headerTitle = if (isSearching) "Results" else kind.libraryTitle,
        headerCount = gridItems.size,
        columns = 7,
        gridRowSpacing = 24.dp,
        sortActions = sortActions,
        onFilter = onFilter,
        filterActive = filterActive,
        filterOpen = filterOpen,
        searchEnabled = true,
        searchActive = searchActive,
        query = query,
        onQueryChange = onQueryChange,
        onSearchToggle = onSearchToggle,
        onClearSearch = onClearSearch,
        searchPlaceholder = if (kind == MediaKind.Movies) "Search movies" else "Search TV shows",
        isSearching = isSearching,
        searchExtras = extras,
        pills = genrePills,
        selectedPill = selectedGenre,
        onPill = onGenre,
        gridItems = gridItems,
        gridKey = { it.key },
        cell = { item, scope ->
            TvPosterCard(
                title = item.title, year = item.year, posterUrl = item.posterUrl, rating = formatRating(item.rating),
                onClick = { open(item) }, modifier = scope.modifier,
                longPressActions = listOf(
                    TvMenuAction("Details") { open(item) },
                    TvMenuAction(if (item.key in watchlistKeys) "Remove from Watchlist" else "Add to Watchlist") { onToggleWatchlist(item) },
                ),
            )
        },
        emptyContent = {
            if (isLoading || (!isSearching && libraryPending) || (isSearching && isSearchBusy)) CircularProgressIndicator()
            else Text(if (isSearching) "No results" else kind.emptyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        isLoading = isLoading,
        railLetters = available,
        railIndexOf = { letter -> library.indexOfFirst { it.bucket == letter } },
        // Snapshot at composition start: the click that ARMS the key must not
        // restart the restore while the page is still up (it would clear the
        // key before the detail even opened).
        returnKey = remember { TvReturnMemory.pending[pageId] },
        returnOffset = remember { TvReturnMemory.pendingOffset[pageId] },
        onReturnHandled = { TvReturnMemory.pending.remove(pageId); TvReturnMemory.pendingOffset.remove(pageId) },
    )
}
