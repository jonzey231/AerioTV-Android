package com.aeriotv.android.feature.player

import androidx.compose.runtime.Composable
import androidx.media3.exoplayer.ExoPlayer

/**
 * Playback Speed sheet for the VOD, recording, and catch-up player, reusing
 * the live player's [PlaybackSpeedSheet]. Kept out of VODPlayerScreen because
 * that composable sits at the JVM verifier register limit. The speed lives on
 * the single ExoPlayer instance, so it survives version switches and DVR
 * re-prepares (setMediaItem and prepare keep playbackParameters).
 */
@Composable
fun VodPlaybackSpeedSheetHost(exoPlayer: ExoPlayer?, onClose: () -> Unit) {
    val p = exoPlayer ?: return
    PlaybackSpeedSheet(
        currentSpeed = p.readSpeed(),
        onSelect = { speed ->
            p.applySpeed(speed)
            onClose()
        },
        onDismiss = onClose,
    )
}
