package com.aeriotv.android.feature.movies

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.Info
import com.aeriotv.android.ui.scale.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/** One Continue Watching page (Apple parity: MoviesHeroPage). */
data class MediaHeroPage(
    val key: String,
    val title: String,
    val artUrl: String?,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val durationSecs: Int?,
    val genre: String?,
    val rating: String?,
    val positionMs: Long,
    val durationMs: Long,
    val item: MediaItem?,
    val tmdbId: String? = null,
    val isMovie: Boolean = true,
    /** Plot for the TV hero (tvOS shows three lines under the meta row). */
    val plot: String? = null,
) {
    val hasProgress: Boolean get() = positionMs > 0L
    val remainingLabel: String? get() {
        if (durationMs <= 0L || positionMs <= 0L) return null
        val mins = ((durationMs - positionMs) / 60_000L).coerceAtLeast(0L)
        return if (mins >= 60) "${mins / 60} h ${mins % 60} min left" else "$mins min left"
    }
}

/**
 * Hero card (Apple parity: MoviesHero on iPhone): 220 dp, 16 dp corners, art
 * under a top-to-bottom background gradient, title 28 sp bold on two lines,
 * a 13 sp meta line (year, S/E, duration, genre, rating), then Resume or
 * Play as a filled capsule, icon-only Play from Beginning and Details, and
 * the time-left label. Long press on the primary button offers removal from
 * Continue Watching.
 */
@Composable
fun MediaHeroCard(
    page: MediaHeroPage,
    onPrimary: () -> Unit,
    onPlayFromStart: () -> Unit,
    onDetails: () -> Unit,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Long-press menu: Add to / Remove from Watchlist (Apple parity). */
    isOnWatchlist: Boolean = false,
    onToggleWatchlist: (() -> Unit)? = null,
    removeLabel: String = "Remove from Continue Watching",
    /** Hide / Unhide the title (Logan 2026-09-14); null leaves the row out. */
    isHidden: Boolean = false,
    onToggleHidden: (() -> Unit)? = null,
) {
    val bg = MaterialTheme.colorScheme.background
    var menu by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            // Tapping the artwork opens Details, same as the Info button
            // (Logan 2026-11-03, Apple parity). A tap gesture, not
            // clickable, so the card adds no D-pad focus stop on TV; the
            // buttons below consume their own taps first.
            .pointerInput(onDetails) { detectTapGestures(onTap = { onDetails() }) },
    ) {
        // A real landscape backdrop fills the wide slot with a center crop. A
        // portrait poster must NOT be cropped into it (Logan 2026-09-14): it
        // takes a poster-shaped slot at the hero's full height on the trailing
        // edge and the copy sits beside it, the Live TV banner treatment
        // (d40f9ab5). artAspect is 0 until the image lands.
        var artAspect by remember(page.artUrl) { mutableStateOf(0f) }
        val artPortrait = artAspect > 0f &&
            artAspect < com.aeriotv.android.feature.livetv.grid.PORTRAIT_ART_MAX_ASPECT
        val posterWidth = 220.dp * artAspect
        if (!page.artUrl.isNullOrBlank()) {
            AsyncImage(
                model = page.artUrl, contentDescription = null,
                contentScale = if (artPortrait) ContentScale.Fit else ContentScale.Crop,
                modifier = if (artPortrait) {
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(posterWidth)
                } else {
                    Modifier.fillMaxSize()
                },
                onSuccess = { state ->
                    val w = state.result.image.width.toFloat()
                    val h = state.result.image.height.toFloat()
                    if (w > 0f && h > 0f) artAspect = w / h
                },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                // The poster slot keeps its own width clear of the scrim.
                .padding(end = if (artPortrait) posterWidth else 0.dp)
                .background(
                    Brush.verticalGradient(listOf(bg.copy(alpha = 0.05f), bg.copy(alpha = 0.92f))),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .padding(end = if (artPortrait) posterWidth else 0.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                page.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp,
                color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                page.year?.let { add(it.toString()) }
                if ((page.season ?: 0) > 0) add("S${page.season} E${page.episode ?: 0}")
                page.durationSecs?.takeIf { it > 0 }?.let { s -> add(if (s >= 3600) "${s / 3600} h ${(s % 3600) / 60} min" else "${s / 60} min") }
                page.genre?.split(',', '/', '|')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    meta.joinToString(" · "), fontSize = 13.sp.subtext(), fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val r = formatRating(page.rating)
                if (r.isNotEmpty()) {
                    Text(
                        (if (meta.isEmpty()) "" else " · ") + "★ $r", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.textAccent, maxLines = 1,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onPrimary)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    Text(if (page.hasProgress) "Resume" else "Play", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
                }
                if (page.hasProgress) HeroIconButton(Icons.Filled.Replay, "Play from Beginning", onPlayFromStart)
                HeroIconButton(Icons.Outlined.Info, "Details", onDetails)
                // The hero menu is the right-most options circle, not a long
                // press on Resume (Logan 2026-09-10, all platforms).
                if (onRemove != null || onToggleWatchlist != null || onToggleHidden != null) Box {
                    HeroIconButton(Icons.Filled.MoreHoriz, "Options") { menu = true }
                    run {
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (onToggleWatchlist != null) {
                                DropdownMenuItem(
                                    text = { Text(if (isOnWatchlist) "Remove from Watchlist" else "Add to Watchlist") },
                                    onClick = { menu = false; onToggleWatchlist() },
                                )
                            }
                            if (onToggleHidden != null) {
                                DropdownMenuItem(
                                    text = { Text(if (isHidden) "Unhide" else "Hide") },
                                    onClick = { menu = false; onToggleHidden() },
                                )
                            }
                            if (onRemove != null) {
                                DropdownMenuItem(
                                    text = { Text(removeLabel, color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; onRemove() },
                                )
                            }
                        }
                    }
                }
                page.remainingLabel?.let {
                    Text(it, fontSize = 11.sp.subtext(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun HeroIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
    }
}
