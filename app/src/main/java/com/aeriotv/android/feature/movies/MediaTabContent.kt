package com.aeriotv.android.feature.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
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
    viewModel: OnDemandViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    val library: List<MediaItem> = remember(state.movies, state.series, kind, hiddenGroups, selectedGenre, sortOrder) {
        val all = if (kind == MediaKind.Movies) state.movies.map { it.toMediaItem() } else state.series.map { it.toMediaItem() }
        all.asSequence()
            .filter { it.category == null || it.category !in hiddenGroups }
            .filter { selectedGenre == null || it.category == selectedGenre }
            .toList()
            .sortedBy(sortOrder)
    }
    val results: List<MediaItem> = remember(state.searchResults, state.seriesSearchResults, kind, sortOrder) {
        (if (kind == MediaKind.Movies) state.searchResults.map { it.toMediaItem() } else state.seriesSearchResults.map { it.toMediaItem() })
            .sortedBy(sortOrder)
    }
    val gridItems = if (isSearching) results else library
    val available = remember(library) { library.map { it.bucket }.toSet() }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Full-span leading items: room, header, (search), (pills).
    val leadingCount = 2 + (if (searchActive) 1 else 0) + (if (!isSearching && genrePills.isNotEmpty()) 1 else 0)
    val railVisible by remember(leadingCount, library.size) {
        derivedStateOf { compact && !isSearching && library.size >= 9 && gridState.firstVisibleItemIndex >= 1 }
    }
    val bottomInset = LocalTabBarBottomInset.current

    fun submitQuery(v: String) {
        query = v
        if (kind == MediaKind.Movies) viewModel.setSearchQuery(v) else viewModel.setSeriesSearchQuery(v)
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PullToRefreshBox(
            isRefreshing = isLoading && gridItems.isNotEmpty(),
            onRefresh = { if (kind == MediaKind.Movies) viewModel.refresh() else viewModel.refreshSeries() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyVerticalGrid(
                columns = if (compact) GridCells.Fixed(3) else GridCells.Adaptive(minSize = 120.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = if (compact) 16.dp + 18.dp else 16.dp,
                    top = 0.dp, bottom = bottomInset + 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "room", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(6.dp)) }
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    LibraryHeader(
                        title = if (isSearching) "Results" else kind.libraryTitle,
                        count = gridItems.size,
                        onTitleTap = { scope.launch { gridState.animateScrollToItem(1) } },
                        onSearch = { searchActive = !searchActive; if (!searchActive) submitQuery("") },
                        onSort = { showSort = true },
                        onFilter = { showManageGroups = true },
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
                    )
                }
                if (searchActive) {
                    item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { submitQuery(it) },
                            placeholder = { Text(if (kind == MediaKind.Movies) "Search movies" else "Search TV shows") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (!isSearching && genrePills.isNotEmpty()) {
                    item(key = "pills", span = { GridItemSpan(maxLineSpan) }) {
                        GenrePills(
                            pills = genrePills,
                            selected = selectedGenre,
                            onSelect = { selectedGenre = if (selectedGenre == it) null else it },
                        )
                    }
                }
                if (gridItems.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                            if (isLoading || (isSearching && (state.isSearching || state.isSearchingSeries))) CircularProgressIndicator()
                            else Text(if (isSearching) "No results" else kind.emptyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(gridItems, key = { it.key }) { item ->
                    MediaPosterCard(item = item, onClick = {
                        item.movieUuid?.let(onMovieClick) ?: item.seriesId?.let(onSeriesClick)
                    })
                }
            }
        }
        if (railVisible) {
            AlphabetRail(
                available = available,
                onLetter = { letter ->
                    val idx = library.indexOfFirst { it.bucket == letter }
                    if (idx >= 0) scope.launch { gridState.scrollToItem(leadingCount + idx) }
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
            )
        }
    }

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
private fun LibraryHeader(
    title: String,
    count: Int,
    onTitleTap: () -> Unit,
    onSearch: () -> Unit,
    onSort: () -> Unit,
    onFilter: () -> Unit,
    sortMenu: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.clickable(onClick = onTitleTap),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text(count.toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                 modifier = Modifier.padding(bottom = 1.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeaderCircle(Icons.Filled.Search, "Search", onSearch)
            Box { HeaderCircle(Icons.Filled.SwapVert, "Sort", onSort); sortMenu() }
            HeaderCircle(Icons.Filled.FilterList, "Filter", onFilter)
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

@Composable
private fun GenrePills(pills: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "all") { GenrePill("All", selected == null) { onSelect(null) } }
        items(pills.size, key = { pills[it] }) { i -> GenrePill(pills[i], selected == pills[i]) { onSelect(pills[i]) } }
    }
}

@Composable
private fun GenrePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 2:3 poster, 8 dp corners, rating badge bottom-end, two-line title, year line. */
@Composable
fun MediaPosterCard(item: MediaItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    item.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 4,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
            }
            val rating = item.rating?.trim().orEmpty()
            if (rating.isNotEmpty() && rating != "0" && rating != "0.0") {
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
        Text(
            item.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp,
            modifier = Modifier.height(30.dp),
        )
        Text(
            item.year?.toString() ?: " ", fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}
