package com.aeriotv.android.core.playback

/**
 * Video codec TYPE changes across a Dispatcharr stream switch (Streamer
 * 2026-11-01). The MediaTek Codec2 HAL (android.hardware.media.c2@1.2-mediatek)
 * crashed six times, every time since 18:20 within 60 ms of the HAL destroying
 * c2.mtk.avc.decoder and creating c2.mtk.hevc.decoder (or the reverse) inside
 * one player. Same-type recreates and plain tunes survived. So a type change
 * is never handled in place: the player is stopped, the video decoder release
 * is awaited, the HAL settles, and only then is the new type prepared.
 *
 * Pure logic, no Android types, so the unit tests can drive it.
 */
object CodecChangeRule {
    /** Settle after the decoder release before re-preparing a new type. */
    const val SETTLE_MS = 150L

    /** MPEG-TS PMT stream_type values that carry video. */
    private val VIDEO_STREAM_TYPES = setOf(
        0x01, // MPEG-1 video
        0x02, // MPEG-2 video
        0x10, // MPEG-4 part 2
        0x1B, // H.264
        0x20, // H.264 MVC sub-bitstream
        0x24, // HEVC
        0x42, // AVS
        0xD1, // Dirac
        0xEA, // VC-1
    )

    fun isVideoStreamType(type: Int): Boolean = type in VIDEO_STREAM_TYPES

    /** The first video stream type in a PMT's (type, pid) list, or null. */
    fun videoStreamType(streams: List<Pair<Int, Int>>): Int? =
        streams.firstOrNull { isVideoStreamType(it.first) }?.first

    /** Normalized codec family for a PMT stream type. */
    fun familyOfStreamType(type: Int?): String? = when (type) {
        null -> null
        0x1B, 0x20 -> "h264"
        0x24 -> "hevc"
        0x01, 0x02 -> "mpeg2"
        0x10 -> "mpeg4"
        0xEA -> "vc1"
        else -> "type0x%02x".format(type)
    }

    /** Normalized codec family for a Dispatcharr stream_stats.video_codec
     *  value (ffprobe codec_name) or a Media3 sample MIME type. */
    fun familyOf(codec: String?): String? {
        val c = codec?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return when {
            c == "video/avc" || c == "h264" || c == "avc" || c == "avc1" || c.startsWith("h.264") -> "h264"
            c == "video/hevc" || c == "hevc" || c == "h265" || c == "hev1" || c == "hvc1" || c.startsWith("h.265") -> "hevc"
            c == "video/mpeg2" || c == "mpeg2video" || c == "mpeg2" -> "mpeg2"
            c == "video/mp4v-es" || c == "mpeg4" -> "mpeg4"
            c == "video/wvc1" || c == "vc1" -> "vc1"
            c == "video/av01" || c == "av1" -> "av1"
            c == "video/x-vnd.on2.vp9" || c == "vp9" -> "vp9"
            else -> c
        }
    }

    /** True when a PMT change from [oldType] to [newType] changes the video
     *  codec type, so the decoder must be rebuilt sequentially. A missing
     *  side (no video before, or none after) is not a type change. */
    fun isTypeChange(oldType: Int?, newType: Int?): Boolean =
        oldType != null && newType != null && familyOfStreamType(oldType) != familyOfStreamType(newType)

    enum class SwitchPlan {
        /** Same codec, or the target codec is not known yet: keep the
         *  connection (a type change is still caught at the PMT). */
        KEPT_CONNECTION,
        /** Known different codec: sequential rebuild from the start. */
        SEQUENTIAL,
    }

    /** User Switch Stream: [currentCodec] is what the player decodes now
     *  (MIME or codec name), [targetCodec] the target's stream_stats.video_codec. */
    fun switchPlan(currentCodec: String?, targetCodec: String?): SwitchPlan {
        val cur = familyOf(currentCodec) ?: return SwitchPlan.KEPT_CONNECTION
        val tgt = familyOf(targetCodec) ?: return SwitchPlan.KEPT_CONNECTION
        return if (cur == tgt) SwitchPlan.KEPT_CONNECTION else SwitchPlan.SEQUENTIAL
    }
}
