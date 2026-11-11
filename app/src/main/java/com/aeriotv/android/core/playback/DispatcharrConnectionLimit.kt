package com.aeriotv.android.core.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Dispatcharr connection-limit refusals, recognized ONLY from the server's
 * exact signals and ONLY for Direct Connect (Dispatcharr API) playlists:
 *
 *  - HTTP 429 whose JSON `error` starts with "Stream limit exceeded": the
 *    user's own concurrent stream limit blocked this request (proxy
 *    live_proxy/views.py, terminate_on_limit_exceeded off or no candidate).
 *  - HTTP 503 whose JSON `error` starts with "All active M3U profiles have
 *    reached maximum connection limits": every provider profile is at its
 *    max_streams.
 *
 * Neither is transient from the client's side, so the app shows the notice
 * with a Retry button and does nothing else: no automatic retry, no failover
 * walk, no Reconnecting spinner. Any other status or body (503 "Channel is
 * stopping", a clean end of stream after a server-side terminate, ...) is
 * NOT a limit signal and keeps its existing handling. When the server ends
 * an existing client to make room it says nothing that client can read, so
 * that case is never guessed at.
 */
@UnstableApi
object DispatcharrConnectionLimit {

    /** [STREAM_ENDED] is not a server refusal: the live stream ended cleanly
     *  again right after its one reconnect (see AerioExoPlayerHolder
     *  onLiveCleanEnd). It reuses the same card and Retry plumbing. */
    enum class Kind {
        USER_STREAM_LIMIT, PROVIDER_LIMIT, STREAM_ENDED,
        /** Not a limit: the server is rate-limiting this network (API key
         *  lockout, [StreamGate]). Same card: a countdown, then the give-up copy. */
        RATE_LIMITED,
        /** Not a limit: the server refused this playlist's API key and the key
         *  is held ([StreamAuth]). Same card; Retry opens nothing with the
         *  held key, so it only plays again once the key is replaced. */
        KEY_REJECTED,
    }

    data class Notice(
        val kind: Kind,
        val title: String,
        val message: String,
    ) {
        /** "Close another stream, then press Retry." belongs under the two limit refusals only. */
        val closeAnotherStreamHint: Boolean
            get() = kind == Kind.USER_STREAM_LIMIT || kind == Kind.PROVIDER_LIMIT
    }

    /** True while the active playlist is a Dispatcharr API (Direct Connect)
     *  source. Published by PlaylistRepository whenever the active playlist
     *  changes; XC / M3U playlists never show the notice. */
    @Volatile
    var directConnectActive: Boolean = false

    /** Shown only once a clean end has been VERIFIED as this account hitting
     *  its stream limit (see StreamEndVerifier); an unverified clean end keeps
     *  reconnecting on a backoff instead. Says nothing about limits, because
     *  what the user can do about it is press Retry. */
    val STREAM_ENDED = Notice(
        kind = Kind.STREAM_ENDED,
        title = "Stream ended",
        message = "This stream was stopped by the server. Press Retry to start it again.",
    )

    /** A stream 401 whose key is held as rejected (Apple LiveStopNotice.keyRejected):
     *  the live stream stops, and the "API key no longer valid" prompt points
     *  at Edit Playlist. */
    val KEY_REJECTED = Notice(
        kind = Kind.KEY_REJECTED,
        title = com.aeriotv.android.core.network.KEY_REJECTED_TITLE,
        message = com.aeriotv.android.core.network.KEY_REJECTED_MESSAGE,
    )

    private const val USER_LIMIT_PREFIX = "Stream limit exceeded"
    private const val PROVIDER_LIMIT_PREFIX =
        "All active M3U profiles have reached maximum connection limits"

    /** The limit notice inside whatever Media3 handed us, or null. */
    fun parse(error: Throwable?): Notice? {
        if (!directConnectActive) return null
        val http = Dispatcharr503.findInvalidResponseCode(error) ?: return null
        return fromResponse(http.responseCode, String(http.responseBody, Charsets.UTF_8))
    }

    /** Same match for a raw OkHttp response (cast ingest, retained fill). */
    fun fromResponse(code: Int, body: String): Notice? {
        if (!directConnectActive) return null
        // Timeshift answers the stream limit with 403 and a plain-text body.
        val reason = Dispatcharr503.errorField(body)
            ?: body.trim().takeIf { code == 403 && it.startsWith(USER_LIMIT_PREFIX, ignoreCase = true) && it.length <= 200 }
            ?: return null
        return when {
            (code == 429 || code == 403) && reason.startsWith(USER_LIMIT_PREFIX, ignoreCase = true) -> Notice(
                kind = Kind.USER_STREAM_LIMIT,
                title = "Too many sessions playing",
                // Shared spec 2026-11-10: the count read from the server's text
                // ("Stream limit exceeded (2 concurrent streams allowed)").
                message = com.aeriotv.android.core.network.streamLimitMessage(reason),
            )
            code == 503 && reason.startsWith(PROVIDER_LIMIT_PREFIX, ignoreCase = true) -> Notice(
                kind = Kind.PROVIDER_LIMIT,
                title = "Server is busy",
                message = "All connections for this channel are in use right now. " +
                    "Try again in a few minutes, or contact your server administrator.",
            )
            else -> null
        }
    }

    /** Load-error policy wrapper: a limit refusal is never retried by Media3
     *  itself; everything else defers to [base]. */
    class LoadErrorPolicy(
        private val base: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
    ) : LoadErrorHandlingPolicy by base {
        override fun getRetryDelayMsFor(
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
        ): Long {
            if (parse(loadErrorInfo.exception) != null) return C.TIME_UNSET
            if (StreamGate.noRetry(loadErrorInfo.exception)) return C.TIME_UNSET
            if (ForceHls.findServerHlsRedirect(loadErrorInfo.exception) != null) return C.TIME_UNSET
            if (LiveRedirect.findUpstreamHls(loadErrorInfo.exception) != null) return C.TIME_UNSET
            return base.getRetryDelayMsFor(loadErrorInfo)
        }
    }
}
