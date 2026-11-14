package com.aeriotv.android.core.network

import com.aeriotv.android.core.playback.ForceHls
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sign-in redirect rule itself (the Apple app's rule), no network. */
class RedirectAuthRuleTest {
    private fun keeps(from: String, to: String) = RedirectAuthRule.keepsSignIn(from, to)

    @Test fun aHopToAnotherHostDropsThem() {
        assertFalse(keeps("http://dispatcharr.test:9191/proxy/ts/stream/x", "http://provider.test:9191/live/1.ts"))
        assertFalse(keeps("https://dispatcharr.test/x", "https://provider.test/x"))
        assertFalse(keeps("http://192.168.1.10:9191/x", "http://dispatcharr.test:9191/x"))
        // A subdomain is another host.
        assertFalse(keeps("https://dispatcharr.test/x", "https://cdn.dispatcharr.test/x"))
    }

    @Test fun aHopOnTheSameHostAndPortKeepsThem() {
        assertTrue(keeps("http://dispatcharr.test:9191/proxy/vod/movie/u", "http://dispatcharr.test:9191/proxy/vod/movie/u/vod_s"))
        assertTrue(keeps("https://dispatcharr.test/x", "https://dispatcharr.test/y"))
        // Host names compare without case; an explicit default port is the same port.
        assertTrue(keeps("http://Dispatcharr.TEST/x", "http://dispatcharr.test:80/y"))
        assertTrue(keeps("https://dispatcharr.test:443/x", "https://dispatcharr.test/y"))
    }

    @Test fun aHopToAnotherPortOnTheSameHostDropsThem() {
        assertFalse(keeps("http://dispatcharr.test:9191/x", "http://dispatcharr.test:8080/y"))
        assertFalse(keeps("https://dispatcharr.test/x", "https://dispatcharr.test:8443/y"))
        assertFalse(keeps("http://dispatcharr.test/x", "http://dispatcharr.test:9191/y"))
    }

    @Test fun anHttpsToHttpDowngradeDropsThem() {
        assertFalse(keeps("https://dispatcharr.test/x", "http://dispatcharr.test/x"))
        // Even on the same explicit port.
        assertFalse(keeps("https://dispatcharr.test:8443/x", "http://dispatcharr.test:8443/x"))
        assertFalse(RedirectAuthRule.keepsSignIn("dispatcharr.test", true, 9191, "DISPATCHARR.test", false, 9191))
    }

    @Test fun anHttpToHttpsUpgradeKeepsThemOnDefaultPortsOrAnUnchangedPort() {
        assertTrue(keeps("http://dispatcharr.test/x", "https://dispatcharr.test/x"))
        assertTrue(keeps("http://dispatcharr.test:80/x", "https://dispatcharr.test:443/x"))
        assertTrue(keeps("http://dispatcharr.test:8080/x", "https://dispatcharr.test:8080/x"))
        // Any other port change on the upgrade drops them.
        assertFalse(keeps("http://dispatcharr.test:9191/x", "https://dispatcharr.test/x"))
        assertFalse(keeps("http://dispatcharr.test/x", "https://dispatcharr.test:8443/x"))
        assertFalse(keeps("http://dispatcharr.test:8080/x", "https://dispatcharr.test:8443/x"))
    }

    @Test fun eachHopIsJudgedAgainstThePreviousUrlAndTheOriginalRequest() {
        val origin = "http://dispatcharr.test:9191/a".toHttpUrl()
        val provider = "http://provider.test:9191/b".toHttpUrl()
        // Back to the origin after leaving: the previous hop was another host.
        assertFalse(RedirectAuthRule.keepsSignIn(origin, provider, "http://dispatcharr.test:9191/c".toHttpUrl()))
        // Upgrade then stay: both checks pass.
        val up = "https://dispatcharr.test/a".toHttpUrl()
        assertTrue(RedirectAuthRule.keepsSignIn("http://dispatcharr.test/a".toHttpUrl(), up, "https://dispatcharr.test/b".toHttpUrl()))
        // Upgrade then back to http: a downgrade from the previous hop.
        assertFalse(RedirectAuthRule.keepsSignIn("http://dispatcharr.test/a".toHttpUrl(), up, "http://dispatcharr.test/b".toHttpUrl()))
    }

    @Test fun anHttpsChainRedirectedToHttpOnTheSameHostIsFollowedAsHttps() {
        fun up(origin: String, next: String) =
            RedirectAuthRule.tlsUpgradeTarget(origin.toHttpUrl(), origin.toHttpUrl(), next.toHttpUrl())?.toString()
        // http on port 80: https on the original request's port.
        assertEquals("https://dispatcharr.test/landing", up("https://dispatcharr.test/a", "http://dispatcharr.test/landing"))
        assertEquals("https://dispatcharr.test:8443/x", up("https://dispatcharr.test:8443/a", "http://dispatcharr.test/x"))
        // http on the same port as the https request, query kept.
        assertEquals("https://dispatcharr.test:8443/x?y=1", up("https://dispatcharr.test:8443/a", "http://dispatcharr.test:8443/x?y=1"))
        // Host names compare without case.
        assertNotNull(up("https://dispatcharr.test/a", "http://DISPATCHARR.test/x"))
        // Any other downgrade is not this rule: another port, another host.
        assertNull(up("https://dispatcharr.test/a", "http://dispatcharr.test:8080/x"))
        assertNull(up("https://dispatcharr.test:8443/a", "http://dispatcharr.test:9191/x"))
        assertNull(up("https://dispatcharr.test/a", "http://provider.test/x"))
        // Not a downgrade, or the chain was not https.
        assertNull(up("https://dispatcharr.test/a", "https://dispatcharr.test/x"))
        assertNull(up("http://dispatcharr.test/a", "http://dispatcharr.test/x"))
        // The hop that answered was http (an http hop in the chain): not this rule.
        assertNull(
            RedirectAuthRule.tlsUpgradeTarget(
                "https://dispatcharr.test/a".toHttpUrl(), "http://dispatcharr.test/b".toHttpUrl(), "http://dispatcharr.test/c".toHttpUrl(),
            ),
        )
    }

    @Test fun aRecordingUrlReportedAsHttpBehindAnUntrustedProxyIsUsedAsHttps() {
        val servers = listOf("https://dispatcharr.test", null)
        assertEquals(
            "https://dispatcharr.test/api/channels/recordings/5/hls/index.m3u8",
            RedirectAuthRule.serverUrlFor("http://dispatcharr.test/api/channels/recordings/5/hls/index.m3u8", servers),
        )
        assertEquals("https://dispatcharr.test/x", RedirectAuthRule.serverUrlFor("https://dispatcharr.test/x", servers))
        assertNull(RedirectAuthRule.serverUrlFor("http://dispatcharr.test:9191/x", servers))
        assertNull(RedirectAuthRule.serverUrlFor("http://other.test/x", servers))
        // An http server URL is used as it is.
        assertEquals(
            "http://192.168.1.10:9191/x",
            RedirectAuthRule.serverUrlFor("http://192.168.1.10:9191/x", listOf("http://192.168.1.10:9191")),
        )
    }

    @Test fun aUrlThatDoesNotParseNeverKeepsThem() {
        assertFalse(keeps("not a url", "http://dispatcharr.test/x"))
        assertFalse(keeps("http://dispatcharr.test/x", "ftp://dispatcharr.test/x"))
    }

    @Test fun theFourHeadersAndOnlyThoseAreSignInHeaders() {
        assertEquals(listOf("X-API-Key", "Authorization", "Cookie", "X-Emby-Authorization"), RedirectAuthRule.HEADERS)
        assertTrue(RedirectAuthRule.isSignInHeader("x-api-key"))
        assertTrue(RedirectAuthRule.isSignInHeader("AUTHORIZATION"))
        assertTrue(RedirectAuthRule.isSignInHeader("cookie"))
        assertTrue(RedirectAuthRule.isSignInHeader("x-emby-authorization"))
        assertFalse(RedirectAuthRule.isSignInHeader("User-Agent"))
        assertFalse(RedirectAuthRule.isSignInHeader("Set-Cookie"))
    }

    @Test fun withoutSignInKeepsEverythingElse() {
        val out = RedirectAuthRule.withoutSignIn(
            mapOf(
                "X-API-Key" to "k",
                "authorization" to "ApiKey k",
                "COOKIE" to "c",
                "X-Emby-Authorization" to "e",
                "User-Agent" to "ua",
                "Accept" to "*/*",
            ),
        )
        assertEquals(setOf("User-Agent", "Accept"), out.keys)
    }

    @Test fun firstRequestGateUsesTheSameHostAndPortRule() {
        val servers = listOf("https://dispatcharr.test", "http://192.168.1.10:9191", null, "")
        assertTrue(RedirectAuthRule.isServerUrl("https://dispatcharr.test/api/channels/logos/1/cache/", servers))
        assertTrue(RedirectAuthRule.isServerUrl("http://192.168.1.10:9191/api/channels/recordings/3/file/", servers))
        assertTrue(RedirectAuthRule.isServerUrl("HTTPS://Dispatcharr.TEST:443/x", servers))
        // What the logo gate's plain startsWith on the base let through:
        // a longer host, a longer IP, another port.
        assertFalse(RedirectAuthRule.isServerUrl("https://dispatcharr.test.evil.test/logo.png", servers))
        assertFalse(RedirectAuthRule.isServerUrl("http://192.168.1.100/logo.png", listOf("http://192.168.1.10")))
        assertFalse(RedirectAuthRule.isServerUrl("https://dispatcharr.test:8443/logo.png", servers))
        // What the recording and catch-up gates' host-only check let through:
        // the same host on another port, and https to http.
        assertFalse(RedirectAuthRule.isServerUrl("http://192.168.1.10:8080/api/channels/recordings/3/file/", servers))
        assertFalse(RedirectAuthRule.isServerUrl("http://dispatcharr.test/api/channels/recordings/3/file/", servers))
        // http to https on the default ports keeps the server's sign-in.
        assertTrue(RedirectAuthRule.isServerUrl("https://plain.test/x", listOf("http://plain.test")))
        assertFalse(RedirectAuthRule.isServerUrl("not a url", servers))
    }

    @Test fun theLogoGateAlsoStaysUnderTheServersPath() {
        val servers = listOf("https://example.test/dispatcharr")
        assertTrue(RedirectAuthRule.isServerUrl("https://example.test/dispatcharr/api/channels/logos/1/cache/", servers, withinPath = true))
        assertTrue(RedirectAuthRule.isServerUrl("https://example.test/dispatcharr", servers, withinPath = true))
        assertFalse(RedirectAuthRule.isServerUrl("https://example.test/dispatcharr2/logo.png", servers, withinPath = true))
        assertFalse(RedirectAuthRule.isServerUrl("https://example.test/other/logo.png", servers, withinPath = true))
        // A base with no path covers every path on its host and port.
        assertTrue(RedirectAuthRule.isServerUrl("http://192.168.1.10:9191/any/path.png", listOf("http://192.168.1.10:9191"), withinPath = true))
    }

    @Test fun onlyTheServersOwnHlsSessionIsAdopted() {
        val entry = "http://dispatcharr.test:9191/proxy/ts/stream/abc"
        assertTrue(ForceHls.isServerSession(entry, "http://dispatcharr.test:9191/proxy/hls/tok/index.m3u8"))
        // A session-shaped URL on another host or port is a provider's: never
        // adopted with the server's headers.
        assertFalse(ForceHls.isServerSession(entry, "http://provider.test:9191/proxy/hls/tok/index.m3u8"))
        assertFalse(ForceHls.isServerSession(entry, "http://dispatcharr.test:8080/proxy/hls/tok/index.m3u8"))
        assertFalse(ForceHls.isServerSession("https://dispatcharr.test/proxy/ts/stream/abc", "http://dispatcharr.test/proxy/hls/tok/index.m3u8"))
        assertFalse(ForceHls.isServerSession(entry, "http://dispatcharr.test:9191/live/1.ts"))
    }
}
