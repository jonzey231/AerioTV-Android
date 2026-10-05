package com.aeriotv.android.feature.player

/**
 * Routes a LONG D-pad Left/Right that STARTS with the VOD player chrome
 * hidden into the same scrub path a long Left/Right takes while the chrome
 * is showing (Google TV Streamer report, 2026-09-14).
 *
 * Before this, a hidden-chrome Left/Right only revealed the chrome and parked
 * focus on Play/Pause, so the auto-repeats that follow walked the control row
 * instead of scrubbing: the user had to surface the controls first and press
 * again. A repeat carries no memory of the state the press began in, so the
 * first (non-repeat) KeyDown of every Left/Right arms this latch, and the
 * repeats that follow read it.
 *
 * Lives here rather than as another remembered value in VODPlayerScreen: that
 * composable sits at the JVM verifier register limit. One VOD player is on
 * screen at a time, and every press re-arms the latch from the live chrome
 * state, so a stale value cannot survive into the next press.
 */
internal object VodHiddenChromeScrub {
    private var armed = false

    /** Settings > Player > Skip Without Controls (GH #94), kept hot by
     *  MainActivity. The VOD player (movies, episodes, recordings) never
     *  enters ExoWindowState Fullscreen, so the activity-layer skip that serves
     *  live TV never sees its keys; the VOD handler asks here instead. */
    @Volatile var skipWithoutControls = false

    /** A Custom remote map assigns Left/Right player slots. It wins over Skip
     *  Without Controls, as it does for live TV. */
    @Volatile var customHorizontalMap = false

    private var lastSkipAt = 0L

    /** Same repeat pacing as the live player's hidden-chrome skip. */
    private const val SKIP_REPEAT_MS = 250L

    const val NOT_SKIP = 0
    const val SKIP = 1
    const val SWALLOW = 2

    /**
     * Call for each handled Left/Right KeyDown before [shouldScrub].
     *
     * @return [NOT_SKIP] when the press keeps its normal meaning, [SKIP] when
     *  it should seek by the Skip Back / Skip Forward interval with the chrome
     *  left hidden, [SWALLOW] for a held repeat inside the pacing window.
     */
    fun skipDecision(isRepeat: Boolean, chromeVisible: Boolean, now: Long): Int {
        if (chromeVisible || !skipWithoutControls || customHorizontalMap) return NOT_SKIP
        if (isRepeat && now - lastSkipAt < SKIP_REPEAT_MS) return SWALLOW
        lastSkipAt = now
        return SKIP
    }

    /**
     * Call once per handled Left/Right KeyDown, before the chrome is revealed.
     *
     * @param isRepeat auto-repeat KeyDown (nativeKeyEvent.repeatCount > 0).
     * @param chromeVisible chrome state as the press was delivered.
     * @return true when this KeyDown should scrub instead of moving focus.
     */
    fun shouldScrub(isRepeat: Boolean, chromeVisible: Boolean): Boolean {
        if (!isRepeat) {
            // Press start: short Left/Right keeps today's behavior either way.
            armed = !chromeVisible
            return false
        }
        return armed
    }
}
