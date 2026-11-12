package com.aeriotv.android.feature.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.aeriotv.android.core.network.RateLimitReason
import com.aeriotv.android.core.network.rateLimitedMessage
import com.aeriotv.android.core.network.serverBusyCountdown
import com.aeriotv.android.core.playback.DispatcharrConnectionLimit
import com.aeriotv.android.core.playback.StreamGate

/**
 * On demand, recordings, and catch-up on the touch player: what a stream 429
 * means for its error card (shared spec 2026-11-10). Kept out of
 * VODPlayerScreen, which sits at the ART verifier's register limit; there is
 * one on-demand player at a time, so this is a single state.
 *
 *  - Rate-limited (API key lockout, or the gate paused after a 429): the card
 *    counts the pause down in the shared 429 wording and retries once when it
 *    ends; after [StreamGate.MAX_AUTO_RETRIES] it shows the give-up line and
 *    waits for Retry. Never the 5 s / 10 s / 20 s / 30 s ladder.
 *  - Stream limit (or every provider profile full): the line, no auto retry.
 */
@OptIn(UnstableApi::class)
internal object VodStreamRefusal {
    @Volatile private var reason: RateLimitReason? = null
    @Volatile private var waitSeconds = 0L
    @Volatile private var retries = 0
    @Volatile private var final = false
    @Volatile private var limited = false

    /** The card line for [error], or null when it is neither refusal (the usual card). */
    fun onError(error: Throwable?, url: String?): String? {
        // The stream's key was refused and held (StreamAuth): the usual card,
        // and no automatic retry (each would be another counted failed-key
        // open). The "Sign-in not accepted" prompt shows.
        com.aeriotv.android.core.playback.StreamAuth.find(error)?.let { held ->
            reason = null
            limited = true
            // A held Child's address (item 15): the line the lists show.
            return if (held is com.aeriotv.android.core.playback.StreamRouteHeldException) com.aeriotv.android.feature.settings.DISPATCHARR_UNREACHABLE else null
        }
        StreamGate.find(error)?.let { rl ->
            limited = false
            reason = rl.reason
            waitSeconds = StreamGate.waitSeconds(url, rl)
            final = retries >= StreamGate.MAX_AUTO_RETRIES
            return if (final) rateLimitedMessage(waitSeconds, rl.reason) else serverBusyCountdown(waitSeconds, rl.reason)
        }
        DispatcharrConnectionLimit.parse(error)?.let { notice ->
            reason = null
            limited = true
            return notice.message
        }
        reason = null
        limited = false
        return null
    }

    /** No automatic retry for this card (stream limit, or the rate-limit budget is spent). */
    val noAutoRetry: Boolean get() = limited || (reason != null && final)

    /** Seconds to count down before the one retry, or null for the usual ladder. */
    fun countdownSeconds(): Long? = if (reason != null && !final) waitSeconds else null

    /** The card line for [secondsLeft] of the countdown. */
    fun countdownText(secondsLeft: Long): String = serverBusyCountdown(secondsLeft, reason ?: RateLimitReason.Other)

    /** The countdown ended: one retry spent. */
    fun noteRetry() {
        retries++
    }

    /** The stream played (or the player closed): a fresh budget next time. */
    fun reset() {
        reason = null
        waitSeconds = 0L
        retries = 0
        final = false
        limited = false
    }

    /** A manual Retry from the card earns the budget again. */
    fun manualRetry() {
        retries = 0
        final = false
    }
}
