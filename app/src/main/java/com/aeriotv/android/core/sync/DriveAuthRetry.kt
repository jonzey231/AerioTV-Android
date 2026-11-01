package com.aeriotv.android.core.sync

import java.io.IOException

/** Drive answered 401 (Invalid Credentials): the access token is no longer accepted. */
class DriveAuthException(what: String) : IOException("$what: HTTP 401")

/** Drive answered with another HTTP error. Never read as "missing" or "empty". */
class DriveHttpException(val code: Int, what: String) : IOException("$what: HTTP $code")

/** Drive needs the user to sign in again; no call goes out until they do. */
class DriveNeedsSignInException : IOException("Drive needs sign-in")

/**
 * What [DriveAppDataClient] needs from the session owner (DriveSyncManager):
 * the current token for a call, one silent refresh after a 401, and the
 * needs-sign-in mark when that refresh does not produce a working token.
 */
interface DriveAuthHandler {
    /** True once Drive needs sign-in: calls fail fast without touching the network. */
    fun needsSignIn(): Boolean

    /** The token to use instead of [token] when a refresh already replaced it, else [token]. */
    fun current(token: String): String

    /** A new token after [stale] got a 401, or null when none can be had without the user. */
    suspend fun refresh(stale: String): String?

    /** The refresh failed or the refreshed token got a 401 too. */
    fun markNeedsSignIn(reason: String)
}

/**
 * One rule for every Drive call (2026-11-01 field run: 43 calls in a row
 * answered 401 with the same token): a 401 refreshes the token once and
 * retries once. A failed refresh, or a second 401, marks Drive as needing
 * sign-in and throws [DriveNeedsSignInException]. Pure, unit tested
 * (DriveAuthRetryTest).
 */
object DriveAuthRetry {
    suspend fun <T> run(h: DriveAuthHandler, token: String, call: suspend (String) -> T): T {
        if (h.needsSignIn()) throw DriveNeedsSignInException()
        val first = h.current(token)
        return try {
            call(first)
        } catch (e: DriveAuthException) {
            val fresh = h.refresh(first)
            if (fresh == null || fresh == first) {
                h.markNeedsSignIn(if (fresh == null) "refresh failed" else "refresh returned the same token")
                throw DriveNeedsSignInException()
            }
            try {
                call(fresh)
            } catch (e2: DriveAuthException) {
                h.markNeedsSignIn("401 after refresh")
                throw DriveNeedsSignInException()
            }
        }
    }
}
