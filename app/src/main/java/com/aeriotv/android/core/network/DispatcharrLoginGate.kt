package com.aeriotv.android.core.network

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps AerioTV from tripping Dispatcharr's login throttle (scope "login",
 * 3 POST /api/accounts/token/ per minute per client IP; 429 with a
 * Retry-After while the window lasts). Before this gate one 401 on an API
 * call fanned out into a silent re-login from every caller (player reload
 * ladder, DVR refresh, guide), several per minute, and once the throttle
 * tripped every later login on the same home IP was refused too.
 *
 * Rules (Apple twin: DispatcharrLoginGate in DispatcharrDirectConnect.swift):
 *  1. At most one silent re-login per playlist per [ATTEMPT_WINDOW_MS];
 *     concurrent callers wait on the in-flight attempt and share its result.
 *  2. A 429 parks every login to that server until Retry-After (default 60 s).
 *  3. A rejected login (401/403) parks that playlist for [ATTEMPT_WINDOW_MS]
 *     and the "enter a new key" state is surfaced once per park.
 *  4. Callers never reach the gate without a stored password (API-key mode).
 *  5. A successful login clears nothing and changes nothing.
 */
object DispatcharrLoginGate {
    private const val TAG = "DispatcharrAuthBroker"
    const val ATTEMPT_WINDOW_MS = 60_000L
    const val DEFAULT_RETRY_AFTER_S = 60L

    private class Slot {
        val mutex = Mutex()
        @Volatile var lastAttemptAt = 0L
        @Volatile var lastKey: String? = null
        @Volatile var rejectedUntil = 0L
        @Volatile var rejectionSurfaced = false
    }

    private val slots = ConcurrentHashMap<String, Slot>()
    private val serverParkedUntil = ConcurrentHashMap<String, Long>()

    private fun now() = SystemClock.elapsedRealtime()

    private fun hostKey(baseUrl: String): String =
        runCatching { URI(baseUrl.trim()).let { "${it.host}:${it.port}" } }.getOrNull()
            ?: baseUrl.trim().trimEnd('/')

    /** Seconds left on a 429 park for [baseUrl], or 0 when logins may go out. */
    fun serverParkedSeconds(baseUrl: String): Long {
        val until = serverParkedUntil[hostKey(baseUrl)] ?: return 0
        val left = until - now()
        return if (left > 0) (left + 999) / 1000 else 0
    }

    /** Reason of the 429 that parked each server's logins. */
    private val serverParkedReason = ConcurrentHashMap<String, RateLimitReason>()

    /** Record a 429 from the login (or refresh) endpoint. */
    fun parkServer(baseUrl: String, retryAfterSeconds: Long?, reason: RateLimitReason = RateLimitReason.SignInLockout) {
        val secs = retryAfterSeconds?.takeIf { it > 0 } ?: DEFAULT_RETRY_AFTER_S
        serverParkedUntil[hostKey(baseUrl)] = now() + secs * 1000
        serverParkedReason[hostKey(baseUrl)] = reason
        Log.w(TAG, "[AUTH] login throttled by the server ($reason); next attempt in ${secs}s")
    }

    /** Why [baseUrl]'s logins are parked. */
    fun parkedReason(baseUrl: String): RateLimitReason = serverParkedReason[hostKey(baseUrl)] ?: RateLimitReason.SignInLockout

    /**
     * Run [attempt] (a silent re-login that returns the fresh api_key, or
     * null on failure) under the per-playlist rules above. [attempt] throws
     * [DispatcharrError.InvalidCredentials] for a rejected login.
     */
    suspend fun silentRelogin(playlistId: String, attempt: suspend () -> String?): String? {
        val slot = slots.getOrPut(playlistId) { Slot() }
        return slot.mutex.withLock {
            val t = now()
            if (t < slot.rejectedUntil) {
                if (!slot.rejectionSurfaced) {
                    slot.rejectionSurfaced = true
                    surfaceRejected(playlistId)
                }
                Log.i(TAG, "[AUTH] re-login for ${playlistId.take(8)} parked after a rejected login; " +
                    "next attempt in ${(slot.rejectedUntil - t + 999) / 1000}s")
                return@withLock null
            }
            if (slot.lastAttemptAt != 0L && t - slot.lastAttemptAt < ATTEMPT_WINDOW_MS) {
                // A caller that waited on the in-flight attempt, or one inside
                // the window: share the result, never a second POST.
                Log.i(TAG, "[AUTH] re-login for ${playlistId.take(8)} already ran " +
                    "${(t - slot.lastAttemptAt) / 1000}s ago; sharing its result")
                return@withLock slot.lastKey
            }
            slot.lastAttemptAt = t
            slot.lastKey = null
            try {
                attempt().also { slot.lastKey = it }
            } catch (e: DispatcharrError.InvalidCredentials) {
                slot.rejectedUntil = now() + ATTEMPT_WINDOW_MS
                slot.rejectionSurfaced = true
                surfaceRejected(playlistId)
                null
            }
        }
    }

    /** True while a rejected login parks [playlistId] (warmup skips its login). */
    fun isRejected(playlistId: String): Boolean =
        (slots[playlistId]?.rejectedUntil ?: 0L) > now()

    /** A login outside [silentRelogin] (foreground warmup) was rejected. */
    fun noteRejected(playlistId: String) {
        val slot = slots.getOrPut(playlistId) { Slot() }
        if (slot.rejectedUntil > now()) return
        slot.rejectedUntil = now() + ATTEMPT_WINDOW_MS
        slot.rejectionSurfaced = true
        surfaceRejected(playlistId)
    }

    private fun surfaceRejected(playlistId: String) {
        Log.w(
            TAG,
            "[AUTH] login rejected for ${playlistId.take(8)}; re-login parked for " +
                "${ATTEMPT_WINDOW_MS / 1000}s. The user must enter a new key or password in Edit Playlist.",
        )
    }

    /** Parse a Retry-After header (seconds form; HTTP-date falls back to default). */
    fun parseRetryAfter(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it > 0 }
}
