package com.aeriotv.android.core.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Playlist delete cutoff, Apple parity (17f1d07): a deleted playlist's
 * keys, Xtream Codes credentials in URLs, M3U and XMLTV URLs, and the hosts
 * no remaining playlist uses are refused; whatever a remaining playlist
 * still uses keeps working; signing in again lifts it.
 */
class PlaylistRevocationTest {

    @Before fun setUp() {
        PlaylistRevocation.resetForTests()
        PlaylistRevocation.logSink = {}
    }

    @After fun tearDown() {
        PlaylistRevocation.resetForTests()
        PlaylistRevocation.logSink = null
    }

    private fun xc(id: String, host: String, user: String, pass: String) = PlaylistFingerprint.of(
        playlistId = id, sourceType = "XtreamCodes", url = "http://$host", username = user, password = pass,
    )

    private fun m3u(id: String, url: String, epg: String? = null) =
        PlaylistFingerprint.of(playlistId = id, sourceType = "M3uUrl", url = url, epgUrl = epg)

    private fun deleteOf(deleted: PlaylistFingerprint, vararg remaining: PlaylistFingerprint) =
        PlaylistRevocation.revoke(deleted.minus(remaining.toList()))

    private fun refused(url: String, key: String? = null) = PlaylistRevocation.refusal(url, key) != null

    // ---- Xtream Codes ----

    @Test fun aDeletedXtreamPlaylistSendsNothing() {
        deleteOf(xc("A", "xc.example:8080", "alice", "pw1"))
        // API calls, live, movie, and series streams, catch-up, and the guide.
        assertTrue(refused("http://xc.example:8080/player_api.php?username=alice&password=pw1&action=get_live_streams"))
        assertTrue(refused("http://xc.example:8080/live/alice/pw1/101.ts"))
        assertTrue(refused("http://xc.example:8080/movie/alice/pw1/7.mkv"))
        assertTrue(refused("http://xc.example:8080/timeshift/alice/pw1/60/2026-11-13:15-00/101.ts"))
        assertTrue(refused("http://xc.example:8080/xmltv.php?username=alice&password=pw1"))
        // A logo on its host (no other playlist uses it).
        assertTrue(refused("http://xc.example:8080/images/logo101.png"))
        // The same credentials redirected to a CDN host are still its traffic.
        assertTrue(refused("http://cdn.example/live/alice/pw1/101.ts"))
        // Anything else.
        assertFalse(refused("http://other.example/live/bob/pw2/1.ts"))
    }

    @Test fun anotherXtreamPlaylistOnTheSameHostKeepsWorking() {
        val a = xc("A", "xc.example:8080", "alice", "pw1")
        val b = xc("B", "xc.example:8080", "bob", "pw2")
        deleteOf(a, b)
        // A's credentials are refused on the shared host...
        assertTrue(refused("http://xc.example:8080/player_api.php?username=alice&password=pw1"))
        assertTrue(refused("http://xc.example:8080/live/alice/pw1/101.ts"))
        // ...and B's requests there go out: the host is B's too.
        assertFalse(refused("http://xc.example:8080/player_api.php?username=bob&password=pw2&action=get_live_streams"))
        assertFalse(refused("http://xc.example:8080/live/bob/pw2/101.ts"))
        assertFalse(refused("http://xc.example:8080/xmltv.php?username=bob&password=pw2"))
        assertFalse(refused("http://xc.example:8080/images/logo101.png"))
    }

    @Test fun theSameXtreamAccountOnAnotherPlaylistKeepsWorking() {
        val a = xc("A", "xc.example:8080", "alice", "pw1")
        val b = xc("B", "xc.example:8080", "alice", "pw1")
        deleteOf(a, b)
        assertFalse(refused("http://xc.example:8080/live/alice/pw1/101.ts"))
    }

    // ---- M3U ----

    @Test fun aDeletedM3uPlaylistSendsNothing() {
        deleteOf(m3u("A", "http://lists.example/u/abc/get.php?type=m3u", "http://epg.example/abc.xml"))
        assertTrue(refused("http://lists.example/u/abc/get.php?type=m3u"))
        assertTrue(refused("http://epg.example/abc.xml"))
        // Streams and logos on its hosts.
        assertTrue(refused("http://lists.example/stream/101.ts"))
        assertTrue(refused("http://epg.example/logos/101.png"))
        assertFalse(refused("http://elsewhere.example/stream/101.ts"))
    }

    @Test fun anotherM3uPlaylistOnTheSameHostKeepsWorking() {
        val a = m3u("A", "http://lists.example/a.m3u", "http://lists.example/a.xml")
        val b = m3u("B", "http://lists.example/b.m3u", "http://lists.example/b.xml")
        deleteOf(a, b)
        // A's own list and guide are refused...
        assertTrue(refused("http://lists.example/a.m3u"))
        assertTrue(refused("http://lists.example/a.xml"))
        // ...B's list, guide, streams, and logos on the shared host go out.
        assertFalse(refused("http://lists.example/b.m3u"))
        assertFalse(refused("http://lists.example/b.xml"))
        assertFalse(refused("http://lists.example/stream/7.ts"))
    }

