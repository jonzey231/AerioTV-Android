package com.aeriotv.android.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multiview tile 503 decisions, the walk's step budget, and the empty-session
 *  streak (Apple tile parity: 6c5b9c2, 65c0663, 890f521, 5267c55). */
class TileLive503PolicyTest {

    private val noHeaders = emptyMap<String, List<String>>()

    private fun stopping(retryAfterMs: Long = 0L) = Dispatcharr503.Info(
        kind = Dispatcharr503.Kind.STOPPING,
        reason = "Channel is stopping, retry shortly",
        retryAfterMs = retryAfterMs,
    )

    // ---- reason parsing (same parse as the live holder) ----

    @Test fun stoppingBodyParsesAsStopping() {
        val info = Dispatcharr503.infoFrom(
            """{"error": "Channel is stopping, retry shortly"}""",
            mapOf("Retry-After" to listOf("1")),
        )
        assertEquals(Dispatcharr503.Kind.STOPPING, info.kind)
        assertEquals("Channel is stopping, retry shortly", info.reason)
        assertEquals(1_000L, info.retryAfterMs)
    }

    @Test fun noStreamsBodyParsesAsUnavailableVerbatim() {
        val info = Dispatcharr503.infoFrom("""{"error": "no available streams for this channel."}""", noHeaders)
        assertEquals(Dispatcharr503.Kind.UNAVAILABLE, info.kind)
        assertEquals("No available streams for this channel", info.reason)
        assertEquals(0L, info.retryAfterMs)
    }

    @Test fun bodyWithoutErrorFieldIsQuotedNotGuessed() {
        val info = Dispatcharr503.infoFrom("Upstream timed out", noHeaders)
        assertEquals("Upstream timed out", info.reason)
        assertEquals(Dispatcharr503.Kind.UNAVAILABLE, info.kind)
        assertEquals("Channel unavailable", Dispatcharr503.infoFrom("", noHeaders).reason)
    }

    @Test fun statusLineQuotesTheServer() {
        assertEquals(
            "Server: No available streams for this channel. Trying another stream...",
            LiveStreamFailover.serverStatusText("no available streams for this channel..", "Trying another stream..."),
        )
        assertEquals("Reconnecting...", LiveStreamFailover.serverStatusText(null, "Reconnecting..."))
        assertEquals("Reconnecting...", LiveStreamFailover.serverStatusText("  ", "Reconnecting..."))
    }

    // ---- tile decisions ----

    @Test fun unavailableWalksAtOnce() {
        val info = Dispatcharr503.infoFrom("""{"error": "Channel resources unavailable"}""", noHeaders)
        assertEquals(TileLive503Policy.Action.Walk, TileLive503Policy.decide(info, 0, 0L, 0L, 1_000_000L))
    }

    @Test fun stoppingLadderIsOneTwoFourEightThenEight() {
        var retries = 0
        var waited = 0L
        val delays = mutableListOf<Long>()
        while (true) {
            val a = TileLive503Policy.decide(stopping(), retries, waited, 0L, 1_000_000L)
            if (a !is TileLive503Policy.Action.SameUrlRetry) {
                assertEquals(TileLive503Policy.Action.Walk, a)
                break
            }
            assertFalse(a.drainingOwn)
            delays += a.delayMs
            retries = a.attempt
            waited = a.waitedMs
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 5_000L), delays)
        assertEquals(TileLive503Policy.STOPPING_BUDGET_MS, waited)
    }

    @Test fun retryAfterIsAFloorClampedToThreeSeconds() {
        val a = TileLive503Policy.decide(stopping(retryAfterMs = 60_000L), 0, 0L, 0L, 1_000_000L)
        assertEquals(3_000L, (a as TileLive503Policy.Action.SameUrlRetry).delayMs)
        val b = TileLive503Policy.decide(stopping(retryAfterMs = 2_000L), 0, 0L, 0L, 1_000_000L)
        assertEquals(2_000L, (b as TileLive503Policy.Action.SameUrlRetry).delayMs)
    }

    @Test fun ownReleaseWaitsFortyFiveSecondsThenStandingRetryNotWalk() {
        val now = 1_000_000L
        val releasedAt = now - 5_000L
        var retries = 0
        var waited = 0L
        while (true) {
            val a = TileLive503Policy.decide(stopping(), retries, waited, releasedAt, now)
            if (a !is TileLive503Policy.Action.SameUrlRetry) {
                assertEquals(TileLive503Policy.Action.StandingRetry, a)
                break
            }
            assertTrue(a.drainingOwn)
            assertEquals(TileLive503Policy.SELF_RELEASE_STOPPING_BUDGET_MS, a.budgetMs)
            retries = a.attempt
            waited = a.waitedMs
        }
        assertEquals(TileLive503Policy.SELF_RELEASE_STOPPING_BUDGET_MS, waited)
    }

    @Test fun ownReleaseWindowIsNinetySeconds() {
        val now = 1_000_000L
        assertTrue(TileLive503Policy.drainingOwn(now - 89_999L, now))
        assertFalse(TileLive503Policy.drainingOwn(now - 90_000L, now))
        assertFalse(TileLive503Policy.drainingOwn(0L, now))
    }

    @Test fun standingRetryIsFifteenThirtyThenSixty() {
        assertEquals(15_000L, TileLive503Policy.standingRetryDelayMs(1))
        assertEquals(30_000L, TileLive503Policy.standingRetryDelayMs(2))
        assertEquals(60_000L, TileLive503Policy.standingRetryDelayMs(3))
        assertEquals(60_000L, TileLive503Policy.standingRetryDelayMs(9))
    }

    // ---- walk step budget ----

    @Test fun walkCapableIngestTimeoutIs134Seconds() {
        assertEquals(28_000L, LiveStreamFailover.FIRST_TUNE_FIRST_BYTE_MS)
        assertEquals(12_000L, LiveStreamFailover.STEP_FIRST_BYTE_MS)
        assertEquals(134_000L, LiveStreamFailover.WALK_INGEST_TIMEOUT_MS)
    }

    // ---- empty-session streak ----

    @Test fun sixEmptySessionsStopReconnects() {
        var run = 0
        repeat(5) {
            run = StreamEndVerifier.emptyRunAfter(run, 188L)
            assertFalse(StreamEndVerifier.stopsReconnects(run))
        }
        run = StreamEndVerifier.emptyRunAfter(run, 188L)
        assertEquals(6, run)
        assertTrue(StreamEndVerifier.stopsReconnects(run))
    }

    @Test fun aDeliveringSessionResetsTheStreak() {
        var run = StreamEndVerifier.emptyRunAfter(4, 0L)
        assertEquals(5, run)
        run = StreamEndVerifier.emptyRunAfter(run, StreamEndVerifier.EMPTY_SESSION_BYTES)
        assertEquals(0, run)
    }
}
