package com.aeriotv.android.core.network

/**
 * Typed exception family for Dispatcharr Direct Connect, mirroring iOS
 * `DispatcharrDirectConnectError` (DispatcharrDirectConnect.swift line 271-305)
 * so callers can branch on cause instead of regex-matching exception messages:
 *
 *  - [InvalidCredentials]: 401/403 from POST /api/accounts/token/. The user
 *    typed the wrong username or password; the message carries the
 *    Dashboard-vs-XC distinction so UX shows the actionable copy.
 *  - [Unauthorized]: 401/403 from an api_key-authenticated call (channel
 *    list, EPG grid, DVR, VOD, etc.). Triggers
 *    [DispatcharrAuthBroker.silentRebootstrapApiKey], the admin probably
 *    rotated the user's api_key, so we re-login from stored creds, extract
 *    the fresh key, persist it, and replay.
 *  - [RefreshExpired]: 401/403 from POST /api/accounts/token/refresh/.
 *    The refresh token is itself stale (24 h+ idle); the warmup path
 *    falls back to a fresh login.
 *  - [UnexpectedResponse]: 200 OK but the JSON didn't decode. Catches
 *    the SPA-shell case where the URL points at a non-Dispatcharr host
 *    that 200s with an HTML login page.
 *  - [Transport]: any other network or HTTP failure.
 *
 * All four extend [Exception] so the existing try/catch chain at the
 * PlaylistRepository.loadAndPersist boundary continues to surface
 * `t.message` as the user-facing error.
 */
sealed class DispatcharrError(message: String) : Exception(message) {
    class InvalidCredentials(message: String) : DispatcharrError(message)
    open class Unauthorized(message: String) : DispatcharrError(message)

    /**
     * The server already rejected this exact API key and the app has no way
     * to recover it ([DispatcharrRequestGate]): failed locally, nothing was
     * sent. An [Unauthorized] so every caller that stops on a 401 stops here
     * too; the auth broker never tries to recover it.
     */
    class KeyRejected : Unauthorized(
        "Dispatcharr no longer accepts this playlist's API key. Enter the current key in Edit Playlist, " +
            "or ask your Dispatcharr administrator for a new one.",
    )
    class RefreshExpired(message: String) : DispatcharrError(message)
    class UnexpectedResponse(message: String) : DispatcharrError(message)
    open class Transport(message: String) : DispatcharrError(message)

    /** HTTP 404 from a by-id lookup (a regenerated program id, for one).
     *  A [Transport] so every existing catch keeps handling it. */
    class NotFound(message: String) : Transport(message)

    /** HTTP 429 from the login endpoint, or a login skipped because an
     *  earlier 429 is still parking it. A [Transport] so every existing
     *  catch keeps handling it; the copy says to wait. */
    class LoginThrottled(
        val retryAfterSeconds: Long,
        val reason: RateLimitReason = RateLimitReason.SignInLockout,
    ) : Transport(rateLimitedMessage(retryAfterSeconds, reason))

    /**
     * HTTP 429 from an API call, or a call skipped because an earlier 429
     * still parks the server ([DispatcharrRequestGate]). [retryAfterSeconds]
     * is the wait left (Retry-After, 60 s when missing, capped at 120 s). A
     * [Transport] so every existing catch keeps handling it.
     */
    class RateLimited(
        val retryAfterSeconds: Long,
        /** What the server said the 429 was for (its body), see [rateLimitReasonOf]. */
        val reason: RateLimitReason = RateLimitReason.Other,
    ) : Transport(rateLimitedMessage(retryAfterSeconds, reason))

    /**
     * A 403 from an endpoint whose permission class is a CAPABILITY check
     * rather than an authentication check (the DVR writes, which are
     * IsAdminOrDVRManager).
     *
     * Deliberately NOT [Unauthorized]: the auth broker rebootstraps the
     * api_key on Unauthorized and silently replays, which turned a real "your
     * account may not manage recordings" into a confusing retry loop. This one
     * is not retried; the caller re-probes /api/accounts/users/me/, re-derives
     * the capability, and either names the missing permission or -- when the
     * account's permissions say it SHOULD be allowed -- points at a network
     * restriction (per-user allowed_networks) instead.
     */
    class Forbidden(
        message: String,
        val capabilityHint: String? = null,
    ) : DispatcharrError(message)
}

/**
 * Why Dispatcharr answered 429, read from its own reason (shared spec, Logan
 * 2026-11-10): every 429 the app shows says exactly that Dispatcharr
 * rate-limited the user, and for what.
 */
enum class RateLimitReason {
    /** "Too many API key attempts. Try again later." (apps/accounts/authentication.py). */
    ApiKeyLockout,

    /** "Too many login attempts. Try again later." (the sign-in throttle). */
    SignInLockout,

    /** Any other 429. */
    Other,
}

