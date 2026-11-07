package com.aeriotv.android.core.playback

/**
 * Pure decisions for the live resume gate in [AerioExoPlayerHolder]: whether a
 * buffer underrun may hold playback, and when a running hold must end.
 *
 * Measured 2026-11-07 on the Streamer: three holds in three minutes (13.5 s,
 * 15.5 s, and 6.1 s), one of them a repeat-stall hold armed on a feed running
 * at 0.68x real time. A feed slower than the wall clock never refills the
 * cushion, so a hold on it is just a pause until the fuse blows.
 */
object ResumeGatePolicy {

    /** Feed media time per wall second below which a hold cannot win. */
    const val MEDIA_RATIO_MIN = 0.9

    /** How long a running hold may sit on a starved feed before letting go. */
    const val STARVED_RELEASE_AFTER_MS = 3_000L

    enum class Release { TARGET_REACHED, FEED_STARVED, TIMEOUT }

    /**
     * True when a hold may start. [repeat] is kept in the signature so the
     * repeat-stall path goes through the same rate check as the first stall;
     * it no longer bypasses it. A null [ratio] (not yet measured) may hold.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldArm(aheadMs: Long, targetMs: Long, ratio: Double?, repeat: Boolean): Boolean {
        if (targetMs <= 0L || aheadMs >= targetMs) return false
        if (ratio != null && ratio < MEDIA_RATIO_MIN) return false
        return true
    }

    /** Release reason for a running hold on this tick, or null to keep holding. */
    fun releaseOnTick(
        aheadMs: Long,
        targetMs: Long,
        ratio: Double?,
        heldMs: Long,
        timeoutMs: Long,
    ): Release? = when {
        aheadMs >= targetMs -> Release.TARGET_REACHED
        ratio != null && ratio < MEDIA_RATIO_MIN && heldMs >= STARVED_RELEASE_AFTER_MS -> Release.FEED_STARVED
        heldMs >= timeoutMs -> Release.TIMEOUT
        else -> null
    }

    /** What the chrome and the media session show: the viewer still wants to play during a hold. */
    fun wantsToPlay(playWhenReady: Boolean, gateHolding: Boolean, audioOnlyHolding: Boolean): Boolean =
        playWhenReady || gateHolding || audioOnlyHolding
}
