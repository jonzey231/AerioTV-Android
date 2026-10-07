package com.aeriotv.android.core.cast.multiview

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The composite's media clock: System.nanoTime since the composite
 *  started, offset by [BASE_TICKS] so early audio never goes negative. */
class CompositeClock(val t0Nanos: Long) {
    companion object {
        const val BASE_TICKS = 10L * 90_000L
    }
    fun ticksForNanos(nanos: Long): Long = BASE_TICKS + (nanos - t0Nanos) * 9 / 100_000
    fun ticksForUs(us: Long): Long = BASE_TICKS + us * 9 / 100
    fun usForNanos(nanos: Long): Long = (nanos - t0Nanos) / 1000
}

/**
 * AAC-LC stereo 48 kHz encoder for the composite's audio (MediaCodec, the
 * platform encoder). Input is the focused tile's PCM, already normalized
 * and stamped with its playout wall time; output is ADTS frames on the
 * [clock] timeline, the same timeline the video frames use.
 *
 * The timeline is sample-continuous. A chunk that lands more than
 * [REANCHOR_NANOS] away from where the timeline expects it (a focus switch,
 * a tile stall) re-anchors: later chunks jump forward (a short gap), earlier
 * ones are dropped until the tile's clock catches up. While no PCM arrives
 * (focused tile still starting, stalled, or silent) silence is generated so
 * the audio rendition never starves the receiver.
 */
class CompositeAudioEncoder(
    private val clock: CompositeClock,
    private val onFrame: (adts: ByteArray, ticks: Long) -> Unit,
    private val log: (String) -> Unit,
) {
    companion object {
        private const val RATE = CompositePcmNormalizer.OUT_RATE
        private const val CHANNELS = CompositePcmNormalizer.OUT_CHANNELS
        private const val FRAMES_PER_BLOCK = 1024
        private const val BITRATE = 128_000
        private const val REANCHOR_NANOS = 200_000_000L
        /** Silence fills the timeline up to this far behind now. */
        private const val SILENCE_TRAIL_NANOS = 100_000_000L
        /** No PCM for this long counts as no PCM. */
        private const val IDLE_NANOS = 300_000_000L
    }

    private class Chunk(val samples: ShortArray, val startNanos: Long, val reanchorTile: Int)

    private val queue = LinkedBlockingQueue<Chunk>()
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var resetTimeline = false
    private var thread: Thread? = null
    private var codec: MediaCodec? = null

    private val block = ShortArray(FRAMES_PER_BLOCK * CHANNELS)
    private var blockFill = 0
    private var blockStartNanos = -1L
    private var nextNanos = -1L
    private var lastPcmAtNanos = 0L
    @Volatile var droppedChunks = 0
        private set

    fun start(): Boolean {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAMES_PER_BLOCK * CHANNELS * 2)
        }
        val c = runCatching {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
        }.getOrElse {
            log("[MV-CAST] AAC encoder unavailable: $it")
            return false
        }
        codec = c
        running = true
        thread = Thread({ loop(c) }, "MV-CAST-aac").also { it.start() }
        return true
    }

    /** Focused tile PCM, interleaved stereo at 48 kHz. Any thread.
     *  [reanchorTile] >= 0: that tile just resumed after a stall; the
     *  timeline snaps to this chunk's playout stamp whatever the drift, so
     *  lip sync does not carry the stall. */
    fun submit(samples: ShortArray, playoutNanos: Long, reanchorTile: Int = -1) {
        if (running && !paused && samples.isNotEmpty()) queue.offer(Chunk(samples, playoutNanos, reanchorTile))
    }

    /** Background pause: no PCM, no silence; the timeline restarts from the
     *  first chunk (or silence) after the resume. Any thread. */
    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        if (value) queue.clear() else resetTimeline = true
        log("[MV-CAST] composite audio ${if (value) "paused" else "resumed"}")
    }

    fun release() {
        running = false
        thread?.interrupt()
        runCatching { thread?.join(1_000) }
        thread = null
        codec?.let { c -> runCatching { c.stop() }; runCatching { c.release() } }
        codec = null
    }

    private fun loop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val chunk = try { queue.poll(10, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                val now = System.nanoTime()
                if (resetTimeline) {
                    resetTimeline = false
                    blockFill = 0
                    nextNanos = -1L
                    lastPcmAtNanos = now
                }
                if (paused) {
                    drain(c, info)
                    continue
                }
                if (chunk != null) {
                    lastPcmAtNanos = now
                    if (chunk.reanchorTile >= 0) reanchor(chunk)
                    append(c, chunk.samples, chunk.startNanos)
                } else if (now - lastPcmAtNanos > IDLE_NANOS) {
                    fillSilence(c, now - SILENCE_TRAIL_NANOS)
                }
                drain(c, info)
            }
        } catch (t: Throwable) {
            if (running) log("[MV-CAST] AAC encoder stopped: $t")
        }
    }

    /** Snap the timeline to [chunk]'s playout stamp (tile resumed after a
     *  stall); the partial block before it is dropped. */
    private fun reanchor(chunk: Chunk) {
        val deltaMs = if (nextNanos >= 0) (chunk.startNanos - nextNanos) / 1_000_000 else 0L
        log("[MV-CAST] composite audio re-anchor tile=${chunk.reanchorTile} delta=${deltaMs}ms")
        blockFill = 0
        nextNanos = chunk.startNanos
    }

    private fun fillSilence(c: MediaCodec, untilNanos: Long) {
        if (nextNanos < 0) {
            nextNanos = untilNanos
            return
        }
        val zeros = ShortArray(FRAMES_PER_BLOCK * CHANNELS)
        while (nextNanos + framesToNanos(FRAMES_PER_BLOCK) <= untilNanos) {
            append(c, zeros, nextNanos)
        }
    }

    private fun framesToNanos(frames: Int): Long = frames * 1_000_000_000L / RATE

    private fun append(c: MediaCodec, samples: ShortArray, startNanos: Long) {
        if (nextNanos >= 0) {
            val drift = startNanos - nextNanos
            if (drift < -REANCHOR_NANOS) {
                droppedChunks++
                return
            }
            if (drift > REANCHOR_NANOS) {
                blockFill = 0
                nextNanos = startNanos
            }
        } else {
            nextNanos = startNanos
        }
        var i = 0
        while (i < samples.size) {
            if (blockFill == 0) blockStartNanos = nextNanos
            val n = minOf(samples.size - i, block.size - blockFill)
            System.arraycopy(samples, i, block, blockFill, n)
            blockFill += n
            i += n
            nextNanos += framesToNanos(n / CHANNELS)
            if (blockFill == block.size) {
                queueBlock(c)
                blockFill = 0
            }
        }
    }

    private fun queueBlock(c: MediaCodec) {
        val us = clock.usForNanos(blockStartNanos)
        // Before the composite started (the first silence anchor): no slot.
        if (us < 0) return
        val idx = c.dequeueInputBuffer(20_000)
        if (idx < 0) {
            droppedChunks++
            return
        }
        val buf = c.getInputBuffer(idx) ?: return
        buf.clear()
        val bb = java.nio.ByteBuffer.allocate(block.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(block)
        buf.put(bb.array())
        c.queueInputBuffer(idx, 0, block.size * 2, us, 0)
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx < 0) return
            val out = c.getOutputBuffer(idx)
            if (out != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val raw = ByteArray(info.size)
                out.position(info.offset)
                out.get(raw)
                onFrame(Adts.wrap(raw, RATE, CHANNELS), clock.ticksForUs(info.presentationTimeUs))
            }
            c.releaseOutputBuffer(idx, false)
        }
    }
}