/**
 * Pure: the [RateLimitReason] in a 429 [body]: the JSON `detail` or `error`
 * string, else the plain text, matched case-insensitively on its leading
 * phrase. DRF may append "Expected available in N seconds." to the detail;
 * the leading phrase still decides.
 */
fun rateLimitReasonOf(body: String?): RateLimitReason {
    val text = rateLimitText(body) ?: return RateLimitReason.Other
    return when {
        text.startsWith("Too many API key attempts", ignoreCase = true) -> RateLimitReason.ApiKeyLockout
        text.startsWith("Too many login attempts", ignoreCase = true) -> RateLimitReason.SignInLockout
        else -> RateLimitReason.Other
    }
}

/** The server's reason text in a refusal body: JSON `detail`, else `error`, else short plain text. */
internal fun rateLimitText(body: String?): String? {
    if (body.isNullOrBlank()) return null
    jsonStringField(body, "detail")?.let { return it }
    jsonStringField(body, "error")?.let { return it }
    val flat = body.trim().replace(Regex("\\s+"), " ")
    return flat.takeIf { it.length <= 300 && !it.startsWith("<") && !it.startsWith("{") }
}

private fun jsonStringField(body: String, name: String): String? {
    val key = "\"$name\""
    val at = body.indexOf(key)
    if (at < 0) return null
    val colon = body.indexOf(':', at + key.length)
    val open = if (colon >= 0) body.indexOf('"', colon + 1) else -1
    if (open < 0) return null
    val close = body.indexOf('"', open + 1)
    return if (close > open) body.substring(open + 1, close).trim().takeIf { it.isNotEmpty() } else null
}

private fun rateLimitLead(reason: RateLimitReason): String = when (reason) {
    RateLimitReason.ApiKeyLockout -> "Dispatcharr rate-limited this network after too many failed API key attempts."
    RateLimitReason.SignInLockout -> "Dispatcharr rate-limited sign-ins from this network after too many failed attempts."
    RateLimitReason.Other -> "Dispatcharr rate-limited this network (HTTP 429)."
}

/** Final copy once the automatic retries are spent (or with none to make). */
fun rateLimitedMessage(seconds: Long, reason: RateLimitReason = RateLimitReason.Other): String =
    "${rateLimitLead(reason)} Wait ${secondsPhrase(seconds)} and try again."

/** "1 second", "45 seconds". */
fun secondsPhrase(seconds: Long): String = if (seconds == 1L) "1 second" else "$seconds seconds"

/** The countdown line while a 429 is waited out before the next automatic try. */
fun serverBusyCountdown(seconds: Long, reason: RateLimitReason = RateLimitReason.Other): String =
    "${rateLimitLead(reason)} Trying again in ${secondsPhrase(seconds)}."

/**
 * The stream-limit refusal ("Stream limit exceeded (2 concurrent streams
 * allowed)"): never retried, so there is only this line. M is read from the
 * server text; without it the sentence stops at "reached."
 */
fun streamLimitMessage(serverText: String?): String {
    val m = serverText?.let { Regex("""(\d+)\s+concurrent""", RegexOption.IGNORE_CASE).find(it) }
        ?.groupValues?.get(1)?.toIntOrNull()
    return if (m != null) {
        "Dispatcharr stream limit reached: $m concurrent streams allowed for this account."
    } else {
        "Dispatcharr stream limit reached."
    }
}

/** The 429 reason anywhere in [this] throwable's cause chain; [RateLimitReason.Other] when none is known. */
fun Throwable.rateLimitReason(): RateLimitReason {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth < 8) {
        when (t) {
            is DispatcharrError.RateLimited -> return t.reason
            is DispatcharrError.LoginThrottled -> return t.reason
            is RateLimitRetry.RateLimitedFinal -> return t.reason
            is RateLimitReasonCarrier -> return t.rateLimitReason
        }
        t = t.cause
        depth++
    }
    return RateLimitReason.Other
}

/** An exception from another layer (the stream gate) that knows its 429 reason. */
interface RateLimitReasonCarrier {
    val rateLimitReason: RateLimitReason
}

/** The 429 anywhere in [this] throwable's cause chain, as seconds to wait; null when it is not one. */
fun Throwable.rateLimitSeconds(): Long? {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth < 8) {
        when (t) {
            is DispatcharrError.RateLimited -> return t.retryAfterSeconds
            is DispatcharrError.LoginThrottled -> return t.retryAfterSeconds
        }
        t = t.cause
        depth++
    }
    // A layer that rewrapped the error by message only (PlaylistFetcher,
    // a Result built from t.message) still carries the status text.
    var m: Throwable? = this
    depth = 0
    while (m != null && depth < 8) {
        if (m.message?.contains("HTTP 429") == true) return DispatcharrRequestGate.DEFAULT_RETRY_AFTER_S
        m = m.cause
        depth++
    }
    return null
}
