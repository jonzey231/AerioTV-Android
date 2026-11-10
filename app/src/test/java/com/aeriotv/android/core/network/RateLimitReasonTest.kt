package com.aeriotv.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every 429 the app shows names Dispatcharr and what it rate-limited (shared
 * spec, Logan 2026-11-10). Classification is pure and reads the server's own
 * reason: JSON `detail` or `error`, or plain text, by its leading phrase.
 */
class RateLimitReasonTest {
    @Test fun apiKeyLockoutJsonDetail() {
        assertEquals(
            RateLimitReason.ApiKeyLockout,
            rateLimitReasonOf("""{"detail":"Too many API key attempts. Try again later."}"""),
        )
    }

    @Test fun apiKeyLockoutWithTheDrfExpectedAvailableSuffix() {
        assertEquals(
            RateLimitReason.ApiKeyLockout,
            rateLimitReasonOf("""{"detail": "Too many API key attempts. Try again later. Expected available in 42 seconds."}"""),
        )
    }

    @Test fun signInLockoutJsonDetailAndPlainText() {
        assertEquals(RateLimitReason.SignInLockout, rateLimitReasonOf("""{"detail":"Too many login attempts. Try again later."}"""))
        assertEquals(RateLimitReason.SignInLockout, rateLimitReasonOf("Too many login attempts. Try again later."))
        assertEquals(RateLimitReason.SignInLockout, rateLimitReasonOf("  too many LOGIN attempts. try again later.\n"))
    }

    @Test fun apiKeyLockoutPlainTextIsCaseInsensitive() {
        assertEquals(RateLimitReason.ApiKeyLockout, rateLimitReasonOf("TOO MANY API KEY ATTEMPTS. Try again later."))
    }

    @Test fun streamLimitJsonErrorIsNotALockout() {
        assertEquals(
            RateLimitReason.Other,
            rateLimitReasonOf("""{"error": "Stream limit exceeded (2 concurrent streams allowed)"}"""),
        )
    }

    @Test fun unknownBodies() {
        assertEquals(RateLimitReason.Other, rateLimitReasonOf("""{"detail":"Request was throttled. Expected available in 5 seconds."}"""))
        assertEquals(RateLimitReason.Other, rateLimitReasonOf("<html><body>429 Too Many Requests</body></html>"))
        assertEquals(RateLimitReason.Other, rateLimitReasonOf(""))
        assertEquals(RateLimitReason.Other, rateLimitReasonOf(null))
        // The leading phrase decides: mentioning it later is not enough.
        assertEquals(RateLimitReason.Other, rateLimitReasonOf("Slow down: too many API key attempts"))
    }

    @Test fun countdownCopy() {
        assertEquals(
            "Dispatcharr rate-limited this network after too many failed API key attempts. Trying again in 42 seconds.",
            serverBusyCountdown(42, RateLimitReason.ApiKeyLockout),
        )
        assertEquals(
            "Dispatcharr rate-limited sign-ins from this network after too many failed attempts. Trying again in 1 second.",
            serverBusyCountdown(1, RateLimitReason.SignInLockout),
        )
        assertEquals(
            "Dispatcharr rate-limited this network (HTTP 429). Trying again in 60 seconds.",
            serverBusyCountdown(60, RateLimitReason.Other),
        )
    }

    @Test fun finalCopy() {
        assertEquals(
            "Dispatcharr rate-limited this network after too many failed API key attempts. Wait 42 seconds and try again.",
            rateLimitedMessage(42, RateLimitReason.ApiKeyLockout),
        )
        assertEquals(
            "Dispatcharr rate-limited sign-ins from this network after too many failed attempts. Wait 1 second and try again.",
            rateLimitedMessage(1, RateLimitReason.SignInLockout),
        )
        assertEquals(
            "Dispatcharr rate-limited this network (HTTP 429). Wait 60 seconds and try again.",
            rateLimitedMessage(60),
        )
    }

    @Test fun streamLimitCopy() {
        assertEquals(
            "Dispatcharr stream limit reached: 2 concurrent streams allowed for this account.",
            streamLimitMessage("Stream limit exceeded (2 concurrent streams allowed)"),
        )
        assertEquals("Dispatcharr stream limit reached.", streamLimitMessage("Stream limit exceeded"))
        assertEquals("Dispatcharr stream limit reached.", streamLimitMessage(null))
    }

    @Test fun errorsCarryTheirReason() {
        assertEquals(
            "Dispatcharr rate-limited sign-ins from this network after too many failed attempts. Wait 30 seconds and try again.",
            DispatcharrError.LoginThrottled(30).message,
        )
        assertEquals(RateLimitReason.ApiKeyLockout, IllegalStateException("x", DispatcharrError.RateLimited(5, RateLimitReason.ApiKeyLockout)).rateLimitReason())
        assertEquals(RateLimitReason.Other, IllegalStateException("x").rateLimitReason())
    }

    @Test fun thePauseRemembersItsReason() {
        DispatcharrRequestGate.resetForTests()
        try {
            DispatcharrRequestGate.pause("host:443", 30, RateLimitReason.ApiKeyLockout)
            assertEquals(RateLimitReason.ApiKeyLockout, DispatcharrRequestGate.pausedReason("host:443"))
            assertEquals(RateLimitReason.Other, DispatcharrRequestGate.pausedReason("other:443"))
        } finally {
            DispatcharrRequestGate.resetForTests()
        }
    }
}
