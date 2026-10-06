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
// Settings > Appearance > Channel List / program badges are LIVE preferences:
// the user flips a toggle while the Live TV list or the guide is on screen
// (on TV, Settings can sit beside the list). These four MUST be dynamic
// `compositionLocalOf`, never `staticCompositionLocalOf`. A static local's
// reads are not tracked, so nothing invalidates a reader whose own parameters
// did not change -- and the guide's rows (GuideGrid.GridRow) and the list's
// rows live inside a LazyColumn subcomposition, which is only re-invoked when
// its item-content lambda changes. A static local therefore left the rows
// drawing the OLD prefs until a tab switch forced the lazy layout to
// re-subcompose, which is exactly the "leave the tab and come back" bug
// (2026-09-15). A dynamic local reads as state inside each row's own
// recompose scope, so only the rows that read it recompose.
val LocalShowEpgBadges = androidx.compose.runtime.compositionLocalOf { true }

/** Settings > Appearance > Channel List: what the guide rail draws. */
data class GuideRailPrefs(
    val logos: Boolean = true,
    val numbers: Boolean = true,
    val names: Boolean = true,
    /** Settings > Live TV > Logo Size as a multiplier, see [liveTvLogoScale]. */
    val logoScale: Float = 1f,
)

/**
 * Settings > Live TV > Logo Size (Discord request 2026-10-06, identical spec on
 * Apple). The SAME ladder, labels and default as Multiview's Logo Size, read
 * relative to the default: 10 draws today's size, 5 half, 25 two and a half
 * times. It multiplies the "logos grow when numbers or names are hidden"
 * result; the guide rail caps growth at the room its cell has.
 */
val LIVE_TV_LOGO_SIZES = listOf(5, 10, 15, 20, 25)
const val LIVE_TV_LOGO_SIZE_DEFAULT = 10
fun liveTvLogoScale(percent: Int): Float {
    val snapped = LIVE_TV_LOGO_SIZES.minByOrNull { kotlin.math.abs(it - percent) } ?: LIVE_TV_LOGO_SIZE_DEFAULT
    return snapped.toFloat() / LIVE_TV_LOGO_SIZE_DEFAULT
}
val LocalGuideRailPrefs = androidx.compose.runtime.compositionLocalOf { GuideRailPrefs() }

/**
 * True when the XMLTV sub-title would only repeat what the title or the
 * description already says: equal to either, or the description begins with
 * the sub-title in square brackets ("[Der Tote im Heizungsraum] ..."), which
 * is how Schedules Direct's German lineups ship their synopses (Dispatcharr
 * passes episodeTitle150 and description1000 through verbatim; XC clients
 * never see the sub-title field, so only Direct Connect showed both lines).
 */
fun subtitleIsRedundant(sub: String?, title: String?, description: String?): Boolean {
    val s = sub?.trim().orEmpty()
    if (s.isEmpty()) return true
    if (s == title?.trim()) return true
    val d = description?.trim().orEmpty()
    if (d.isEmpty()) return false
    return d == s || d.startsWith("[$s]")
}

/** Settings > Appearance > Channel List > Show Program Subtitles. */
val LocalShowProgramSubtitles = androidx.compose.runtime.compositionLocalOf { true }

/**
 * Labels of badge kinds the user has hidden under the master switch (Logan,
 * 2026-08-19). Provided at the Live TV level; EpgFlagsRow and the program-info
 * badge list filter against it, so every surface obeys the same choice.
 */
val LocalHiddenEpgBadges = androidx.compose.runtime.compositionLocalOf { emptySet<String>() }

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
    val hidden = LocalHiddenEpgBadges.current
    val shown = if (hidden.isEmpty()) flags else flags.filter { it.label !in hidden }
    if (shown.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        shown.forEach { EpgFlagBadge(it, compact = compact) }
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
