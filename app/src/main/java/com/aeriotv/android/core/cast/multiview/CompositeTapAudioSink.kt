package com.aeriotv.android.core.cast.multiview

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/** Receives the composite tiles' decoded PCM. Called on each tile's
 *  playback thread. */
interface CompositeAudioTap {
    /** Whether [tile]'s PCM is wanted now (the audio-focused tile only). */
    fun wants(tile: Int): Boolean

    /** [bytes] of [tile]'s PCM that the tile's clock plays at
     *  [playoutNanos] (System.nanoTime base). */
    fun onPcm(tile: Int, bytes: ByteArray, sampleRate: Int, channels: Int, pcmEncoding: Int, playoutNanos: Long)
}

/**
 * Audio sink of a composite tile (Multiview cast, 2026-10-06). Nothing is
 * ever played on the phone (no AudioTrack is created): buffers are accepted
 * at real-time pace exactly like the muted multiview tile sink, and the
 * audio-focused tile's PCM is handed to [tap] stamped with the wall time its
 * tile clock reaches it, which is also when that tile's video frame for the
 * same moment lands on its SurfaceTexture. That shared wall clock is what
 * keeps the composite's audio in sync with the focused tile's picture, and
 * focus switches are a flag flip (every tile keeps decoding its audio).
 *
 * Used with a clockless tile audio renderer (aerioRenderersFactory with a
 * tile gate), so the tile runs on ExoPlayer's standalone clock.
 */
@OptIn(UnstableApi::class)
class CompositeTapAudioSink(
    sink: AudioSink,
    private val tile: Int,
    private val tap: CompositeAudioTap,
) : ForwardingAudioSink(sink) {
    private var playing = false
    private var anchorPtsUs = AudioSink.CURRENT_POSITION_NOT_SET
    private var anchorWallUs = 0L
    private var endOfStream = false
    private var sampleRate = 0
    private var channels = 0
    private var pcmEncoding = 0

    private fun wallUs() = System.nanoTime() / 1000L
    private fun pacedNowUs(): Long =
        if (anchorPtsUs == AudioSink.CURRENT_POSITION_NOT_SET) AudioSink.CURRENT_POSITION_NOT_SET
        else if (playing) anchorPtsUs + (wallUs() - anchorWallUs) else anchorPtsUs
    private fun reanchor(ptsUs: Long) { anchorPtsUs = ptsUs; anchorWallUs = wallUs() }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        sampleRate = inputFormat.sampleRate
        channels = inputFormat.channelCount
        pcmEncoding = inputFormat.pcmEncoding
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: java.nio.ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (anchorPtsUs == AudioSink.CURRENT_POSITION_NOT_SET) reanchor(presentationTimeUs)
        val now = pacedNowUs()
        if (presentationTimeUs > now + CUSHION_US) return false
        if (tap.wants(tile) && buffer.hasRemaining()) {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val playoutNanos = System.nanoTime() + (presentationTimeUs - now) * 1000L
            tap.onPcm(tile, bytes, sampleRate, channels, pcmEncoding, playoutNanos)
        }
        buffer.position(buffer.limit())
        return true
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = AudioSink.CURRENT_POSITION_NOT_SET

    override fun play() {
        if (anchorPtsUs != AudioSink.CURRENT_POSITION_NOT_SET) reanchor(pacedNowUs())
        playing = true
    }

    override fun pause() {
        if (anchorPtsUs != AudioSink.CURRENT_POSITION_NOT_SET) reanchor(pacedNowUs())
        playing = false
    }

    override fun flush() {
        anchorPtsUs = AudioSink.CURRENT_POSITION_NOT_SET
        endOfStream = false
        super.flush()
    }

    override fun reset() {
        anchorPtsUs = AudioSink.CURRENT_POSITION_NOT_SET
        endOfStream = false
        super.reset()
    }

    override fun hasPendingData(): Boolean = !endOfStream
    override fun isEnded(): Boolean = endOfStream
    override fun playToEndOfStream() { endOfStream = true }

    private companion object {
        const val CUSHION_US = 250_000L
    }
}
