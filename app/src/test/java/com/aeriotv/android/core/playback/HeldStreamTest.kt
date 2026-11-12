package com.aeriotv.android.core.playback

import com.aeriotv.android.core.network.DispatcharrRequestGate
import com.aeriotv.android.core.network.heldRouteRefusal
import com.aeriotv.android.core.profiles.ProfileHostHold
import com.aeriotv.android.core.profiles.ProfileScope
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Item 15 (parity, 2026-11-11): a held Child's lists and its streams to the
 * playlist's moved address both show the existing line "Could not reach your
 * Dispatcharr server". A local stand-in server shows that no stream request
 * reaches the network while the address is held. Nothing here touches a real
 * Dispatcharr.
 */
class HeldStreamTest {
    private lateinit var server: HttpServer
    private val hits = AtomicInteger(0)
    private val client = OkHttpClient.Builder().addInterceptor(StreamGate.interceptor).build()
    private fun base() = "http://127.0.0.1:${server.address.port}"
    private fun streamUrl() = "${base()}/proxy/ts/stream/test-channel"

    @Before fun setUp() {
        DispatcharrRequestGate.resetForTests()
        StreamGate.log = {}
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
    }

    @After fun tearDown() {
        server.stop(0)
        ProfileScope.sealed = emptySet()
        ProfileScope.sealedRoutes = emptySet()
        ProfileScope.held = emptySet()
        DispatcharrRequestGate.resetForTests()
    }

    private fun holdChildHere() {
        ProfileScope.sealed = setOf("kid")
        ProfileScope.sealedRoutes = ProfileHostHold.routes(base(), null)
        ProfileScope.held = setOf("kid")
    }

    @Test fun theListAndTheStreamShowTheSameLine() {
        holdChildHere()
        val list = heldRouteRefusal("${base()}/api/channels/channels/", bypass = false)
        assertNotNull(list)
        assertEquals("Could not reach your Dispatcharr server", list!!.message)
        assertEquals(list.message, DispatcharrConnectionLimit.heldNotice(StreamRouteHeldException()).message)
        assertTrue(DispatcharrConnectionLimit.ROUTE_HELD.title.isBlank())
        // The same-server check and the held sign-in form still get through.
        assertNull(heldRouteRefusal("${base()}/api/accounts/users/me/", bypass = true))
    }

    @Test fun aHeldStreamNeverReachesTheServerAndStopsWithoutRetry() {
        holdChildHere()
        try {
            client.newCall(Request.Builder().url(streamUrl()).build()).execute().close()
            fail("expected the open to be refused")
        } catch (e: IOException) {
            val held = StreamAuth.find(e)
            assertTrue(held is StreamRouteHeldException)
            assertEquals("Could not reach your Dispatcharr server", held!!.message)
            // Media3 never re-GETs it, and the player shows the held card.
            assertTrue(StreamGate.noRetry(e))
            assertSame(DispatcharrConnectionLimit.ROUTE_HELD, DispatcharrConnectionLimit.heldNotice(held))
        }
        assertEquals(0, hits.get())
    }

    @Test fun otherCasesKeepTheirCopy() {
        // A rejected key still shows "API key no longer valid".
        assertSame(DispatcharrConnectionLimit.KEY_REJECTED, DispatcharrConnectionLimit.heldNotice(StreamKeyRejectedException()))
        // No hold: lists and streams go out as usual.
        assertNull(heldRouteRefusal("${base()}/api/channels/channels/", bypass = false))
        client.newCall(Request.Builder().url(streamUrl()).build()).execute().use { assertEquals(200, it.code) }
        assertEquals(1, hits.get())
    }
}
