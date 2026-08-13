package com.aeriotv.android.core.cast.hlsproxy

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pure-JVM checks for the cast HLS proxy remuxer against a synthetic
 * transport stream built in-test: crafted PAT/PMT plus H.264 (fake
 * SPS/PPS/IDR/non-IDR NALs) and ADTS AAC PES packets. Validates the P1
 * contract: init emitted once config is known, segments cut on keyframe
 * boundaries at the ~3 s target, baseMediaDecodeTime carried from PES
 * PTS including across the 33-bit wraparound, and the typed refusal for
 * codecs the pure remux cannot serve.
 */
class TsToFmp4RemuxerTest {

    private class Capture : TsToFmp4Remuxer.Listener {
        var init: ByteArray? = null
        var initCount = 0
        val segments = ArrayList<ByteArray>()
        val durations = ArrayList<Long>()
        override fun onInitSegment(data: ByteArray) {
            init = data
            initCount++
        }
        override fun onMediaSegment(data: ByteArray, durationTicks: Long) {
            segments.add(data)
            durations.add(durationTicks)
        }
    }

    // ---- fixture: TS packet crafting ----

    private val continuity = HashMap<Int, Int>()

    private fun tsPacket(pid: Int, payload: ByteArray, pusi: Boolean): ByteArray {
        require(payload.size <= 184)
        val cc = continuity.getOrDefault(pid, 0)
        continuity[pid] = (cc + 1) and 0x0F
        val pkt = ByteArray(188)
        pkt[0] = 0x47
        pkt[1] = (((if (pusi) 0x40 else 0x00) or ((pid shr 8) and 0x1F))).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        if (payload.size == 184) {
            pkt[3] = (0x10 or cc).toByte() // payload only
            System.arraycopy(payload, 0, pkt, 4, 184)
        } else {
            // Adaptation field used purely as stuffing so short payloads
            // still fill the fixed 188-byte packet.
            pkt[3] = (0x30 or cc).toByte()
            val afLen = 183 - payload.size
            pkt[4] = afLen.toByte()
            if (afLen > 0) {
                pkt[5] = 0x00 // adaptation flags: none
                for (i in 6 until 5 + afLen) pkt[i] = 0xFF.toByte()
            }
            System.arraycopy(payload, 0, pkt, 5 + afLen, payload.size)
        }
        return pkt
    }

    /** Chop an arbitrary elementary payload into TS packets, PUSI on the first. */
    private fun packetize(pid: Int, bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var off = 0
        var first = true
        while (off < bytes.size) {
            val n = minOf(184, bytes.size - off)
            out.write(tsPacket(pid, bytes.copyOfRange(off, off + n), first))
            first = false
            off += n
        }
        return out.toByteArray()
    }

    private fun patPacket(): ByteArray {
        val section = byteArrayOf(
            0x00, // table_id PAT
            0xB0.toByte(), 0x0D, // section_length 13
            0x00, 0x01, // transport_stream_id
            0xC1.toByte(), 0x00, 0x00, // version/current, section, last
            0x00, 0x01, // program_number 1
            0xE1.toByte(), 0x00, // PMT PID 0x100
            0x00, 0x00, 0x00, 0x00, // CRC (unchecked)
        )
        return tsPacket(0x0000, byteArrayOf(0x00) + section, pusi = true)
    }

    private fun pmtPacket(videoType: Int, audioType: Int?): ByteArray {
        val streams = ByteArrayOutputStream().apply {
            write(videoType); write(0xE1); write(0x01); write(0xF0); write(0x00) // PID 0x101
            if (audioType != null) {
                write(audioType); write(0xE1); write(0x02); write(0xF0); write(0x00) // PID 0x102
            }
        }.toByteArray()
        val sectionLen = 9 + streams.size + 4
        val section = ByteArrayOutputStream().apply {
            write(0x02) // table_id PMT
            write(0xB0); write(sectionLen)
            write(0x00); write(0x01) // program_number
            write(0xC1); write(0x00); write(0x00)
            write(0xE1); write(0x01) // PCR PID
            write(0xF0); write(0x00) // program_info_length 0
            write(streams)
            write(ByteArray(4)) // CRC
        }.toByteArray()
        return tsPacket(0x0100, byteArrayOf(0x00) + section, pusi = true)
    }

