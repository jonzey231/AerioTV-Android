package com.aeriotv.android.feature.miniplayer

import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
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
 *   [art]  Channel                    [play/pause]  [x]
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
    /** Cast glyph for Google Cast, TV glyph for the AerioTV Remote transport. */
    transportIcon: ImageVector = Icons.Filled.Cast,
    /** Hidden while nothing is playing yet (there is nothing to pause). */
    showTransport: Boolean = true,
    stopDescription: String = "Stop casting",
    /** Stats and notes under the status line, one Text each: the
     *  "Receiver: ..." stat, then the two-line transcode note while the
     *  phone is transcoding. Empty for the companion transport. */
    detailLines: List<String> = emptyList(),
    /** Tablets draw the tab pill's chrome behind the row instead. */
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    horizontalPadding: androidx.compose.ui.unit.Dp = 12.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
            .clickable(onClick = onTap)
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                // Keeps its own tile corners; square when the user turns
                // Appearance > Rounded corners off.
                .clip(com.aeriotv.android.core.ui.artworkTileShape(LOGO_TILE_CORNER, model = artUri))
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
            // TWO lines, as on iOS's RemoteSessionCard (Logan 2026-09-27):
            // title, then the accent status. The program lives in the sheet.
            Text(
                text = subtitle
                    ?: if (!deviceName.isNullOrBlank()) "Casting to $deviceName" else "Tap to control",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.textAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detailLines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                // Accent, like the pause glyph beside it (iOS card parity).
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The logo tile's own corner radius. The tile and the art inside it read this
 *  one value, so they cannot drift. */
private val LOGO_TILE_CORNER = 6.dp
