package com.aeriotv.android.core.playback

/**
 * What a Multiview tile does with an explained Dispatcharr 503 (Apple parity:
 * TSHLSRemuxer tile 503 handling, commits 6c5b9c2 and 65c0663).
 *
 * The body is parsed by [Dispatcharr503] exactly as the live holder parses it;
 * only the tile's recovery differs, because a tile mirrors Apple's tile:
 *
 *  - "Channel is stopping": the server is still tearing down the previous
 *    session for this channel. Retry the SAME url on a 1, 2, 4, 8 s ladder
 *    (the server's Retry-After, clamped to 1 to 3 s, is the floor) until
 *    [STOPPING_BUDGET_MS] has been waited. When the session draining is the
 *    tile's OWN (it released this channel within [SELF_RELEASE_WINDOW_MS]),
 *    the server has been measured taking 18 s and over 22 s to drain, so the
 *    budget is [SELF_RELEASE_STOPPING_BUDGET_MS] and a spent budget falls to
 *    the standing retry, never the walk: another stream cannot help while
 *    the server tears ours down. A spent budget on someone else's session
 *    walks like an unavailable channel.
 *  - Any other reason: the server already tried the channel's streams, so
 *    walk now (where the account may switch streams) or take the standing
 *    retry (where it may not).
 *
 * Pure logic, unit tested; the tile owns the timers.
 */
object TileLive503Policy {

    sealed class Action {
        /** Retry the same url after [delayMs]; [waitedMs] is the running
         *  total and [budgetMs] the budget it counts against. */
        data class SameUrlRetry(
            val delayMs: Long,
            val attempt: Int,
            val waitedMs: Long,
            val budgetMs: Long,
            val drainingOwn: Boolean,
        ) : Action()

        /** Walk to the next member stream now (change_stream). */
        object Walk : Action()

        /** The slow standing retry ([standingRetryDelayMs]). */
        object StandingRetry : Action()
    }

    val STOPPING_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L)
    const val STOPPING_BUDGET_MS = 20_000L
    const val SELF_RELEASE_STOPPING_BUDGET_MS = 45_000L
    const val SELF_RELEASE_WINDOW_MS = 90_000L
    const val RETRY_AFTER_MIN_MS = 1_000L
    const val RETRY_AFTER_MAX_MS = 3_000L

    /** Delay before the tile restarts its pipeline after a 503 walk step
     *  switched streams (Apple: stop, then start 0.5 s later). */
    const val POST_SWITCH_RESTART_MS = 500L

    /** True when the tile released this channel itself recently enough that
     *  a "Channel is stopping" answer is the tile's own session draining. */
    fun drainingOwn(selfReleasedAtMs: Long, nowMs: Long): Boolean =
        selfReleasedAtMs > 0L && nowMs - selfReleasedAtMs < SELF_RELEASE_WINDOW_MS

    /**
     * Decide for one explained 503. [retries] and [waitedMs] are the tile's
     * same-url stopping counters for this tune (reset on a channel change and
     * on steady playback); the caller adds the returned delay to them.
     * [retryAfterMs] is [Dispatcharr503.Info.retryAfterMs] (0 when absent).
     */
    fun decide(
        info: Dispatcharr503.Info,
        retries: Int,
        waitedMs: Long,
        selfReleasedAtMs: Long,
        nowMs: Long,
    ): Action {
        if (info.kind != Dispatcharr503.Kind.STOPPING) return Action.Walk
        val own = drainingOwn(selfReleasedAtMs, nowMs)
        val budget = if (own) SELF_RELEASE_STOPPING_BUDGET_MS else STOPPING_BUDGET_MS
        val step = STOPPING_BACKOFF_MS[retries.coerceIn(0, STOPPING_BACKOFF_MS.size - 1)]
        val floor = (if (info.retryAfterMs > 0L) info.retryAfterMs else RETRY_AFTER_MIN_MS)
            .coerceIn(RETRY_AFTER_MIN_MS, RETRY_AFTER_MAX_MS)
        val remaining = budget - waitedMs
        if (remaining >= floor) {
            // The last wait is trimmed to what is left of the budget.
            val delay = maxOf(minOf(maxOf(step, floor), remaining), floor)
            return Action.SameUrlRetry(
                delayMs = delay,
                attempt = retries + 1,
                waitedMs = waitedMs + delay,
                budgetMs = budget,
                drainingOwn = own,
            )
        }
        return if (own) Action.StandingRetry else Action.Walk
    }

    /** Standing retry number [attempt] (1-based): 15 s, 30 s, then every 60 s
     *  for as long as the tile shows the channel. */
    fun standingRetryDelayMs(attempt: Int): Long = when {
        attempt <= 1 -> 15_000L
        attempt == 2 -> 30_000L
        else -> 60_000L
    }
}
