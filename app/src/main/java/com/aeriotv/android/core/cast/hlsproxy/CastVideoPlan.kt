package com.aeriotv.android.core.cast.hlsproxy

import java.io.ByteArrayOutputStream
import java.util.Locale

// Cast HLS proxy: the pure half of the on-phone video transcode (iOS
// CastVideoTranscoder.swift parity, 2026-09-26). Stream info, receiver
// caps, the plan and its decision rule, codec strings and the avcC / hvcC
// records live here with no Android dependency so the JVM unit tests run
// them directly; the MediaCodec pipeline is in CastVideoTranscoder.kt.
//
// Measured on a Chromecast Ultra: its Cast runtime decodes H.264 1080p at
// about 47 frames per second on every path (MSE and native), so 1080p50/60
// H.264 plays at 0.66x to 0.93x. The same device answers canDisplayType
// true for H.264 1080p30 and for avc1.640029. So the phone decodes the
// source with the platform hardware decoder and re-encodes with the
// platform hardware encoder (MediaCodec only; no third-party encoder),
// into one of two output profiles chosen from the receiver's caps:
//
//  - HEVC Main at the source resolution and frame rate, when the receiver
//    presents HEVC 1080p60 (`display.hevc_1080p60`, or MSE `hvc1`).
//  - H.264 High level 4.1, when it does not: 1280x720 at the source frame
//    rate (the default, keeps sports motion) or the source resolution at
//    half the frame rate (Developer picker "1080p30").

/** What the remuxer learned from the source H.264 SPS (see
 *  [CastSpsParser.parseSpsInfo]). */
data class CastH264StreamInfo(
    val width: Int,
    val height: Int,
    val profileIdc: Int,
    val constraintFlags: Int,
    val levelIdc: Int,
    /** frame_mbs_only_flag: false for PAFF / MBAFF interlaced coding. */
    val progressive: Boolean = true,
    /** Frames per second from the VUI timing info, null when absent. */
    val fps: Double? = null,
    val fullRange: Boolean = false,
    val colourPrimaries: Int? = null,
    val transferCharacteristics: Int? = null,
    val matrixCoefficients: Int? = null,
    /** VUI bitstream_restriction max_num_reorder_frames, null when absent. */
    val maxNumReorderFrames: Int? = null,
) {
    /** RFC 6381 avc1 string from the SPS header bytes. */
    val codecString: String
        get() = String.format(
            Locale.US, "avc1.%02X%02X%02X",
            profileIdc and 0xFF, constraintFlags and 0xFF, levelIdc and 0xFF,
        )

    /** "4.1" for level_idc 41. */
    val levelLabel: String get() = "${levelIdc / 10}.${levelIdc % 10}"

    /** "1920x1080@59.94", or "@?" without VUI timing. */
    val label: String get() = "${width}x$height@${fps?.let { CastVideoPlan.fpsLabel(it) } ?: "?"}"
}

/** The receiver's own measurement of what it can decode (`mse`, from
 *  MediaSource.isTypeSupported) and PRESENT (`display`, from
 *  cast.framework canDisplayType). Missing keys read as false. */
data class CastReceiverVideoCaps(
    val mse: Map<String, Boolean>,
    /** null when the receiver page sent no `display` map at all (an older
     *  page); the plan then never transcodes on its own. */
    val display: Map<String, Boolean>?,
) {
    fun mse(key: String): Boolean = mse[key] == true
    fun display(key: String): Boolean = display?.get(key) == true

    /** HEVC at 1080p60 is presentable. */
    val hevc1080: Boolean get() = display("hevc_1080p60") || mse("hvc1")

    /** HEVC at 4K60 is presentable. */
    val hevc4K: Boolean get() = display("hevc_4k60") || mse("hvc1.4k")
}

/** Developer picker `castTranscodeDownProfile`: the H.264 output shape
 *  when the receiver cannot present HEVC. */
enum class CastTranscodeDownProfile(val rawValue: String) {
    /** 1280x720 at the source frame rate (default: keeps sports motion). */
    P720P60("720p60"),

    /** Source resolution (at most 1920x1080) at half the frame rate for
     *  50/60p sources: every other presented frame is encoded. */
    P1080P30("1080p30"),
    ;

