package com.aeriotv.android.core.playback

import com.aeriotv.android.core.network.DispatcharrRequestGate
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A stream open the server answered with 401 for this playlist's API key,
 * and the key could not be recovered: it is held ("Sign-in not accepted"
 * shows), the stream stops, and no stream opens with that key again.
 */
open class StreamKeyRejectedException protected constructor(message: String, cause: Throwable?) : IOException(message, cause) {
    constructor(cause: Throwable? = null) : this(com.aeriotv.android.core.network.KEY_REJECTED_MESSAGE, cause)
}

/**
 * Item 15 of the profile follow-ups (parity, 2026-11-11): a stream open to
 * an address a held Child is sealed from (item 8: the playlist's server
 * address changed and no same-server check has passed). Refused here, before
 * anything goes out, and stopped like a held key (no retry, no failover
 * walk), but shown as the existing line "Could not reach your Dispatcharr
 * server", the same as the lists. No prompt shows.
 */
class StreamRouteHeldException : StreamKeyRejectedException(com.aeriotv.android.feature.settings.DISPATCHARR_UNREACHABLE, null)

/**
 * HTTP 401 on Dispatcharr stream opens (Logan 2026-11-10). A Google TV on an
 * older build sent 60 stream opens with a dead key in 3 minutes, and the
 * server's failed-key lockout then blocked every device on the network.
 *
 * Every stream open (live, Android Auto, on demand, DVR playback, catch-up and
 * Live Rewind, Multiview tiles and background, the Cast proxy ingest and
 * Multiview compositor, on-device recording) passes [StreamGate], which:
 *  - per open, rewrites the key the request was built with to its current
 *    state ([rewriteFor]): a key a re-sign-in replaced goes out as the new
 *    key; an open built with a held key never goes out at all (no request,
 *    not even a keyless one) and stops with [StreamKeyRejectedException]
 *    (2026-11-11: after a relaunch re-held the key, tunes went out keyless,
 *    got a plain 401, and sat in the reconnect loop);
 *  - on a 401 that carried a key, asks the key broker once ([onUnauthorized],
 *    one at a time per key across every stream): one probe, then a silent
 *    re-sign-in when a password is stored (the open is retried once with the
 *    new key), else the key is held and the existing prompt shows; the
 *    stream stops with [StreamKeyRejectedException].
 * Keys are never logged; a key's identity is the request gate's hash.
 */
object StreamAuth {
    sealed interface Outcome {
        /** Open again with [key] (the re-issued key). */
        data class Retry(val key: String) : Outcome
        /** The key is held: the stream stops, no open uses it again. */
        data object Held : Outcome
        /** The key works (or nothing can be proved): the 401 is the stream's own; normal error handling. */
        data object NotKey : Outcome
    }

    sealed interface Rewrite {
        data object Keep : Rewrite
        data class Replace(val key: String) : Rewrite
        /** The key is held: the open stops before any request. */
        data object Held : Rewrite
    }

    /** The broker's 401 handling (DispatcharrAuthBroker registers it). */
    @Volatile var handler: (suspend (usedKey: String) -> Outcome)? = null

    /** Clock in ms (tests replace it). */
    @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** After the broker found the key good, another stream 401 with it is not probed again for this long. */
    const val NOT_KEY_COOLDOWN_MS = 60_000L

    /** Longest a stream open waits for the broker. */
    const val WAIT_MS = 20_000L

    /** Keys the broker gave up on without a playlist to hold them for (hash to until when). */
    private val blocked = ConcurrentHashMap<String, Long>()
    private const val BLOCK_TTL_MS = 600_000L
    private val notKeyUntil = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Outcome>>()

    /** The two header names that carry the key (PlaybackHeaders' dual pair). */
    fun isAuthHeader(name: String): Boolean =
        name.equals("X-API-Key", ignoreCase = true) || name.equals("Authorization", ignoreCase = true)

