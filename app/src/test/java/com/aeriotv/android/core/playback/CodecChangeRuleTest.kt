package com.aeriotv.android.core.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codec type change decision and live-edge rejoin math (Streamer 2026-11-01). */
class CodecChangeRuleTest {

    @Test
    fun `same codec switch keeps the connection`() {
        assertEquals(CodecChangeRule.SwitchPlan.KEPT_CONNECTION, CodecChangeRule.switchPlan("video/avc", "h264"))
        assertEquals(CodecChangeRule.SwitchPlan.KEPT_CONNECTION, CodecChangeRule.switchPlan("video/hevc", "hevc"))
    }

    @Test
    fun `different codec switch is sequential`() {
        assertEquals(CodecChangeRule.SwitchPlan.SEQUENTIAL, CodecChangeRule.switchPlan("video/hevc", "h264"))
        assertEquals(CodecChangeRule.SwitchPlan.SEQUENTIAL, CodecChangeRule.switchPlan("video/avc", "hevc"))
        assertEquals(CodecChangeRule.SwitchPlan.SEQUENTIAL, CodecChangeRule.switchPlan("video/avc", "mpeg2video"))
    }

    @Test
    fun `unknown target codec keeps the connection and leaves it to the PMT`() {
        assertEquals(CodecChangeRule.SwitchPlan.KEPT_CONNECTION, CodecChangeRule.switchPlan("video/avc", null))
        assertEquals(CodecChangeRule.SwitchPlan.KEPT_CONNECTION, CodecChangeRule.switchPlan(null, "hevc"))
        assertEquals(CodecChangeRule.SwitchPlan.KEPT_CONNECTION, CodecChangeRule.switchPlan("video/avc", "null"))
    }

    @Test
    fun `pmt stream type change is detected`() {
        assertTrue(CodecChangeRule.isTypeChange(0x1B, 0x24))
        assertTrue(CodecChangeRule.isTypeChange(0x24, 0x1B))
        assertTrue(CodecChangeRule.isTypeChange(0x02, 0x1B))
        assertFalse(CodecChangeRule.isTypeChange(0x1B, 0x1B))
        assertFalse(CodecChangeRule.isTypeChange(null, 0x24))
        assertEquals(0x24, CodecChangeRule.videoStreamType(listOf(0x81 to 257, 0x24 to 256)))
    }

    // ---- sniffer ----

    private fun section(tableId: Int, body: ByteArray): ByteArray {
        val len = body.size + 4 + 5
        val s = ByteArray(3 + len)
        s[0] = tableId.toByte()
        s[1] = (0xB0 or ((len shr 8) and 0x0f)).toByte()
        s[2] = len.toByte()
        s[3] = 0; s[4] = 1; s[5] = 0xC1.toByte(); s[6] = 0; s[7] = 0
        System.arraycopy(body, 0, s, 8, body.size)
        val crc = TsTableSniffer.crc32Mpeg(s, 0, s.size - 4)
        for (k in 0 until 4) s[s.size - 4 + k] = (crc ushr (24 - 8 * k)).toByte()
        return s
    }

    private fun packet(pid: Int, sec: ByteArray): ByteArray {
        val p = ByteArray(188) { 0xff.toByte() }
        p[0] = 0x47
        p[1] = (0x40 or (pid shr 8)).toByte()
        p[2] = pid.toByte()
        p[3] = 0x10
        p[4] = 0
        System.arraycopy(sec, 0, p, 5, sec.size)
        return p
    }

    private fun pat(pmtPid: Int) = packet(0, section(0, byteArrayOf(0, 1, (0xE0 or (pmtPid shr 8)).toByte(), pmtPid.toByte())))

    private fun pmt(pmtPid: Int, videoType: Int, videoPid: Int): ByteArray {
        val body = byteArrayOf(
            0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00,
            videoType.toByte(), (0xE0 or (videoPid shr 8)).toByte(), videoPid.toByte(), 0xF0.toByte(), 0x00,
            0x81.toByte(), 0xE1.toByte(), 0x01, 0xF0.toByte(), 0x00,
        )
        return packet(pmtPid, section(2, body))
    }

    @Test
    fun `sniffer reports a codec change on the same PIDs instead of a table change`() {
        val codec = ArrayList<Pair<Int, Int>>()
        val tables = ArrayList<String>()
        val s = TsTableSniffer(onCodecChange = { o, n -> codec.add(o to n) }) { tables.add(it) }
        val avc = pat(0x1000) + pmt(0x1000, 0x1B, 0x100)
        s.feed(avc, 0, avc.size)
        val hevc = pat(0x1000) + pmt(0x1000, 0x24, 0x100)
        s.feed(hevc, 0, hevc.size)
        assertEquals(listOf(0x1B to 0x24), codec)
        assertTrue(tables.isEmpty())
    }

    @Test
    fun `sniffer keeps a same codec PID change as a table change`() {
        val codec = ArrayList<Pair<Int, Int>>()
        val tables = ArrayList<String>()
        val s = TsTableSniffer(onCodecChange = { o, n -> codec.add(o to n) }) { tables.add(it) }
        val a = pat(0x1000) + pmt(0x1000, 0x1B, 0x100)
        s.feed(a, 0, a.size)
        val b = pat(0x1000) + pmt(0x1000, 0x1B, 0x200)
        s.feed(b, 0, b.size)
        assertTrue(codec.isEmpty())
        assertEquals(1, tables.size)
    }

    // ---- live-edge rejoin ----

    @Test
    fun `rejoin skips the replayed burst and keeps two seconds ahead`() {
        // Streamer 19:35:49: ~14.3 s buffered one second after READY.
        val target = LiveRejoin.skipTargetUs(firstUs = 0L, bufferedUs = 14_259_000L, keepUs = 2_000_000L)
        assertEquals(12_259_000L, target)
    }

    @Test
    fun `rejoin does nothing when the burst is within the keep ahead`() {
        assertEquals(C.TIME_UNSET, LiveRejoin.skipTargetUs(0L, 2_300_000L, 2_000_000L))
        assertEquals(C.TIME_UNSET, LiveRejoin.skipTargetUs(5_000_000L, 6_000_000L, 2_000_000L))
    }

    @Test
    fun `burst counts as landed after a quiet spell or the max wait`() {
        assertFalse(LiveRejoin.burstLanded(nowMs = 1_100, firstDataAtMs = 1_000, lastGrowAtMs = 1_050))
        assertTrue(LiveRejoin.burstLanded(nowMs = 1_400, firstDataAtMs = 1_000, lastGrowAtMs = 1_050))
        assertTrue(LiveRejoin.burstLanded(nowMs = 3_600, firstDataAtMs = 1_000, lastGrowAtMs = 3_590))
        assertFalse(LiveRejoin.burstLanded(nowMs = 5_000, firstDataAtMs = 0, lastGrowAtMs = 0))
    }
}
