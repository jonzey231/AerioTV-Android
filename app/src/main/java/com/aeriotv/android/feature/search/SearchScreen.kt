package com.aeriotv.android.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global Search screen (parity task #41). Reproduces the iOS SearchView: a
 * search field + four scope chips (All / Movies / TV Shows / EPG) over a mixed
 * result list. EPG results jump to the guide; movie/series results open detail.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onEpgResult: (channelKey: String, startMillis: Long) -> Unit,
    onMovieClick: (uuid: String) -> Unit,
    onSeriesClick: (id: Int) -> Unit,
    /**
     * Both false when hosted as the TV nav-bar Search TAB (Logan 2026-08-06)
     * rather than a pushed route: there is no screen to pop (the arrow would
     * lie; remote Back still fires [onBack] to return to Live TV), and the
     * field must not grab focus on composition - the TV nav bar selects on
     * FOCUS, so the tab composes the moment its pill highlights and a focus
     * grab here would yank the user out of the bar mid-traversal.
     */
    showBackButton: Boolean = true,
    autoFocusField: Boolean = true,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { onBack() }

    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    if (autoFocusField) {
        LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        // Back + search field.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBackButton) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
                    // On Android TV the field otherwise traps D-pad DOWN, so once
                    // the on-screen keyboard is dismissed there is no way to reach
                    // the scope chips / results with the remote. Hand focus down
                    // to the content instead.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionDown
                        ) {
                            focusManager.moveFocus(FocusDirection.Down)
                        } else {
                            false
                        }
                    },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search movies, shows, programs…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }

        Spacer(Modifier.height(12.dp))
        // Scope chips.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchViewModel.Scope.entries.forEach { scope ->
                ScopeChip(
                    label = scope.label,
                    selected = scope == state.scope,
                    onClick = { viewModel.onScopeChange(scope) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        // Content.
        when {
            state.query.isBlank() -> CenterMessage(
                icon = Icons.Filled.Search,
                title = "Search for movies, shows, or EPG programs",
            )
            state.isSearching && state.results.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            state.results.isEmpty() -> CenterMessage(
                icon = Icons.Filled.Search,
                title = "No results for “${state.query}”",
                subtitle = "Try a different search term or change the scope filter.",
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.results, key = { it.key }) { result ->
                    ResultRow(
                        result = result,
                        onClick = {
                            when (result) {
                                is SearchViewModel.Result.Epg ->
                                    onEpgResult(result.programme.channelId, result.programme.startMillis)
                                is SearchViewModel.Result.Movie -> onMovieClick(result.movie.uuid)
                                is SearchViewModel.Result.Series -> onSeriesClick(result.series.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            )
            // White ring on D-pad focus (the app-wide TV convention) so the
            // remote user can see which scope is highlighted.
            .then(
                if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(50))
                else Modifier,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ResultRow(result: SearchViewModel.Result, onClick: () -> Unit) {
    val (title, subtitle, poster, icon) = resultDisplay(result)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            // Highlight the D-pad-focused result (fill + white ring) so it is
            // clear which row Select will act on.
            .background(
                if (focused) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                else Color.Transparent,
            )
            .then(
                if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 50.dp, height = 70.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (poster != null) {
                AsyncImage(model = poster, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (result is SearchViewModel.Result.Epg && result.isLive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class ResultDisplay(val title: String, val subtitle: String, val poster: String?, val icon: ImageVector)

private fun resultDisplay(result: SearchViewModel.Result): ResultDisplay = when (result) {
    is SearchViewModel.Result.Movie -> ResultDisplay(
        title = result.movie.displayName,
        subtitle = "Movie",
        poster = result.movie.posterUrl?.takeIf { it.isNotBlank() },
        icon = Icons.Filled.Movie,
    )
    is SearchViewModel.Result.Series -> ResultDisplay(
        title = result.series.displayName,
        subtitle = "TV Show",
        poster = result.series.posterUrl?.takeIf { it.isNotBlank() },
        icon = Icons.Filled.Tv,
    )
    is SearchViewModel.Result.Epg -> {
        val time = TIME_FMT.format(Date(result.programme.startMillis))
        ResultDisplay(
            title = result.programme.title,
            subtitle = if (result.isLive) "LIVE · $time" else time,
            poster = null,
            icon = if (result.isLive) Icons.Filled.LiveTv else Icons.Filled.CalendarMonth,
        )
    }
}

@Composable
private fun CenterMessage(icon: ImageVector, title: String, subtitle: String? = null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private val TIME_FMT = SimpleDateFormat("h:mm a", Locale.getDefault())
