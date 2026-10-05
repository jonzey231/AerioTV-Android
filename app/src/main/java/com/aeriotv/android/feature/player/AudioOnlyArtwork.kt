package com.aeriotv.android.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aeriotv.android.ui.theme.LocalAppTheme

/** Below this surface height only the logo (or note) is drawn, no text. */
private val AUDIO_ONLY_TEXT_MIN_HEIGHT = 160.dp

/**
 * What an audio-only (radio) stream shows where the video would be
 * (GH jonzey231/AerioTV#90), the same rule on Apple and Android:
 *  - a dark vertical gradient background (never plain black);
 *  - the channel logo centered, 40 percent of the shorter side in the full
 *    player, 55 percent when [compact] (mini player, PiP, Multiview tile);
 *  - without a logo, a music note in an accent-colored circle of that size;
 *  - the channel name and the label "Audio Only" under it, only when the
 *    surface is at least 160 dp tall.
 * Purely decorative: no focus, no pointer input, so taps, D-pad and the player
 * chrome above it behave exactly as on video.
 */
@Composable
fun AudioOnlyArtwork(
    logoUrl: String,
    name: String,
    compact: Boolean = false,
) {
    val accent = LocalAppTheme.current.accentPrimary
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF1C2230), Color(0xFF07080C))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val shorter = if (maxWidth < maxHeight) maxWidth else maxHeight
        val side = shorter * (if (compact) 0.55f else 0.4f)
        val showText = maxHeight >= AUDIO_ONLY_TEXT_MIN_HEIGHT
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            if (logoUrl.isNotBlank()) {
                coil3.compose.AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(side),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(side)
                        .background(accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(side * 0.5f),
                    )
                }
            }
            if (showText) {
                if (name.isNotBlank()) {
                    Text(
                        text = name,
                        style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Text(
                    text = "Audio Only",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}
