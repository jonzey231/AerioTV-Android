package com.aeriotv.android.feature.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity

/**
 * Phone/tablet-only building blocks shared by MovieDetailScreen and
 * SeriesDetailScreen, each copied from the iPhone VODDetailView. The TV
 * screens keep their own private composables; nothing here is reached on a
 * TV form factor.
 */

/**
 * Synopsis clamped to 4 lines (iOS VODDetailView lineLimit(4)) with a small
 * "More" / "Less" toggle so the rest of a long Dispatcharr plot stays
 * reachable. The toggle only renders once the text actually overflows.
 */
@Composable
internal fun ExpandablePlot(plot: String, maxWidth: Dp) {
    var expanded by remember(plot) { mutableStateOf(false) }
    var overflows by remember(plot) { mutableStateOf(false) }
    Column(modifier = Modifier.widthIn(max = maxWidth)) {
        Text(
            text = plot,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            Text(
                text = if (expanded) "Less" else "More",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { expanded = !expanded },
            )
        }
    }
}

/**
 * Label / value row: 60dp label column in tertiary, value in secondary text,
 * both labelSmall and neither accent-tinted (iOS metaRow, VODDetailView 2073).
 */
@Composable
internal fun PhoneMetaRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.width(60.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "Version: {label}" capsule on its own row above the link pills: elevated
 * background, 12/8 padding, stack icon leading, up-down chevron trailing,
 * opening a menu with a check mark on the active copy (iOS versionRow,
 * VODDetailView 1928-1956). onSelect(null) = Auto.
 */
@Composable
internal fun PhoneVersionPill(
    options: List<VodProviderOption>,
    selected: VodProviderOption?,
    onSelect: (VodProviderOption?) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val currentLabel = selected?.label ?: "Auto"
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { menu = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Layers,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Version: $currentLabel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Icon(
                imageVector = Icons.Filled.UnfoldMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            VersionMenuItem(
                label = "Auto (recommended)",
                checked = selected == null,
                onClick = { menu = false; onSelect(null) },
            )
            options.forEach { option ->
                VersionMenuItem(
                    label = option.label,
                    checked = selected?.relationId == option.relationId,
                    onClick = { menu = false; onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun VersionMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Spacer(Modifier.size(18.dp))
            }
        },
        onClick = onClick,
    )
}

/**
 * TMDB provenance note at the end of the info block (iOS tmdbSourceNote,
 * VODDetailView 1362-1414): confirms when artwork or details came from TMDB,
 * and turns an art-less title into a hint to add a key. Renders nothing when
 * no state applies.
 *
 * @param tmdbPosterUsed a TMDB poster is on screen
 * @param tmdbDetailsPresent TMDB filled in blank text fields
 * @param hasProviderArt the server supplied any artwork
 * @param tmdbConfigured opt-in on and a key saved
 * @param lookupDone the poster lookup has run (so a miss is a real miss)
 */
@Composable
internal fun TmdbSourceNote(
    tmdbPosterUsed: Boolean,
    tmdbDetailsPresent: Boolean,
    hasProviderArt: Boolean,
    tmdbConfigured: Boolean,
    lookupDone: Boolean,
) {
    val accent = MaterialTheme.colorScheme.primary
    when {
        tmdbPosterUsed && tmdbDetailsPresent -> TmdbNoteRow(
            icon = Icons.Filled.Verified,
            tint = accent,
            text = "Poster and missing details pulled from TMDB using your API key.",
        )
        tmdbPosterUsed -> TmdbNoteRow(
            icon = Icons.Filled.Verified,
            tint = accent,
            text = "Poster pulled from TMDB using your API key.",
        )
        tmdbDetailsPresent -> TmdbNoteRow(
            icon = Icons.Filled.Verified,
            tint = accent,
            text = "Missing details filled in from TMDB using your API key.",
        )
        !hasProviderArt && !tmdbConfigured -> TmdbNoteRow(
            icon = Icons.Filled.AutoAwesome,
            tint = accent,
            text = "No artwork from your provider. Enter a TMDB API key in Settings > App Behaviors " +
                "to fill it in automatically. Only works when TMDB has a matching title.",
        )
        !hasProviderArt && lookupDone -> TmdbNoteRow(
            icon = Icons.Filled.Search,
            tint = MaterialTheme.colorScheme.tertiary,
            text = "No matching title found on TMDB.",
        )
    }
}

@Composable
private fun TmdbNoteRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String) {
    Row(
        modifier = Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/**
 * Episode watch-progress chrome (iOS TVEpisodeRowButton, VODDetailView
 * 2467-2508, phone sizes): a "Watched" / "Currently Watching" capsule (10dp
 * icon, 11sp text, 8/3 padding) and, for unfinished episodes with a known
 * duration, a 3dp accent bar capped at 240dp plus a percent label.
 */
@Composable
internal fun EpisodeProgressChrome(progress: WatchProgressEntity) {
    val finished = progress.isFinished
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (finished) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val fg = if (finished) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
        Icon(
            imageVector = if (finished) Icons.Filled.CheckCircle else Icons.Filled.PlayCircle,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(10.dp),
        )
        Text(
            text = if (finished) "Watched" else "Currently Watching",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
    if (!finished && progress.durationMs > 0L) {
        val pct = (progress.positionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0f, 1f)
        Spacer(Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = 240.dp)
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {},
            )
            Text(
                text = "${(pct * 100).toInt()}%",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
