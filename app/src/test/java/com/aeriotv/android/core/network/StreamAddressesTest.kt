package com.aeriotv.android.core.network

import com.aeriotv.android.core.playback.PlaybackHttp
import com.aeriotv.android.core.playback.StreamGate
import com.aeriotv.android.core.playback.StreamRouteHeldException
import com.aeriotv.android.core.profiles.ProfileHostHold
import com.aeriotv.android.core.profiles.ProfileScope
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Stream requests from before a playlist moved (StreamAddresses). A playlist
 * is moved from dispatcharr-old.test to dispatcharr.test on a loopback
 * stand-in (two servers, the clients' DNS maps every *.test name to
 * 127.0.0.1), then a channel row cached before the move is played. Each
 * server records every request: path, Host, whether each sign-in header was
 * present, and WHICH fake key it was by label (never the value). The old
 * address must get nothing, or no key. Fake credentials only.
 */
class StreamAddressesTest {

    data class Hit(val path: String, val hostPort: String, val present: Map<String, Boolean>, val key: String) {
        override fun toString() = "host=$hostPort $path " +
            RedirectAuthRule.HEADERS.joinToString(" ") { "$it=${if (present[it] == true) "present" else "absent"}" } +
            " key=$key"
    }

    private lateinit var old: HttpServer
    private lateinit var new: HttpServer
    private val oldHits = CopyOnWriteArrayList<Hit>()
    private val newHits = CopyOnWriteArrayList<Hit>()
    private val gateLog = CopyOnWriteArrayList<String>()

    private val dns = Dns { host ->
        if (host.endsWith(".test")) listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
        else Dns.SYSTEM.lookup(host)
    }

    private fun label(key: String?): String = when (key) {
        null -> "none"
        OWN -> "own"
        TEEN -> "teen"
        KID -> "kid"
        OTHER -> "other"
        else -> "unknown"
    }

