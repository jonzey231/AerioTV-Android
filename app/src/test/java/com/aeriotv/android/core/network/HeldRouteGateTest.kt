package com.aeriotv.android.core.network

import com.aeriotv.android.core.profiles.ProfileHostHold
import com.aeriotv.android.core.profiles.ProfileScope
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * While a profile is held after a server change, no request of any kind goes
 * to the held addresses (2026-11-11): a local stand-in server shows that a
 * logo (or a guide download, the redirect read, the XC bridge) never reaches
 * it, and that other addresses are untouched. Nothing here touches a real
 * Dispatcharr.
 */
class HeldRouteGateTest {
    private lateinit var server: HttpServer
    private val hits = AtomicInteger(0)
    private val client = OkHttpClient.Builder().addInterceptor(HeldRouteGate.interceptor).build()
    private fun base() = "http://127.0.0.1:${server.address.port}"

    @Before fun setUp() {
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
    }

    private fun hold() {
        ProfileScope.sealed = setOf("kid")
        ProfileScope.sealedRoutes = ProfileHostHold.routes(base(), null)
        ProfileScope.held = setOf("kid")
    }

    @Test fun aLogoToAHeldAddressNeverGoesOut() {
        hold()
        assertTrue(HeldRouteGate.refuses("${base()}/api/channels/logos/12/cache/"))
        try {
            client.newCall(Request.Builder().url("${base()}/api/channels/logos/12/cache/").build()).execute().close()
            fail("expected the request to be refused")
        } catch (e: HeldRouteGate.Refused) {
            assertEquals("Could not reach your Dispatcharr server", e.message)
        }
        assertEquals(0, hits.get())
    }

    @Test fun otherAddressesAndNoHoldAreUntouched() {
        hold()
        // A logo CDN is another address: it goes out.
        assertFalse(HeldRouteGate.refuses("https://logos.example.test/a.png"))
        ProfileScope.sealedRoutes = emptySet()
        client.newCall(Request.Builder().url("${base()}/api/channels/logos/12/cache/").build()).execute().use { assertEquals(200, it.code) }
        assertEquals(1, hits.get())
    }
}
