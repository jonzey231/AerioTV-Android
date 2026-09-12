package com.aeriotv.android.feature.ondemand

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import com.aeriotv.android.feature.movies.MediaItem
import com.aeriotv.android.feature.movies.displayTitle
import com.aeriotv.android.feature.movies.formatRating
import com.aeriotv.android.feature.movies.tv.TvPage
import com.aeriotv.android.feature.movies.tv.TvPosterCard
import com.aeriotv.android.ui.tv.tvFocusScale

/**
 * Android TV building blocks for the VOD detail pages, ported from the tvOS
 * VODDetailView at the canon halving rule (every tvOS point is one half dp on
 * the 960x540 dp TV canvas). Nothing here is reached on a phone.
 */

/**
 * Page horizontal margin for the TV detail page. tvOS lays the column out
 * inside the 90 pt tvOS safe area and adds 56 pt of its own
 * (VODDetailView.swift:377), so the content edge sits ~146 pt = 73 dp in.
 * Android expresses the same thing as this app's canon overscan
 * (TvPage.overscan = 80 pt -> 40 dp) plus the tvOS 56 pt -> 28 dp inset, so
 * the detail page and the media page share one left edge.
 */
internal val TV_DETAIL_INSET = TvPage.overscan + 28.dp

/**
 * The hero CARD's margin: the same overscan plus tvOS's own 16 pt -> 8 dp
 * hero padding (VODDetailView.swift:657), matching TvMediaPage's hero.
 */
internal val TV_DETAIL_HERO_INSET = TvPage.overscan + TvPage.heroInset

/**
 * `MoviesHeroButton` (MoviesView.swift:3393-3459): capsule, 60 pt = 30 dp
 * tall, 26 pt = 13 dp horizontal padding, accent fill when primary and the
 * elevated wash otherwise; the focus ring is WHITE on the primary and the
 * theme accent on every secondary, at 3 pt = 1.5 dp, scale 1.04.
 */
@Composable
internal fun TvHeroActionButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val fill = if (primary) colors.primary else colors.onSurface.copy(alpha = 0.12f)
    val ink = if (primary) colors.onPrimary else colors.onSurface
    Row(
        modifier = modifier
            .tvFocusScale(focused, focusedScale = 1.04f)
            .height(30.dp)
            .clip(CircleShape)
            .background(fill)
            .border(
                width = 1.5.dp,
                color = when {
                    !focused -> Color.Transparent
                    primary -> Color.White
                    else -> colors.primary
                },
                shape = CircleShape,
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = ink, modifier = Modifier.size(11.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `TVEpisodeCard` (VODDetailView.swift:2643-2708): a 360 x 203 pt still
 * (180 x 101 dp here) with the bottom fade, the "E{n}" badge, the accent
 * progress bar, the watched check, and the title / meta lines beneath.
 * Long press opens Mark as Watched / Mark as Unwatched.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvEpisodeCard(
    title: String,
    meta: String?,
    episodeNumber: Int?,
    stillUrl: String?,
    progress: WatchProgressEntity?,
    onClick: () -> Unit,
    onToggleWatched: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val guard = rememberTvMenuGuard()
    val watched = progress?.isFinished == true
    val fraction = progress
        ?.takeIf { !it.isFinished && it.durationMs > 0L }
        ?.let { (it.positionMs.toFloat() / it.durationMs.toFloat()).coerceIn(0f, 1f) }
        ?.takeIf { it > 0f }

    Column(
        modifier = modifier
            .width(180.dp)
            .tvFocusScale(focused, focusedScale = 1.08f)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = { guard.arm(); menuOpen = true },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!stillUrl.isNullOrBlank()) {
                AsyncImage(
                    model = stillUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp),
                )
            }
            // tvOS: clear from the middle to black 0.55 at the bottom.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f),
                        ),
                    ),
            )
            episodeNumber?.let { n ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(text = "E$n", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            if (watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
            fraction?.let { pct ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(pct)
                        .height(2.5.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!meta.isNullOrBlank()) {
            Text(
                text = meta,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (menuOpen) {
        TvActionMenuDialog(
            title = title,
            actions = listOf(
                TvMenuAction(if (watched) "Mark as Unwatched" else "Mark as Watched") { onToggleWatched() },
            ),
            guard = guard,
            onDismiss = { menuOpen = false },
        )
    }
}

/**
 * `tvDetailsBlock` (VODDetailView.swift:1149-1185): a "Details" header over a
 * two-column fact grid, uppercase tertiary labels above secondary values.
 */
@Composable
internal fun TvDetailsBlock(
    facts: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    if (facts.isEmpty() && footer == null) return
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = TV_DETAIL_INSET)) {
        Text(
            text = "Details",
            style = MaterialTheme.typography.titleMedium,
            // tvOS .headlineSmall is SEMIbold, not bold (Typography.swift).
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(9.dp))
        facts.chunked(2).forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
                pair.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f).padding(end = 20.dp)) {
                        Text(
                            text = label.uppercase(),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            // tvOS .bodyMedium: 24 pt -> 12 sp, regular, with
                            // the font's own leading.
                            text = value,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        footer?.invoke()
    }
}

/**
 * `tvRelatedSection` (VODDetailView.swift:1049-1081): "Available Related
 * Titles" as a 200 pt = 100 dp poster strip; long press toggles Watchlist,
 * the only watchlist affordance on the detail page.
 */
@Composable
internal fun TvRelatedSection(
    items: List<MediaItem>,
    watchlisted: (MediaItem) -> Boolean,
    onOpen: (MediaItem) -> Unit,
    onToggleWatchlist: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Available Related Titles",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = TV_DETAIL_INSET),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = TV_DETAIL_INSET, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items = items, key = { it.key }) { item ->
                TvPosterCard(
                    title = item.title,
                    year = item.year,
                    posterUrl = item.posterUrl,
                    rating = formatRating(item.rating),
                    onClick = { onOpen(item) },
                    modifier = Modifier.width(100.dp),
                    longPressActions = listOf(
                        TvMenuAction(
                            if (watchlisted(item)) "Remove from Watchlist" else "Add to Watchlist",
                        ) { onToggleWatchlist(item) },
                    ),
                )
            }
        }
    }
}

