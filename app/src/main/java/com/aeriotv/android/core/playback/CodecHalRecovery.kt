package com.aeriotv.android.core.playback

/**
 * Recovery rules for a dead vendor codec HAL (Google TV Streamer
 * 2026-11-01: android.hardware.media.c2@1.2-mediatek crashed while a
 * c2.mtk.hevc.decoder was being destroyed, which killed every live decoder in
 * the app with DEAD_OBJECT / ERROR_CODE_DECODING_RESOURCES_RECLAIMED). The new
 * HAL process is up about 300 ms later, so the player waits a settle delay and
 * rebuilds in exactly the mode that was active before the error.
 *
 * Pure logic, no Android types, so the unit tests can drive it.
 */
class CodecHalRecovery(private val clock: () -> Long) {
    enum class Mode(val logName: String) { VIDEO("video"), RADIO("radio") }

    sealed class Decision {
        data class Rebuild(val mode: Mode, val settleMs: Long, val attempt: Int) : Decision()
        data class CapReached(val rebuildsInWindow: Int) : Decision()
    }

    private val rebuildTimes = ArrayDeque<Long>()

    /** Radio when Radio Mode owns the holder (including a Radio Mode start
     *  that was in flight when the HAL died: its flag is set first), video
     *  otherwise. A video tune never picks up the Radio policy. */
    fun modeFor(radioVideoOff: Boolean, radioStartInFlight: Boolean): Mode =
        if (radioVideoOff || radioStartInFlight) Mode.RADIO else Mode.VIDEO

    /** First rebuild in the window settles [FIRST_SETTLE_MS], the second
     *  [SECOND_SETTLE_MS]; past [MAX_REBUILDS] in [WINDOW_MS] the caller hands
     *  over to the existing unavailable path. */
    fun decide(radioVideoOff: Boolean, radioStartInFlight: Boolean): Decision {
        val now = clock()
        while (rebuildTimes.isNotEmpty() && now - rebuildTimes.first() > WINDOW_MS) rebuildTimes.removeFirst()
        if (rebuildTimes.size >= MAX_REBUILDS) return Decision.CapReached(rebuildTimes.size)
        rebuildTimes.addLast(now)
        val attempt = rebuildTimes.size
        val settle = if (attempt <= 1) FIRST_SETTLE_MS else SECOND_SETTLE_MS
        return Decision.Rebuild(modeFor(radioVideoOff, radioStartInFlight), settle, attempt)
    }

    companion object {
        const val FIRST_SETTLE_MS = 400L
        const val SECOND_SETTLE_MS = 1_000L
        const val MAX_REBUILDS = 2
        const val WINDOW_MS = 30_000L

        /** MediaCodec DEAD_OBJECT (-32, logged as 0xffffffe0). */
        const val DEAD_OBJECT = -32
    }
}
