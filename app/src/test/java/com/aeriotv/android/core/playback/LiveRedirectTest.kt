package com.aeriotv.android.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** GH #159: classification of a redirected live body's first bytes. */
class LiveRedirectTest {
    private fun sniff(b: ByteArray) = LiveRedirect.sniff(b, b.size)

    @Test fun playlistIsHls() =
        assertEquals(LiveRedirect.Content.HLS, sniff("#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()))

    @Test fun playlistWithBomAndWhitespaceIsHls() =
        assertEquals(
            LiveRedirect.Content.HLS,
            sniff(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "\r\n #EXTM3U\n".toByteArray()),
        )

    @Test fun alignedTsIsTs() {
        val b = ByteArray(376); b[0] = 0x47; b[188] = 0x47
        assertEquals(LiveRedirect.Content.TS, sniff(b))
    }

    @Test fun unalignedBodyIsUnknown() {
        val b = ByteArray(376); b[0] = 0x47; b[188] = 0x11
        assertEquals(LiveRedirect.Content.UNKNOWN, sniff(b))
    }

    @Test fun htmlIsUnknown() =
        assertEquals(LiveRedirect.Content.UNKNOWN, sniff("<html>".toByteArray()))

    @Test fun hostClass() {
        assertEquals("same-host", LiveRedirect.hostClass("http://a.example:9191/x", "http://A.example:9191/y"))
        assertEquals("cross-host", LiveRedirect.hostClass("http://a.example/x", "http://b.example/y"))
    }

    @Test fun withoutAuthDropsServerHeaders() {
        val out = LiveRedirect.withoutAuth(mapOf("X-API-Key" to "k", "authorization" to "b", "User-Agent" to "ua"))
        assertEquals(setOf("User-Agent"), out.keys)
    }
}
