package com.aeriotv.android.core.playback

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import com.aeriotv.android.core.ui.SkipIntervals

/**
 * Media session view of the shared live ExoPlayer whose seek back / forward
 * commands (notification, lock screen, Bluetooth, Android Auto) move by the
 * CURRENT Settings > Skip Intervals values. ExoPlayer fixes its increments at
 * build time, so without this a changed setting would only apply after the
 * holder next rebuilds the player.
 *
 * [isHoldingForBuffer] reports a resume gate or audio-only hold, which drops
 * playWhenReady only to fill the buffer. The session then sees playWhenReady
 * true and STATE_BUFFERING, so Cast senders, the notification, and the system
 * media controls show buffering, not paused. Everything else passes through.
 */
class SkipIntervalsPlayer(
    player: Player,
    private val isHoldingForBuffer: () -> Boolean = { false },
) : ForwardingPlayer(player) {

    override fun getSeekBackIncrement(): Long = SkipIntervals.backMs

    override fun getSeekForwardIncrement(): Long = SkipIntervals.forwardMs

    override fun getPlayWhenReady(): Boolean = super.getPlayWhenReady() || isHoldingForBuffer()

    override fun getPlaybackState(): Int {
        val state = super.getPlaybackState()
        return if (state == Player.STATE_READY && isHoldingForBuffer()) Player.STATE_BUFFERING else state
    }

    override fun seekBack() {
        seekTo((currentPosition - SkipIntervals.backMs).coerceAtLeast(0L))
    }

    override fun seekForward() {
        val target = currentPosition + SkipIntervals.forwardMs
        val dur = duration
        seekTo(if (dur != C.TIME_UNSET) minOf(target, dur) else target)
    }
}
