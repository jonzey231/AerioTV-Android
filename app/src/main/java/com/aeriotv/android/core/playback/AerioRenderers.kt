package com.aeriotv.android.core.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Shared renderers factory for every player in the app (live holder, VOD,
 * multiview tiles). Identical to a stock [DefaultRenderersFactory] with
 * decoder fallback, except for the audio sink:
 *
 * When [audioPassthrough] is false (the default preference) the sink is
 * built with PCM-only capabilities, so Dolby bitstreams (AC3/EAC3) are
 * decoded in-app by MediaCodec and the display receives plain PCM on the
 * standard latency-compensated path. Many TVs decode a passthrough
 * bitstream with latency Android reports as zero, which the player cannot
 * compensate; the visible symptom is lip-sync drift on live TV.
 *
 * When [audioPassthrough] is true the stock sink is used and the bitstream
 * rides HDMI untouched for the TV or receiver to decode (5.1 preserved).
 *
 * API gotcha: the Context overload of [DefaultAudioSink.Builder] IGNORES
 * setAudioCapabilities (it installs its own AudioCapabilitiesReceiver), so
 * the deprecated no-context Builder is the one that actually honors a
 * forced capability set.
 */
@OptIn(UnstableApi::class)
fun aerioRenderersFactory(context: Context, audioPassthrough: Boolean): DefaultRenderersFactory {
    val factory = if (audioPassthrough) {
        DefaultRenderersFactory(context)
    } else {
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                @Suppress("DEPRECATION")
                return DefaultAudioSink.Builder()
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
    }
    return factory
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
}
