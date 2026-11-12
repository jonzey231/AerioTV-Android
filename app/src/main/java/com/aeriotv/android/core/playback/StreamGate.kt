package com.aeriotv.android.core.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.aeriotv.android.core.network.DispatcharrRequestGate
import com.aeriotv.android.core.network.rateLimitedMessage
import com.aeriotv.android.core.network.serverBusyCountdown
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * A stream open refused because the server is rate-limiting this network:
 * either the open never went out (the server's request gate is paused after
 * a 429) or the server just answered with Dispatcharr's API key lockout.
 * [seconds] is how long to wait before the one retry.
 */
class StreamRateLimitedException(
    val seconds: Long,
    val reason: com.aeriotv.android.core.network.RateLimitReason = com.aeriotv.android.core.network.RateLimitReason.Other,
    cause: Throwable? = null,
) : IOException("HTTP 429 ($reason): Dispatcharr rate-limited this network; retry in ${seconds}s", cause),
    com.aeriotv.android.core.network.RateLimitReasonCarrier {
    override val rateLimitReason: com.aeriotv.android.core.network.RateLimitReason get() = reason
}

/**
 * HTTP 429 on stream requests (shared spec 2026-11-10, Apple parity).
 *
 * Dispatcharr answers a stream request with 429 for two different reasons:
 *
 *  - the API key lockout (apps/accounts/authentication.py): every request that
 *    carries a key, streams included, gets `{"detail": "Too many API key
 *    attempts. Try again later. ..."}` with a Retry-After while this network
 *    is locked out. That is the same condition the API calls see, so it feeds
 *    the SAME per-server pause ([DispatcharrRequestGate]) and no stream opens
 *    until the pause ends. The players count down, retry once, and after the
 *    shared retry budget show the rate-limit copy.
 *  - the user's stream limit (apps/proxy/live_proxy and vod_proxy views):
 *    `{"error": "Stream limit exceeded (N concurrent streams allowed)"}`. The
 *    API is fine, so the gate is NOT paused; that stream is not retried and
 *    the server's own text is shown ([DispatcharrConnectionLimit]). Timeshift
 *    answers the same limit with 403 and a plain "Stream limit exceeded".
 *
 * Any other 429 on a stream is treated as the lockout. Keys and the server URL
 * are never logged.
 */
@UnstableApi
object StreamGate {
    /** Text Dispatcharr puts in a stream-limit refusal. */
    const val STREAM_LIMIT_TEXT = "Stream limit exceeded"

    /** Text of Dispatcharr's API key lockout. */
    const val LOCKOUT_TEXT = "Too many API key attempts"

    enum class Kind { Lockout, StreamLimit }

    /** A classified refusal. [seconds]: the wait for a lockout; [message]: the
     *  server's own text for a stream limit; [reason]: what the 429 was for. */
    data class Refusal(
        val kind: Kind,
        val seconds: Long,
        val message: String?,
        val reason: com.aeriotv.android.core.network.RateLimitReason = com.aeriotv.android.core.network.RateLimitReason.Other,
    )

    /**
     * Pure classification of a stream response. Null = not a rate-limit or
     * stream-limit refusal (every other status keeps its existing handling).
     */
    fun classify(code: Int, body: String, retryAfter: String?): Refusal? {
        val text = refusalText(body)
        if ((code == 429 || code == 403) && text != null && text.contains(STREAM_LIMIT_TEXT, ignoreCase = true)) {
            return Refusal(Kind.StreamLimit, 0, text)
        }
        if (code != 429) return null
        return Refusal(
            Kind.Lockout,
            DispatcharrRequestGate.parseRetryAfter(retryAfter),
            null,
            com.aeriotv.android.core.network.rateLimitReasonOf(body),
        )
    }

    /** The server's message: JSON `error`, else JSON `detail`, else a short plain body. */
    internal fun refusalText(body: String): String? {
        Dispatcharr503.errorField(body)?.let { return it }
        jsonField(body, "detail")?.let { return it }
        val flat = body.trim().replace(Regex("\\s+"), " ")
        return flat.takeIf { it.isNotEmpty() && it.length <= 200 && !it.startsWith("<") }
    }

    private fun jsonField(body: String, name: String): String? {
        val key = "\"$name\""
        val at = body.indexOf(key)
        if (at < 0) return null
        val colon = body.indexOf(':', at + key.length)
        val open = if (colon >= 0) body.indexOf('"', colon + 1) else -1
        if (open < 0) return null
        val close = body.indexOf('"', open + 1)
        return if (close > open) body.substring(open + 1, close).trim().takeIf { it.isNotEmpty() } else null
    }

