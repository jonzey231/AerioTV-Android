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
/**
 * Items 8 and 15: a held Child's request to its playlist's moved address is
 * never sent; its lists fail with the existing line "Could not reach your
 * Dispatcharr server", the same line its streams stop with
 * (StreamRouteHeldException). Null = send it. [bypass] is the same-server
 * check and the held sign-in form ([BypassChildSeal]).
 */
internal fun heldRouteRefusal(url: String, bypass: Boolean): DispatcharrError.Transport? =
    if (!bypass && com.aeriotv.android.core.profiles.ProfileScope.isSealedRoute(url)) {
        DispatcharrError.Transport(com.aeriotv.android.feature.settings.DISPATCHARR_UNREACHABLE)
    } else null

internal val DispatcharrGatePlugin = createClientPlugin("DispatcharrGate") {
    on(Send) { request ->
        // Launch: the Drive credentials pull goes first (bounded wait).
        DispatcharrLaunchGate.await()
        val built = request.url.build()
        val path = built.encodedPath
        val isTokenEndpoint = path.contains("/api/accounts/token/")
        val server = DispatcharrRequestGate.serverKey(built.toString())
        // A Child held after its playlist's server address changed stays
        // active and sends nothing there until the hold lifts (item 8). Only
        // the same-server check and the held sign-in form get through.
        heldRouteRefusal(built.toString(), request.attributes.contains(BypassChildSeal))?.let { refusal ->
            Log.i(TAG, "[PROFILE-HOST] skipped ${request.method.value} $path: the active Child is held after a server change")
            throw refusal
        }
        // Built with a key a re-sign-in has since replaced: send the current
        // one (never logged), not a counted failure on a dead key. Never for
        // an explicit check of one key (the sign-in form, Fix Login, a
        // re-login's check), and never into a held key (keySwap).
        val bypassKeyGate = request.attributes.contains(BypassKeyGate)
        requestApiKey(request.headers["X-API-Key"], request.headers["Authorization"])
            ?.let { old -> keySwap(old, bypassKeyGate)?.let { old to it } }
            ?.let { (old, new) ->
                val (x, auth) = swappedAuthHeaders(request.headers["X-API-Key"], request.headers["Authorization"], old, new)
                x?.let { request.headers["X-API-Key"] = it }
                auth?.let { request.headers["Authorization"] = it }
                Log.i(TAG, "[AUTH] ${request.method.value} $path: sent with the re-issued key")
            }
        if (!bypassKeyGate) {
            val key = requestApiKey(request.headers["X-API-Key"], request.headers["Authorization"])
            if (key != null && DispatcharrRequestGate.isKeyDeleted(key)) {
                Log.i(TAG, "[AUTH] skipped ${request.method.value} $path: its playlist was deleted on this device")
                throw DispatcharrError.KeyRejected()
            }
            if (key != null && DispatcharrRequestGate.isKeyRejected(key)) {
                Log.i(TAG, "[AUTH] skipped ${request.method.value} $path: this API key was rejected by the server; waiting for Edit Playlist")
                throw DispatcharrError.KeyRejected()
            }
        }
        if (!isTokenEndpoint) {
            val left = DispatcharrRequestGate.pausedSeconds(server)
            if (left > 0) {
                val reason = DispatcharrRequestGate.pausedReason(server)
                Log.i(TAG, "[RATE] skipped ${request.method.value} $path: server paused after a 429 ($reason), ${left}s left")
                throw DispatcharrError.RateLimited(left, reason)
            }
        }
        val call = proceed(request)
        if (call.response.status.value == 429 && !isTokenEndpoint) {
            val retryAfterRaw = call.response.headers["Retry-After"]
            val secs = DispatcharrRequestGate.parseRetryAfter(retryAfterRaw)
            val serverHeader = call.response.headers["Server"]
            val body = runCatching { call.response.bodyAsText().take(200) }.getOrDefault("")
            val reason = rateLimitReasonOf(body)
            Log.w(
                TAG,
                "[RATE] HTTP 429 for ${request.method.value} $path: reason=$reason Retry-After=${retryAfterRaw ?: "none"} " +
                    "(waiting ${secs}s) Server=${serverHeader ?: "none"} body=" +
                    LogSanitizer.redact(body.replace('\n', ' ')),
            )
            DispatcharrRequestGate.pause(server, secs, reason)
            throw DispatcharrError.RateLimited(secs, reason)
        }
        call
    }
}

/** Set on an explicit key check (verifyConnection, Edit Playlist, the setup sender) so a rejected key can be tried again on purpose. */
internal val BypassKeyGate = AttributeKey<Boolean>("DispatcharrBypassKeyGate")

/**
 * The key a request built with [key] is sent with instead, or null to send
 * it as built. Measured on a phone 2026-11-12: a re-login against another
 * server had recorded the user's real key as replaced by that server's key,
 * which was then held; when the user signed in again and the real server
 * issued the real key, its check was swapped to the held key and refused
 * locally, so the right password could not sign in. An explicit key check
 * ([bypassKeyGate]) is never swapped, and nothing is swapped into a key that
 * is held.
 */
internal fun keySwap(key: String, bypassKeyGate: Boolean): String? {
    if (bypassKeyGate) return null
    return DispatcharrRequestGate.replacementFor(key)?.takeUnless { DispatcharrRequestGate.isKeyRejected(it) }
}

/** Set on the same-server check and the held sign-in form: they may reach a held Child's playlist (item 8). */
internal val BypassChildSeal = AttributeKey<Boolean>("DispatcharrBypassChildSeal")

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