    companion object {
        fun fromRaw(raw: String?): CastTranscodeDownProfile =
            entries.firstOrNull { it.rawValue == raw } ?: P720P60
    }
}

/** One transcode output. */
data class CastVideoOutputSpec(
    val codec: Codec,
    val width: Int,
    val height: Int,
    /** 1 keeps every frame; 2 encodes every other presented frame. */
    val frameStep: Int,
    /** Upper bound on the encoder's average bit rate, bits per second. The
     *  encoder runs at min(measured source rate, this). */
    val bitrateCap: Int,
) {
    enum class Codec { HEVC, H264 }

    fun outputFps(sourceFps: Double?): Double? = sourceFps?.let { it / maxOf(1, frameStep).toDouble() }

    companion object {
        const val HEVC_1080_CAP = 12_000_000
        const val HEVC_4K_CAP = 25_000_000
        const val H264_CAP = 8_000_000

        /** H.264 High level 4.1 (profile_idc 100, level_idc 41). */
        const val H264_LEVEL_41 = 41
    }
}

/** The plan's answer for one source. */
data class CastVideoDecision(
    /** null means H.264 passthrough. */
    val output: CastVideoOutputSpec?,
    /** Why (passthrough) or what triggered the transcode. */
    val reason: String,
)

/**
 * Everything the remuxer needs to decide the video path once it has seen
 * the source SPS. Built by the cast sender from the receiver caps and the
 * Developer switches; the session overrides it with [disabledReason] after
 * a MediaCodec failure.
 */
