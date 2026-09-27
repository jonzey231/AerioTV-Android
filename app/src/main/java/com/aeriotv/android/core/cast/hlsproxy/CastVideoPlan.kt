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
//
// HEVC sources (2026-09-27) take the same two outputs through the same
// pipeline, decoded by the platform HEVC decoder; see [CastVideoPlan.decide].

/** What the remuxer learned from the source SPS: H.264 (see
 *  [CastSpsParser.parseSpsInfo]) or HEVC (see
 *  [CastSpsParser.parseHevcStreamInfo]). The plan, the transcoder and the
 *  cast card's transcode note all read this one shape. */
data class CastVideoStreamInfo(
    val width: Int,
    val height: Int,
    /** H.264 profile_idc, or HEVC general_profile_idc. */
    val profileIdc: Int,
    /** H.264 constraint_set flags byte; unused for HEVC. */
    val constraintFlags: Int,
    /** H.264 level_idc (41 = 4.1), or HEVC general_level_idc (153 = 5.1). */
    val levelIdc: Int,
    /** frame_mbs_only_flag (H.264) or !field_seq_flag (HEVC): false for
     *  interlaced coding. */
    val progressive: Boolean = true,
    /** Frames per second from the VUI timing info, null when absent. */
    val fps: Double? = null,
    val fullRange: Boolean = false,
    val colourPrimaries: Int? = null,
    val transferCharacteristics: Int? = null,
    val matrixCoefficients: Int? = null,
    /** VUI bitstream_restriction max_num_reorder_frames, null when absent. */
    val maxNumReorderFrames: Int? = null,
    val codec: CastVideoOutputSpec.Codec = CastVideoOutputSpec.Codec.H264,
    /** Luma bit depth: 8, or 10 for HEVC Main10 (HLG / PQ broadcasts). */
    val bitDepth: Int = 8,
    /** chroma_format_idc: 1 is 4:2:0. */
    val chromaFormatIdc: Int = 1,
    /** RFC 6381 hvc1 string for an HEVC source (from the SPS profile, tier
     *  and level), null for H.264. */
    val hevcCodecString: String? = null,
) {
    /** HDR transfer from the VUI transfer_characteristics: 18 is HLG
     *  (ARIB STD-B67), 16 is PQ (SMPTE ST 2084). Anything else, or no VUI,
     *  is SDR. Read by the plan (HDR needs its own receiver keys) and the
     *  transcoder (tone map or keep). */
    val hdrTransfer: CastHdrTransfer?
        get() = when (transferCharacteristics) {
            18 -> CastHdrTransfer.HLG
            16 -> CastHdrTransfer.PQ
            else -> null
        }

    val isHdr: Boolean get() = hdrTransfer != null

    /** VUI colour_primaries 9: BT.2020 (every HLG / PQ broadcast measured
     *  so far; the tone map converts it to BT.709). */
    val isBt2020: Boolean get() = colourPrimaries == 9

    /** RFC 6381 string: avc1 from the SPS header bytes, hvc1 from the PTL. */
    val codecString: String
        get() = hevcCodecString ?: String.format(
            Locale.US, "avc1.%02X%02X%02X",
            profileIdc and 0xFF, constraintFlags and 0xFF, levelIdc and 0xFF,
        )

    /** "4.1" for H.264 level_idc 41, "5.1" for HEVC general_level_idc 153. */
    val levelLabel: String
        get() = if (codec == CastVideoOutputSpec.Codec.HEVC) {
            "${levelIdc / 30}.${(levelIdc % 30) / 3}"
        } else {
            "${levelIdc / 10}.${levelIdc % 10}"
        }

    /** "1920x1080@59.94", or "@?" without VUI timing. */
    val label: String get() = "${width}x$height@${fps?.let { CastVideoPlan.fpsLabel(it) } ?: "?"}"

    /** "HEVC" or "H.264": the user-facing codec name. */
    val codecName: String get() = CastVideoPlan.codecName(codec)
}

/** The two HDR transfers the cast path knows. [key] is the suffix of the
 *  receiver caps keys (`hevc_1080p60_hlg`, `hvc1.pq`). */
enum class CastHdrTransfer(val key: String, val label: String) {
    HLG("hlg", "HLG"),
    PQ("pq", "PQ"),
}

/** The video path the remuxer settled on for one ingest connection, handed
 *  up through the proxy session and the sender to the cast card. */
