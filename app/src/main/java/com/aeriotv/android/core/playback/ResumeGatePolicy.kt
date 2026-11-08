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

    /*
     * Switch window rule. Measured 2026-11-07 on the Streamer: five
     * server-side upstream switches in 80 s. The gate armed inside the switch
     * window with no rate measured yet and released early twice (after 12.9 s
     * at a 4640 ms target that had shrunk from 5414 ms, and at 2015 ms against
     * a 5483 ms target), and each time playback froze again about 1 s later
     * because the next switch dropped the buffer it had released onto. So:
     * no new hold and no target shrink while a switch window is open; one hold
     * to the full learned target when the window closes; and a hold already
     * running when a switch begins is extended until the window closes and
     * the target is met, still bounded by the hard timeout.
     */

    /** Feed media time per wall second below which a hold cannot win. */
    const val MEDIA_RATIO_MIN = 0.9

    /** How long a running hold may sit on a starved feed before letting go. */
    const val STARVED_RELEASE_AFTER_MS = 3_000L

    /**
     * Switch window settle: the window closes this long after the new
     * source's first rendered frame (matches SWITCH_JUMP_GRACE_MS and the
     * Apple switchSettleAfterFirstFrame).
     */
    const val SWITCH_SETTLE_AFTER_FIRST_FRAME_MS = 5_000L

    /** Hard cap after the last switch, so a new source that never renders
     *  cannot hold the window open forever. */
    const val SWITCH_WINDOW_CAP_MS = 30_000L

    /**
     * Upstream switch window, same rule as the Apple ResumeGatePolicy
     * SwitchWindow. Opened by a switch, re-armed by every further switch,
     * closed [SWITCH_SETTLE_AFTER_FIRST_FRAME_MS] after the new source's first
     * rendered frame, or [SWITCH_WINDOW_CAP_MS] after the last switch. The
     * fixed 30 s window it replaces was a guess. Times are elapsedRealtime ms.
     */
    class SwitchWindow {
        var lastSwitchAtMs: Long? = null
            private set
        var firstFrameAtMs: Long? = null
            private set
        /** Media position where the new source starts (buffered end when the
         *  window opened); the first frame must be at or past it, so the old
         *  source's buffer playing out does not count. */
        var boundaryMs: Long? = null
            private set

        /**
         * Records a switch. [resetBoundary] replaces the boundary even on an
         * open window (a re-prepare starts a new timeline). Returns true when
         * it opened a closed window. A switch at or before the last one is stale.
         */
        @Synchronized
        fun noteSwitch(nowMs: Long, boundaryMs: Long?, resetBoundary: Boolean = false): Boolean {
            val last = lastSwitchAtMs
            if (last != null && nowMs <= last) return false
            val wasOpen = isOpen(nowMs)
            if (!wasOpen || this.boundaryMs == null || resetBoundary) this.boundaryMs = boundaryMs
            lastSwitchAtMs = nowMs
            firstFrameAtMs = null
            return !wasOpen
        }

        /**
         * Records a playback tick. Returns true when it is the new source's
         * first rendered frame: position advanced while playing, at or past
         * the boundary, inside the cap.
         */
        @Synchronized
        fun noteTick(nowMs: Long, positionMs: Long, advanced: Boolean): Boolean {
            val last = lastSwitchAtMs ?: return false
            if (firstFrameAtMs != null || !advanced || nowMs < last) return false
            if (nowMs - last >= SWITCH_WINDOW_CAP_MS) return false
            val b = boundaryMs
            if (b != null && positionMs < b) return false
            firstFrameAtMs = nowMs
            return true
        }

        @Synchronized
        fun close() {
            lastSwitchAtMs = null
            firstFrameAtMs = null
            boundaryMs = null
        }

        @Synchronized
        fun isOpen(nowMs: Long): Boolean {
            val last = lastSwitchAtMs ?: return false
            val since = nowMs - last
            if (since < 0 || since >= SWITCH_WINDOW_CAP_MS) return false
            val first = firstFrameAtMs
            if (first != null && nowMs - first >= SWITCH_SETTLE_AFTER_FIRST_FRAME_MS) return false
            return true
        }

        /** Latest time the window can still be open (for logs). */
        @Synchronized
        fun closesByMs(): Long {
            val last = lastSwitchAtMs ?: return 0L
            val cap = last + SWITCH_WINDOW_CAP_MS
            val first = firstFrameAtMs ?: return cap
            return minOf(cap, first + SWITCH_SETTLE_AFTER_FIRST_FRAME_MS)
        }
    }

    enum class Release { TARGET_REACHED, FEED_STARVED, TIMEOUT }

    /**
     * True when a hold may start. [repeat] is kept in the signature so the
     * repeat-stall path goes through the same rate check as the first stall;
     * it no longer bypasses it. A null [ratio] (not yet measured) may hold.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldArm(
        aheadMs: Long,
        targetMs: Long,
        ratio: Double?,
        repeat: Boolean,
        inSwitchWindow: Boolean = false,
    ): Boolean {
        if (inSwitchWindow) return false
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
        inSwitchWindow: Boolean = false,
    ): Release? = when {
        heldMs >= timeoutMs -> Release.TIMEOUT
        inSwitchWindow -> null
        aheadMs >= targetMs -> Release.TARGET_REACHED
        ratio != null && ratio < MEDIA_RATIO_MIN && heldMs >= STARVED_RELEASE_AFTER_MS -> Release.FEED_STARVED
        else -> null
    }

    /**
     * Target a running or deferred hold must clear: it only ratchets up for
     * the life of the hold and across a switch window (a shrinking worst-gap
     * ring released a hold at 4640 ms that had armed at 5414 ms).
     */
    fun effectiveTarget(currentMs: Long, peakMs: Long): Long = maxOf(currentMs, peakMs)

    /** True when a hold deferred by a switch window should arm now. */
    fun shouldArmAfterSwitch(pending: Boolean, inSwitchWindow: Boolean, gateActive: Boolean): Boolean =
        pending && !inSwitchWindow && !gateActive

    /** What the chrome and the media session show: the viewer still wants to play during a hold. */
    fun wantsToPlay(playWhenReady: Boolean, gateHolding: Boolean, audioOnlyHolding: Boolean): Boolean =
        playWhenReady || gateHolding || audioOnlyHolding
}
