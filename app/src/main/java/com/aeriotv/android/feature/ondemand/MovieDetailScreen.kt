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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.network.DispatcharrVODMovie
import com.aeriotv.android.core.network.DispatcharrVODProviderInfo
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
import com.aeriotv.android.feature.movies.MediaPosterCard
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
import com.aeriotv.android.ui.tv.tvFocusScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Movie detail screen. Mirrors iOS VODDetailView (Aerio/Features/VOD/VODDetailView.swift)
 * line 268-362 hero pattern + 365-418 info section:
 *
 *  - Hero ZStack: 16:9 backdrop image + bottom-gradient overlay so the title
 *    block stays readable over busy artwork. Small movie poster anchored to
 *    the bottom-leading corner with the title / meta strip / Play CTA next
 *    to it. Backdrop image comes from provider-info; falls back to the
 *    poster if provider-info hasn't finished loading.
 *  - Plot copy directly below.
 *  - Trailer + View on TMDB pill chip row.
 *  - Genre / Cast / Director / Country labeled rows.
 *
 * Provider-info (cast / director / country / trailer / backdrop) loads lazily
 * on first paint via [OnDemandViewModel.loadMovieProviderInfo]; the screen
 * renders whatever's available immediately so the user never sees an empty
 * shell while the upstream Xtream fetch (Dispatcharr line 1693-1701) runs.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MovieDetailScreen(
    movieUuid: String,
    onBack: () -> Unit,
    /** fromStart is passed per launch (never held in view state) and zeroes
     *  the start position WITHOUT clearing the WatchProgress row (Apple C3). */
    onPlay: (fromStart: Boolean) -> Unit,
    // Known For tile pushes from the bio dialog: plain navigation pushes so
    // remote BACK returns here. Defaults keep non-nav call sites compiling.
    onOpenMovie: (String) -> Unit = {},
    onOpenSeries: (Int) -> Unit = {},
    viewModel: OnDemandViewModel = hiltViewModel(),
    watchVm: WatchProgressViewModel = hiltViewModel(),
    watchlistVm: com.aeriotv.android.feature.movies.WatchlistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val movie = viewModel.movieByUuid(movieUuid)
    val context = LocalContext.current
    // Keyed lookup, Apple's WatchProgressManager.getResumePosition
    // (VODModels.swift:280-286): the recent-50 scan read any movie outside the
    // 50 most recently touched rows as no-resume.
    val progress by watchVm.observe(movieUuid).collectAsStateWithLifecycle(initialValue = null)
    // Continue Watching / Watchlist row for a title the library walk never
    // loaded: fetch it by name before giving up with "not found".
    val progressTitle = progress?.title
    LaunchedEffect(movieUuid, movie == null, progressTitle) {
        if (movie == null) viewModel.resolveMovie(movieUuid, progressTitle)
    }
    val resolvingMovie = movie == null && viewModel.isResolving("m:$movieUuid")
    // getResumePosition returns nil for a finished row and for position 0.
    val hasResume = progress?.let {
        !it.isFinished && it.positionMs > 0L &&
            (it.durationMs <= 0L || it.positionMs < it.durationMs - 5 * 60_000L)
    } == true
    val info = movie?.id?.let { state.movieProviderInfo[it] }

    LaunchedEffect(movie?.id) {
        movie?.id?.let {
            viewModel.loadMovieProviderInfo(it)
            // Version picker data (Dispatcharr Direct Connect only; the VM
            // caches empty for every other source so the pill never renders).
            viewModel.loadMovieProviders(it)
        }
    }
    val versionOptions = movie?.id?.let { state.movieProviders[it] }.orEmpty()
    val selectedVersion = movie?.id?.let { state.selectedMovieVersion[it] }
    var showVersionPicker by remember(movie?.id) { mutableStateOf(false) }

    // TMDB poster fallback (opt-in). Resolves ONLY when the server supplied no
    // artwork (no logo, no provider poster/backdrop) and provider-info has
    // settled, so it never overrides a real poster or hits TMDB needlessly.
    var tmdbPosterUrl by remember(movie?.id) { mutableStateOf<String?>(null) }
    // Provenance note inputs (phone): whether the poster lookup has run, and
    // whether the opt-in + key are set, so an art-less title can say "add a
    // key" vs "no match" (iOS tmdbLookupDone / TMDBPosters.apiKey).
    var tmdbLookupDone by remember(movie?.id) { mutableStateOf(false) }
    // Persistent art cache (Logan 2026-09-04: TMDB first when a key is set).
    // The background library pass has usually resolved this title already, so
    // the hero and synopsis are right on the first frame with no request.
    val artVersion by viewModel.artVersion.collectAsStateWithLifecycle(initialValue = 0)
    val artKey = remember(movie?.uuid, movie?.displayName) {
        movie?.let { tmdbArtKey(displayTitle(it.displayName, it.year), true) }
    }
    val cachedBackdropUrl = remember(artKey, artVersion) { artKey?.let { viewModel.artBackdropUrl(it) } }
    val cachedOverview = remember(artKey, artVersion) { artKey?.let { viewModel.artOverview(it) } }
    var tmdbConfigured by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { tmdbConfigured = viewModel.isTmdbConfigured() }
    val hasServerArt = movie != null && (
        !movie.logo?.url.isNullOrBlank() ||
            !info?.posterUrl.isNullOrBlank() || !info?.backdropUrl.isNullOrBlank()
        )
    LaunchedEffect(movie?.id, info, state.movieProviderInfoLoading) {
        val m = movie ?: return@LaunchedEffect
        val infoSettled = m.id == null || state.movieProviderInfo.containsKey(m.id) ||
            !state.movieProviderInfoLoading.contains(m.id)
        if (!hasServerArt && tmdbPosterUrl == null && infoSettled) {
            tmdbPosterUrl = viewModel.resolveTmdbPoster(
                tmdbId = info?.tmdbId ?: m.tmdbId,
                title = m.displayName,
                isMovie = true,
            )
            tmdbLookupDone = true
        }
    }

    // TMDB metadata backfill (same opt-in gate as the poster fallback).
    // Fetched only when the server left at least one of plot / genre / cast /
    // director blank after provider-info settled; server values always win,
    // TMDB only fills the holes. Fully-described libraries never hit TMDB.
    var tmdbDetails by remember(movie?.id) { mutableStateOf<TmdbDetails?>(null) }
    LaunchedEffect(movie?.id, info, state.movieProviderInfoLoading) {
        val m = movie ?: return@LaunchedEffect
        val infoSettled = m.id == null || state.movieProviderInfo.containsKey(m.id) ||
            !state.movieProviderInfoLoading.contains(m.id)
        val missingMeta = (info?.effectivePlot ?: m.plot).isNullOrBlank() ||
            (info?.effectiveGenre ?: m.genre).isNullOrBlank() ||
            info?.effectiveCast.isNullOrBlank() ||
            info?.effectiveDirector.isNullOrBlank()
        if (missingMeta && tmdbDetails == null && infoSettled) {
            tmdbDetails = viewModel.resolveTmdbDetails(
                tmdbId = info?.tmdbId ?: m.tmdbId,
                title = m.displayName,
                isMovie = true,
            )
        }
    }

    // Structured TMDB credits for the Cast & Crew strip. Independent of the
    // missingMeta gate above: servers only ever send comma-separated name
    // strings, so headshots always need TMDB. The resolver returns null when
    // the TMDB opt-in or key is absent and the strip simply does not render.
    var tmdbCredits by remember(movie?.id) { mutableStateOf<TmdbCredits?>(null) }
    LaunchedEffect(movie?.id, info, state.movieProviderInfoLoading) {
        val m = movie ?: return@LaunchedEffect
        val infoSettled = m.id == null || state.movieProviderInfo.containsKey(m.id) ||
            !state.movieProviderInfoLoading.contains(m.id)
        if (tmdbCredits == null && infoSettled) {
            tmdbCredits = viewModel.resolveTmdbCredits(
                tmdbId = info?.tmdbId ?: m.tmdbId,
                title = m.displayName,
                isMovie = true,
            )
        }
    }
    // Cast first, then directors, deduped by id so a directing actor doesn't
    // show twice (the cast entry wins; it carries the character name).
    val castCrewPeople = remember(tmdbCredits) {
        tmdbCredits?.let { c -> (c.cast + c.directors).distinctBy { it.id } }.orEmpty()
    }
    var bioPerson by remember { mutableStateOf<TmdbPerson?>(null) }
    // "Related": TMDB recommendations filtered to the local library. Keyed on
    // the library size too so it re-matches as the launch sweep publishes.
    var relatedItems by remember(movieUuid) { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(movieUuid, movie?.id, info, state.movies.size) {
        val m = movie ?: return@LaunchedEffect
        relatedItems = viewModel.relatedTitles(
            tmdbId = info?.tmdbId ?: m.tmdbId,
            title = m.displayName,
            isMovie = true,
            selfKey = "m:${m.uuid}",
        )
    }

    BackHandler(enabled = true) { onBack() }
    val isTv = rememberLiveTvFormFactor().isTv
    val watchlistEntries by watchlistVm.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val watchlistKeys = remember(watchlistEntries) { watchlistEntries.map { it.key }.toSet() }
    // tvOS puts Version / Trailer / TMDB in the hero action row, so the URLs
    // are resolved at screen scope (the phone keeps them in the info block).
    val heroTrailerUrl = (info?.effectiveTrailer ?: movie?.youtubeTrailer?.takeIf { it.isNotBlank() })
        ?.let { youtubeUrl(it) }
    val heroTmdbUrl = (info?.tmdbId ?: movie?.tmdbId)?.takeIf { it.isNotBlank() }?.let {
        "https://www.themoviedb.org/movie/$it"
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
        if (movie == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (resolvingMovie) {
                    androidx.compose.material3.CircularProgressIndicator()
                } else {
                    Text(
                        text = "Movie not found",
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
            val detailListState = androidx.compose.foundation.lazy.rememberLazyListState()
            val detailScope = androidx.compose.runtime.rememberCoroutineScope()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = detailListState,
                // 32dp left the cast row sitting on the very bottom edge of a
                // TV panel with its focus scale clipped (Logan, 2026-08-15).
                // The last row needs somewhere to scroll into.
                contentPadding = PaddingValues(bottom = if (isTv) 96.dp else 32.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item {
                    if (isTv) {
                        val heroPlot = tmdbDetails?.overview?.takeIf { it.isNotBlank() } ?: cachedOverview
                            ?: info?.effectivePlot?.takeIf { it.isNotBlank() }
                            ?: movie.plot?.takeIf { it.isNotBlank() }
                        val genreToken = (info?.effectiveGenre ?: movie.genre ?: tmdbDetails?.genres)
                            ?.split(',', '/', '|')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        val runtimeSecs = info?.durationSecs?.takeIf { it > 0 }
                            ?: movie.durationSecs?.takeIf { it > 0 }
                        val playFocus = remember { FocusRequester() }
                        LaunchedEffect(Unit) {
                            repeat(10) {
                                if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                                delay(16L)
                            }
                        }
                        TvDetailHero(
                            artUrl = cachedBackdropUrl ?: info?.backdropUrl ?: movie.logo?.url ?: tmdbPosterUrl,
                            // tvOS displayName drops every trailing
                            // "(YYYY)" group (VODModels.swift:1284).
                            title = displayTitle(movie.displayName, null),
                            // tvOS movie meta: year, runtime, first genre token
                            // (no MOVIE chip on TV), then the rating.
                            metaParts = listOfNotNull(
                                info?.year?.toString() ?: movie.year?.toString() ?: tmdbDetails?.year,
                                runtimeSecs?.let { formatDuration(it) },
                                genreToken,
                            ),
                            rating = (info?.rating?.takeIf { it.isNotBlank() } ?: movie.rating)
                                ?.let { runCatching { String.format("%.1f", it.toDouble()) }.getOrDefault(it) }
                                ?.takeIf { it.isNotBlank() && it != "0.0" }
                                ?: tmdbDetails?.voteAverage,
                            plot = heroPlot,
                        ) {
                            TvHeroActionButton(
                                title = if (hasResume) "Resume" else "Play",
                                icon = Icons.Filled.PlayArrow,
                                primary = true,
                                modifier = Modifier
                                    .focusRequester(playFocus)
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            detailScope.launch {
                                                runCatching { detailListState.animateScrollToItem(0) }
                                            }
                                        }
                                    },
                                onClick = { onPlay(false) },
                            )
                            if (hasResume) {
                                TvHeroActionButton(
                                    title = "Play from Beginning",
                                    icon = Icons.Filled.Replay,
                                    onClick = { onPlay(true) },
                                )
                            }
                            if (versionOptions.size > 1) {
                                TvHeroActionButton(
                                    title = "Version: ${selectedVersion?.label ?: "Auto"}",
                                    icon = Icons.Outlined.Tune,
                                    onClick = { showVersionPicker = true },
                                )
                            }
                            heroTrailerUrl?.let { url ->
                                TvHeroActionButton(
                                    title = "Trailer",
                                    icon = Icons.Outlined.PlayCircle,
                                    onClick = {
                                        if (youtubeResolvable) {
                                            trailerMenuUrl = url
                                        } else {
                                            qrLink = TvQrLink(
                                                title = "Trailer",
                                                caption = "Scan with your phone to watch the trailer on YouTube.",
                                                url = url,
                                            )
                                        }
                                    },
                                )
                            }
                            heroTmdbUrl?.let { url ->
                                TvHeroActionButton(
                                    title = "TMDB",
                                    icon = Icons.Outlined.Info,
                                    onClick = {
                                        qrLink = TvQrLink(
                                            title = "View on TMDB",
                                            caption = "Scan with your phone to view this title on TMDB.",
                                            url = url,
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        HeroSection(
                            movie = movie,
                            info = info,
                            tmdbPosterUrl = tmdbPosterUrl,
                            tmdbDetails = tmdbDetails,
                            tmdbBackdropUrl = cachedBackdropUrl,
                            hasResume = hasResume,
                            isTv = false,
                            onPlay = { onPlay(false) },
                        )
                    }
                }
                if (!isTv) {
                item {
                    InfoSection(
                        movie = movie,
                        info = info,
                        tmdbDetails = tmdbDetails,
                        cachedOverview = cachedOverview,
                        isTv = isTv,
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
                        onVersionSelect = { option ->
                            movie.id?.let { viewModel.selectMovieVersion(it, option) }
                        },
                        tmdbPosterUsed = tmdbPosterUrl != null,
                        hasProviderArt = hasServerArt,
                        tmdbConfigured = tmdbConfigured,
                        tmdbLookupDone = tmdbLookupDone,
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
                }
                if (castCrewPeople.isNotEmpty()) {
                    item {
                        CastCrewSection(
                            people = castCrewPeople,
                            isTv = isTv,
                            profileUrl = viewModel::tmdbProfileImageUrl,
                            onPersonClick = { bioPerson = it },
                        )
                    }
                }
                if (isTv) {
                    // tvDetailsBlock (VODDetailView 1124-1185): the facts grid
                    // sits after Cast and Crew on tvOS, with the provenance
                    // note inside it.
                    item(key = "details-block") {
                        val genre = info?.effectiveGenre?.takeIf { it.isNotBlank() }
                            ?: movie.genre?.takeIf { it.isNotBlank() } ?: tmdbDetails?.genres
                        val cast = info?.effectiveCast?.takeIf { it.isNotBlank() } ?: tmdbDetails?.castTop
                        val director = info?.effectiveDirector?.takeIf { it.isNotBlank() } ?: tmdbDetails?.director
                        val runtimeSecs = info?.durationSecs?.takeIf { it > 0 }
                            ?: movie.durationSecs?.takeIf { it > 0 }
                        // tvOS tvFacts order (VODDetailView.swift:1124-1147)
                        // laid into a 2-column row-major grid: left column
                        // Genre then Runtime, right column Released then
                        // Director.
                        val facts = buildList {
                            genre?.let { add("Genre" to joinGenres(it)) }
                            info?.effectiveReleaseDate?.takeIf { it.length > 4 }
                                ?.let { add("Released" to it) }
                            runtimeSecs?.let { add("Runtime" to formatDuration(it)) }
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
                    item {
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
                item {
                    // iOS puts the long TMDB attribution at the very bottom of
                    // the page on BOTH platforms (VODDetailView 377-380).
                    TmdbAttribution(
                        modifier = Modifier
                            .padding(horizontal = if (isTv) TV_DETAIL_INSET else 16.dp)
                            .padding(top = 24.dp),
                        long = true,
                        isTv = isTv,
                    )
                }
            }
            }
        }

        if (showVersionPicker) {
            VodVersionPickerSheet(
                options = versionOptions,
                selected = selectedVersion,
                onSelect = { option ->
                    movie?.id?.let { viewModel.selectMovieVersion(it, option) }
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
                    when (val target = viewModel.resolveKnownForTarget(item)) {
                        is OnDemandViewModel.KnownForTarget.Movie -> { onOpenMovie(target.uuid); true }
                        is OnDemandViewModel.KnownForTarget.Series -> { onOpenSeries(target.id); true }
                        null -> false
                    }
                },
            )
        }

        // Floating back button overlaid on the hero. Mirrors iOS's small
        // chevron pill drawn on top of the backdrop (the regular nav bar
        // is hidden so the artwork bleeds to the top safe area). Hidden on
        // Android TV (remote BACK pops the screen; same call as the
        // playlist-detail top bar) so it can't trap D-pad focus invisibly.
        if (!isTv) {
            FloatingBackButton(onClick = onBack)
        }
    }
}

@Composable
private fun HeroSection(
    movie: DispatcharrVODMovie,
    info: DispatcharrVODProviderInfo?,
    tmdbPosterUrl: String?,
    tmdbDetails: TmdbDetails?,
    /** Cached TMDB backdrop for this title; wins over the provider's art. */
    tmdbBackdropUrl: String? = null,
    hasResume: Boolean,
    isTv: Boolean,
    onPlay: () -> Unit,
    onPlayFocused: () -> Unit = {},
) {
    val heroUrl = tmdbBackdropUrl ?: info?.backdropUrl ?: movie.logo?.url ?: tmdbPosterUrl
    val posterUrl = movie.logo?.url ?: info?.posterUrl ?: tmdbPosterUrl
    // TMDB sits last in each chain: it only backfills fields the server
    // (provider-info AND the list row) left empty.
    val displayYear = info?.year?.toString() ?: movie.year?.toString() ?: tmdbDetails?.year
    val displayRating = (info?.rating?.takeIf { it.isNotBlank() } ?: movie.rating)
        ?.let { runCatching { String.format("%.1f", it.toDouble()) }.getOrDefault(it) }
        ?.takeIf { it.isNotBlank() && it != "0.0" }
        ?: tmdbDetails?.voteAverage
    val durationSecs = info?.durationSecs?.takeIf { it > 0 } ?: movie.durationSecs?.takeIf { it > 0 }

    // On TV land focus on Play the moment the screen opens; without this the
    // screen had no focused control at all and the D-pad appeared dead.
    val playFocus = remember { FocusRequester() }
    if (isTv) {
        LaunchedEffect(Unit) {
            repeat(10) {
                if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(16L)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Phone: iOS keeps an inline nav bar in the app background so the
            // hero never runs under the status bar; inset the hero the same
            // way (the floating back circle still overlays the artwork).
            .then(if (isTv) Modifier else Modifier.statusBarsPadding())
            // 16:11 of the 960dp-wide TV canvas is 660dp, taller than the
            // whole 540dp screen: the title block and Play CTA sat below the
            // fold, leaving nothing but raw artwork visible. A fixed 300dp
            // hero (~56% of the canvas) keeps them on screen. Phones use the
            // iPhone's fixed 280dp hero (VODDetailView 1181).
            .then(if (isTv) Modifier.height(300.dp) else Modifier.height(280.dp)),
    ) {
        if (!heroUrl.isNullOrBlank()) {
            AsyncImage(
                model = heroUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        }
        // Gradient overlay so the title block reads against any artwork.
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
                            startY = 0f,
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
                    horizontal = if (isTv) TV_DETAIL_INSET else 16.dp,
                    vertical = 16.dp,
                ),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Small poster anchored to bottom-leading like iOS VODDetailView
            // line 304-309.
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            ) {
                if (!posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = movie.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                // iOS VODDetailView line 312 sets the title directly to
                // `item.name` without appending the year; the year already
                // shows on the meta strip below. Dispatcharr often serves
                // titles that already embed "(YYYY)" - appending the
                // resolved year on top of that gives "'Til Death (2006) (2006)".
                Text(
                    text = movie.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                MetaStrip(
                    year = displayYear,
                    rating = displayRating,
                    durationSecs = durationSecs,
                    typeLabel = "MOVIE",
                )
                Spacer(Modifier.height(10.dp))
                PlayCta(
                    hasResume = hasResume,
                    onClick = onPlay,
                    // Coming back UP the page, focus stopping on Play is not
                    // enough: bring-into-view scrolls only far enough to show
                    // the CTA, which sits low in the hero, so the artwork above
                    // it stayed cut off and the hero never looked whole again
                    // (Logan, 2026-08-15). Reaching Play means we are at the
                    // top, so put the list back to a true zero offset.
                    modifier = Modifier
                        .focusRequester(playFocus)
                        .onFocusChanged { if (it.isFocused) onPlayFocused() },
                )
            }
        }
    }
}

/**
 * Year · ★ rating · 1h 19m · MOVIE pill. Each piece independently optional -
 * a sparse movie row (e.g. only year known) shouldn't show a dangling " · ".
 * Mirrors iOS VODDetailView lines 317-353.
 */
@Composable
private fun MetaStrip(
    year: String?,
    rating: String?,
    durationSecs: Int?,
    typeLabel: String?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        if (durationSecs != null && durationSecs > 0) {
            Text(
                text = formatDuration(durationSecs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!typeLabel.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Big rounded cyan Play / Resume button. */
@Composable
private fun PlayCta(
    hasResume: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .tvFocusScale(focused)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            text = if (hasResume) "Resume" else "Play",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun InfoSection(
    movie: DispatcharrVODMovie,
    info: DispatcharrVODProviderInfo?,
    tmdbDetails: TmdbDetails?,
    /** TMDB synopsis from the persistent art cache (the provider's plot can
     *  arrive in another language, Logan 2026-09-04). */
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
    onOpenUrl: (label: String, url: String) -> Unit,
) {
    // Server-provided values always win; TMDB backfills only the holes. The
    // synopsis is the exception: TMDB's wins when there is one (Apple
    // VODDetailView.mergedPlot).
    val plot = tmdbDetails?.overview?.takeIf { it.isNotBlank() } ?: cachedOverview
        ?: info?.effectivePlot?.takeIf { it.isNotBlank() } ?: movie.plot?.takeIf { it.isNotBlank() }
    val genre = info?.effectiveGenre?.takeIf { it.isNotBlank() } ?: movie.genre?.takeIf { it.isNotBlank() }
        ?: tmdbDetails?.genres
    val cast = info?.effectiveCast?.takeIf { it.isNotBlank() } ?: tmdbDetails?.castTop
    val director = info?.effectiveDirector?.takeIf { it.isNotBlank() } ?: tmdbDetails?.director
    val country = info?.effectiveCountry?.takeIf { it.isNotBlank() }
    // Native Dispatcharr movies get the trailer from provider-info; XC movies
    // carry it on the movie row itself (toMovie copies youtube_trailer).
    val trailerUrl = (info?.effectiveTrailer ?: movie.youtubeTrailer?.takeIf { it.isNotBlank() })
        ?.let { youtubeUrl(it) }
    val tmdbUrl = (info?.tmdbId ?: movie.tmdbId)?.takeIf { it.isNotBlank() }?.let {
        "https://www.themoviedb.org/movie/$it"
    }
    // Foldable (#40): cap only the long synopsis to a readable line length on
    // the unfolded Medium panel; Dp.Unspecified (no-op) on folded phone and TV.
    val readableCap = if (isTv) Dp.Unspecified else rememberViewport().readableMaxWidth

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTv) TV_DETAIL_INSET else 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!plot.isNullOrBlank()) {
            if (isTv) {
                // No maxLines cap on TV: capping silently truncated the back
                // half of longer synopses (Dispatcharr's plots can run
                // 400-800 chars) and there is no tap to expand.
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (trailerUrl != null) {
                    PillButton(
                        icon = Icons.Outlined.PlayCircle,
                        text = "Trailer",
                        onClick = { onOpenUrl("Trailer", trailerUrl) },
                    )
                }
                if (tmdbUrl != null) {
                    PillButton(
                        icon = Icons.Outlined.Info,
                        text = "View on TMDB",
                        onClick = { onOpenUrl("View on TMDB", tmdbUrl) },
                    )
                }
                if (isTv && versionLabel != null) {
                    PillButton(
                        icon = Icons.Outlined.Tune,
                        text = "Version: $versionLabel",
                        onClick = onVersionClick,
                    )
                }
            }
        }
        val row: @Composable (String, String) -> Unit = { label, value ->
            if (isTv) MetaRow(label, value) else PhoneMetaRow(label, value)
        }
        // iOS order: Genre first, then Released (VODDetailView 1305, 1313).
        if (!genre.isNullOrBlank()) row("Genre", genre)
        // v0.26.0 reliably populates release_date. The hero already shows the
        // year, so only surface the full date here when it carries more than a
        // bare year (month/day). Mirrors iOS VODDetailView (count > 4).
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
 * role. Cast leads, directors follow (deduped upstream). Cards open
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
    val edgeInset = if (isTv) TV_DETAIL_INSET else 16.dp
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            text = "Cast & Crew",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            // tvOS .headlineSmall is SEMIbold (Typography.swift).
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = edgeInset),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            // The row clips to its own bounds, so a focused card's scale had
            // nowhere to grow and its name/role were sliced off the bottom
            // (Logan, 2026-08-15). Vertical headroom for the focus scale.
            contentPadding = PaddingValues(
                horizontal = edgeInset,
                // Phone: 12dp matches the iPhone cast strip (VODDetailView 2245).
                vertical = if (isTv) 20.dp else 12.dp,
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
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
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
        // tvOS: name .labelMedium (20 pt -> 10 sp, medium), role .labelSmall
        // (18 pt -> 9 sp), one line each on TV so a two-word name does not
        // wrap inside the 100 dp card.
        Text(
            text = person.name,
            fontSize = if (isTv) 10.sp else 12.sp,
            lineHeight = if (isTv) 12.sp else 14.sp,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        person.role?.takeIf { it.isNotBlank() }?.let { role ->
            Text(
                text = role,
                fontSize = if (isTv) 9.sp else 11.sp,
                lineHeight = if (isTv) 11.sp else 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (isTv) 1 else 2,
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
private fun FloatingBackButton(onClick: () -> Unit) {
    // statusBarsPadding pushes the button below the system status bar / notch
    // so the tap target lands inside the user-actionable safe area. iOS uses
    // the toolbar slot for this; we float over the hero, so the inset has
    // to be applied explicitly.
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

/**
 * Build a YouTube watch URL from whatever shape Dispatcharr stores
 * `youtube_trailer` in. Most providers send just the 11-char video key
 * (`dQw4w9WgXcQ`); a stray full URL or `youtu.be/<key>` shows up
 * occasionally. Mirrors iOS VODDetailView.trailerURL (line 488-498).
 */
internal fun youtubeUrl(raw: String): String? {
    val key = raw.trim()
    if (key.isEmpty()) return null
    if (key.startsWith("http://") || key.startsWith("https://")) return key
    if (key.startsWith("youtu.be/")) return "https://$key"
    return "https://www.youtube.com/watch?v=$key"
}

private fun formatDuration(totalSecs: Int): String {
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/**
 * Phone/tablet "Related" strip: library titles TMDB recommends for the
 * opened one (tvOS "Available Related Titles"). Header matches the Cast &
 * Crew section; tiles are the Movies tab poster cards.
 */
@Composable
internal fun RelatedSection(
    items: List<MediaItem>,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            text = "Related",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = items, key = { it.key }) { related ->
                MediaPosterCard(
                    item = related,
                    onClick = {
                        val uuid = related.movieUuid
                        val id = related.seriesId
                        if (uuid != null) onOpenMovie(uuid) else if (id != null) onOpenSeries(id)
                    },
                    modifier = Modifier.width(110.dp),
                )
            }
        }
    }
}
