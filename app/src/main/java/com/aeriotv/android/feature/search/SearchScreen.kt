package com.aeriotv.android.feature.search

import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
     * GH #162: a result for a program airing NOW opens its channel (the user
     * searched for something to watch); a future program still jumps to its
     * guide cell. Null keeps the guide jump for every EPG result.
     */
    onPlayChannel: ((channelId: String) -> Unit)? = null,
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
    val chipFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // GH #162: restore the list position and the opened row's focus when the
    // screen comes back from a detail or player route.
    val listState = rememberLazyListState(viewModel.listIndex, viewModel.listOffset)
    val restoreKey = remember { viewModel.lastOpenedKey }
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (restoreKey != null && state.results.any { it.key == restoreKey }) {
            android.util.Log.i("AerioSearch", "[SEARCH] restore results=${state.results.size} index=${viewModel.listIndex}")
            // One frame so the restored row is laid out before focusing it.
            kotlinx.coroutines.delay(50)
            runCatching { restoreFocus.requestFocus() }
        } else if (autoFocusField) {
            runCatching { fieldFocus.requestFocus() }
        }
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
                    .onFocusChanged { if (it.isFocused) com.aeriotv.android.ui.tv.TvFocusTrace.focus("search:field") }
                    // On Android TV the field otherwise traps D-pad DOWN, so once
                    // the on-screen keyboard is dismissed there is no way to reach
                    // the scope chips / results with the remote. Hand focus down
                    // to the content instead.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionDown
                        ) {
                            // Land on the selected scope chip: a plain moveFocus
                            // skipped the chip row and dropped into the results
                            // (Streamer 2026-11-02), leaving scope unreachable.
                            runCatching { chipFocus.requestFocus() }.isSuccess ||
                                focusManager.moveFocus(FocusDirection.Down)
                        } else if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionUp
                        ) {
                            // The single-line field spends Up on moving the
                            // cursor home, so the nav bar was unreachable from
                            // it (Streamer 2026-11-02). Leave the content group;
                            // its exit lands on the selected tab's pill.
                            focusManager.moveFocus(FocusDirection.Up)
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
                    focusRequester = if (scope == state.scope) chipFocus else null,
                    upTarget = fieldFocus,
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
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(state.results, key = { _, it -> it.key }) { index, result ->
                    ResultRow(
                        result = result,
                        focusRequester = if (result.key == restoreKey) restoreFocus else null,
                        // Up from the first row lands on the selected scope chip,
                        // not the nav bar (Streamer 2026-11-02).
                        upTarget = if (index == 0) chipFocus else null,
                        traceName = "search:row$index",
                        onClick = {
                            viewModel.lastOpenedKey = result.key
                            viewModel.listIndex = listState.firstVisibleItemIndex
                            viewModel.listOffset = listState.firstVisibleItemScrollOffset
                            when (result) {
                                is SearchViewModel.Result.Epg -> {
                                    val channelId = result.channelRowId
                                    if (result.isLive && channelId != null && onPlayChannel != null) {
                                        android.util.Log.i("AerioSearch", "[SEARCH] live result: open channel")
                                        onPlayChannel(channelId)
                                    } else {
                                        onEpgResult(result.programme.channelId, result.programme.startMillis)
                                    }
                                }
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
private fun ScopeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    upTarget: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    if (focused) LaunchedEffect(Unit) { com.aeriotv.android.ui.tv.TvFocusTrace.focus("pill:$label") }
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (upTarget != null) Modifier.upTo(upTarget) else Modifier)
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
private fun ResultRow(
    result: SearchViewModel.Result,
    focusRequester: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    traceName: String = "search:row",
    onClick: () -> Unit,
) {
    val display = resultDisplay(result)
    val title = display.title
    val subtitle = display.subtitle
    val poster = display.poster
    val icon = display.icon
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    if (focused) LaunchedEffect(Unit) { com.aeriotv.android.ui.tv.TvFocusTrace.focus(traceName) }
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
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (upTarget != null) Modifier.upTo(upTarget) else Modifier)
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
                // EPG rows show the program's guide art when it has one (Logan
                // 2026-11-02) and fall back to the channel LOGO, also when the
                // art fails to load. Both fit inside the fixed box so nothing
                // shifts when the image arrives; the logo keeps its breathing
                // room. VOD posters are 2:3 art: crop-fill the box edge to edge.
                if (result is SearchViewModel.Result.Epg) {
                    var artFailed by remember(poster) { mutableStateOf(false) }
                    val showArt = display.programArt && !artFailed
                    val model = if (showArt) poster else display.fallbackPoster
                    if (model != null) {
                        AsyncImage(
                            model = model,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                                .then(if (showArt) Modifier else Modifier.padding(5.dp)),
                            contentScale = ContentScale.Fit,
                            onError = { if (showArt) artFailed = true },
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                } else {
                    AsyncImage(
                        model = poster,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
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
                    MaterialTheme.colorScheme.textAccent
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (display.detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = display.detail,
                    style = MaterialTheme.typography.bodySmall.subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class ResultDisplay(
    val title: String,
    val subtitle: String,
    val poster: String?,
    val icon: ImageVector,
    /** Second info line under the subtitle: the programme description or the
     *  VOD plot (Logan 2026-08-06: results show real content data, not just a
     *  bare title + time). Omitted when the source has none. */
    val detail: String? = null,
    /** True when [poster] is the program's own guide art (EPG rows). */
    val programArt: Boolean = false,
    /** Channel logo shown when the program art is missing or fails to load. */
    val fallbackPoster: String? = null,
)

private fun resultDisplay(result: SearchViewModel.Result): ResultDisplay = when (result) {
    is SearchViewModel.Result.Movie -> ResultDisplay(
        title = result.movie.displayName,
        subtitle = listOfNotNull(
            "Movie",
            result.movie.year?.toString(),
            result.movie.genre?.takeIf { it.isNotBlank() },
        ).joinToString("  ·  "),
        poster = result.movie.posterUrl?.takeIf { it.isNotBlank() },
        icon = Icons.Filled.Movie,
        detail = result.movie.plot?.takeIf { it.isNotBlank() },
    )
    is SearchViewModel.Result.Series -> ResultDisplay(
        title = result.series.displayName,
        subtitle = listOfNotNull(
            "TV Show",
            result.series.year?.toString(),
            result.series.genre?.takeIf { it.isNotBlank() },
        ).joinToString("  ·  "),
        poster = result.series.posterUrl?.takeIf { it.isNotBlank() },
        icon = Icons.Filled.Tv,
        detail = result.series.plot?.takeIf { it.isNotBlank() },
    )
    is SearchViewModel.Result.Epg -> {
        val range = timeFmt().format(Date(result.programme.startMillis)) +
            " - " + timeFmt().format(Date(result.programme.endMillis))
        // Program art the app already holds, no extra fetch: the art the
        // guide banner resolved this session (what the program info card opens
        // with), then the XMLTV / grid program icon the EPG model stores.
        val programArt = com.aeriotv.android.feature.livetv.grid.cachedPreviewArt(
            result.programme.dispatcharrProgramId,
            result.programme.title,
        )?.takeIf { it.isNotBlank() }
            ?: result.programme.iconUrl?.takeIf { it.isNotBlank() }
        ResultDisplay(
            title = result.programme.title,
            subtitle = listOfNotNull(
                "LIVE".takeIf { result.isLive },
                result.channelName,
                range,
            ).joinToString("  ·  "),
            poster = programArt ?: result.channelLogo,
            programArt = programArt != null,
            fallbackPoster = result.channelLogo,
            icon = if (result.isLive) Icons.Filled.LiveTv else Icons.Filled.CalendarMonth,
            detail = result.programme.description.takeIf { it.isNotBlank() },
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
                    style = MaterialTheme.typography.bodySmall.subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private fun timeFmt() = com.aeriotv.android.core.ui.ClockFormat.short()

/**
 * D-pad Up moves to [target]. Explicit key handling, not focusProperties: on
 * the TV shell the content group's exit redirect won over a custom Up target
 * and sent the first result row to the nav bar (Streamer 2026-11-02).
 */
private fun Modifier.upTo(target: FocusRequester): Modifier = onPreviewKeyEvent { e ->
    e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp &&
        runCatching { target.requestFocus() }.isSuccess
}
