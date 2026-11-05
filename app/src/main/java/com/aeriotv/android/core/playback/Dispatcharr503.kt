package com.aeriotv.android.core.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/**
 * Dispatcharr 503 parsing for the live tune path.
 *
 * Every 503 the Dispatcharr TS proxy returns carries a JSON body
 * `{"error": "<reason>"}` (see its proxy views.py). The reasons fall into two
 * operationally different buckets:
 *
 *  - "Channel is stopping, retry shortly" plus a `Retry-After: 1` header. The
 *    server is tearing down the PREVIOUS session for this channel (for example
 *    right after a cast ended). The same URL works again in about a second, so
 *    the only correct move is to wait the advertised interval and retry the
 *    SAME url.
 *  - "No available streams for this channel", "Channel resources unavailable",
 *    or a specific upstream error_reason (often with a `"waited": "Ns"` field).
 *    The server already tried the channel's streams and could not obtain one,
 *    so waiting on this URL is pointless and the client should walk to the next
 *    member stream itself.
 *
 * We deliberately NEVER infer a cause beyond what the server wrote. The older
 * Apple/Android copy guessed "too many connections", which was wrong for the
 * Nothing Phone ESPN HD session (session4.txt 01:48:51): the body said
 * something else entirely. Whatever text the server sends is what the user is
 * shown, verbatim.
 */
@UnstableApi
object Dispatcharr503 {

    /** What the 503 body means for the tune path. */
    enum class Kind {
        /** Previous session is being torn down; retry the same url shortly. */
        STOPPING,

        /** Server could not obtain a stream; walk to another one now. */
        UNAVAILABLE,
    }

    data class Info(
        val kind: Kind,
        /** The server's own text, sentence-cased for display. Never invented. */
        val reason: String,
        /** Retry-After in ms, already clamped; only meaningful for [Kind.STOPPING]. */
        val retryAfterMs: Long,
    )

    /** No usable Retry-After on the response: the ladder below decides alone. */
    const val DEFAULT_RETRY_AFTER_MS = 0L

    /**
     * Backoff ladder for a STOPPING 503, in ms. Before 2026-09-14 the client
     * retried the same url five times at the advertised ~1 s and then entered
     * the failover walk, which on a teardown produced 60 requests in 52 s plus
     * two change_stream POSTs the server answered 504 ("not confirmed by the
     * channel owner"), and landed back on the stream it started from. Waiting
     * is the only thing that helps while the server is stopping, so wait
     * properly: the five steps sum to exactly [STOPPING_BUDGET_MS].
     */
    val STOPPING_BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

    /** Times the same url may be retried for a STOPPING 503 before anything else. */
    const val MAX_STOPPING_RETRIES = 5

    /** Total time a STOPPING 503 may be waited out before the unavailable card
     *  with Retry takes over. 2 + 4 + 8 + 16 + 30 = 60. */
    const val STOPPING_BUDGET_MS = 60_000L

    /** Ceiling on the honored Retry-After. A "Channel is stopping" teardown on a
     *  provider-capped channel really does run for tens of seconds (Streamer
     *  2026-09-14: 52 s), so a larger advertised wait is respected up to the
     *  whole budget rather than clamped down to a hammering interval. */
    const val MAX_RETRY_AFTER_MS = STOPPING_BUDGET_MS

    /**
     * Wait before same-url retry [attempt] (1-based) of a STOPPING 503: the
     * ladder step, or the server's own Retry-After when it asks for longer,
     * trimmed to whatever is left of [STOPPING_BUDGET_MS].
     */
    fun stoppingDelayMs(attempt: Int, retryAfterMs: Long, elapsedMs: Long): Long {
        val step = STOPPING_BACKOFF_MS[
            (attempt - 1).coerceIn(0, STOPPING_BACKOFF_MS.size - 1),
        ]
        val wanted = maxOf(step, retryAfterMs)
        val remaining = (STOPPING_BUDGET_MS - elapsedMs).coerceAtLeast(0L)
        return minOf(wanted, remaining)
    }

    /**
     * Find the 503 inside whatever Media3 handed us (a load error arrives
     * wrapped: UnexpectedLoaderException / PlaybackException / the raw
     * exception), or null when this error is not an HTTP 503.
     */
    fun parse(error: Throwable?): Info? {
        val http = findInvalidResponseCode(error) ?: return null
        if (http.responseCode != 503) return null
        return infoFrom(String(http.responseBody, Charsets.UTF_8), http.headerFields)
    }

    /** The [Info] for a 503 [body] and its response [headers]; the one parse
     *  both the live holder and the Multiview tiles go through. */
    internal fun infoFrom(body: String, headers: Map<String, List<String>>): Info {
        val reason = reasonFrom(body)
        val kind =
            if (reason.contains("stopping", ignoreCase = true)) Kind.STOPPING else Kind.UNAVAILABLE
        return Info(
            kind = kind,
            reason = sentenceCase(reason),
            retryAfterMs = retryAfterMs(headers),
        )
    }

    internal fun findInvalidResponseCode(
        error: Throwable?,
    ): HttpDataSource.InvalidResponseCodeException? {
        var t = error
        var depth = 0
        while (t != null && depth < 6) {
            if (t is HttpDataSource.InvalidResponseCodeException) return t
            t = t.cause
            depth++
        }
        return null
    }

    /**
     * Pull `error` out of the JSON body without a parser dependency (the body is
     * a tiny flat object). Falls back to the trimmed body, then to a neutral
     * phrase; we never substitute a guessed cause.
     */
    private fun reasonFrom(body: String): String {
        errorField(body)?.let { return it }
        val flat = body.trim().replace(Regex("\\s+"), " ")
        return if (flat.isNotEmpty() && flat.length <= 200) flat else "Channel unavailable"
    }

    /** The JSON `error` string of a Dispatcharr error body, or null when the
     *  body carries none. No fallback text: callers that match on the
     *  server's exact wording must not match on a guess. */
    internal fun errorField(body: String): String? {
        val key = "\"error\""
        val at = body.indexOf(key)
        if (at >= 0) {
            val colon = body.indexOf(':', at + key.length)
            val open = if (colon >= 0) body.indexOf('"', colon + 1) else -1
            if (open >= 0) {
                val close = body.indexOf('"', open + 1)
                if (close > open) {
                    val value = body.substring(open + 1, close).trim()
                    if (value.isNotEmpty()) return value
                }
            }
        }
        return null
    }

    /** Sentence case: capitalize the first letter only, leave the rest as the
     *  server wrote it (stream names and codes keep their casing). */
    private fun sentenceCase(text: String): String {
        val trimmed = text.trim().trimEnd('.')
        if (trimmed.isEmpty()) return trimmed
        return trimmed[0].uppercaseChar() + trimmed.substring(1)
    }

    /** Retry-After is seconds in Dispatcharr's responses; clamped both ends. */
    private fun retryAfterMs(headers: Map<String, List<String>>): Long {
        val raw = headers.entries
            .firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
            ?.value?.firstOrNull()
            ?.trim()
        val seconds = raw?.toDoubleOrNull() ?: return DEFAULT_RETRY_AFTER_MS
        val ms = (seconds * 1000.0).toLong()
        return ms.coerceIn(0L, MAX_RETRY_AFTER_MS)
    }
}
