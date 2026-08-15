package com.aeriotv.android.core.playback

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Shared renderers factory for every player in the app (live holder, VOD,
 * multiview tiles). Two deviations from stock, each gated by a flag:
 *
 * [audioPassthrough] false (the default preference) builds the audio sink
 * with PCM-only capabilities, so Dolby bitstreams (AC3/EAC3) are decoded
 * in-app by MediaCodec and the display receives plain PCM on the standard
 * latency-compensated path. Many TVs decode a passthrough bitstream with
 * latency Android reports as zero, which the player cannot compensate;
 * the visible symptom is lip-sync drift on live TV. True restores the
 * stock sink (bitstream rides HDMI untouched, 5.1 preserved).
 * API gotcha: the Context overload of [DefaultAudioSink.Builder] IGNORES
 * setAudioCapabilities (it installs its own AudioCapabilitiesReceiver), so
 * the deprecated no-context Builder is the one that actually honors a
 * forced capability set.
 *
 * [forceVideoCodecReinit] true (live holder only) vetoes VIDEO decoder
 * reuse across media-item transitions, so every channel switch re-creates
 * the codec against the current surface. Some Codec2 video decoders
 * (observed: c2.exynos.h264.decoder, Samsung phone, GitHub black-screen
 * report on 0.2.7) come out of Media3's flush-and-reuse path decoding but
 * never rendering: audio plays, the screen stays black, and only an app
 * restart recovers. The re-init path is the one that works on every
 * device in the user log; audio codec reuse is untouched, so switches
 * stay snappy.
 */
@OptIn(UnstableApi::class)
fun aerioRenderersFactory(
    context: Context,
    audioPassthrough: Boolean,
    forceVideoCodecReinit: Boolean = false,
    // On-demand path only (VOD + DVR recordings): prefer the bundled FFmpeg
    // audio decoder over the platform MediaCodec one. GH #45 - some devices'
    // hardware AAC decoder (Hisense/MediaTek c2.android.aac.decoder) throws a
    // RUNTIME CodecException 0xe on HE-AAC (AAC+SBR) recordings that ffmpeg
    // decodes fine. enableDecoderFallback can't rescue a post-STARTED runtime
    // failure and never crosses to the separate FFmpeg renderer, so the player
    // fatals + retries the same broken decoder. PREFER routes AAC (and AC-3/
    // E-AC-3/DTS, which this path already PCM-decodes with passthrough off) to
    // FFmpeg first. Scoped to on-demand so live TV's 24/7 hardware-first audio
    // is untouched; a single finite VOD stream is a few % of one core, video
    // stays hardware-decoded. MUST stay false for the live holder + multiview.
    preferSoftwareAudio: Boolean = false,
): DefaultRenderersFactory {
    val factory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink? {
            if (audioPassthrough) {
                android.util.Log.i("AerioPlayerDiag", "audio sink -> stock context sink (passthrough on)")
                return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                    ?.let { AudioSyncShiftSink(it) }
            }
            android.util.Log.i("AerioPlayerDiag", "audio sink -> forced-PCM no-context sink + PTS-smoothing (passthrough off)")
            @Suppress("DEPRECATION")
            val pcmSink = DefaultAudioSink.Builder()
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
            return AudioSyncShiftSink(PtsSmoothingAudioSink(pcmSink))
        }

        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) {
            // Always our subclass: it carries the Dolby Vision base-layer
            // fallback, which every playback path needs, and vetoes codec
            // reuse only when asked. Mirror of the stock platform renderer
            // construction; no video extension renderers are bundled in this
            // app, so skipping the extension lookup super would do loses
            // nothing.
            out.add(
                AerioMediaCodecVideoRenderer(
                    context,
                    codecAdapterFactory,
                    mediaCodecSelector,
                    allowedVideoJoiningTimeMs,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                    vetoCodecReuse = forceVideoCodecReinit,
                ),
            )
        }
    }
    return factory
        .setEnableDecoderFallback(true)
        // EXTENSION_RENDERER_MODE_ON (live + multiview default): the bundled
        // FFmpeg audio renderer sits AFTER the platform MediaCodec renderers, so
        // hardware decoders stay primary and FFmpeg is used only as a fallback
        // for formats the device can't decode in hardware -- notably AC-3 /
        // E-AC-3 / DTS on broadcast (ATSC) channels, which cheaper boxes like the
        // Chromecast with Google TV have no MediaCodec decoder for. Routing ALL
        // audio through the software decoder 24/7 would waste CPU on formats the
        // hardware handles fine, so live stays hardware-first.
        //
        // EXTENSION_RENDERER_MODE_PREFER (on-demand only, preferSoftwareAudio):
        // FFmpeg audio renderer goes FIRST, so it claims AAC (incl. HE-AAC/SBR)
        // and the quirky-hardware-AAC decode failure in GH #45 can't happen. It
        // changes ORDERING, not membership -- the platform renderer is still in
        // the list, so any MIME FFmpeg doesn't advertise (Opus/FLAC/Vorbis/ALAC)
        // still falls through to hardware. No passthrough regression because the
        // on-demand path already forces a PCM sink (audioPassthrough=false); if
        // a "bitstream to receiver" option is ever added to VOD, revisit this.
        .setExtensionRendererMode(
            if (preferSoftwareAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
        )
}