    private fun ptsBytes(marker: Int, ts: Long): ByteArray = byteArrayOf(
        ((marker shl 4) or (((ts shr 30) and 0x07).toInt() shl 1) or 1).toByte(),
        ((ts shr 22) and 0xFF).toByte(),
        ((((ts shr 15) and 0x7F).toInt() shl 1) or 1).toByte(),
        ((ts shr 7) and 0xFF).toByte(),
        (((ts and 0x7F).toInt() shl 1) or 1).toByte(),
    )

    private fun pes(streamId: Int, payload: ByteArray, pts: Long, dts: Long = pts): ByteArray {
        val hasDts = dts != pts
        val header = ByteArrayOutputStream().apply {
            write(0x00); write(0x00); write(0x01); write(streamId)
            write(0x00); write(0x00) // PES_packet_length 0 (unbounded, video norm)
            write(0x80)
            write(if (hasDts) 0xC0 else 0x80)
            write(if (hasDts) 10 else 5)
            write(ptsBytes(if (hasDts) 0x3 else 0x2, pts))
            if (hasDts) write(ptsBytes(0x1, dts))
        }.toByteArray()
        return header + payload
    }

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            out.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
            out.write(nal)
        }
        return out.toByteArray()
    }

    // Fake but structurally plausible parameter sets; the remuxer embeds
    // them verbatim in avcC (dimension parse failure falls back safely).
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E) + ByteArray(8) { (it + 1).toByte() }
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())

    private fun videoAu(pts: Long, dts: Long, keyframe: Boolean, withParamSets: Boolean = false): ByteArray {
        val slice = (if (keyframe) byteArrayOf(0x65) else byteArrayOf(0x41)) +
            ByteArray(64) { (it * 7).toByte() }
        val payload = if (withParamSets) annexB(sps, pps, slice) else annexB(slice)
        return packetize(0x0101, pes(0xE0, payload, pts, dts))
    }

    private fun adtsFrame(payloadSize: Int): ByteArray {
        val frameLen = 7 + payloadSize
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), // MPEG-4, layer 0, no CRC
            // profile LC(01)<<6 | freqIndex 3 (48 kHz)<<2 | priv 0 | chan hi 0
            0x4C,
            0x80.toByte(), // chan cfg 2 in the top bits
            ((frameLen shr 3) and 0xFF).toByte(),
            (((frameLen and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        ) + ByteArray(payloadSize) { (it * 3).toByte() }
    }

    private fun audioPes(pts: Long, frames: Int = 2): ByteArray {
        val body = ByteArrayOutputStream().apply { repeat(frames) { write(adtsFrame(32)) } }.toByteArray()
        return packetize(0x0102, pes(0xC0, body, pts))
    }

    // ---- helpers over emitted MP4 bytes ----

    private fun boxType(data: ByteArray, off: Int): String =
        String(data, off + 4, 4, Charsets.US_ASCII)

    private fun containsBox(data: ByteArray, type: String): Boolean {
        val needle = type.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..data.size - 4) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    /** baseMediaDecodeTime of the FIRST tfdt (video traf) in a segment. */
    private fun firstTfdt(seg: ByteArray): Long {
        for (i in 0..seg.size - 8) {
            if (seg[i + 4].toInt() == 't'.code && seg[i + 5].toInt() == 'f'.code &&
                seg[i + 6].toInt() == 'd'.code && seg[i + 7].toInt() == 't'.code
            ) {
                var v = 0L
                for (b in 0 until 8) v = (v shl 8) or (seg[i + 12 + b].toLong() and 0xFF)
                return v
            }
        }
        error("no tfdt in segment")
    }

    // ---- tests ----

    private val ticks = TsToFmp4Remuxer.TICKS_PER_SECOND
    private val frameTicks = 3_000L // 30 fps

    /** Feed a GOP-per-second stream: keyframe every 30 frames at 30 fps. */
    private fun feedGops(
        remuxer: TsToFmp4Remuxer,
        startPts: Long,
        frames: Int,
        withAudio: Boolean,
    ) {
        for (f in 0 until frames) {
            val pts33 = (startPts + f * frameTicks) and ((1L shl 33) - 1)
            val keyframe = f % 30 == 0
            val au = videoAu(pts33, pts33, keyframe, withParamSets = keyframe)
            remuxer.feed(au, 0, au.size)
            if (withAudio && f % 3 == 0) {
                val ap = audioPes(pts33)
                remuxer.feed(ap, 0, ap.size)
            }
        }
    }

    @Test
    fun `init and keyframe-cut segments with pts carried into tfdt`() {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(cap)
        val pat = patPacket()
        val pmt = pmtPacket(videoType = 0x1B, audioType = 0x0F)
        remuxer.feed(pat, 0, pat.size)
        remuxer.feed(pmt, 0, pmt.size)

        val t0 = 900_000L // arbitrary 10 s in
        // AAC config must be known before the first keyframe is queued or
        // the remuxer (correctly) drops video until the init can exist;
        // real ingest interleaves audio early, mirror that here.
        val warmAudio = audioPes(t0 - frameTicks)
        remuxer.feed(warmAudio, 0, warmAudio.size)
        // 121 frames = 4 s: the 3 s target cuts on the keyframe at t0+3 s
        // (1 s GOP cadence), closing a segment of exactly 90 frames.
        feedGops(remuxer, t0, frames = 121, withAudio = true)

        assertEquals("init emitted exactly once", 1, cap.initCount)
        val init = cap.init!!
        assertEquals("ftyp", boxType(init, 0))
        assertTrue(containsBox(init, "moov"))
        assertTrue(containsBox(init, "avcC"))
        assertTrue("audio track present", containsBox(init, "mp4a"))
        assertTrue("esds present", containsBox(init, "esds"))

        assertTrue("at least one segment", cap.segments.isNotEmpty())
        val seg = cap.segments[0]
        assertEquals("moof", boxType(seg, 0))
        assertTrue(containsBox(seg, "mdat"))
        // Keyframe cadence is 1 s GOPs, so the 3 s target cuts on the
        // keyframe 90 frames in.
        assertEquals(90 * frameTicks, cap.durations[0])
        // First segment starts the session timeline.
        assertEquals(0L, firstTfdt(seg))
    }

    @Test
    fun `pts wraparound does not break segment timeline`() {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(cap)
        val pat = patPacket()
        val pmt = pmtPacket(videoType = 0x1B, audioType = null) // video-only: simpler clock
        remuxer.feed(pat, 0, pat.size)
        remuxer.feed(pmt, 0, pmt.size)

        // Start 2 s before the 33-bit wrap point so the stream crosses it
        // inside the first segment. 331 frames = 11 s of video: cuts on
        // the 3/6/9 s keyframes close three segments.
        val wrap = 1L shl 33
        val t0 = wrap - 2 * ticks
        feedGops(remuxer, t0, frames = 331, withAudio = false)

        assertTrue("segments across the wrap", cap.segments.size >= 3)
        // Timeline continuity: each segment's tfdt is the sum of the
        // durations before it. A wraparound mishandled as a 26.5 h
        // backwards jump would shatter this.
        var expected = 0L
        for (i in cap.segments.indices) {
            assertEquals("tfdt of segment $i", expected, firstTfdt(cap.segments[i]))
            expected += cap.durations[i]
        }
        assertTrue(
            "durations stay exact across the wrap",
            cap.durations.all { abs(it - 3 * ticks) == 0L },
        )
    }

    /** The sync scan needs a verified triple 0x47 run before anything
     *  parses, so refusal fixtures must span at least three packets. */
    private fun refusalFor(videoType: Int, audioType: Int?): Throwable? {
        val remuxer = TsToFmp4Remuxer(Capture())
        val pmt = pmtPacket(videoType, audioType)
        val stream = patPacket() + pmt + pmt
        return runCatching { remuxer.feed(stream, 0, stream.size) }.exceptionOrNull()
    }

    @Test
    fun `non-h264 video is refused with the codec name`() {
        val thrown = refusalFor(videoType = 0x24, audioType = 0x0F) // HEVC
        assertTrue(thrown is UnsupportedCodecException)
        assertEquals("HEVC video", (thrown as UnsupportedCodecException).codecName)
    }

    @Test
    fun `ac3 audio is refused with the codec name`() {
        val thrown = refusalFor(videoType = 0x1B, audioType = 0x81)
        assertTrue(thrown is UnsupportedCodecException)
        assertEquals("AC-3 audio", (thrown as UnsupportedCodecException).codecName)
    }
}