data class CastVideoPlan(
    val caps: CastReceiverVideoCaps?,
    /** Developer switch `castForceHEVCTranscode`: transcode any H.264
     *  source, HEVC when the receiver presents it, else the H.264 profile. */
    val force: Boolean = false,
    val downProfile: CastTranscodeDownProfile = CastTranscodeDownProfile.P720P60,
    /** Set by the session after a MediaCodec failure: passthrough for the
     *  rest of the session. */
    val disabledReason: String? = null,
) {
    /**
     * The decision rule. Pure; unit-tested.
     *
     * 1. A source the receiver presents as H.264 passes through: anything
     *    up to 1280x720 at any rate (a lower pixel rate than 1080p30), 1080
     *    at up to 30 fps when `display.h264_1080p30`, 1080 at any rate when
     *    `display.h264_1080p60`, 4K when `display.h264_4k60`.
     * 2. Otherwise HEVC at the source size when the receiver presents HEVC
     *    at that size (a 4K source falls to 1080 HEVC when only 1080 HEVC
     *    is presentable).
     * 3. Otherwise H.264 High 4.1 in the Developer down profile.
     * The force switch skips rule 1. Without a `display` map (an older
     * receiver page) the plan passes through unless forced.
     */
    fun decide(s: CastH264StreamInfo): CastVideoDecision {
        disabledReason?.let { return CastVideoDecision(null, it) }
        val caps = this.caps ?: CastReceiverVideoCaps(emptyMap(), null)
        if (!force) {
            if (this.caps == null) return CastVideoDecision(null, "receiver caps not measured")
            if (caps.display == null) return CastVideoDecision(null, "receiver sent no display caps")
        }
        val is4K = s.width > 1920 || s.height > 1088
        val is1080 = !is4K && (s.width > 1280 || s.height > 720)
        // No VUI timing: level 4.1 and up is the 1080p50/60 class.
        val fps = s.fps ?: if (s.levelIdc > 40) 60.0 else 30.0
        val highRate = fps > 31
        val fits = when {
            is4K -> caps.display("h264_4k60")
            is1080 -> caps.display("h264_1080p60") || (!highRate && caps.display("h264_1080p30"))
            else -> true
        }
        if (fits && !force) return CastVideoDecision(null, "receiver displays the source")
        val trigger = if (force) "Developer switch" else "source above receiver display"
        if (is4K && caps.hevc4K) {
            return CastVideoDecision(
                CastVideoOutputSpec(
                    CastVideoOutputSpec.Codec.HEVC, even(s.width), even(s.height),
                    frameStep = 1, bitrateCap = CastVideoOutputSpec.HEVC_4K_CAP,
                ),
                trigger,
            )
        }
        if (caps.hevc1080) {
            val size = fit(s.width, s.height, 1920, 1080)
            return CastVideoDecision(
                CastVideoOutputSpec(
                    CastVideoOutputSpec.Codec.HEVC, size.first, size.second,
                    frameStep = 1, bitrateCap = CastVideoOutputSpec.HEVC_1080_CAP,
                ),
                trigger,
            )
        }
        // 1080p30 needs the receiver to present 1080p30; otherwise 720p.
        val profile =
            if (downProfile == CastTranscodeDownProfile.P1080P30 &&
                (caps.display("h264_1080p30") || caps.display == null)
            ) {
                CastTranscodeDownProfile.P1080P30
            } else {
                CastTranscodeDownProfile.P720P60
            }
        return when (profile) {
            CastTranscodeDownProfile.P720P60 -> {
                val size = fit(s.width, s.height, 1280, 720)
                CastVideoDecision(
                    CastVideoOutputSpec(
                        CastVideoOutputSpec.Codec.H264, size.first, size.second,
                        frameStep = 1, bitrateCap = CastVideoOutputSpec.H264_CAP,
                    ),
                    trigger,
                )
            }
            CastTranscodeDownProfile.P1080P30 -> {
                val size = fit(s.width, s.height, 1920, 1080)
                CastVideoDecision(
                    CastVideoOutputSpec(
                        CastVideoOutputSpec.Codec.H264, size.first, size.second,
                        frameStep = if (highRate) 2 else 1, bitrateCap = CastVideoOutputSpec.H264_CAP,
                    ),
                    trigger,
                )
            }
        }
    }

    /** `[Cast] video plan: source=avc1.64002A 1920x1080@59.94 receiver
     *  display h264_1080p60=no h264_1080p30=yes hevc_1080p60=no hvc1=no ->
     *  transcode H.264 720p59.94 level 4.1 (8000 kbps)`. */
    fun logLine(source: CastH264StreamInfo, decision: CastVideoDecision): String {
        val caps = this.caps ?: CastReceiverVideoCaps(emptyMap(), null)
        fun yn(b: Boolean) = if (b) "yes" else "no"
        val line = StringBuilder("[Cast] video plan: source=${source.codecString} ${source.label}")
        if (!source.progressive) line.append(" interlaced")
        when {
            this.caps == null -> line.append(" receiver caps=none")
            caps.display == null -> line.append(" receiver display=none hvc1=${yn(caps.mse("hvc1"))}")
            else -> line.append(
                " receiver display h264_1080p60=${yn(caps.display("h264_1080p60"))}" +
                    " h264_1080p30=${yn(caps.display("h264_1080p30"))}" +
                    " hevc_1080p60=${yn(caps.display("hevc_1080p60"))}" +
                    " hvc1=${yn(caps.mse("hvc1"))}",
            )
        }
        val out = decision.output ?: return line.append(" -> passthrough (${decision.reason})").toString()
        val fps = out.outputFps(source.fps)?.let { fpsLabel(it) } ?: ""
        val kbps = out.bitrateCap / 1000
        when (out.codec) {
            CastVideoOutputSpec.Codec.HEVC -> line.append(" -> transcode HEVC ${out.height}p$fps ($kbps kbps)")
            CastVideoOutputSpec.Codec.H264 ->
                line.append(" -> transcode H.264 ${out.height}p$fps level 4.1 ($kbps kbps)")
        }
        if (force) line.append(" [forced]")
        return line.toString()
    }

    companion object {
        val PASSTHROUGH = CastVideoPlan(caps = null)

        /** 59.94 stays 59.94, 50.0 prints as 50. */
        fun fpsLabel(fps: Double): String =
            if (kotlin.math.abs(fps - Math.round(fps).toDouble()) < 0.005) {
                String.format(Locale.US, "%.0f", fps)
            } else {
                String.format(Locale.US, "%.2f", fps)
            }

        /** Scale (w, h) down to fit inside the box, preserving aspect, even
         *  dimensions; never scales up. */
        fun fit(w: Int, h: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
            if (w <= 0 || h <= 0) return Pair(maxWidth, maxHeight)
            if (w <= maxWidth && h <= maxHeight) return Pair(even(w), even(h))
            val scale = minOf(maxWidth.toDouble() / w, maxHeight.toDouble() / h)
            return Pair(even(Math.round(w * scale).toInt()), even(Math.round(h * scale).toInt()))
        }

        internal fun even(v: Int): Int = maxOf(2, v and 1.inv())
    }
}

