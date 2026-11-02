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
    enum class Kind { USER_STREAM_LIMIT, PROVIDER_LIMIT, STREAM_ENDED }

    data class Notice(
        val kind: Kind,
        val title: String,
        val message: String,
    )

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
        val reason = Dispatcharr503.errorField(body) ?: return null
        return when {
            code == 429 && reason.startsWith(USER_LIMIT_PREFIX, ignoreCase = true) -> Notice(
                kind = Kind.USER_STREAM_LIMIT,
                title = "Too many sessions playing",
                message = userLimitMessage(reason),
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

    private fun userLimitMessage(reason: String): String {
        val count = Regex("""\((\d+)""").find(reason)?.groupValues?.get(1)?.toIntOrNull()
        val devices = when (count) {
            null -> "a limited number of sessions"
            1 -> "1 session"
            else -> "$count sessions"
        }
        return "Your account can play $devices at once, and they are all in use. " +
            "Stop something that is playing, or contact your server administrator."
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
            if (ForceHls.findServerHlsRedirect(loadErrorInfo.exception) != null) return C.TIME_UNSET
            if (LiveRedirect.findUpstreamHls(loadErrorInfo.exception) != null) return C.TIME_UNSET
            return base.getRetryDelayMsFor(loadErrorInfo)
        }
    }
}
