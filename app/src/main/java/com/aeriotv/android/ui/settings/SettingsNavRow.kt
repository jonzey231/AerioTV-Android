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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    selected: Boolean = false,
    trailingChevron: Boolean = true,
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
    val iconBox = if (isTv) 24.dp else 34.dp
    val iconGlyph = if (isTv) 15.dp else 18.dp
    val gap = if (isTv) 8.dp else 12.dp
    val padH = if (isTv) 10.dp else 14.dp
    val padV = if (isTv) 7.dp else 14.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .settingsRowCard(focused = focused)
            .then(
                if (selected && !focused) {
                    Modifier.background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(12.dp),
                    )
                } else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = padH, vertical = padV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(iconBox)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(iconGlyph),
            )
        }
        Spacer(Modifier.size(gap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected && !focused) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                maxLines = if (isTv) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    // bodySmall is ~10.8sp effective under the 0.9 TV type
                    // scale; bodyMedium keeps the subtitle readable from the
                    // couch.
                    style = if (isTv) MaterialTheme.typography.bodyMedium
                    else MaterialTheme.typography.bodySmall,
                    color = if (selected && !focused) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    // tvOS truncates here rather than wrapping; matching it is
                    // what keeps the rail row a single fixed height. TOUCH keeps
                    // wrapping - the tablet sidebar deliberately shows Sync's
                    // long subtitle over two lines and must not change.
                    maxLines = if (isTv) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
