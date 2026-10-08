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
 * picture timestamp jump) aligns the next chunk exactly to its stamp;
 * otherwise a chunk more than [REANCHOR_NANOS] later than expected is
 * preceded by silence and one more than that earlier has its stale head
 * trimmed. The timeline never jumps: the Cast receiver appends in MSE
 * sequence mode, which closes gaps by moving all later audio earlier. While no PCM arrives
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

    /** Output-stamp compensation from [AacDelayProbe], microseconds: how
     *  far after its stamp an input sample lands in the decoded output of a
     *  decoder that does not trim priming (no edit list is muxed, so the
     *  receiver does not trim). Output stamps move by it. Encoder thread. */
    private var compUs = 0L
    private var firstInUs = Long.MIN_VALUE
    private var firstOutLogged = false

    private fun loop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val probe = AacDelayProbe.measure(RATE, CHANNELS, BITRATE, FRAMES_PER_BLOCK)
        if (probe.delayUs != null && kotlin.math.abs(probe.delayUs) <= AacDelayProbe.MAX_COMP_US) {
            compUs = probe.delayUs
            log("[MV-CAST] av comp=${compUs / 1000}ms reason=aac-codec-delay ${probe.detail}")
        } else {
            log("[MV-CAST] av comp=0ms reason=aac-probe-unusable ${probe.detail}")
        }
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
        // The timeline never jumps: the receiver appends audio in MSE
        // sequence mode, which closes any gap by pulling every later
        // sample earlier (a 470 to 500 ms startup gap put the audio that
        // far ahead of the picture for the rest of the cast). The next
        // append aligns exactly instead: silence fills a lead, a lag is
        // trimmed from the chunk's head.
        alignExactly = true
    }

    /** Set by [reanchor]: the next chunk is aligned to its stamp with no
     *  tolerance. Encoder thread. */
    private var alignExactly = false

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
            val tolerance = if (alignExactly) framesToNanos(1) else REANCHOR_NANOS
            alignExactly = false
            if (drift < -tolerance) {
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
            if (drift > tolerance) {
                // Early: pad with silence up to the chunk's stamp so the
                // timeline stays sample-continuous (no gap for sequence
                // mode to collapse).
                val padFrames = (drift * RATE / 1_000_000_000L).toInt()
                if (padFrames > 0) {
                    gapFillFrames += padFrames
                    appendSamples(c, ShortArray(padFrames * CHANNELS))
                }
            }
        } else {
            nextNanos = startNanos
        }
        appendSamples(c, samples)
    }

    /** Frames of silence inserted to keep the timeline gap-free. */
    @Volatile var gapFillFrames = 0L
        private set

    private fun appendSamples(c: MediaCodec, samples: ShortArray) {
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

    private val queueInfo = MediaCodec.BufferInfo()

    private fun queueBlock(c: MediaCodec) {
        val us = clock.usForNanos(blockStartNanos)
        // Before the composite started (the first silence anchor): no slot.
        if (us < 0) return
        // A dropped block is a hole the receiver's sequence-mode append
        // would close, so wait for an input buffer, draining output in
        // between (a burst of silence padding fills every input slot).
        var idx = c.dequeueInputBuffer(5_000)
        var tries = 0
        while (idx < 0 && tries++ < 40) {
            drain(c, queueInfo)
            idx = c.dequeueInputBuffer(5_000)
        }
        if (idx < 0) {
            droppedChunks++
            return
        }
        val buf = c.getInputBuffer(idx) ?: return
        buf.clear()
        val bb = java.nio.ByteBuffer.allocate(block.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(block)
        buf.put(bb.array())
        if (firstInUs == Long.MIN_VALUE) firstInUs = us
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
                if (!firstOutLogged) {
                    firstOutLogged = true
                    log(
                        "[MV-CAST] composite first AAC output pts=${info.presentationTimeUs / 1000}ms " +
                            "first input pts=${firstInUs / 1000}ms stamp shift=${(info.presentationTimeUs - firstInUs) / 1000}ms " +
                            "muxed at ${(info.presentationTimeUs - compUs) / 1000}ms (comp ${compUs / 1000}ms)",
                    )
                }
                onFrame(Adts.wrap(raw, RATE, CHANNELS), clock.ticksForUs(info.presentationTimeUs - compUs))
            }
            c.releaseOutputBuffer(idx, false)
        }
    }
}

/**
 * Measures the platform AAC encoder's effective delay on this device
 * (2026-10-08, Nothing Phone: audio led the picture on the Chromecast while
 * the phone-side mux offset was about -35 ms). A tone starting at a known
 * input time is encoded with the same settings as the composite and decoded
 * with the platform AAC decoder, which, like the receiver (no edit list is
 * muxed), plays the priming instead of trimming it. The tone's onset in the
 * decoded output, measured against the output frame stamps, minus its input
 * time is the delay: positive means audio lands late relative to its stamp,
 * negative (the encoder already stamping its output earlier than its
 * content) means it lands early, which is an audio lead on the TV.
 */
