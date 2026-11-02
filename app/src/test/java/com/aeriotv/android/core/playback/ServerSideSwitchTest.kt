package com.aeriotv.android.core.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Server-side stream switch on a kept connection (Streamer 2026-11-01). */
@UnstableApi
class ServerSideSwitchTest {

    private class Sink : TrackOutput {
        val times = ArrayList<Long>()
        val formats = ArrayList<Format>()
        var bytes = 0
        override fun format(format: Format) { formats.add(format) }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = length
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            bytes += length
            data.skipBytes(length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            times.add(timeUs)
        }
    }

    @Test
    fun `splice puts both tracks on one shared base with no oscillation`() {
        val logs = ArrayList<String>()
        val r = ContinuousTsExtractor.Rebaser({ logs.add(it) })
        val v = r.newTrack(C.TRACK_TYPE_VIDEO)
        val a = r.newTrack(C.TRACK_TYPE_AUDIO)
        // Old stream: video and audio on their timeline.
        for (i in 0 until 10) r.map(v, 50_000_000L + i * 33_367L)
        for (i in 0 until 10) r.map(a, 50_100_000L + i * 32_000L)
        val vLast = 50_000_000L + 9 * 33_367L
        // Fresh TsExtractor: one TimestampAdjuster, both tracks near 0 but
        // with different starting offsets (audio leads by 300 ms).
        r.armSplice()
        // Audio crosses first, then video, then three more samples each.
        val a0 = (0 until 3).map { r.map(a, 300_000L + it * 32_000L) }
        val v0 = r.map(v, 0L)
        // Video continues right after its old output, never overlapping it.
        assertEquals(vLast + 33_367L, v0)
        val vOut = (1..3).map { r.map(v, it * 33_367L) }
        val aOut = (3 until 6).map { r.map(a, 300_000L + it * 32_000L) }
        // One shared base: the new stream's raw A/V relation holds exactly.
        for (i in 0 until 3) {
            val rawA = 300_000L + (i + 3) * 32_000L
            val rawV = (i + 1) * 33_367L
            assertEquals(rawA - rawV, aOut[i] - vOut[i])
        }
        // No oscillation: steady spacing across the three later samples, and
        // audio never went backwards.
        assertEquals(listOf(33_367L, 33_367L), vOut.zipWithNext { x, y -> y - x })
        assertEquals(listOf(32_000L, 32_000L), aOut.zipWithNext { x, y -> y - x })
        assertTrue((a0 + aOut).zipWithNext().all { (x, y) -> y > x })
        assertEquals(0, logs.count { it.contains("rebased PTS by") && it.contains("jump") })
    }

    @Test
    fun `repeated rebases request a splice reset`() {
        var clock = 0L
        val reasons = ArrayList<String>()
        val r = ContinuousTsExtractor.Rebaser({}, { clock }) { reasons.add(it) }
        val a = r.newTrack(C.TRACK_TYPE_AUDIO)
        r.map(a, 10_000_000L)
        r.map(a, 10_032_000L)
        r.map(a, 10_064_000L - 1_536_000_000L) // the fscod 3 artifact
        clock = 50
        r.map(a, 10_096_000L)
        assertEquals(listOf("pts"), reasons)
    }

    private fun ac3(rate: Int, ch: Int = 2, mime: String? = MimeTypes.AUDIO_AC3): Format =
        Format.Builder().setSampleMimeType(mime).setSampleRate(rate).setChannelCount(ch).build()

    private fun frame(g: Ac3FrameGuardOutput, timeUs: Long) {
        g.sampleData(ParsableByteArray(100), 100)
        g.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, 100, 0, null)
    }

    @Test
    fun `damaged AC-3 headers are dropped with the rest of their PES`() {
        val sink = Sink()
        val g = Ac3FrameGuardOutput(sink)
        g.onPesStart()
        g.format(ac3(48_000))
        frame(g, 0L)
        frame(g, 32_000L)
        // fscod 3: sampleRate NO_VALUE, then Ac3Reader's clock goes -1536 s.
        g.format(ac3(Format.NO_VALUE, mime = null))
        frame(g, 64_000L)
        g.format(ac3(48_000))
        frame(g, 64_000L - 1_536_000_000L)
        // Damaged header parsed as E-AC-3 on the next PES.
        g.onPesStart()
        g.format(ac3(48_000, 6, MimeTypes.AUDIO_E_AC3))
        frame(g, 96_000L)
        g.onPesStart()
        g.format(ac3(48_000))
        frame(g, 128_000L)
        assertEquals(listOf(0L, 32_000L, 128_000L), sink.times)
        assertEquals(1, sink.formats.size)
        assertEquals(MimeTypes.AUDIO_AC3, sink.formats[0].sampleMimeType)
        assertEquals(300, sink.bytes)
    }

    @Test
    fun `a persistent new audio format is followed`() {
        val sink = Sink()
        val g = Ac3FrameGuardOutput(sink) {}
        g.onPesStart()
        g.format(ac3(48_000))
        frame(g, 0L)
        g.format(ac3(48_000, 6))
        for (i in 1..Ac3FrameGuardOutput.RELOCK_FRAMES + 2) {
            g.onPesStart()
            frame(g, i * 32_000L)
        }
        assertEquals(2, sink.formats.size)
        assertEquals(6, sink.formats[1].channelCount)
        assertEquals(3, sink.times.size)
    }

    // ---- TS table sniffer ----

    private fun section(tableId: Int, body: ByteArray): ByteArray {
        val len = body.size + 4 + 5 // header after length: 5 bytes, body, crc
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

    private fun pmt(pmtPid: Int, videoPid: Int, audioPid: Int): ByteArray {
        val body = byteArrayOf(
            0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00, // PCR PID, program_info_length 0
            0x1B, (0xE0 or (videoPid shr 8)).toByte(), videoPid.toByte(), 0xF0.toByte(), 0x00,
            0x81.toByte(), (0xE0 or (audioPid shr 8)).toByte(), audioPid.toByte(), 0xF0.toByte(), 0x00,
        )
        return packet(pmtPid, section(2, body))
    }

    @Test
    fun `sniffer reports a changed elementary stream set and ignores repeats`() {
        val changes = ArrayList<String>()
        val s = TsTableSniffer { changes.add(it) }
        val stream = pat(0x1000) + pmt(0x1000, 0x100, 0x101) + pat(0x1000) + pmt(0x1000, 0x100, 0x101)
        s.feed(stream, 0, stream.size)
        assertTrue(changes.isEmpty())
        val switched = pat(0x1000) + pmt(0x1000, 0x200, 0x201)
        s.feed(switched, 0, switched.size)
        assertEquals(1, changes.size)
        val newPmt = pat(0x1100)
        s.feed(newPmt, 0, newPmt.size)
        assertEquals(2, changes.size)
    }

    @Test
    fun `sniffer ignores a section with a bad CRC`() {
        val changes = ArrayList<String>()
        val s = TsTableSniffer { changes.add(it) }
        val good = pat(0x1000) + pmt(0x1000, 0x100, 0x101)
        s.feed(good, 0, good.size)
        val bad = pmt(0x1000, 0x200, 0x201).also { it[20] = (it[20].toInt() xor 0x55).toByte() }
        s.feed(bad, 0, bad.size)
        assertTrue(changes.isEmpty())
    }
}
