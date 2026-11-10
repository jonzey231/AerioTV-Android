package com.aeriotv.android.core.playback

import com.aeriotv.android.core.network.DispatcharrRequestGate
import com.aeriotv.android.core.network.RateLimitReason
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Stream 429s (shared spec 2026-11-10): classification of each body shape,
 * and a local stand-in server for the stream URL showing that a lockout
 * pauses the gate and no further stream request reaches the network while it
 * is paused, while a stream limit passes through untouched and never pauses.
 * Nothing here touches a real Dispatcharr.
 */
class StreamGateTest {
    private lateinit var server: HttpServer
    private val hits = AtomicInteger(0)
    @Volatile private var status = 200
    @Volatile private var body = ""
    @Volatile private var retryAfter: String? = null
    private val logs = mutableListOf<String>()

    private val client = OkHttpClient.Builder().addInterceptor(StreamGate.interceptor).build()
    private fun streamUrl() = "http://127.0.0.1:${server.address.port}/proxy/ts/stream/test-channel"

    @Before fun setUp() {
        DispatcharrRequestGate.resetForTests()
        DispatcharrConnectionLimit.directConnectActive = true
        StreamGate.log = { synchronized(logs) { logs += it } }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            retryAfter?.let { ex.responseHeaders.add("Retry-After", it) }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.start()
    }

    @After fun tearDown() {
        server.stop(0)
        DispatcharrRequestGate.resetForTests()
        DispatcharrConnectionLimit.directConnectActive = false
    }

    private fun get(): okhttp3.Response = client.newCall(Request.Builder().url(streamUrl()).build()).execute()

    // ---- pure classification ----

    @Test fun lockoutBodies() {
        val withSuffix = StreamGate.classify(429, """{"detail":"Too many API key attempts. Try again later. Expected available in 42 seconds."}""", "42")!!
        assertEquals(StreamGate.Kind.Lockout, withSuffix.kind)
        assertEquals(42L, withSuffix.seconds)
        assertEquals(RateLimitReason.ApiKeyLockout, withSuffix.reason)
        val plain = StreamGate.classify(429, """{"detail":"Too many API key attempts. Try again later."}""", null)!!
        assertEquals(DispatcharrRequestGate.DEFAULT_RETRY_AFTER_S, plain.seconds)
        assertEquals(RateLimitReason.ApiKeyLockout, plain.reason)
    }

    @Test fun streamLimitBodies() {
        val json = StreamGate.classify(429, """{"error": "Stream limit exceeded (2 concurrent streams allowed)"}""", null)!!
        assertEquals(StreamGate.Kind.StreamLimit, json.kind)
        assertEquals("Stream limit exceeded (2 concurrent streams allowed)", json.message)
        // Timeshift: 403 with a plain-text body.
        val timeshift = StreamGate.classify(403, "Stream limit exceeded", null)!!
        assertEquals(StreamGate.Kind.StreamLimit, timeshift.kind)
    }

    @Test fun anyOther429IsTreatedAsTheLockoutWithReasonOther() {
        val r = StreamGate.classify(429, "<html>Too Many Requests</html>", "10")!!
        assertEquals(StreamGate.Kind.Lockout, r.kind)
        assertEquals(RateLimitReason.Other, r.reason)
        assertEquals(10L, r.seconds)
    }

    @Test fun otherStatusesAreNotRefusals() {
        assertNull(StreamGate.classify(503, """{"error":"Channel is stopping, retry shortly"}""", "1"))
        assertNull(StreamGate.classify(403, "Forbidden", null))
        assertNull(StreamGate.classify(200, "", null))
    }

    // ---- stand-in server ----

    @Test fun lockoutPausesTheGateAndNoStreamOpensWhilePaused() {
        status = 429
        body = """{"detail":"Too many API key attempts. Try again later. Expected available in 30 seconds."}"""
        retryAfter = "30"
        try {
            get().close()
            fail("a lockout 429 must surface as StreamRateLimitedException")
        } catch (e: StreamRateLimitedException) {
            assertEquals(30L, e.seconds)
            assertEquals(RateLimitReason.ApiKeyLockout, e.reason)
        }
        assertEquals(1, hits.get())
        assertTrue(StreamGate.pausedSeconds(streamUrl()) in 29..30)
        // Every later open (failover, Media3, a retry) is refused locally.
        status = 200
        repeat(5) {
            try {
                get().close()
                fail("no stream may open while the gate is paused")
            } catch (e: StreamRateLimitedException) {
                assertEquals(RateLimitReason.ApiKeyLockout, e.reason)
            }
        }
        assertEquals("no request reached the server while paused", 1, hits.get())
        // The API calls are paused by the same gate.
        assertTrue(DispatcharrRequestGate.pausedSeconds(DispatcharrRequestGate.serverKey(streamUrl())) > 0)
    }

