package com.aeriotv.android.feature.ondemand

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aeriotv.android.feature.livetv.ManageGroupsSheet
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.TvMenuGuard
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import com.aeriotv.android.core.network.DispatcharrVODMovie
import com.aeriotv.android.core.network.DispatcharrVODSeries
import com.aeriotv.android.feature.livetv.rememberLiveTvFormFactor
import com.aeriotv.android.feature.main.LocalTvChromeCollapsed
import com.aeriotv.android.feature.main.collapsibleChrome
import com.aeriotv.android.feature.miniplayer.MiniPlayerSession
import com.aeriotv.android.feature.miniplayer.MiniPlayerViewModel
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
import com.aeriotv.android.ui.scale.WithDisplayScale
import com.aeriotv.android.ui.tv.tvFocusScale
import kotlinx.coroutines.launch

/**
 * On Demand tab shell. Mirrors iOS OnDemandView (Aerio/Features/VOD/OnDemandView.swift):
 * "Movies" / "Series" pill segment selector above the active sub-view.
 *
 * Phase 10b ships Movies fully wired (browse + play). Series is a placeholder
 * until Phase 10c lands the `/api/vod/series/` endpoint, episode picker, and
 * WatchProgress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnDemandTabContent(
    modifier: Modifier = Modifier,
    onMovieClick: (DispatcharrVODMovie) -> Unit = {},
    onSeriesClick: (DispatcharrVODSeries) -> Unit = {},
    onEpisodeResume: (String) -> Unit = {},
    // Issue #9: resume an in-progress MOVIE by id from the Continue Watching
    // tab (opens the movie detail, which surfaces the Resume button). Episodes
    // use onEpisodeResume.
    onResumeMovie: (String) -> Unit = {},
    // Global Search (parity #41): opens the full Search screen (movies / shows /
    // EPG). Surfaced on the Movies / Series header rows here in addition to the
    // Live TV globe, because "search for shows" is naturally looked for on the
    // On Demand tab (Discord discoverability report).
    onOpenSearch: () -> Unit = {},
    viewModel: OnDemandViewModel = hiltViewModel(),
    watchVm: WatchProgressViewModel = hiltViewModel(),
) {
    var section by rememberSaveable { mutableStateOf(OnDemandSection.Movies) }

    // Issue #9: Continue Watching is its own On Demand sub-tab now (movies +
    // episodes), replacing the rails that sat squished atop the Movies / Series
    // grids. Its pill only appears when there is in-progress content. Rows are
    // self-contained (title/poster/position/duration/vodType) so no library
    // join is needed; an item is "in progress" if it has a position, is not
    // flagged finished, and is not within 5 minutes of the end (the same
    // heuristic the old rails and iOS use).
    val recentProgress by watchVm.observeRecent(30).collectAsStateWithLifecycle(initialValue = emptyList())
    val continueWatchingRows = remember(recentProgress) {
        recentProgress.filter { row ->
            row.positionMs > 0L && !row.isFinished &&
                (row.durationMs <= 0L || row.positionMs < row.durationMs - 5 * 60 * 1000L)
        }
    }
    val availableSections = remember(continueWatchingRows.isNotEmpty()) {
        buildList {
            if (continueWatchingRows.isNotEmpty()) add(OnDemandSection.ContinueWatching)
            add(OnDemandSection.Movies)
            add(OnDemandSection.Series)
        }
    }
    // If Continue Watching empties while it is the selected tab (last item
    // finished / removed), fall back to Movies so the pill can disappear
    // without stranding the user on a vanished tab.
    LaunchedEffect(availableSections) {
        if (section !in availableSections) section = OnDemandSection.Movies
    }
    // XC libraries are probed cheaply at init but the heavy per-category walk is
    // deferred until the tab is actually shown -- kick it off here (no-op for
    // non-XC sources and once already loaded).
    LaunchedEffect(Unit) { viewModel.loadXtreamItemsIfNeeded() }
    val settingsVm: SettingsViewModel = hiltViewModel()
    val scale by settingsVm.displayScaleMovies.collectAsStateWithLifecycle(initialValue = 1.0f)

    val tabIsTv = rememberLiveTvFormFactor().isTv
    // Grid scroll state is hoisted out of the sub-screens so (a) BOTH the
    // chrome-collapse logic here and the per-grid BringIntoViewSpec / BACK
    // handling can observe it, and (b) the Movies scroll position survives a
    // Movies -> Series -> Movies pill round-trip. rememberLazyGridState is
    // saveable, so the detail-return scroll restore keeps working unchanged.
    val moviesGridState = rememberLazyGridState()
    val seriesGridState = rememberLazyGridState()
    val continueGridState = rememberLazyGridState()
    val activeGridState = when (section) {
        OnDemandSection.Movies -> moviesGridState
        OnDemandSection.Series -> seriesGridState
        OnDemandSection.ContinueWatching -> continueGridState
    }
    // TV chrome collapse: once the user scrolls past the first poster row,
    // report "collapsed" to the shell (top tab bar) and shrink the segment
    // pills below, so the reclaimed height shows more posters. derivedStateOf
    // keeps this from recomposing on every scroll frame -- it only flips at
    // the row-0 boundary.
    val chromeCollapsed = LocalTvChromeCollapsed.current
    val scrolled by remember(activeGridState) {
        derivedStateOf { activeGridState.firstVisibleItemIndex > 0 }
    }
    if (tabIsTv && chromeCollapsed != null) {
        LaunchedEffect(scrolled) { chromeCollapsed.value = scrolled }
        // Leaving the tab (or losing the TV shell) must never strand the
        // top bar collapsed for the next tab.
        DisposableEffect(Unit) {
            onDispose { chromeCollapsed.value = false }
        }
    }
    WithDisplayScale(scale = scale) {
    Column(modifier = modifier.fillMaxSize()) {
        // Phone: centered title bar matching the other tabs. TV: no title --
        // the selected nav pill already says where you are, the Live TV tab
        // has no title either, and at 10 feet the 64dp bar was the top slice
        // of a chrome stack that consumed half the screen before any poster.
        if (!tabIsTv) {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "On Demand",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        }

        // Same collapse treatment as the shell's top tab bar: shrink, never
        // unmount (the pills hold D-pad focus targets), and focus arriving on
        // a pill expands the row back even while scrolled.
        var pillsHaveFocus by remember { mutableStateOf(false) }
        val pillsFraction by animateFloatAsState(
            targetValue = if (tabIsTv && scrolled && !pillsHaveFocus) 0f else 1f,
            animationSpec = tween(durationMillis = 250),
            label = "vodPillsCollapse",
        )
        Box(
            modifier = if (tabIsTv) {
                Modifier
                    .onFocusChanged { pillsHaveFocus = it.hasFocus }
                    .collapsibleChrome(pillsFraction)
            } else {
                Modifier
            },
        ) {
            SegmentPills(
                sections = availableSections,
                current = section,
                onSelect = { section = it },
            )
        }

        when (section) {
            OnDemandSection.ContinueWatching -> ContinueWatchingSubScreen(
                viewModel = viewModel,
                movieRows = continueWatchingRows.filter { it.vodType != "episode" },
                episodeRows = continueWatchingRows.filter { it.vodType == "episode" },
                onResumeMovie = onResumeMovie,
                onEpisodeResume = onEpisodeResume,
                onSeriesClick = onSeriesClick,
                onRemove = { watchVm.delete(it) },
            )
            OnDemandSection.Movies -> MoviesSubScreen(
                viewModel = viewModel,
                onMovieClick = onMovieClick,
                gridState = moviesGridState,
                onOpenSearch = onOpenSearch,
            )
            OnDemandSection.Series -> SeriesSubScreen(
                viewModel = viewModel,
                onSeriesClick = onSeriesClick,
                onEpisodeResume = onEpisodeResume,
                gridState = seriesGridState,
                onOpenSearch = onOpenSearch,
            )
        }
    }
    }
}

@Composable
private fun SegmentPills(
    sections: List<OnDemandSection>,
    current: OnDemandSection,
    onSelect: (OnDemandSection) -> Unit,
) {
    // tvOS parity: the Movies / TV Shows segmented control sits in the
    // CENTER of the header, not stretched edge-to-edge. Each pill claims
    // only as much width as its content needs (with a sensible minimum so
    // the focus highlight isn't a tight box around the text). On phone we
    // keep the existing full-width split because the segmented control IS
    // the dominant nav affordance on a 6"/7" portrait screen -- tucking it
    // into a small centered island would waste vertical layout. iOS
    // MoviesView / TVShowsView shows the same instinct: tvOS uses a
    // compact, centered Segmented Picker; iOS uses a full-width one.
    val isTv = rememberLiveTvFormFactor().isTv
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (isTv) 4.dp else 8.dp),
        horizontalArrangement = if (isTv) Arrangement.Center else Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sections.forEachIndexed { index, entry ->
            if (isTv && index > 0) Spacer(Modifier.width(12.dp))
            SegmentPill(
                label = entry.label,
                icon = entry.icon,
                selected = entry == current,
                onClick = { onSelect(entry) },
                modifier = if (isTv) {
                    // Just enough room for the longest label + icon + a bit
                    // of breathing room for the focus border.
                    Modifier.widthIn(min = 160.dp)
                } else {
                    Modifier.weight(1f)
                },
            )
        }
    }
}

@Composable
private fun SegmentPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    // D-pad focus ring (same 2dp white ring as guide pills / program cells)
    // so the focused pill is distinguishable from the selected one at 10 feet.
    var pillFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .onFocusChanged { pillFocused = it.isFocused }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(
                if (pillFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(50))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Continue Watching sub-tab (issue #9). Two horizontal shelves -- in-progress
 * Movies and in-progress TV Shows -- reusing the same rail + card components
 * that used to sit squished atop the Movies / Series grids. The reporter asked
 * for exactly this ("a separate tab ... horizontal scrolling 2 rows"). The pill
 * that leads here is only shown when at least one shelf has content, so this is
 * never reached empty; the guard is defensive.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingSubScreen(
    viewModel: OnDemandViewModel,
    movieRows: List<WatchProgressEntity>,
    episodeRows: List<WatchProgressEntity>,
    onResumeMovie: (String) -> Unit,
    onEpisodeResume: (String) -> Unit,
    onSeriesClick: (DispatcharrVODSeries) -> Unit,
    onRemove: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val seriesById = remember(state.series) { state.series.associateBy { it.id } }
    if (movieRows.isEmpty() && episodeRows.isEmpty()) {
        EmptyState(
            title = "Nothing in progress",
            body = "Movies and shows you start will show up here so you can pick up where you left off.",
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        if (movieRows.isNotEmpty()) {
            ContinueWatchingRail(
                title = "Movies",
                items = movieRows,
                posterFor = { it.posterUrl },
                // Resume by id: opens the movie detail (with its Resume button),
                // so it works even for a movie that hasn't paged into the grid.
                onItemClick = { onResumeMovie(it.videoId) },
                onRemove = { onRemove(it.videoId) },
            )
        }
        if (episodeRows.isNotEmpty()) {
            SeriesContinueWatchingRail(
                title = "TV Shows",
                items = episodeRows,
                seriesById = seriesById,
                onItemClick = { onEpisodeResume(it.videoId) },
                onRemove = { onRemove(it.videoId) },
                onOpenSeries = { row ->
                    row.seriesId?.toIntOrNull()?.let { seriesById[it] }?.let(onSeriesClick)
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MoviesSubScreen(
    viewModel: OnDemandViewModel,
    onMovieClick: (DispatcharrVODMovie) -> Unit,
    gridState: LazyGridState,
    onOpenSearch: () -> Unit = {},
    watchVm: WatchProgressViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isTv = rememberLiveTvFormFactor().isTv
    // VOD group hide filter (iOS MoviesView hiddenMovieGroups, MoviesView.swift:74).
    // Categories are tagged on each movie row by OnDemandViewModel (from the XC
    // get_vod_categories lookup); items with no categoryName fall under the
    // "Uncategorized" bucket so the user can hide that too.
    val hiddenMovieGroups by settingsVm.hiddenMovieGroups
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val allMovieGroups = remember(state.movies, state.movieGroupNames) {
        // Server-authoritative names from /api/vod/categories/ cover the FULL
        // library, including groups whose items haven't paged in yet; deriving
        // from loaded items stays as the fallback for sources without the
        // endpoint (XC). The Uncategorized bucket is offered only when some
        // LOADED item actually lacks a group -- the endpoint never lists it.
        val base = state.movieGroupNames.ifEmpty {
            state.movies.asSequence()
                .mapNotNull { it.categoryName }
                .distinct().toList().sorted()
        }
        if (state.movies.any { it.categoryName == null }) {
            (base + UNCATEGORIZED).distinct()
        } else {
            base
        }
    }
    var showManageGroups by rememberSaveable { mutableStateOf(false) }
    // BACK from MovieDetail must land D-pad focus back on the poster / rail
    // card that opened it, not the top nav pills. See VodReturnFocusState.
    val returnFocus = rememberVodReturnFocus(isTv)
    // TV BACK ladder, copied from GuideScreen: scrolled into the grid -> one
    // BACK returns to the top-left poster; already at the top -> this handler
    // is DISABLED so BACK falls through to MainScaffold's tab -> Live TV
    // handler. Also stands down while the mini-player is up so its own
    // root-level BackHandler can dismiss it (Compose dispatches Back LIFO and
    // this handler is registered after the overlay's).
    val miniPlayerVm: MiniPlayerViewModel = hiltViewModel()
    val miniState by miniPlayerVm.state.collectAsStateWithLifecycle()
    val miniActive = miniState is MiniPlayerSession.State.Active
    val atTop by remember(gridState) {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
        }
    }
    val backScope = rememberCoroutineScope()
    val firstPosterFocus = remember { FocusRequester() }
    androidx.activity.compose.BackHandler(enabled = isTv && !miniActive && !atTop) {
        backScope.launch {
            gridState.animateScrollToItem(0)
            // The index-0 poster can compose a frame or two after the scroll
            // settles; retry briefly, then give up quietly (focus stays put).
            repeat(10) {
                if (runCatching { firstPosterFocus.requestFocus() }.isSuccess) return@launch
                kotlinx.coroutines.delay(16L)
            }
        }
    }

    if (state.unsupportedSource) {
        EmptyState(
            title = "Movies needs Dispatcharr",
            body = "Switch to a Dispatcharr playlist in Settings to browse movies.",
        )
        return
    }

    // (Continue Watching moved to its own On Demand sub-tab, issue #9.)

    // Apply the hide filter once at this point in the pipeline; everything
    // below renders from `visibleFiltered`. The total still reflects the
    // server count so the user can see how many they've hidden.
    val visibleFiltered = remember(state.visible, hiddenMovieGroups) {
        if (hiddenMovieGroups.isEmpty()) state.visible
        else state.visible.filter { (it.categoryName ?: UNCATEGORIZED) !in hiddenMovieGroups }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        VodHeaderRow(
            searchField = {
                VodSearchField(
                    query = state.searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    placeholder = "Search movies",
                    isTv = isTv,
                )
            },
            hiddenCount = hiddenMovieGroups.size,
            onManageGroups = { showManageGroups = true },
            onOpenSearch = onOpenSearch,
            isTv = isTv,
            countLabel = state.totalCount.takeIf { it > 0 }?.let { total ->
                "${visibleFiltered.size} / $total"
            },
        )

        val countLabel = state.totalCount.takeIf { it > 0 }?.let { total ->
            "${visibleFiltered.size} / $total"
        }
        if (countLabel != null && !isTv) {
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (state.isLoading && state.movies.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return@Column
        }
        state.error?.let { err ->
            if (state.movies.isEmpty()) {
                Text(
                    text = "Couldn't load movies: $err",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
                return@Column
            }
        }
        if (visibleFiltered.isEmpty()) {
            EmptyState(
                title = when {
                    state.searchQuery.isNotBlank() -> "No matches"
                    hiddenMovieGroups.isNotEmpty() -> "Everything's hidden"
                    else -> "No movies"
                },
                body = when {
                    state.searchQuery.isNotBlank() -> "Try a different search term."
                    hiddenMovieGroups.isNotEmpty() -> "All ${hiddenMovieGroups.size} group${if (hiddenMovieGroups.size == 1) "" else "s"} you chose to hide accounts for every movie in this library. Use the filter button to show some again."
                    else -> "Dispatcharr returned an empty Movies library. Confirm VOD is enabled on the server."
                },
            )
            return@Column
        }

        // TV: deadband spec kills the horizontal-move vertical jump (see
        // com.aeriotv.android.ui.tv.TvLargeCardBringIntoViewSpec). Phone keeps the inherited default.
        val bringIntoViewSpec =
            if (isTv) com.aeriotv.android.ui.tv.TvLargeCardBringIntoViewSpec
            else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
        CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides bringIntoViewSpec,
        ) {
        LazyVerticalGrid(
            // Larger posters + overscan-safe padding on the 10-foot TV; phone
            // keeps the compact grid whose 104dp bottom clears the bottom
            // NavigationBar (TV has top tabs, so it needs far less bottom inset).
            columns = GridCells.Adaptive(minSize = if (isTv) 108.dp else 120.dp),
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            contentPadding = PaddingValues(
                start = if (isTv) 48.dp else 12.dp,
                end = if (isTv) 48.dp else 12.dp,
                top = if (isTv) 16.dp else 8.dp,
                bottom = if (isTv) 32.dp else 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
        ) {
            // (Continue Watching is its own On Demand sub-tab now, issue #9.)
            itemsIndexed(items = visibleFiltered, key = { _, it -> it.id }) { index, movie ->
                // Prefetch the next page as the user nears the end of what's
                // loaded. Browse only -- search results aren't paginated here,
                // and the cursor guard stops once the library is fully walked.
                if (state.moviesNextCursor != null &&
                    state.searchQuery.isBlank() &&
                    index >= visibleFiltered.size - 8
                ) {
                    LaunchedEffect(visibleFiltered.size) { viewModel.loadMoreMovies() }
                }
                MoviePoster(
                    movie = movie,
                    isTv = isTv,
                    focusRequester = returnFocus.requesterFor("movie:${movie.uuid}"),
                    // Index 0 additionally carries the BACK-to-top requester;
                    // a separate hook so it never disturbs VodReturnFocusState.
                    modifier = if (isTv && index == 0) {
                        Modifier.focusRequester(firstPosterFocus)
                    } else {
                        Modifier
                    },
                    onClick = {
                        returnFocus.arm("movie:${movie.uuid}")
                        onMovieClick(movie)
                    },
                )
            }
        }
        }
    }

    if (showManageGroups && allMovieGroups.isNotEmpty()) {
        ManageGroupsSheet(
            allGroups = allMovieGroups,
            hiddenGroups = hiddenMovieGroups,
            onSave = { settingsVm.setHiddenMovieGroups(it) },
            onDismiss = { showManageGroups = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesSubScreen(
    viewModel: OnDemandViewModel,
    onSeriesClick: (DispatcharrVODSeries) -> Unit,
    onEpisodeResume: (String) -> Unit = {},
    gridState: LazyGridState,
    onOpenSearch: () -> Unit = {},
    watchVm: WatchProgressViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isTv = rememberLiveTvFormFactor().isTv
    // Series-side group hide filter, mirrors MoviesSubScreen above.
    val hiddenSeriesGroups by settingsVm.hiddenSeriesGroups
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val allSeriesGroups = remember(state.series, state.seriesGroupNames) {
        // Endpoint-driven names first, items-derived fallback, Uncategorized
        // only when a loaded item lacks a group -- see allMovieGroups above.
        val base = state.seriesGroupNames.ifEmpty {
            state.series.asSequence()
                .mapNotNull { it.categoryName }
                .distinct().toList().sorted()
        }
        if (state.series.any { it.categoryName == null }) {
            (base + UNCATEGORIZED).distinct()
        } else {
            base
        }
    }
    var showManageGroups by rememberSaveable { mutableStateOf(false) }
    // BACK from SeriesDetail / the episode player must land D-pad focus back
    // on the poster / rail card that opened it. See VodReturnFocusState.
    val returnFocus = rememberVodReturnFocus(isTv)
    // TV BACK ladder, same wiring as MoviesSubScreen above.
    val miniPlayerVm: MiniPlayerViewModel = hiltViewModel()
    val miniState by miniPlayerVm.state.collectAsStateWithLifecycle()
    val miniActive = miniState is MiniPlayerSession.State.Active
    val atTop by remember(gridState) {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
        }
    }
    val backScope = rememberCoroutineScope()
    val firstPosterFocus = remember { FocusRequester() }
    androidx.activity.compose.BackHandler(enabled = isTv && !miniActive && !atTop) {
        backScope.launch {
            gridState.animateScrollToItem(0)
            repeat(10) {
                if (runCatching { firstPosterFocus.requestFocus() }.isSuccess) return@launch
                kotlinx.coroutines.delay(16L)
            }
        }
    }
    // (Continue Watching episodes moved to the Continue Watching sub-tab, #9.)

    if (state.unsupportedSource) {
        EmptyState(
            title = "Series needs Dispatcharr",
            body = "Switch to a Dispatcharr playlist in Settings to browse series.",
        )
        return
    }

    val visibleSeriesFiltered = remember(state.visibleSeries, hiddenSeriesGroups) {
        if (hiddenSeriesGroups.isEmpty()) state.visibleSeries
        else state.visibleSeries.filter { (it.categoryName ?: UNCATEGORIZED) !in hiddenSeriesGroups }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        VodHeaderRow(
            searchField = {
                VodSearchField(
                    query = state.seriesSearchQuery,
                    onQueryChange = viewModel::setSeriesSearchQuery,
                    placeholder = "Search series",
                    isTv = isTv,
                )
            },
            hiddenCount = hiddenSeriesGroups.size,
            onManageGroups = { showManageGroups = true },
            onOpenSearch = onOpenSearch,
            isTv = isTv,
            countLabel = state.seriesTotalCount.takeIf { it > 0 }?.let { total ->
                "${visibleSeriesFiltered.size} / $total"
            },
        )

        val countLabel = state.seriesTotalCount.takeIf { it > 0 }?.let { total ->
            "${visibleSeriesFiltered.size} / $total"
        }
        if (countLabel != null && !isTv) {
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (state.isLoadingSeries && state.series.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return@Column
        }
        state.seriesError?.let { err ->
            if (state.series.isEmpty()) {
                Text(
                    text = "Couldn't load series: $err",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
                return@Column
            }
        }
        if (visibleSeriesFiltered.isEmpty()) {
            EmptyState(
                title = when {
                    state.seriesSearchQuery.isNotBlank() -> "No matches"
                    hiddenSeriesGroups.isNotEmpty() -> "Everything's hidden"
                    else -> "No series"
                },
                body = when {
                    state.seriesSearchQuery.isNotBlank() -> "Try a different search term."
                    hiddenSeriesGroups.isNotEmpty() -> "All ${hiddenSeriesGroups.size} group${if (hiddenSeriesGroups.size == 1) "" else "s"} you chose to hide accounts for every series in this library. Use the filter button to show some again."
                    else -> "Dispatcharr returned an empty Series library. Confirm VOD is enabled on the server."
                },
            )
            return@Column
        }

        // TV: same deadband spec as the Movies grid (com.aeriotv.android.ui.tv.TvLargeCardBringIntoViewSpec).
        val bringIntoViewSpec =
            if (isTv) com.aeriotv.android.ui.tv.TvLargeCardBringIntoViewSpec
            else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
        CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides bringIntoViewSpec,
        ) {
        LazyVerticalGrid(
            // Series tab matches the Movies tab's TV / phone grid metrics.
            columns = GridCells.Adaptive(minSize = if (isTv) 108.dp else 120.dp),
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            contentPadding = PaddingValues(
                start = if (isTv) 48.dp else 12.dp,
                end = if (isTv) 48.dp else 12.dp,
                top = if (isTv) 16.dp else 8.dp,
                bottom = if (isTv) 32.dp else 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
        ) {
            // (Continue Watching episodes are on the Continue Watching sub-tab, #9.)
            itemsIndexed(items = visibleSeriesFiltered, key = { _, it -> it.id }) { index, series ->
                if (state.seriesNextCursor != null &&
                    state.seriesSearchQuery.isBlank() &&
                    index >= visibleSeriesFiltered.size - 8
                ) {
                    LaunchedEffect(visibleSeriesFiltered.size) { viewModel.loadMoreSeries() }
                }
                SeriesPoster(
                    series = series,
                    isTv = isTv,
                    focusRequester = returnFocus.requesterFor("series:${series.id}"),
                    // Index 0 additionally carries the BACK-to-top requester;
                    // a separate hook so it never disturbs VodReturnFocusState.
                    modifier = if (isTv && index == 0) {
                        Modifier.focusRequester(firstPosterFocus)
                    } else {
                        Modifier
                    },
                    onClick = {
                        returnFocus.arm("series:${series.id}")
                        onSeriesClick(series)
                    },
                )
            }
        }
        }
    }

    if (showManageGroups && allSeriesGroups.isNotEmpty()) {
        ManageGroupsSheet(
            allGroups = allSeriesGroups,
            hiddenGroups = hiddenSeriesGroups,
            onSave = { settingsVm.setHiddenSeriesGroups(it) },
            onDismiss = { showManageGroups = false },
        )
    }
}

/** Search field + Manage-Groups filter icon, used by both the Movies and
 *  Series sub-screens. Mirrors the Live TV header row -- search occupies
 *  the bulk of the width, the filter icon sits flush right and turns
 *  primary-tinted when any groups are hidden so the user can see at a
 *  glance that the list is filtered. */
