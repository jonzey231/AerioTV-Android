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

    /** Default wait when a STOPPING 503 carries no usable Retry-After. */
    const val DEFAULT_RETRY_AFTER_MS = 1_000L

    /** Ceiling on the honored Retry-After: a live tune must not stall longer. */
    const val MAX_RETRY_AFTER_MS = 3_000L

    /** Times the same url may be retried for a STOPPING 503 before anything else. */
    const val MAX_STOPPING_RETRIES = 5

    /**
     * Find the 503 inside whatever Media3 handed us (a load error arrives
     * wrapped: UnexpectedLoaderException / PlaybackException / the raw
     * exception), or null when this error is not an HTTP 503.
     */
    fun parse(error: Throwable?): Info? {
        val http = findInvalidResponseCode(error) ?: return null
        if (http.responseCode != 503) return null
        val reason = reasonFrom(String(http.responseBody, Charsets.UTF_8))
        val kind =
            if (reason.contains("stopping", ignoreCase = true)) Kind.STOPPING else Kind.UNAVAILABLE
        return Info(
            kind = kind,
            reason = sentenceCase(reason),
            retryAfterMs = retryAfterMs(http.headerFields),
        )
    }

    private fun findInvalidResponseCode(
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
        val flat = body.trim().replace(Regex("\\s+"), " ")
        return if (flat.isNotEmpty() && flat.length <= 200) flat else "Channel unavailable"
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
        return ms.coerceIn(DEFAULT_RETRY_AFTER_MS, MAX_RETRY_AFTER_MS)
    }
}