    // ---- Dispatcharr keys and hosts ----

    @Test fun aDeletedDispatcharrPlaylistsKeysAndUnsharedHostAreRefused() {
        val a = PlaylistFingerprint.of(
            "A", "DispatcharrApiKey", url = "http://disp.example:9191", lanUrl = "http://192.168.1.10:9191",
            apiKey = "KEY-A", profileKeys = listOf("KEY-A-KID"),
        )
        deleteOf(a)
        assertTrue(refused("http://other.example/api/channels/", key = "KEY-A"))
        assertTrue(refused("http://other.example/api/channels/", key = "KEY-A-KID"))
        assertTrue(refused("http://disp.example:9191/api/channels/logos/1/cache/"))
        assertTrue(refused("http://192.168.1.10:9191/proxy/ts/stream/x"))
        assertFalse(refused("http://disp2.example:9191/api/channels/", key = "KEY-B"))
    }

    @Test fun twoPlaylistsSharingAKeyAndHostLoseNothing() {
        val a = PlaylistFingerprint.of("A", "DispatcharrApiKey", url = "http://127.0.0.1:8781", apiKey = "SAME")
        val b = PlaylistFingerprint.of("B", "DispatcharrApiKey", url = "http://127.0.0.1:8781", apiKey = "SAME")
        deleteOf(a, b)
        assertFalse(refused("http://127.0.0.1:8781/api/channels/channels/", key = "SAME"))
        assertFalse(refused("http://127.0.0.1:8781/proxy/ts/stream/1", key = "SAME"))
    }

    @Test fun aProfileKeyAnotherPlaylistStillUsesKeepsWorking() {
        val a = PlaylistFingerprint.of("A", "DispatcharrApiKey", url = "http://a.example", apiKey = "KEY-A", profileKeys = listOf("KID"))
        deleteOf(a, PlaylistFingerprint("profiles", keys = setOf("KID")))
        assertTrue(refused("http://x.example/api/", key = "KEY-A"))
        assertFalse(refused("http://x.example/api/", key = "KID"))
    }

    // ---- lifting ----

    @Test fun signingInAgainLiftsTheCutoff() {
        val a = xc("A", "xc.example:8080", "alice", "pw1")
        deleteOf(a)
        assertTrue(refused("http://xc.example:8080/live/alice/pw1/1.ts"))
        // The same server added again under a new id.
        PlaylistRevocation.lift(listOf(xc("NEW", "xc.example:8080", "alice", "pw1")))
        assertFalse(refused("http://xc.example:8080/live/alice/pw1/1.ts"))
    }

    @Test fun aKeySavedAgainLiftsItsEntry() {
        deleteOf(PlaylistFingerprint.of("A", "DispatcharrApiKey", url = "http://a.example", apiKey = "KEY-A"))
        assertTrue(refused("http://a.example/api/"))
        PlaylistRevocation.liftKey("KEY-A")
        assertFalse(refused("http://a.example/api/"))
        assertFalse(refused("http://b.example/api/", key = "KEY-A"))
    }

    @Test fun anUnrelatedPlaylistDoesNotLiftIt() {
        deleteOf(m3u("A", "http://lists.example/a.m3u"))
        PlaylistRevocation.lift(listOf(m3u("B", "http://other.example/b.m3u")))
        assertTrue(refused("http://lists.example/a.m3u"))
    }

    @Test fun nothingIsRefusedBeforeAnyDelete() {
        assertNull(PlaylistRevocation.refusal("http://xc.example:8080/live/alice/pw1/1.ts", "KEY"))
    }

    @Test fun anEmptyFingerprintRevokesNothing() {
        val lines = mutableListOf<String>()
        PlaylistRevocation.logSink = { lines += it }
        PlaylistRevocation.revoke(PlaylistFingerprint("A"))
        assertNull(PlaylistRevocation.refusal("http://a.example/", null))
        assertEquals(1, lines.size)
        assertFalse("never a key or URL in the log", lines.single().contains("http"))
    }

    @Test fun theLogCarriesCountsOnly() {
        val lines = mutableListOf<String>()
        PlaylistRevocation.logSink = { lines += it }
        deleteOf(xc("A1234567890", "xc.example:8080", "alice", "pw1"))
        val line = lines.single()
        assertTrue(line, line.contains("2 URL secret(s)") && line.contains("1 host(s)"))
        assertFalse(line.contains("alice") || line.contains("pw1") || line.contains("xc.example"))
    }
}