// ---- codec configuration records and strings ----

/** Pure helpers for the avcC / hvcC records and their RFC 6381 strings. */
object CastVideoCodecConfig {

    /** avcC payload (the box body) for one SPS and one PPS, 4-byte NAL
     *  lengths. */
    fun avcCPayload(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(16 + sps.size + pps.size)
        out.write(1) // configurationVersion
        out.write(if (sps.size > 1) sps[1].toInt() and 0xFF else 0) // AVCProfileIndication
        out.write(if (sps.size > 2) sps[2].toInt() and 0xFF else 0) // profile_compatibility
        out.write(if (sps.size > 3) sps[3].toInt() and 0xFF else 0) // AVCLevelIndication
        out.write(0xFF) // 4-byte NAL lengths (lengthSizeMinusOne = 3)
        out.write(0xE1) // 1 SPS
        out.write((sps.size shr 8) and 0xFF); out.write(sps.size and 0xFF)
        out.write(sps)
        out.write(1) // 1 PPS
        out.write((pps.size shr 8) and 0xFF); out.write(pps.size and 0xFF)
        out.write(pps)
        return out.toByteArray()
    }

    /** RFC 6381 / ISO 14496-15 Annex E codec string from an hvcC payload
     *  (the box body, starting at configurationVersion):
     *  hvc1.[A-C]<profile>.<reversed compat hex>.<L|H><level>[.<constraint>]*
     *  with trailing zero constraint bytes omitted, e.g. hvc1.1.6.L153.B0. */
    fun hevcCodecString(hvcC: ByteArray): String? {
        if (hvcC.size < 13 || hvcC[0].toInt() != 1) return null
        val b1 = hvcC[1].toInt() and 0xFF
        val space = b1 shr 6
        val tier = (b1 shr 5) and 1
        val profile = b1 and 0x1F
        val compat = ((hvcC[2].toLong() and 0xFF) shl 24) or ((hvcC[3].toLong() and 0xFF) shl 16) or
            ((hvcC[4].toLong() and 0xFF) shl 8) or (hvcC[5].toLong() and 0xFF)
        var reversed = 0L
        for (bit in 0 until 32) {
            if (compat and (1L shl bit) != 0L) reversed = reversed or (1L shl (31 - bit))
        }
        val constraints = (6 until 12).map { hvcC[it].toInt() and 0xFF }.toMutableList()
        while (constraints.isNotEmpty() && constraints.last() == 0) constraints.removeAt(constraints.size - 1)
        val sb = StringBuilder("hvc1.")
        sb.append(listOf("", "A", "B", "C")[space]).append(profile)
        sb.append('.').append(java.lang.Long.toHexString(reversed).uppercase(Locale.US))
        sb.append('.').append(if (tier == 1) "H" else "L").append(hvcC[12].toInt() and 0xFF)
        for (c in constraints) sb.append(String.format(Locale.US, ".%02X", c))
        return sb.toString()
    }

    /** HEVC SPS fields the hvcC record repeats. */
    data class HevcSpsInfo(
        /** general_profile_space .. general_level_idc: the 12 bytes hvcC
         *  copies verbatim. */
        val generalPtl: List<Int>,
        val maxSubLayersMinus1: Int,
        val temporalIdNesting: Boolean,
        val chromaFormatIdc: Int,
        val bitDepthLumaMinus8: Int,
        val bitDepthChromaMinus8: Int,
        val width: Int,
        val height: Int,
    )

