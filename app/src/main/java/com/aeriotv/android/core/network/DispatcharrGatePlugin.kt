package com.aeriotv.android.core.network

import android.util.Log
import com.aeriotv.android.core.debug.LogSanitizer
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsText
import io.ktor.util.AttributeKey

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
 *  - a request carrying an API key the server already rejected (and the app
 *    cannot recover) fails fast with [DispatcharrError.KeyRejected] and is
 *    never sent, unless it is an explicit key check ([BypassKeyGate]).
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
        // Built with a key a re-sign-in has since replaced: send the current
        // one (never logged), not a counted failure on a dead key.
        requestApiKey(request.headers["X-API-Key"], request.headers["Authorization"])
            ?.let { old -> DispatcharrRequestGate.replacementFor(old)?.let { old to it } }
            ?.let { (old, new) ->
                val (x, auth) = swappedAuthHeaders(request.headers["X-API-Key"], request.headers["Authorization"], old, new)
                x?.let { request.headers["X-API-Key"] = it }
                auth?.let { request.headers["Authorization"] = it }
                Log.i(TAG, "[AUTH] ${request.method.value} $path: sent with the re-issued key")
            }
        if (!request.attributes.contains(BypassKeyGate)) {
            val key = requestApiKey(request.headers["X-API-Key"], request.headers["Authorization"])
            if (key != null && DispatcharrRequestGate.isKeyRejected(key)) {
                Log.i(TAG, "[AUTH] skipped ${request.method.value} $path: this API key was rejected by the server; waiting for Edit Playlist")
                throw DispatcharrError.KeyRejected()
            }
        }
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

/** Set on an explicit key check (verifyConnection, Edit Playlist, the setup sender) so a rejected key can be tried again on purpose. */
internal val BypassKeyGate = AttributeKey<Boolean>("DispatcharrBypassKeyGate")

/**
 * New values for the auth headers that carry [old]: X-API-Key, and an
 * `ApiKey` or `Bearer` Authorization with the same scheme. Null = leave that
 * header as it is (absent, or carrying something else such as a JWT).
 */
internal fun swappedAuthHeaders(xApiKey: String?, authorization: String?, old: String, new: String): Pair<String?, String?> {
    val o = old.trim()
    val x = xApiKey?.takeIf { it.trim() == o }?.let { new }
    val parts = authorization?.trim()?.split(Regex("\\s+"), limit = 2)
    val auth = if (parts != null && parts.size == 2 && parts[1] == o &&
        (parts[0].equals("ApiKey", true) || parts[0].equals("Bearer", true))
    ) "${parts[0]} $new" else null
    return x to auth
}

/** The API key a request carries: X-API-Key, else `Authorization: ApiKey <k>` or `Bearer <k>`. */
internal fun requestApiKey(xApiKey: String?, authorization: String?): String? {
    xApiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val parts = authorization?.trim()?.split(Regex("\\s+"), limit = 2) ?: return null
    if (parts.size != 2) return null
    return parts[1].takeIf { parts[0].equals("ApiKey", true) || parts[0].equals("Bearer", true) }
}

private const val TAG = "DispatcharrGate"
