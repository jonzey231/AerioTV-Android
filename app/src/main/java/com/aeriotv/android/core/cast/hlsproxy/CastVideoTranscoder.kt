package com.aeriotv.android.core.cast.hlsproxy

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale

// Cast HLS proxy: the MediaCodec half of the on-phone video transcode (iOS
// CastVideoTranscoder.swift parity, 2026-09-26). The plan, codec strings and
// config records are in CastVideoPlan.kt.

/** The on-phone video transcode failed mid-ingest. Thrown from
 *  [TsToFmp4Remuxer.feed] so the session reconnects with a fresh remuxer,
 *  which it builds with the video plan disabled (H.264 passthrough) for the
 *  rest of the session. */
class CastVideoTranscodeException(val reason: String) : Exception("video transcode failed: $reason")

/** What the transcoder hands back to the remuxer, always through the
 *  delivery hop, in this order: one format, then the samples; or one
 *  failure. */
class CastVideoTranscodeSink(
    /** [config] is the avcC or hvcC box body for the init segment. */
    val onFormat: (codec: CastVideoOutputSpec.Codec, config: ByteArray, width: Int, height: Int) -> Unit,
    /** One access unit, 4-byte NAL lengths, presentation order. DTS equals
     *  PTS: the encoder never reorders. */
    val onSample: (data: ByteArray, pts: Long, keyframe: Boolean) -> Unit,
    /** Any MediaCodec failure: the session falls back to passthrough. */
    val onFailure: (reason: String) -> Unit,
)

/** Abstraction over the MediaCodec transcoder so the remuxer's pure logic
 *  stays testable off-device. */
interface CastVideoTranscoding {
    /** One source access unit: 4-byte-length H.264 NAL units with the
     *  parameter sets, AUDs and filler already removed, 90 kHz unwrapped
     *  timestamps, and the SPS / PPS in force for it. */
    fun feed(sample: ByteArray, pts: Long, dts: Long, keyframe: Boolean, sps: ByteArray, pps: ByteArray)
    fun release()
}

/** Hop into the remuxer's single-caller context. On Android that is the
 *  session's per-remuxer lock (the ingest thread blocks in a socket read
 *  between Dispatcharr bursts, so posting to it would hold output for up
 *  to 9 s). */
typealias CastIngestDelivery = (block: () -> Unit) -> Unit

typealias CastVideoTranscoderFactory = (
    source: CastH264StreamInfo,
    spec: CastVideoOutputSpec,
    targetKeyTicks: Long,
    sink: CastVideoTranscodeSink,
    deliver: CastIngestDelivery,
) -> CastVideoTranscoding

/**
 * Decode (MediaCodec, hardware H.264) -> scale (GLES on the decoder's
 * SurfaceTexture, drawn into the encoder's input Surface) -> encode
 * (MediaCodec, hardware HEVC or H.264, realtime priority, no B-frames) on
 * a private HandlerThread; never on the ingest thread.
 *
 * PTS flow: every decoded frame keeps its source PTS (the decoder hands
 * back the input presentationTimeUs, and MediaCodec decoders already emit
 * in presentation order), the encoder is given that PTS through
 * eglPresentationTimeANDROID and hands it back untouched, and with
 * B-frames off the output DTS equals its PTS, so the video rides the
 * source clock and audio sync is exactly what the passthrough had. A
 * decoded frame at or below the last released PTS is dropped so the
 * output is strictly monotonic.
 *
 * Key frames: the first encoded frame and then the first frame at or after
 * each [targetKeyTicks] of media are forced IDRs
 * (PARAMETER_KEY_REQUEST_SYNC_FRAME right before that frame is drawn),
 * which is exactly the remuxer's cut rule (first keyframe at or after the
 * segment target), so every segment cut lands on an encoder IDR and
 * segments stay at ~3 s.
 */
