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
 * The timeline is sample-continuous. Chunk stamps come from the focused
 * tile's picture clock (the frames the compositor draws), so the timeline
 * follows the picture: a [Reanchor] (focus switch, stall resume, restart,
 * picture timestamp jump) snaps it to the chunk's stamp; otherwise a chunk
 * more than [REANCHOR_NANOS] later than expected jumps forward (a short
 * gap) and one more than that earlier has its stale head trimmed, so the
 * audio never trails the picture. While no PCM arrives
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
        private const val REANCHOR_NANOS = 100_000_000L
        /** Silence fills the timeline up to this far behind now. */
        private const val SILENCE_TRAIL_NANOS = 100_000_000L
        /** No PCM for this long counts as no PCM. */
        private const val IDLE_NANOS = 300_000_000L
    }

    /** Why the timeline snaps to a chunk, with the focused tile's drawn
     *  picture time and the chunk's audio time (media ms) for the log. */
    class Reanchor(val tile: Int, val reason: String, val videoMs: Long, val audioMs: Long)

    private class Chunk(val samples: ShortArray, val startNanos: Long, val reanchor: Reanchor?)

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
    /** Late PCM frames trimmed to keep the audio on the picture. */
    @Volatile var trimmedSamples = 0L
        private set
    /** The encoder's AudioSpecificConfig (csd-0) as hex, once it is known. */
    @Volatile var csd0Hex: String = ""
        private set
    /** Blocks of generated silence queued (no PCM from the focused tile). */
    @Volatile var silenceBlocks = 0L
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

    /** Focused tile PCM, interleaved stereo at 48 kHz, stamped on the
     *  tile's picture clock. Any thread. [reanchor] non-null: the timeline
     *  snaps to this chunk's stamp whatever the drift. */
    fun submit(samples: ShortArray, playoutNanos: Long, reanchor: Reanchor? = null) {
        if (running && !paused && samples.isNotEmpty()) queue.offer(Chunk(samples, playoutNanos, reanchor))
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
                    chunk.reanchor?.let { reanchor(chunk, it) }
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

    /** Snap the timeline to [chunk]'s picture-clock stamp; the partial
     *  block before it is dropped. delta = how far the timeline moved. */
    private fun reanchor(chunk: Chunk, r: Reanchor) {
        val deltaMs = if (nextNanos >= 0) (chunk.startNanos - nextNanos) / 1_000_000 else 0L
        log(
            "[MV-CAST] composite audio re-anchor tile=${r.tile} reason=${r.reason} " +
                "video=${r.videoMs}ms audio=${r.audioMs}ms delta=${deltaMs}ms",
        )
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
            silenceBlocks++
        }
    }

    private fun framesToNanos(frames: Int): Long = frames * 1_000_000_000L / RATE

    private fun append(c: MediaCodec, samples: ShortArray, startNanos: Long) {
        if (nextNanos >= 0) {
            val drift = startNanos - nextNanos
            if (drift < -REANCHOR_NANOS) {
                // Late: the head of the chunk belongs to pictures already
                // drawn. Trim it so the rest lands where it belongs.
                val skipSamples = ((-drift) * RATE / 1_000_000_000L).toInt() * CHANNELS
                if (skipSamples >= samples.size) {
                    droppedChunks++
                    return
                }
                trimmedSamples += skipSamples / CHANNELS
                return append(c, samples.copyOfRange(skipSamples, samples.size), nextNanos)
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
            if (out != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                val cfg = ByteArray(info.size)
                out.position(info.offset)
                out.get(cfg)
                csd0Hex = cfg.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                log("[MV-CAST] AAC encoder csd-0=$csd0Hex (${cfg.size} B)")
            }
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
