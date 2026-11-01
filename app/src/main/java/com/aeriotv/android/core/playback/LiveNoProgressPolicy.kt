package com.aeriotv.android.core.playback

/**
 * Cold-start decision for a live tune that has not shown decoded progress.
 *
 * Streamer 2026-11-01 18:20: the AC-3 decoder died with DEAD_OBJECT during a
 * channel switch, Media3 raised no error, and the player sat in BUFFERING
 * while the server delivered 47 MB. The old NO-DATA net read "nothing
 * buffered" as "no bytes" and walked every stream on a healthy channel. Bytes
 * flowing with no decoded progress is a local decoder fault (rebuild the
 * player); only true network silence is a dead stream (walk / unavailable).
 *
 * Pure logic so the decision table is unit tested.
 */
object LiveNoProgressPolicy {
    enum class Verdict { WAIT, REBUILD, WALK }

    /** Bytes since the prime that count as a flowing feed (a few TS packets
     *  of PAT/PMT do not). */
    const val MIN_FLOWING_BYTES = 64L * 1024L

    /** A byte this recent means the feed is still alive. Covers the measured
     *  8 to 9.5 s Dispatcharr proxy burst cadence. */
    const val FLOWING_RECENT_MS = 12_000L

    /** Time after the prime a flowing feed gets to show a decoded sample. */
    const val NO_PROGRESS_BUDGET_MS = 15_000L

    fun isFlowing(bytesInBudget: Long, msSinceLastByte: Long): Boolean =
        bytesInBudget >= MIN_FLOWING_BYTES && msSinceLastByte in 0..FLOWING_RECENT_MS

    /**
     * @param elapsedMs time since the stream was primed.
     * @param silenceBudgetMs the existing no-bytes ceiling before unavailable.
     * @param decodedProgress a frame rendered or the playhead advanced.
     */
    fun decide(
        elapsedMs: Long,
        silenceBudgetMs: Long,
        bytesInBudget: Long,
        msSinceLastByte: Long,
        decodedProgress: Boolean,
    ): Verdict {
        if (decodedProgress) return Verdict.WAIT
        if (isFlowing(bytesInBudget, msSinceLastByte)) {
            return if (elapsedMs >= NO_PROGRESS_BUDGET_MS) Verdict.REBUILD else Verdict.WAIT
        }
        return if (elapsedMs >= silenceBudgetMs) Verdict.WALK else Verdict.WAIT
    }
}
