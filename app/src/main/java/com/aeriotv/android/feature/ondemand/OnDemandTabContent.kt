package com.aeriotv.android.feature.ondemand

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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import com.aeriotv.android.core.network.DispatcharrVODMovie
import com.aeriotv.android.core.network.DispatcharrVODSeries
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
import com.aeriotv.android.ui.scale.WithDisplayScale

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
    viewModel: OnDemandViewModel = hiltViewModel(),
) {
    var section by rememberSaveable { mutableStateOf(OnDemandSection.Movies) }
    val settingsVm: SettingsViewModel = hiltViewModel()
    val scale by settingsVm.displayScaleMovies.collectAsStateWithLifecycle(initialValue = 1.0f)

    WithDisplayScale(scale = scale) {
    Column(modifier = modifier.fillMaxSize()) {
        // Match the Live TV tab header style (Phase 50): centered title in
        // titleLarge + bold so every tab top reads as a consistent surface.
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OnDemandSection.entries.forEach { entry ->
            SegmentPill(
                label = entry.label,
                icon = entry.icon,
                selected = entry == current,
                onClick = { onSelect(entry) },
                modifier = Modifier.weight(1f),
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
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentProgress by watchVm.observeRecent(20).collectAsStateWithLifecycle(initialValue = emptyList())

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

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text("Search movies") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
            ),
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
            "${state.movies.size} / $total"
        }
        if (countLabel != null) {
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
        if (state.visible.isEmpty()) {
            EmptyState(
                title = if (state.searchQuery.isNotBlank()) "No matches" else "No movies",
                body = if (state.searchQuery.isNotBlank())
                    "Try a different search term."
                else
                    "Dispatcharr returned an empty Movies library. Confirm VOD is enabled on the server.",
            )
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.fillMaxSize(),
            // 104dp bottom clears the MainScaffold NavigationBar so the
            // final row of movie posters isn't half-buried behind the
            // tab bar.
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = state.visible, key = { it.id }) { movie ->
                MoviePoster(
                    movie = movie,
                    onClick = { onMovieClick(movie) },
                )
            }
        }
    }
}

@Composable
private fun SeriesSubScreen(
    viewModel: OnDemandViewModel,
    onSeriesClick: (DispatcharrVODSeries) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.unsupportedSource) {
        EmptyState(
            title = "Series needs Dispatcharr",
            body = "Switch to a Dispatcharr playlist in Settings to browse series.",
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.seriesSearchQuery,
            onValueChange = viewModel::setSeriesSearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text("Search series") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
            ),
        )

        val countLabel = state.seriesTotalCount.takeIf { it > 0 }?.let { total ->
            "${state.series.size} / $total"
        }
        if (countLabel != null) {
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
        if (state.visibleSeries.isEmpty()) {
            EmptyState(
                title = if (state.seriesSearchQuery.isNotBlank()) "No matches" else "No series",
                body = if (state.seriesSearchQuery.isNotBlank())
                    "Try a different search term."
                else
                    "Dispatcharr returned an empty Series library. Confirm VOD is enabled on the server.",
            )
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.fillMaxSize(),
            // Series tab matches Movies tab nav-bar inset.
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = state.visibleSeries, key = { it.id }) { series ->
                SeriesPoster(
                    series = series,
                    onClick = { onSeriesClick(series) },
                )
            }
        }
    }
}

@Composable
private fun SeriesPoster(
    series: DispatcharrVODSeries,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
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
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
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
                style = MaterialTheme.typography.labelSmall,
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
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
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
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(10.dp))
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

@Composable
private fun MoviePoster(
    movie: DispatcharrVODMovie,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
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
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
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
                style = MaterialTheme.typography.labelSmall,
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