    /** Parse an HEVC SPS NAL (2-byte header included, emulation prevention
     *  bytes still in). */
    fun parseHevcSps(nal: ByteArray): HevcSpsInfo? {
        if (nal.size <= 15 || ((nal[0].toInt() shr 1) and 0x3F) != 33) return null
        val rbsp = CastSpsParser.unescapeRbsp(nal, 2)
        if (rbsp.size < 13) return null
        val maxSub = (rbsp[0].toInt() shr 1) and 0x07
        val nesting = rbsp[0].toInt() and 1 == 1
        val ptl = (1 until 13).map { rbsp[it].toInt() and 0xFF }
        return try {
            val r = CastBitReader(rbsp)
            r.skip(8 + 96) // vps id .. nesting, general PTL
            val subProfile = BooleanArray(maxSub)
            val subLevel = BooleanArray(maxSub)
            for (i in 0 until maxSub) {
                subProfile[i] = r.bits(1) == 1
                subLevel[i] = r.bits(1) == 1
            }
            if (maxSub > 0) for (i in maxSub until 8) r.bits(2)
            for (i in 0 until maxSub) {
                if (subProfile[i]) r.skip(88)
                if (subLevel[i]) r.bits(8)
            }
            r.ue() // sps_seq_parameter_set_id
            val chroma = r.ue()
            if (chroma == 3) r.bits(1)
            var width = r.ue()
            var height = r.ue()
            if (r.bits(1) == 1) { // conformance_window_flag
                val l = r.ue(); val rr = r.ue(); val t = r.ue(); val b = r.ue()
                val subW = if (chroma == 1 || chroma == 2) 2 else 1
                val subH = if (chroma == 1) 2 else 1
                width -= (l + rr) * subW
                height -= (t + b) * subH
            }
            val luma = r.ue()
            val chromaDepth = r.ue()
            HevcSpsInfo(ptl, maxSub, nesting, chroma, luma, chromaDepth, width, height)
        } catch (_: RuntimeException) {
            null
        }
    }

