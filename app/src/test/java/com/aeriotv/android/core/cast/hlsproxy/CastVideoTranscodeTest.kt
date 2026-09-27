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

    /** HEVC Main10 level 5.1 3840x2160 SPS with a full body and VUI:
     *  BT.2020 primaries, HLG transfer (18), BT.2020 matrix, 50 fps
     *  (1 / 50), sps_max_num_reorder_pics 2, two short-term RPS (the second
     *  inter-predicted) so the parser has to walk st_ref_pic_set. */
    private fun hevcSps4K50Hlg(): ByteArray {
        val w = BitWriter()
        w.put(0, 4); w.put(0, 3); w.put(1, 1) // vps id, max_sub_layers_minus1 0, nesting
        w.put(0, 2); w.put(0, 1); w.put(2, 5) // space, tier, profile_idc 2 (Main10)
        w.put(0x2000_0000L, 32) // compat flag 2
        w.put(0xB0, 8); w.put(0L, 40)
        w.put(153, 8) // level 5.1
        w.ue(0) // sps id
        w.ue(1) // 4:2:0
        w.ue(3840); w.ue(2160)
        w.put(0, 1) // no conformance window
        w.ue(2); w.ue(2) // 10-bit luma and chroma
        w.ue(4) // log2_max_pic_order_cnt_lsb_minus4
        w.put(1, 1); w.ue(4); w.ue(2); w.ue(0) // ordering info: dpb, reorder 2, latency
        w.ue(0); w.ue(3); w.ue(0); w.ue(3); w.ue(0); w.ue(0) // block sizes, depths
        w.put(0, 1) // scaling_list_enabled
        w.put(1, 1); w.put(1, 1) // amp, sao
        w.put(0, 1) // pcm
        w.ue(2) // num_short_term_ref_pic_sets
        w.ue(1); w.ue(0); w.ue(0); w.put(1, 1) // RPS 0: one negative picture
        w.put(1, 1); w.put(0, 1); w.ue(0) // RPS 1: inter predicted, sign, abs_delta
        w.put(1, 1); w.put(0, 1); w.put(1, 1) // j0 used; j1 not used, use_delta
        w.put(0, 1) // long_term_ref_pics_present
        w.put(1, 1); w.put(1, 1) // temporal mvp, strong intra smoothing
        w.put(1, 1) // vui
        w.put(0, 1); w.put(0, 1) // aspect, overscan
        w.put(1, 1); w.put(5, 3); w.put(0, 1); w.put(1, 1); w.put(9, 8); w.put(18, 8); w.put(9, 8)
        w.put(0, 1); w.put(0, 1); w.put(0, 1); w.put(0, 1); w.put(0, 1) // chroma loc .. display window
        w.put(1, 1); w.put(1L, 32); w.put(50L, 32); w.put(0, 1); w.put(0, 1) // timing, no hrd
        w.put(0, 1) // bitstream_restriction
        w.put(0, 1) // sps_extension_present
        w.trailing()
        return byteArrayOf(0x42, 0x01) + escapeRbsp(w.toByteArray())
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

    private val src: CastVideoStreamInfo by lazy { CastSpsParser.parseSpsInfo(h264Sps1080p60()) }

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

    @Test
    fun `hevc sps info reads size, depth, level, VUI timing and colour`() {
        val info = CastSpsParser.parseHevcStreamInfo(hevcSps4K50Hlg())
        assertEquals(CastVideoOutputSpec.Codec.HEVC, info.codec)
        assertEquals(3840, info.width)
        assertEquals(2160, info.height)
        assertEquals(10, info.bitDepth)
        assertEquals(1, info.chromaFormatIdc)
        assertEquals(2, info.profileIdc)
        assertEquals(153, info.levelIdc)
        assertEquals("5.1", info.levelLabel)
        assertEquals("hvc1.2.4.L153.B0", info.codecString)
        assertEquals(50.0, info.fps!!, 0.0)
        assertEquals(9, info.colourPrimaries)
        assertEquals(18, info.transferCharacteristics)
        assertEquals(9, info.matrixCoefficients)
        assertEquals(2, info.maxNumReorderFrames)
        assertTrue(info.progressive)
        assertEquals("HEVC", info.codecName)
        assertEquals("3840x2160@50", info.label)

        // The 1080p Main fixture has no VUI: size and crop still read, no fps.
        val main = CastSpsParser.parseHevcStreamInfo(hevcParameterSets().second)
        assertEquals(1920, main.width)
        assertEquals("conformance window crop", 1080, main.height)
        assertEquals(8, main.bitDepth)
        assertNull(main.fps)
        assertEquals("hvc1.1.6.L153.B0", main.codecString)
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

    /** Google TV Streamer web receiver, measured 2026-09-27. */
    private val streamerCaps = CastReceiverVideoCaps(
        mse = mapOf("avc1.64002A" to true, "hvc1" to true, "hvc1.4k" to false, "hev1" to true),
        display = mapOf(
            "h264_1080p60" to true, "h264_1080p30" to true, "hevc_1080p60" to true,
            "hevc_4k60" to false, "h264_4k60" to true,
        ),
    )

    private val hevc4K50: CastVideoStreamInfo by lazy { CastSpsParser.parseHevcStreamInfo(hevcSps4K50Hlg()) }
    private val hevc1080p50: CastVideoStreamInfo by lazy {
        // SDR BT.709 8-bit: the HDR rules have their own tests below.
        hevc4K50.copy(
            width = 1920, height = 1080, levelIdc = 123, bitDepth = 8,
            colourPrimaries = 1, transferCharacteristics = 1, matrixCoefficients = 1,
        )
    }

    @Test
    fun `hevc source decisions per receiver`() {
        val streamer = CastVideoPlan(caps = streamerCaps)
        assertEquals(
            "Streamer: 4K HEVC falls to HEVC 1080 at the source rate",
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.HEVC, 1920, 1080, frameStep = 1, bitrateCap = 12_000_000),
            streamer.decide(hevc4K50).output,
        )
        assertEquals(
            "[Cast] video plan: source=hvc1.2.4.L153.B0 3840x2160@50 10-bit HDR HLG receiver display " +
                "h264_1080p60=yes h264_1080p30=yes hevc_1080p60=yes hvc1=yes hevc_4k60=no " +
                "hevc_1080p60_hlg=no hevc_4k60_hlg=no hvc1.hlg=no " +
                "-> transcode HEVC 1080p50 (12000 kbps) tone mapped to SDR BT.709",
            streamer.logLine(hevc4K50, streamer.decide(hevc4K50)),
        )
        assertNull("Streamer: 1080 HEVC passes through", streamer.decide(hevc1080p50).output)
        assertNull(
            "720 HEVC needs only MSE hvc1",
            streamer.decide(hevc1080p50.copy(width = 1280, height = 720)).output,
        )
        val hevc4KRx = CastVideoPlan(
            caps = streamerCaps.copy(display = streamerCaps.display!! + ("hevc_4k60" to true) + ("hevc_4k60_hlg" to true)),
        )
        assertNull("4K HLG HEVC passes through to hevc_4k60 + hevc_4k60_hlg", hevc4KRx.decide(hevc4K50).output)

        val ultra = CastVideoPlan(caps = ultraCaps)
        assertEquals(
            "Ultra: no HEVC at all -> H.264 720p at the source rate",
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.H264, 1280, 720, frameStep = 1, bitrateCap = 8_000_000),
            ultra.decide(hevc4K50).output,
        )
        assertEquals(
            "Ultra, 1080p30 profile: 1080 at half rate",
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.H264, 1920, 1080, frameStep = 2, bitrateCap = 8_000_000),
            ultra.copy(downProfile = CastTranscodeDownProfile.P1080P30).decide(hevc1080p50).output,
        )
        val displayOnly = CastVideoPlan(
            caps = CastReceiverVideoCaps(mse = mapOf("hvc1" to false), display = mapOf("hevc_1080p60" to true)),
        )
        assertEquals(
            "720 HEVC without MSE hvc1 is not HEVC-transcoded down to itself",
            CastVideoOutputSpec.Codec.H264,
            displayOnly.decide(hevc1080p50.copy(width = 1280, height = 720)).output?.codec,
        )
    }

    @Test
    fun `hevc without caps transcodes, force and disabled keep their meaning`() {
        val none = CastVideoPlan.PASSTHROUGH.decide(hevc4K50)
        assertEquals(
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.H264, 1280, 720, frameStep = 1, bitrateCap = 8_000_000),
            none.output,
        )
        assertEquals("receiver caps not measured", none.reason)
        val oldPage = CastVideoPlan(caps = CastReceiverVideoCaps(mse = mapOf("hvc1" to true), display = null))
        assertNull("old page, MSE hvc1, 1080 HEVC fits", oldPage.decide(hevc1080p50).output)
        assertEquals(
            "old page, 4K HEVC -> HEVC 1080",
            CastVideoOutputSpec.Codec.HEVC, oldPage.decide(hevc4K50).output?.codec,
        )
        val forced = CastVideoPlan(caps = streamerCaps, force = true)
        val d = forced.decide(hevc1080p50)
        assertEquals(
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.HEVC, 1920, 1080, frameStep = 1, bitrateCap = 12_000_000),
            d.output,
        )
        assertEquals("Developer switch", d.reason)
        val disabled = CastVideoPlan(caps = ultraCaps, disabledReason = "MediaCodec failed")
        assertEquals(CastVideoDecision(null, "MediaCodec failed"), disabled.decide(hevc4K50))
    }

    /** Travel Chromecast class: no HEVC, no HDR, 1080p30 H.264 only. */
    private val travelCaps = CastReceiverVideoCaps(
        mse = mapOf("hvc1" to false, "hvc1.hlg" to false, "hvc1.pq" to false),
        display = mapOf("h264_1080p60" to false, "h264_1080p30" to true, "hevc_1080p60" to false, "hevc_4k60" to false),
    )

    @Test
    fun `HDR HEVC needs the HDR key for its size class`() {
        val hlg4K = hevc4K50
        assertEquals(CastHdrTransfer.HLG, hlg4K.hdrTransfer)
        assertTrue(hlg4K.isHdr && hlg4K.isBt2020)
        val pq4K = hlg4K.copy(transferCharacteristics = 16)
        assertEquals(CastHdrTransfer.PQ, pq4K.hdrTransfer)
        val hlg1080 = hlg4K.copy(width = 1920, height = 1080, levelIdc = 123)
        val hlg720 = hlg4K.copy(width = 1280, height = 720, levelIdc = 93)
        val sdr = mapOf("hevc_1080p60" to true, "hevc_4k60" to true, "h264_1080p60" to true, "h264_1080p30" to true)
        fun plan(display: Map<String, Boolean>, mse: Map<String, Boolean> = mapOf("hvc1" to true)) =
            CastVideoPlan(caps = CastReceiverVideoCaps(mse = mse, display = display))

        // 4K class.
        assertNull("4K HLG + hevc_4k60_hlg: passthrough", plan(sdr + ("hevc_4k60_hlg" to true)).decide(hlg4K).output)
        assertEquals(
            "4K HLG, SDR 4K key only: transcode to HEVC 1080 SDR",
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.HEVC, 1920, 1080, 1, 12_000_000, hdr = false),
            plan(sdr).decide(hlg4K).output,
        )
        assertEquals(listOf("HDR"), plan(sdr).decide(hlg4K).unsupported)
        assertEquals(
            "4K HLG, 1080 HLG yes: HEVC 1080 keeps HDR",
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.HEVC, 1920, 1080, 1, 12_000_000, hdr = true),
            plan(sdr + ("hevc_1080p60_hlg" to true)).decide(hlg4K).output,
        )
        assertNotNull(
            "4K PQ is not passed by the HLG key",
            plan(sdr + ("hevc_4k60_hlg" to true)).decide(pq4K).output,
        )
        assertNull("4K PQ + hevc_4k60_pq: passthrough", plan(sdr + ("hevc_4k60_pq" to true)).decide(pq4K).output)

        // 1080 class.
        assertNull("1080 HLG + hevc_1080p60_hlg", plan(sdr + ("hevc_1080p60_hlg" to true)).decide(hlg1080).output)
        val sdrOnly1080 = plan(sdr).decide(hlg1080)
        assertEquals(
            "1080 HLG, SDR HEVC yes: HEVC 1080 tone mapped",
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.HEVC, 1920, 1080, 1, 12_000_000, hdr = false),
            sdrOnly1080.output,
        )
        assertEquals("receiver does not display HLG at the source size", sdrOnly1080.reason)
        assertNotNull("MSE hvc1.hlg does not pass 1080", plan(sdr, mapOf("hvc1" to true, "hvc1.hlg" to true)).decide(hlg1080).output)

        // 720 class.
        assertNull("720 HLG + MSE hvc1.hlg", plan(sdr, mapOf("hvc1" to true, "hvc1.hlg" to true)).decide(hlg720).output)
        assertEquals(
            "720 HLG without hvc1.hlg: HEVC 720 SDR",
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.HEVC, 1280, 720, 1, 12_000_000, hdr = false),
            plan(sdr).decide(hlg720).output,
        )

        // No HEVC at all: H.264 720p SDR, never HDR.
        val ultra = CastVideoPlan(caps = ultraCaps).decide(hlg4K)
        assertEquals(
            CastVideoOutputSpec(CastVideoOutputSpec.Codec.H264, 1280, 720, 1, 8_000_000, hdr = false),
            ultra.output,
        )
        assertEquals(listOf("HEVC", "4K", "HDR"), ultra.unsupported)
        assertEquals(listOf("HEVC", "4K", "HDR"), CastVideoPlan(caps = travelCaps).decide(hlg4K).unsupported)
        val d = CastVideoPlan(caps = travelCaps).decide(hlg4K)
        assertEquals(
            listOf(
                "Source: HEVC 3840x2160 at 50fps HDR",
                "Transcoding on this phone to H.264 1280x720 at 50fps",
                "Your Travel Chromecast TV doesn't support HEVC, 4K, HDR",
            ),
            CastVideoPlan.transcodeNote(
                "Travel Chromecast TV", "phone", CastVideoPathInfo(hlg4K, d.output, d.reason, d.unsupported, d.forced),
            ),
        )
        val kept = plan(sdr + ("hevc_1080p60_hlg" to true)).decide(hlg4K)
        assertEquals(
            "Transcoding on this phone to HEVC 1920x1080 at 50fps HDR",
            CastVideoPlan.transcodeNote("TV", "phone", CastVideoPathInfo(hlg4K, kept.output, "x", kept.unsupported))[1],
        )

        // H.264 source ruled-out list.
        val h264NoThirty = CastVideoPlan(caps = ultraCaps.copy(display = ultraCaps.display!! + ("h264_1080p30" to false)))
        assertEquals(listOf("1080p"), h264NoThirty.decide(src).unsupported)
    }

    @Test
    fun `transcode note and receiver stat copy`() {
        val streamer = CastVideoPlan(caps = streamerCaps)
        val d = streamer.decide(hevc4K50)
        val path = CastVideoPathInfo(hevc4K50, d.output, "x", d.unsupported, d.forced)
        assertEquals(
            listOf(
                "Source: HEVC 3840x2160 at 50fps HDR",
                "Transcoding on this phone to HEVC 1920x1080 at 50fps",
                "Your Living Room Google TV doesn't support 4K, HDR",
            ),
            CastVideoPlan.transcodeNote("Living Room Google TV", "phone", path),
        )
        val ud = CastVideoPlan(caps = ultraCaps).decide(src)
        val ultraPath = CastVideoPathInfo(src, ud.output, "x", ud.unsupported, ud.forced)
        assertEquals(
            listOf(
                "Source: H.264 1920x1080 at 59.94fps",
                "Transcoding on this tablet to H.264 1280x720 at 59.94fps",
                "This receiver doesn't support 1080p60",
            ),
            CastVideoPlan.transcodeNote(null, "tablet", ultraPath),
        )
        val halfRate = CastVideoPathInfo(
            src, CastVideoPlan(caps = ultraCaps, downProfile = CastTranscodeDownProfile.P1080P30).decide(src).output, "x",
        )
        assertEquals(
            "Transcoding on this phone to H.264 1920x1080 at 29.97fps",
            CastVideoPlan.transcodeNote(" ", "phone", halfRate)[1],
        )
        val fd = CastVideoPlan(caps = streamerCaps, force = true).decide(hevc1080p50)
        assertEquals(
            "Transcode forced by the Developer switch",
            CastVideoPlan.transcodeNote("TV", "phone", CastVideoPathInfo(hevc1080p50, fd.output, "x", fd.unsupported, fd.forced))[2],
        )
        assertTrue("no transcode, no note", CastVideoPlan.transcodeNote("TV", "phone", CastVideoPathInfo(src, null, "fits")).isEmpty())
        assertTrue(CastVideoPlan.transcodeNote("TV", "phone", null).isEmpty())
        assertEquals("1920x1080 at 60fps", CastVideoPlan.receiverPlayingText("1920x1080", 60.0))
        assertEquals("1280x720 at 52fps", CastVideoPlan.receiverPlayingText("1280x720", 52.3))
        assertEquals("1280x720", CastVideoPlan.receiverPlayingText("1280x720", null))
        assertEquals("59.94", CastVideoPlan.fpsText(60000.0 / 1001))
        assertEquals("50", CastVideoPlan.fpsText(50.0))
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

        override fun feed(sample: ByteArray, pts: Long, dts: Long, keyframe: Boolean, parameterSets: List<ByteArray>) {
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

    @Test
    fun `hevc passes through as hvc1 with the parameter sets only in the sample entry`() {
        val (bytes, _) = testTs(videoFrames = 120, audioLagTicks = 0, hevc = true)
        val paths = ArrayList<CastVideoPathInfo>()
        val cap = object : TsToFmp4Remuxer.Listener by Capture() {
            val segments = ArrayList<ByteArray>()
            var init: ByteArray? = null
            override fun onInitSegments(video: ByteArray, audio: ByteArray?) { init = video }
            override fun onMediaSegment(video: ByteArray, audio: ByteArray?, videoDurationTicks: Long, audioDurationTicks: Long) {
                segments.add(video)
            }
            override fun onVideoPath(info: CastVideoPathInfo) { paths.add(info) }
        }
        val remuxer = TsToFmp4Remuxer(cap, videoPlan = CastVideoPlan(caps = streamerCaps))
        remuxer.feed(bytes, 0, bytes.size)
        remuxer.release()

        val init = cap.init!!
        assertTrue("hvc1 + hvcC", contains(init, "hvc1") && contains(init, "hvcC") && !contains(init, "avcC"))
        assertEquals("hvc1.1.6.L153.B0", CastHlsProxyServer(log = {}).videoCodecString(init))
        assertFalse(remuxer.videoIsTranscoded)
        assertEquals("HEVC passthrough", remuxer.videoPathDescription)
        assertEquals(1, paths.size)
        assertNull(paths[0].output)
        assertEquals(CastVideoOutputSpec.Codec.HEVC, paths[0].source.codec)
        assertTrue(cap.segments.isNotEmpty())
        val (vps, sps, _) = hevcParameterSets()
        for (seg in cap.segments) {
            assertFalse("no in-band VPS", containsBytes(seg, vps))
            assertFalse("no in-band SPS", containsBytes(seg, sps))
            assertTrue("slices ride as 4-byte-length NALs", containsBytes(seg, byteArrayOf(0, 0, 1, 0x90.toByte())))
        }
    }

    private fun containsBytes(data: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    // ---- TS fixture: 30 fps H.264 or HEVC (1 s GOP) + AAC-LC 48 kHz, one frame per PES ----

    private val audioFrameTicks = 1024L * 90_000 / 48_000

    private fun testTs(videoFrames: Int, audioLagTicks: Long, hevc: Boolean = false): Pair<ByteArray, Int> {
        val videoFrameTicks = 3_000L
        val base = 10_000L
        val ts = TsWriter()
        ts.psi(0, patTable())
        ts.psi(0x1000, pmtTable(if (hevc) 0x24 else 0x1B))
        val audioFrames = ((videoFrames * videoFrameTicks) / audioFrameTicks).toInt()
        var nextAudio = 0
        for (i in 0 until videoFrames) {
            val dts = base + i * videoFrameTicks
            val au = if (hevc) hevcAu(i % 30 == 0) else videoAu(i % 30 == 0)
            ts.pes(0x100, pesPacket(0xE0, au, dts, dts))
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

    private fun pmtTable(videoType: Int): ByteArray {
        val body = byteArrayOf(
            0x00, 0x01, 0xC1.toByte(), 0, 0,
            0xE1.toByte(), 0x00,
            0xF0.toByte(), 0x00,
            videoType.toByte(), 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00, // H.264 or HEVC on 0x100
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

    /** AUD, VPS, SPS, PPS then one 400-byte slice: IDR_W_RADL (19) or
     *  TRAIL_R (1). */
    private fun hevcAu(keyframe: Boolean): ByteArray {
        val (vps, sps, pps) = hevcParameterSets()
        val slice = ByteArray(400) { 0x10 }
        slice[0] = if (keyframe) 0x26 else 0x02
        slice[1] = 0x01
        val start = byteArrayOf(0, 0, 0, 1)
        val aud = byteArrayOf(0x46, 0x01, 0x10)
        return start + aud + start + vps + start + sps + start + pps + start + slice
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