    @Test fun theOpenGoesOutAgainOnceThePauseEnds() {
        var now = 1_000_000L
        DispatcharrRequestGate.clock = { now }
        try {
            status = 429
            body = """{"detail":"Too many API key attempts. Try again later."}"""
            retryAfter = "5"
            runCatching { get().close() }
            assertEquals(1, hits.get())
            now += 5_001
            status = 200
            body = "ts"
            retryAfter = null
            get().use { assertEquals(200, it.code) }
            assertEquals(2, hits.get())
        } finally {
            DispatcharrRequestGate.clock = { System.nanoTime() / 1_000_000 }
        }
    }

    @Test fun streamLimitPassesThroughAndNeverPauses() {
        status = 429
        body = """{"error": "Stream limit exceeded (2 concurrent streams allowed)"}"""
        get().use { resp ->
            assertEquals(429, resp.code)
            val notice = DispatcharrConnectionLimit.fromResponse(resp.code, resp.body!!.string())
            assertNotNull(notice)
            assertEquals(DispatcharrConnectionLimit.Kind.USER_STREAM_LIMIT, notice!!.kind)
            assertEquals("Dispatcharr stream limit reached: 2 concurrent streams allowed for this account.", notice.message)
        }
        assertEquals(0L, StreamGate.pausedSeconds(streamUrl()))
        // Not paused: the next open still reaches the server.
        get().close()
        assertEquals(2, hits.get())
    }

    @Test fun unknown429WithoutRetryAfterWaitsTheGateDefault() {
        status = 429
        body = "Too Many Requests"
        retryAfter = null
        try {
            get().close()
            fail("expected StreamRateLimitedException")
        } catch (e: StreamRateLimitedException) {
            assertEquals(DispatcharrRequestGate.DEFAULT_RETRY_AFTER_S, e.seconds)
            assertEquals(RateLimitReason.Other, e.reason)
        }
    }

    @Test fun nonDispatcharrPlaylistsKeepTheirOldHandling() {
        DispatcharrConnectionLimit.directConnectActive = false
        status = 429
        body = """{"detail":"Too many API key attempts. Try again later."}"""
        get().use { assertEquals(429, it.code) }
        assertEquals(0L, StreamGate.pausedSeconds(streamUrl()))
    }

    @Test fun anApi429AlsoBlocksStreams() {
        DispatcharrRequestGate.pause(DispatcharrRequestGate.serverKey(streamUrl()), 20, RateLimitReason.ApiKeyLockout)
        try {
            get().close()
            fail("expected StreamRateLimitedException")
        } catch (e: StreamRateLimitedException) {
            assertTrue(e.seconds in 19..20)
        }
        assertEquals(0, hits.get())
    }

    @Test fun findWalksTheCauseChainAndPoliciesStop() {
        val e = StreamRateLimitedException(12, RateLimitReason.ApiKeyLockout)
        val wrapped = RuntimeException("load", java.io.IOException("io", e))
        assertEquals(e, StreamGate.find(wrapped))
        assertTrue(StreamGate.noRetry(wrapped))
        assertNull(StreamGate.find(java.io.IOException("plain")))
    }

    @Test fun waitSecondsUsesWhatIsLeftOnThePause() {
        DispatcharrRequestGate.pause(DispatcharrRequestGate.serverKey(streamUrl()), 40, RateLimitReason.ApiKeyLockout)
        val w = StreamGate.waitSeconds(streamUrl(), StreamRateLimitedException(5))
        assertTrue(w in 39..40)
        assertEquals(1L, StreamGate.waitSeconds(null, StreamRateLimitedException(0)))
    }

    @Test fun playerCardCopy() {
        val c = StreamGate.countdownNotice(42, RateLimitReason.ApiKeyLockout)
        assertEquals(DispatcharrConnectionLimit.Kind.RATE_LIMITED, c.kind)
        assertEquals(
            "Dispatcharr rate-limited this network after too many failed API key attempts. Trying again in 42 seconds.",
            c.message,
        )
        assertEquals(
            "Dispatcharr rate-limited this network (HTTP 429). Wait 1 second and try again.",
            StreamGate.finalNotice(1, RateLimitReason.Other).message,
        )
    }

    @Test fun logsNameTheReasonNeverTheKeyOrHost() {
        status = 429
        body = """{"detail":"Too many API key attempts. Try again later."}"""
        retryAfter = "9"
        runCatching { get().close() }
        val joined = synchronized(logs) { logs.joinToString("\n") }
        assertTrue(joined, joined.contains("reason=ApiKeyLockout"))
        assertTrue(joined, !joined.contains("127.0.0.1"))
    }
}
