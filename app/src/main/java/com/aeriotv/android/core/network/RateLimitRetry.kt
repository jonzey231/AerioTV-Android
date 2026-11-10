package com.aeriotv.android.core.network

import kotlinx.coroutines.delay

/**
 * Interactive flows (device setup receiver, Add Playlist, connection test)
 * when the server answers 429: show "The server is busy. Trying again in N
 * seconds." with a live countdown, try again automatically up to
 * [MAX_AUTO_RETRIES] times, then fail with copy that names the rate limit
 * ([rateLimitedMessage]) instead of "Could not connect: HTTP 429".
 */
object RateLimitRetry {
    const val MAX_AUTO_RETRIES = 2

    /** Test hook: how one second of the countdown passes. */
    @Volatile var tick: suspend () -> Unit = { delay(1_000) }

    /**
     * Runs [attempt]; on a 429 failure (see [rateLimitSeconds]) counts down
     * through [onCountdown] (N, N-1, ..., 1), then runs it again. [onRetry]
     * fires right before each new attempt so the caller can put its normal
     * status line back. The last failure is rewritten to the rate-limit copy.
     */
    suspend fun <T> run(
        onCountdown: (secondsLeft: Long) -> Unit,
        onRetry: () -> Unit = {},
        attempt: suspend () -> Result<T>,
    ): Result<T> {
        var retries = 0
        while (true) {
            val r = attempt()
            val wait = r.exceptionOrNull()?.rateLimitSeconds() ?: return r
            if (retries >= MAX_AUTO_RETRIES) {
                return Result.failure(RateLimitedFinal(wait, r.exceptionOrNull()))
            }
            retries++
            var left = wait.coerceIn(1, DispatcharrRequestGate.MAX_RETRY_AFTER_S)
            while (left > 0) {
                onCountdown(left)
                tick()
                left--
            }
            onRetry()
        }
    }

    /** The give-up failure: its message is the user-facing rate-limit copy. */
    class RateLimitedFinal(val seconds: Long, cause: Throwable?) :
        Exception(rateLimitedMessage(seconds), cause)
}