@Composable
private fun VodHeaderRow(
    searchField: @Composable RowScope.() -> Unit,
    hiddenCount: Int,
    onManageGroups: () -> Unit,
    onOpenSearch: (() -> Unit)? = null,
    isTv: Boolean = false,
    countLabel: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // TV: 48dp = the 5% overscan-safe margin the poster grid already
            // uses, and the library count joins this row instead of spending
            // its own line of vertical space.
            .padding(horizontal = if (isTv) 48.dp else 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isTv && countLabel != null) {
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) { searchField() }
        }
        // Global Search (parity #41): the globe-magnifier opens the full Search
        // screen (movies / shows / EPG), matching the Live TV header. Sits to the
        // left of the group filter, in the same relative slot as the guide's
        // globe, so "search for shows" is discoverable right here on On Demand.
        if (onOpenSearch != null) {
            if (isTv) {
                TvHeaderIconButton(
                    icon = Icons.Filled.TravelExplore,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onOpenSearch,
                )
            } else {
                IconButton(
                    onClick = onOpenSearch,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.TravelExplore,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(if (isTv) 8.dp else 4.dp))
        }
        if (isTv) {
            TvHeaderIconButton(
                icon = Icons.Filled.FilterList,
                contentDescription = "Filter groups",
                tint = if (hiddenCount == 0) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                onClick = onManageGroups,
            )
        } else {
            IconButton(
                onClick = onManageGroups,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.FilterList,
                    contentDescription = "Filter groups",
                    tint = if (hiddenCount == 0)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * Guide-chrome circle button for TV header controls: bare glyph at rest, the
 * shared 2dp white focus ring + soft fill when D-pad focused.
 */
@Composable
private fun TvHeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    FilledTonalIconButton(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier
            .size(30.dp)
            .then(
                if (focused) Modifier.border(2.dp, Color.White, CircleShape)
                else Modifier,
            ),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (focused) Color.White.copy(alpha = 0.15f) else Color.Transparent,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Stand-in group bucket for movies / series whose XC `category_id` is
 *  missing or has no matching get_vod_categories row. Lets the user hide
 *  that bucket the same way they hide a real group. */
private const val UNCATEGORIZED = "Uncategorized"

/**
 * Deadband [androidx.compose.foundation.gestures.BringIntoViewSpec] for the
 * TV poster grids, modeled on TvImeNoJitterBringIntoViewSpec but tuned for
 * poster cards instead of 1-2px IME cursor corrections.
 *
 * A D-pad LEFT/RIGHT move within a row must never produce a vertical scroll:
 * the focused poster's 2-line title strip + meta line make neighboring cards
 * report fractionally different heights to the focus system, so the default
 * minimal-nudge spec issued a small "correction" on every horizontal hop and
 * the whole grid visibly jumped up/down while the user was only moving
 * sideways. Only a genuine row change (the next card is truly off the
 * viewport edge) needs a scroll, and that correction is always at least a
 * full card height, far above the 24f deadband. Suppressing anything smaller
 * keeps horizontal traversal rock-steady without touching real scrolls.
 */
@OptIn(ExperimentalFoundationApi::class)

/**
 * BACK-from-detail D-pad focus restoration for the VOD grids/rails (TV only;
 * every member no-ops off TV so phone behavior is untouched).
 *
 * MovieDetail / SeriesDetail / the episode player are nav routes pushed ON TOP
 * of MAIN, so this whole tab is disposed while they're up; an in-composition
 * focusRestorer can't survive that. Instead the clicked item's key is written
 * to a rememberSaveable slot (which DOES survive via the back-stack's saved
 * state), the matching grid poster / rail card re-attaches [requester] when
 * the tab recomposes on return, and [restoreIfPending] pulls focus onto it --
 * otherwise the window's initial-focus assignment parks focus on the top nav
 * pills (user report).
 */
private class VodReturnFocusState(
    private val isTv: Boolean,
    private val pendingKeyState: MutableState<String?>,
) {
    val requester = FocusRequester()
    private val pendingKey: String? get() = pendingKeyState.value

    /** Record the item being opened so focus can return to it after BACK.
     *  Call right before the navigation callback. */
    fun arm(key: String) {
        if (isTv) pendingKeyState.value = key
    }

    /** The [FocusRequester] for [key]'s item, or null for every other item. */
    fun requesterFor(key: String): FocusRequester? =
        if (isTv && key == pendingKey) requester else null

    /** One-shot on the return composition: focus the armed item. The item
     *  composes a frame or two after the grid restores its scroll position,
     *  so retry until the requester is attached, then re-assert once more a
     *  few frames later in case the initial-focus fallback (nav pills) lands
     *  after the first success. Gives up quietly if the item is gone (e.g. a
     *  Continue Watching row that completed while watching). */
    suspend fun restoreIfPending() {
        if (!isTv || pendingKey == null) return
        repeat(20) {
            if (runCatching { requester.requestFocus() }.isSuccess) {
                kotlinx.coroutines.delay(48L)
                runCatching { requester.requestFocus() }
                pendingKeyState.value = null
                return
            }
            kotlinx.coroutines.delay(16L)
        }
        pendingKeyState.value = null
    }
}

@Composable
private fun rememberVodReturnFocus(isTv: Boolean): VodReturnFocusState {
    // The key lives in rememberSaveable so it survives the tab's disposal
    // while a detail route sits on top of MAIN. arm() writes it
    // synchronously in the click handler, before navigation saves state.
    val pendingKey = rememberSaveable { mutableStateOf<String?>(null) }
    val state = remember { VodReturnFocusState(isTv, pendingKey) }
    LaunchedEffect(Unit) { state.restoreIfPending() }
    return state
}

@Composable
private fun SeriesPoster(
    series: DispatcharrVODSeries,
    isTv: Boolean = false,
    /** Attached only on the item BACK should refocus (see VodReturnFocusState). */
    focusRequester: FocusRequester? = null,
    /** Extra hook for the grid's index-0 BACK-to-top requester. */
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .tvFocusScale(focused)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                .then(
                    // D-pad focus ring so the highlighted poster is obvious at
                    // 10 feet. No-op on touch (phone posters never gain focus).
                    if (isTv && focused)
                        Modifier.border(
                            3.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(10.dp),
                        )
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val poster = series.posterUrl
            if (!poster.isNullOrBlank()) {
                AsyncImage(
                    model = poster,
                    contentDescription = series.displayName,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(40.dp),
                )
            }
            // iOS parity: rating as an on-poster corner badge, see MoviePoster.
            VodRatingBadge(series.rating, Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = series.displayName.ifBlank { "Untitled" },
            style = if (isTv) MaterialTheme.typography.labelLarge
            else MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            // minLines 2 keeps the year meta line at the same height
            // across a grid row whether the title wraps or not.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        series.year?.let { year ->
            Text(
                text = year.toString(),
                style = if (isTv) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.labelSmall,
                // iOS parity: year = textSecondary tvOS / textTertiary iPhone.
                color = if (isTv) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun ContinueWatchingRail(
    items: List<WatchProgressEntity>,
    posterFor: (WatchProgressEntity) -> String?,
    onItemClick: (WatchProgressEntity) -> Unit,
    onRemove: (WatchProgressEntity) -> Unit,
    title: String = "Continue Watching",
    /** BACK-from-detail refocus hook: non-null only for the card the focus
     *  restore should land on (see VodReturnFocusState). */
    focusRequesterFor: (WatchProgressEntity) -> FocusRequester? = { null },
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(
                horizontal = if (rememberLiveTvFormFactor().isTv) 48.dp else 20.dp,
                vertical = 8.dp,
            ),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = if (rememberLiveTvFormFactor().isTv) 44.dp else 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items = items, key = { it.videoId }) { row ->
                ContinueWatchingCard(
                    row = row,
                    posterUrl = posterFor(row),
                    focusRequester = focusRequesterFor(row),
                    onClick = { onItemClick(row) },
                    onRemove = { onRemove(row) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingCard(
    row: WatchProgressEntity,
    posterUrl: String?,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val progress = if (row.durationMs > 0L) {
        (row.positionMs.toFloat() / row.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    var cardFocused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val tvGuard = rememberTvMenuGuard()
    Column(
        modifier = Modifier
            .width(140.dp)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier,
            )
            .onFocusChanged { cardFocused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            // Same D-pad focus ring as the poster grid; without it the rail
            // is reachable but invisible to focus at 10 feet.
            .then(
                if (cardFocused) Modifier.border(
                    3.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(10.dp),
                ) else Modifier,
            )
            // tvGuard: the OK RELEASE after a TV long-press is delivered as a
            // click (to this card or the menu's first row); arm/wrap swallows
            // it. Same wiring as the DVR recording rows.
            .combinedClickable(
                onClick = tvGuard.wrap(onClick),
                onLongClick = { menuOpen = true; tvGuard.arm() },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (!posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = row.title,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.PlayCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(36.dp),
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                drawStopIndicator = {},
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = row.title.ifBlank { "Untitled" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        val remainingMin = if (row.durationMs > row.positionMs) {
            ((row.durationMs - row.positionMs) / 60_000L).toInt().coerceAtLeast(1)
        } else 0
        if (remainingMin > 0) {
            Text(
                text = "$remainingMin min left",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
    if (menuOpen) {
        ContinueWatchingActionMenu(
            title = row.title.ifBlank { "Untitled" },
            guard = tvGuard,
            onDismiss = { menuOpen = false },
            onRemove = onRemove,
        )
    }
}

/**
 * Long-press menu for a Continue Watching rail card (movie + episode
 * variants share it). The house TvActionMenuDialog on BOTH form factors:
 * the rail card had no prior long-press behavior on phone, the centered
 * dialog reads fine on touch, and the guard no-ops off TV, so one idiom
 * serves both instead of forking a DropdownMenu branch.
 */
@Composable
private fun ContinueWatchingActionMenu(
    title: String,
    guard: TvMenuGuard,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    // iOS parity (16e3b8377, Models/VODModels.swift): the series-variant card
    // offers "Open Series" above Remove. Null for the movie variant, and null
    // when the parent series can't be resolved from the library cache.
    onOpenSeries: (() -> Unit)? = null,
) {
    TvActionMenuDialog(
        title = title,
        actions = buildList {
            if (onOpenSeries != null) {
                add(
                    TvMenuAction(
                        label = "Open Series",
                        icon = Icons.Outlined.Tv,
                    ) { onOpenSeries() },
                )
            }
            add(
                TvMenuAction(
                    label = "Remove from Continue Watching",
                    icon = Icons.Outlined.Delete,
                    destructive = true,
                ) { onRemove() },
            )
            // Dismiss-only escape hatch; the dialog itself dismisses before
            // running any action, so the click body is intentionally empty.
            add(TvMenuAction(label = "Cancel") {})
        },
        guard = guard,
        onDismiss = onDismiss,
    )
}

/**
 * Continue Watching rail for series episodes. iOS Issue #19 + 16e3b83 parity:
 * each card shows the parent SERIES poster + name with an "S1:E4 - Title"
 * subtitle (resolved from the series cache by seriesId), instead of the
 * episode's own still. Tapping resumes the episode directly.
 */
@Composable
private fun SeriesContinueWatchingRail(
    items: List<WatchProgressEntity>,
    seriesById: Map<Int, DispatcharrVODSeries>,
    onItemClick: (WatchProgressEntity) -> Unit,
    onRemove: (WatchProgressEntity) -> Unit,
    title: String = "Continue Watching",
    // iOS parity: long-press -> Open Series jumps to the full show page.
    onOpenSeries: (WatchProgressEntity) -> Unit = {},
    /** BACK-from-player refocus hook: non-null only for the card the focus
     *  restore should land on (see VodReturnFocusState). */
    focusRequesterFor: (WatchProgressEntity) -> FocusRequester? = { null },
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(
                horizontal = if (rememberLiveTvFormFactor().isTv) 48.dp else 20.dp,
                vertical = 8.dp,
            ),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = if (rememberLiveTvFormFactor().isTv) 44.dp else 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items = items, key = { it.videoId }) { row ->
                val series = row.seriesId?.toIntOrNull()?.let { seriesById[it] }
                SeriesContinueWatchingCard(
                    row = row,
                    // Prefer the loaded library's series art (iOS shows the
                    // parent SERIES poster on these cards); the stored row's
                    // own posterUrl (episode still) is the fallback.
                    seriesPoster = series?.posterUrl?.takeIf { it.isNotBlank() }
                        ?: row.posterUrl,
                    seriesName = series?.displayName ?: row.title,
                    focusRequester = focusRequesterFor(row),
                    onClick = { onItemClick(row) },
                    onRemove = { onRemove(row) },
                    // Only offer Open Series when the parent series is in the
                    // loaded library (mirrors iOS `if let parentSeries`).
                    onOpenSeries = series?.let { { onOpenSeries(row) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesContinueWatchingCard(
    row: WatchProgressEntity,
    seriesPoster: String?,
    seriesName: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    // Non-null only when the parent series resolved in the library cache.
    onOpenSeries: (() -> Unit)? = null,
) {
    val progress = if (row.durationMs > 0L) {
        (row.positionMs.toFloat() / row.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val tag = listOfNotNull(
        row.seasonNumber.takeIf { it > 0 }?.let { "S$it" },
        row.episodeNumber.takeIf { it > 0 }?.let { "E$it" },
    ).joinToString(":")
    val subtitle = listOf(tag, row.title).filter { it.isNotBlank() }.joinToString(" - ")
    var cardFocused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val tvGuard = rememberTvMenuGuard()
    Column(
        modifier = Modifier
            .width(140.dp)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier,
            )
            .onFocusChanged { cardFocused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            // Same D-pad focus ring as the poster grid; without it the rail
            // is reachable but invisible to focus at 10 feet.
            .then(
                if (cardFocused) Modifier.border(
                    3.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(10.dp),
                ) else Modifier,
            )
            // Same long-press menu + spurious-OK-release guard wiring as
            // ContinueWatchingCard above.
            .combinedClickable(
                onClick = tvGuard.wrap(onClick),
                onLongClick = { menuOpen = true; tvGuard.arm() },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (!seriesPoster.isNullOrBlank()) {
                AsyncImage(
                    model = seriesPoster,
                    contentDescription = seriesName,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.PlayCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(36.dp),
                )
            }
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    drawStopIndicator = {},
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = seriesName.ifBlank { "Untitled" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
    if (menuOpen) {
        ContinueWatchingActionMenu(
            title = seriesName.ifBlank { row.title.ifBlank { "Untitled" } },
            guard = tvGuard,
            onDismiss = { menuOpen = false },
            onRemove = onRemove,
            onOpenSeries = onOpenSeries,
        )
    }
}

@Composable
private fun MoviePoster(
    movie: DispatcharrVODMovie,
    isTv: Boolean = false,
    /** Attached only on the item BACK should refocus (see VodReturnFocusState). */
    focusRequester: FocusRequester? = null,
    /** Extra hook for the grid's index-0 BACK-to-top requester. */
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .tvFocusScale(focused)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                .then(
                    // D-pad focus ring so the highlighted poster is obvious at
                    // 10 feet. No-op on touch (phone posters never gain focus).
                    if (isTv && focused)
                        Modifier.border(
                            3.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(10.dp),
                        )
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val poster = movie.posterUrl
            if (!poster.isNullOrBlank()) {
                AsyncImage(
                    model = poster,
                    contentDescription = movie.displayName,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(40.dp),
                )
            }
            // iOS parity: the rating lives ON the poster as a small
            // white-on-black corner badge (MoviesView VODPosterCard), not in
            // an accent meta line under the title.
            VodRatingBadge(movie.rating, Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = movie.displayName.ifBlank { "Untitled" },
            // labelMedium is ~10.8sp effective at the 0.9 TV type scale,
            // below couch readability; labelLarge on TV.
            style = if (isTv) MaterialTheme.typography.labelLarge
            else MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            // minLines 2 keeps the year meta line at the same height
            // across a grid row whether the title wraps or not.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        movie.year?.let { year ->
            Text(
                text = year.toString(),
                style = if (isTv) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.labelSmall,
                // iOS parity: year = textSecondary on tvOS, textTertiary on
                // iPhone (VODPosterCard).
                color = if (isTv) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/**
 * iOS VODPosterCard rating badge: small white-on-black capsule pinned to the
 * poster's bottom-trailing corner on both platforms. Renders nothing when the
 * rating is missing/blank.
 */
@Composable
private fun VodRatingBadge(rating: String?, modifier: Modifier = Modifier) {
    val text = rating?.takeIf { it.isNotBlank() } ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class OnDemandSection(val label: String, val icon: ImageVector) {
    // Issue #9: a Continue Watching sub-tab, shown only when there is
    // in-progress content (movies + episodes). Rendered first, before Movies.
    ContinueWatching(label = "Continue", icon = Icons.Outlined.History),
    Movies(label = "Movies", icon = Icons.Outlined.Movie),
    Series(label = "Series", icon = Icons.Outlined.Tv),
}

/**
 * Search field that behaves differently on TV vs phone (audit task #30).
 * On a 10-foot UI the always-on TextField was a focus trap -- D-pad DOWN
 * from the section pills landed on the soft IME, and there's no easy way
 * to get back into the grid without entering text. Toggle-to-search keeps
 * the field collapsed behind a magnifier icon by default, expands it only
 * when the user presses the icon, and auto-focuses the field so the
 * remote keyboard is immediately useful.
 *
 * On phone / tablet, the field is always visible (matches the iOS phone
 * UX). The collapse-with-X-button when expanded mirrors iOS's TV behavior
 * so the user can dismiss the keyboard without clearing query first.
 */
@Composable
private fun VodSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    isTv: Boolean,
) {
    if (!isTv) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else null,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
            ),
        )
        return
    }
    // TV: collapsed unless the user explicitly expands. A non-empty query
    // forces expansion so a saved state restoration doesn't strand the
    // user with a filter they can't see.
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (query.isNotEmpty()) expanded = true
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(expanded) {
        if (expanded) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    if (!expanded) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TvHeaderIconButton(
                icon = Icons.Outlined.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { expanded = true },
            )
        }
        return
    }
    // The text editor consumes D-pad verticals as single-line cursor moves
    // (no-ops) even after the IME closes, which strands focus in the field:
    // the user can type but never reach the results below or the pills
    // above. Route verticals to focus traversal before the editor sees them.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionDown -> {
                        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                        true
                    }
                    Key.DirectionUp -> {
                        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
                        true
                    }
                    else -> false
                }
            },
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            // X button collapses the field. When the query has text, clear
            // it first; a second tap collapses. Two-step lets the user
            // close the soft IME without losing their place in the grid.
            IconButton(onClick = {
                if (query.isNotEmpty()) {
                    onQueryChange("")
                } else {
                    expanded = false
                }
            }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = if (query.isNotEmpty()) "Clear search" else "Close search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
        ),
    )
}
