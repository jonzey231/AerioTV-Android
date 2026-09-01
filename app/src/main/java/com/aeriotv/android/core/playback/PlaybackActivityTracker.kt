package com.aeriotv.android.core.playback

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide count of live ExoPlayer instances, so background maintenance
 * (PlaylistRefreshWorker's channel + EPG refresh) can yield while the user is
 * actually watching something. Motivated by the 2026-08-31 multiview stutter
 * hunt on the Google TV Streamer: with four decoders running the SoC has no
 * spare cores, and any normal-priority download/gunzip/parse starves the
 * MediaCodec loops enough to judder every tile.
 *
 * Incremented when a player is built, decremented on release. Both the main
 * AerioExoPlayerHolder player and each multiview tile player count.
 */
object PlaybackActivityTracker {
    private val active = AtomicInteger(0)

    fun playerCreated() {
        active.incrementAndGet()
    }

    fun playerReleased() {
        // Guard against double-release paths driving the count negative and
        // permanently unblocking the gate.
        active.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    val isPlaybackActive: Boolean
        get() = active.get() > 0
}
