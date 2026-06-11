package com.aeriotv.android.core.playback

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
): DefaultRenderersFactory {
    val factory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink? {
            if (audioPassthrough) {
                return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            }
            @Suppress("DEPRECATION")
            return DefaultAudioSink.Builder()
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
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
            if (!forceVideoCodecReinit) {
                super.buildVideoRenderers(
                    context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
                )
                return
            }
            // Mirror of the stock platform renderer construction; no video
            // extension renderers are bundled in this app, so skipping the
            // extension lookup super would do loses nothing.
            out.add(
                NoReuseMediaCodecVideoRenderer(
                    context,
                    codecAdapterFactory,
                    mediaCodecSelector,
                    allowedVideoJoiningTimeMs,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                ),
            )
        }
    }
    return factory
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
}

/**
 * MediaCodecVideoRenderer that downgrades every would-be codec reuse to a
 * full re-initialisation. See [aerioRenderersFactory]: flush-and-reuse on
 * some Codec2 decoders produces a decoder that runs but never renders
 * (black screen with audio after a live channel switch).
 */
@OptIn(UnstableApi::class)
private class NoReuseMediaCodecVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
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
    override fun canReuseCodec(
        codecInfo: MediaCodecInfo,
        oldFormat: Format,
        newFormat: Format,
    ): DecoderReuseEvaluation {
        val evaluation = super.canReuseCodec(codecInfo, oldFormat, newFormat)
        if (evaluation.result == DecoderReuseEvaluation.REUSE_RESULT_NO) return evaluation
        return DecoderReuseEvaluation(
            evaluation.decoderName,
            oldFormat,
            newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_NO,
            DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND,
        )
    }
}