    /**
     * Item 8 and 15: a held Child's streams to the moved address never go out
     * (the Dispatcharr API calls are refused the same way at the request
     * gate). Throws [StreamRouteHeldException] before any network.
     */
    fun refuseHeldRoute(url: String) {
        if (!com.aeriotv.android.core.network.HeldRouteGate.refuses(url)) return
        log("[PROFILE-HOST] stream open refused: the active profile is held at this address")
        throw StreamRouteHeldException()
    }

    /** Seconds left on [url]'s server pause, 0 when streams may open. */
    fun pausedSeconds(url: String): Long = DispatcharrRequestGate.pausedSeconds(DispatcharrRequestGate.serverKey(url))

    /** Throws before any network when [url]'s server is paused after a 429. */
    fun beforeOpen(url: String) {
        val left = pausedSeconds(url)
        if (left > 0) {
            val reason = DispatcharrRequestGate.pausedReason(DispatcharrRequestGate.serverKey(url))
            log("[RATE] stream open skipped: server paused after a 429 ($reason), ${left}s left")
            throw StreamRateLimitedException(left, reason)
        }
    }

    /**
     * A stream answered [code]: a lockout pauses the server (the API calls
     * stop too) and comes back as the exception to throw; a stream limit or
     * anything else returns null and keeps its existing handling. Lockouts are
     * only acted on for Dispatcharr (Direct Connect) playlists.
     */
    fun onResponse(url: String, code: Int, body: String, retryAfter: String?): StreamRateLimitedException? {
        if (code != 429) return null
        if (!DispatcharrConnectionLimit.directConnectActive) return null
        val r = classify(code, body, retryAfter) ?: return null
        if (r.kind != Kind.Lockout) return null
        DispatcharrRequestGate.pause(DispatcharrRequestGate.serverKey(url), r.seconds, r.reason)
        log("[RATE] stream HTTP 429 reason=${r.reason} Retry-After=${retryAfter ?: "none"}; no stream opens for ${r.seconds}s")
        return StreamRateLimitedException(r.seconds, r.reason)
    }

    /** [request] with its key rewritten to [rewrite] (StreamAuth): the new key. A held key never gets here. */
    private fun withKey(request: okhttp3.Request, rewrite: StreamAuth.Rewrite): okhttp3.Request = when (rewrite) {
        StreamAuth.Rewrite.Keep -> request
        StreamAuth.Rewrite.Held -> throw StreamKeyRejectedException()
        is StreamAuth.Rewrite.Replace -> request.newBuilder().apply {
            StreamAuth.authHeaders(rewrite.key).forEach { (k, v) -> header(k, v) }
        }.build()
    }

    /** OkHttp side (live, tiles, Live Rewind fill, Cast ingest, recordings). */
    val interceptor = Interceptor { chain ->
        com.aeriotv.android.core.network.DispatcharrLaunchGate.awaitBlocking()
        val url = chain.request().url.toString()
        refuseHeldRoute(url)
        // Per open: the key this request was built with, as it stands now
        // (replaced by a re-sign-in, or held). A held key stops the open here,
        // before any request goes out: no keyless retry. StreamAuth.
        val builtKey = StreamAuth.keyOf { chain.request().header(it) }
        val rewrite = StreamAuth.rewriteFor(builtKey)
        if (rewrite == StreamAuth.Rewrite.Held) throw StreamKeyRejectedException()
        val request = withKey(chain.request(), rewrite)
        beforeOpen(url)
        var resp: Response = chain.proceed(request)
        if (resp.code == 429) {
            val body = runCatching { resp.peekBody(4_096).string() }.getOrDefault("")
            onResponse(url, resp.code, body, resp.header("Retry-After"))?.let { e ->
                resp.close()
                throw e
            }
        }
        if (resp.code == 401) {
            val sentKey = StreamAuth.keyOf { request.header(it) }
            if (sentKey != null) {
                when (val out = StreamAuth.onUnauthorized(sentKey)) {
                    is StreamAuth.Outcome.Retry -> {
                        resp.close()
                        resp = chain.proceed(withKey(request, StreamAuth.Rewrite.Replace(out.key)))
                    }
                    StreamAuth.Outcome.Held -> {
                        resp.close()
                        throw StreamKeyRejectedException()
                    }
                    StreamAuth.Outcome.NotKey -> Unit
                }
            }
        }
        resp
    }

    /** The rate-limit refusal anywhere in [error]'s cause chain, or null. */
    fun find(error: Throwable?): StreamRateLimitedException? {
        var t = error
        var depth = 0
        while (t != null && depth < 8) {
            if (t is StreamRateLimitedException) return t
            t = t.cause
            depth++
        }
        return null
    }

    /** Media3 load-error policies: never re-GET a rate-limited stream, or one whose key is held, themselves. */
    fun noRetry(error: Throwable?): Boolean = find(error) != null || StreamAuth.find(error) != null