    /** hvcC payload from one VPS, SPS and PPS (4-byte NAL lengths), built
     *  from the parameter sets the encoder hands back in its csd buffers. */
    fun buildHvcc(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray? {
        val info = parseHevcSps(sps) ?: return null
        val out = ByteArrayOutputStream(64 + vps.size + sps.size + pps.size)
        out.write(1)
        for (b in info.generalPtl) out.write(b)
        out.write(0xF0); out.write(0x00) // reserved + min_spatial_segmentation_idc 0
        out.write(0xFC) // reserved + parallelismType 0
        out.write(0xFC or (info.chromaFormatIdc and 0x03))
        out.write(0xF8 or (info.bitDepthLumaMinus8 and 0x07))
        out.write(0xF8 or (info.bitDepthChromaMinus8 and 0x07))
        out.write(0x00); out.write(0x00) // avgFrameRate unspecified
        out.write(
            (((info.maxSubLayersMinus1 + 1) and 0x07) shl 3) or
                (if (info.temporalIdNesting) 0x04 else 0) or 0x03, // lengthSizeMinusOne 3
        )
        out.write(3) // numOfArrays
        for ((type, nal) in listOf(32 to vps, 33 to sps, 34 to pps)) {
            out.write(0x80 or type) // array_completeness 1
            out.write(0x00); out.write(0x01)
            out.write((nal.size shr 8) and 0xFF); out.write(nal.size and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** Split an Annex B byte run (3- or 4-byte start codes) into NAL units. */
    fun splitAnnexB(payload: ByteArray): List<ByteArray> {
        val nals = ArrayList<ByteArray>(4)
        var i = 0
        var nalStart = -1
        val n = payload.size
        while (i + 2 < n) {
            if (payload[i].toInt() == 0 && payload[i + 1].toInt() == 0 && payload[i + 2].toInt() == 1) {
                if (nalStart >= 0) {
                    var end = i
                    if (end > nalStart && payload[end - 1].toInt() == 0) end--
                    if (end > nalStart) nals.add(payload.copyOfRange(nalStart, end))
                }
                nalStart = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (nalStart in 0 until n) nals.add(payload.copyOfRange(nalStart, n))
        return nals
    }

    /** The init segment's avcC / hvcC body from the encoder's Annex B
     *  parameter sets (csd-0 + csd-1, or the CODEC_CONFIG buffer). */
    fun configRecord(codec: CastVideoOutputSpec.Codec, parameterSets: ByteArray): ByteArray? {
        val nals = splitAnnexB(parameterSets)
        return when (codec) {
            CastVideoOutputSpec.Codec.HEVC -> {
                fun nal(type: Int) = nals.firstOrNull { it.isNotEmpty() && ((it[0].toInt() shr 1) and 0x3F) == type }
                val vps = nal(32) ?: return null
                val sps = nal(33) ?: return null
                val pps = nal(34) ?: return null
                buildHvcc(vps, sps, pps)
            }
            CastVideoOutputSpec.Codec.H264 -> {
                fun nal(type: Int) = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == type }
                val sps = nal(7) ?: return null
                val pps = nal(8) ?: return null
                avcCPayload(sps, pps)
            }
        }
    }

    /** Annex B access unit to 4-byte-length NAL units. For HEVC the
     *  parameter sets and AUD are dropped: hvc1 means the sample entry's
     *  record is the only copy (VideoToolbox never writes them in band, so
     *  this keeps both platforms' output identical). */
    fun annexBToLengthPrefixed(data: ByteArray, codec: CastVideoOutputSpec.Codec): ByteArray {
        val nals = splitAnnexB(data)
        val keep = nals.filter { nal ->
            nal.isNotEmpty() && when (codec) {
                CastVideoOutputSpec.Codec.HEVC -> ((nal[0].toInt() shr 1) and 0x3F) !in 32..35
                CastVideoOutputSpec.Codec.H264 -> (nal[0].toInt() and 0x1F) !in setOf(7, 8, 9)
            }
        }
        val out = ByteArray(keep.sumOf { 4 + it.size })
        var w = 0
        for (nal in keep) {
            val n = nal.size
            out[w] = (n ushr 24).toByte(); out[w + 1] = (n ushr 16).toByte()
            out[w + 2] = (n ushr 8).toByte(); out[w + 3] = n.toByte()
            System.arraycopy(nal, 0, out, w + 4, n)
            w += 4 + n
        }
        return out
    }
}

// ---- SPS parsing ----

/** MSB-first bit reader over an RBSP; throws past the end. */
class CastBitReader(private val data: ByteArray) {
    private var pos = 0

    fun bits(n: Int): Int {
        var v = 0
        repeat(n) {
            val byte = data[pos ushr 3].toInt() and 0xFF
            v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1)
            pos++
        }
        return v
    }

    /** Up to 63 bits as an unsigned value (the 32-bit VUI timing fields). */
    fun bitsLong(n: Int): Long {
        var v = 0L
        repeat(n) {
            val byte = data[pos ushr 3].toInt() and 0xFF
            v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1).toLong()
            pos++
        }
        return v
    }

    fun skip(n: Int) {
        if ((pos + n + 7) / 8 > data.size) throw IndexOutOfBoundsException("bit reader past the end")
        pos += n
    }

    fun ue(): Int {
        var zeros = 0
        while (bits(1) == 0 && zeros < 32) zeros++
        return (1 shl zeros) - 1 + if (zeros > 0) bits(zeros) else 0
    }

    fun se(): Int {
        val k = ue()
        return if (k % 2 == 0) -(k / 2) else (k + 1) / 2
    }
}

object CastSpsParser {

    /** Strip emulation prevention bytes (00 00 03) from [nal] starting at
     *  [from] (past the NAL header). */
    fun unescapeRbsp(nal: ByteArray, from: Int): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var i = from
        while (i < nal.size) {
            if (i + 2 < nal.size && nal[i].toInt() == 0 && nal[i + 1].toInt() == 0 && nal[i + 2].toInt() == 3) {
                out.write(0); out.write(0)
                i += 3
            } else {
                out.write(nal[i].toInt())
                i++
            }
        }
        return out.toByteArray()
    }

    /** H.264 SPS to the stream facts the video plan needs. The VUI is read
     *  best effort: a truncated or exotic VUI leaves fps / colour null
     *  instead of failing the dimensions. Throws on an unreadable SPS. */
    fun parseSpsInfo(spsNal: ByteArray): CastH264StreamInfo {
        if (spsNal.size < 4) error("short SPS")
        val r = CastBitReader(unescapeRbsp(spsNal, 1))
        val profileIdc = r.bits(8)
        val constraints = r.bits(8)
        val levelIdc = r.bits(8)
        r.ue() // seq_parameter_set_id
        var chromaFormat = 1
        if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134)) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) r.bits(1)
            r.ue(); r.ue(); r.bits(1) // bit depths, qpprime
            if (r.bits(1) == 1) { // seq_scaling_matrix_present
                val lists = if (chromaFormat == 3) 12 else 8
                for (l in 0 until lists) {
                    if (r.bits(1) == 1) skipScalingList(r, if (l < 6) 16 else 64)
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.bits(1); r.se(); r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue(); r.bits(1) // max_num_ref_frames, gaps_allowed
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bits(1)
        if (frameMbsOnly == 0) r.bits(1)
        r.bits(1) // direct_8x8
        var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
        if (r.bits(1) == 1) {
            cropL = r.ue(); cropR = r.ue(); cropT = r.ue(); cropB = r.ue()
        }
        val cropUnitX = if (chromaFormat == 0) 1 else 2
        val cropUnitY = (if (chromaFormat <= 1) 2 else 1) * (2 - frameMbsOnly)
        val width = widthMbs * 16 - (cropL + cropR) * cropUnitX
        val height = heightMapUnits * 16 * (2 - frameMbsOnly) - (cropT + cropB) * cropUnitY
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) error("implausible")
        val base = CastH264StreamInfo(
            width = width, height = height, profileIdc = profileIdc,
            constraintFlags = constraints, levelIdc = levelIdc, progressive = frameMbsOnly == 1,
        )
        return parseVui(r, base)
    }

