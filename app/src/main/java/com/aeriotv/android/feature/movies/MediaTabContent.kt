package com.aeriotv.android.feature.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.feature.livetv.ManageGroupsSheet
import com.aeriotv.android.feature.livetv.rememberLiveTvFormFactor
import com.aeriotv.android.feature.ondemand.OnDemandViewModel
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Movies / TV Shows tab, phone and tablet (media-center redesign, Apple
 * parity with MoviesView on iPhone). Top to bottom: 22 dp room, library
 * header (title + count, search / sort / filter circles), genre pills, the
 * poster grid (3 columns on compact width, adaptive wider), and an alphabet
 * rail on the right once the header has scrolled off. Pull to refresh sweeps
 * the provider. The hero deck lands above the header in a later pass.
 */
@Composable
fun MediaTabContent(
    kind: MediaKind,
    onMovieClick: (String) -> Unit,
    onSeriesClick: (Int) -> Unit,
    onEpisodeResume: (String) -> Unit,
    onResumeMovie: (String) -> Unit,
    onPlayMovie: (String) -> Unit = onResumeMovie,
    viewModel: OnDemandViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
    watchVm: com.aeriotv.android.feature.watchprogress.WatchProgressViewModel = hiltViewModel(),
    watchlistVm: WatchlistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Watchlist (Apple parity): a second deck under Continue Watching, and
    // Add / Remove entries on every poster and hero menu.
    val watchlistEntries by watchlistVm.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val watchlistKeys = remember(watchlistEntries) { watchlistEntries.map { it.key }.toSet() }
    val watchlistPages: List<MediaHeroPage> = remember(watchlistEntries, state.movies, state.series, kind) {
        watchlistEntries.filter { it.isMovie == (kind == MediaKind.Movies) }.map { e ->
            val m = if (e.isMovie) state.movies.firstOrNull { "m:" + it.uuid == e.key } else null
            val sr = if (!e.isMovie) state.series.firstOrNull { "s:" + it.id == e.key } else null
            val item = m?.toMediaItem() ?: sr?.toMediaItem() ?: MediaItem(
                key = e.key, title = e.title, year = e.year, rating = e.rating, posterUrl = e.posterUrl, category = null,
                movieUuid = if (e.isMovie) e.key.removePrefix("m:") else null,
                seriesId = if (!e.isMovie) e.key.removePrefix("s:").toIntOrNull() else null,
            )
            MediaHeroPage(
                key = "wl:" + e.key, title = item.title, artUrl = item.posterUrl, year = item.year, season = null, episode = null,
                durationSecs = m?.durationSecs, genre = m?.genre ?: sr?.genre, rating = item.rating,
                positionMs = 0L, durationMs = 0L, item = item, tmdbId = m?.tmdbId ?: sr?.tmdbId, isMovie = e.isMovie,
            )
        }
    }
    // Continue Watching (Apple parity, heroPages): unfinished progress rows,
    // newest first, movies for the Movies tab and one page per series for
    // TV Shows (the newest episode row wins), at most 12.
    val recentProgress by watchVm.observeRecent(40).collectAsStateWithLifecycle(initialValue = emptyList())
    val heroPages: List<MediaHeroPage> = remember(recentProgress, state.movies, state.series, kind) {
        val rows = recentProgress.filter { r ->
            r.positionMs > 0L && !r.isFinished && (r.durationMs <= 0L || r.positionMs < r.durationMs - 5 * 60_000L)
        }
        if (kind == MediaKind.Movies) {
            rows.filter { it.vodType == "movie" }.mapNotNull { r ->
                val m = state.movies.firstOrNull { it.uuid == r.videoId }
                // A row the player saved before the title was known reads
                // "On Demand"; without the movie loaded there is nothing to show.
                if (m == null && (r.title.isBlank() || r.title == "On Demand")) return@mapNotNull null
                MediaHeroPage(
                    key = "cw:" + r.videoId, title = m?.let { displayTitle(it.displayName, it.year) } ?: displayTitle(r.title, null),
                    artUrl = m?.posterUrl ?: r.posterUrl, year = m?.year, season = null, episode = null,
                    durationSecs = m?.durationSecs ?: (r.durationMs / 1000L).toInt().takeIf { it > 0 },
                    genre = m?.genre, rating = m?.rating, positionMs = r.positionMs, durationMs = r.durationMs,
                    item = m?.toMediaItem() ?: MediaItem(key = "m:" + r.videoId, title = r.title, year = null, rating = null,
                        posterUrl = r.posterUrl, category = null, movieUuid = r.videoId),
                    tmdbId = m?.tmdbId, isMovie = true,
                )
            }.take(12)
        } else {
            rows.filter { it.vodType == "episode" && it.seriesId != null }
                .distinctBy { it.seriesId }
                .mapNotNull { r ->
                    val sId = r.seriesId?.toIntOrNull() ?: return@mapNotNull null
                    val series = state.series.firstOrNull { it.id == sId } ?: return@mapNotNull null
                    MediaHeroPage(
                        key = "cw:" + r.videoId, title = displayTitle(series.displayName, series.year),
                        artUrl = series.posterUrl ?: r.posterUrl, year = series.year,
                        season = r.seasonNumber, episode = r.episodeNumber,
                        durationSecs = (r.durationMs / 1000L).toInt().takeIf { it > 0 },
                        genre = series.genre, rating = series.rating, positionMs = r.positionMs, durationMs = r.durationMs,
                        item = series.toMediaItem(), tmdbId = series.tmdbId, isMovie = false,
                    )
                }.take(12)
        }
    }
    LaunchedEffect(Unit) { viewModel.ensureLoaded() }
    val compact = rememberLiveTvFormFactor().widthClass == WindowWidthSizeClass.Compact
    val hiddenGroups by (if (kind == MediaKind.Movies) settingsVm.hiddenMovieGroups else settingsVm.hiddenSeriesGroups)
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val sortWire by (if (kind == MediaKind.Movies) settingsVm.moviesSortOrder else settingsVm.seriesSortOrder)
        .collectAsStateWithLifecycle(initialValue = "titleAZ")
    val sortOrder = MediaSortOrder.fromWire(sortWire)

    var selectedGenre by remember { mutableStateOf<String?>(null) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSort by remember { mutableStateOf(false) }
    var showManageGroups by remember { mutableStateOf(false) }

    val groupNames = if (kind == MediaKind.Movies) state.movieGroupNames else state.seriesGroupNames
    val genrePills = remember(groupNames, hiddenGroups) { groupNames.filterNot { it in hiddenGroups } }
    val isLoading = if (kind == MediaKind.Movies) state.isLoading else state.isLoadingSeries
    val isSearching = query.isNotBlank()

    // The library build (title cleanup, diacritic folding and the sort over
    // tens of thousands of rows) runs off the main thread; the previous list
    // stays on screen until the new one lands. Built inline it stalled the
    // first open of Movies for ~3 s and TV Shows for ~1 s on the Nothing
    // Phone (Logan 2026-09-09, "switching tabs freezes for a couple seconds").
    var libraryBuilt by remember { mutableStateOf<Pair<List<MediaItem>, Set<Char>>>(emptyList<MediaItem>() to emptySet()) }
    val sourceMovies = state.movies
    val sourceSeries = state.series
    var libraryPending by remember { mutableStateOf(true) }
    LaunchedEffect(sourceMovies, sourceSeries, kind, hiddenGroups, selectedGenre, sortOrder) {
        libraryPending = true
        libraryBuilt = withContext(Dispatchers.Default) {
            val all = if (kind == MediaKind.Movies) sourceMovies.map { it.toMediaItem() } else sourceSeries.map { it.toMediaItem() }
            val list = all.asSequence()
                .filter { it.category == null || it.category !in hiddenGroups }
                .filter { selectedGenre == null || it.category == selectedGenre }
                .toList()
                .sortedBy(sortOrder)
            list to list.mapTo(HashSet()) { it.bucket }
        }
        libraryPending = false
    }
    val library: List<MediaItem> = libraryBuilt.first
    // Search hits (iOS searchHits / filteredMovies): the server results plus
    // the TMDB person's titles found in the library, deduped by key and
    // sorted by the tab's sort order. A provider pick re-runs the server
    // search with the account filter; person hits carry no provider, so only
    // the server's answer counts then.
    val personMatchName = if (kind == MediaKind.Movies) state.personMatchName else state.seriesPersonMatchName
    val selectedProviderId = if (kind == MediaKind.Movies) state.selectedProviderId else state.seriesSelectedProviderId
    val results: List<MediaItem> = remember(
        state.searchResults, state.seriesSearchResults, state.personMatches, state.seriesPersonMatches,
        selectedProviderId, kind, sortOrder,
    ) {
        val server = if (kind == MediaKind.Movies) state.searchResults.map { it.toMediaItem() } else state.seriesSearchResults.map { it.toMediaItem() }
        val people = when {
            selectedProviderId != null -> emptyList()
            kind == MediaKind.Movies -> state.personMatches.map { it.toMediaItem() }
            else -> state.seriesPersonMatches.map { it.toMediaItem() }
        }
        val seen = HashSet<String>()
        (server + people).filter { seen.add(it.key) }.sortedBy(sortOrder)
    }
    // Provider pills (iOS providerPills): Dispatcharr Direct Connect only,
    // shown while searching when the account list has two or more providers.
    val providerIds = remember(state.providerNames) { state.providerNames.keys.sorted() }
    val showProviderPills = isSearching && providerIds.size >= 2
    val gridItems = if (isSearching) results else library
    val available: Set<Char> = libraryBuilt.second

    // Hero backdrops (Apple parity: TMDB backdrop per hero page when a key is
    // set); the cropped poster shows until one arrives. Cached per page key.
    var backdrops by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    LaunchedEffect(heroPages.map { it.key } + watchlistPages.map { it.key }) {
        for (page in heroPages + watchlistPages) {
            if (backdrops.containsKey(page.key)) continue
            val url = viewModel.resolveTmdbBackdropUrl(page.tmdbId, searchTitle(page.title), page.isMovie)
            backdrops = backdrops + (page.key to url)
        }
    }
    val gridState = rememberLazyGridState()

    fun submitQuery(v: String) {
        query = v
        if (kind == MediaKind.Movies) viewModel.setSearchQuery(v) else viewModel.setSeriesSearchQuery(v)
    }

    val heroCard: @Composable (MediaHeroPage) -> Unit = { page ->
        val videoId = page.key.removePrefix("cw:")
        val play: () -> Unit = {
            if (kind == MediaKind.Movies) { viewModel.noteMovieTitle(videoId, page.title); onPlayMovie(videoId) }
            else onEpisodeResume(videoId)
        }
        MediaHeroCard(
            page = backdrops[page.key]?.let { page.copy(artUrl = it) } ?: page,
            onPrimary = play,
            onPlayFromStart = { watchVm.delete(videoId); play() },
            onDetails = { page.item?.movieUuid?.let { u -> viewModel.noteMovieTitle(u, page.title); onMovieClick(u) } ?: page.item?.seriesId?.let(onSeriesClick) },
            onRemove = { watchVm.delete(videoId) },
            isOnWatchlist = page.item?.key in watchlistKeys,
            onToggleWatchlist = page.item?.let { item -> { watchlistVm.toggle(item) } },
        )
    }
    val wlCard: @Composable (MediaHeroPage) -> Unit = { page ->
        val item = page.item
        MediaHeroCard(
            page = backdrops[page.key]?.let { page.copy(artUrl = it) } ?: page,
            onPrimary = {
                item?.movieUuid?.let { u -> viewModel.noteMovieTitle(u, page.title); onPlayMovie(u) } ?: item?.seriesId?.let(onSeriesClick)
            },
            onPlayFromStart = {},
            onDetails = { item?.movieUuid?.let { u -> viewModel.noteMovieTitle(u, page.title); onMovieClick(u) } ?: item?.seriesId?.let(onSeriesClick) },
            onRemove = { item?.let { watchlistVm.remove(it.key) } },
            removeLabel = "Remove from Watchlist",
        )
    }
    val searchExtras = buildList {
        if (personMatchName != null) add(PageRow("person") {
            Text(
                "Includes titles with $personMatchName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        })
        if (showProviderPills) add(PageRow("providers") {
            EdgeToEdgePillRow {
                item(key = "all") {
                    GenrePill("All Providers", selectedProviderId == null) { viewModel.selectProvider(null, kind == MediaKind.Movies) }
                }
                items(providerIds.size, key = { providerIds[it] }) { i ->
                    val pid = providerIds[i]
                    GenrePill(state.providerNames[pid] ?: "Provider $pid", selectedProviderId == pid) {
                        viewModel.selectProvider(if (selectedProviderId == pid) null else pid, kind == MediaKind.Movies)
                    }
                }
            }
        })
    }

    MediaPageScaffold(
        gridState = gridState,
        compact = compact,
        decks = listOf(
            PageDeck("Continue Watching", heroPages, { it.key }, heroCard),
            PageDeck("Watchlist", watchlistPages, { it.key }, wlCard),
        ),
        headerTitle = if (isSearching) "Results" else kind.libraryTitle,
        headerCount = gridItems.size,
        sortMenu = {
            DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                MediaSortOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = { Text(order.label) },
                        trailingIcon = { if (order == sortOrder) Icon(Icons.Filled.Check, contentDescription = null) },
                        onClick = {
                            showSort = false
                            if (kind == MediaKind.Movies) settingsVm.setMoviesSortOrder(order.wire)
                            else settingsVm.setSeriesSortOrder(order.wire)
                        },
                    )
                }
            }
        },
        onSort = { showSort = true },
        onFilter = { showManageGroups = true },
        searchEnabled = true,
        searchActive = searchActive,
        query = query,
        onQueryChange = { submitQuery(it) },
        onSearchToggle = { searchActive = !searchActive; if (!searchActive) submitQuery("") },
        searchPlaceholder = if (kind == MediaKind.Movies) "Search movies" else "Search TV shows",
        isSearching = isSearching,
        searchExtras = searchExtras,
        pills = genrePills,
        selectedPill = selectedGenre,
        onPill = { selectedGenre = it },
        gridItems = gridItems,
        gridKey = { it.key },
        cell = { item ->
            val open = { item.movieUuid?.let(onMovieClick) ?: item.seriesId?.let(onSeriesClick); Unit }
            MediaPosterCard(
                item = item,
                onClick = open,
                menu = { close ->
                    DropdownMenuItem(text = { Text("Details") }, onClick = { close(); open() })
                    DropdownMenuItem(
                        text = { Text(if (item.key in watchlistKeys) "Remove from Watchlist" else "Add to Watchlist") },
                        onClick = { close(); watchlistVm.toggle(item) },
                    )
                },
            )
        },
        emptyContent = {
            if (isLoading || (!isSearching && libraryPending) || (isSearching && (state.isSearching || state.isSearchingSeries))) CircularProgressIndicator()
            else Text(if (isSearching) "No results" else kind.emptyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        railLetters = available,
        railIndexOf = { letter -> library.indexOfFirst { it.bucket == letter } },
        isRefreshing = isLoading && gridItems.isNotEmpty(),
        onRefresh = { if (kind == MediaKind.Movies) viewModel.refresh() else viewModel.refreshSeries() },
    )

    if (showManageGroups && groupNames.isNotEmpty()) {
        ManageGroupsSheet(
            allGroups = groupNames,
            hiddenGroups = hiddenGroups,
            onSave = { if (kind == MediaKind.Movies) settingsVm.setHiddenMovieGroups(it) else settingsVm.setHiddenSeriesGroups(it) },
            onDismiss = { showManageGroups = false },
        )
    }
}

@Composable
internal fun EdgeToEdgePillRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val compact = rememberLiveTvFormFactor().widthClass == WindowWidthSizeClass.Compact
    // The grid pads 18 dp each side; widen over both so the pills reach the
    // screen edges, and the row's own 16 dp lead keeps the first pill at 16.
    Box(modifier = Modifier.layout { measurable, constraints ->
        val extra = 18.dp.roundToPx() + 18.dp.roundToPx()
        val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = 0))
        layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-18.dp.roundToPx(), 0) }
    }) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp),
            content = content,
        )
    }
}

@Composable
internal fun GenrePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
    ) {
        // iPhone pill: 15pt medium, 33pt tall. Roboto's default line box is
        // taller than SF's, so the line height is pinned (Logan 2026-09-09).
        Text(
            label, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 2:3 poster, 8 dp corners, rating badge bottom-end, two-line title, year line. Long press opens [menu]. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaPosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = { if (menu != null) menuOpen = true }),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // Anchored inside the poster Box: as a direct Column child the
            // zero-size popup anchor still collected a spacedBy gap and nudged
            // the card down while the menu was open.
            if (menu != null) {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
            }
            // A small spinner where the poster will be while the art is
            // missing or still loading; the title is under the box (Logan
            // 2026-09-09, both platforms).
            var artLoaded by remember(item.posterUrl) { mutableStateOf(false) }
            if (!artLoaded) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    onSuccess = { artLoaded = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // One decimal like iOS ("5.851" -> "5.9"); blank when not a number.
            val rating = formatRating(item.rating)
            if (rating.isNotEmpty()) {
                Text(
                    rating, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                )
            }
        }
        // Centered under the poster (Logan 2026-09-10, all platforms).
        Text(
            item.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().height(30.dp),
        )
        Text(
            item.year?.toString() ?: " ", fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
    }
}