    /** The key a request carries: X-API-Key, else `Authorization: ApiKey <key>`. Null without one. */
    fun keyOf(header: (String) -> String?): String? {
        header("X-API-Key")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val auth = header("Authorization")?.trim() ?: return null
        val parts = auth.split(Regex("\\s+"), limit = 2)
        if (parts.size == 2 && parts[0].equals("ApiKey", ignoreCase = true)) return parts[1].trim().takeIf { it.isNotEmpty() }
        return null
    }

    fun keyOf(headers: Map<String, String>): String? =
        keyOf { name -> headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value }

    /** The auth headers for [key], the shape PlaybackHeaders sends. */
    fun authHeaders(key: String): Map<String, String> = mapOf("X-API-Key" to key, "Authorization" to "ApiKey $key")

    fun isHeld(key: String): Boolean {
        if (DispatcharrRequestGate.isKeyRejected(key)) return true
        val h = DispatcharrRequestGate.keyHash(key)
        val until = blocked[h] ?: return false
        if (clock() >= until) { blocked.remove(h, until); return false }
        return true
    }

    /** What an open built with [key] sends now. */
    fun rewriteFor(key: String?): Rewrite {
        if (key == null) return Rewrite.Keep
        DispatcharrRequestGate.replacementFor(key)?.takeIf { !isHeld(it) }?.let { return Rewrite.Replace(it) }
        if (isHeld(key)) return Rewrite.Held
        return Rewrite.Keep
    }

    /**
     * A stream answered 401 with [key]. Blocking (stream threads only): every
     * stream that hits this at the same moment shares one broker call.
     */
    fun onUnauthorized(key: String): Outcome {
        when (val r = rewriteFor(key)) {
            is Rewrite.Replace -> return Outcome.Retry(r.key)
            Rewrite.Held -> return Outcome.Held
            Rewrite.Keep -> Unit
        }
        val h = DispatcharrRequestGate.keyHash(key)
        notKeyUntil[h]?.let { if (clock() < it) return Outcome.NotKey else notKeyUntil.remove(h, it) }
        val mine = CompletableDeferred<Outcome>()
        val shared = inFlight.putIfAbsent(h, mine)
        if (shared != null) {
            return runBlocking { withTimeoutOrNull(WAIT_MS) { shared.await() } } ?: Outcome.NotKey
        }
        var out: Outcome = Outcome.NotKey
        try {
            val call = handler
            out = if (call == null) Outcome.NotKey
            else runCatching { runBlocking { withTimeoutOrNull(WAIT_MS) { call(key) } } }.getOrNull() ?: Outcome.NotKey
            when (out) {
                Outcome.NotKey -> notKeyUntil[h] = clock() + NOT_KEY_COOLDOWN_MS
                // Held without a playlist to hold it for (no prompt): strip it here anyway.
                Outcome.Held -> if (!DispatcharrRequestGate.isKeyRejected(key)) blocked[h] = clock() + BLOCK_TTL_MS
                is Outcome.Retry -> Unit
            }
            log("[AUTH] stream HTTP 401: ${when (out) { is Outcome.Retry -> "re-signed in, retrying with the new key"; Outcome.Held -> "key held, stream stopped"; Outcome.NotKey -> "key is fine, stream error stands" }}")
        } finally {
            mine.complete(out)
            inFlight.remove(h, mine)
        }
        return out
    }

    /** The key refusal anywhere in [error]'s cause chain, or null. */
    fun find(error: Throwable?): StreamKeyRejectedException? {
        var t = error
        var depth = 0
        while (t != null && depth < 8) {
            if (t is StreamKeyRejectedException) return t
            t = t.cause
            depth++
        }
        return null
    }

    fun resetForTests() {
        blocked.clear()
        notKeyUntil.clear()
        inFlight.clear()
        handler = null
        clock = { System.nanoTime() / 1_000_000 }
    }

    /** Log sink (tests replace it). */
    @Volatile var log: (String) -> Unit = { android.util.Log.i(StreamGate.TAG, it) }
}
