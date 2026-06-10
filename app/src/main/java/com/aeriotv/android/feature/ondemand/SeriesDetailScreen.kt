package com.aeriotv.android.feature.ondemand

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.network.DispatcharrVODEpisode
import com.aeriotv.android.core.network.DispatcharrVODProviderInfo
import com.aeriotv.android.core.network.DispatcharrVODSeries
import com.aeriotv.android.core.network.TmdbCredits
import com.aeriotv.android.core.network.TmdbDetails
import com.aeriotv.android.core.network.TmdbPerson
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.TvQrLink
import com.aeriotv.android.core.tv.TvQrLinkDialog
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import com.aeriotv.android.feature.livetv.rememberLiveTvFormFactor
import com.aeriotv.android.feature.watchprogress.UpNextEntry
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
import com.aeriotv.android.ui.tv.tvFocusScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Series detail screen. Mirrors iOS VODDetailView (Aerio/Features/VOD/VODDetailView.swift)
 * for series: hero with backdrop + bottom-leading poster + title block, no Play
 * button (the user picks an episode instead), Trailer + TMDB chip row, meta
 * rows, then per-season episode list with thumbnail / duration / air-date /
 * rating / plot per row - matching TVEpisodeRowButton on iOS (line 827-979).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    seriesId: Int,
    onBack: () -> Unit,
    onEpisodeClick: (DispatcharrVODEpisode) -> Unit,
    viewModel: OnDemandViewModel = hiltViewModel(),
    watchVm: WatchProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val series = viewModel.seriesById(seriesId)
    val info = state.seriesProviderInfo[seriesId]
    val recent by watchVm.observeRecent(50).collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current

    LaunchedEffect(seriesId) {
        viewModel.loadEpisodes(seriesId)
        viewModel.loadSeriesProviderInfo(seriesId)
    }

    // TMDB poster fallback (opt-in): only when the server gave no artwork.
    var tmdbPosterUrl by remember(seriesId) { mutableStateOf<String?>(null) }
    LaunchedEffect(seriesId, info, state.seriesProviderInfoLoading) {
        val s = series ?: return@LaunchedEffect
        val hasServerArt = !s.posterUrl.isNullOrBlank() ||
            !info?.posterUrl.isNullOrBlank() || !info?.backdropUrl.isNullOrBlank()
        val infoSettled = state.seriesProviderInfo.containsKey(seriesId) ||
            !state.seriesProviderInfoLoading.contains(seriesId)
        if (!hasServerArt && tmdbPosterUrl == null && infoSettled) {
            tmdbPosterUrl = viewModel.resolveTmdbPoster(
                tmdbId = info?.tmdbId ?: s.tmdbId,
                title = s.displayName,
                isMovie = false,
            )
        }
    }

    // TMDB metadata backfill (same opt-in gate as the poster fallback).
    // Fetched only when the server left at least one of plot / genre / cast /
    // director blank after provider-info settled; server values always win,
    // TMDB only fills the holes. Fully-described libraries never hit TMDB.
    var tmdbDetails by remember(seriesId) { mutableStateOf<TmdbDetails?>(null) }
    LaunchedEffect(seriesId, info, state.seriesProviderInfoLoading) {
        val s = series ?: return@LaunchedEffect
        val infoSettled = state.seriesProviderInfo.containsKey(seriesId) ||
            !state.seriesProviderInfoLoading.contains(seriesId)
        val missingMeta = (info?.effectivePlot ?: s.plot).isNullOrBlank() ||
            (info?.effectiveGenre ?: s.genre).isNullOrBlank() ||
            info?.effectiveCast.isNullOrBlank() ||
            info?.effectiveDirector.isNullOrBlank()
        if (missingMeta && tmdbDetails == null && infoSettled) {
            tmdbDetails = viewModel.resolveTmdbDetails(
                tmdbId = info?.tmdbId ?: s.tmdbId,
                title = s.displayName,
                isMovie = false,
            )
        }
    }

    // Structured TMDB credits for the Cast & Crew strip. Independent of the
    // missingMeta gate above: servers only ever send comma-separated name
    // strings, so headshots always need TMDB. The resolver returns null when
    // the TMDB opt-in or key is absent and the strip simply does not render.
    var tmdbCredits by remember(seriesId) { mutableStateOf<TmdbCredits?>(null) }
    LaunchedEffect(seriesId, info, state.seriesProviderInfoLoading) {
        val s = series ?: return@LaunchedEffect
        val infoSettled = state.seriesProviderInfo.containsKey(seriesId) ||
            !state.seriesProviderInfoLoading.contains(seriesId)
        if (tmdbCredits == null && infoSettled) {
            tmdbCredits = viewModel.resolveTmdbCredits(
                tmdbId = info?.tmdbId ?: s.tmdbId,
                title = s.displayName,
                isMovie = false,
            )
        }
    }
    // Cast first, then creators, deduped by id so a creator who also acts
    // doesn't show twice (the cast entry wins; it carries the character).
    val castCrewPeople = remember(tmdbCredits) {
        tmdbCredits?.let { c -> (c.cast + c.directors).distinctBy { it.id } }.orEmpty()
    }
    var bioPerson by remember { mutableStateOf<TmdbPerson?>(null) }

    BackHandler(enabled = true) { onBack() }

    val episodes = state.episodesBySeries[seriesId].orEmpty()
    val isLoading = seriesId in state.episodesLoadingFor
    val error = state.episodesErrorFor[seriesId]

    // Season picker state. iOS defaults to the first season; honour the
    // sorted-keys order so "Specials" (season 0) never accidentally lands as
    // the default when seasons 1+ exist.
    val seasons = remember(episodes) {
        episodes
            .groupBy { it.seasonNumber ?: 0 }
            .toSortedMap()
    }
    var selectedSeason by remember(seasons.keys) {
        mutableStateOf(seasons.keys.firstOrNull { it != 0 } ?: seasons.keys.firstOrNull() ?: 0)
    }

    val episodesInSeason = remember(seasons, selectedSeason) {
        seasons[selectedSeason]
            ?.sortedBy { it.episodeNumber ?: Int.MAX_VALUE }
            ?: emptyList()
    }

    // Continue Watching pick: the most-recently-touched unfinished episode of
    // this series. iOS Issue #19 parity: this includes the next episode that
    // the up-next queue seeds (positionMs 0, not finished) after you finish one,
    // so the series surfaces "what's next" instead of dropping off.
    val continueWatchingEpisode = remember(recent, episodes) {
        val episodeIds = episodes.asSequence().map { it.uuid }.toSet()
        val pick = recent.firstOrNull { row ->
            row.videoId in episodeIds && !row.isFinished
        }
        pick?.let { row -> episodes.firstOrNull { it.uuid == row.videoId }?.to(row) }
    }

    // Capture episode metadata + an up-next queue (rest of the series, ordered
    // by season then episode, capped at 50) before launching the player, so
    // finishing this episode advances Continue Watching to the next one
    // (iOS Issue #19). Resume position is preserved by captureEpisodePlay.
    val playEpisode: (DispatcharrVODEpisode) -> Unit = { ep ->
        val ordered = episodes.sortedWith(
            compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: Int.MAX_VALUE }),
        )
        val idx = ordered.indexOfFirst { it.uuid == ep.uuid }
        val queue = if (idx >= 0) {
            ordered.drop(idx + 1).take(50).map {
                UpNextEntry(
                    vodId = it.uuid,
                    title = it.displayName,
                    posterUrl = it.stillImageUrl,
                    streamUrl = null, // re-resolved by uuid at play time
                    seasonNumber = it.seasonNumber ?: 0,
                    episodeNumber = it.episodeNumber ?: 0,
                )
            }
        } else emptyList()
        watchVm.captureEpisodePlay(
            videoId = ep.uuid,
            title = ep.displayName,
            posterUrl = ep.stillImageUrl,
            seriesId = seriesId.toString(),
            seasonNumber = ep.seasonNumber ?: 0,
            episodeNumber = ep.episodeNumber ?: 0,
            streamUrl = null,
            upNextQueue = WatchProgressViewModel.encodeQueue(queue),
        )
        onEpisodeClick(ep)
    }

    val isTv = rememberLiveTvFormFactor().isTv
    val edgeInset = if (isTv) 48.dp else 16.dp

    // TV: external links surface as a QR dialog (no browser on Android TV).
    var qrLink by remember { mutableStateOf<TvQrLink?>(null) }
    // TV trailer menu: when a YouTube app can take VIEW for a watch URL the
    // menu offers to play right on this device, with QR as the second row.
    // Boxes without YouTube keep the straight-to-QR path. Resolved once per
    // entry (the installed-package set can't change under this screen) and
    // it needs the https/www.youtube.com <queries> entry in the manifest or
    // API 30+ package-visibility filtering blanks the lookup.
    val youtubeResolvable = remember {
        runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=x")),
                0,
            )
        }.getOrNull() != null
    }
    var trailerMenuUrl by remember { mutableStateOf<String?>(null) }

    // Scroll-to-top fix: the hero holds no focusables, so once the list
    // scrolls it away the D-pad has nowhere to land above the topmost
    // actionable item and the title / rating / poster become unreachable.
    // Whichever focusable container is topmost THIS composition calls this
    // on gaining focus to bring the hero back. Initial entry is safe: the
    // firstActionFocus request below fires while firstVisibleItemIndex is
    // still 0, so the guard skips it.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val maybeScrollHeroIntoView: () -> Unit = {
        // Offset counts too: the chip row's own bring-into-view can settle at
        // item 0 with the hero title still clipped above the screen edge.
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }
    // Mirrors SeriesInfoSection's own chip-visibility logic; keep in sync.
    val hasInfoChips = info?.effectiveTrailer?.let { youtubeUrl(it) } != null ||
        !(info?.tmdbId ?: series?.tmdbId).isNullOrBlank()

    // On TV land focus on the first actionable control (Resume when the
    // series has one, otherwise the first episode row) once the episode list
    // arrives; without this the screen had no focused control at all and the
    // D-pad appeared dead. Fire once so later data changes don't yank focus.
    val firstActionFocus = remember(seriesId) { FocusRequester() }
    var initialFocusDone by remember(seriesId) { mutableStateOf(false) }
    if (isTv) {
        LaunchedEffect(continueWatchingEpisode != null, episodesInSeason.isNotEmpty()) {
            if (initialFocusDone) return@LaunchedEffect
            if (continueWatchingEpisode == null && episodesInSeason.isEmpty()) return@LaunchedEffect
            repeat(10) {
                if (runCatching { firstActionFocus.requestFocus() }.isSuccess) {
                    initialFocusDone = true
                    return@LaunchedEffect
                }
                delay(16L)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (series == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Series not found",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item {
                    SeriesHeroSection(
                        series = series,
                        info = info,
                        tmdbPosterUrl = tmdbPosterUrl,
                        tmdbDetails = tmdbDetails,
                        isTv = isTv,
                    )
                }
                item {
                    SeriesInfoSection(
                        series = series,
                        info = info,
                        tmdbDetails = tmdbDetails,
                        isTv = isTv,
                        castPhotosVisible = castCrewPeople.isNotEmpty(),
                        // The chip row, when present, is always the topmost
                        // focusable on this screen.
                        chipRowModifier = Modifier.onFocusChanged {
                            if (it.hasFocus) maybeScrollHeroIntoView()
                        },
                        onOpenUrl = { label, url ->
                            if (isTv) {
                                if (label == "Trailer" && youtubeResolvable) {
                                    trailerMenuUrl = url
                                } else {
                                    qrLink = TvQrLink(
                                        title = label,
                                        caption = when (label) {
                                            "Trailer" -> "Scan with your phone to watch the trailer on YouTube."
                                            else -> "Scan with your phone to view this title on TMDB."
                                        },
                                        url = url,
                                    )
                                }
                            } else {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                            }
                        },
                    )
                }

                if (castCrewPeople.isNotEmpty()) {
                    item(key = "cast-crew") {
                        CastCrewSection(
                            people = castCrewPeople,
                            isTv = isTv,
                            profileUrl = viewModel::tmdbProfileImageUrl,
                            onPersonClick = { bioPerson = it },
                            modifier = Modifier.onFocusChanged {
                                if (it.hasFocus && !hasInfoChips) maybeScrollHeroIntoView()
                            },
                        )
                    }
                }

                continueWatchingEpisode?.let { (episode, progress) ->
                    item(key = "continue-watching") {
                        ContinueWatchingCard(
                            episode = episode,
                            positionMs = progress.positionMs,
                            durationMs = progress.durationMs,
                            onResume = { playEpisode(episode) },
                            modifier = Modifier
                                .padding(horizontal = edgeInset)
                                .onFocusChanged {
                                    if (it.hasFocus && !hasInfoChips && castCrewPeople.isEmpty()) {
                                        maybeScrollHeroIntoView()
                                    }
                                },
                            resumeModifier = Modifier.focusRequester(firstActionFocus),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }

                if (seasons.size > 1) {
                    item(key = "season-picker") {
                        SeasonPicker(
                            seasons = seasons.keys.toList(),
                            selected = selectedSeason,
                            onSelect = { selectedSeason = it },
                            edgeInset = edgeInset,
                            modifier = Modifier.onFocusChanged {
                                if (it.hasFocus && !hasInfoChips && castCrewPeople.isEmpty() &&
                                    continueWatchingEpisode == null
                                ) {
                                    maybeScrollHeroIntoView()
                                }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                if (isLoading && episodes.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (error != null && episodes.isEmpty()) {
                    item {
                        Text(
                            text = "Couldn't load episodes: $error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = edgeInset, vertical = 24.dp),
                        )
                    }
                } else if (episodes.isEmpty()) {
                    item {
                        Text(
                            text = "Server returned no episodes for this series.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = edgeInset, vertical = 24.dp),
                        )
                    }
                } else {
                    itemsIndexed(items = episodesInSeason, key = { _, ep -> ep.id }) { index, ep ->
                        EpisodeRow(
                            episode = ep,
                            onClick = { playEpisode(ep) },
                            modifier = Modifier
                                .padding(
                                    horizontal = if (isTv) 44.dp else 12.dp,
                                    vertical = 4.dp,
                                )
                                .then(
                                    if (index == 0 && continueWatchingEpisode == null) {
                                        Modifier.focusRequester(firstActionFocus)
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(
                                    // Topmost only when nothing focusable
                                    // renders above the episode list.
                                    if (index == 0 && !hasInfoChips && castCrewPeople.isEmpty() &&
                                        continueWatchingEpisode == null && seasons.size <= 1
                                    ) {
                                        Modifier.onFocusChanged {
                                            if (it.hasFocus) maybeScrollHeroIntoView()
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
        }

        // Hidden on Android TV: remote BACK pops the screen, and the floating
        // pill would otherwise be an invisible D-pad focus stop.
        if (!isTv) {
            FloatingBackButton(onClick = onBack)
        }

        qrLink?.let { link ->
            TvQrLinkDialog(
                title = link.title,
                caption = link.caption,
                url = link.url,
                onDismiss = { qrLink = null },
            )
        }

        trailerMenuUrl?.let { url ->
            // Guard never armed: this menu opens from a short press, and the
            // rows' own OK latch already ignores the opening press's release.
            TvActionMenuDialog(
                title = "Trailer",
                actions = listOf(
                    TvMenuAction(
                        label = "Play in YouTube",
                        icon = Icons.Filled.PlayArrow,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                    ),
                    TvMenuAction(
                        label = "Show QR Code",
                        icon = Icons.Filled.QrCode,
                        onClick = {
                            qrLink = TvQrLink(
                                title = "Trailer",
                                caption = "Scan with your phone to watch the trailer on YouTube.",
                                url = url,
                            )
                        },
                    ),
                ),
                guard = rememberTvMenuGuard(),
                onDismiss = { trailerMenuUrl = null },
            )
        }

        bioPerson?.let { person ->
            PersonBioDialog(
                person = person,
                fetchBio = viewModel::resolveTmdbPersonBio,
                profileUrl = viewModel::tmdbProfileImageUrl,
                onDismiss = { bioPerson = null },
            )
        }
    }
}

@Composable
private fun SeriesHeroSection(
    series: DispatcharrVODSeries,
    info: DispatcharrVODProviderInfo?,
    tmdbPosterUrl: String?,
    tmdbDetails: TmdbDetails?,
    isTv: Boolean,
) {
    val heroUrl = info?.backdropUrl ?: series.posterUrl ?: tmdbPosterUrl
    val posterUrl = series.posterUrl ?: info?.posterUrl ?: tmdbPosterUrl
    // TMDB sits last in each chain: it only backfills fields the server
    // (provider-info AND the list row) left empty.
    val displayYear = info?.year?.toString() ?: series.year?.toString() ?: tmdbDetails?.year
    val displayRating = (info?.rating?.takeIf { it.isNotBlank() } ?: series.rating)
        ?.let { runCatching { String.format("%.1f", it.toDouble()) }.getOrDefault(it) }
        ?.takeIf { it.isNotBlank() && it != "0.0" }
        ?: tmdbDetails?.voteAverage

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 16:11 of the 960dp TV canvas is 660dp, taller than the whole
            // 540dp screen: the title block sat below the fold and the screen
            // read as a full-screen poster. Fixed 300dp hero on TV; phones
            // keep the aspect-ratio hero. Same fix as MovieDetailScreen.
            .then(if (isTv) Modifier.height(300.dp) else Modifier.aspectRatio(16f / 11f)),
    ) {
        if (!heroUrl.isNullOrBlank()) {
            AsyncImage(
                model = heroUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    horizontal = if (isTv) 48.dp else 16.dp,
                    vertical = 16.dp,
                ),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                if (!posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = series.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Tv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                // iOS VODDetailView line 312 sets the title to `item.name`
                // without appending the year; the year already shows on the
                // meta strip below. Dispatcharr often serves titles that
                // already embed "(YYYY)" so appending duplicates it.
                Text(
                    text = series.displayName.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                MetaStripCompact(year = displayYear, rating = displayRating)
            }
        }
    }
}

@Composable
private fun MetaStripCompact(year: String?, rating: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!year.isNullOrBlank()) {
            Text(
                text = year,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!rating.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFA502),
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = rating,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SeriesInfoSection(
    series: DispatcharrVODSeries,
    info: DispatcharrVODProviderInfo?,
    tmdbDetails: TmdbDetails?,
    isTv: Boolean,
    castPhotosVisible: Boolean,
    chipRowModifier: Modifier,
    onOpenUrl: (label: String, url: String) -> Unit,
) {
    // Server-provided values always win; TMDB backfills only the holes.
    val plot = info?.effectivePlot?.takeIf { it.isNotBlank() } ?: series.plot?.takeIf { it.isNotBlank() }
        ?: tmdbDetails?.overview
    val genre = info?.effectiveGenre?.takeIf { it.isNotBlank() } ?: series.genre?.takeIf { it.isNotBlank() }
        ?: tmdbDetails?.genres
    val cast = info?.effectiveCast?.takeIf { it.isNotBlank() } ?: tmdbDetails?.castTop
    val director = info?.effectiveDirector?.takeIf { it.isNotBlank() } ?: tmdbDetails?.director
    val country = info?.effectiveCountry?.takeIf { it.isNotBlank() }
    val trailerUrl = info?.effectiveTrailer?.let { youtubeUrl(it) }
    val tmdbUrl = (info?.tmdbId ?: series.tmdbId)?.takeIf { it.isNotBlank() }?.let {
        "https://www.themoviedb.org/tv/$it"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!plot.isNullOrBlank()) {
            // Series plots can run long with episode summaries layered into
            // the main synopsis; iOS doesn't truncate them and Android
            // shouldn't either. Matches MovieDetailScreen.
            Text(
                text = plot,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailerUrl != null || tmdbUrl != null) {
            Row(
                modifier = chipRowModifier,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (trailerUrl != null) {
                    PillButton(icon = Icons.Outlined.PlayCircle, text = "Trailer") {
                        onOpenUrl("Trailer", trailerUrl)
                    }
                }
                if (tmdbUrl != null) {
                    PillButton(icon = Icons.Outlined.Info, text = "View on TMDB") {
                        onOpenUrl("View on TMDB", tmdbUrl)
                    }
                }
            }
        }
        if (!genre.isNullOrBlank()) MetaRow("Genre", genre)
        // The text rows duplicate the Cast & Crew photo strip when it
        // renders; they stay as the fallback when TMDB enrichment is off
        // or returned nothing for this title.
        if (!cast.isNullOrBlank() && !castPhotosVisible) MetaRow("Cast", cast)
        if (!director.isNullOrBlank() && !castPhotosVisible) MetaRow("Director", director)
        if (!country.isNullOrBlank()) MetaRow("Country", country)
    }
}

/**
 * Plex-style Cast & Crew strip: TMDB headshot, real name, character or crew
 * role. Cast leads, creators follow (deduped upstream). Cards open
 * [PersonBioDialog]; the 3dp primary focus ring matches the poster cards on
 * the On Demand shelves so D-pad focus reads the same at 10 feet.
 */
@Composable
private fun CastCrewSection(
    people: List<TmdbPerson>,
    isTv: Boolean,
    profileUrl: (String?, String) -> String?,
    onPersonClick: (TmdbPerson) -> Unit,
    modifier: Modifier = Modifier,
) {
    val edgeInset = if (isTv) 48.dp else 16.dp
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            text = "Cast & Crew",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = edgeInset),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = edgeInset),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = people, key = { it.id }) { person ->
                PersonCard(
                    person = person,
                    isTv = isTv,
                    photoUrl = profileUrl(person.profilePath, "w185"),
                    onClick = { onPersonClick(person) },
                )
            }
        }
    }
}

@Composable
private fun PersonCard(
    person: TmdbPerson,
    isTv: Boolean,
    photoUrl: String?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(if (isTv) 110.dp else 90.dp)
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
                    if (focused) Modifier.border(
                        3.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(10.dp),
                    ) else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!photoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = person.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = person.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        person.role?.takeIf { it.isNotBlank() }?.let { role ->
            Text(
                text = role,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .tvFocusScale(focused)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SeasonPicker(
    seasons: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    edgeInset: androidx.compose.ui.unit.Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = edgeInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = seasons) { season ->
            val isSelected = season == selected
            var focused by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    )
                    .border(
                        width = 2.dp,
                        color = if (focused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(50),
                    )
                    .clickable { onSelect(season) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = if (season == 0) "Specials" else "Season $season",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Episode row - 16:9 still thumbnail on the left, title with episode number,
 * duration · air date · rating metadata strip, plot, and a play icon on the
 * right. Mirrors iOS TVEpisodeRowButton (VODDetailView lines 842-957).
 */
@Composable
private fun EpisodeRow(
    episode: DispatcharrVODEpisode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resting look is unchanged (transparent row); the wash + white ring only
    // appear under D-pad focus, which never happens on touch devices.
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else Color.Transparent,
            )
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(112.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            val still = episode.stillImageUrl
            if (!still.isNullOrBlank()) {
                AsyncImage(
                    model = still,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            val titleLine = buildString {
                episode.episodeNumber?.let { append("E$it · ") }
                append(episode.displayName.ifBlank { "Episode ${episode.id}" })
            }
            Text(
                text = titleLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val pieces = listOfNotNull(
                episode.durationSecs?.takeIf { it > 0 }?.let { formatEpisodeDuration(it) },
                episode.airDate?.let { formatAirDate(it) }?.takeIf { it.isNotBlank() },
                episode.rating?.takeIf { it.isNotBlank() && it != "0.0" }
                    ?.let { runCatching { String.format("%.1f", it.toDouble()) }.getOrDefault(it) }
                    ?.let { "★ $it" },
            )
            if (pieces.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = pieces.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            val plot = episode.effectivePlot?.takeIf { it.isNotBlank() }
            if (plot != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = plot,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            modifier = Modifier
                .size(28.dp)
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun ContinueWatchingCard(
    episode: DispatcharrVODEpisode,
    positionMs: Long,
    durationMs: Long,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    resumeModifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val remainingMin = if (durationMs > positionMs) {
        ((durationMs - positionMs) / 60_000L).toInt().coerceAtLeast(1)
    } else 0
    val seasonEpisodeTag = listOfNotNull(
        episode.seasonNumber?.let { "S$it" },
        episode.episodeNumber?.let { "E$it" },
    ).joinToString("·")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Continue Watching",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildString {
                if (seasonEpisodeTag.isNotBlank()) append(seasonEpisodeTag).append("  ·  ")
                append(episode.displayName.ifBlank { "Episode ${episode.id}" })
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var resumeFocused by remember { mutableStateOf(false) }
            Button(
                onClick = onResume,
                modifier = resumeModifier
                    .onFocusChanged { resumeFocused = it.isFocused }
                    .tvFocusScale(resumeFocused)
                    .border(
                        width = 2.dp,
                        color = if (resumeFocused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(50),
                    ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Resume")
            }
            if (remainingMin > 0) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "$remainingMin min left",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FloatingBackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .statusBarsPadding()
            .padding(top = 8.dp, start = 8.dp),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun formatEpisodeDuration(secs: Int): String {
    val h = secs / 3600
    val m = (secs % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/**
 * Dispatcharr emits air_date as `yyyy-MM-dd` (POSIX, UTC). Format to the
 * user's locale short style - iOS does the same conversion in
 * VODEpisode.displayAirDate (VODModels.swift lines 469-484).
 */
private fun formatAirDate(raw: String): String {
    val trimmed = raw.takeIf { it.length >= 10 } ?: return raw
    return runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = parser.parse(trimmed.substring(0, 10)) ?: return raw
        DateFormat.getDateInstance(DateFormat.SHORT).format(date)
    }.getOrDefault(raw)
}
