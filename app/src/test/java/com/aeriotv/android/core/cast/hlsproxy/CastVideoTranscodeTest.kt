package com.aeriotv.android.core.cast.hlsproxy

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cast video transcode (iOS Scripts/cast-hls-proxy-tests section 17
 * parity, 2026-09-26). The MediaCodec sessions cannot run on the JVM;
 * what runs is everything around them: the SPS facts the plan reads, the
 * plan's decision per receiver, the codec strings the master declares, the
 * hvcC / avcC records, and the remuxer's transcode wiring driven by a fake
 * transcoder that re-stamps like the real one (PTS kept, DTS = PTS, forced
 * IDR every 3 s, a few frames of encoder latency).
 */
class CastVideoTranscodeTest {

    // ---- bitstream fixtures ----

    private class BitWriter {
        val bytes = ArrayList<Byte>()
        private var nbits = 0
        fun put(v: Long, n: Int) {
            for (i in n - 1 downTo 0) {
                if (nbits % 8 == 0) bytes.add(0)
                if ((v shr i) and 1L == 1L) {
                    val last = bytes.size - 1
                    bytes[last] = (bytes[last].toInt() or (0x80 ushr (nbits % 8))).toByte()
                }
                nbits++
            }
        }
        fun put(v: Int, n: Int) = put(v.toLong(), n)
        fun ue(v: Int) {
            val x = v + 1
            var len = 0
            while ((x shr len) > 1) len++
            put(0, len)
            put(x, len + 1)
        }
        fun trailing() {
            put(1, 1)
            while (nbits % 8 != 0) put(0, 1)
        }
        fun toByteArray() = bytes.toByteArray()
    }