    private fun server(hits: MutableList<Hit>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(4)
            createContext("/") { ex ->
                val h = ex.requestHeaders
                hits += Hit(
                    path = ex.requestURI.path,
                    hostPort = h.getFirst("Host").orEmpty(),
                    present = RedirectAuthRule.HEADERS.associateWith { h.getFirst(it) != null },
                    key = label(h.getFirst("X-API-Key")),
                )
                val body = "ok".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }

    private val oldBase get() = "http://dispatcharr-old.test:${old.address.port}"
    private val newBase get() = "http://dispatcharr.test:${new.address.port}"

    @Before fun setUp() {
        StreamAddresses.resetForTests()
        StreamAddresses.log = { gateLog += it }
        RedirectAuthRule.log = {}
        StreamGate.log = {}
        old = server(oldHits)
        new = server(newHits)
    }

    @After fun tearDown() {
        old.stop(0)
        new.stop(0)
        StreamAddresses.resetForTests()
        ProfileScope.held = emptySet()
        ProfileScope.sealed = emptySet()
        ProfileScope.sealedRoutes = emptySet()
        ProfileScope.activate(null)
    }

    /** The playlist after its move: Server URL on dispatcharr.test, keys own and teen. */
    private fun moved(lanUrl: String? = null) = StreamAddresses.update(
        listOf(
            StreamAddresses.Entry(
                playlistId = "p1", serverUrl = newBase, lanUrl = lanUrl,
                keys = setOf(OWN, TEEN, KID), active = true, ownKey = OWN,
            ),
        ),
    )

    /** Shaped like every stream client: the gate first, the redirect rule last. */
    private fun streamClient(): OkHttpClient = OkHttpClient.Builder().dns(dns)
        .let(StreamAddresses::install).let(RedirectAuthRule::install).build()

    private fun open(client: OkHttpClient, url: String, key: String?) {
        val b = Request.Builder().url(url)
        if (key != null) {
            b.header("X-API-Key", key)
            b.header("Authorization", "ApiKey $key")
        }
        client.newCall(b.build()).execute().use { it.body.string() }
    }

    private fun measure(label: String) {
        println("[MEASURE] $label: old address ${if (oldHits.isEmpty()) "got nothing" else oldHits.joinToString("; ")}" +
            " | new address ${if (newHits.isEmpty()) "got nothing" else newHits.joinToString("; ")}")
    }

    private fun overlay(id: String, key: String) = ProfileScope.Overlay(
        playlistId = "p1", profileId = id, apiKey = key, username = id, password = null,
        userLevel = 1, dvrAccess = "none", catchupEnabled = true, vodMoviesEnabled = true,
        vodSeriesEnabled = true, accountProfileIds = "", isStaff = false, isSuperuser = false,
        customProperties = "", capabilitiesFetchedAt = 0L, capabilitiesSchema = 0, capabilitiesStale = false,
    )

    // ---- the decision, no network ----

    @Test fun theServersOwnPathIsTheTailFromProxyOrLive() {
        assertEquals("/proxy/ts/stream/abc", StreamAddresses.serverPath("http://h:1/proxy/ts/stream/abc".toHttpUrl()))
        assertEquals("/proxy/ts/stream/abc?x=1", StreamAddresses.serverPath("https://h/base/proxy/ts/stream/abc?x=1".toHttpUrl()))
        assertEquals("/live/u/p/7.ts", StreamAddresses.serverPath("http://h/live/u/p/7.ts".toHttpUrl()))
        assertEquals(null, StreamAddresses.serverPath("http://h/some/provider/path".toHttpUrl()))
    }

    @Test fun aRequestWithNoPlaylistsSignInIsNotJudged() {
        moved()
        assertEquals(StreamAddresses.Decision.Send, StreamAddresses.decide("$oldBase/proxy/ts/stream/abc".toHttpUrl(), null))
        assertEquals(StreamAddresses.Decision.Send, StreamAddresses.decide("$oldBase/proxy/ts/stream/abc".toHttpUrl(), "unrelated"))
    }

    @Test fun aRebuiltOpenStillAdoptsTheServersOwnHlsSession() {
        moved()
        // The open was rebuilt from the old address; the server's session is on the new one.
        assertTrue(
            com.aeriotv.android.core.playback.ForceHls.isServerSession(
                "$oldBase/proxy/ts/stream/abc", "$newBase/proxy/hls/tok/index.m3u8",
            ),
        )
        // A provider's session-shaped URL is never the server's.
        assertTrue(
            !com.aeriotv.android.core.playback.ForceHls.isServerSession(
                "$oldBase/proxy/ts/stream/abc", "http://provider.test:1/proxy/hls/tok/index.m3u8",
            ),
        )
    }

    // ---- the stand-in ----

    @Test fun aRowCachedBeforeTheMoveIsRebuiltOnTheNewAddress() {
        moved()
        open(streamClient(), "$oldBase/proxy/ts/stream/abc", OWN)
        measure("stream client, row cached before the move, /proxy/ path")
        assertTrue("the old address got nothing", oldHits.isEmpty())
        val hit = newHits.single()
        assertEquals("/proxy/ts/stream/abc", hit.path)
        assertEquals("dispatcharr.test:${new.address.port}", hit.hostPort)
        assertTrue(hit.present["X-API-Key"] == true && hit.present["Authorization"] == true)
        assertEquals("own", hit.key)
        assertTrue(gateLog.any { it.contains("rebuilt") })
    }

    @Test fun thePlaybackClientRebuildsTooAndTheCurrentAddressGoesAsItIs() {
        moved()
        val c = PlaybackHttp.client(30_000, 30_000).newBuilder().dns(dns).build()
        open(c, "$oldBase/proxy/vod/movie/m1", OWN)
        open(c, "$newBase/proxy/ts/stream/now", OWN)
        measure("PlaybackHttp client, cached /proxy/ URL, then the current address")
        assertTrue(oldHits.isEmpty())
        assertEquals(listOf("/proxy/vod/movie/m1", "/proxy/ts/stream/now"), newHits.map { it.path })
        assertTrue(newHits.all { it.key == "own" })
    }

    @Test fun aPathThatIsNotTheServersOwnGoesWithoutTheKey() {
        moved()
        open(streamClient(), "$oldBase/some/provider/path.ts", OWN)
        measure("stream client, cached URL, not the server's own path")
        val hit = oldHits.single()
        assertTrue("no sign-in header reached the old address", hit.present.values.none { it })
        assertTrue(newHits.isEmpty())
    }

    @Test fun aHeldProfilesKeyNeverGoesToTheNewAddress() {
        moved()
        // Teen is the active profile: its own key goes to the new address.
        ProfileScope.activate(overlay("teen", TEEN))
        open(streamClient(), "$oldBase/proxy/ts/stream/abc", TEEN)
        // The move holds Teen: its overlay is dropped, the playlist's own key is used.
        ProfileScope.held = setOf("teen")
        open(streamClient(), "$oldBase/proxy/ts/stream/abc", TEEN)
        measure("stream client, active profile then held profile, cached /proxy/ URL")
        assertTrue(oldHits.isEmpty())
        assertEquals(listOf("teen", "own"), newHits.map { it.key })
    }

    @Test fun aHeldChildsMovedAddressGetsNothing() {
        moved()
        ProfileScope.sealed = setOf("kid")
        ProfileScope.activate(overlay("kid", KID))
        ProfileScope.sealedRoutes = ProfileHostHold.routes(newBase, null)
        ProfileScope.held = setOf("kid")
        try {
            open(streamClient(), "$oldBase/proxy/ts/stream/abc", KID)
            fail("a held Child's stream must not open")
        } catch (_: StreamRouteHeldException) {
        }
        measure("stream client, held Child, cached /proxy/ URL")
        assertTrue(oldHits.isEmpty())
        assertTrue(newHits.isEmpty())
    }

    @Test fun anotherPlaylistsStaleRequestIsNotRebuilt() {
        StreamAddresses.update(
            listOf(
                StreamAddresses.Entry("p1", serverUrl = newBase, keys = setOf(OWN), active = true, ownKey = OWN),
                StreamAddresses.Entry("p3", serverUrl = "http://elsewhere.test:1", keys = setOf(OTHER), active = false, ownKey = OTHER),
            ),
        )
        open(streamClient(), "$oldBase/proxy/ts/stream/abc", OTHER)
        measure("stream client, inactive playlist's cached /proxy/ URL")
        assertTrue(oldHits.single().present.values.none { it })
        assertTrue(newHits.isEmpty())
    }

    @Test fun theLocalUrlIsUsedWhenItsLastProbeReachedIt() {
        val lan = "http://lan.test:${new.address.port}"
        moved(lanUrl = lan)
        StreamAddresses.lanVerdict = { if (it == lan) true else null }
        open(streamClient(), "$oldBase/proxy/ts/stream/abc", OWN)
        measure("stream client, cached /proxy/ URL, Local URL reachable")
        assertTrue(oldHits.isEmpty())
        assertEquals("lan.test:${new.address.port}", newHits.single().hostPort)
    }

    @Test fun anXtreamCodesRowCachedBeforeTheMoveIsRebuiltAndNeverSentElsewhere() {
        StreamAddresses.update(
            listOf(
                StreamAddresses.Entry(
                    "x1", serverUrl = "http://xc.test:${new.address.port}",
                    xtreamUser = "u1", xtreamPass = "p1", active = true,
                ),
            ),
        )
        val c = streamClient()
        open(c, "http://xc-old.test:${old.address.port}/live/u1/p1/7.ts", null)
        try {
            open(c, "http://xc-old.test:${old.address.port}/player_api.php?username=u1&password=p1", null)
            fail("credentials in the URL never go to the old address")
        } catch (_: StreamAddresses.StaleAddressException) {
        }
        measure("stream client, Xtream Codes, cached /live/ URL then a non-stream URL")
        assertTrue(oldHits.isEmpty())
        assertEquals("/live/u1/p1/7.ts", newHits.single().path)
        assertEquals("xc.test:${new.address.port}", newHits.single().hostPort)
    }

    private companion object {
        const val OWN = "own-key-not-real"
        const val TEEN = "teen-key-not-real"
        const val KID = "kid-key-not-real"
        const val OTHER = "other-key-not-real"
    }
}
