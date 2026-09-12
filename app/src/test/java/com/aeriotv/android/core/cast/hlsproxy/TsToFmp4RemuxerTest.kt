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
 * codecs the pure remux cannot serve. The audio cases cover the
 * 2026-09-12 model (no phone transcode at all): AC-3 passes through into
 * an ac-3 sample entry with its dac3 box when the receiver decodes it,
 * and refuses by name when it does not.
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
        var audioCodec: String? = null
        override fun onAudioCodec(name: String) {
            audioCodec = name
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
    fun `dts audio is refused with the codec name`() {
        val thrown = refusalFor(videoType = 0x1B, audioType = 0x82)
        assertTrue(thrown is UnsupportedCodecException)
        assertEquals("DTS audio", (thrown as UnsupportedCodecException).codecName)
    }

    // ---- AC-3 / E-AC-3 passthrough (no transcode since 2026-09-12) ----

    /** Minimal syncframe: 48 kHz, 32 kbps (64 words = 128 bytes),
     *  acmod 2 (stereo), lfeon 0. */
    private fun ac3Frame(): ByteArray {
        val frame = ByteArray(128)
        frame[0] = 0x0B; frame[1] = 0x77
        frame[4] = 0x00 // fscod 0, frmsizecod 0
        frame[5] = 0x40 // bsid 8, bsmod 0
        frame[6] = 0x40 // acmod 2, dsurmod 0, lfeon 0
        return frame
    }

    private fun ac3AudioPes(pts: Long, frames: Int = 2): ByteArray {
        val body = ByteArrayOutputStream().apply { repeat(frames) { write(ac3Frame()) } }.toByteArray()
        return packetize(0x0102, pes(0xBD, body, pts)) // private_stream_1, the AC-3 norm
    }

    @Test
    fun `ac3 passes through with an ac-3 sample entry when the receiver decodes it`() {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(listener = cap, allowAc3Passthrough = true)
        val pat = patPacket()
        val pmt = pmtPacket(videoType = 0x1B, audioType = 0x81)
        remuxer.feed(pat, 0, pat.size)
        remuxer.feed(pmt, 0, pmt.size)

        val t0 = 900_000L
        // Warm audio first so the syncframe config exists before the first
        // usable keyframe (a PES only completes when the next PUSI on its
        // PID flushes it, so interleaving is what makes it flow).
        val warm = ac3AudioPes(t0 - frameTicks)
        remuxer.feed(warm, 0, warm.size)
        for (f in 0 until 121) {
            val pts = t0 + f * frameTicks
            val keyframe = f % 30 == 0
            val au = videoAu(pts, pts, keyframe, withParamSets = keyframe)
            remuxer.feed(au, 0, au.size)
            if (f % 3 == 0) {
                val ap = ac3AudioPes(pts)
                remuxer.feed(ap, 0, ap.size)
            }
        }

        assertEquals("audio codec reported from the PMT", "AC-3", cap.audioCodec)
        assertEquals("init emitted exactly once", 1, cap.initCount)
        val init = cap.init!!
        assertTrue("ac-3 sample entry", containsBox(init, "ac-3"))
        assertTrue("dac3 config box", containsBox(init, "dac3"))
        assertTrue("no AAC sample entry", !containsBox(init, "mp4a"))
        assertTrue("no esds on a passthrough AC-3 track", !containsBox(init, "esds"))
        assertTrue("segments produced", cap.segments.isNotEmpty())
    }

    @Test
    fun `ac3 refuses by name when the receiver cannot decode it`() {
        val thrown = refusalFor(videoType = 0x1B, audioType = 0x81) // no passthrough allowed
        assertTrue(thrown is UnsupportedCodecException)
        thrown as UnsupportedCodecException
        assertEquals("AC-3 audio", thrown.codecName)
        assertTrue("refusal is flagged as audio", !thrown.isVideo)
    }

    @Test
    fun `mp2 audio refuses even on an ac3-capable receiver`() {
        val remuxer = TsToFmp4Remuxer(listener = Capture(), allowAc3Passthrough = true)
        val pmt = pmtPacket(videoType = 0x1B, audioType = 0x04)
        val stream = patPacket() + pmt + pmt
        val thrown = runCatching { remuxer.feed(stream, 0, stream.size) }.exceptionOrNull()
        assertTrue(thrown is UnsupportedCodecException)
        assertEquals("MP2 audio", (thrown as UnsupportedCodecException).codecName)
    }

    @Test
    fun `aac latm is refused loudly rather than parsed as adts`() {
        // stream_type 0x11 is LATM/LOAS, NOT the ADTS 0x0F this remux
        // strips headers from. Treating it as ADTS would queue garbage
        // samples into an mp4a track and fail on the receiver with no
        // explanation, so the PMT refuses it by name (2026-09-12).
        val thrown = refusalFor(videoType = 0x1B, audioType = 0x11)
        assertTrue(thrown is UnsupportedCodecException)
        thrown as UnsupportedCodecException
        assertEquals("AAC-LATM audio", thrown.codecName)
        assertTrue("refusal is flagged as audio", !thrown.isVideo)
    }

    /** ADTS frame WITH the CRC word: protection_absent is 0, so the
     *  header is 9 bytes, not 7, and the raw AAC payload starts later. */
    private fun adtsFrameWithCrc(payloadSize: Int): ByteArray {
        val frameLen = 9 + payloadSize
        return byteArrayOf(
            0xFF.toByte(), 0xF0.toByte(), // MPEG-4, layer 0, protection_absent 0
            0x4C, // profile LC, freqIndex 3 (48 kHz)
            0x80.toByte(), // channel config 2
            ((frameLen shr 3) and 0xFF).toByte(),
            (((frameLen and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
            0x00, 0x00, // CRC word
        ) + ByteArray(payloadSize) { (it * 3).toByte() }
    }

    @Test
    fun `adts with crc strips the full nine byte header`() {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(cap)
        val pat = patPacket()
        val pmt = pmtPacket(videoType = 0x1B, audioType = 0x0F)
        remuxer.feed(pat, 0, pat.size)
        remuxer.feed(pmt, 0, pmt.size)

        val t0 = 900_000L
        val payloadSize = 32
        // Warm the AAC config with a CRC-bearing frame, then run a normal
        // GOP pattern whose audio PES also carries CRC frames.
        fun crcPes(pts: Long): ByteArray {
            val body = ByteArrayOutputStream().apply { repeat(2) { write(adtsFrameWithCrc(payloadSize)) } }
            return packetize(0x0102, pes(0xC0, body.toByteArray(), pts))
        }
        val warm = crcPes(t0 - frameTicks)
        remuxer.feed(warm, 0, warm.size)
        for (f in 0 until 121) {
            val pts = t0 + f * frameTicks
            val keyframe = f % 30 == 0
            val au = videoAu(pts, pts, keyframe, withParamSets = keyframe)
            remuxer.feed(au, 0, au.size)
            if (f % 3 == 0) {
                val ap = crcPes(pts)
                remuxer.feed(ap, 0, ap.size)
            }
        }

        assertTrue("init emitted", cap.init != null)
        assertTrue("segments produced", cap.segments.isNotEmpty())
        // Every audio sample must be exactly the payload: a 7-byte
        // assumption would leave the 2-byte CRC word prefixed to each
        // frame and the decoder would reject the whole track.
        val sizes = audioSampleSizes(cap.segments[0])
        assertTrue("audio samples present", sizes.isNotEmpty())
        assertTrue(
            "every audio sample is the raw payload, got $sizes",
            sizes.all { it == payloadSize },
        )
    }

    /** Sample sizes from the SECOND traf's trun (the audio track). */
    private fun audioSampleSizes(seg: ByteArray): List<Int> {
        var i = 0
        while (i + 8 <= seg.size) {
            val size = be32(seg, i)
            if (size <= 0) return emptyList()
            if (String(seg, i + 4, 4, Charsets.US_ASCII) == "moof") {
                val trafs = ArrayList<Pair<Int, Int>>()
                var j = i + 8
                while (j + 8 <= i + size) {
                    val s = be32(seg, j)
                    if (s <= 0) break
                    if (String(seg, j + 4, 4, Charsets.US_ASCII) == "traf") trafs.add(Pair(j, s))
                    j += s
                }
                if (trafs.size < 2) return emptyList()
                val (off, sz) = trafs[1]
                var k = off + 8
                while (k + 8 <= off + sz) {
                    val s = be32(seg, k)
                    if (s <= 0) break
                    if (String(seg, k + 4, 4, Charsets.US_ASCII) == "trun") {
                        val count = be32(seg, k + 12)
                        // trun body: sample_count(4) data_offset(4) then
                        // duration/size pairs (flags 0x000301, version 0).
                        return (0 until count).map { n -> be32(seg, k + 24 + n * 8) }
                    }
                    k += s
                }
                return emptyList()
            }
            i += size
        }
        return emptyList()
    }

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    // ---- Chromium init-segment requirements (2026-09-12) ----

    /**
     * An ADTS frame with an arbitrary sampling_frequency_index and
     * channel_configuration, so a test can present the header shapes that
     * no two-byte AudioSpecificConfig can express.
     */
    private fun adtsFrameCfg(freqIndex: Int, chanConfig: Int, payloadSize: Int): ByteArray {
        val frameLen = 7 + payloadSize
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), // MPEG-4, layer 0, no CRC
            (((1 shl 6) or (freqIndex shl 2)) or ((chanConfig shr 2) and 0x01)).toByte(),
            (((chanConfig and 0x03) shl 6)).toByte(),
            ((frameLen shr 3) and 0xFF).toByte(),
            (((frameLen and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        ) + ByteArray(payloadSize) { (it * 3).toByte() }
    }

    /** One box in a walked tree: type, total size, and payload bounds. */
    private class Mp4Box(val type: String, val start: Int, val size: Int, val bodyStart: Int) {
        val end get() = start + size
    }

    /**
     * Children of the box body [from, to), asserting they tile it exactly.
     * Chromium's BoxReader::ScanChildren walks until pos == box_size and
     * fails on any child whose declared size runs past the parent, so a
     * parent whose children do not exactly fill it is a parse failure, not
     * a cosmetic flaw.
     */
    private fun childBoxes(d: ByteArray, from: Int, to: Int, parent: String): List<Mp4Box> {
        val out = ArrayList<Mp4Box>()
        var off = from
        while (off < to) {
            assertTrue("$parent: child header truncated at $off", off + 8 <= to)
            val size = be32(d, off)
            assertTrue("$parent: child at $off declares size $size", size >= 8 && off + size <= to)
            out.add(Mp4Box(String(d, off + 4, 4, Charsets.US_ASCII), off, size, off + 8))
            off += size
        }
        assertEquals("$parent children must tile the box exactly", to, off)
        return out
    }

    private fun child(boxes: List<Mp4Box>, type: String, parent: String): Mp4Box =
        boxes.firstOrNull { it.type == type } ?: error("$parent is missing a required $type box")

    /**
     * Every structural rule Chromium's media/formats/mp4 parser enforces
     * on an init segment, read off the bytes the remuxer actually emits.
     * Read against box_definitions.cc, box_reader.cc, es_descriptor.cc,
     * aac.cc and mp4_stream_parser.cc and confirmed against a real
     * Chromium MSE SourceBuffer on 2026-09-12: every rule below, when
     * broken, turns into the receiver's "Append: stream parsing failed" on
     * the init segment while ffprobe still reads the file happily.
     */
    private fun assertChromiumParsableInit(init: ByteArray, expectAudio: Boolean) {
        val top = childBoxes(init, 0, init.size, "init")
        assertEquals("init must be ftyp then moov", listOf("ftyp", "moov"), top.map { it.type })

        val moov = top[1]
        val moovKids = childBoxes(init, moov.bodyStart, moov.end, "moov")
        // Movie::Parse: mvhd and mvex are both REQUIRED, and mvex absent
        // is reported as "Detected unfragmented MP4".
        val mvhd = child(moovKids, "mvhd", "moov")
        assertEquals("mvhd must be version 0 here", 0, init[mvhd.bodyStart].toInt())
        assertTrue("MovieHeader timescale must not be 0", be32(init, mvhd.bodyStart + 12) > 0)
        val traks = moovKids.filter { it.type == "trak" }
        assertEquals("one video track plus audio when present", if (expectAudio) 2 else 1, traks.size)

        val mvex = child(moovKids, "mvex", "moov")
        val trexes = childBoxes(init, mvex.bodyStart, mvex.end, "mvex").filter { it.type == "trex" }
        assertEquals("one trex per trak", traks.size, trexes.size)
        val trexTracks = HashSet<Int>()
        for (trex in trexes) {
            val trackId = be32(init, trex.bodyStart + 4)
            // ParseMoov: RCHECK(desc_idx > 0) on the trex's
            // default_sample_description_index, which is one-based.
            assertTrue(
                "trex default_sample_description_index must be >= 1",
                be32(init, trex.bodyStart + 8) >= 1,
            )
            assertTrue("duplicate trex track id $trackId", trexTracks.add(trackId))
        }

        val seenTrackIds = HashSet<Int>()
        var sawAudio = false
        for (trak in traks) {
            val trakKids = childBoxes(init, trak.bodyStart, trak.end, "trak")
            val tkhd = child(trakKids, "tkhd", "trak")
            val trackId = be32(init, tkhd.bodyStart + 12)
            // ParseMoov rejects a duplicate track ID outright.
            assertTrue("duplicate track id $trackId in moov", seenTrackIds.add(trackId))
            assertTrue("track $trackId has no trex", trexTracks.contains(trackId))

            val mdia = child(trakKids, "mdia", "trak")
            val mdiaKids = childBoxes(init, mdia.bodyStart, mdia.end, "mdia")
            // Media::Parse requires mdhd, hdlr and minf, in any order.
            val mdhd = child(mdiaKids, "mdhd", "mdia")
            assertTrue(
                "MediaHeader timescale must not be 0",
                be32(init, mdhd.bodyStart + 12) > 0,
            )
            val hdlr = child(mdiaKids, "hdlr", "mdia")
            val handler = String(init, hdlr.bodyStart + 8, 4, Charsets.US_ASCII)
            assertTrue("handler must be vide or soun, got $handler", handler == "vide" || handler == "soun")
            // HandlerReference::Parse reads the rest of the box as the
            // name and, when the last byte is NOT zero, re-reads byte 0 as
            // a Pascal length that must equal size - 1. A name that is
            // neither NUL-terminated nor correctly counted fails there.
            assertEquals(
                "hdlr name must be NUL-terminated",
                0,
                init[hdlr.end - 1].toInt(),
            )

            val minf = child(mdiaKids, "minf", "mdia")
            val minfKids = childBoxes(init, minf.bodyStart, minf.end, "minf")
            val stbl = child(minfKids, "stbl", "minf")
            val stblKids = childBoxes(init, stbl.bodyStart, stbl.end, "stbl")
            val stsd = child(stblKids, "stsd", "stbl")
            // SampleDescription::Parse: full box header, entry count, then
            // the entries as children filling the rest of the box.
            assertEquals("one sample entry", 1, be32(init, stsd.bodyStart + 4))
            val entries = childBoxes(init, stsd.bodyStart + 8, stsd.end, "stsd")
            assertEquals("stsd entry count must match its children", 1, entries.size)
            val entry = entries[0]

            if (handler == "vide") {
                assertEquals("video sample entry format", "avc1", entry.type)
                // VideoSampleEntry::Parse consumes a fixed 78-byte
                // preamble before scanning children, so avcC must begin at
                // body + 78 and width/height sit at body + 24.
                val width = ((init[entry.bodyStart + 24].toInt() and 0xFF) shl 8) or
                    (init[entry.bodyStart + 25].toInt() and 0xFF)
                val height = ((init[entry.bodyStart + 26].toInt() and 0xFF) shl 8) or
                    (init[entry.bodyStart + 27].toInt() and 0xFF)
                // coded_size feeds VideoDecoderConfig::IsValidConfig.
                assertTrue("avc1 width must be 1..32767, got $width", width in 1..32767)
                assertTrue("avc1 height must be 1..32767, got $height", height in 1..32767)
                // tkhd width/height are read as 16.16 and become the
                // display aspect ratio, so they must agree with the entry.
                assertEquals("tkhd width matches avc1", width, be32(init, tkhd.end - 8) ushr 16)
                assertEquals("tkhd height matches avc1", height, be32(init, tkhd.end - 4) ushr 16)

                val avcC = child(
                    childBoxes(init, entry.bodyStart + 78, entry.end, "avc1"),
                    "avcC",
                    "avc1",
                )
                assertEquals("avcC configurationVersion must be 1", 1, init[avcC.bodyStart].toInt())
                val profileIdc = init[avcC.bodyStart + 1].toInt() and 0xFF
                // Anything outside this set maps to
                // VIDEO_CODEC_PROFILE_UNKNOWN and VideoSampleEntry::Parse
                // then fails with "Unrecognized video codec profile".
                assertTrue(
                    "avcC profile_indication $profileIdc is not one Chromium maps",
                    profileIdc in setOf(66, 77, 88, 100, 110, 122, 244),
                )
                // lengthSizeMinusOne: Chromium computes
                // length_size = (value & 3) + 1 and rejects a length_size
                // of 3, so the encoded value 2 is the illegal one. We emit
                // 0xFF, which is the usual "reserved bits set" spelling of
                // a 4-byte NAL length.
                assertTrue(
                    "avcC NAL length size must be 1, 2 or 4",
                    (init[avcC.bodyStart + 4].toInt() and 0x03) != 2,
                )
                val numSps = init[avcC.bodyStart + 5].toInt() and 0x1F
                assertTrue("avcC must carry at least one SPS", numSps >= 1)
                val spsLen = ((init[avcC.bodyStart + 6].toInt() and 0xFF) shl 8) or
                    (init[avcC.bodyStart + 7].toInt() and 0xFF)
                assertTrue("avcC SPS must not be empty", spsLen > 0)
                assertTrue("avcC SPS must fit the box", avcC.bodyStart + 8 + spsLen <= avcC.end)
            } else {
                sawAudio = true
                assertTrue(
                    "audio sample entry format ${entry.type}",
                    entry.type in setOf("mp4a", "ac-3", "ec-3"),
                )
                // AudioSampleEntry::Parse consumes a fixed 28-byte
                // preamble, so the config box starts at body + 28.
                val channels = ((init[entry.bodyStart + 16].toInt() and 0xFF) shl 8) or
                    (init[entry.bodyStart + 17].toInt() and 0xFF)
                val sampleSize = ((init[entry.bodyStart + 18].toInt() and 0xFF) shl 8) or
                    (init[entry.bodyStart + 19].toInt() and 0xFF)
                val sampleRate = be32(init, entry.bodyStart + 24) ushr 16
                // ParseMoov maps samplesize to a SampleFormat and rejects
                // anything but 8, 16, 24 or 32.
                assertTrue("samplesize $sampleSize has no SampleFormat", sampleSize in setOf(8, 16, 24, 32))
                assertTrue("audio channelcount must be 1..8, got $channels", channels in 1..8)
                assertTrue("audio samplerate must be > 0", sampleRate > 0)

                val configKids = childBoxes(init, entry.bodyStart + 28, entry.end, entry.type)
                if (entry.type == "mp4a") {
                    val esds = child(configKids, "esds", "mp4a")
                    // ESDescriptor::Parse walks ES_Descriptor(0x03) >
                    // DecoderConfigDescriptor(0x04) >
                    // DecoderSpecificInfo(0x05). Tags and the one-byte
                    // sizes we emit must line up exactly or it bails.
                    var q = esds.bodyStart + 4 // past version/flags
                    assertEquals("ES_Descriptor tag", 0x03, init[q].toInt() and 0xFF)
                    val esSize = init[q + 1].toInt() and 0xFF
                    assertTrue("ES_Descriptor size must be a single byte here", esSize < 0x80)
                    assertEquals("ES_Descriptor size must reach the box end", esds.end, q + 2 + esSize)
                    q += 2 + 3 // ES_ID(2) + flags(1)
                    assertEquals("DecoderConfigDescriptor tag", 0x04, init[q].toInt() and 0xFF)
                    val dcdSize = init[q + 1].toInt() and 0xFF
                    assertTrue("DecoderConfigDescriptor size must be a single byte", dcdSize < 0x80)
                    assertEquals(
                        "objectTypeIndication must be 0x40 (MPEG-4 audio)",
                        0x40,
                        init[q + 2].toInt() and 0xFF,
                    )
                    q += 2 + 13 // the 13 fixed DecoderConfigDescriptor bytes
                    assertEquals("DecoderSpecificInfo tag", 0x05, init[q].toInt() and 0xFF)
                    val ascLen = init[q + 1].toInt() and 0xFF
                    assertEquals("a two-byte AudioSpecificConfig", 2, ascLen)
                    val a0 = init[q + 2].toInt() and 0xFF
                    val a1 = init[q + 3].toInt() and 0xFF
                    val objectType = a0 shr 3
                    val freqIndex = ((a0 and 0x07) shl 1) or (a1 shr 7)
                    val chanConfig = (a1 shr 3) and 0x0F
                    // AAC::Parse: profile outside 1..4 (plus the HE and
                    // xHE signals we never emit) is refused outright.
                    assertTrue("ASC audioObjectType $objectType is not AAC 1..4", objectType in 1..4)
                    // A two-byte ASC has room for exactly
                    // 5 + 4 + 4 + 3 GASpecificConfig bits = 16. Index 15
                    // would need 24 more bits, 13 and 14 are reserved and
                    // resolve to a 0 Hz rate, and channel_config 0 sends
                    // Chromium looking for a Program Config Element that
                    // is not there. Each case fails the append.
                    assertTrue("ASC sampling_frequency_index $freqIndex is not 0..12", freqIndex in 0..12)
                    assertTrue("ASC channel_configuration $chanConfig is not 1..7", chanConfig in 1..7)
                    // Chromium derives the decoder config from the ASC and
                    // compares it with the sample entry. The mp4a box must
                    // not advertise a rate or layout the ASC contradicts.
                    val ascRates = intArrayOf(
                        96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000,
                        22_050, 16_000, 12_000, 11_025, 8_000, 7_350,
                    )
                    // 16.16 cannot hold an integer part above 65535, so
                    // the sample entry carries the clamped rate; Chromium
                    // takes the real one from the ASC.
                    assertEquals(
                        "mp4a samplerate must match the ASC",
                        ascRates[freqIndex].coerceAtMost(65_535),
                        sampleRate,
                    )
                    // Table 1.19: the channel COUNT, which differs from the
                    // configuration number at config 7 (7.1 is 8 channels).
                    val ascChannels = intArrayOf(2, 1, 2, 3, 4, 5, 6, 8)[chanConfig]
                    assertEquals("mp4a channelcount must match the ASC", ascChannels, channels)
                } else {
                    // ETSI TS 102 366 Annex F: without its config box the
                    // AC-3 / E-AC-3 sample entry has no channel layout and
                    // ParseMoov refuses a zero channelcount fallback.
                    child(configKids, if (entry.type == "ac-3") "dac3" else "dec3", entry.type)
                }
            }
        }
        assertEquals("audio track present", expectAudio, sawAudio)
    }

    /** The AAC init the AAC path emits, against every Chromium rule. */
    @Test
    fun `aac init segment satisfies every chromium mp4 parser rule`() {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(cap)
        val pat = patPacket()
        val pmt = pmtPacket(videoType = 0x1B, audioType = 0x0F)
        remuxer.feed(pat, 0, pat.size)
        remuxer.feed(pmt, 0, pmt.size)
        feedGops(remuxer, startPts = 900_000L, frames = 61, withAudio = true)
        assertChromiumParsableInit(cap.init!!, expectAudio = true)
    }

    /** The same rules over the AC-3 passthrough entry, which must keep its
     *  dac3 box and a nonzero channel count. */
    @Test
    fun `ac3 init segment satisfies every chromium mp4 parser rule`() {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(cap, allowAc3Passthrough = true)
        val pat = patPacket()
        val pmt = pmtPacket(videoType = 0x1B, audioType = 0x81)
        remuxer.feed(pat, 0, pat.size)
        remuxer.feed(pmt, 0, pmt.size)
        val t0 = 900_000L
        val warm = ac3AudioPes(t0 - frameTicks)
        remuxer.feed(warm, 0, warm.size)
        for (f in 0 until 61) {
            val pts = t0 + f * frameTicks
            val au = videoAu(pts, pts, f % 30 == 0, withParamSets = f % 30 == 0)
            remuxer.feed(au, 0, au.size)
            if (f % 3 == 0) {
                val ap = ac3AudioPes(pts)
                remuxer.feed(ap, 0, ap.size)
            }
        }
        assertChromiumParsableInit(cap.init!!, expectAudio = true)
    }

    /** The ASC fields the emitted init actually carries. */
    private fun ascOf(init: ByteArray): Triple<Int, Int, Int> {
        val i = init.indices.first { k ->
            k + 4 <= init.size && String(init, k, 4, Charsets.US_ASCII) == "esds"
        }
        val q = init.indexOfFirst2(0x05, 0x02, from = i) + 2
        val a0 = init[q].toInt() and 0xFF
        val a1 = init[q + 1].toInt() and 0xFF
        return Triple(a0 shr 3, ((a0 and 0x07) shl 1) or (a1 shr 7), (a1 shr 3) and 0x0F)
    }

    private fun ByteArray.indexOfFirst2(a: Int, b: Int, from: Int): Int {
        for (i in from until size - 1) {
            if ((this[i].toInt() and 0xFF) == a && (this[i + 1].toInt() and 0xFF) == b) return i
        }
        error("no $a $b pair after $from")
    }

    /** Feed a GOP stream whose audio PES carries the given ADTS config. */
    private fun feedWithAdtsConfig(
        remuxer: TsToFmp4Remuxer,
        freqIndex: Int,
        chanConfig: Int,
    ) {
        val pat = patPacket()
        val pmt = pmtPacket(videoType = 0x1B, audioType = 0x0F)
        remuxer.feed(pat, 0, pat.size)
        remuxer.feed(pmt, 0, pmt.size)
        val t0 = 900_000L
        for (f in 0 until 61) {
            val pts = t0 + f * frameTicks
            val au = videoAu(pts, pts, f % 30 == 0, withParamSets = f % 30 == 0)
            remuxer.feed(au, 0, au.size)
            if (f % 3 == 0) {
                val body = ByteArrayOutputStream().apply {
                    repeat(3) { write(adtsFrameCfg(freqIndex, chanConfig, payloadSize = 32)) }
                }.toByteArray()
                val ap = packetize(0x0102, pes(0xC0, body, pts))
                remuxer.feed(ap, 0, ap.size)
            }
        }
    }

    /**
     * Dispatcharr's ffmpeg AAC encoder emits channel_configuration 0 (it
     * logs "Using a PCE to encode channel layout") whenever the AC-3 source
     * layout is outside Table 1.19: 2.1, 3.1, 6.1 and 7.0 all do it. A zero
     * copied into the ASC fails Chromium's SkipDecoderGASpecificConfig and
     * takes the whole init append down, which the old coerceAtLeast(1) on
     * the sample entry hid without fixing.
     */
    @Test
    fun `adts channel configuration zero is sanitized to stereo in the asc`() {
        val logs = ArrayList<String>()
        val cap = Capture()
        feedWithAdtsConfig(TsToFmp4Remuxer(cap, log = { logs.add(it) }), freqIndex = 3, chanConfig = 0)
        val init = cap.init!!
        assertChromiumParsableInit(init, expectAudio = true)
        val (objectType, freqIndex, chanConfig) = ascOf(init)
        assertEquals("AAC-LC preserved", 2, objectType)
        assertEquals("48 kHz preserved", 3, freqIndex)
        assertEquals("channel config 0 becomes stereo", 2, chanConfig)
        assertTrue(
            "the substitution must be logged once: $logs",
            logs.any { it.contains("channel_configuration 0 -> 2") },
        )
    }

    /** A reserved sampling_frequency_index resolves to 0 Hz in Chromium, so
     *  it cannot reach the ASC either. */
    @Test
    fun `reserved adts sampling frequency index is sanitized in the asc`() {
        val logs = ArrayList<String>()
        val cap = Capture()
        feedWithAdtsConfig(TsToFmp4Remuxer(cap, log = { logs.add(it) }), freqIndex = 13, chanConfig = 2)
        val init = cap.init!!
        assertChromiumParsableInit(init, expectAudio = true)
        val (_, freqIndex, chanConfig) = ascOf(init)
        assertEquals("reserved index 13 becomes 48 kHz", 3, freqIndex)
        assertEquals("stereo preserved", 2, chanConfig)
        assertTrue(
            "the substitution must be logged once: $logs",
            logs.any { it.contains("sampling_frequency_index 13 -> 3") },
        )
    }

    /** Index 15 would need a 24-bit explicit rate that a two-byte ASC has
     *  no room for, so Chromium reads past the end of the descriptor. */
    @Test
    fun `explicit rate sampling frequency index is sanitized in the asc`() {
        val cap = Capture()
        feedWithAdtsConfig(TsToFmp4Remuxer(cap), freqIndex = 15, chanConfig = 2)
        assertChromiumParsableInit(cap.init!!, expectAudio = true)
        assertEquals("index 15 becomes 48 kHz", 3, ascOf(cap.init!!).second)
    }

    /**
     * 96 kHz is index 0, and `96000 shl 16` overflows a 32-bit 16.16 value
     * (it wrapped to 30464 Hz). The sample entry must carry the clamped
     * 65535 rather than a wrapped one; Chromium reads the true rate from
     * the ASC next to it.
     */
    @Test
    fun `96 kHz audio does not wrap the 16 16 sample rate`() {
        val cap = Capture()
        feedWithAdtsConfig(TsToFmp4Remuxer(cap), freqIndex = 0, chanConfig = 2)
        val init = cap.init!!
        assertChromiumParsableInit(init, expectAudio = true)
        assertEquals("96 kHz survives into the ASC", 0, ascOf(init).second)
        val mp4a = init.indices.first { i ->
            i + 4 <= init.size && String(init, i, 4, Charsets.US_ASCII) == "mp4a"
        }
        assertEquals(
            "16.16 sample rate clamped, not wrapped",
            65_535,
            be32(init, mp4a + 4 + 24) ushr 16,
        )
    }

    /** Table 1.19 config 7 is 7.1: EIGHT channels, not seven. The sample
     *  entry's channelcount must be the count, not the config number. */
    @Test
    fun `channel configuration seven reports eight channels`() {
        val cap = Capture()
        feedWithAdtsConfig(TsToFmp4Remuxer(cap), freqIndex = 3, chanConfig = 7)
        val init = cap.init!!
        assertChromiumParsableInit(init, expectAudio = true)
        assertEquals("config 7 survives into the ASC", 7, ascOf(init).third)
    }

    /**
     * The codec config is latched from the FIRST ADTS header seen and then
     * never revisited, so a single false 0xFFFx hit inside frame payload
     * used to poison the esds for the whole session. The next frame must
     * start on a syncword before a header is believed, the same rule the
     * AC-3 path has always applied.
     */
    @Test
    fun `a false adts syncword does not latch the codec config`() {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(cap)
        val pat = patPacket()
        val pmt = pmtPacket(videoType = 0x1B, audioType = 0x0F)
        remuxer.feed(pat, 0, pat.size)
        remuxer.feed(pmt, 0, pmt.size)
        // A header-shaped 7 bytes claiming index 13 and a 30-byte frame:
        // offset 30 lands inside the FIRST real frame's payload, so the
        // confirmation fails and the scan walks on to the real header.
        val falseSync = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            (((1 shl 6) or (13 shl 2))).toByte(), 0x80.toByte(),
            ((30 shr 3) and 0xFF).toByte(), (((30 and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
        val t0 = 900_000L
        for (f in 0 until 61) {
            val pts = t0 + f * frameTicks
            val au = videoAu(pts, pts, f % 30 == 0, withParamSets = f % 30 == 0)
            remuxer.feed(au, 0, au.size)
            if (f % 3 == 0) {
                val body = ByteArrayOutputStream().apply {
                    if (f == 0) write(falseSync)
                    repeat(2) { write(adtsFrame(32)) }
                }.toByteArray()
                val ap = packetize(0x0102, pes(0xC0, body, pts))
                remuxer.feed(ap, 0, ap.size)
            }
        }
        val init = cap.init!!
        assertChromiumParsableInit(init, expectAudio = true)
        // The real frames are 48 kHz stereo; the false header claimed a
        // reserved index, which would have refused the audio entirely.
        val mp4a = init.indices.first { i ->
            i + 4 <= init.size && String(init, i, 4, Charsets.US_ASCII) == "mp4a"
        }
        val body = mp4a + 4
        assertEquals("channelcount latched from the real header", 2, init[body + 17].toInt())
        assertEquals("samplerate latched from the real header", 48_000, be32(init, body + 24) ushr 16)
    }

}
