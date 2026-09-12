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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.Dp
import com.aeriotv.android.ui.adaptive.rememberViewport
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
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Tune
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
import com.aeriotv.android.ui.TmdbAttribution
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
import com.aeriotv.android.feature.movies.displayTitle
import com.aeriotv.android.feature.movies.MediaItem
import com.aeriotv.android.feature.movies.tmdbArtKey
import com.aeriotv.android.feature.watchprogress.UpNextEntry
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
import com.aeriotv.android.ui.tv.tvFocusScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Replay
import com.aeriotv.android.core.network.TmdbEpisodeInfo
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Series detail screen. Mirrors iOS VODDetailView
 * (Aerio/Features/VOD/VODDetailView.swift), which renders both form factors
 * from one view:
 *  - TV follows the tvOS layout: the 620 pt (310 dp) hero with no poster, the
 *    action row (Resume S2 E3 / Play S1 E2, Play from Beginning, Version,
 *    Trailer, TMDB), horizontal episode cards, episode-scoped Cast and Crew,
 *    the Details facts block, Available Related Titles, TMDB attribution.
 *  - Phone follows the iPhone layout: the 280 dp hero with the 80 x 120 poster,
 *    the info block, Cast and Crew, vertical episode rows, Related, attribution.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SeriesDetailScreen(
    seriesId: Int,
    onBack: () -> Unit,
    /** fromStart is passed per launch (never held in view state) and zeroes the
     *  start position WITHOUT clearing the WatchProgress row (Apple C3). */
    onEpisodeClick: (DispatcharrVODEpisode, Boolean) -> Unit,
    // Known For tile pushes from the bio dialog: plain navigation pushes so
    // remote BACK returns here. Defaults keep non-nav call sites compiling.
    onOpenMovie: (String) -> Unit = {},
    onOpenSeries: (Int) -> Unit = {},
    viewModel: OnDemandViewModel = hiltViewModel(),
    watchVm: WatchProgressViewModel = hiltViewModel(),
    watchlistVm: com.aeriotv.android.feature.movies.WatchlistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val series = viewModel.seriesById(seriesId)
    LaunchedEffect(seriesId, series == null) { if (series == null) viewModel.resolveSeries(seriesId) }
    val resolvingSeries = series == null && viewModel.isResolving("s:$seriesId")
    val info = state.seriesProviderInfo[seriesId]
    // Keyed per-series episode rows, never the recent-50 scan: a long-tail
    // series outside the recency window still finds its resume point
    // (Apple's keyed getResumePosition, VODModels.swift:280-286).
    val episodeProgress by watchVm.observeSeriesEpisodes(seriesId.toString())
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current
    // "Related": TMDB recommendations filtered to the local library. Keyed on
    // the library size too so it re-matches as the launch sweep publishes.
    var relatedItems by remember(seriesId) { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(seriesId, series == null, info, state.series.size) {
        val s = series ?: return@LaunchedEffect
        relatedItems = viewModel.relatedTitles(
            tmdbId = info?.tmdbId ?: s.tmdbId,
            title = s.displayName,
            isMovie = false,
            selfKey = "s:${s.id}",
        )
    }
    val watchlistEntries by watchlistVm.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val watchlistKeys = remember(watchlistEntries) { watchlistEntries.map { it.key }.toSet() }

    LaunchedEffect(seriesId) {
        // loadEpisodes now primes provider-info (Dispatcharr series_info) FIRST so the
        // lazy-scrape populates the episode table before /episodes/ is hit, and caches
        // seriesProviderInfo, so the separate loadSeriesProviderInfo() call is gone.
        viewModel.loadEpisodes(seriesId)
        // Version picker data (Dispatcharr Direct Connect only; the VM caches
        // empty for every other source so the pill never renders).
        viewModel.loadSeriesProviders(seriesId)
    }
    val versionOptions = state.seriesProviders[seriesId].orEmpty()
    val selectedVersion = state.selectedSeriesVersion[seriesId]
    var showVersionPicker by remember(seriesId) { mutableStateOf(false) }

    // TMDB poster fallback (opt-in): only when the server gave no artwork.
    var tmdbPosterUrl by remember(seriesId) { mutableStateOf<String?>(null) }
    // Provenance note inputs (phone): whether the poster lookup has run, and
    // whether the opt-in + key are set, so an art-less title can say "add a
    // key" vs "no match" (iOS tmdbLookupDone / TMDBPosters.apiKey).
    var tmdbLookupDone by remember(seriesId) { mutableStateOf(false) }
    // Persistent art cache: same TMDB-first rule as the movie screen.
    val artVersion by viewModel.artVersion.collectAsStateWithLifecycle(initialValue = 0)
    val artKey = remember(seriesId, series?.displayName) {
        series?.let { tmdbArtKey(displayTitle(it.displayName, it.year), false) }
    }
    val cachedBackdropUrl = remember(artKey, artVersion) { artKey?.let { viewModel.artBackdropUrl(it) } }
    val cachedOverview = remember(artKey, artVersion) { artKey?.let { viewModel.artOverview(it) } }
    var tmdbConfigured by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { tmdbConfigured = viewModel.isTmdbConfigured() }
    val hasServerArt = series != null && (
        !series.posterUrl.isNullOrBlank() ||
            !info?.posterUrl.isNullOrBlank() || !info?.backdropUrl.isNullOrBlank()
        )
    LaunchedEffect(seriesId, info, state.seriesProviderInfoLoading) {
        val s = series ?: return@LaunchedEffect
        val infoSettled = state.seriesProviderInfo.containsKey(seriesId) ||
            !state.seriesProviderInfoLoading.contains(seriesId)
        if (!hasServerArt && tmdbPosterUrl == null && infoSettled) {
            tmdbPosterUrl = viewModel.resolveTmdbPoster(
                tmdbId = info?.tmdbId ?: s.tmdbId,
                title = s.displayName,
                isMovie = false,
            )
            tmdbLookupDone = true
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
    var bioPerson by remember { mutableStateOf<TmdbPerson?>(null) }

    BackHandler(enabled = true) { onBack() }

    val episodes = state.episodesBySeries[seriesId].orEmpty()
    val isLoading = seriesId in state.episodesLoadingFor
    val error = state.episodesErrorFor[seriesId]

    val seasons = remember(episodes) {
        episodes
            .groupBy { it.seasonNumber ?: 0 }
            .toSortedMap()
    }

    // Target episode + button label (Apple tvSeriesTarget, VODDetailView 731-751).
    val target = remember(episodes, episodeProgress) { seriesTarget(episodes, episodeProgress) }

    // Apple seats the season that holds the resume target (seatSelectedSeason,
    // VODDetailView 904-911) rather than defaulting to the first non-special.
    var selectedSeason by remember(seasons.keys) {
        mutableStateOf(seasons.keys.firstOrNull { it != 0 } ?: seasons.keys.firstOrNull() ?: 0)
    }
    var seasonSeated by remember(seriesId) { mutableStateOf(false) }
    LaunchedEffect(target?.episode?.uuid, seasons.keys) {
        val season = target?.episode?.seasonNumber ?: return@LaunchedEffect
        if (!seasonSeated && seasons.containsKey(season)) {
            selectedSeason = season
            seasonSeated = true
        }
    }

    val episodesInSeason = remember(seasons, selectedSeason) {
        seasons[selectedSeason]
            ?.sortedBy { it.episodeNumber ?: Int.MAX_VALUE }
            ?: emptyList()
    }

    // Per-episode watch progress for the row / card chrome, keyed by the
    // episode uuid (the same key the player writes).
    val progressByEpisode = remember(episodeProgress) { episodeProgress.associateBy { it.videoId } }

    // TMDB season data for the selected season: stills (w780 first, the
    // provider's still as the fallback), the episode name when the provider's
    // title is useless, and the guest stars / crew for the episode-scoped
    // Cast and Crew row (Apple VODDetailView 871-873, 793-798, 2259-2273).
    var tmdbSeason by remember(seriesId) { mutableStateOf<Map<Int, Map<Int, TmdbEpisodeInfo>>>(emptyMap()) }
    LaunchedEffect(seriesId, selectedSeason, info, series == null) {
        val s = series ?: return@LaunchedEffect
        if (tmdbSeason.containsKey(selectedSeason)) return@LaunchedEffect
        val fetched = viewModel.resolveTmdbSeason(
            tmdbId = info?.tmdbId ?: s.tmdbId,
            title = s.displayName,
            seasonNumber = selectedSeason,
        ) ?: return@LaunchedEffect
        tmdbSeason = tmdbSeason + (selectedSeason to fetched)
    }
    val seasonStills = tmdbSeason[selectedSeason].orEmpty()

    // The focused episode card latches here and retitles / re-orders the Cast
    // and Crew row. The latch deliberately survives focus leaving the card so
    // D-pad Down from a card still lands on the strip (Apple 561-565).
    var peopleEpisodeNumber by remember(seriesId) { mutableStateOf<Int?>(null) }
    val castCrewPeople = remember(tmdbCredits, peopleEpisodeNumber, seasonStills) {
        val base = tmdbCredits?.let { c -> c.cast + c.directors }.orEmpty()
        val episodeInfo = peopleEpisodeNumber?.let { seasonStills[it] }
        (base + episodeInfo?.guestStars.orEmpty() + episodeInfo?.crew.orEmpty()).distinctBy { it.id }
    }
    val castCrewTitle = peopleEpisodeNumber
        ?.let { "Cast & Crew · Episode $it" }
        ?: "Cast & Crew"

    fun episodeTitle(ep: DispatcharrVODEpisode): String {
        val provider = ep.displayName.trim()
        if (provider.length > 2) return provider
        val tmdbName = ep.episodeNumber?.let { seasonStills[it]?.name }
        return tmdbName ?: "Episode ${ep.episodeNumber ?: ep.id}"
    }

    fun episodeStill(ep: DispatcharrVODEpisode): String? =
        ep.episodeNumber
            ?.let { seasonStills[it]?.stillPath }
            ?.let { viewModel.tmdbStillImageUrl(it) }
            ?: ep.stillImageUrl

    // Capture episode metadata + an up-next queue (rest of the series, ordered
    // by season then episode, capped at 50) before launching the player, so
    // finishing this episode advances Continue Watching to the next one
    // (iOS Issue #19). This is a METADATA-ONLY annotate: a full save(0) here
    // wiped the resume point on every launch from the detail page.
    val playEpisode: (DispatcharrVODEpisode, Boolean) -> Unit = { ep, fromStart ->
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
        onEpisodeClick(ep, fromStart)
    }

    val toggleWatched: (DispatcharrVODEpisode) -> Unit = { ep ->
        watchVm.setEpisodeWatched(
            videoId = ep.uuid,
            title = ep.displayName,
            posterUrl = ep.stillImageUrl,
            seriesId = seriesId.toString(),
            seasonNumber = ep.seasonNumber ?: 0,
            episodeNumber = ep.episodeNumber ?: 0,
            watched = progressByEpisode[ep.uuid]?.isFinished != true,
        )
    }

    val isTv = rememberLiveTvFormFactor().isTv
    val edgeInset = if (isTv) TV_DETAIL_INSET else 16.dp

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

    val trailerUrl = info?.effectiveTrailer?.let { youtubeUrl(it) }
    val tmdbUrl = (info?.tmdbId ?: series?.tmdbId)?.takeIf { it.isNotBlank() }?.let {
        "https://www.themoviedb.org/tv/$it"
    }
    val openLink: (String, String) -> Unit = { label, url ->
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
    }

    // Scroll-to-top fix (single-owner page scroll model, unchanged): whichever
    // focusable container is topmost THIS composition calls this on gaining
    // focus to bring the hero back. On TV the hero's own action row is now the
    // topmost focusable, so it owns the call.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val maybeScrollHeroIntoView: () -> Unit = {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            scope.launch {
                // The focus move that lands here queues its own bring-into-view,
                // which steals the scroll mutex and cancels this animation
                // mid-flight, leaving the hero clipped. Retry until the list
                // genuinely rests at the top.
                repeat(4) {
                    runCatching { listState.animateScrollToItem(0) }
                    if (listState.firstVisibleItemIndex == 0 &&
                        listState.firstVisibleItemScrollOffset == 0
                    ) {
                        return@launch
                    }
                    delay(90L)
                }
            }
        }
    }
    // Mirrors SeriesInfoSection's own chip-visibility logic; keep in sync.
    // On TV the chips moved into the hero action row, so the info block never
    // holds a focusable there.
    val hasInfoChips = !isTv && (trailerUrl != null || tmdbUrl != null)

    // TV: focus the primary Play button the moment it exists (Apple makes it
    // prefersDefaultFocus and re-asserts it with a retry loop, 427-445).
    val firstActionFocus = remember(seriesId) { FocusRequester() }
    var initialFocusDone by remember(seriesId) { mutableStateOf(false) }
    if (isTv) {
        LaunchedEffect(target != null, episodesInSeason.isNotEmpty()) {
            if (initialFocusDone) return@LaunchedEffect
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
                if (resolvingSeries) {
                    androidx.compose.material3.CircularProgressIndicator()
                } else {
                    Text(
                        text = "Series not found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // TV: same large-card deadband spec as the VOD grids; the Cast &
            // Crew row's person cards otherwise bounce the whole screen on
            // D-pad left/right (user report, round 2).
            val bringSpec = if (isTv) com.aeriotv.android.ui.tv.TvLargeCardBringIntoViewSpec
            else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides bringSpec,
            ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = if (isTv) 60.dp else 32.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item {
                    if (isTv) {
                        val plot = tmdbDetails?.overview?.takeIf { it.isNotBlank() } ?: cachedOverview
                            ?: info?.effectivePlot?.takeIf { it.isNotBlank() }
                            ?: series.plot?.takeIf { it.isNotBlank() }
                        val genreToken = (info?.effectiveGenre ?: series.genre ?: tmdbDetails?.genres)
                            ?.split(',', '/', '|')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        TvDetailHero(
                            artUrl = cachedBackdropUrl ?: info?.backdropUrl ?: series.posterUrl ?: tmdbPosterUrl,
                            title = series.displayName,
                            // tvOS series meta: year, first genre token (a
                            // series carries no runtime), then the rating.
                            metaParts = listOfNotNull(
                                info?.year?.toString() ?: series.year?.toString() ?: tmdbDetails?.year,
                                genreToken,
                            ),
                            rating = (info?.rating?.takeIf { it.isNotBlank() } ?: series.rating)
                                ?.let { runCatching { String.format("%.1f", it.toDouble()) }.getOrDefault(it) }
                                ?.takeIf { it.isNotBlank() && it != "0.0" }
                                ?: tmdbDetails?.voteAverage,
                            plot = plot,
                        ) {
                            TvHeroActionButton(
                                title = when {
                                    isLoading && episodes.isEmpty() -> "Loading…"
                                    else -> target?.label ?: "Play"
                                },
                                icon = Icons.Filled.PlayArrow,
                                primary = true,
                                modifier = Modifier
                                    .focusRequester(firstActionFocus)
                                    .onFocusChanged { if (it.isFocused) maybeScrollHeroIntoView() },
                                onClick = { target?.let { playEpisode(it.episode, false) } },
                            )
                            if (target?.resuming == true) {
                                TvHeroActionButton(
                                    title = "Play from Beginning",
                                    icon = Icons.Filled.Replay,
                                    onClick = { playEpisode(target.episode, true) },
                                )
                            }
                            if (versionOptions.size > 1) {
                                TvHeroActionButton(
                                    title = "Version: ${selectedVersion?.label ?: "Auto"}",
                                    icon = Icons.Outlined.Tune,
                                    onClick = { showVersionPicker = true },
                                )
                            }
                            trailerUrl?.let { url ->
                                TvHeroActionButton(
                                    title = "Trailer",
                                    icon = Icons.Outlined.PlayCircle,
                                    onClick = { openLink("Trailer", url) },
                                )
                            }
                            tmdbUrl?.let { url ->
                                TvHeroActionButton(
                                    title = "TMDB",
                                    icon = Icons.Outlined.Info,
                                    onClick = { openLink("View on TMDB", url) },
                                )
                            }
                        }
                    } else {
                        SeriesHeroSection(
                            series = series,
                            info = info,
                            tmdbPosterUrl = tmdbPosterUrl,
                            tmdbDetails = tmdbDetails,
                            tmdbBackdropUrl = cachedBackdropUrl,
                            isTv = false,
                        )
                    }
                }
                if (!isTv) {
                    item {
                        SeriesInfoSection(
                            series = series,
                            info = info,
                            tmdbDetails = tmdbDetails,
                            cachedOverview = cachedOverview,
                            isTv = false,
                            castPhotosVisible = castCrewPeople.isNotEmpty(),
                            // Only offered when there is an actual choice (> 1
                            // provider copy on a Dispatcharr Direct Connect source).
                            versionLabel = if (versionOptions.size > 1) {
                                selectedVersion?.label ?: "Auto"
                            } else {
                                null
                            },
                            onVersionClick = { showVersionPicker = true },
                            versionOptions = versionOptions,
                            selectedVersion = selectedVersion,
                            onVersionSelect = { option -> viewModel.selectSeriesVersion(seriesId, option) },
                            tmdbPosterUsed = tmdbPosterUrl != null,
                            hasProviderArt = hasServerArt,
                            tmdbConfigured = tmdbConfigured,
                            tmdbLookupDone = tmdbLookupDone,
                            // The chip row, when present, is the topmost
                            // focusable on the phone page.
                            chipRowModifier = Modifier.onFocusChanged {
                                if (it.hasFocus) maybeScrollHeroIntoView()
                            },
                            onOpenUrl = openLink,
                        )
                    }
                }

                // tvOS order puts the seasons section directly under the hero
                // and Cast and Crew after it; the phone keeps cast above the
                // episode list (VODDetailView 349-383).
                if (!isTv && castCrewPeople.isNotEmpty()) {
                    item(key = "cast-crew") {
                        CastCrewSection(
                            title = castCrewTitle,
                            people = castCrewPeople,
                            isTv = false,
                            profileUrl = viewModel::tmdbProfileImageUrl,
                            onPersonClick = { bioPerson = it },
                            modifier = Modifier.onFocusChanged {
                                if (it.hasFocus && !hasInfoChips) maybeScrollHeroIntoView()
                            },
                        )
                    }
                }

                if (seasons.size > 1) {
                    item(key = "season-picker") {
                        SeasonPicker(
                            seasons = seasons.keys.toList(),
                            selected = selectedSeason,
                            onSelect = { selectedSeason = it },
                            edgeInset = edgeInset,
                            isTv = isTv,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                } else if (isTv && episodes.isNotEmpty()) {
                    // Apple renders a static "Episodes" header for a
                    // single-season show (VODDetailView 856-860).
                    item(key = "episodes-header") {
                        Text(
                            text = "Episodes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = edgeInset),
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
                } else if (isTv) {
                    // tvOS episode CARDS in a horizontal strip, 32 pt spacing
                    // and 36 pt vertical headroom for the focus scale.
                    item(key = "episode-strip") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = edgeInset, vertical = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(items = episodesInSeason, key = { it.id }) { ep ->
                                TvEpisodeCard(
                                    title = episodeTitle(ep),
                                    meta = listOfNotNull(
                                        ep.durationSecs?.takeIf { it > 0 }?.let { formatEpisodeDuration(it) },
                                        ep.airDate?.let { formatAirDate(it) }?.takeIf { it.isNotBlank() },
                                    ).joinToString(" · ").takeIf { it.isNotBlank() },
                                    episodeNumber = ep.episodeNumber,
                                    stillUrl = episodeStill(ep),
                                    progress = progressByEpisode[ep.uuid],
                                    onClick = { playEpisode(ep, false) },
                                    onToggleWatched = { toggleWatched(ep) },
                                    modifier = Modifier.onFocusChanged {
                                        if (it.isFocused) peopleEpisodeNumber = ep.episodeNumber
                                    },
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(items = episodesInSeason, key = { _, ep -> ep.id }) { index, ep ->
                        EpisodeRow(
                            episode = ep,
                            title = episodeTitle(ep),
                            stillUrl = episodeStill(ep),
                            progress = progressByEpisode[ep.uuid],
                            isTv = false,
                            onClick = { playEpisode(ep, false) },
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .then(
                                    // Topmost only when nothing focusable
                                    // renders above the episode list.
                                    if (index == 0 && !hasInfoChips && castCrewPeople.isEmpty() &&
                                        seasons.size <= 1
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

                if (isTv && castCrewPeople.isNotEmpty()) {
                    item(key = "cast-crew-tv") {
                        CastCrewSection(
                            title = castCrewTitle,
                            people = castCrewPeople,
                            isTv = true,
                            profileUrl = viewModel::tmdbProfileImageUrl,
                            onPersonClick = { bioPerson = it },
                        )
                    }
                }

                if (isTv) {
                    item(key = "details-block") {
                        val genre = info?.effectiveGenre?.takeIf { it.isNotBlank() }
                            ?: series.genre?.takeIf { it.isNotBlank() } ?: tmdbDetails?.genres
                        val cast = info?.effectiveCast?.takeIf { it.isNotBlank() } ?: tmdbDetails?.castTop
                        val director = info?.effectiveDirector?.takeIf { it.isNotBlank() } ?: tmdbDetails?.director
                        val facts = buildList {
                            genre?.let { add("Genre" to it) }
                            info?.releaseDate?.takeIf { it.length > 4 }?.let { add("Released" to it) }
                            if (episodes.isNotEmpty()) {
                                add("Seasons" to "${seasons.size} seasons, ${episodes.size} episodes")
                            }
                            director?.let { add("Director" to it) }
                            if (castCrewPeople.isEmpty()) cast?.let { add("Cast" to it) }
                            info?.effectiveCountry?.takeIf { it.isNotBlank() }?.let { add("Country" to it) }
                        }
                        TvDetailsBlock(facts = facts) {
                            TmdbSourceNote(
                                tmdbPosterUsed = tmdbPosterUrl != null,
                                tmdbDetailsPresent = tmdbDetails != null,
                                hasProviderArt = hasServerArt,
                                tmdbConfigured = tmdbConfigured,
                                lookupDone = tmdbLookupDone,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                if (relatedItems.isNotEmpty()) {
                    item(key = "related") {
                        if (isTv) {
                            TvRelatedSection(
                                items = relatedItems,
                                watchlisted = { it.key in watchlistKeys },
                                onOpen = { item ->
                                    item.movieUuid?.let(onOpenMovie) ?: item.seriesId?.let(onOpenSeries)
                                },
                                onToggleWatchlist = { watchlistVm.toggle(it) },
                            )
                        } else {
                            RelatedSection(items = relatedItems, onOpenMovie = onOpenMovie, onOpenSeries = onOpenSeries)
                        }
                    }
                }
                item(key = "tmdb-attribution") {
                    // iOS puts the long TMDB attribution at the very bottom of
                    // the page on BOTH platforms (VODDetailView 377-380).
                    TmdbAttribution(
                        modifier = Modifier.padding(horizontal = edgeInset).padding(top = 24.dp),
                        long = true,
                        isTv = isTv,
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

        if (showVersionPicker) {
            VodVersionPickerSheet(
                options = versionOptions,
                selected = selectedVersion,
                onSelect = { option ->
                    viewModel.selectSeriesVersion(seriesId, option)
                    showVersionPicker = false
                },
                onDismiss = { showVersionPicker = false },
            )
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
            // Known For tiles resolve against the library and push the
            // matched detail route. No dismissal on success: the push
            // disposes this composition (dialog included), and on BACK the
            // dialog state re-lands closed over THIS title, which is the
            // desired return-to-origin behavior.
            PersonBioDialog(
                person = person,
                fetchBio = viewModel::resolveTmdbPersonBio,
                profileUrl = viewModel::tmdbProfileImageUrl,
                onDismiss = { bioPerson = null },
                isTv = isTv,
                onTileClick = { item ->
                    when (val target2 = viewModel.resolveKnownForTarget(item)) {
                        is OnDemandViewModel.KnownForTarget.Movie -> { onOpenMovie(target2.uuid); true }
                        is OnDemandViewModel.KnownForTarget.Series -> { onOpenSeries(target2.id); true }
                        null -> false
                    }
                },
            )
        }
    }
}

@Composable
private fun SeriesHeroSection(
    series: DispatcharrVODSeries,
    info: DispatcharrVODProviderInfo?,
    /** Cached TMDB backdrop for this title; wins over the provider's art. */
    tmdbBackdropUrl: String? = null,
    tmdbPosterUrl: String?,
    tmdbDetails: TmdbDetails?,
    isTv: Boolean,
) {
    val heroUrl = tmdbBackdropUrl ?: info?.backdropUrl ?: series.posterUrl ?: tmdbPosterUrl
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
            // Phone: iOS keeps an inline nav bar in the app background so the
            // hero never runs under the status bar; inset the hero the same
            // way (the floating back circle still overlays the artwork).
            .then(if (isTv) Modifier else Modifier.statusBarsPadding())
            // 16:11 of the 960dp TV canvas is 660dp, taller than the whole
            // 540dp screen: the title block sat below the fold and the screen
            // read as a full-screen poster. Fixed 300dp hero on TV; phones
            // use the iPhone's fixed 280dp hero (VODDetailView 1181).
            .then(if (isTv) Modifier.height(300.dp) else Modifier.height(280.dp)),
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
        // Phone: iOS heroOverlay is two stops, clear at 50% height to the
        // app background at the bottom (Colors.swift 137-141).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (isTv) {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                                MaterialTheme.colorScheme.background,
                            ),
                        )
                    } else {
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.background,
                        )
                    },
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
                } else if (isTv) {
                    // Phone matches iOS: no placeholder glyph, just the tile.
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
    /** TMDB synopsis from the persistent art cache. */
    cachedOverview: String? = null,
    isTv: Boolean,
    castPhotosVisible: Boolean,
    // Non-null only when there is more than one provider copy to pick from
    // (Dispatcharr Direct Connect); renders the "Version: {label}" pill.
    versionLabel: String? = null,
    onVersionClick: () -> Unit = {},
    // Phone: the version menu picks inline (iOS versionRow Menu); TV keeps
    // the sheet behind onVersionClick.
    versionOptions: List<VodProviderOption> = emptyList(),
    selectedVersion: VodProviderOption? = null,
    onVersionSelect: (VodProviderOption?) -> Unit = {},
    // Phone: TMDB provenance note inputs (iOS tmdbSourceNote).
    tmdbPosterUsed: Boolean = false,
    hasProviderArt: Boolean = true,
    tmdbConfigured: Boolean = false,
    tmdbLookupDone: Boolean = false,
    chipRowModifier: Modifier,
    onOpenUrl: (label: String, url: String) -> Unit,
) {
    // Server-provided values always win; TMDB backfills only the holes. The
    // synopsis is the exception: TMDB's wins when there is one.
    val plot = tmdbDetails?.overview?.takeIf { it.isNotBlank() } ?: cachedOverview
        ?: info?.effectivePlot?.takeIf { it.isNotBlank() } ?: series.plot?.takeIf { it.isNotBlank() }
    val genre = info?.effectiveGenre?.takeIf { it.isNotBlank() } ?: series.genre?.takeIf { it.isNotBlank() }
        ?: tmdbDetails?.genres
    val cast = info?.effectiveCast?.takeIf { it.isNotBlank() } ?: tmdbDetails?.castTop
    val director = info?.effectiveDirector?.takeIf { it.isNotBlank() } ?: tmdbDetails?.director
    val country = info?.effectiveCountry?.takeIf { it.isNotBlank() }
    // DispatcharrVODSeries carries no youtube_trailer field of its own, so
    // provider-info is the only trailer source for series.
    val trailerUrl = info?.effectiveTrailer?.let { youtubeUrl(it) }
    val tmdbUrl = (info?.tmdbId ?: series.tmdbId)?.takeIf { it.isNotBlank() }?.let {
        "https://www.themoviedb.org/tv/$it"
    }
    // Foldable (#40): cap only the long synopsis to a readable line length on
    // the unfolded Medium panel; Dp.Unspecified (no-op) on folded phone and TV.
    val readableCap = if (isTv) Dp.Unspecified else rememberViewport().readableMaxWidth

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!plot.isNullOrBlank()) {
            if (isTv) {
                // No cap on TV: series plots can run long with episode
                // summaries layered in, and there is no tap to expand.
                Text(
                    text = plot,
                    modifier = Modifier.widthIn(max = readableCap),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // iPhone clamps to 4 lines (VODDetailView 1283); "More"
                // keeps the full text reachable.
                ExpandablePlot(plot = plot, maxWidth = readableCap)
            }
        }
        // Phone: version pill on its own row ABOVE the link pills (iOS
        // versionRow precedes externalLinks, VODDetailView 1289).
        if (!isTv && versionLabel != null) {
            PhoneVersionPill(
                options = versionOptions,
                selected = selectedVersion,
                onSelect = onVersionSelect,
            )
        }
        if (trailerUrl != null || tmdbUrl != null || (isTv && versionLabel != null)) {
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
                if (isTv && versionLabel != null) {
                    PillButton(icon = Icons.Outlined.Tune, text = "Version: $versionLabel") {
                        onVersionClick()
                    }
                }
            }
        }
        val row: @Composable (String, String) -> Unit = { label, value ->
            if (isTv) MetaRow(label, value) else PhoneMetaRow(label, value)
        }
        // iOS order: Genre first, then Released (VODDetailView 1305, 1313).
        if (!genre.isNullOrBlank()) row("Genre", genre)
        // v0.26.0 release_date as its own row when it carries more than the
        // bare year already shown in the hero. Mirrors iOS VODDetailView.
        info?.releaseDate?.takeIf { it.length > 4 }?.let { row("Released", it) }
        // The text rows duplicate the Cast & Crew photo strip when it
        // renders; they stay as the fallback when TMDB enrichment is off
        // or returned nothing for this title.
        if (!cast.isNullOrBlank() && !castPhotosVisible) row("Cast", cast)
        if (!director.isNullOrBlank() && !castPhotosVisible) row("Director", director)
        if (!country.isNullOrBlank()) row("Country", country)
        if (isTv) {
            TmdbAttribution(modifier = Modifier.padding(top = 12.dp), long = true, isTv = true)
        } else {
            TmdbSourceNote(
                tmdbPosterUsed = tmdbPosterUsed,
                tmdbDetailsPresent = tmdbDetails != null,
                hasProviderArt = hasProviderArt,
                tmdbConfigured = tmdbConfigured,
                lookupDone = tmdbLookupDone,
            )
        }
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
    title: String,
    people: List<TmdbPerson>,
    isTv: Boolean,
    profileUrl: (String?, String) -> String?,
    onPersonClick: (TmdbPerson) -> Unit,
    modifier: Modifier = Modifier,
) {
    val edgeInset = if (isTv) TV_DETAIL_INSET else 16.dp
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = edgeInset),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(
                horizontal = edgeInset,
                // tvOS reserves 36 pt = 18 dp of headroom so the focus scale
                // never clips; phone 12 dp matches the iPhone cast strip.
                vertical = if (isTv) 18.dp else 12.dp,
            ),
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
            .width(if (isTv) 100.dp else 90.dp)
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
        // Name and role centered under the photo (Logan 2026-09-10, all platforms).
        Text(
            text = person.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        person.role?.takeIf { it.isNotBlank() }?.let { role ->
            Text(
                text = role,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
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
    isTv: Boolean = false,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = edgeInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = seasons) { season ->
            val isSelected = season == selected
            if (isTv) {
                // TV chrome canon (ui/tv/TvChrome.kt): same capsule + ring rules
                // as the library group pills (tvOS MoviesPillStyle).
                com.aeriotv.android.ui.tv.TvPill(
                    // Apple always renders "Season {n}" (VODDetailView 849).
                    label = "Season $season",
                    selected = isSelected,
                    onClick = { onSelect(season) },
                )
                return@items
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    // Phone: iOS unselected pill sits on the elevated background.
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(season) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                // Phone: always "Season N" (iOS VODDetailView 1557); selected
                // reads in the app background over the accent fill, unselected
                // in secondary text (1552).
                Text(
                    text = "Season $season",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
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
    /** Provider title when usable, else the TMDB name, else "Episode {n}". */
    title: String,
    stillUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Phone: watch-progress chrome (iOS TVEpisodeRowButton 2467-2508).
    progress: com.aeriotv.android.core.data.db.entity.WatchProgressEntity? = null,
    isTv: Boolean = false,
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
            // Phone: 4 + the call site's 12 = 16 horizontal, 10 vertical
            // (iOS VODDetailView 2519).
            .padding(horizontal = 4.dp, vertical = if (isTv) 6.dp else 10.dp),
        verticalAlignment = if (isTv) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 14.dp),
    ) {
        Box(
            modifier = Modifier
                // Phone: 96x54 radius 6 (iOS VODDetailView 2411).
                .then(if (isTv) Modifier.width(112.dp).aspectRatio(16f / 9f) else Modifier.size(96.dp, 54.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            val still = stillUrl
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
                append(title)
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
            if (!isTv && progress != null) {
                Spacer(Modifier.height(4.dp))
                EpisodeProgressChrome(progress)
            }
        }

        if (isTv) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier
                    .size(28.dp)
                    .padding(top = 8.dp),
            )
        } else {
            // iOS: filled play circle, 22pt at 70% accent (VODDetailView 2515).
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp),
            )
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
