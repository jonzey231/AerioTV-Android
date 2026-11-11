package com.aeriotv.android.core.playback

import com.aeriotv.android.core.network.DispatcharrRequestGate
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Stream 401s (Logan 2026-11-10): a local stand-in for the stream URL answers
 * 401 `{"detail":"Invalid API key"}` unless the request carries the good key.
 * The broker is a stub. Shows that a 401 reaches the broker once for every
 * stream that hits it, that a held key is never sent again (no request at all per
 * open), that a re-sign-in retries the open once with the new key and every
 * later open built with the old key goes out with the new one, and that a key
 * the probe accepts leaves the 401 alone and is not probed again at once.
 * Nothing here touches a real Dispatcharr.
 */
class StreamAuthTest {
    private lateinit var server: HttpServer
    /** The X-API-Key each request carried ("" = none). */
    private val sentKeys = CopyOnWriteArrayList<String>()
    @Volatile private var goodKey = "GOOD"
    private val brokerCalls = AtomicInteger(0)
    private var now = 1_000_000L

    private val client = OkHttpClient.Builder().addInterceptor(StreamGate.interceptor).build()
    private fun streamUrl() = "http://127.0.0.1:${server.address.port}/proxy/ts/stream/test-channel"

    @Before fun setUp() {
        DispatcharrRequestGate.resetForTests()
        StreamAuth.resetForTests()
        StreamAuth.clock = { now }
        StreamAuth.log = {}
        StreamGate.log = {}
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { ex ->
            val key = ex.requestHeaders.getFirst("X-API-Key").orEmpty()
            sentKeys += key
            val (status, body) = if (key == goodKey) 200 to "ts" else 401 to """{"detail":"Invalid API key"}"""
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After fun tearDown() {
        server.stop(0)
        DispatcharrRequestGate.resetForTests()
        StreamAuth.resetForTests()
    }

    private fun open(key: String?): okhttp3.Response {
        val b = Request.Builder().url(streamUrl())
        if (key != null) StreamAuth.authHeaders(key).forEach { (k, v) -> b.header(k, v) }
        return client.newCall(b.build()).execute()
    }

    private fun expectStopped(key: String?) {
        try {
            open(key).close()
            fail("the stream should stop")
        } catch (e: StreamKeyRejectedException) {
            // expected
        }
    }

    // ---- pure pieces ----

    @Test fun theKeyIsReadFromEitherHeader() {
        assertEquals("k1", StreamAuth.keyOf(mapOf("x-api-key" to "k1")))
        assertEquals("k2", StreamAuth.keyOf(mapOf("Authorization" to "ApiKey k2")))
        assertNull(StreamAuth.keyOf(mapOf("Authorization" to "Bearer jwt")))
        assertNull(StreamAuth.keyOf(mapOf("User-Agent" to "x")))
        assertEquals(StreamAuth.Rewrite.Keep, StreamAuth.rewriteFor(null))
        assertEquals(StreamAuth.Rewrite.Keep, StreamAuth.rewriteFor("k1"))
    }

    // ---- stand-in server ----

    @Test fun aRejectedKeyGoesToTheBrokerOnceIsHeldAndNeverSentAgain() {
        StreamAuth.handler = { key ->
            brokerCalls.incrementAndGet()
            // What the broker does on a rejected key with no password: hold it (and prompt).
            DispatcharrRequestGate.markKeyRejected("p1", key, "Home")
            StreamAuth.Outcome.Held
        }
        expectStopped("DEAD")
        assertEquals(1, brokerCalls.get())
        assertEquals("Home", DispatcharrRequestGate.prompt.value?.playlistName)
        // Every later open (a reconnect, a retry, another tile) built with the
        // dead key stops before any request: not even a keyless one goes out,
        // and the broker is not asked again.
        repeat(9) { expectStopped("DEAD") }
        assertEquals(1, brokerCalls.get())
        assertEquals("only the first open reached the server", listOf("DEAD"), sentKeys.toList())
    }

    @Test fun afterARelaunchReHeldTheKeyNoOpenReachesTheServer() {
        // The hold is memory only: after a relaunch the first API call (or stream)
        // with the dead key is refused and the broker holds it again. From then on
        // the playlist's stream headers still carry the key, so every open is
        // recognized and stopped locally (2026-11-11: it used to go out keyless,
        // get a plain 401, and sit in the reconnect loop).
        StreamAuth.handler = { brokerCalls.incrementAndGet(); StreamAuth.Outcome.Held }
        DispatcharrRequestGate.markKeyRejected("p1", "DEAD", "Home")
        val playlist = com.aeriotv.android.core.data.db.entity.PlaylistEntity(
            id = "p1", name = "Home", urlString = "http://127.0.0.1:1",
            sourceType = com.aeriotv.android.core.data.SourceType.DispatcharrApiKey.name, apiKey = "DEAD",
        )
        val headers = com.aeriotv.android.core.network.PlaybackHeaders.forPlaylist(playlist)
        assertEquals("the held key stays in the stream headers", "DEAD", StreamAuth.keyOf(headers))
        repeat(5) {
            val b = Request.Builder().url(streamUrl())
            headers.forEach { (k, v) -> b.header(k, v) }
            try {
                client.newCall(b.build()).execute().close()
                fail("the open should stop before the network")
            } catch (_: StreamKeyRejectedException) {
            }
        }
        assertEquals("nothing reached the server", 0, sentKeys.size)
        assertEquals("no probe for a key already held", 0, brokerCalls.get())
    }

    @Test fun aHeldKeyProvenGoodAgainPlaysWithTheSameHeaders() {
        goodKey = "BACK"
        DispatcharrRequestGate.markKeyRejected("p1", "BACK", "Home")
        expectStopped("BACK")
        assertEquals(0, sentKeys.size)
        // The automatic re-check (or a sign-in) proved it: the next open goes out with it.
        DispatcharrRequestGate.clearKey("BACK")
        open("BACK").use { assertEquals(200, it.code) }
        assertEquals(listOf("BACK"), sentKeys.toList())
    }

    @Test fun aHeldKeyWithAReplacementGoesOutAsTheNewKey() {
        DispatcharrRequestGate.markKeyRejected("p1", "OLD", "Home")
        DispatcharrRequestGate.noteKeyReplaced("OLD", "GOOD")
        open("OLD").use { assertEquals(200, it.code) }
        assertEquals(listOf("GOOD"), sentKeys.toList())
    }

    @Test fun streamsThatHitTheSame401TogetherShareOneBrokerCall() {
        val release = CountDownLatch(1)
        StreamAuth.handler = { key ->
            brokerCalls.incrementAndGet()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { release.await(5, TimeUnit.SECONDS) }
            DispatcharrRequestGate.markKeyRejected("p1", key)
            StreamAuth.Outcome.Held
        }
        val pool = Executors.newFixedThreadPool(6)
        val stopped = AtomicInteger(0)
        val done = CountDownLatch(6)
        repeat(6) {
            pool.execute {
                try { open("DEAD").close() } catch (_: StreamKeyRejectedException) { stopped.incrementAndGet() } catch (_: Exception) {}
                done.countDown()
            }
        }
        Thread.sleep(300)
        release.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(1, brokerCalls.get())
        assertEquals(6, stopped.get())
    }

    @Test fun aReSignInRetriesTheOpenOnceAndLaterOpensUseTheNewKey() {
        StreamAuth.handler = { old ->
            brokerCalls.incrementAndGet()
            // The broker re-signed in (a stored password) and the server issued GOOD.
            DispatcharrRequestGate.noteKeyReplaced(old, "GOOD")
            StreamAuth.Outcome.Retry("GOOD")
        }
        open("OLD").use { assertEquals(200, it.code) }
        assertEquals(listOf("OLD", "GOOD"), sentKeys.toList())
        // A reconnect built with the captured old key goes out with the new one.
        open("OLD").use { assertEquals(200, it.code) }
        assertEquals(listOf("OLD", "GOOD", "GOOD"), sentKeys.toList())
        assertEquals(1, brokerCalls.get())
    }

    @Test fun aKeyTheProbeAcceptsLeavesThe401AloneAndIsNotProbedAgainAtOnce() {
        StreamAuth.handler = { brokerCalls.incrementAndGet(); StreamAuth.Outcome.NotKey }
        open("ODD").use { assertEquals(401, it.code) }
        open("ODD").use { assertEquals(401, it.code) }
        assertEquals(1, brokerCalls.get())
        now += StreamAuth.NOT_KEY_COOLDOWN_MS + 1
        open("ODD").use { assertEquals(401, it.code) }
        assertEquals(2, brokerCalls.get())
    }

    @Test fun anOpenWithoutAKeyNeverAsksTheBroker() {
        StreamAuth.handler = { brokerCalls.incrementAndGet(); StreamAuth.Outcome.Held }
        open(null).use { assertEquals(401, it.code) }
        assertEquals(0, brokerCalls.get())
    }

    @Test fun aGoodKeyPlaysUntouched() {
        StreamAuth.handler = { brokerCalls.incrementAndGet(); StreamAuth.Outcome.Held }
        open("GOOD").use { assertEquals(200, it.code) }
        assertEquals(0, brokerCalls.get())
        assertEquals(listOf("GOOD"), sentKeys.toList())
    }

    @Test fun mediaRetryPoliciesNeverRetryAHeldKey() {
        assertTrue(StreamGate.noRetry(java.io.IOException("wrapped", StreamKeyRejectedException())))
    }
}
