package com.aeriotv.android.core.network

import android.util.Log
import com.aeriotv.android.core.debug.LogSanitizer
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsText

/**
 * Ktor plugin on [DispatcharrClient]'s HTTP client that applies
 * [DispatcharrRequestGate] to every request it sends:
 *  - while the server is parked after a 429, fail fast with
 *    [DispatcharrError.RateLimited] and send nothing;
 *  - on a 429 answer, log it (status, Retry-After, Server header, first 200
 *    bytes of the body; never a key or the Authorization header), park the
 *    server, and throw [DispatcharrError.RateLimited] so no caller turns it
 *    into a generic "HTTP 429" transport error.
 *
 * The login and token refresh endpoints are left to [DispatcharrLoginGate]
 * (their own throttle scope, their own Retry-After handling).
 */
internal val DispatcharrGatePlugin = createClientPlugin("DispatcharrGate") {
    on(Send) { request ->
        val built = request.url.build()
        val path = built.encodedPath
        val isTokenEndpoint = path.contains("/api/accounts/token/")
        val server = DispatcharrRequestGate.serverKey(built.toString())
        if (!isTokenEndpoint) {
            val left = DispatcharrRequestGate.pausedSeconds(server)
            if (left > 0) {
                Log.i(TAG, "[RATE] skipped ${request.method.value} $path: server paused after a 429, ${left}s left")
                throw DispatcharrError.RateLimited(left)
            }
        }
        val call = proceed(request)
        if (call.response.status.value == 429 && !isTokenEndpoint) {
            val retryAfterRaw = call.response.headers["Retry-After"]
            val secs = DispatcharrRequestGate.parseRetryAfter(retryAfterRaw)
            val serverHeader = call.response.headers["Server"]
            val body = runCatching { call.response.bodyAsText().take(200) }.getOrDefault("")
            Log.w(
                TAG,
                "[RATE] HTTP 429 for ${request.method.value} $path: Retry-After=${retryAfterRaw ?: "none"} " +
                    "(waiting ${secs}s) Server=${serverHeader ?: "none"} body=" +
                    LogSanitizer.redact(body.replace('\n', ' ')),
            )
            DispatcharrRequestGate.pause(server, secs)
            throw DispatcharrError.RateLimited(secs)
        }
        call
    }
}

private const val TAG = "DispatcharrGate"
