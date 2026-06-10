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
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
import com.aeriotv.android.ui.tv.tvFocusScale
import kotlinx.coroutines.delay

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    movieUuid: String,
    onBack: () -> Unit,
    onPlay: (DispatcharrVODMovie) -> Unit,
    viewModel: OnDemandViewModel = hiltViewModel(),
    watchVm: WatchProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val movie = viewModel.movieByUuid(movieUuid)
    val context = LocalContext.current
    val recent by watchVm.observeRecent(50).collectAsStateWithLifecycle(initialValue = emptyList())
    val progress = recent.firstOrNull { it.videoId == movieUuid }
    val hasResume = progress != null && progress.positionMs > 0L &&
        (progress.durationMs <= 0L || progress.positionMs < progress.durationMs - 5 * 60_000L)
    val info = movie?.id?.let { state.movieProviderInfo[it] }

    LaunchedEffect(movie?.id) {
        movie?.id?.let { viewModel.loadMovieProviderInfo(it) }
    }

    // TMDB poster fallback (opt-in). Resolves ONLY when the server supplied no
    // artwork (no logo, no provider poster/backdrop) and provider-info has
    // settled, so it never overrides a real poster or hits TMDB needlessly.
    var tmdbPosterUrl by remember(movie?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(movie?.id, info, state.movieProviderInfoLoading) {
        val m = movie ?: return@LaunchedEffect
        val hasServerArt = !m.logo?.url.isNullOrBlank() ||
            !info?.posterUrl.isNullOrBlank() || !info?.backdropUrl.isNullOrBlank()
        val infoSettled = m.id == null || state.movieProviderInfo.containsKey(m.id) ||
            !state.movieProviderInfoLoading.contains(m.id)
        if (!hasServerArt && tmdbPosterUrl == null && infoSettled) {
            tmdbPosterUrl = viewModel.resolveTmdbPoster(
                tmdbId = info?.tmdbId ?: m.tmdbId,
                title = m.displayName,
                isMovie = true,
            )
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

    BackHandler(enabled = true) { onBack() }
    val isTv = rememberLiveTvFormFactor().isTv

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
                Text(
                    text = "Movie not found",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item {
                    HeroSection(
                        movie = movie,
                        info = info,
                        tmdbPosterUrl = tmdbPosterUrl,
                        tmdbDetails = tmdbDetails,
                        hasResume = hasResume,
                        isTv = isTv,
                        onPlay = { onPlay(movie) },
                    )
                }
                item {
                    InfoSection(
                        movie = movie,
                        info = info,
                        tmdbDetails = tmdbDetails,
                        isTv = isTv,
                        castPhotosVisible = castCrewPeople.isNotEmpty(),
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
                    item {
                        CastCrewSection(
                            people = castCrewPeople,
                            isTv = isTv,
                            profileUrl = viewModel::tmdbProfileImageUrl,
                            onPersonClick = { bioPerson = it },
                        )
                    }
                }
            }
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
    hasResume: Boolean,
    isTv: Boolean,
    onPlay: () -> Unit,
) {
    val heroUrl = info?.backdropUrl ?: movie.logo?.url ?: tmdbPosterUrl
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
            // 16:11 of the 960dp-wide TV canvas is 660dp, taller than the
            // whole 540dp screen: the title block and Play CTA sat below the
            // fold, leaving nothing but raw artwork visible. A fixed 300dp
            // hero (~56% of the canvas) keeps them on screen. Phones keep
            // the aspect-ratio hero (16:11 of 411dp is only ~282dp).
            .then(if (isTv) Modifier.height(300.dp) else Modifier.aspectRatio(16f / 11f)),
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
                        startY = 0f,
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
                    modifier = Modifier.focusRequester(playFocus),
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
    isTv: Boolean,
    castPhotosVisible: Boolean,
    onOpenUrl: (label: String, url: String) -> Unit,
) {
    // Server-provided values always win; TMDB backfills only the holes.
    val plot = info?.effectivePlot?.takeIf { it.isNotBlank() } ?: movie.plot?.takeIf { it.isNotBlank() }
        ?: tmdbDetails?.overview
    val genre = info?.effectiveGenre?.takeIf { it.isNotBlank() } ?: movie.genre?.takeIf { it.isNotBlank() }
        ?: tmdbDetails?.genres
    val cast = info?.effectiveCast?.takeIf { it.isNotBlank() } ?: tmdbDetails?.castTop
    val director = info?.effectiveDirector?.takeIf { it.isNotBlank() } ?: tmdbDetails?.director
    val country = info?.effectiveCountry?.takeIf { it.isNotBlank() }
    val trailerUrl = info?.effectiveTrailer?.let { youtubeUrl(it) }
    val tmdbUrl = (info?.tmdbId ?: movie.tmdbId)?.takeIf { it.isNotBlank() }?.let {
        "https://www.themoviedb.org/movie/$it"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!plot.isNullOrBlank()) {
            // No maxLines cap -- iOS VODDetailView.swift:369 lets the plot
            // wrap freely. Capping at 6 silently truncated the back half of
            // longer synopses (Dispatcharr's plots can run 400-800 chars).
            Text(
                text = plot,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailerUrl != null || tmdbUrl != null) {
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
