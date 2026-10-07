package com.aeriotv.android.core.cast.multiview

import com.aeriotv.android.core.cast.hlsproxy.CastHlsProxyServer
import com.aeriotv.android.core.cast.hlsproxy.CastSegmentProfile
import com.aeriotv.android.core.cast.hlsproxy.TsToFmp4Remuxer
import com.aeriotv.android.feature.multiview.MultiviewLayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composite's segment profile (Apple 3515e92 round 6): 1 s cuts, a
 * nearest-second TARGETDURATION, only composite segments listed, HOLD-BACK
 * exactly three targets with no 8 s floor. Single-channel casts keep 3 s
 * cuts, round-up and the standard hold-back.
 */
class MultiviewCompositeSegmentProfileTest {

    private val tps = TsToFmp4Remuxer.TICKS_PER_SECOND

    private fun ticks(seconds: Double) = (seconds * tps).toLong()

    private fun publish(server: CastHlsProxyServer, gen: Int, count: Int, seconds: Double) = repeat(count) {
        server.addSegment(gen, byteArrayOf(1), byteArrayOf(2), durationTicks = ticks(seconds))
    }

    private fun tag(text: String, name: String): String? =
        text.lineSequence().firstOrNull { it.startsWith("#$name:") }?.substringAfter(':')

    @Test
    fun `profile targets match the encoder key frame interval`() {
        assertEquals(3 * tps, CastSegmentProfile.STANDARD.targetSegmentTicks)
        assertEquals(tps, CastSegmentProfile.COMPOSITE.targetSegmentTicks)
    }

    @Test
    fun `target duration rounds up for standard and to nearest for composite`() {
        val spans = listOf(ticks(1.033), ticks(0.967))
        assertEquals(2, CastHlsProxyServer.targetDurationSeconds(spans, nearest = false))
        assertEquals(1, CastHlsProxyServer.targetDurationSeconds(spans, nearest = true))
        assertEquals(4, CastHlsProxyServer.targetDurationSeconds(listOf(ticks(3.03)), nearest = false))
        assertEquals(3, CastHlsProxyServer.targetDurationSeconds(listOf(ticks(3.03)), nearest = true))
        assertEquals(2, CastHlsProxyServer.targetDurationSeconds(listOf(ticks(1.5)), nearest = true))
        assertEquals(1, CastHlsProxyServer.targetDurationSeconds(listOf(ticks(0.2)), nearest = true))
        assertEquals(4, CastHlsProxyServer.targetDurationSeconds(emptyList(), nearest = true))
    }

    @Test
    fun `composite lists only composite segments with target 1 and hold-back 3`() {
        val server = CastHlsProxyServer(log = {})
        val chan = server.beginGeneration()
        server.setInitSegments(chan, byteArrayOf(1), byteArrayOf(2))
        publish(server, chan, 5, 4.0) // a leftover single-channel run
        val comp = server.beginGeneration()
        server.setProfile(comp, CastSegmentProfile.COMPOSITE)
        server.setInitSegments(comp, byteArrayOf(3), byteArrayOf(4))
        publish(server, comp, 10, 1.033)
        val text = server.videoPlaylistText()
        assertEquals("1", tag(text, "EXT-X-TARGETDURATION"))
        assertEquals("HOLD-BACK=3.000", tag(text, "EXT-X-SERVER-CONTROL"))
        assertFalse("no channel segments listed", text.contains("vinit$chan.mp4"))
        assertEquals(10, text.lineSequence().count { it.startsWith("#EXTINF") })
        // The trimmed run's discontinuity accounting stays consistent.
        assertEquals("5", tag(text, "EXT-X-MEDIA-SEQUENCE"))
        assertEquals(server.audioPlaylistText().lines().first { it.startsWith("#EXT-X-TARGETDURATION") }, "#EXT-X-TARGETDURATION:1")
    }

    @Test
    fun `composite window holds about 30 s of 1 s segments`() {
        val server = CastHlsProxyServer(log = {})
        val comp = server.beginGeneration()
        server.setProfile(comp, CastSegmentProfile.COMPOSITE)
        server.setInitSegments(comp, byteArrayOf(3), byteArrayOf(4))
        publish(server, comp, 60, 1.0)
        val text = server.videoPlaylistText()
        assertEquals(CastHlsProxyServer.COMPOSITE_WINDOW_SIZE, text.lineSequence().count { it.startsWith("#EXTINF") })
        assertEquals(30, CastHlsProxyServer.COMPOSITE_WINDOW_SIZE)
    }

    @Test
    fun `standard cast keeps round-up and the 8 s hold-back floor`() {
        val server = CastHlsProxyServer(log = {})
        val gen = server.beginGeneration()
        server.setInitSegments(gen, byteArrayOf(1), byteArrayOf(2))
        publish(server, gen, 4, 3.03)
        val text = server.videoPlaylistText()
        assertEquals("4", tag(text, "EXT-X-TARGETDURATION"))
        // max(3 x target 3 s (nominal floor), 8 s floor) = 9 s, clamped to
        // max(3 x 4 s, room) by the playlist.
        val hb = tag(text, "EXT-X-SERVER-CONTROL")!!.substringAfter('=').toDouble()
        assertTrue("standard hold-back keeps the floor, got $hb", hb >= 8.0)
    }

    @Test
    fun `layout options follow the Settings list and fall back to Default`() {
        assertEquals(listOf(MultiviewLayoutMode.Auto, MultiviewLayoutMode.Stacked), MultiviewCompositeLayout.layoutOptions(2))
        assertEquals(
            listOf(MultiviewLayoutMode.Auto, MultiviewLayoutMode.EvenGrid, MultiviewLayoutMode.Stacked),
            MultiviewCompositeLayout.layoutOptions(3),
        )
        assertEquals(MultiviewLayoutMode.Auto, MultiviewCompositeLayout.effectiveMode(MultiviewLayoutMode.EvenGrid, 2))
        assertEquals(MultiviewLayoutMode.Stacked, MultiviewCompositeLayout.effectiveMode(MultiviewLayoutMode.Stacked, 4))
        val stacked = MultiviewCompositeLayout.tileRects(2, padding = false, mode = MultiviewLayoutMode.Stacked)
        assertEquals(MultiviewCompositeLayout.WIDTH, stacked[0].width)
        assertTrue(stacked[1].top >= stacked[0].bottom)
    }

    @Test
    fun `composite encoder GOP is 1 s`() {
        assertEquals(1, MultiviewCompositor.ENCODER_KEY_INTERVAL_S)
        assertEquals(1_000_000_000L, MultiviewCompositor.FORCED_KEY_NANOS)
    }
}