/**
 * Task #184: session-wide user audio sync offset (positive = audio later),
 * mirrored on iOS/tvOS as mpv's audio-delay. Shared by every player built
 * from [aerioRenderersFactory] and reset only on app restart - matching
 * the Apple side, where the offset lives on the warm mpv instance. Not
 * persisted to disk.
 */
object AudioSyncOffset {
    const val MIN_MS = -1000L
    const val MAX_MS = 1000L

    @Volatile
    var offsetMs: Long = 0L
        set(value) {
            field = value.coerceIn(MIN_MS, MAX_MS)
        }
}

/**
 * Task #184: applies [AudioSyncOffset] by shifting the position the audio
 * sink reports. The audio renderer is the player's MediaClock, so video
 * release timing follows the reported audio position: inflating it by X
 * makes video run X early, i.e. audio plays X LATER relative to video
 * (positive = audio later, matching mpv audio-delay). ExoPlayer has no
 * first-class audio-delay knob; this clock shift is the established
 * Media3 pattern. Changes mid-playback re-sync within a frame or two.
 */
@OptIn(UnstableApi::class)
private class AudioSyncShiftSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        if (p == AudioSink.CURRENT_POSITION_NOT_SET) return p
        return (p + AudioSyncOffset.offsetMs * 1000L).coerceAtLeast(0L)
    }
}

/**
 * Wraps the forced-PCM audio sink to absorb the periodic ~1s output-PTS jumps
 * the FFmpeg AC-3 / E-AC-3 decoder emits on live single-PMT MPEG-TS. Root cause
 * (verified against media3 1.4.1 DefaultAudioSink.handleBuffer): when the
 * incoming presentationTimeUs diverges from the sink's frame-derived expected by
 * more than a hardcoded 200ms, the sink drains the AudioTrack to re-sync, which
 * underruns it; since the audio renderer is the MediaClock, the player stalls
 * READY -> BUFFERING for 2-4s and flushes the video codec. There is no public
 * knob to widen that 200ms gate, and the UnexpectedDiscontinuityException is
 * log-only (never thrown), so catching it does nothing. Instead we keep the
 * OUTPUT timeline continuous: on a spurious forward jump (>=150ms and <=1.5s) we
 * advance the rewritten PTS by the last NORMAL inter-buffer cadence instead of
 * the jump, so the gate never trips. Normal buffers, backward jumps, and real
 * gaps (>1.5s) pass through untouched, so it is a structural no-op for VOD and
 * multiview (continuous timestamps) and for passthrough (this wrapper isn't used
 * when passthrough is on). State resets on configure/flush/reset/discontinuity so
 * a genuine seek is never mis-corrected. Position reporting is unaffected (the
 * sink derives it from AudioTrack frames, not from our rewrite).
 */
@OptIn(UnstableApi::class)
private class PtsSmoothingAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private var hasLast = false
    private var lastInUs = 0L
    private var lastOutUs = 0L
    private var lastNormalDeltaUs = -1L // -1 until we have seen a normal cadence

    override fun handleBuffer(
        buffer: java.nio.ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val outUs: Long = if (!hasLast) {
            presentationTimeUs // first buffer: anchor the output timeline to the real PTS
        } else {
            val deltaIn = presentationTimeUs - lastInUs
            val effectiveDelta =
                if (lastNormalDeltaUs >= 0 && deltaIn in TRIP_THRESHOLD_US..MAX_SWALLOW_US) {
                    lastNormalDeltaUs // spurious jump -> advance by the normal cadence
                } else {
                    deltaIn // normal increment, backward jump, or real large gap
                }
            lastOutUs + effectiveDelta
        }
        val ok = super.handleBuffer(buffer, outUs, encodedAccessUnitCount)
        // Advance state only when the buffer was accepted; on a partial/false return
        // the same buffer is re-submitted and must map to the same rewritten PTS.
        if (ok) {
            if (hasLast) {
                val deltaIn = presentationTimeUs - lastInUs
                if (deltaIn in 0 until TRIP_THRESHOLD_US) lastNormalDeltaUs = deltaIn
            }
            lastInUs = presentationTimeUs
            lastOutUs = outUs
            hasLast = true
        }
        return ok
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        resetSmoothing()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun flush() { resetSmoothing(); super.flush() }

    override fun reset() { resetSmoothing(); super.reset() }

    override fun handleDiscontinuity() { resetSmoothing(); super.handleDiscontinuity() }

    private fun resetSmoothing() {
        hasLast = false
        lastInUs = 0L
        lastOutUs = 0L
        lastNormalDeltaUs = -1L
    }

    companion object {
        // Below the sink's hardcoded 200ms re-sync gate, so we pre-empt it.
        private const val TRIP_THRESHOLD_US = 150_000L
        // Cap: only absorb the known ~1s muxer hiccups; larger = a real gap, pass through.
        private const val MAX_SWALLOW_US = 1_500_000L
    }
}