class CastVideoTranscoder(
    context: Context,
    private val source: CastH264StreamInfo,
    private val spec: CastVideoOutputSpec,
    private val targetKeyTicks: Long,
    private val sink: CastVideoTranscodeSink,
    private val deliver: CastIngestDelivery,
    private val log: (String) -> Unit,
) : CastVideoTranscoding {

    companion object {
        private const val TICKS = TsToFmp4Remuxer.TICKS_PER_SECOND

        /** Encoder falling behind: the input backlog bound. The spec is
         *  "drop if more than 1 s behind", but the Dispatcharr proxy
         *  delivers its bytes in 8 to 9.5 s bursts (measured 2026-09-14),
         *  so a whole burst sits in this queue for a moment by design. The
         *  bound is therefore 1 s of lag beyond the largest measured burst. */
        val MAX_BACKLOG_TICKS = 11 * TICKS

        /** Frames drawn into the encoder and not yet back out of it before
         *  the work thread waits. */
        const val MAX_IN_FLIGHT = 8

        /** Consecutive decode or encode errors that end the transcode. */
        const val MAX_CONSECUTIVE_ERRORS = 90

        /** Source bitrate measurement window. */
        val BITRATE_WINDOW_TICKS = 4 * TICKS

        /** Stats line cadence. */
        const val STATS_INTERVAL_MS = 10_000L

        private const val STALL_MS = 2_000L
        private const val FRAME_WAIT_MS = 500L
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val POLL_MS = 10L
        private const val POLL_IDLE_MS = 1_000L

        private fun ticksToUs(ticks: Long): Long = ticks * 100 / 9
        private fun usToTicks(us: Long): Long = Math.round(us * 9 / 100.0)
    }

    private class InputFrame(
        val data: ByteArray,
        val pts: Long,
        val dts: Long,
        val keyframe: Boolean,
        val sps: ByteArray,
        val pps: ByteArray,
    )

    // ---- state guarded by `lock` ----
    private val lock = Any()
    private val pending = ArrayDeque<InputFrame>()
    private var drainScheduled = false
    private var released = false
    private var failed = false
    private var encodedFrames = 0
    private var encodedSinceStats = 0
    private var droppedBacklog = 0
    private var droppedEncoder = 0
    private var droppedLate = 0
    private var decodeErrors = 0
    /** Stats snapshots of work-thread state. */
    private var inFlightSnapshot = 0
    private var decoderHeldSnapshot = 0

    private val isStopped: Boolean get() = synchronized(lock) { released || failed }

    // ---- threads ----
    private val workThread = HandlerThread("CastVideoTranscode", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val work = Handler(workThread.looper)
    /** SurfaceTexture frame callbacks and the stats timer: must not be the
     *  work thread, which blocks waiting for the frame. */
    private val aux = HandlerThread("CastVideoFrames").apply { start() }
    private val auxHandler = Handler(aux.looper)

    // ---- work-thread state ----
    private var decoder: MediaCodec? = null
    private var decoderName = ""
    private var decoderSps = ByteArray(0)
    private var decoderPps = ByteArray(0)
    private var encoder: MediaCodec? = null
    private var encoderName = ""
    private var encoderSurface: Surface? = null
    private var gl: GlScaler? = null
    private var waitingForKey = true
    private var lastReleasedPts = -1L
    private var anchorPts = -1L
    private var lastKeyPts = -1L
    private var presentedIndex = 0
    private var consecutiveErrors = 0
    private var sessionResets = 0
    private var targetBitrate = spec.bitrateCap
    private var bitrateBytes = 0L
    private var bitrateStartDts = -1L
    private var bitrateMeasured = false
    private var formatDelivered = false
    private var pendingConfig: ByteArray? = null
    /** Frames queued into the decoder and not yet out of it. */
    private var decoderHeld = 0
    /** (presentationTimeUs, source ticks) of frames drawn into the
     *  encoder and not yet out of it, in order. */
    private val rendered = ArrayDeque<Pair<Long, Long>>()
    private var lateLogged = 0
    private var pollScheduled = false
    private var lastProgressMs = 0L
    private val bufferInfo = MediaCodec.BufferInfo()

    private val frameSync = Object()
    private var frameAvailable = false

    // ---- stats and thermal ----
    private var statsStartedAt = SystemClock.elapsedRealtime()
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var lastThermal = currentThermal()
    private val thermalListener: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            val l = PowerManager.OnThermalStatusChangedListener { noteThermalChange(it) }
            runCatching { powerManager?.addThermalStatusListener({ it.run() }, l) }
            l
        } else {
            null
        }
    private val statsRunnable = object : Runnable {
        override fun run() {
            logStats()
            if (!isStopped) auxHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    init {
        auxHandler.postDelayed(statsRunnable, STATS_INTERVAL_MS)
    }

    // ---- ingest side ----

    override fun feed(sample: ByteArray, pts: Long, dts: Long, keyframe: Boolean, sps: ByteArray, pps: ByteArray) {
        var dropped = 0
        var backlogSeconds = 0.0
        var schedule = false
        synchronized(lock) {
            if (released || failed) return
            pending.addLast(InputFrame(sample, pts, dts, keyframe, sps, pps))
            // Drop whole GOPs from the front: a partial GOP cannot be
            // decoded, so the queue always restarts on a source IDR.
            while (pending.isNotEmpty() && pending.last().dts - pending.first().dts > MAX_BACKLOG_TICKS) {
                pending.removeFirst(); dropped++
                while (pending.isNotEmpty() && !pending.first().keyframe) { pending.removeFirst(); dropped++ }
            }
            if (dropped > 0) {
                droppedBacklog += dropped
                if (pending.isNotEmpty()) {
                    backlogSeconds = (pending.last().dts - pending.first().dts).toDouble() / TICKS
                }
            }
            if (!drainScheduled) { drainScheduled = true; schedule = true }
        }
        if (dropped > 0) {
            log(
                String.format(
                    Locale.US, "video transcode: encoder behind, dropped %d source frames (backlog now %.1f s)",
                    dropped, backlogSeconds,
                ),
            )
        }
        if (schedule) work.post { drain() }
    }

    override fun release() {
        synchronized(lock) {
            released = true
            pending.clear()
        }
        auxHandler.removeCallbacks(statsRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let { l ->
                runCatching { powerManager?.removeThermalStatusListener(l) }
            }
        }
        // Unblock a work thread waiting on a frame.
        synchronized(frameSync) { frameSync.notifyAll() }
        work.post {
            teardownSessions()
            workThread.quitSafely()
            aux.quitSafely()
        }
    }

    // ---- work thread ----

    private fun drain() {
        while (true) {
            val frame = synchronized(lock) {
                if (released || failed || pending.isEmpty()) {
                    drainScheduled = false
                    null
                } else {
                    pending.removeFirst()
                }
            } ?: break
            process(frame)
        }
        schedulePoll()
    }

    /** Between Dispatcharr bursts nothing feeds the codecs, so frames they
     *  still hold are pulled out by a short poll until they stop coming. */
    private fun schedulePoll() {
        if (pollScheduled || isStopped) return
        if (decoderHeld == 0 && rendered.isEmpty()) return
        pollScheduled = true
        lastProgressMs = SystemClock.elapsedRealtime()
        work.postDelayed(pollRunnable, POLL_MS)
    }

    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            pollScheduled = false
            if (isStopped) return
            val before = decoderHeld + rendered.size
            drainDecoder()
            drainEncoder(0)
            val after = decoderHeld + rendered.size
            val now = SystemClock.elapsedRealtime()
            if (after != before) lastProgressMs = now
            val idle = synchronized(lock) { pending.isEmpty() && !drainScheduled }
            if (idle && after > 0 && now - lastProgressMs < POLL_IDLE_MS) {
                pollScheduled = true
                work.postDelayed(this, POLL_MS)
            }
        }
    }

    private fun process(frame: InputFrame) {
        measureBitrate(frame)
        if (waitingForKey && !frame.keyframe) return
        if (encoder == null) {
            if (!frame.keyframe) return
            if (!makePipeline(frame.sps, frame.pps)) return
        } else if (frame.keyframe && (decoder == null || !frame.sps.contentEquals(decoderSps) ||
                !frame.pps.contentEquals(decoderPps))
        ) {
            if (!makeDecoder(frame.sps, frame.pps)) return
        }
        val decoder = decoder ?: return
        if (waitingForKey) {
            waitingForKey = false
            if (anchorPts < 0) anchorPts = frame.pts
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + STALL_MS
            var index: Int
            while (true) {
                index = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (index >= 0) break
                drainDecoder()
                drainEncoder(0)
                if (isStopped || this.decoder !== decoder) return
                if (SystemClock.elapsedRealtime() > deadline) {
                    fail("decoder stalled with no free input buffer")
                    return
                }
            }
            val buf = decoder.getInputBuffer(index) ?: run {
                noteError("decoder gave no input buffer")
                return
            }
            buf.clear()
            if (frame.data.size > buf.capacity()) {
                decoder.queueInputBuffer(index, 0, 0, ticksToUs(frame.pts), 0)
                noteError("source frame larger than the decoder input buffer")
                waitingForKey = true
                return
            }
            // Length prefixes back to start codes (same size, in place).
            val annexB = frame.data.copyOf()
            var p = 0
            while (p + 4 <= annexB.size) {
                val n = ((annexB[p].toInt() and 0xFF) shl 24) or ((annexB[p + 1].toInt() and 0xFF) shl 16) or
                    ((annexB[p + 2].toInt() and 0xFF) shl 8) or (annexB[p + 3].toInt() and 0xFF)
                annexB[p] = 0; annexB[p + 1] = 0; annexB[p + 2] = 0; annexB[p + 3] = 1
                p += 4 + n
            }
            buf.put(annexB)
            decoder.queueInputBuffer(
                index, 0, annexB.size, ticksToUs(frame.pts),
                if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
            )
            decoderHeld++
        } catch (e: MediaCodec.CodecException) {
            synchronized(lock) { decodeErrors++ }
            onDecoderException(e)
            return
        } catch (e: IllegalStateException) {
            synchronized(lock) { decodeErrors++ }
            onDecoderException(e)
            return
        }
        drainDecoder()
        drainEncoder(0)
    }

    private fun onDecoderException(e: Exception) {
        val codec = e as? MediaCodec.CodecException
        if (codec == null || (!codec.isRecoverable && !codec.isTransient)) {
            // The decoder is gone (media server reset, resource reclaim):
            // rebuild it on the next source IDR.
            resetDecoder("decoder session invalidated (${describe(e)})")
            return
        }
        noteError("decode failed (${describe(e)})")
        waitingForKey = true
    }

    /** Pull every decoded frame the decoder has ready; render or drop. */
    private fun drainDecoder() {
        val decoder = decoder ?: return
        try {
            while (!isStopped) {
                val idx = decoder.dequeueOutputBuffer(bufferInfo, 0)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (idx < 0) continue // format / buffers changed
                decoderHeld = maxOf(0, decoderHeld - 1)
                consecutiveErrors = 0
                val ticks = usToTicks(bufferInfo.presentationTimeUs)
                val render = admitDecoded(ticks)
                decoder.releaseOutputBuffer(idx, render)
                if (render) renderToEncoder(ticks)
                if (this.decoder !== decoder) return
            }
        } catch (e: MediaCodec.CodecException) {
            onDecoderException(e)
        } catch (e: IllegalStateException) {
            onDecoderException(e)
        }
        synchronized(lock) { decoderHeldSnapshot = decoderHeld }
    }

    /** Presentation-order gate and frame step: true when the frame goes to
     *  the encoder. */
    private fun admitDecoded(ticks: Long): Boolean {
        // Leading pictures of the first GOP present before the IDR the
        // remuxer anchored its timeline on.
        if (anchorPts >= 0 && ticks < anchorPts) return false
        if (lastReleasedPts >= 0 && ticks <= lastReleasedPts) {
            synchronized(lock) { droppedLate++ }
            lateLogged++
            if (lateLogged == 1 || lateLogged % 30 == 0) {
                log("video transcode: decoded frame arrived out of order ($lateLogged so far)")
            }
            return false
        }
        lastReleasedPts = ticks
        presentedIndex++
        if (spec.frameStep > 1 && (presentedIndex - 1) % spec.frameStep != 0) return false
        // In-flight cap: wait here (the work thread), never on the ingest.
        if (rendered.size >= MAX_IN_FLIGHT) {
            val deadline = SystemClock.elapsedRealtime() + STALL_MS
            while (rendered.size >= MAX_IN_FLIGHT && !isStopped) {
                drainEncoder(DEQUEUE_TIMEOUT_US)
                if (encoder == null) return false
                if (SystemClock.elapsedRealtime() > deadline) {
                    fail("encoder stalled with $MAX_IN_FLIGHT frames in flight")
                    return false
                }
            }
        }
        return !isStopped && encoder != null
    }

    /** The decoder just released [ticks] to the SurfaceTexture: wait for it,
     *  draw it scaled into the encoder's input surface, stamp and submit. */
    private fun renderToEncoder(ticks: Long) {
        val gl = gl ?: return
        val encoder = encoder ?: return
        synchronized(frameSync) {
            val deadline = SystemClock.elapsedRealtime() + FRAME_WAIT_MS
            while (!frameAvailable && !isStopped) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                frameSync.wait(left)
            }
            if (!frameAvailable) {
                if (!isStopped) noteError("decoded frame never reached the scaler")
                return
            }
            frameAvailable = false
        }
        try {
            gl.surfaceTexture.updateTexImage()
            val forceKey = lastKeyPts < 0 || ticks - lastKeyPts >= targetKeyTicks
            if (forceKey) {
                lastKeyPts = ticks
                encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            }
            gl.draw()
            val us = ticksToUs(ticks)
            gl.swap(us * 1000)
            rendered.addLast(us to ticks)
            synchronized(lock) { inFlightSnapshot = rendered.size }
        } catch (e: RuntimeException) {
            val codec = e as? MediaCodec.CodecException
            if (codec == null || (!codec.isRecoverable && !codec.isTransient)) {
                resetSessions("encoder session invalidated (${describe(e)})")
            } else {
                noteError("encode failed (${describe(e)})")
            }
            return
        }
        drainEncoder(0)
    }

    /** Pull every encoded access unit ready; deliver format then samples. */
    private fun drainEncoder(timeoutUs: Long) {
        val encoder = encoder ?: return
        try {
            var timeout = timeoutUs
            while (!isStopped) {
                val idx = encoder.dequeueOutputBuffer(bufferInfo, timeout)
                timeout = 0
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = encoder.outputFormat
                    val csd = listOfNotNull(f.getByteBuffer("csd-0"), f.getByteBuffer("csd-1"))
                    if (csd.isNotEmpty()) pendingConfig = csd.fold(ByteArray(0)) { acc, b -> acc + bytesOf(b) }
                    continue
                }
                if (idx < 0) continue
                val buffer = encoder.getOutputBuffer(idx)
                val info = bufferInfo
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        if (pendingConfig == null) pendingConfig = bytesOf(buffer)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    continue
                }
                if (buffer == null || info.size <= 0) {
                    encoder.releaseOutputBuffer(idx, false)
                    continue
                }
                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                val bytes = bytesOf(buffer)
                val us = info.presentationTimeUs
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                encoder.releaseOutputBuffer(idx, false)
                onEncoded(bytes, us, key)
            }
        } catch (e: MediaCodec.CodecException) {
            if (!e.isRecoverable && !e.isTransient) {
                resetSessions("encoder session invalidated (${describe(e)})")
            } else {
                log("video transcode: encoder output error ${describe(e)}")
            }
        } catch (e: IllegalStateException) {
            resetSessions("encoder session invalidated (${describe(e)})")
        }
    }

    private fun onEncoded(bytes: ByteArray, us: Long, keyframe: Boolean) {
        // Match the output to the frame drawn with that timestamp; anything
        // older that never came out was dropped inside the encoder.
        var ticks = usToTicks(us)
        var skipped = 0
        while (rendered.isNotEmpty() && rendered.first().first <= us) {
            val (rUs, rTicks) = rendered.removeFirst()
            if (rUs == us) ticks = rTicks else skipped++
        }
        synchronized(lock) {
            droppedEncoder += skipped
            inFlightSnapshot = rendered.size
        }
        if (!formatDelivered) {
            val config = pendingConfig?.let { CastVideoCodecConfig.configRecord(spec.codec, it) }
            if (config == null) {
                fail("encoder produced no ${if (spec.codec == CastVideoOutputSpec.Codec.HEVC) "hvcC" else "avcC"} parameter sets")
                return
            }
            formatDelivered = true
            val codec = spec.codec
            val w = spec.width
            val h = spec.height
            deliver { sink.onFormat(codec, config, w, h) }
        }
        val sample = CastVideoCodecConfig.annexBToLengthPrefixed(bytes, spec.codec)
        if (sample.isEmpty()) return
        synchronized(lock) {
            encodedFrames++
            encodedSinceStats++
        }
        consecutiveErrors = 0
        deliver { sink.onSample(sample, ticks, keyframe) }
    }

    // ---- sessions ----

    /** Encoder first (the decoder renders into the scaler, the scaler into
     *  the encoder's input surface), then the decoder. */
    private fun makePipeline(sps: ByteArray, pps: ByteArray): Boolean {
        if (!makeEncoder()) return false
        return makeDecoder(sps, pps)
    }

    private fun makeDecoder(sps: ByteArray, pps: ByteArray): Boolean {
        if (sps.isEmpty() || pps.isEmpty()) return false
        val gl = gl ?: return false
        decoder?.let { old ->
            // Parameter set change: the frames the old decoder holds are
            // the outgoing format's last fraction of a second; dropped.
            runCatching { old.stop() }
            runCatching { old.release() }
            decoder = null
            decoderHeld = 0
        }
        val name = decoderName.ifEmpty { findCodec(MediaFormat.MIMETYPE_VIDEO_AVC, encoder = false, source.width, source.height) }
        if (name == null) {
            fail("hardware H.264 decoder unavailable (none for ${source.width}x${source.height})")
            return false
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, source.width, source.height).apply {
            val start = byteArrayOf(0, 0, 0, 1)
            setByteBuffer("csd-0", ByteBuffer.wrap(start + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(start + pps))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(2 * 1024 * 1024, source.width * source.height))
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
        }
        val codec = try {
            MediaCodec.createByCodecName(name).also {
                it.configure(format, gl.decoderSurface, null, 0)
                it.start()
            }
        } catch (e: Exception) {
            fail("hardware H.264 decoder unavailable (${describe(e)})")
            return false
        }
        decoder = codec
        decoderName = name
        decoderSps = sps
        decoderPps = pps
        return true
    }

    private fun makeEncoder(): Boolean {
        val mime = when (spec.codec) {
            CastVideoOutputSpec.Codec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
            CastVideoOutputSpec.Codec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val codecLabel = if (spec.codec == CastVideoOutputSpec.Codec.HEVC) "HEVC" else "H.264"
        val name = encoderName.ifEmpty { findCodec(mime, encoder = true, spec.width, spec.height) }
        if (name == null) {
            fail("hardware $codecLabel encoder unavailable (none for ${spec.width}x${spec.height})")
            return false
        }
        val outFps = spec.outputFps(source.fps)
        // Forced IDRs every `targetKeyTicks` set the real cadence (see the
        // class comment). The encoder's own interval sits one second past
        // it as a safety net: at exactly the target it would place its own
        // IDR a frame BEFORE the target (179.82 frames at 59.94) and the
        // forced one right after, two IDRs per segment.
        val keySeconds = targetKeyTicks.toDouble() / TICKS
        fun format(withProfile: Boolean, withBitrateMode: Boolean) =
            MediaFormat.createVideoFormat(mime, spec.width, spec.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setFloat(MediaFormat.KEY_FRAME_RATE, (outFps ?: 30.0).toFloat())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                    setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, (keySeconds + 1).toFloat())
                } else {
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Math.round(keySeconds + 1).toInt())
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
                if (withBitrateMode) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
                if (withProfile) {
                    when (spec.codec) {
                        CastVideoOutputSpec.Codec.HEVC ->
                            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                        CastVideoOutputSpec.Codec.H264 -> {
                            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                        }
                    }
                }
                colorStandard(source.colourPrimaries, source.matrixCoefficients)?.let {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, it)
                }
                colorTransfer(source.transferCharacteristics)?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
                setInteger(
                    MediaFormat.KEY_COLOR_RANGE,
                    if (source.fullRange) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED,
                )
            }
        val vbr = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name }
                ?.getCapabilitiesForType(mime)?.encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true
        }.getOrDefault(false)
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var lastError: Exception? = null
        for (withProfile in listOf(true, false)) {
            try {
                val c = MediaCodec.createByCodecName(name)
                try {
                    c.configure(format(withProfile, vbr), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    surface = c.createInputSurface()
                    c.start()
                    codec = c
                } catch (e: Exception) {
                    runCatching { c.release() }
                    throw e
                }
                break
            } catch (e: Exception) {
                lastError = e
                if (withProfile) {
                    val profile = if (spec.codec == CastVideoOutputSpec.Codec.HEVC) "HEVC Main" else "High 4.1"
                    log("video transcode: encoder refused profile $profile (${describe(e)})")
                }
            }
        }
        if (codec == null || surface == null) {
            fail("hardware $codecLabel encoder unavailable (${lastError?.let { describe(it) } ?: "configure"})")
            return false
        }
        val scaler = try {
            GlScaler(surface, spec.width, spec.height, auxHandler) {
                synchronized(frameSync) {
                    frameAvailable = true
                    frameSync.notifyAll()
                }
            }
        } catch (e: RuntimeException) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            surface.release()
            fail("scaler setup failed (${describe(e)})")
            return false
        }
        encoder = codec
        encoderName = name
        encoderSurface = surface
        gl = scaler
        val target = when (spec.codec) {
            CastVideoOutputSpec.Codec.HEVC -> "HEVC Main"
            CastVideoOutputSpec.Codec.H264 -> "H.264 High 4.1"
        }
        val outLabel = outFps?.let { CastVideoPlan.fpsLabel(it) } ?: "?"
        log(
            "video transcode: H.264 ${source.width}x${source.height}@${source.fps?.let { CastVideoPlan.fpsLabel(it) } ?: "?"} " +
                "level ${source.levelLabel} -> $target ${spec.width}x${spec.height}@$outLabel, " +
                "target ${targetBitrate / 1000} kbps (MediaCodec hardware $name)",
        )
        return true
    }

    /** First hardware codec for [mime] that takes [w]x[h]; null when the
     *  device has none (the transcode then fails over to passthrough). */
    private fun findCodec(mime: String, encoder: Boolean, w: Int, h: Int): String? {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrNull() ?: return null
        return infos.firstOrNull { info ->
            info.isEncoder == encoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                isHardware(info) &&
                runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(w, h) == true
                }.getOrDefault(false)
        }?.name
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated && !info.isSoftwareOnly && !info.isAlias
        }
        val n = info.name.lowercase(Locale.US)
        return !n.startsWith("omx.google.") && !n.startsWith("c2.android.") && !n.contains(".sw.")
    }

    /** Drop the decoder and restart it on the next source IDR (the encoder
     *  and its surface stay). Repeated resets end the transcode. */
    private fun resetDecoder(reason: String) {
        sessionResets++
        log("video transcode: $reason; rebuilding at the next IDR (reset $sessionResets)")
        decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        decoder = null
        decoderSps = ByteArray(0)
        decoderPps = ByteArray(0)
        decoderHeld = 0
        waitingForKey = true
        if (sessionResets >= 5) fail("$reason, $sessionResets resets")
    }

    /** Drop every session and restart on the next source IDR (encoder
     *  invalidation). Repeated resets end the transcode. */
    private fun resetSessions(reason: String) {
        sessionResets++
        log("video transcode: $reason; rebuilding at the next IDR (reset $sessionResets)")
        teardownSessions()
        waitingForKey = true
        lastKeyPts = -1
        if (sessionResets >= 5) fail("$reason, $sessionResets resets")
    }

    private fun teardownSessions() {
        decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        decoder = null
        decoderSps = ByteArray(0)
        decoderPps = ByteArray(0)
        decoderHeld = 0
        encoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        encoder = null
        gl?.let { runCatching { it.release() } }
        gl = null
        encoderSurface?.let { runCatching { it.release() } }
        encoderSurface = null
        rendered.clear()
        synchronized(frameSync) { frameAvailable = false }
    }

    private fun noteError(what: String) {
        consecutiveErrors++
        if (consecutiveErrors == 1 || consecutiveErrors % 30 == 0) {
            log("video transcode: $what ($consecutiveErrors in a row)")
        }
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            fail("$what, $consecutiveErrors errors in a row")
        }
    }

    private fun fail(reason: String) {
        synchronized(lock) {
            if (failed || released) return
            failed = true
            pending.clear()
        }
        log("video transcode failed: $reason; falling back to H.264 passthrough")
        deliver { sink.onFailure(reason) }
    }

    // ---- bitrate ----

    /** The first [BITRATE_WINDOW_TICKS] of source media set the encoder
     *  target to min(source rate, profile cap). */
    private fun measureBitrate(frame: InputFrame) {
        if (bitrateMeasured) return
        if (bitrateStartDts < 0) bitrateStartDts = frame.dts
        bitrateBytes += frame.data.size
        val span = frame.dts - bitrateStartDts
        if (span < BITRATE_WINDOW_TICKS) return
        bitrateMeasured = true
        val measured = (bitrateBytes.toDouble() * 8 * TICKS / span).toInt()
        val target = maxOf(1_000_000, minOf(measured, spec.bitrateCap))
        log("video transcode: source measured ${measured / 1000} kbps, encoder target ${target / 1000} kbps")
        if (target == targetBitrate) return
        targetBitrate = target
        encoder?.let { enc ->
            runCatching {
                enc.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target) })
            }
        }
    }

    // ---- stats and thermal ----

    private fun logStats() {
        val line: String
        synchronized(lock) {
            if (released || failed) return
            val now = SystemClock.elapsedRealtime()
            val elapsed = maxOf(0.001, (now - statsStartedAt) / 1000.0)
            statsStartedAt = now
            val fps = encodedSinceStats / elapsed
            encodedSinceStats = 0
            val queued = pending.size
            val backlog = if (pending.isEmpty()) 0L else pending.last().dts - pending.first().dts
            val sb = StringBuilder(
                String.format(
                    Locale.US, "video transcode: encoded %.1f fps, queue %d (%.1f s) reorder %d in flight %d",
                    fps, queued, backlog.toDouble() / TICKS, decoderHeldSnapshot, inFlightSnapshot,
                ),
            )
            if (droppedBacklog + droppedEncoder + droppedLate > 0) {
                sb.append(", dropped backlog=$droppedBacklog encoder=$droppedEncoder late=$droppedLate")
            }
            if (decodeErrors > 0) sb.append(", decode errors=$decodeErrors")
            sb.append(", thermal=").append(thermalName(currentThermal()))
            line = sb.toString()
        }
        log(line)
    }

    private fun currentThermal(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager?.currentThermalStatus ?: -1 else -1

    private fun noteThermalChange(status: Int) {
        val old: Int
        synchronized(lock) {
            if (released || failed) return
            old = lastThermal
            lastThermal = status
        }
        if (old == status) return
        log("video transcode: thermal state ${thermalName(old)} -> ${thermalName(status)}")
    }

    private fun thermalName(s: Int): String = when (s) {
        PowerManager.THERMAL_STATUS_NONE -> "nominal"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    // ---- helpers ----

    private fun bytesOf(b: ByteBuffer): ByteArray {
        val dup = b.duplicate()
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out
    }

    private fun describe(e: Throwable): String {
        val codec = e as? MediaCodec.CodecException
        return codec?.diagnosticInfo ?: e.message ?: e.javaClass.simpleName
    }

    /** VUI colour_primaries (then matrix_coefficients) to KEY_COLOR_STANDARD. */
    private fun colorStandard(primaries: Int?, matrix: Int?): Int? = when (primaries) {
        1 -> MediaFormat.COLOR_STANDARD_BT709
        5 -> MediaFormat.COLOR_STANDARD_BT601_PAL
        6 -> MediaFormat.COLOR_STANDARD_BT601_NTSC
        9 -> MediaFormat.COLOR_STANDARD_BT2020
        else -> when (matrix) {
            1 -> MediaFormat.COLOR_STANDARD_BT709
            5 -> MediaFormat.COLOR_STANDARD_BT601_PAL
            6 -> MediaFormat.COLOR_STANDARD_BT601_NTSC
            9 -> MediaFormat.COLOR_STANDARD_BT2020
            else -> null
        }
    }

    private fun colorTransfer(code: Int?): Int? = when (code) {
        1, 6, 14, 15 -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        16 -> MediaFormat.COLOR_TRANSFER_ST2084
        18 -> MediaFormat.COLOR_TRANSFER_HLG
        else -> null
    }
}

/**
 * The GPU scaler between the two codecs: the decoder renders into a
 * SurfaceTexture (external OES texture, zero copy), and one textured quad
 * is drawn into an EGL window surface wrapping the encoder's input Surface
 * at the output size. That is the hardware scale for the 720p profile and
 * the 4K-to-1080 HEVC case; at equal sizes it is a straight copy.
 *
 * A decoder cannot render straight into the encoder's input surface at a
 * different size: the encoder's GraphicBufferSource takes the decoder's
 * buffers as they are and does not scale.
 *
 * Every call except the frame callback runs on the transcoder's work
 * thread, which owns the EGL context.
 */
private class GlScaler(
    encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    frameHandler: Handler,
    onFrame: () -> Unit,
) {
    private val display: EGLDisplay
    private val context: EGLContext
    private val eglSurface: EGLSurface
    private val program: Int
    private val textureId: Int
    private val aPosition: Int
    private val aTexCoord: Int
    private val uTexMatrix: Int
    private val texMatrix = FloatArray(16)
    val surfaceTexture: SurfaceTexture
    val decoderSurface: Surface

    private val quad: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val texCoords: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no recordable EGL config"
        }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }

        program = buildProgram()
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener({ onFrame() }, frameHandler)
        decoderSurface = Surface(surfaceTexture)
    }

    fun draw() {
        surfaceTexture.getTransformMatrix(texMatrix)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 8, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    /** Stamp the drawn frame with its presentation time and submit it to
     *  the encoder. */
    fun swap(ptsNs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed 0x${Integer.toHexString(EGL14.eglGetError())}" }
    }

    fun release() {
        runCatching { decoderSurface.release() }
        runCatching { surfaceTexture.release() }
        runCatching { GLES20.glDeleteProgram(program) }
        runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        fun floatBuffer(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v); position(0)
            }
    }
}
