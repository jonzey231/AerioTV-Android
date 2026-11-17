package com.aeriotv.android.core.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogSanitizerMaskTest {

    @Test
    fun xtreamMovieKeepsKindAndExtensionOnly() {
        val out = LogSanitizer.maskStreamUrl("https://panel.example.net:8443/movie/alice/s3cret/6001342.mkv")
        assertEquals("https://<host>/movie/<user>/<pass>/<id>.mkv", out)
    }

    @Test
    fun xtreamLiveAndSeries() {
        assertEquals(
            "http://<host>/live/<user>/<pass>/<id>.ts",
            LogSanitizer.maskStreamUrl("http://1.2.3.4/live/alice/s3cret/123.ts"),
        )
        assertEquals(
            "http://<host>/series/<user>/<pass>/<id>.mp4",
            LogSanitizer.maskStreamUrl("http://h/series/alice/s3cret/987.mp4"),
        )
    }

    @Test
    fun shorthandXtreamPathHidesEverySegment() {
        val out = LogSanitizer.maskStreamUrl("http://h:80/alice/s3cret/123")
        assertEquals("http://<host>/<seg>/<seg>/<id>", out)
    }

    @Test
    fun queryValuesAreMaskedButActionIsKept() {
        val out = LogSanitizer.maskStreamUrl(
            "http://h/player_api.php?username=alice&password=s3cret&action=get_vod_streams",
        )
        assertEquals("http://<host>/player_api.php?username=<v>&password=<v>&action=get_vod_streams", out)
    }

    @Test
    fun dispatcharrProxyPathAndUserInfo() {
        val out = LogSanitizer.maskStreamUrl("https://bob:pw@d.example/proxy/ts/stream/5f1c-uuid?api_key=abc")
        assertEquals("https://<host>/proxy/ts/stream/<id>?api_key=<v>", out)
    }

    @Test
    fun urlsInsideTextAreMasked() {
        val text = "REQUEST: http://panel.example.net/movie/alice/s3cret/1.mkv done"
        val out = LogSanitizer.maskUrlsIn(text)
        assertEquals("REQUEST: http://<host>/movie/<user>/<pass>/<id>.mkv done", out)
        assertFalse(out.contains("alice") || out.contains("s3cret") || out.contains("panel"))
    }

    @Test
    fun blankAndUnparseable() {
        assertEquals("<none>", LogSanitizer.maskStreamUrl(null))
        assertEquals("<url>", LogSanitizer.maskStreamUrl("http://h/a b|c"))
    }
}
