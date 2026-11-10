package com.aeriotv.android.core.network

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Retry-After parsing, the per-server 429 pause, and the interactive countdown retry. */
class DispatcharrRateLimitTest {
    private var now = 1_000_000L
    private val wallNow = java.time.ZonedDateTime.parse("2026-11-09T12:00:00Z").toInstant().toEpochMilli()

    @Before fun setUp() {
        DispatcharrRequestGate.resetForTests()
        DispatcharrRequestGate.clock = { now }
        DispatcharrRequestGate.wallClock = { wallNow }
    }

    @After fun tearDown() {
        DispatcharrRequestGate.resetForTests()
        DispatcharrRequestGate.clock = { System.nanoTime() / 1_000_000 }
        DispatcharrRequestGate.wallClock = { System.currentTimeMillis() }
        RateLimitRetry.tick = { kotlinx.coroutines.delay(1_000) }
    }

    @Test fun retryAfterSeconds() {
        assertEquals(42L, DispatcharrRequestGate.parseRetryAfter("42"))
        assertEquals(42L, DispatcharrRequestGate.parseRetryAfter(" 42 "))
        assertEquals(3L, DispatcharrRequestGate.parseRetryAfter("2.2"))
    }

    @Test fun retryAfterMissingOrBadDefaultsTo60() {
        assertEquals(60L, DispatcharrRequestGate.parseRetryAfter(null))
        assertEquals(60L, DispatcharrRequestGate.parseRetryAfter(""))
        assertEquals(60L, DispatcharrRequestGate.parseRetryAfter("soon"))
        assertEquals(60L, DispatcharrRequestGate.parseRetryAfter("0"))
        assertEquals(60L, DispatcharrRequestGate.parseRetryAfter("-5"))
    }

    @Test fun retryAfterCappedAt120() {
        assertEquals(120L, DispatcharrRequestGate.parseRetryAfter("3600"))
    }

    @Test fun retryAfterHttpDate() {
        assertEquals(30L, DispatcharrRequestGate.parseRetryAfter("Mon, 09 Nov 2026 12:00:30 GMT", wallNow))
        assertEquals(120L, DispatcharrRequestGate.parseRetryAfter("Mon, 09 Nov 2026 13:00:00 GMT", wallNow))
        // In the past: the default, never zero.
        assertEquals(60L, DispatcharrRequestGate.parseRetryAfter("Mon, 09 Nov 2026 11:00:00 GMT", wallNow))
    }

    @Test fun serverKeyNormalizesPorts() {
        assertEquals("tv.example.com:443", DispatcharrRequestGate.serverKey("https://TV.example.com/api/x/"))
        assertEquals("192.168.1.5:9191", DispatcharrRequestGate.serverKey("http://192.168.1.5:9191/api/core/version/"))
        assertEquals("host:80", DispatcharrRequestGate.serverKey("http://host"))
    }

    @Test fun pauseCountsDownAndClears() {
        val k = "tv.example.com:443"
        assertEquals(0L, DispatcharrRequestGate.pausedSeconds(k))
        DispatcharrRequestGate.pause(k, 60)
        assertEquals(60L, DispatcharrRequestGate.pausedSeconds(k))
        now += 59_500
        assertEquals(1L, DispatcharrRequestGate.pausedSeconds(k))
        now += 500
        assertEquals(0L, DispatcharrRequestGate.pausedSeconds(k))
        // Another server is never touched.
        DispatcharrRequestGate.pause(k, 10)
        assertEquals(0L, DispatcharrRequestGate.pausedSeconds("other:443"))
    }

    @Test fun shorterPauseNeverShortensALongerOne() {
        val k = "s:443"
        DispatcharrRequestGate.pause(k, 90)
        DispatcharrRequestGate.pause(k, 5)
        assertEquals(90L, DispatcharrRequestGate.pausedSeconds(k))
        DispatcharrRequestGate.pause(k, 500)
        assertEquals(120L, DispatcharrRequestGate.pausedSeconds(k))
    }

    @Test fun rateLimitFoundThroughCausesAndMessages() {
        assertEquals(30L, DispatcharrError.RateLimited(30).rateLimitSeconds())
        assertEquals(30L, IllegalStateException("Could not connect", DispatcharrError.RateLimited(30)).rateLimitSeconds())
        assertEquals(60L, IllegalStateException("Failed: HTTP 429 Too Many Requests").rateLimitSeconds())
        assertEquals(null, IllegalStateException("HTTP 401").rateLimitSeconds())
    }

    @Test fun countdownRetriesTwiceThenNamesTheRateLimit() = runBlocking {
        RateLimitRetry.tick = {}
        val shown = mutableListOf<Long>()
        var attempts = 0
        val r = RateLimitRetry.run<Int>(onCountdown = { shown += it }) {
            attempts++
            Result.failure(DispatcharrError.RateLimited(3))
        }
        assertEquals(3, attempts)
        assertEquals(listOf(3L, 2L, 1L, 3L, 2L, 1L), shown)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue(msg, msg.contains("rate-limiting"))
        assertTrue(msg, !msg.contains("Could not connect"))
    }

    @Test fun countdownStopsOnSuccess() = runBlocking {
        RateLimitRetry.tick = {}
        var attempts = 0
        var retried = 0
        val r = RateLimitRetry.run(onCountdown = {}, onRetry = { retried++ }) {
            attempts++
            if (attempts == 1) Result.failure(DispatcharrError.RateLimited(1)) else Result.success(7)
        }
        assertEquals(7, r.getOrNull())
        assertEquals(2, attempts)
        assertEquals(1, retried)
    }

    @Test fun otherFailuresAreNotRetried() = runBlocking {
        var attempts = 0
        val r = RateLimitRetry.run<Int>(onCountdown = {}) {
            attempts++
            Result.failure(DispatcharrError.Unauthorized("HTTP 401"))
        }
        assertEquals(1, attempts)
        assertTrue(r.exceptionOrNull() is DispatcharrError.Unauthorized)
    }

    @Test fun countdownCopy() {
        assertEquals("The server is busy. Trying again in 45 seconds.", serverBusyCountdown(45))
        assertEquals("The server is busy. Trying again in 1 second.", serverBusyCountdown(1))
    }
}
