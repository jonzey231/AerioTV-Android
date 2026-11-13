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
 *  6. The startup login (DispatcharrWarmupCoordinator) is the same attempt
 *     as a re-login (2026-11-11): it runs through [silentRelogin], so one
 *     launch makes at most one login, and a re-login while it runs, or
 *     within [ATTEMPT_WINDOW_MS] after it, shares its key instead of
 *     signing in again.
 *  7. A re-login that signs in but whose key the server refuses
 *     ([ReloginKeyRefused], one check of the key) parks that identity for
 *     [ATTEMPT_WINDOW_MS]: no new attempt, and no waiter or later caller
 *     gets that key (2026-11-12, Apple 44c544c).
 *  8. A finished result is shared only when its key differs from the one
 *     the caller just had refused (2026-11-12, measured on the Streamer: a
 *     key that passed its check, worked for 49 s, then was rotated on the
 *     server came back as the shared result and was held as if it had
 *     failed at issue). When the shared key IS the refused one: if it passed
 *     its check when issued, the server rotated it since, so a fresh
 *     re-login runs (still checked before anyone gets its key); if it never
 *     succeeded, the identity parks like a refused check ([noteKeyRefused]).
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
        /** The last re-login signed in, but users/me carried no API key. */
        @Volatile var noApiKey = false
        /** Until when the key the last re-login issued counts as refused: no attempt, no key. */
        @Volatile var keyRefusedUntil = 0L
        /** [lastKey] passed its one check when it was issued (the server accepted it then). */
        @Volatile var lastKeyVerified = false
        /** Set by the attempt in flight when its key passes its check ([noteKeyVerified]). */
        @Volatile var verifiedInFlight: String? = null
    }

    private val slots = ConcurrentHashMap<String, Slot>()
    private val serverParkedUntil = ConcurrentHashMap<String, Long>()

    /** Monotonic ms clock (tests replace it). */
    @Volatile internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
    /** Log sink (tests replace it; android.util.Log is not available on the JVM). */
    @Volatile internal var logSink: ((warn: Boolean, line: String) -> Unit)? = null

    private fun now() = clock()
    private fun logI(line: String) = logSink?.invoke(false, line) ?: run { Log.i(TAG, line); Unit }
    private fun logW(line: String) = logSink?.invoke(true, line) ?: run { Log.w(TAG, line); Unit }

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
        logW("[AUTH] login throttled by the server ($reason); next attempt in ${secs}s")
    }

    /** Why [baseUrl]'s logins are parked. */
    fun parkedReason(baseUrl: String): RateLimitReason = serverParkedReason[hostKey(baseUrl)] ?: RateLimitReason.SignInLockout

    /**
     * Run [attempt] (a silent re-login that returns the fresh api_key, or
     * null on failure) under the per-playlist rules above. [attempt] throws
     * [DispatcharrError.InvalidCredentials] for a rejected login.
     */
    suspend fun silentRelogin(
        playlistId: String,
        /** The key the caller just had refused, if any: a finished result with this same key is not shared (rule 8). */
        refusedKey: String? = null,
        attempt: suspend () -> String?,
    ): String? {
        val slot = slots.getOrPut(playlistId) { Slot() }
        return slot.mutex.withLock {
            val t = now()
            if (t < slot.rejectedUntil) {
                if (!slot.rejectionSurfaced) {
                    slot.rejectionSurfaced = true
                    surfaceRejected(playlistId)
                }
                logI("[AUTH] re-login for ${playlistId.take(8)} parked after a rejected login; " +
                    "next attempt in ${(slot.rejectedUntil - t + 999) / 1000}s")
                return@withLock null
            }
            if (t < slot.keyRefusedUntil) {
                // Its last re-login signed in, but the server refused the key it
                // issued: re-signing in mints the same key. Nobody gets it.
                logI("[AUTH] re-login for ${playlistId.take(8)} parked after the server refused the key it issued; " +
                    "next attempt in ${(slot.keyRefusedUntil - t + 999) / 1000}s")
                return@withLock null
            }
            if (slot.lastAttemptAt != 0L && t - slot.lastAttemptAt < ATTEMPT_WINDOW_MS) {
                when (shareRule(slot.lastKey, slot.lastKeyVerified, refusedKey)) {
                    Share.Share -> {
                        // A caller that waited on the in-flight attempt, or one inside
                        // the window with another key: share the result, never a second POST.
                        logI("[AUTH] re-login for ${playlistId.take(8)} already ran " +
                            "${(t - slot.lastAttemptAt) / 1000}s ago; sharing its result")
                        return@withLock slot.lastKey
                    }
                    Share.Park -> {
                        // Handed out, never succeeded, and refused now: nobody gets it again for the window.
                        slot.lastKey = null
                        slot.keyRefusedUntil = t + ATTEMPT_WINDOW_MS
                        logW("[AUTH] re-login for ${playlistId.take(8)} signed in, but the server refused the key it issued; the key is held")
                        return@withLock null
                    }
                    Share.Fresh -> logI(
                        "[AUTH] re-login for ${playlistId.take(8)}: the key it issued ${(t - slot.lastAttemptAt) / 1000}s ago passed its check " +
                            "and is refused now (rotated on the server); signing in again",
                    )
                }
            }
            slot.lastAttemptAt = t
            slot.lastKey = null
            slot.lastKeyVerified = false
            slot.verifiedInFlight = null
            slot.noApiKey = false
            try {
                attempt().also {
                    slot.lastKey = it
                    slot.lastKeyVerified = it != null && slot.verifiedInFlight == it
                }
            } catch (e: DispatcharrError.NoApiKey) {
                // Signed in, but the account has no API key (removed on the
                // server). The caller holds the key: retrying it only feeds
                // the server's failed-key lockout for the whole network.
                slot.noApiKey = true
                logW("[AUTH] re-login for ${playlistId.take(8)} signed in, but the account has no API key")
                null
            } catch (e: DispatcharrError.InvalidCredentials) {
                slot.rejectedUntil = now() + ATTEMPT_WINDOW_MS
                slot.rejectionSurfaced = true
                surfaceRejected(playlistId)
                null
            } catch (e: ReloginKeyRefused) {
                // Signed in, but the one check of the key it issued was refused:
                // not stored, nobody gets it, the caller holds the old key.
                slot.lastKey = null
                slot.keyRefusedUntil = now() + ATTEMPT_WINDOW_MS
                logW("[AUTH] re-login for ${playlistId.take(8)} signed in, but the server refused the key it issued; " +
                    "the key is held and no re-login runs for ${ATTEMPT_WINDOW_MS / 1000}s")
                null
            }
        }
    }

    /**
     * A key [playlistId]'s re-login issued came back refused after it was
     * handed out (a caller got it as the shared result and the server refused
     * it): park like a refused check; nobody gets that key again for the window.
     */
    fun noteKeyRefused(playlistId: String) {
        val slot = slots.getOrPut(playlistId) { Slot() }
        slot.lastKey = null
        slot.keyRefusedUntil = now() + ATTEMPT_WINDOW_MS
        logW("[AUTH] re-login for ${playlistId.take(8)} signed in, but the server refused the key it issued; the key is held")
    }

    /** The attempt in flight for [playlistId] checked [key] and the server accepted it (rule 8). */
    fun noteKeyVerified(playlistId: String, key: String) {
        slots[playlistId]?.verifiedInFlight = key
    }

    /** [key] is [playlistId]'s last re-login result and it passed its check when issued. */
    fun keyVerified(playlistId: String, key: String): Boolean =
        slots[playlistId]?.let { it.lastKeyVerified && it.lastKey?.trim() == key.trim() } == true

    /** What a caller inside the window gets ([refusedKey]: the key it just had refused). */
    internal enum class Share { Share, Fresh, Park }

    /**
     * Rule 8, pure: share a finished result unless its key is the one just
     * refused; then a key that passed its check was rotated since (a fresh
     * re-login), and one that never succeeded parks the identity.
     */
    internal fun shareRule(lastKey: String?, lastKeyVerified: Boolean, refusedKey: String?): Share = when {
        lastKey == null || refusedKey == null || lastKey.trim() != refusedKey.trim() -> Share.Share
        lastKeyVerified -> Share.Fresh
        else -> Share.Park
    }

    /** The last re-login for [playlistId] signed in, but the server refused the key it issued (within the window). */
    fun foundRefusedKey(playlistId: String): Boolean = (slots[playlistId]?.keyRefusedUntil ?: 0L) > now()

    /**
     * The re-login slot for [playlistId] signing in as [profileId] (null or
     * empty = the playlist's own sign-in). One slot per identity
     * (2026-11-12, security): a non-default profile's re-login never shares
     * the playlist's (or another profile's) fresh key, which would replay its
     * requests as that user. Same key as the profile switch's own login.
     */
    fun slotFor(playlistId: String, profileId: String?): String =
        if (profileId.isNullOrEmpty()) playlistId else "$playlistId:$profileId"

    /** The last re-login for [playlistId] signed in but the account has no API key. */
    fun accountHasNoKey(playlistId: String): Boolean = slots[playlistId]?.noApiKey == true

    /** True while a rejected login parks [playlistId] (warmup skips its login). */
    fun isRejected(playlistId: String): Boolean =
        (slots[playlistId]?.rejectedUntil ?: 0L) > now()

    /**
     * The user just signed in to [playlistId] with a password the server
     * accepted (the rejected-key prompt's sign-in form): forget the park and
     * the shared result, so the next silent re-login uses the new password.
     */
    fun reset(playlistId: String) { slots.remove(playlistId) }

    /** A login outside [silentRelogin] (foreground warmup) was rejected. */
    fun noteRejected(playlistId: String) {
        val slot = slots.getOrPut(playlistId) { Slot() }
        if (slot.rejectedUntil > now()) return
        slot.rejectedUntil = now() + ATTEMPT_WINDOW_MS
        slot.rejectionSurfaced = true
        surfaceRejected(playlistId)
    }

    private fun surfaceRejected(playlistId: String) {
        logW(
            "[AUTH] login rejected for ${playlistId.take(8)}; re-login parked for " +
                "${ATTEMPT_WINDOW_MS / 1000}s. The user must enter a new key or password in Edit Playlist.",
        )
    }

    /** Test hook: forget every slot and park. */
    internal fun resetForTests() {
        slots.clear()
        serverParkedUntil.clear()
        serverParkedReason.clear()
    }

    /** Parse a Retry-After header (seconds form; HTTP-date falls back to default). */
    fun parseRetryAfter(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it > 0 }
}

/**
 * A silent re-login signed in, but the one request that checked the key it
 * read from the account was refused ([reloginOutcome]). [DispatcharrLoginGate]
 * ends the re-login with no key and parks the identity.
 */
internal class ReloginKeyRefused : Exception("The server refused the key the re-login issued")
