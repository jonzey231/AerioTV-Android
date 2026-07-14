package com.aeriotv.android.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.ProgramInfoTarget

/**
 * Shared EPG program badges (LIVE / NEW / PREMIERE / FINALE / REPEAT) and the
 * season/episode label, rendered in the guide cell, the channel list, and the
 * program-info sheet.
 *
 * These are driven by feed metadata, NOT the wall clock: `LIVE` is the XMLTV
 * `<live/>` / Dispatcharr `is_live` broadcast flag, distinct from the guide's
 * clock-derived "airing now" tint. See the per-source matrix -- Dispatcharr
 * cannot supply REPEAT (no previously-shown field) or a content rating.
 */

// Single source of truth for the badge colours (was a private LIVE_RED
// duplicated across ProgramInfoSheet and RecordProgramSheet).
val EpgLiveRed = Color(0xFFFF4757)
val EpgNewGreen = Color(0xFF27AE60)
val EpgPremierePurple = Color(0xFF9B59B6)
val EpgRepeatGray = Color(0xFF8A8F98)

data class EpgFlag(val label: String, val color: Color)

/**
 * Whether EPG badges (flags + season/episode pill) render. Provided at the Live
 * TV level from the per-device-type "Show program badges" setting; the badge
 * call sites gate on it. Defaults to true so any surface without an explicit
 * provider still shows badges.
 */
val LocalShowEpgBadges = androidx.compose.runtime.staticCompositionLocalOf { true }

/**
 * Ordered badge list for a program, most-salient first. REPEAT is suppressed
 * when NEW is set (a program is one or the other). Empty when nothing applies.
 */
fun epgFlagsOf(
    isNew: Boolean,
    isLiveBroadcast: Boolean,
    isPremiere: Boolean,
    isFinale: Boolean,
    isRepeat: Boolean,
): List<EpgFlag> = buildList {
    if (isLiveBroadcast) add(EpgFlag("LIVE", EpgLiveRed))
    if (isNew) add(EpgFlag("NEW", EpgNewGreen))
    if (isPremiere) add(EpgFlag("PREMIERE", EpgPremierePurple))
    if (isFinale) add(EpgFlag("FINALE", EpgPremierePurple))
    if (isRepeat && !isNew) add(EpgFlag("REPEAT", EpgRepeatGray))
}

fun EPGProgramme.epgFlags(): List<EpgFlag> =
    epgFlagsOf(isNew, isLiveBroadcast, isPremiere, isFinale, isRepeat)

fun ProgramInfoTarget.epgFlags(): List<EpgFlag> =
    epgFlagsOf(isNew, isLiveBroadcast, isPremiere, isFinale, isRepeat)

/** "S3 E5" / "S3" / "E5", or null when neither number is known. */
fun seasonEpisodeLabel(season: Int?, episode: Int?): String? = when {
    season != null && episode != null -> "S$season E$episode"
    season != null -> "S$season"
    episode != null -> "E$episode"
    else -> null
}

fun EPGProgramme.seasonEpisodeLabel(): String? = seasonEpisodeLabel(season, episode)
fun ProgramInfoTarget.seasonEpisodeLabel(): String? = seasonEpisodeLabel(season, episode)

/**
 * A solid-color badge pill. [compact] shrinks it for the space-tight guide
 * grid cells (fixed small font + tighter padding); the default size keeps the
 * theme's TV-scaled labelSmall for the roomier list rows and info sheet.
 */
@Composable
fun EpgFlagBadge(flag: EpgFlag, modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(
        color = flag.color,
        shape = RoundedCornerShape(if (compact) 3.dp else 4.dp),
        modifier = modifier,
    ) {
        Text(
            text = flag.label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = if (compact) 9.sp else TextUnit.Unspecified,
            lineHeight = if (compact) 10.sp else TextUnit.Unspecified,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = if (compact) 3.dp else 5.dp,
                vertical = if (compact) 1.dp else 1.dp,
            ),
        )
    }
}

/** A horizontal run of badges; renders nothing when [flags] is empty. */
@Composable
fun EpgFlagsRow(
    flags: List<EpgFlag>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    spacing: androidx.compose.ui.unit.Dp = if (compact) 3.dp else 4.dp,
) {
    if (flags.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        flags.forEach { EpgFlagBadge(it, compact = compact) }
    }
}

/** A small neutral outlined "S3 E5" pill. Renders nothing when [label] is null. */
@Composable
fun SeasonEpisodePill(label: String?, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (label == null) return
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(if (compact) 3.dp else 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = if (compact) 9.sp else TextUnit.Unspecified,
            lineHeight = if (compact) 10.sp else TextUnit.Unspecified,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = if (compact) 3.dp else 5.dp,
                vertical = 1.dp,
            ),
        )
    }
}
