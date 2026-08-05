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
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected && !focused) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
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
