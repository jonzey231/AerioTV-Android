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

    /** [bytes] of [tile]'s PCM starting at media time [audioPtsUs], which
     *  the tile's picture clock reaches at [playoutNanos] (System.nanoTime
     *  base). */
    fun onPcm(tile: Int, bytes: ByteArray, sampleRate: Int, channels: Int, pcmEncoding: Int, playoutNanos: Long, audioPtsUs: Long)

    /** [tile]'s picture clock at [nowNanos] (media time of the picture it
     *  shows, see [TilePictureClock]); null before its first frame. */
    fun pictureNowUs(tile: Int, nowNanos: Long): Long?
}

/**
 * A composite tile's picture clock (2026-10-08, Nothing Phone stutter: after
 * a stall the composite audio trailed the picture). Fed by the tile player's
 * VideoFrameMetadataListener with each frame's media time and the wall time
 * (System.nanoTime) it is released to the tile's SurfaceTexture, which is
 * also the SurfaceTexture timestamp the compositor sees when it draws it.
 * The audio tap stamps and paces PCM by this clock, so the composite's audio
 * follows the frames the compositor actually draws through stalls, restarts
 * and timestamp jumps. Extrapolation past the newest frame is capped, so the
 * clock freezes (and audio stops) while the tile's picture is stalled.
 * Pure; unit tested.
 */
class TilePictureClock {
    companion object {
        private const val RING = 32
        /** The clock runs on past the newest frame for at most this long. */
        const val MAX_EXTRAPOLATION_NANOS = 300_000_000L
        /** Media time moving more than this between frames is a jump. */
        const val JUMP_US = 1_000_000L
    }

    private val pts = LongArray(RING)
    private val release = LongArray(RING)
    private var count = 0
    private var head = 0

    @Synchronized fun reset() { count = 0; head = 0 }

    /** Records a frame; true when its media time jumped (a seek or a
     *  source timestamp discontinuity), which re-anchors the audio. */
    @Synchronized fun onFrame(ptsUs: Long, releaseNanos: Long): Boolean {
        val jumped = count > 0 && kotlin.math.abs(ptsUs - pts[(head + RING - 1) % RING]) > JUMP_US
        pts[head] = ptsUs
        release[head] = releaseNanos
        head = (head + 1) % RING
        if (count < RING) count++
        return jumped
    }

    @Synchronized fun nowPtsUs(nowNanos: Long): Long? {
        if (count == 0) return null
        val i = (head + RING - 1) % RING
        // A frame released ahead of now (the decoder schedules frames a
        // little early) puts the clock before it by that lead.
        val elapsed = (nowNanos - release[i]).coerceAtMost(MAX_EXTRAPOLATION_NANOS)
        return pts[i] + elapsed / 1000L
    }

    /** Media time of the frame released closest to [releaseNanos] (a
     *  SurfaceTexture timestamp); null when none is within 20 ms. */
    @Synchronized fun ptsForRelease(releaseNanos: Long): Long? {
        var best = -1
        var bestD = Long.MAX_VALUE
        for (k in 0 until count) {
            val d = kotlin.math.abs(release[k] - releaseNanos)
            if (d < bestD) { bestD = d; best = k }
        }
        return if (best >= 0 && bestD <= 20_000_000L) pts[best] else null
    }
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
 * When the tile has drawn a picture, PCM is paced and stamped by the tile's
 * [TilePictureClock] instead (the media time of the frames the compositor
 * draws), and PCM the picture has already passed is dropped, so a stall or
 * a restart cannot leave the audio behind the picture.
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
    /** Renderer stream offset: handleBuffer times minus this are media
     *  times, the domain of the video frame metadata. */
    private var streamOffsetUs = 0L

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

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        if (outputStreamOffsetUs != androidx.media3.common.C.TIME_UNSET) streamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: java.nio.ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (anchorPtsUs == AudioSink.CURRENT_POSITION_NOT_SET) reanchor(presentationTimeUs)
        val nowNanos = System.nanoTime()
        val mediaPtsUs = presentationTimeUs - streamOffsetUs
        val picture = tap.pictureNowUs(tile, nowNanos)
        // How far ahead of the clock this PCM plays: the picture clock once
        // the tile shows frames, the sink's own wall pacing before that.
        val aheadUs = if (picture != null) mediaPtsUs - picture else presentationTimeUs - pacedNowUs()
        if (aheadUs > CUSHION_US) return false
        if (tap.wants(tile) && buffer.hasRemaining() && aheadUs >= -STALE_US) {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            tap.onPcm(tile, bytes, sampleRate, channels, pcmEncoding, nowNanos + aheadUs * 1000L, mediaPtsUs)
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
        /** PCM this far behind the picture is stale (pictures already
         *  drawn); it is dropped rather than stretching the timeline. */
        const val STALE_US = 150_000L
    }
}