/**
 * MediaCodecVideoRenderer that downgrades every would-be codec reuse to a
 * full re-initialisation. See [aerioRenderersFactory]: flush-and-reuse on
 * some Codec2 decoders produces a decoder that runs but never renders
 * (black screen with audio after a live channel switch).
 */
@OptIn(UnstableApi::class)
private class AerioMediaCodecVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
    private val vetoCodecReuse: Boolean,
) : MediaCodecVideoRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    /**
     * Dolby Vision base-layer fallback.
     *
     * Media3 1.4.1 substitutes an HEVC/H.264/AV1 decoder for the Dolby Vision
     * profiles whose base layer is plainly backward compatible (DvheDtr,
     * DvheSt, DvavSe, Dvav110 - see MediaCodecUtil.getAlternativeCodecMimeType)
     * but deliberately NOT for dvhe.07 (DvheDtb), the dual-layer Blu-ray
     * profile. On a device without a Dolby Vision decoder that leaves the video
     * track with no decoder at all, so ExoPlayer drops it and plays the file as
     * audio only. Measured on a Z Fold 5 with a 73 Mbps remux (HEVC Main 10
     * 3840x2160 + TrueHD Atmos, DOVI profile 7, el_present_flag 1): audio
     * played, the surface never received a frame, and no video codec was ever
     * created.
     *
     * The base layer of these files is ordinary HEVC Main 10; the enhancement
     * layer and RPU ride in NAL units a plain HEVC decoder ignores, which is
     * exactly how mpv plays them on iOS. So when the stock path finds nothing,
     * retry as if the track were HEVC. Colour may be slightly off versus true
     * Dolby Vision output (the RPU is not applied), which is a better outcome
     * than no picture.
     *
     * Deliberately narrow: it only engages when the stock path found no
     * decoder AND the format is Dolby Vision, so no other content changes
     * behaviour.
     */
    private fun baseLayerFormatOrNull(format: Format): Format? {
        if (!MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType, ignoreCase = true)) return null
        return format.buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            // The codecs string still says dvhe.*; clearing it stops the
            // profile/level check from rejecting the HEVC decoder.
            .setCodecs(null)
            .build()
    }

    override fun supportsFormat(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
    ): Int {
        val support = super.supportsFormat(mediaCodecSelector, format)
        if (RendererCapabilities.getFormatSupport(support) == androidx.media3.common.C.FORMAT_HANDLED) {
            return support
        }
        val baseLayer = baseLayerFormatOrNull(format) ?: return support
        val fallback = super.supportsFormat(mediaCodecSelector, baseLayer)
        if (RendererCapabilities.getFormatSupport(fallback) == androidx.media3.common.C.FORMAT_HANDLED) {
            android.util.Log.i(
                "AerioPlayerDiag",
                "Dolby Vision without a DV decoder; decoding the HEVC base layer",
            )
            return fallback
        }
        return support
    }

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val infos = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        if (infos.isNotEmpty()) return infos
        val baseLayer = baseLayerFormatOrNull(format) ?: return infos
        return super.getDecoderInfos(mediaCodecSelector, baseLayer, requiresSecureDecoder)
    }

    override fun canReuseCodec(
        codecInfo: MediaCodecInfo,
        oldFormat: Format,
        newFormat: Format,
    ): DecoderReuseEvaluation {
        val evaluation = super.canReuseCodec(codecInfo, oldFormat, newFormat)
        if (!vetoCodecReuse) return evaluation
        // Stock already vetoed reuse (a real format/config change) -- respect it.
        if (evaluation.result == DecoderReuseEvaluation.REUSE_RESULT_NO) return evaluation
        // Only the decoders that actually showed the flush-and-reuse black-screen
        // (Exynos / Samsung C2 H.264, the 0.2.7 GitHub report) need the forced
        // re-init. Forcing it EVERYWHERE re-instantiates the codec against the
        // live persistent Surface every switch, which on MediaTek (c2.mtk.avc.
        // decoder) bumps the C2 surface generation mid-stream and DECODE-FAILS
        // (queueInputBuffer errno 14 after "discarded an unknown buffer"). So let
        // every other vendor take Media3's stock flush-and-reuse path, which never
        // re-attaches the surface (no generation bump, no race) and is also faster.
        val name = codecInfo.name.lowercase()
        val needsForcedReinit =
            name.startsWith("c2.exynos.") ||
            name.startsWith("omx.exynos.") ||
            name.startsWith("omx.samsung.")
        if (!needsForcedReinit) return evaluation
        return DecoderReuseEvaluation(
            evaluation.decoderName,
            oldFormat,
            newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_NO,
            DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND,
        )
    }
}