object AacDelayProbe {
    class Result(val delayUs: Long?, val detail: String)

    /** A larger value is not a codec delay; it is not applied. */
    const val MAX_COMP_US = 200_000L
    private const val ONSET_FRAMES = 4_800 // 100 ms at 48 kHz
    private const val TOTAL_BLOCKS = 24
    private const val AMPLITUDE = 12_000
    private const val THRESHOLD = AMPLITUDE / 4
    private const val TIMEOUT_NANOS = 2_000_000_000L

    fun measure(rate: Int, channels: Int, bitrate: Int, framesPerBlock: Int): Result {
        var enc: MediaCodec? = null
        var dec: MediaCodec? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, framesPerBlock * channels * 2)
            }
            val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc = e
            e.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            e.start()
            val encName = e.name
            val info = MediaCodec.BufferInfo()
            val frames = ArrayList<Pair<ByteArray, Long>>()
            var csd: ByteArray? = null
            var fed = 0
            var encDone = false
            val deadline = System.nanoTime() + TIMEOUT_NANOS
            while (!encDone && System.nanoTime() < deadline) {
                if (fed <= TOTAL_BLOCKS) {
                    val idx = e.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val buf = e.getInputBuffer(idx)!!
                        buf.clear()
                        if (fed == TOTAL_BLOCKS) {
                            e.queueInputBuffer(idx, 0, 0, fed.toLong() * framesPerBlock * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val bb = java.nio.ByteBuffer.allocate(framesPerBlock * channels * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            for (f in 0 until framesPerBlock) {
                                val n = fed * framesPerBlock + f
                                val v = if (n < ONSET_FRAMES) 0 else
                                    (AMPLITUDE * Math.sin(2 * Math.PI * 1000.0 * (n - ONSET_FRAMES) / rate + Math.PI / 2)).toInt()
                                repeat(channels) { bb.putShort(v.toShort()) }
                            }
                            buf.put(bb.array())
                            e.queueInputBuffer(idx, 0, bb.capacity(), fed.toLong() * framesPerBlock * 1_000_000L / rate, 0)
                        }
                        fed++
                    }
                }
                while (true) {
                    val o = e.dequeueOutputBuffer(info, 5_000)
                    if (o < 0) break
                    val out = e.getOutputBuffer(o)
                    if (out != null && info.size > 0) {
                        val b = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(b)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) csd = b else frames += b to info.presentationTimeUs
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encDone = true
                    e.releaseOutputBuffer(o, false)
                }
            }
            if (csd == null || frames.isEmpty()) return Result(null, "encoder=$encName frames=${frames.size} csd=${csd != null}")
            val firstStamp = frames.first().second

            val dfmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
            }
            val d = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            dec = d
            d.configure(dfmt, null, null, 0)
            d.start()
            val decName = d.name
            var next = 0
            var decDone = false
            var onsetUs: Long? = null
            var outChannels = channels
            var outRate = rate
            while (!decDone && onsetUs == null && System.nanoTime() < deadline) {
                if (next <= frames.size) {
                    val idx = d.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val buf = d.getInputBuffer(idx)!!
                        buf.clear()
                        if (next == frames.size) {
                            d.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val (b, ts) = frames[next]
                            buf.put(b)
                            d.queueInputBuffer(idx, 0, b.size, ts, 0)
                        }
                        next++
                    }
                }
                while (onsetUs == null) {
                    val o = d.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outChannels = d.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outRate = d.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        continue
                    }
                    if (o < 0) break
                    val out = d.getOutputBuffer(o)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        val sb = out.slice().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                        val n = info.size / 2 / outChannels
                        for (f in 0 until n) {
                            if (kotlin.math.abs(sb.get(f * outChannels).toInt()) >= THRESHOLD) {
                                onsetUs = info.presentationTimeUs + f * 1_000_000L / outRate
                                break
                            }
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decDone = true
                    d.releaseOutputBuffer(o, false)
                }
            }
            val inputOnsetUs = ONSET_FRAMES * 1_000_000L / rate
            val detail = "encoder=$encName decoder=$decName first stamp=${firstStamp}us " +
                "tone in=${inputOnsetUs}us out=${onsetUs?.let { "${it}us" } ?: "none"}"
            return Result(onsetUs?.let { it - inputOnsetUs }, detail)
        } catch (t: Throwable) {
            return Result(null, "failed: $t")
        } finally {
            enc?.let { runCatching { it.stop() }; runCatching { it.release() } }
            dec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        }
    }
}
