package com.aeriotv.android.core.network

import com.aeriotv.android.core.playback.PlaybackHttp
import com.aeriotv.android.core.playback.StreamAuth
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * VOD stream requests carry X-API-Key only (VodSignIn): Dispatcharr's VOD
 * proxy sends the Authorization header of the request that creates a VOD
 * session to the provider. A loopback stand-in records, per request, the path
 * and whether X-API-Key and Authorization were present (never a value), and
 * answers a VOD entry with the 301 to its session URL that Dispatcharr sends.
 * Fake key only.
 */
class VodSignInTest {

    data class Hit(val path: String, val xApiKey: Boolean, val authorization: Boolean) {
        override fun toString() = "$path X-API-Key=${p(xApiKey)} Authorization=${p(authorization)}"
        private fun p(b: Boolean) = if (b) "present" else "absent"
    }

    private lateinit var server: HttpServer
    private val hits = CopyOnWriteArrayList<Hit>()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before fun setUp() {
        StreamAddresses.resetForTests()
        RedirectAuthRule.log = {}
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(4)
            createContext("/") { ex ->
                val h = ex.requestHeaders
                val path = ex.requestURI.path
                hits += Hit(path, h.getFirst("X-API-Key") != null, h.getFirst("Authorization") != null)
                // Dispatcharr: a VOD entry answers 301 to its session URL.
                val entry = Regex("^/proxy/vod/(movie|episode)/[^/]+$").matches(path)
                if (entry) {
                    ex.responseHeaders.add("Location", "$path/sess-1")
                    ex.sendResponseHeaders(301, -1)
                    ex.close()
                } else {
                    val body = "ok".toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
            }
            start()
        }
    }

    @After fun tearDown() {
        server.stop(0)
        StreamAddresses.resetForTests()
    }

    private fun open(client: OkHttpClient, path: String, headers: Map<String, String>) {
        val b = Request.Builder().url("$base$path")
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { it.body.string() }
    }

    private fun measure(label: String) {
        println("[MEASURE] $label: ${hits.joinToString("; ")}")
    }

    // ---- the rule, no network ----

    @Test fun vodPathsAreTheVodProxyAndTheXtreamMovieAndSeriesPaths() {
        listOf(
            "/proxy/vod/movie/m1", "/proxy/vod/movie/m1/sess-1", "/proxy/vod/episode/e1",
            "/dispatcharr/proxy/vod/movie/m1", "/movie/u1/p1/7.mkv", "/series/u1/p1/8.mp4",
            "/base/movie/u1/p1/7.mkv",
        ).forEach { assertTrue(it, VodSignIn.isVodPath(it)) }
        listOf(
            "/proxy/ts/stream/abc", "/timeshift/u1/p1/60/2026-11-13:20-00/5.ts", "/live/u1/p1/5.ts",
            "/api/channels/recordings/5/file/", "/api/vod/movies/12/", "/api/vod/series/3/",
            "/api/vod/series/3/provider-info/", "/api/vod/movie/12/",
        ).forEach { assertFalse(it, VodSignIn.isVodPath(it)) }
    }

    @Test fun vodHeadersCarryTheKeyAsXApiKeyOnly() {
        val vod = "http://h/proxy/vod/movie/m1"
        assertEquals(mapOf("X-API-Key" to KEY), StreamAuth.authHeaders(KEY, vod))
        assertEquals(mapOf("X-API-Key" to KEY, "Authorization" to "ApiKey $KEY"), StreamAuth.authHeaders(KEY, "http://h/proxy/ts/stream/x"))
        // Authorization: ApiKey alone becomes X-API-Key; a Bearer token is not an API key.
        assertEquals(mapOf("X-API-Key" to KEY), VodSignIn.headersFor(vod, mapOf("Authorization" to "ApiKey $KEY")))
        assertEquals(mapOf("User-Agent" to "ua"), VodSignIn.headersFor(vod, mapOf("Authorization" to "Bearer jwt", "User-Agent" to "ua")))
        val live = mapOf("X-API-Key" to KEY, "Authorization" to "ApiKey $KEY")
        assertEquals(live, VodSignIn.headersFor("http://h/proxy/ts/stream/x", live))
    }

    // ---- the stand-in ----

    /** What PlaybackHeaders sends for a Dispatcharr playlist. */
    private val playback = mapOf("X-API-Key" to KEY, "Authorization" to "ApiKey $KEY", "User-Agent" to "AerioTV-test")

    @Test fun vodRequestsCarryXApiKeyAndNoAuthorization() {
        val c = PlaybackHttp.client(30_000, 30_000)
        open(c, "/proxy/vod/movie/m1", playback)
        open(c, "/proxy/vod/episode/e1", playback)
        open(c, "/movie/u1/p1/7.mkv", playback)
        open(c, "/series/u1/p1/8.mp4", playback)
        open(c, "/proxy/vod/movie/m2/sess-9", mapOf("Authorization" to "ApiKey $KEY"))
        measure("PlaybackHttp client, VOD requests with the playback headers (entries follow the 301)")
        assertEquals(
            listOf(
                "/proxy/vod/movie/m1", "/proxy/vod/movie/m1/sess-1",
                "/proxy/vod/episode/e1", "/proxy/vod/episode/e1/sess-1",
                "/movie/u1/p1/7.mkv", "/series/u1/p1/8.mp4", "/proxy/vod/movie/m2/sess-9",
            ),
            hits.map { it.path },
        )
        assertTrue("every VOD hop carries X-API-Key", hits.all { it.xApiKey })
        assertTrue("no VOD hop carries Authorization", hits.none { it.authorization })
    }

    @Test fun liveCatchUpAndRecordingsKeepBothHeaders() {
        val c = OkHttpClient.Builder().let(StreamAddresses::install).let(RedirectAuthRule::install).build()
        open(c, "/proxy/ts/stream/abc", playback)
        open(c, "/timeshift/u1/p1/60/2026-11-13:20-00/5.ts", playback)
        open(c, "/api/channels/recordings/5/file/", playback)
        measure("stream client, live, catch-up and recording requests")
        assertTrue(hits.all { it.xApiKey && it.authorization })
    }

    @Test fun theVodResolveProbesThatCreateTheSessionCarryXApiKeyOnly() = runBlocking {
        val client = DispatcharrClient()
        val movie = client.resolveVODStreamUrl(base, KEY, "m1")
        val episode = client.resolveVODEpisodeStreamUrl(base, KEY, "e1")
        measure("DispatcharrClient VOD resolve probes (movie, episode)")
        assertEquals("$base/proxy/vod/movie/m1/sess-1", movie)
        assertEquals("$base/proxy/vod/episode/e1/sess-1", episode)
        assertEquals(listOf("/proxy/vod/movie/m1", "/proxy/vod/episode/e1"), hits.map { it.path })
        assertTrue(hits.all { it.xApiKey && !it.authorization })
    }

    private companion object {
        const val KEY = "test-key-not-real"
    }
}