data class CastVideoPathInfo(
    val source: CastVideoStreamInfo,
    /** null means passthrough. */
    val output: CastVideoOutputSpec?,
    val reason: String,
    /** [CastVideoDecision.unsupported], carried so the card never
     *  recomputes it. */
    val unsupported: List<String> = emptyList(),
    /** [CastVideoDecision.forced]. */
    val forced: Boolean = false,
) {
    val isTranscode: Boolean get() = output != null
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

    /** HDR [t] HEVC at 4K60 is presentable (canDisplayType with the eotf
     *  form). An SDR yes says nothing about HDR: a receiver that decodes
     *  HEVC but cannot show HLG / PQ plays it washed out. */
    fun hevcHdr4K(t: CastHdrTransfer): Boolean = display("hevc_4k60_${t.key}")

    /** HDR [t] HEVC at 1080p60 is presentable. */
    fun hevcHdr1080(t: CastHdrTransfer): Boolean = display("hevc_1080p60_${t.key}")

    /** HDR [t] HEVC at 720-class sizes: MSE `hvc1.hlg` / `hvc1.pq`, or the
     *  1080 display key (a smaller picture presents where 1080 does). */
    fun hevcHdr720(t: CastHdrTransfer): Boolean = mse("hvc1.${t.key}") || hevcHdr1080(t)

    /** HDR [t] presentable at [width]x[height], by size class. */
    fun hevcHdr(t: CastHdrTransfer, width: Int, height: Int): Boolean = when {
        width > 1920 || height > 1088 -> hevcHdr4K(t)
        width > 1280 || height > 720 -> hevcHdr1080(t)
        else -> hevcHdr720(t)
    }
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
    /** The source's HDR transfer is kept (HEVC Main10 with the source
     *  tags) because the receiver presents HDR at this output size. false
     *  for every SDR output, including a tone-mapped HDR source. */
    val hdr: Boolean = false,
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
    /** For a transcode: what the receiver's answers ruled out for this
     *  source, in card order, from "HEVC", "4K", "1080p60", "1080p", "HDR".
     *  Empty for passthrough and for a forced transcode. */
    val unsupported: List<String> = emptyList(),
    /** The Developer force switch caused this transcode. */
    val forced: Boolean = false,
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
     * H.264 source:
     * 1. A source the receiver presents as H.264 passes through: anything
     *    up to 1280x720 at any rate (a lower pixel rate than 1080p30), 1080
     *    at up to 30 fps when `display.h264_1080p30`, 1080 at any rate when
     *    `display.h264_1080p60`, 4K when `display.h264_4k60`.
     * 2. Otherwise HEVC at the source size when the receiver presents HEVC
     *    at that size (a 4K source falls to 1080 HEVC when only 1080 HEVC
     *    is presentable).
     * 3. Otherwise H.264 High 4.1 in the Developer down profile.
     * Without a `display` map (an older receiver page) or without caps at
     * all the plan passes through unless forced.
     *
     * HEVC source (see [decideHevc]): passes through when the receiver
     * presents HEVC at the source size, else HEVC 1080 at the source rate
     * when the receiver presents 1080 HEVC, else the H.264 down profile.
     *
     * The force switch skips the passthrough rule for both.
     */
    fun decide(s: CastVideoStreamInfo): CastVideoDecision {
        val d = decideShape(s)
        if (d.output == null) return d
        return if (force) d.copy(forced = true) else d.copy(unsupported = ruledOut(s))
    }

    /**
     * The caps checks that failed for this source, in card order. Mirrors
     * the passthrough tests in [decideShape] / [decideHevc] so the card
     * names exactly what sent the source to the transcoder:
     *  - "HEVC": an HEVC source and the receiver presents HEVC at no size.
     *  - "4K": a 4K source refused at 4K in its own codec.
     *  - "1080p60": a 1080-class source above 30 fps refused where 1080p30
     *    is accepted (H.264 only; HEVC has no 30 fps key).
     *  - "1080p": a 1080-class source refused outright.
     *  - "HDR": an HDR source without the HDR key for its size class.
     */
    fun ruledOut(s: CastVideoStreamInfo): List<String> {
        val caps = this.caps ?: CastReceiverVideoCaps(emptyMap(), null)
        val is4K = s.width > 1920 || s.height > 1088
        val is1080 = !is4K && (s.width > 1280 || s.height > 720)
        val out = ArrayList<String>(4)
        if (s.codec == CastVideoOutputSpec.Codec.HEVC) {
            val anyHevc = caps.hevc1080 || caps.hevc4K || caps.mse("hvc1")
            if (!anyHevc) out.add("HEVC")
            if (is4K && !caps.display("hevc_4k60")) out.add("4K")
            if (is1080 && anyHevc && !(caps.display("hevc_1080p60") || caps.mse("hvc1"))) out.add("1080p")
        } else {
            val levelFps = if (s.levelIdc > 40) 60.0 else 30.0
            val highRate = (s.fps ?: levelFps) > 31
            if (is4K && !caps.display("h264_4k60")) out.add("4K")
            if (is1080 && !caps.display("h264_1080p60")) {
                when {
                    !caps.display("h264_1080p30") -> out.add("1080p")
                    highRate -> out.add("1080p60")
                }
            }
        }
        val t = s.hdrTransfer
        if (t != null && !caps.hevcHdr(t, s.width, s.height)) out.add("HDR")
        return out
    }

    private fun decideShape(s: CastVideoStreamInfo): CastVideoDecision {
        disabledReason?.let { return CastVideoDecision(null, it) }
        if (s.codec == CastVideoOutputSpec.Codec.HEVC) return decideHevc(s)
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
            return CastVideoDecision(withHdr(s, caps, hevcOutput(s, null, null, CastVideoOutputSpec.HEVC_4K_CAP)), trigger)
        }
        if (caps.hevc1080) {
            return CastVideoDecision(
                withHdr(s, caps, hevcOutput(s, 1920, 1080, CastVideoOutputSpec.HEVC_1080_CAP)), trigger,
            )
        }
        return CastVideoDecision(h264DownOutput(s, caps, highRate), trigger)
    }

    /**
     * HEVC source. Passthrough when the receiver presents HEVC at the source
     * size: 4K needs `display.hevc_4k60`; 1080-class needs
     * `display.hevc_1080p60` or MSE `hvc1`; 720-class needs MSE `hvc1`.
     * Otherwise a 4K source becomes HEVC 1080p at the same frame rate when
     * the receiver presents 1080 HEVC (the Google TV Streamer answers
     * hevc_1080p60 yes, hevc_4k60 no), and everything else takes the H.264
     * down profile (a Chromecast Ultra answers no to every HEVC key).
     *
     * HDR (VUI transfer 18 HLG or 16 PQ): passthrough also needs the HDR
     * key for the source size class (`display.hevc_4k60_<hlg|pq>`,
     * `display.hevc_1080p60_<hlg|pq>`, or MSE `hvc1.<hlg|pq>` for 720-class).
     * Without it the source is transcoded even when the SDR HEVC key says
     * yes: to HEVC 1080 when the receiver presents SDR HEVC, else the H.264
     * down profile. The output keeps HDR only when the receiver presents
     * that transfer at the OUTPUT size class; otherwise it is SDR BT.709,
     * tone mapped on the phone. H.264 output is always SDR.
     *
     * Unlike H.264, missing caps never mean passthrough here: an HEVC stream
     * sent blind is a black screen on every receiver that lacks HEVC, while
     * the H.264 down profile plays on all of them.
     */
    private fun decideHevc(s: CastVideoStreamInfo): CastVideoDecision {
        val caps = this.caps ?: CastReceiverVideoCaps(emptyMap(), null)
        val is4K = s.width > 1920 || s.height > 1088
        val is1080 = !is4K && (s.width > 1280 || s.height > 720)
        // No VUI timing: level 4.1 (123) and up is the 1080p50/60 class.
        val fps = s.fps ?: if (s.levelIdc > 120) 60.0 else 30.0
        val highRate = fps > 31
        val sdrFits = when {
            is4K -> caps.display("hevc_4k60")
            is1080 -> caps.display("hevc_1080p60") || caps.mse("hvc1")
            else -> caps.mse("hvc1")
        }
        // An HDR source also needs the HDR key for its size class: the
        // Chromecast Ultra class decodes HEVC but a receiver without HDR
        // shows HLG / PQ washed out, so an SDR-only yes is a transcode.
        val hdr = s.hdrTransfer
        val hdrFits = hdr == null || caps.hevcHdr(hdr, s.width, s.height)
        if (sdrFits && hdrFits && !force) return CastVideoDecision(null, "receiver displays the source")
        val trigger = when {
            force -> "Developer switch"
            this.caps == null -> "receiver caps not measured"
            sdrFits -> "receiver does not display ${hdr?.label ?: "HDR"} at the source size"
            else -> "receiver does not display HEVC at the source size"
        }
        // Forced only: a 4K source the receiver presents at 4K is re-encoded
        // at 4K, the H.264 rule's shape.
        if (is4K && force && caps.display("hevc_4k60")) {
            return CastVideoDecision(withHdr(s, caps, hevcOutput(s, null, null, CastVideoOutputSpec.HEVC_4K_CAP)), trigger)
        }
        // HEVC 1080 for a 4K source, and for an HDR source the receiver
        // presents as SDR HEVC at its own size (tone mapped on the phone
        // unless the receiver presents HDR at the output size).
        if (caps.hevc1080 && (is4K || force || (hdr != null && sdrFits))) {
            return CastVideoDecision(
                withHdr(s, caps, hevcOutput(s, 1920, 1080, CastVideoOutputSpec.HEVC_1080_CAP)), trigger,
            )
        }
        return CastVideoDecision(h264DownOutput(s, caps, highRate), trigger)
    }

    /** An HEVC output keeps the source's HDR only when the receiver
     *  presents that transfer at the OUTPUT size class; otherwise it is SDR
     *  (tone mapped on the phone). H.264 output is never HDR. */
    private fun withHdr(s: CastVideoStreamInfo, caps: CastReceiverVideoCaps, out: CastVideoOutputSpec): CastVideoOutputSpec {
        val t = s.hdrTransfer ?: return out
        if (out.codec != CastVideoOutputSpec.Codec.HEVC) return out
        return out.copy(hdr = caps.hevcHdr(t, out.width, out.height))
    }

    /** HEVC at the source size, fitted into [maxWidth]x[maxHeight] (null
     *  keeps the source size, even dimensions). */
    private fun hevcOutput(s: CastVideoStreamInfo, maxWidth: Int?, maxHeight: Int?, cap: Int): CastVideoOutputSpec {
        val size = if (maxWidth == null || maxHeight == null) {
            Pair(even(s.width), even(s.height))
        } else {
            fit(s.width, s.height, maxWidth, maxHeight)
        }
        return CastVideoOutputSpec(
            CastVideoOutputSpec.Codec.HEVC, size.first, size.second, frameStep = 1, bitrateCap = cap,
        )
    }

    /** H.264 High 4.1 in the Developer down profile. 1080p30 needs the
     *  receiver to present 1080p30 (or an unmeasured display); otherwise
     *  720p at the source rate. */
    private fun h264DownOutput(s: CastVideoStreamInfo, caps: CastReceiverVideoCaps, highRate: Boolean): CastVideoOutputSpec {
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
                CastVideoOutputSpec(
                    CastVideoOutputSpec.Codec.H264, size.first, size.second,
                    frameStep = 1, bitrateCap = CastVideoOutputSpec.H264_CAP,
                )
            }
            CastTranscodeDownProfile.P1080P30 -> {
                val size = fit(s.width, s.height, 1920, 1080)
                CastVideoOutputSpec(
                    CastVideoOutputSpec.Codec.H264, size.first, size.second,
                    frameStep = if (highRate) 2 else 1, bitrateCap = CastVideoOutputSpec.H264_CAP,
                )
            }
        }
    }

    /** `[Cast] video plan: source=avc1.64002A 1920x1080@59.94 receiver
     *  display h264_1080p60=no h264_1080p30=yes hevc_1080p60=no hvc1=no ->
     *  transcode H.264 720p59.94 level 4.1 (8000 kbps)`. */
    fun logLine(source: CastVideoStreamInfo, decision: CastVideoDecision): String {
        val caps = this.caps ?: CastReceiverVideoCaps(emptyMap(), null)
        fun yn(b: Boolean) = if (b) "yes" else "no"
        val line = StringBuilder("[Cast] video plan: source=${source.codecString} ${source.label}")
        if (!source.progressive) line.append(" interlaced")
        if (source.bitDepth > 8) line.append(" ${source.bitDepth}-bit")
        source.hdrTransfer?.let { line.append(" HDR ${it.label}") }
        when {
            this.caps == null -> line.append(" receiver caps=none")
            caps.display == null -> line.append(" receiver display=none hvc1=${yn(caps.mse("hvc1"))}")
            else -> line.append(
                " receiver display h264_1080p60=${yn(caps.display("h264_1080p60"))}" +
                    " h264_1080p30=${yn(caps.display("h264_1080p30"))}" +
                    " hevc_1080p60=${yn(caps.display("hevc_1080p60"))}" +
                    " hvc1=${yn(caps.mse("hvc1"))}" +
                    // The 4K keys only decide anything for an HEVC source.
                    if (source.codec == CastVideoOutputSpec.Codec.HEVC) {
                        " hevc_4k60=${yn(caps.display("hevc_4k60"))}"
                    } else {
                        ""
                    } +
                    // The HDR keys only decide anything for an HDR source.
                    (
                        source.hdrTransfer?.let { t ->
                            " hevc_1080p60_${t.key}=${yn(caps.hevcHdr1080(t))}" +
                                " hevc_4k60_${t.key}=${yn(caps.hevcHdr4K(t))}" +
                                " hvc1.${t.key}=${yn(caps.mse("hvc1.${t.key}"))}"
                        } ?: ""
                        ),
            )
        }
        val out = decision.output ?: return line.append(" -> passthrough (${decision.reason})").toString()
        val fps = out.outputFps(source.fps)?.let { fpsLabel(it) } ?: ""
        val kbps = out.bitrateCap / 1000
        when (out.codec) {
            CastVideoOutputSpec.Codec.HEVC -> line.append(
                " -> transcode HEVC ${out.height}p$fps${if (out.hdr) " HDR" else ""} ($kbps kbps)",
            )
            CastVideoOutputSpec.Codec.H264 ->
                line.append(" -> transcode H.264 ${out.height}p$fps level 4.1 ($kbps kbps)")
        }
        if (source.isHdr && !out.hdr) line.append(" tone mapped to SDR BT.709")
        if (force) line.append(" [forced]")
        return line.toString()
    }

    companion object {
        val PASSTHROUGH = CastVideoPlan(caps = null)

        /** User-facing codec name: "HEVC" or "H.264". */
        fun codecName(codec: CastVideoOutputSpec.Codec): String = when (codec) {
            CastVideoOutputSpec.Codec.HEVC -> "HEVC"
            CastVideoOutputSpec.Codec.H264 -> "H.264"
        }

        /** Up to two decimals, trailing zeros trimmed: 59.94, 59.9, 50, 60.
         *  The cast card copy; iOS formats the same way. */
        fun fpsText(fps: Double): String =
            String.format(Locale.US, "%.2f", fps).trimEnd('0').trimEnd('.')

        /** "HEVC 3840x2160 at 50fps", plus " HDR" for an HDR picture; the
         *  frame rate is left off when the stream carries no VUI timing. */
        fun videoShape(
            codec: CastVideoOutputSpec.Codec,
            width: Int,
            height: Int,
            fps: Double?,
            hdr: Boolean = false,
        ): String =
            "${codecName(codec)} ${width}x$height" + (fps?.let { " at ${fpsText(it)}fps" } ?: "") +
                if (hdr) " HDR" else ""

        /**
         * The cast card's transcode note, exact copy shared with iOS, in
         * this order under the "Receiver:" line:
         *   "Source: HEVC 3840x2160 at 50fps HDR"
         *   "Transcoding on this <phone|tablet> to H.264 1280x720 at 50fps"
         *   "Your <Receiver> doesn't support HEVC, 4K, HDR"
         *     ("This receiver doesn't support ..." without a name;
         *      "Transcode forced by the Developer switch" when forced;
         *      "... didn't report what it supports" when caps never came)
         * Empty when the path is passthrough (no transcode, no note).
         */
        fun transcodeNote(receiverName: String?, deviceNoun: String, path: CastVideoPathInfo?): List<String> {
            val out = path?.output ?: return emptyList()
            val src = path.source
            val lines = mutableListOf(
                "Source: ${videoShape(src.codec, src.width, src.height, src.fps, src.isHdr)}",
                "Transcoding on this $deviceNoun to " +
                    videoShape(out.codec, out.width, out.height, out.outputFps(src.fps), out.hdr),
            )
            val name = receiverName?.trim()?.takeIf { it.isNotEmpty() }
            when {
                path.forced -> lines.add("Transcode forced by the Developer switch")
                path.unsupported.isNotEmpty() -> lines.add(
                    (if (name != null) "Your $name" else "This receiver") +
                        " doesn't support ${path.unsupported.joinToString(", ")}",
                )
                // Caps never came (iOS parity): say so rather than nothing.
                else -> lines.add(
                    (if (name != null) "Your $name" else "This receiver") +
                        " didn't report what it supports",
                )
            }
            return lines
        }

        /** The "Receiver" stat: what the receiver reports it is actually
         *  presenting, "1920x1080 at 60fps" (or just the size before the
         *  first frame-rate sample). */
        fun receiverPlayingText(resolution: String, fps: Double?): String =
            resolution + (fps?.takeIf { it > 0 }?.let { " at ${Math.round(it)}fps" } ?: "")

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

    /** HEVC SPS fields the hvcC record repeats, plus the VUI facts the
     *  video plan reads (best effort: null when the VUI is absent or runs
     *  out). */
    data class HevcSpsInfo(
        /** general_profile_space .. general_level_idc: the 12 bytes hvcC
         *  copies verbatim. */
        val generalPtl: List<Int>,
        val maxSubLayersMinus1: Int,
        val temporalIdNesting: Boolean,
        val chromaFormatIdc: Int,
        val bitDepthLumaMinus8: Int,
        val bitDepthChromaMinus8: Int,
        /** Conformance-window cropped size. */
        val width: Int,
        val height: Int,
        val fps: Double? = null,
        val fullRange: Boolean = false,
        val colourPrimaries: Int? = null,
        val transferCharacteristics: Int? = null,
        val matrixCoefficients: Int? = null,
        /** VUI field_seq_flag: each picture is one field. */
        val fieldSeq: Boolean = false,
        /** sps_max_num_reorder_pics of the highest sub-layer. */
        val maxNumReorderPics: Int? = null,
    )

    /** Parse an HEVC SPS NAL (2-byte header included, emulation prevention
     *  bytes still in), ITU-T H.265 7.3.2.2. Everything through the bit
     *  depths must parse; the rest, up to the VUI timing info, is read best
     *  effort. */
    fun parseHevcSps(nal: ByteArray): HevcSpsInfo? {
        if (nal.size <= 15 || ((nal[0].toInt() shr 1) and 0x3F) != 33) return null
        val rbsp = CastSpsParser.unescapeRbsp(nal, 2)
        if (rbsp.size < 13) return null
        val maxSub = (rbsp[0].toInt() shr 1) and 0x07
        val nesting = rbsp[0].toInt() and 1 == 1
        val ptl = (1 until 13).map { rbsp[it].toInt() and 0xFF }
        val r = CastBitReader(rbsp)
        val base = try {
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
            // separate_colour_plane_flag: ChromaArrayType 0 for the crop units.
            val separatePlanes = chroma == 3 && r.bits(1) == 1
            val cropChroma = if (separatePlanes) 0 else chroma
            var width = r.ue()
            var height = r.ue()
            if (r.bits(1) == 1) { // conformance_window_flag
                val l = r.ue(); val rr = r.ue(); val t = r.ue(); val b = r.ue()
                val subW = if (cropChroma == 1 || cropChroma == 2) 2 else 1
                val subH = if (cropChroma == 1) 2 else 1
                width -= (l + rr) * subW
                height -= (t + b) * subH
            }
            val luma = r.ue()
            val chromaDepth = r.ue()
            HevcSpsInfo(ptl, maxSub, nesting, chroma, luma, chromaDepth, width, height)
        } catch (_: RuntimeException) {
            return null
        }
        return parseHevcSpsTail(r, base, maxSub)
    }

    /** From log2_max_pic_order_cnt_lsb_minus4 to the VUI timing info. */
    private fun parseHevcSpsTail(r: CastBitReader, base: HevcSpsInfo, maxSub: Int): HevcSpsInfo {
        var info = base
        try {
            val log2MaxPocLsb = r.ue() + 4
            val orderingAll = r.bits(1) == 1
            for (i in (if (orderingAll) 0 else maxSub)..maxSub) {
                r.ue() // sps_max_dec_pic_buffering_minus1
                info = info.copy(maxNumReorderPics = r.ue())
                r.ue() // sps_max_latency_increase_plus1
            }
            repeat(6) { r.ue() } // coding / transform block sizes and depths
            if (r.bits(1) == 1 && r.bits(1) == 1) skipHevcScalingListData(r)
            r.bits(1); r.bits(1) // amp, sample_adaptive_offset
            if (r.bits(1) == 1) { // pcm_enabled_flag
                r.bits(4); r.bits(4); r.ue(); r.ue(); r.bits(1)
            }
            val numStRps = r.ue()
            if (numStRps > 64) return info
            val numDeltaPocs = IntArray(numStRps)
            for (i in 0 until numStRps) numDeltaPocs[i] = skipStRefPicSet(r, i, numDeltaPocs)
            if (r.bits(1) == 1) { // long_term_ref_pics_present_flag
                repeat(r.ue()) { r.bits(log2MaxPocLsb); r.bits(1) }
            }
            r.bits(1); r.bits(1) // temporal_mvp, strong_intra_smoothing
            if (r.bits(1) != 1) return info // vui_parameters_present_flag
            if (r.bits(1) == 1 && r.bits(8) == 255) { r.bits(16); r.bits(16) } // aspect ratio
            if (r.bits(1) == 1) r.bits(1) // overscan
            if (r.bits(1) == 1) { // video_signal_type_present_flag
                r.bits(3) // video_format
                info = info.copy(fullRange = r.bits(1) == 1)
                if (r.bits(1) == 1) {
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
            r.bits(1) // neutral_chroma_indication_flag
            info = info.copy(fieldSeq = r.bits(1) == 1)
            r.bits(1) // frame_field_info_present_flag
            if (r.bits(1) == 1) { r.ue(); r.ue(); r.ue(); r.ue() } // default display window
            if (r.bits(1) == 1) { // vui_timing_info_present_flag
                val unitsInTick = r.bitsLong(32)
                val timeScale = r.bitsLong(32)
                if (unitsInTick > 0 && timeScale > 0) {
                    // HEVC ticks are whole pictures (no field factor of 2).
                    val fps = timeScale.toDouble() / unitsInTick
                    if (fps in 1.0..300.0) info = info.copy(fps = fps)
                }
            }
        } catch (_: RuntimeException) {
            // Best effort: keep whatever was read before the SPS ran out.
        }
        return info
    }

    private fun skipHevcScalingListData(r: CastBitReader) {
        for (sizeId in 0 until 4) {
            var matrixId = 0
            while (matrixId < 6) {
                if (r.bits(1) == 0) {
                    r.ue() // scaling_list_pred_matrix_id_delta
                } else {
                    val coefNum = minOf(64, 1 shl (4 + (sizeId shl 1)))
                    if (sizeId > 1) r.se() // dc coef
                    repeat(coefNum) { r.se() }
                }
                matrixId += if (sizeId == 3) 3 else 1
            }
        }
    }

    /** st_ref_pic_set(idx) inside the SPS; returns its NumDeltaPocs. */
    private fun skipStRefPicSet(r: CastBitReader, idx: Int, numDeltaPocs: IntArray): Int {
        val interPred = idx != 0 && r.bits(1) == 1
        if (interPred) {
            r.bits(1); r.ue() // delta_rps_sign, abs_delta_rps_minus1
            var count = 0
            for (j in 0..numDeltaPocs[idx - 1]) {
                val used = r.bits(1) == 1
                val useDelta = used || r.bits(1) == 1
                if (useDelta) count++
            }
            return count
        }
        val neg = r.ue()
        val pos = r.ue()
        if (neg > 16 || pos > 16) throw IndexOutOfBoundsException("implausible RPS")
        repeat(neg + pos) { r.ue(); r.bits(1) }
        return neg + pos
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

    /** HEVC SPS to the stream facts the video plan needs. The VUI is read
     *  best effort, as for H.264. Throws on an unreadable SPS. */
    fun parseHevcStreamInfo(spsNal: ByteArray): CastVideoStreamInfo {
        val sps = CastVideoCodecConfig.parseHevcSps(spsNal) ?: error("unreadable HEVC SPS")
        if (sps.width <= 0 || sps.height <= 0 || sps.width > 8192 || sps.height > 8192) error("implausible")
        val ptl = sps.generalPtl
        return CastVideoStreamInfo(
            width = sps.width,
            height = sps.height,
            profileIdc = ptl[0] and 0x1F,
            constraintFlags = 0,
            levelIdc = ptl[11],
            progressive = !sps.fieldSeq,
            fps = sps.fps,
            fullRange = sps.fullRange,
            colourPrimaries = sps.colourPrimaries,
            transferCharacteristics = sps.transferCharacteristics,
            matrixCoefficients = sps.matrixCoefficients,
            maxNumReorderFrames = sps.maxNumReorderPics,
            codec = CastVideoOutputSpec.Codec.HEVC,
            bitDepth = sps.bitDepthLumaMinus8 + 8,
            chromaFormatIdc = sps.chromaFormatIdc,
            hevcCodecString = CastVideoCodecConfig.hevcCodecString(
                byteArrayOf(1) + ptl.map { it.toByte() }.toByteArray(),
            ),
        )
    }

    /** H.264 SPS to the stream facts the video plan needs. The VUI is read
     *  best effort: a truncated or exotic VUI leaves fps / colour null
     *  instead of failing the dimensions. Throws on an unreadable SPS. */
    fun parseSpsInfo(spsNal: ByteArray): CastVideoStreamInfo {
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
        val base = CastVideoStreamInfo(
            width = width, height = height, profileIdc = profileIdc,
            constraintFlags = constraints, levelIdc = levelIdc, progressive = frameMbsOnly == 1,
        )
        return parseVui(r, base)
    }

    private fun parseVui(r: CastBitReader, base: CastVideoStreamInfo): CastVideoStreamInfo {
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

/**
 * Per-stage timing of the on-phone video transcode, summed over one stats
 * interval (2026-09-27, Nothing Phone (2) sustained 42 of 50 fps). Every
 * stage runs on the transcoder's work thread; [report] runs on the stats
 * timer, so all access is synchronized. Pure: tested on the JVM.
 *
 * Stages per frame: waiting for a free decoder input buffer, decoded frames
 * out of the decoder, waiting for the decoded frame to reach the
 * SurfaceTexture, latch + draw, eglSwapBuffers (blocks when the encoder's
 * input surface has no free buffer), waiting on the encoder in-flight cap.
 */
class CastTranscodeStageStats {
    private var decoded = 0
    private var renders = 0
    private var frameWaitNs = 0L
    private var drawNs = 0L
    private var swapNs = 0L
    private var renderMaxNs = 0L
    private var inputWaitNs = 0L
    private var capWaitNs = 0L
    private var busyNs = 0L

    @Synchronized fun noteDecoded() { decoded++ }

    @Synchronized fun noteRender(frameWait: Long, draw: Long, swap: Long) {
        renders++
        frameWaitNs += frameWait
        drawNs += draw
        swapNs += swap
        renderMaxNs = maxOf(renderMaxNs, frameWait + draw + swap)
    }

    @Synchronized fun noteInputWait(ns: Long) { inputWaitNs += ns }
    @Synchronized fun noteCapWait(ns: Long) { capWaitNs += ns }
    @Synchronized fun noteBusy(ns: Long) { busyNs += ns }

    /** One line for [elapsedMs] of wall time; resets the sums. */
    @Synchronized fun report(elapsedMs: Long): String {
        val secs = maxOf(0.001, elapsedMs / 1000.0)
        val n = maxOf(1, renders)
        fun ms(ns: Long) = ns / 1_000_000.0
        val line = String.format(
            java.util.Locale.US,
            "video transcode: stages decoded %.1f fps, rendered %.1f fps, render %.1f ms avg %.1f max " +
                "(frame wait %.1f, draw %.1f, swap %.1f), waits decoder input %.0f ms/s encoder cap %.0f ms/s, " +
                "work thread busy %.0f%%",
            decoded / secs, renders / secs, ms(frameWaitNs + drawNs + swapNs) / n, ms(renderMaxNs),
            ms(frameWaitNs) / n, ms(drawNs) / n, ms(swapNs) / n,
            ms(inputWaitNs) / secs, ms(capWaitNs) / secs, minOf(100.0, ms(busyNs) / 10.0 / secs),
        )
        decoded = 0; renders = 0; frameWaitNs = 0; drawNs = 0; swapNs = 0; renderMaxNs = 0
        inputWaitNs = 0; capWaitNs = 0; busyNs = 0
        return line
    }
}