    /** Insert emulation prevention bytes (00 00 0x -> 00 00 03 0x, x <= 3). */
    private fun escapeRbsp(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + 8)
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) { out.write(3); zeros = 0 }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** H.264 High level 4.2 1920x1080 SPS with VUI: BT.709, 59.94 fps
     *  (1001 / 120000), max_num_reorder_frames 2. */
    private fun h264Sps1080p60(): ByteArray {
        val w = BitWriter()
        w.put(100, 8); w.put(0, 8); w.put(42, 8)
        w.ue(0) // sps id
        w.ue(1); w.ue(0); w.ue(0); w.put(0, 1); w.put(0, 1) // chroma, depths, qpprime, no scaling
        w.ue(0); w.ue(0); w.ue(0) // log2_max_frame_num, poc type 0, log2_max_poc_lsb
        w.ue(4); w.put(0, 1) // max refs, gaps
        w.ue(119); w.ue(67) // 120 x 68 MBs
        w.put(1, 1); w.put(1, 1) // frame_mbs_only, direct_8x8
        w.put(1, 1); w.ue(0); w.ue(0); w.ue(0); w.ue(4) // crop 1088 -> 1080
        w.put(1, 1) // vui
        w.put(0, 1); w.put(0, 1) // aspect, overscan
        w.put(1, 1); w.put(5, 3); w.put(0, 1); w.put(1, 1); w.put(1, 8); w.put(1, 8); w.put(1, 8)
        w.put(0, 1) // chroma loc
        w.put(1, 1); w.put(1001L, 32); w.put(120_000L, 32); w.put(1, 1)
        w.put(0, 1); w.put(0, 1); w.put(0, 1) // nal hrd, vcl hrd, pic_struct
        w.put(1, 1); w.put(1, 1); w.ue(0); w.ue(0); w.ue(16); w.ue(16); w.ue(2); w.ue(4)
        w.trailing()
        return byteArrayOf(0x67) + escapeRbsp(w.toByteArray())
    }

    /** HEVC Main level 5.1 1920x1080 parameter sets (only the SPS is parsed). */
    private fun hevcParameterSets(): Triple<ByteArray, ByteArray, ByteArray> {
        val w = BitWriter()
        w.put(0, 4); w.put(0, 3); w.put(1, 1) // vps id, max_sub_layers_minus1 0, nesting
        w.put(0, 2); w.put(0, 1); w.put(1, 5) // space, tier, profile_idc 1 (Main)
        w.put(0x6000_0000L, 32) // compat flags 1 and 2
        w.put(0xB0, 8); w.put(0L, 40) // progressive, non-packed, frame-only
        w.put(153, 8) // level 5.1
        w.ue(0) // sps id
        w.ue(1) // 4:2:0
        w.ue(1920); w.ue(1088)
        w.put(1, 1); w.ue(0); w.ue(0); w.ue(0); w.ue(4) // conformance 1088 -> 1080
        w.ue(0); w.ue(0) // 8-bit
        w.trailing()
        val sps = byteArrayOf(0x42, 0x01) + escapeRbsp(w.toByteArray())
        val vps = byteArrayOf(0x40, 0x01, 0x0C, 0x01, 0xFF.toByte(), 0xFF.toByte())
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte(), 0x72, 0xB4.toByte(), 0x62, 0x40)
        return Triple(vps, sps, pps)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf(
            (size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte(),
        ) + type.toByteArray(Charsets.US_ASCII) + payload
    }

    private fun contains(data: ByteArray, type: String): Boolean {
        val t = type.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..data.size - t.size) {
            for (j in t.indices) if (data[i + j] != t[j]) continue@outer
            return true
        }
        return false
    }

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    /** Chromecast Ultra, measured 2026-09-26. */
    private val ultraCaps = CastReceiverVideoCaps(
        mse = mapOf("avc1.64002A" to false, "hvc1" to false, "hvc1.4k" to false, "hev1" to false),
        display = mapOf(
            "h264_1080p60" to false, "h264_1080p30" to true, "hevc_1080p60" to false,
            "hevc_4k60" to false, "h264_4k60" to false,
        ),
    )

    private val src: CastH264StreamInfo by lazy { CastSpsParser.parseSpsInfo(h264Sps1080p60()) }

    // ---- SPS facts ----

    @Test
    fun `sps info reads size, level, VUI timing and colour`() {
        val info = src
        assertEquals(1920, info.width)
        assertEquals("height (cropped)", 1080, info.height)
        assertEquals(42, info.levelIdc)
        assertEquals("avc1.64002A", info.codecString)
        assertEquals(59.94, Math.round(info.fps!! * 100) / 100.0, 0.0)
        assertEquals(1, info.colourPrimaries)
        assertEquals(2, info.maxNumReorderFrames)
        assertTrue(info.progressive)
        assertEquals("1920x1080@59.94", info.label)
        assertEquals("4.2", info.levelLabel)
    }

    // ---- the plan ----

    @Test
    fun `Ultra and 1080p59_94 transcode to H264 720p with the documented log line`() {
        val ultra = CastVideoPlan(caps = ultraCaps)
        val d = ultra.decide(src)
        assertEquals(
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.H264, 1280, 720, frameStep = 1, bitrateCap = 8_000_000),
            d.output,
        )
        assertEquals(
            "[Cast] video plan: source=avc1.64002A 1920x1080@59.94 receiver display h264_1080p60=no " +
                "h264_1080p30=yes hevc_1080p60=no hvc1=no -> transcode H.264 720p59.94 level 4.1 (8000 kbps)",
            ultra.logLine(src, d),
        )
    }

    @Test
    fun `1080p30 profile halves the frame rate and keeps the size`() {
        val plan = CastVideoPlan(caps = ultraCaps, downProfile = CastTranscodeDownProfile.P1080P30)
        assertEquals(
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.H264, 1920, 1080, frameStep = 2, bitrateCap = 8_000_000),
            plan.decide(src).output,
        )
        assertTrue(plan.logLine(src, plan.decide(src)).endsWith("-> transcode H.264 1080p29.97 level 4.1 (8000 kbps)"))
    }

    @Test
    fun `sources the Ultra displays pass through`() {
        val ultra = CastVideoPlan(caps = ultraCaps)
        assertNull("720p60 fits", ultra.decide(src.copy(width = 1280, height = 720, levelIdc = 32)).output)
        assertNull(
            "1080i29.97 fits",
            ultra.decide(src.copy(fps = 29.97, progressive = false, levelIdc = 40)).output,
        )
        assertNull("no VUI, level 4.0 reads as 30 fps", ultra.decide(src.copy(fps = null, levelIdc = 40)).output)
        assertEquals(
            "no VUI, level 4.2 reads as 60 fps",
            CastVideoOutputSpec.Codec.H264, ultra.decide(src.copy(fps = null)).output?.codec,
        )
    }

    @Test
    fun `HEVC receivers get HEVC at the size they present`() {
        val hevcRx = CastVideoPlan(
            caps = CastReceiverVideoCaps(
                mse = mapOf("hvc1" to true),
                display = mapOf("h264_1080p60" to false, "h264_1080p30" to true, "hevc_1080p60" to true),
            ),
        )
        assertEquals(
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.HEVC, 1920, 1080, frameStep = 1, bitrateCap = 12_000_000),
            hevcRx.decide(src).output,
        )
        val s4K = src.copy(width = 3840, height = 2160, levelIdc = 51)
        assertEquals("4K source, 1080-only HEVC", 1920, hevcRx.decide(s4K).output?.width)
        val hevc4K = CastVideoPlan(
            caps = CastReceiverVideoCaps(mse = mapOf("hvc1" to true, "hvc1.4k" to true), display = mapOf("hevc_4k60" to true)),
        )
        assertEquals(
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.HEVC, 3840, 2160, frameStep = 1, bitrateCap = 25_000_000),
            hevc4K.decide(s4K).output,
        )
        assertEquals(
            "[Cast] video plan: source=avc1.64002A 1920x1080@59.94 receiver display h264_1080p60=no " +
                "h264_1080p30=yes hevc_1080p60=yes hvc1=yes -> transcode HEVC 1080p59.94 (12000 kbps)",
            hevcRx.logLine(src, hevcRx.decide(src)),
        )
    }

    @Test
    fun `force switch, old receiver pages, missing caps and a disabled plan`() {
        val streamer = CastVideoPlan(
            caps = CastReceiverVideoCaps(
                mse = mapOf("avc1.64002A" to true),
                display = mapOf("h264_1080p60" to true, "h264_1080p30" to true),
            ),
        )
        assertNull("receiver displays 1080p60", streamer.decide(src).output)
        val forced = streamer.copy(force = true)
        assertEquals(CastVideoOutputSpec.Codec.H264, forced.decide(src).output?.codec)
        assertTrue(forced.logLine(src, forced.decide(src)).endsWith("[forced]"))
        val forcedHevc = CastVideoPlan(
            caps = CastReceiverVideoCaps(mse = mapOf("hvc1" to true), display = mapOf("hevc_1080p60" to true)),
            force = true,
        )
        assertEquals(
            "force with HEVC -> HEVC even when it fits",
            CastVideoOutputSpec.Codec.HEVC,
            forcedHevc.decide(src.copy(width = 1280, height = 720, levelIdc = 32)).output?.codec,
        )
        val oldPage = CastVideoPlan(caps = CastReceiverVideoCaps(mse = mapOf("avc1.64002A" to false), display = null))
        assertEquals(CastVideoDecision(null, "receiver sent no display caps"), oldPage.decide(src))
        assertEquals(
            "[Cast] video plan: source=avc1.64002A 1920x1080@59.94 receiver display=none hvc1=no " +
                "-> passthrough (receiver sent no display caps)",
            oldPage.logLine(src, oldPage.decide(src)),
        )
        assertNull(CastVideoPlan.PASSTHROUGH.decide(src).output)
        assertEquals(
            "[Cast] video plan: source=avc1.64002A 1920x1080@59.94 receiver caps=none " +
                "-> passthrough (receiver caps not measured)",
            CastVideoPlan.PASSTHROUGH.logLine(src, CastVideoPlan.PASSTHROUGH.decide(src)),
        )
        val disabled = CastVideoPlan(caps = ultraCaps, disabledReason = "MediaCodec failed")
        assertEquals(CastVideoDecision(null, "MediaCodec failed"), disabled.decide(src))
    }

    @Test
    fun `fit, fps labels and the Developer picker values`() {
        assertEquals(Pair(1280, 720), CastVideoPlan.fit(1920, 1080, 1280, 720))
        assertEquals(Pair(960, 720), CastVideoPlan.fit(1440, 1080, 1280, 720))
        assertEquals(Pair(704, 480), CastVideoPlan.fit(704, 480, 1280, 720))
        assertEquals("50", CastVideoPlan.fpsLabel(50.0))
        assertEquals("59.94", CastVideoPlan.fpsLabel(60000.0 / 1001))
        assertEquals(CastTranscodeDownProfile.P1080P30, CastTranscodeDownProfile.fromRaw("1080p30"))
        assertEquals(CastTranscodeDownProfile.P720P60, CastTranscodeDownProfile.fromRaw(null))
        assertEquals(CastTranscodeDownProfile.P720P60, CastTranscodeDownProfile.fromRaw("junk"))
    }

    // ---- hvcC / avcC and codec strings ----

    @Test
    fun `hvcC built from the parameter sets names the stream`() {
        val (vps, sps, pps) = hevcParameterSets()
        val parsed = CastVideoCodecConfig.parseHevcSps(sps)
        assertEquals(1920, parsed?.width)
        assertEquals(1080, parsed?.height)
        val hvcC = CastVideoCodecConfig.buildHvcc(vps, sps, pps)!!
        assertEquals("hvc1.1.6.L153.B0", CastVideoCodecConfig.hevcCodecString(hvcC))
        assertEquals("one temporal layer, nested, 4-byte lengths", 0x0F, hvcC[21].toInt() and 0xFF)
        assertEquals("three parameter set arrays", 3, hvcC[22].toInt())
        val tier = hvcC.copyOf()
        tier[1] = (tier[1].toInt() or 0x20).toByte(); tier[12] = 120
        assertEquals("hvc1.1.6.H120.B0", CastVideoCodecConfig.hevcCodecString(tier))

        // The encoder's csd come back Annex B; the record is the same.
        val start = byteArrayOf(0, 0, 0, 1)
        val annexB = start + vps + start + sps + byteArrayOf(0, 0, 1) + pps
        assertTrue(hvcC.contentEquals(CastVideoCodecConfig.configRecord(CastVideoOutputSpec.Codec.HEVC, annexB)))
        assertNull(CastVideoCodecConfig.configRecord(CastVideoOutputSpec.Codec.HEVC, start + sps + start + pps))
    }

    @Test
    fun `avcC from the encoder csd and output samples in length-prefixed form`() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x29, 0xAC.toByte(), 0x2B)
        val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0xB0.toByte())
        val start = byteArrayOf(0, 0, 0, 1)
        val avcC = CastVideoCodecConfig.configRecord(CastVideoOutputSpec.Codec.H264, start + sps + start + pps)!!
        assertTrue(avcC.contentEquals(CastVideoCodecConfig.avcCPayload(sps, pps)))
        assertEquals(listOf(1, 0x64, 0x00, 0x29, 0xFF, 0xE1), avcC.take(6).map { it.toInt() and 0xFF })
        val server = CastHlsProxyServer(log = {})
        assertEquals("avc1.640029", server.videoCodecString(box("avcC", avcC)))

        // An HEVC access unit with in-band parameter sets: only the slice survives.
        val (vps, hsps, hpps) = hevcParameterSets()
        val slice = byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0x11)
        val au = start + vps + start + hsps + start + hpps + start + slice
        val out = CastVideoCodecConfig.annexBToLengthPrefixed(au, CastVideoOutputSpec.Codec.HEVC)
        assertTrue(out.contentEquals(byteArrayOf(0, 0, 0, 4) + slice))
    }

    // ---- master CODECS ----

    @Test
    fun `master declares the transcoded codec verbatim and relabels only passthrough`() {
        val (vps, sps, pps) = hevcParameterSets()
        val hvcC = CastVideoCodecConfig.buildHvcc(vps, sps, pps)!!
        val server = CastHlsProxyServer(log = {})
        server.setReceiverH264Level42(false)
        var gen = server.beginGeneration()
        server.setVideoTranscoded(gen, true)
        server.setInitSegments(gen, box("ftyp", ByteArray(4)) + box("hvcC", hvcC), null)
        assertTrue(server.demuxedMasterPlaylistText().contains("CODECS=\"hvc1.1.6.L153.B0\""))
        gen = server.beginGeneration()
        server.setVideoTranscoded(gen, true)
        server.setInitSegments(gen, box("avcC", byteArrayOf(1, 0x64, 0x00, 0x29, 0xFF.toByte())), null)
        assertTrue(server.demuxedMasterPlaylistText().contains("CODECS=\"avc1.640029\""))
        gen = server.beginGeneration()
        val avc42 = box("avcC", byteArrayOf(1, 0x64, 0x00, 0x2A, 0xFF.toByte()))
        server.setInitSegments(gen, avc42, null)
        assertTrue(
            "passthrough keeps the level 4.0 relabel",
            server.demuxedMasterPlaylistText().contains("CODECS=\"avc1.640028\""),
        )
        assertEquals("avc1.64002A", server.videoCodecString(avc42))
    }

    // ---- remuxer wiring with a fake transcoder ----

    /** Stands in for MediaCodec: keeps PTS, DTS = PTS, forced IDR every
     *  [keyTicks], emits [latency] frames late, format before the first
     *  frame. */
    private class FakeVideoTranscoder(
        val sink: CastVideoTranscodeSink,
        val keyTicks: Long,
        val latency: Int,
        val config: ByteArray,
        val failAfter: Int? = null,
    ) : CastVideoTranscoding {
        val held = ArrayDeque<Long>()
        var sentFormat = false
        var lastKey = -1L
        var fed = 0
        var released = false

        override fun feed(sample: ByteArray, pts: Long, dts: Long, keyframe: Boolean, sps: ByteArray, pps: ByteArray) {
            fed++
            if (failAfter != null && fed == failAfter) { sink.onFailure("fake MediaCodec error"); return }
            if (held.isEmpty() && lastKey < 0 && !keyframe) return
            held.addLast(pts)
            while (held.size > latency) {
                val p = held.removeFirst()
                if (!sentFormat) { sentFormat = true; sink.onFormat(CastVideoOutputSpec.Codec.HEVC, config, 1920, 1080) }
                val key = lastKey < 0 || p - lastKey >= keyTicks
                if (key) lastKey = p
                sink.onSample(byteArrayOf(0, 0, 0, 3, 0x26, 0x01, 0xAF.toByte()), p, key)
            }
        }

        override fun release() { released = true }
    }

    private class Capture : TsToFmp4Remuxer.Listener {
        var videoInit: ByteArray? = null
        val videoSegments = ArrayList<ByteArray>()
        val durations = ArrayList<Long>()
        val audioCounts = ArrayList<Int>()
        var failedReason: String? = null
        override fun onInitSegments(video: ByteArray, audio: ByteArray?) { videoInit = video }
        override fun onMediaSegment(video: ByteArray, audio: ByteArray?, videoDurationTicks: Long, audioDurationTicks: Long) {
            videoSegments.add(video); durations.add(videoDurationTicks)
        }
        override fun onSegmentComposition(
            videoSamples: Int,
            audioSamples: Int,
            firstVideoDtsSeconds: Double,
            firstVideoPtsSeconds: Double,
            firstAudioPtsSeconds: Double,
            segmentStartSeconds: Double,
        ) { audioCounts.add(audioSamples) }
        override fun onVideoTranscodeFailed(reason: String) { failedReason = reason }
    }

    private val forcedPlan = CastVideoPlan(caps = ultraCaps, force = true)
    private val hvcC by lazy { hevcParameterSets().let { CastVideoCodecConfig.buildHvcc(it.first, it.second, it.third)!! } }

    @Test
    fun `transcode path cuts on the encoder IDRs and keeps the warm-up audio`() {
        val (bytes, inputFrames) = testTs(videoFrames = 360, audioLagTicks = 15_300)
        val cap = Capture()
        val logs = ArrayList<String>()
        var fake: FakeVideoTranscoder? = null
        val remuxer = TsToFmp4Remuxer(
            cap, log = { logs.add(it) },
            videoPlan = forcedPlan,
            videoDelivery = { it() },
            videoTranscoderFactory = { _, _, keyTicks, sink, _ ->
                FakeVideoTranscoder(sink, keyTicks, latency = 6, config = hvcC).also { fake = it }
            },
        )
        var off = 0
        while (off < bytes.size) {
            val n = minOf(64 * 1024, bytes.size - off)
            remuxer.feed(bytes, off, n)
            off += n
        }
        remuxer.release()

        assertTrue(logs.any { it.startsWith("[Cast] video plan:") && it.contains("-> transcode") })
        assertTrue(remuxer.videoIsTranscoded)
        assertEquals(true, fake?.released)
        val init = cap.videoInit!!
        assertTrue("init carries hvc1 + hvcC only", contains(init, "hvc1") && contains(init, "hvcC") && !contains(init, "avcC"))
        assertEquals("hvc1.1.6.L153.B0", CastHlsProxyServer(log = {}).videoCodecString(init))
        val full = cap.durations.dropLast(1)
        assertTrue("segments cut on the 3 s encoder IDRs: ${cap.durations}", full.isNotEmpty() && full.all { it == 270_000L })
        for (seg in cap.videoSegments) {
            val t = (0..seg.size - 4).first {
                seg[it] == 't'.code.toByte() && seg[it + 1] == 'r'.code.toByte() &&
                    seg[it + 2] == 'u'.code.toByte() && seg[it + 3] == 'n'.code.toByte()
            }
            assertEquals("segment opens on a sync sample", 0x02000000, be32(seg, t + 24))
            assertEquals("composition offset 0", 0, be32(seg, t + 28))
        }
        assertTrue("at least 3 segments", cap.videoSegments.size >= 3)
        assertTrue("first segment carries the warm-up audio", cap.audioCounts.first() > 0)
        // Passthrough's allowance plus the audio under the 6 frames still
        // inside the encoder at teardown, which the tail trims.
        val output = cap.audioCounts.sum()
        assertTrue("audio lost: input $inputFrames output $output", output >= inputFrames - 62)
    }

    @Test
    fun `a transcoder failure makes the next feed throw after the callback`() {
        val (bytes, _) = testTs(videoFrames = 360, audioLagTicks = 15_300)
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(
            cap,
            videoPlan = forcedPlan,
            videoDelivery = { it() },
            videoTranscoderFactory = { _, _, keyTicks, sink, _ ->
                FakeVideoTranscoder(sink, keyTicks, latency = 2, config = hvcC, failAfter = 5)
            },
        )
        var thrown: Throwable? = null
        var off = 0
        while (off < bytes.size && thrown == null) {
            val n = minOf(4 * 1024, bytes.size - off)
            try { remuxer.feed(bytes, off, n) } catch (t: Throwable) { thrown = t }
            off += n
        }
        assertEquals("fake MediaCodec error", cap.failedReason)
        assertTrue(thrown is CastVideoTranscodeException)
    }

    @Test
    fun `no delivery hop keeps passthrough`() {
        val (bytes, _) = testTs(videoFrames = 120, audioLagTicks = 0)
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(cap, videoPlan = forcedPlan)
        remuxer.feed(bytes, 0, bytes.size)
        assertNotNull(cap.videoInit)
        assertTrue(contains(cap.videoInit!!, "avcC"))
        assertFalse(remuxer.videoIsTranscoded)
    }

    // ---- TS fixture: 30 fps H.264 (1 s GOP) + AAC-LC 48 kHz, one frame per PES ----

    private val audioFrameTicks = 1024L * 90_000 / 48_000

    private fun testTs(videoFrames: Int, audioLagTicks: Long): Pair<ByteArray, Int> {
        val videoFrameTicks = 3_000L
        val base = 10_000L
        val ts = TsWriter()
        ts.psi(0, patTable())
        ts.psi(0x1000, pmtTable())
        val audioFrames = ((videoFrames * videoFrameTicks) / audioFrameTicks).toInt()
        var nextAudio = 0
        for (i in 0 until videoFrames) {
            val dts = base + i * videoFrameTicks
            ts.pes(0x100, pesPacket(0xE0, videoAu(i % 30 == 0), dts, dts))
            while (nextAudio < audioFrames && base + nextAudio * audioFrameTicks <= dts - audioLagTicks) {
                ts.pes(0x101, pesPacket(0xC0, adtsFrame(400), base + nextAudio * audioFrameTicks, null))
                nextAudio++
            }
        }
        while (nextAudio < audioFrames) {
            ts.pes(0x101, pesPacket(0xC0, adtsFrame(400), base + nextAudio * audioFrameTicks, null))
            nextAudio++
        }
        return Pair(ts.bytes(), audioFrames)
    }

    private class TsWriter {
        private val out = ByteArrayOutputStream(1 shl 20)
        private val continuity = HashMap<Int, Int>()
        fun bytes(): ByteArray = out.toByteArray()
        fun psi(pid: Int, table: ByteArray) {
            val section = ByteArray(184) { 0xFF.toByte() }
            section[0] = 0
            System.arraycopy(table, 0, section, 1, table.size)
            packet(pid, section, pusi = true, adaptation = false)
        }
        fun pes(pid: Int, payload: ByteArray) {
            var off = 0
            var pusi = true
            while (off < payload.size) {
                val n = minOf(184, payload.size - off)
                val body = ByteArray(184)
                if (n == 184) {
                    System.arraycopy(payload, off, body, 0, n)
                } else {
                    val stuffing = 184 - n
                    body[0] = (stuffing - 1).toByte()
                    if (stuffing >= 2) {
                        body[1] = 0
                        for (i in 2 until stuffing) body[i] = 0xFF.toByte()
                    }
                    System.arraycopy(payload, off, body, stuffing, n)
                }
                packet(pid, body, pusi, adaptation = n != 184)
                pusi = false
                off += n
            }
        }
        private fun packet(pid: Int, body: ByteArray, pusi: Boolean, adaptation: Boolean) {
            val cc = continuity[pid] ?: 0
            continuity[pid] = (cc + 1) and 0x0F
            val p = ByteArray(188)
            p[0] = 0x47
            p[1] = ((if (pusi) 0x40 else 0) or ((pid shr 8) and 0x1F)).toByte()
            p[2] = (pid and 0xFF).toByte()
            p[3] = ((if (adaptation) 0x30 else 0x10) or cc).toByte()
            System.arraycopy(body, 0, p, 4, 184)
            out.write(p)
        }
    }

    private fun ptsBytes(marker: Int, ts: Long): ByteArray = byteArrayOf(
        ((marker shl 4) or ((((ts shr 30) and 0x07).toInt()) shl 1) or 1).toByte(),
        ((ts shr 22) and 0xFF).toByte(),
        (((((ts shr 15) and 0x7F).toInt()) shl 1) or 1).toByte(),
        ((ts shr 7) and 0xFF).toByte(),
        ((((ts and 0x7F).toInt()) shl 1) or 1).toByte(),
    )

    private fun pesPacket(streamId: Int, payload: ByteArray, pts: Long, dts: Long?): ByteArray {
        val stamps = if (dts == null) ptsBytes(2, pts) else ptsBytes(3, pts) + ptsBytes(1, dts)
        val header = byteArrayOf(
            0, 0, 1, streamId.toByte(), 0, 0, 0x80.toByte(),
            (if (dts == null) 0x80 else 0xC0).toByte(), stamps.size.toByte(),
        )
        val body = header + stamps + payload
        val length = body.size - 6
        body[4] = ((length shr 8) and 0xFF).toByte()
        body[5] = (length and 0xFF).toByte()
        return body
    }

    private fun patTable(): ByteArray {
        val body = byteArrayOf(0x00, 0x01, 0xC1.toByte(), 0, 0, 0, 0x01, 0xF0.toByte(), 0x00)
        return byteArrayOf(0x00, 0xB0.toByte(), (body.size + 4).toByte()) + body + ByteArray(4)
    }

    private fun pmtTable(): ByteArray {
        val body = byteArrayOf(
            0x00, 0x01, 0xC1.toByte(), 0, 0,
            0xE1.toByte(), 0x00,
            0xF0.toByte(), 0x00,
            0x1B, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00, // H.264 on 0x100
            0x0F, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0x00, // ADTS AAC on 0x101
        )
        return byteArrayOf(0x02, 0xB0.toByte(), (body.size + 4).toByte()) + body + ByteArray(4)
    }

    private fun adtsFrame(frameLen: Int): ByteArray {
        val f = ByteArray(frameLen) { 0x21 }
        f[0] = 0xFF.toByte()
        f[1] = 0xF1.toByte()
        f[2] = ((1 shl 6) or (3 shl 2)).toByte()
        f[3] = ((1 shl 6) or ((frameLen shr 11) and 0x03)).toByte()
        f[4] = ((frameLen shr 3) and 0xFF).toByte()
        f[5] = (((frameLen and 0x07) shl 5) or 0x1F).toByte()
        f[6] = 0xFC.toByte()
        return f
    }

    private fun videoAu(keyframe: Boolean): ByteArray {
        val sps = byteArrayOf(
            0x67, 0x42, 0xC0.toByte(), 0x1E, 0xD9.toByte(), 0x00, 0xF0.toByte(),
            0x11, 0x7E.toByte(), 0xF0.toByte(), 0x3C, 0x80.toByte(),
        )
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val slice = ByteArray(400) { 0x10 }
        slice[0] = if (keyframe) 0x65 else 0x41
        val start = byteArrayOf(0, 0, 0, 1)
        return start + sps + start + pps + start + slice
    }
}