    private fun parseVui(r: CastBitReader, base: CastH264StreamInfo): CastH264StreamInfo {
        var info = base
        try {
            if (r.bits(1) != 1) return info // vui_parameters_present_flag
            if (r.bits(1) == 1) { // aspect_ratio_info_present_flag
                if (r.bits(8) == 255) { r.bits(16); r.bits(16) } // Extended_SAR
            }
            if (r.bits(1) == 1) r.bits(1) // overscan
            if (r.bits(1) == 1) { // video_signal_type_present_flag
                r.bits(3) // video_format
                info = info.copy(fullRange = r.bits(1) == 1)
                if (r.bits(1) == 1) { // colour_description_present_flag
                    val primaries = r.bits(8)
                    val transfer = r.bits(8)
                    val matrix = r.bits(8)
                    info = info.copy(
                        colourPrimaries = primaries,
                        transferCharacteristics = transfer,
                        matrixCoefficients = matrix,
                    )
                }
            }
            if (r.bits(1) == 1) { r.ue(); r.ue() } // chroma_loc_info
            if (r.bits(1) == 1) { // timing_info_present_flag
                val unitsInTick = r.bitsLong(32)
                val timeScale = r.bitsLong(32)
                r.bits(1) // fixed_frame_rate_flag
                if (unitsInTick > 0 && timeScale > 0) {
                    // One frame is two ticks (field-based timing, E.2.1).
                    val fps = timeScale.toDouble() / (2.0 * unitsInTick)
                    if (fps in 1.0..300.0) info = info.copy(fps = fps)
                }
            }
            val nalHrd = r.bits(1) == 1
            if (nalHrd) skipHrd(r)
            val vclHrd = r.bits(1) == 1
            if (vclHrd) skipHrd(r)
            if (nalHrd || vclHrd) r.bits(1) // low_delay_hrd_flag
            r.bits(1) // pic_struct_present_flag
            if (r.bits(1) == 1) { // bitstream_restriction_flag
                r.bits(1) // motion_vectors_over_pic_boundaries
                r.ue(); r.ue(); r.ue(); r.ue()
                info = info.copy(maxNumReorderFrames = r.ue())
                r.ue() // max_dec_frame_buffering
            }
        } catch (_: RuntimeException) {
            // Best effort: keep whatever was read before the VUI ran out.
        }
        return info
    }

    private fun skipHrd(r: CastBitReader) {
        val count = r.ue() + 1 // cpb_cnt_minus1
        r.bits(8) // bit_rate_scale, cpb_size_scale
        for (i in 0 until minOf(count, 32)) {
            r.ue(); r.ue(); r.bits(1)
        }
        r.bits(20) // four 5-bit length fields
    }

    private fun skipScalingList(r: CastBitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (j in 0 until size) {
            if (nextScale != 0) nextScale = (lastScale + r.se() + 256) % 256
            if (nextScale != 0) lastScale = nextScale
        }
    }
}