/**
 * The tvOS hero (VODDetailView.swift:626-685), shared by movies and series:
 * a 620 pt = 310 dp card inset 16 pt = 8 dp horizontally and 40 pt = 20 dp at
 * the top, corner radius 24 pt = 12 dp, with BOTH tvOS gradients over the art
 * (leading-to-trailing five stops, then a top-to-bottom fade) and NO poster.
 * The copy block is title, meta line, a 4-line plot, then the action row.
 */
@Composable
internal fun TvDetailHero(
    artUrl: String?,
    title: String,
    metaParts: List<String>,
    rating: String?,
    plot: String?,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TV_DETAIL_HERO_INSET)
            .padding(top = 20.dp)
            .height(310.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        if (!artUrl.isNullOrBlank()) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        }
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to background,
                    0.12f to background,
                    0.38f to background.copy(alpha = 0.92f),
                    0.7f to background.copy(alpha = 0.35f),
                    1f to background.copy(alpha = 0.05f),
                ),
            ),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to background),
            ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp)
                .width(480.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = title.ifBlank { "Untitled" },
                fontSize = 26.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (metaParts.isNotEmpty() || !rating.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    metaParts.forEachIndexed { index, part ->
                        if (index > 0) {
                            Text(
                                text = "·",
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Text(
                            // tvOS: system(size: 22, weight: .medium)
                            // (VODDetailView.swift:716).
                            text = part,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!rating.isNullOrBlank()) {
                        // tvOS puts a middle dot before the star too
                        // (VODDetailView.swift:707).
                        if (metaParts.isNotEmpty()) {
                            Text(
                                text = "·",
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(9.dp),
                        )
                        Text(
                            text = rating,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (!plot.isNullOrBlank()) {
                Text(
                    text = plot,
                    fontSize = 11.sp,
                    // tvOS .bodySmall keeps the font's own leading; a bare
                    // fontSize leaves Compose on bodyLarge's 24 sp line
                    // height, which read as double-spaced on the Streamer.
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(380.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
        }
    }
}

/**
 * tvOS `VODEpisode.cleanedTitle(showName:)` (VODModels.swift:1137): provider
 * episode titles are usually the show's own name plus markers
 * ("A Knight of the Seven Kingdoms (2026) S01E01"), which is why the Android
 * cards were all showing the SHOW title. The show name, year groups, season /
 * episode markers and leftover separators are stripped; an empty result tells
 * the caller to fall back to TMDB's episode name.
 */
internal fun cleanedEpisodeTitle(raw: String, showName: String): String {
    var t = raw.trim()
    val show = displayTitle(showName, null).trim()
    if (show.isNotEmpty() && t.lowercase().startsWith(show.lowercase())) {
        t = t.substring(show.length)
    }
    episodeTitleNoise.forEach { t = it.replace(t, " ") }
    t = episodeTitleLeadingJunk.replace(t, "")
    t = episodeTitleTrailingJunk.replace(t, "")
    t = episodeTitleGaps.replace(t, " ").trim()
    while (true) {
        val m = episodeTitleTrailingYear.find(t) ?: break
        val head = t.substring(0, m.range.first).trim()
        if (head.isEmpty()) break
        t = head
    }
    return t.trim()
}

private val episodeTitleNoise = listOf(
    Regex("""\((?:19|20)\d{2}\)"""),
    Regex("""\bS\d{1,2}\s*E\d{1,3}\b""", RegexOption.IGNORE_CASE),
    Regex("""\b\d{1,2}x\d{1,3}\b""", RegexOption.IGNORE_CASE),
    Regex("""\bepisode\s*\d{1,3}\b""", RegexOption.IGNORE_CASE),
    Regex("""\bseason\s*\d{1,2}\b""", RegexOption.IGNORE_CASE),
)
private val episodeTitleLeadingJunk = Regex("""^[\s\-:·|)(\[\]]+""")
private val episodeTitleTrailingJunk = Regex("""[\s\-:·|(\[]+$""")
private val episodeTitleGaps = Regex("""\s{2,}""")
private val episodeTitleTrailingYear = Regex("""\s*\((?:19|20)\d{2}\)\s*$""")

/**
 * Genre list for the Details block: providers emit "Animation / Comedy",
 * "Animation|Comedy" or "Animation,Comedy"; tvOS shows one comma-separated
 * line, so every separator is normalized to ", ".
 */
internal fun joinGenres(raw: String): String =
    raw.split(',', '/', '|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(", ")
