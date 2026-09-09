package com.aeriotv.android.feature.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
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
) {
    val bg = MaterialTheme.colorScheme.background
    var menu by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (!page.artUrl.isNullOrBlank()) {
            AsyncImage(
                model = page.artUrl, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(bg.copy(alpha = 0.05f), bg.copy(alpha = 0.92f))),
            ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
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
                    meta.joinToString(" · "), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val r = formatRating(page.rating)
                if (r.isNotEmpty()) {
                    Text(
                        (if (meta.isEmpty()) "" else " · ") + "★ $r", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                Box {
                    Row(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .combinedClickable(onClick = onPrimary, onLongClick = { if (onRemove != null || onToggleWatchlist != null) menu = true })
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                        Text(if (page.hasProgress) "Resume" else "Play", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    if (onRemove != null || onToggleWatchlist != null) {
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (onToggleWatchlist != null) {
                                DropdownMenuItem(
                                    text = { Text(if (isOnWatchlist) "Remove from Watchlist" else "Add to Watchlist") },
                                    onClick = { menu = false; onToggleWatchlist() },
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
                if (page.hasProgress) HeroIconButton(Icons.Filled.Replay, "Play from Beginning", onPlayFromStart)
                HeroIconButton(Icons.Outlined.Info, "Details", onDetails)
                page.remainingLabel?.let {
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
