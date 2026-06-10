package com.aeriotv.android.feature.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.aeriotv.android.core.network.DispatcharrVODMovie
import com.aeriotv.android.core.network.DispatcharrVODSeries
import com.aeriotv.android.feature.livetv.rememberLiveTvFormFactor
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
import com.aeriotv.android.ui.scale.WithDisplayScale
import com.aeriotv.android.ui.tv.tvFocusScale

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
    viewModel: OnDemandViewModel = hiltViewModel(),
) {
    var section by rememberSaveable { mutableStateOf(OnDemandSection.Movies) }
    // XC libraries are probed cheaply at init but the heavy per-category walk is
    // deferred until the tab is actually shown -- kick it off here (no-op for
    // non-XC sources and once already loaded).
    LaunchedEffect(Unit) { viewModel.loadXtreamItemsIfNeeded() }
    val settingsVm: SettingsViewModel = hiltViewModel()
    val scale by settingsVm.displayScaleMovies.collectAsStateWithLifecycle(initialValue = 1.0f)

    val tabIsTv = rememberLiveTvFormFactor().isTv
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

        SegmentPills(
            current = section,
            onSelect = { section = it },
        )

        when (section) {
            OnDemandSection.Movies -> MoviesSubScreen(
                viewModel = viewModel,
                onMovieClick = onMovieClick,
            )
            OnDemandSection.Series -> SeriesSubScreen(
                viewModel = viewModel,
                onSeriesClick = onSeriesClick,
                onEpisodeResume = onEpisodeResume,
            )
        }
    }
    }
}

