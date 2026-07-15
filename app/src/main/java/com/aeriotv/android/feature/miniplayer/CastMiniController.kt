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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * "Now Casting" mini controller (GH #33) anchored above the floating tab bar
 * whenever a Cast Connect session is active. It is the re-entry affordance the
 * user asked for: after leaving the player screen there was no way back to the
 * cast remote. Tapping the row re-enters the player, which renders the full
 * [com.aeriotv.android.feature.cast.CastRemoteOverlay] while casting.
 *
 *   ┌───────────────────────────────────────────────────────────────┐
 *   │ [art]  Channel / title             [▶/❚❚]   [×]                │
 *   │        Casting to Living Room                                   │
 *   └───────────────────────────────────────────────────────────────┘
 *
 * Tap the row → open the cast remote.  ▶/❚❚ → play/pause on the TV.  × → stop
 * casting (ends the session and returns playback to the phone).
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
                    imageVector = Icons.Filled.Cast,
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
            Text(
                text = if (!deviceName.isNullOrBlank()) "Casting to $deviceName" else "Tap to control",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onTogglePlayPause) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Stop casting",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