    /** Seconds to wait before the one retry: what is left on the pause, at least 1. */
    fun waitSeconds(url: String?, e: StreamRateLimitedException?): Long {
        val left = url?.let(::pausedSeconds) ?: 0L
        return maxOf(left, e?.seconds ?: 0L, 1L).coerceAtMost(DispatcharrRequestGate.MAX_RETRY_AFTER_S)
    }

    /** The player card while the wait runs (the shared 429 wording). */
    fun countdownNotice(secondsLeft: Long, reason: com.aeriotv.android.core.network.RateLimitReason) =
        DispatcharrConnectionLimit.Notice(
            kind = DispatcharrConnectionLimit.Kind.RATE_LIMITED,
            title = RATE_LIMITED_TITLE,
            message = serverBusyCountdown(secondsLeft, reason),
        )

    /** The player card after the retry budget (the shared 429 give-up wording). */
    fun finalNotice(seconds: Long, reason: com.aeriotv.android.core.network.RateLimitReason) =
        DispatcharrConnectionLimit.Notice(
            kind = DispatcharrConnectionLimit.Kind.RATE_LIMITED,
            title = RATE_LIMITED_TITLE,
            message = rateLimitedMessage(seconds, reason),
        )

    /** No title on the rate-limit card: its one line already names
     *  Dispatcharr and the reason (shared spec, no new copy). */
    const val RATE_LIMITED_TITLE = ""

    /** Automatic retries after a rate-limit wait, shared with the API flows. */
    const val MAX_AUTO_RETRIES = com.aeriotv.android.core.network.RateLimitRetry.MAX_AUTO_RETRIES

    /**
     * Media3 side (DefaultHttpDataSource paths: on demand, recordings, tiles).
     * [headers] are the ones the HTTP factory was built with: the key in them
     * is rewritten per open (a replaced key goes out as the new one through
     * the open's own headers; a held key cannot be taken off a factory's
     * defaults, so that open never goes out).
     */
    class GatedDataSource(private val upstream: DataSource, private val headers: Map<String, String> = emptyMap()) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

        private val builtKey: String? = StreamAuth.keyOf(headers)

        override fun open(dataSpec: DataSpec): Long {
            com.aeriotv.android.core.network.DispatcharrLaunchGate.awaitBlocking()
            val url = dataSpec.uri.toString()
            refuseHeldRoute(url)
            val spec = when (val rewrite = StreamAuth.rewriteFor(builtKey)) {
                StreamAuth.Rewrite.Keep -> dataSpec
                StreamAuth.Rewrite.Held -> throw StreamKeyRejectedException()
                is StreamAuth.Rewrite.Replace -> dataSpec.withAdditionalHeaders(StreamAuth.authHeaders(rewrite.key))
            }
            beforeOpen(url)
            return try {
                upstream.open(spec)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                val retryAfter = e.headerFields.entries
                    .firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
                    ?.value?.firstOrNull()
                val rl = onResponse(url, e.responseCode, String(e.responseBody, Charsets.UTF_8), retryAfter)
                if (rl != null) throw StreamRateLimitedException(rl.seconds, rl.reason, e)
                val sentKey = StreamAuth.keyOf(spec.httpRequestHeaders) ?: builtKey
                if (e.responseCode == 401 && sentKey != null) {
                    when (val out = StreamAuth.onUnauthorized(sentKey)) {
                        is StreamAuth.Outcome.Retry -> {
                            runCatching { upstream.close() }
                            return upstream.open(dataSpec.withAdditionalHeaders(StreamAuth.authHeaders(out.key)))
                        }
                        StreamAuth.Outcome.Held -> throw StreamKeyRejectedException(e)
                        StreamAuth.Outcome.NotKey -> Unit
                    }
                }
                throw e
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
        override fun getUri(): Uri? = upstream.uri
        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
        override fun close() = upstream.close()
    }

    /** Wraps every source [base] makes in [GatedDataSource]; [headers]: the ones [base] was given. */
    class Factory(private val base: DataSource.Factory, private val headers: Map<String, String> = emptyMap()) : DataSource.Factory {
        override fun createDataSource(): DataSource = GatedDataSource(base.createDataSource(), headers)
    }

    /** Load-error policy for the on-demand and tile players. */
    class LoadErrorPolicy(
        private val base: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy =
            androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(),
    ) : androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy by base {
        override fun getRetryDelayMsFor(
            loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo,
        ): Long {
            if (noRetry(loadErrorInfo.exception)) return C.TIME_UNSET
            if (DispatcharrConnectionLimit.parse(loadErrorInfo.exception) != null) return C.TIME_UNSET
            return base.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    const val TAG = "AerioStreamGate"

    /** Log sink (tests replace it; android.util.Log is not available on the JVM). */
    @Volatile var log: (String) -> Unit = { android.util.Log.i(TAG, it) }
}