@Composable
private fun SegmentPills(
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
        OnDemandSection.entries.forEachIndexed { index, entry ->
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

@Composable
private fun MoviesSubScreen(
    viewModel: OnDemandViewModel,
    onMovieClick: (DispatcharrVODMovie) -> Unit,
    watchVm: WatchProgressViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentProgress by watchVm.observeRecent(20).collectAsStateWithLifecycle(initialValue = emptyList())
    val isTv = rememberLiveTvFormFactor().isTv
    // VOD group hide filter (iOS MoviesView hiddenMovieGroups, MoviesView.swift:74).
    // Categories are tagged on each movie row by OnDemandViewModel (from the XC
    // get_vod_categories lookup); items with no categoryName fall under the
    // "Uncategorized" bucket so the user can hide that too.
    val hiddenMovieGroups by settingsVm.hiddenMovieGroups
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val allMovieGroups = remember(state.movies) {
        state.movies.asSequence()
            .map { it.categoryName ?: UNCATEGORIZED }
            .distinct().toList().sorted()
    }
    var showManageGroups by rememberSaveable { mutableStateOf(false) }

    if (state.unsupportedSource) {
        EmptyState(
            title = "Movies needs Dispatcharr",
            body = "Switch to a Dispatcharr playlist in Settings to browse movies.",
        )
        return
    }

    // Continue Watching: filter the recent rows down to the ones whose videoId
    // is in the loaded movies cache AND that aren't within 5 minutes of the end.
    // iOS uses the same "5 min from end = completed" heuristic in VODDetailView.
    val movieIds = remember(state.movies) { state.movies.asSequence().map { it.uuid }.toSet() }
    val continueWatching = remember(recentProgress, movieIds) {
        recentProgress.filter { row ->
            row.videoId in movieIds &&
                    row.positionMs > 0L &&
                    (row.durationMs <= 0L || row.positionMs < row.durationMs - 5 * 60 * 1000L)
        }.take(8)
    }
    val movieByUuid = remember(state.movies) { state.movies.associateBy { it.uuid } }

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
            isTv = isTv,
            countLabel = state.totalCount.takeIf { it > 0 }?.let { total ->
                "${visibleFiltered.size} / $total"
            },
        )

        if (continueWatching.isNotEmpty() && state.searchQuery.isBlank()) {
            ContinueWatchingRail(
                items = continueWatching,
                onItemClick = { progress ->
                    movieByUuid[progress.videoId]?.let(onMovieClick)
                },
            )
        }

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

        LazyVerticalGrid(
            // Larger posters + overscan-safe padding on the 10-foot TV; phone
            // keeps the compact grid whose 104dp bottom clears the bottom
            // NavigationBar (TV has top tabs, so it needs far less bottom inset).
            columns = GridCells.Adaptive(minSize = if (isTv) 128.dp else 120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = if (isTv) 48.dp else 12.dp,
                end = if (isTv) 48.dp else 12.dp,
                top = if (isTv) 16.dp else 8.dp,
                bottom = if (isTv) 32.dp else 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
        ) {
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
                    onClick = { onMovieClick(movie) },
                )
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

@Composable
private fun SeriesSubScreen(
    viewModel: OnDemandViewModel,
    onSeriesClick: (DispatcharrVODSeries) -> Unit,
    onEpisodeResume: (String) -> Unit = {},
    watchVm: WatchProgressViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentProgress by watchVm.observeRecent(20)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val isTv = rememberLiveTvFormFactor().isTv
    val seriesById = remember(state.series) { state.series.associateBy { it.id } }
    // Series-side group hide filter, mirrors MoviesSubScreen above.
    val hiddenSeriesGroups by settingsVm.hiddenSeriesGroups
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val allSeriesGroups = remember(state.series) {
        state.series.asSequence()
            .map { it.categoryName ?: UNCATEGORIZED }
            .distinct().toList().sorted()
    }
    var showManageGroups by rememberSaveable { mutableStateOf(false) }
    // Continue Watching for series = unfinished episode rows. Includes the next
    // episode the up-next queue seeded (positionMs 0) after finishing one, so a
    // binge keeps surfacing the next episode (iOS Issue #19).
    val continueWatchingEpisodes = remember(recentProgress) {
        recentProgress.filter { it.vodType == "episode" && !it.isFinished }.take(8)
    }

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
            isTv = isTv,
            countLabel = state.seriesTotalCount.takeIf { it > 0 }?.let { total ->
                "${visibleSeriesFiltered.size} / $total"
            },
        )

        if (continueWatchingEpisodes.isNotEmpty() && state.seriesSearchQuery.isBlank()) {
            SeriesContinueWatchingRail(
                items = continueWatchingEpisodes,
                seriesById = seriesById,
                onItemClick = { row -> onEpisodeResume(row.videoId) },
            )
        }

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

        LazyVerticalGrid(
            // Series tab matches the Movies tab's TV / phone grid metrics.
            columns = GridCells.Adaptive(minSize = if (isTv) 128.dp else 120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = if (isTv) 48.dp else 12.dp,
                end = if (isTv) 48.dp else 12.dp,
                top = if (isTv) 16.dp else 8.dp,
                bottom = if (isTv) 32.dp else 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
        ) {
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
                    onClick = { onSeriesClick(series) },
                )
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

@Composable
private fun SeriesPoster(
    series: DispatcharrVODSeries,
    isTv: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = series.displayName.ifBlank { "Untitled" },
            style = if (isTv) MaterialTheme.typography.labelLarge
            else MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            // minLines 2 keeps the year/rating meta line at the same height
            // across a grid row whether the title wraps or not.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        if (series.year != null || !series.rating.isNullOrBlank()) {
            val meta = listOfNotNull(
                series.year?.toString(),
                series.rating?.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            Text(
                text = meta,
                style = if (isTv) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun ContinueWatchingRail(
    items: List<WatchProgressEntity>,
    onItemClick: (WatchProgressEntity) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text = "Continue Watching",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
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
                ContinueWatchingCard(row = row, onClick = { onItemClick(row) })
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    row: WatchProgressEntity,
    onClick: () -> Unit,
) {
    val progress = if (row.durationMs > 0L) {
        (row.positionMs.toFloat() / row.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    var cardFocused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(140.dp)
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
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (!row.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = row.posterUrl,
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
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text = "Continue Watching",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
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
                    seriesPoster = series?.posterUrl ?: row.posterUrl,
                    seriesName = series?.displayName ?: row.title,
                    onClick = { onItemClick(row) },
                )
            }
        }
    }
}

@Composable
private fun SeriesContinueWatchingCard(
    row: WatchProgressEntity,
    seriesPoster: String?,
    seriesName: String,
    onClick: () -> Unit,
) {
    val progress = if (row.durationMs > 0L) {
        (row.positionMs.toFloat() / row.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val tag = listOfNotNull(
        row.seasonNumber.takeIf { it > 0 }?.let { "S$it" },
        row.episodeNumber.takeIf { it > 0 }?.let { "E$it" },
    ).joinToString(":")
    val subtitle = listOf(tag, row.title).filter { it.isNotBlank() }.joinToString(" · ")
    var cardFocused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(140.dp)
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
            .clickable(onClick = onClick),
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
}

@Composable
private fun MoviePoster(
    movie: DispatcharrVODMovie,
    isTv: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
            // minLines 2 keeps the year/rating meta line at the same height
            // across a grid row whether the title wraps or not.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        if (movie.year != null || !movie.rating.isNullOrBlank()) {
            val meta = listOfNotNull(
                movie.year?.toString(),
                movie.rating?.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            Text(
                text = meta,
                style = if (isTv) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
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
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .focusRequester(focusRequester),
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
