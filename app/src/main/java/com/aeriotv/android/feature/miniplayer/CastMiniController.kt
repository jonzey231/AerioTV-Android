package com.aeriotv.android.feature.miniplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * The cast card's row, anchored above the floating tab bar whenever this phone
 * is driving another screen. ONE design for both transports (Logan 2026-09-12):
 * Google Cast and the AerioTV Remote companion link, which differ only by the
 * transport glyph and the status line. Tapping the row opens
 * [com.aeriotv.android.feature.cast.CastRemoteSheet] over the current page; it
 * never takes the screen over.
 *
 *   [art]  Channel / program          [play/pause]  [x]
 *          Casting to Living Room
 *
 * Tap the row -> open the remote controls sheet.  Play/Pause -> transport on the
 * other screen.  X -> end the session (no local resume).
 */
@Composable
fun CastMiniController(
    title: String,
    deviceName: String?,
    artUri: String?,
    isPlaying: Boolean,
    onTap: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** Overrides the "Casting to <device>" line -- the companion transport reuses
     *  this exact card with "Controlling <device>", and a session with nothing
     *  playing yet reads "Select a Channel". */
    subtitle: String? = null,
    /** Current program on the other screen, shown under the title. */
    programmeTitle: String? = null,
    /** Cast glyph for Google Cast, TV glyph for the AerioTV Remote transport. */
    transportIcon: ImageVector = Icons.Filled.Cast,
    /** Hidden while nothing is playing yet (there is nothing to pause). */
    showTransport: Boolean = true,
    stopDescription: String = "Stop casting",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            if (!artUri.isNullOrBlank()) {
                AsyncImage(
                    model = artUri,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
            } else {
                Icon(
                    imageVector = transportIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "Now casting" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            programmeTitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = subtitle
                    ?: if (!deviceName.isNullOrBlank()) "Casting to $deviceName" else "Tap to control",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showTransport) {
            IconButton(onClick = onTogglePlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stopDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
