package com.aeriotv.android.core.playback

/**
 * Verification for a live stream that ended cleanly (Dispatcharr's
 * "terminate on limit exceeded": the server ends the OLDEST client's stream
 * when a new client takes the user's last slot, and the booted client only
 * sees a clean end of stream).
 *
 * Reconnecting on a clean end is always right: the stream may simply have
 * been restarted. What must never happen is giving up on a guess, so the
 * player keeps reconnecting on an escalating backoff ([backoffMs]) and only
 * STOPS when this verifier can prove the account is out of slots:
 *
 *  - GET /api/accounts/users/me/ gives the account's `stream_limit` (0 = no
 *    limit, so a limit boot is impossible and nothing is ever verified), and
 *  - GET /proxy/ts/status (Dispatcharr admin Stats, apps/proxy/live_proxy)
 *    lists every live channel with its clients, each carrying `user_id` and
 *    `connected_at`, so we can count this account's sessions and see whether
 *    a NEWER one on another client exists.
 *
 * /proxy/ts/status is IsAdmin server-side, so a standard sub-account gets
 * 401/403: unverifiable, backoff only. Same for any transport failure or a
 * server too old to answer. [hook] is installed once by Navigation (the
 * Dispatcharr calls live in PlaylistRepository / DispatcharrClient); when it
 * is absent every caller falls back to backoff alone.
 */
object StreamEndVerifier {

    /** [stopped] is true ONLY for a proven limit boot; [detail] is logged. */
    data class Verdict(val stopped: Boolean, val detail: String)

    /**
     * Installed by Navigation for the life of the app. Takes the channel uuid
     * the caller is playing (null when unknown) and the wall-clock epoch
     * seconds at which our own session started, and answers whether this
     * account is provably at its stream limit with a newer session elsewhere.
     */
    @Volatile
    var hook: (suspend (channelUuid: String?, ourConnectedAtEpochSec: Double) -> Verdict)? = null

    /** Never throws: an unreachable / refusing server is "not verified". */
    suspend fun verify(channelUuid: String?, ourConnectedAtEpochSec: Double): Verdict {
        val h = hook ?: return Verdict(false, "no Dispatcharr session check available")
        return runCatching { h(channelUuid, ourConnectedAtEpochSec) }
            .getOrElse { Verdict(false, "session check failed: ${it.message}") }
    }

    /** The Dispatcharr channel uuid inside a /proxy/ts/stream/<uuid> url. */
    fun channelUuidFromUrl(url: String?): String? {
        val u = url ?: return null
        val marker = "/proxy/ts/stream/"
        val i = u.indexOf(marker)
        if (i < 0) return null
        return u.substring(i + marker.length).substringBefore('?').substringBefore('/')
            .takeIf { it.isNotBlank() }
    }

    /**
     * Wait before the reconnect for clean end number [streak] (1 = the first
     * one, which reconnects immediately): 5 s, 15 s, 30 s, then 60 s for every
     * further end. The streak resets after 60 s of healthy playback, a channel
     * change or Retry.
     */
    fun backoffMs(streak: Int): Long = when {
        streak <= 1 -> 0L
        streak == 2 -> 5_000L
        streak == 3 -> 15_000L
        streak == 4 -> 30_000L
        else -> 60_000L
    }

    /** A clean end this long after the last reconnect starts a fresh streak
     *  (the stream played healthily in between). */
    const val HEALTHY_RESET_MS = 60_000L

    /** Consecutive clean ends whose session delivered (almost) nothing, past
     *  which the reconnects stop with the Stream ended notice instead of
     *  cycling the ladder forever (Apple 2026-11-03 17:42:44 to 17:53:38: 34
     *  closes after 188 B each, the ladder restarting every 2 minutes). */
    const val EMPTY_ENDS_LIMIT = 6

    /** A session below this many bytes counts as empty: time alone is not
     *  healthy playback, so an empty session never resets the streak. */
    const val EMPTY_SESSION_BYTES = 256L * 1024L

    /** The empty-session streak after a clean end whose session delivered
     *  [sessionBytes]: one more when it was empty, back to 0 when it was not. */
    fun emptyRunAfter(previous: Int, sessionBytes: Long): Int =
        if (sessionBytes >= EMPTY_SESSION_BYTES) 0 else previous + 1

    /** True once [emptyRun] empty sessions in a row stop the reconnects with
     *  the Stream ended notice and its Retry. */
    fun stopsReconnects(emptyRun: Int): Boolean = emptyRun >= EMPTY_ENDS_LIMIT
}
