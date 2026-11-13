// SettingsNavRow.kt
//
// Settings redesign Phase B1 (SettingsUIRedesign.md B4): the Settings root's
// private SectionNavRow promoted into the shared system, with two upgrades the
// plan calls for:
//
//  1. Full focus treatment. The root row used the weak `groupRowFocus` while
//     every other settings row used `settingsRowCard` (border + scale + wash).
//     On TV that made the root list the only screen whose focus was hard to
//     see. This uses `settingsRowCard`, so the root matches its own subpages.
//  2. A `selected` tonal state distinct from focus, for the tablet sidebar and
//     the TV rail in later phases: selection persists while focus is in the
//     detail pane, exactly like the Apple rail.

package com.aeriotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeriotv.android.ui.tv.tvFocusScale

/**
 * One navigation row in the Settings root list, the tablet sidebar, or the TV
 * rail. [selected] paints the persistent tonal state (the pane this row owns is
 * the one on screen); focus paints on top of it via [settingsRowCard].
 *
 * [trailingChevron] is on for stacked navigation and off in two-pane hosts,
 * where the row selects a pane rather than pushing a screen.
 */
@Composable
fun SettingsNavRow(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Optional trailing value ("1.2.3 (45)", "On"), drawn before the chevron in
     * the same muted style the About info rows use. Plain text only: no new
     * border, ring or highlight is introduced by this slot.
     */
    value: String? = null,
    selected: Boolean = false,
    trailingChevron: Boolean = true,
    /**
     * Rail / sidebar styling: NO per-item card. Both Apple platforms draw
     * these as plain rows on the background and reserve the filled pill for
     * the selected or focused one (iPad sidebar and the tvOS rail, verified
     * against Logan's 2026-08-05 captures). Boxing every item, as
     * `settingsRowCard` does, reads as much heavier chrome than either.
     *
     * The phone ROOT list keeps its cards - those match iOS's grouped
     * insetGrouped rows and are correct there.
     */
    flat: Boolean = false,
    /**
     * Glyph color override. Apple draws a few rail icons in a fixed color
     * (DVR's red record.circle on tvOS) rather than the accent; null keeps
     * the accent.
     */
    iconTint: Color? = null,
    /**
     * TV Settings rail (Apple 08c1be8): rows at least this tall with the
     * text centered and Apple's 9 dp side padding, so the rail's pitch is
     * exact. Null keeps the padded height.
     */
    railMinHeight: androidx.compose.ui.unit.Dp? = null,
    /** The rail glyph's box for this row (sp, follows Text Size); null keeps [TvSettingsMetrics.railIconGlyph]. */
    railIconGlyph: androidx.compose.ui.unit.TextUnit? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val isTv = rememberIsTvDevice()
    // 10-foot metrics. Measured against tvOS on the SAME 1080p panel
    // (2026-08-05): its rail row is 5.3% of screen height and it shows 9 items;
    // ours was 19.4% and showed 5. Two causes, both fixed here.
    //
    // 1. tvOS TRUNCATES the subtitle to one line; we wrapped to two, so every
    //    row grew by a whole line. maxLines below matches tvOS.
    // 2. The icon box and padding are phone touch-target values. Android TV
    //    gets a 960x540dp canvas where tvOS gets 1920x1080pt, so a 34dp icon is
    //    3.5% of the width against tvOS's 34pt at 1.8% - literally double.
    // TV values come from TvSettingsMetrics, which is Apple's tvOS ladder
    // halved for the 960x540dp canvas - see that file for why halving is the
    // correct conversion. Touch keeps the phone/tablet values.
    val iconBox = if (isTv) TvSettingsMetrics.railIconWidth else SettingsCardMetrics.iconTile
    // The Settings rail's glyph is sp-sized, so it follows Text Size as
    // Apple's scaledFont rail icon does.
    val railGlyph = with(androidx.compose.ui.platform.LocalDensity.current) { (railIconGlyph ?: TvSettingsMetrics.railIconGlyph).toDp() }
    val iconGlyph = when {
        railMinHeight != null -> railGlyph
        isTv -> TvSettingsMetrics.navIconGlyph
        else -> SettingsCardMetrics.iconGlyph
    }
    val gap = if (isTv) TvSettingsMetrics.railIconGap else 12.dp
    val padH = if (railMinHeight != null) TvSettingsMetrics.railRowPaddingH else if (isTv) 10.dp else 16.dp
    val padV = if (railMinHeight != null) 0.dp else if (isTv) 2.dp else 12.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (flat) {
                    // Plain row; fill only for focus (D-pad affordance) or
                    // selection, and a border only on focus.
                    // The TV Settings rail (railMinHeight) is Apple's
                    // TVRailRowStyle: no focus scale (a focused row keeps its
                    // siblings' frame), a 7 dp corner (tvOS 14), the accent
                    // wash and a full accent ring on focus, and the card fill
                    // for the selected row.
                    val rail = railMinHeight != null
                    val shape = RoundedCornerShape(if (rail) 7.dp else 12.dp)
                    Modifier
                        .then(if (rail) Modifier else Modifier.tvFocusScale(focused, focusedScale = 1.02f))
                        .clip(shape)
                        .background(
                            when {
                                focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                selected && rail -> settingsCardFill()
                                // Phase 3b: selection is a quiet accent wash,
                                // not Material's off-palette secondaryContainer.
                                selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else -> Color.Transparent
                            },
                        )
                        .then(
                            if (focused) {
                                Modifier.border(
                                    2.dp,
                                    if (rail) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                                    shape,
                                )
                            } else Modifier,
                        )
                } else {
                    Modifier
                        .settingsRowCard(
                            focused = focused,
                            // Apple insets the hairline to where the TITLE
                            // starts, i.e. past the leading icon tile.
                            dividerInset = if (isTv) SettingsCardMetrics.dividerInset
                            else SettingsCardMetrics.iconRowDividerInset,
                        )
                        .then(
                            if (selected && !focused) {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                    RoundedCornerShape(SettingsCardMetrics.rowCorner),
                                )
                            } else Modifier,
                        )
                },
            )
            .clickable(onClick = onClick)
            .then(if (railMinHeight != null) Modifier.heightIn(min = railMinHeight) else Modifier)
            .padding(horizontal = padH, vertical = padV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(iconBox)
                .then(
                    // tvOS's rail icon is a bare tinted glyph; the filled tile
                    // is a touch-surface affordance and reads as extra chrome
                    // at 10 feet. Phone and tablet keep the tile.
                    if (isTv) {
                        Modifier
                    } else {
                        // Phase 3b: Apple's 40dp rounded tile is a DIM accent
                        // fill with the glyph in the accent itself, not a
                        // solid bright chip (Logan's restraint rule).
                        Modifier
                            .clip(RoundedCornerShape(SettingsCardMetrics.iconTileCorner))
                            .background((iconTint ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.2f))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint ?: MaterialTheme.colorScheme.primary,
                // A rail glyph may be wider than the 17 dp column (Material
                // pads its glyphs inside the box more than SF Symbols do), so
                // it is drawn at its own size, centered, without moving the text.
                modifier = if (railMinHeight != null) Modifier.requiredSize(iconGlyph) else Modifier.size(iconGlyph),
            )
        }
        Spacer(Modifier.size(gap))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                if (isTv) TvSettingsMetrics.railTextSpacing else 0.dp,
            ),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.let {
                    if (isTv) {
                        it.copy(
                            fontSize = TvSettingsMetrics.railTitleSize,
                            lineHeight = TvSettingsMetrics.railTitleLineHeight,
                        )
                    } else it
                },
                color = MaterialTheme.colorScheme.onBackground,
                // Apple's SettingsRow title is bodyMedium, a REGULAR weight;
                // the tvOS rail keeps its medium title.
                fontWeight = if (isTv) FontWeight.Medium else FontWeight.Normal,
                maxLines = if (isTv) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    // bodySmall is ~10.8sp effective under the 0.9 TV type
                    // scale; bodyMedium keeps the subtitle readable from the
                    // couch.
                    style = if (isTv) {
                        MaterialTheme.typography.bodyMedium.copy(
                            fontSize = TvSettingsMetrics.railSubtitleSize,
                            lineHeight = TvSettingsMetrics.railSubtitleLineHeight,
                        )
                    } else {
                        MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.sp)
                    },
                    color = if (isTv) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.secondary,
                    // tvOS truncates here rather than wrapping; matching it is
                    // what keeps the rail row a single fixed height. TOUCH keeps
                    // wrapping - the tablet sidebar deliberately shows Sync's
                    // long subtitle over two lines and must not change.
                    maxLines = if (isTv) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.size(10.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailingChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = settingsDimTint(),
            )
        }
    }
}
