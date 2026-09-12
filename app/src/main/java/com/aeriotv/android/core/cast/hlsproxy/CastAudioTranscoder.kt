package com.aeriotv.android.core.cast.hlsproxy

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.media3.decoder.ffmpeg.AerioFfmpegPcmDecoder
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cast HLS proxy P2: on-phone audio transcode for muxes whose audio the
 * web receiver cannot decode. The first live field session refused with
 * "AC-3 audio", and most of the lineup (Dispatcharr raw TS, typical
 * IPTV) carries AC-3 or E-AC-3 or MP2; Chromecast web receivers cannot
 * decode AC-3 themselves (HDMI passthrough only, and unreliably), so the
 * fix is to decode on the phone, downmix the PCM to stereo, and encode
 * AAC-LC at about 160 kbps. H.264 video stays pure passthrough in the
 * remuxer.
 *
 * Decoder selection: the platform MediaCodec decoder first (nearly all
 * Samsung/Pixel phones ship AC-3/E-AC-3 decoders, MP2 has a platform
 * decoder); when the device ships none (Qualcomm "yupik" Nothing Phone:
 * its vendor media_codecs list has no audio/ac3 or audio/eac3), fall
 * back to the bundled media3 FFmpeg software decoder through
 * [AerioFfmpegPcmDecoder] - the same extension that makes local
 * playback of these muxes work on that phone, so casting must not
 * refuse where playback succeeds. Only with neither does the session
 * refuse. Since 2026-09-11 the bundled FFmpeg build carries only the
 * patent-expired decoders (ac3, aac, mp2, mp3, flac, alac), so the
 * fallback covers AC-3 and MP2 but NOT E-AC-3; an E-AC-3 mux on a phone
 * with no platform E-AC-3 decoder now refuses the session. No code
 * change was needed for that: [AerioFfmpegPcmDecoder.isSupported] asks
 * the native library at runtime via FfmpegLibrary.supportsFormat.
 *
 * Threading: synchronous, driven entirely by the caller (the FFmpeg
 * decoder's own decode thread is hidden behind a non-blocking
 * queue/drain surface). The
 * remuxer invokes [feed] from the proxy's ingest thread; each call queues
 * one source access unit and opportunistically drains both codecs. No
 * internal threads.
 *
 * PTS flow: the source AU's unwrapped 90 kHz ticks enter the decoder as
 * microseconds with the access unit, and the decoder (either one)
 * carries them through the PCM buffers to the encoder (chunked PCM re-stamps by sample offset),
 * and [AacPtsMapper] regularizes the encoder's output stamps onto an
 * exact anchor + n * 1024 / sampleRate ladder so the remuxer's fMP4
 * sample durations and tfdt stay coherent. A stamp past the
 * discontinuity threshold re-anchors the ladder.
 *
 * Testability: MediaCodec does not exist on the JVM, so the downmix
 * math, the elementary-stream frame-header parsers, and the PTS mapper
 * are pure companion members with unit tests; the codec plumbing itself
 * is device-verified. The class is open so the remuxer test can fake the
 * codec path.
 */
open class CastAudioTranscoder(
    private val source: SourceCodec,
    private val listener: Listener,
    private val log: (String) -> Unit,
) {
    /** Mime strings spelled as literals (identical to the MediaFormat
     *  constants) so loading this enum never touches android.media on
     *  the JVM test path. */
    enum class SourceCodec(val displayName: String, val decoderMimes: List<String>) {
        AC3("AC-3", listOf("audio/ac3")),
        EAC3("E-AC-3", listOf("audio/eac3")),
        /** MPEG-1/2 audio; L2 is the broadcast norm, the plain mpeg
         *  decoder covers layer III panels. */
        MP2("MP2", listOf("audio/mpeg-L2", "audio/mpeg")),
    }

    interface Listener {
        /** Encoder AudioSpecificConfig (csd-0) for the fMP4 esds; fires
         *  once, before the first [onAacFrame]. */
        fun onEncoderConfig(asc: ByteArray, sampleRate: Int)

        /** One 1024-sample AAC-LC frame; [ptsTicks] is 90 kHz, already
         *  regularized by the PTS mapper. */
        fun onAacFrame(data: ByteArray, ptsTicks: Long)
    }

    /** Parsed elementary-stream frame header: everything the framer and
     *  the decoder configuration need. */
    class EsFrameInfo(
        val frameLength: Int,
        val sampleRate: Int,
        val samplesPerFrame: Int,
        val channels: Int,
    )

    /**
     * Regularizes encoder output PTS onto an exact rational ladder
     * anchored at the first (or post-discontinuity) stamp:
     * pts(n) = anchor + n * 1024 * 90000 / sampleRate, computed from the
     * anchor each time so the non-integer 44.1 kHz frame duration never
     * accumulates drift. A stamp more than [discontinuityTicks] off the
     * ladder (splice/reconnect) re-anchors.
     */
    class AacPtsMapper(
        private val sampleRate: Int,
        private val discontinuityTicks: Long = DISCONTINUITY_TICKS,
    ) {
        private var anchorTicks = -1L
        private var framesSinceAnchor = 0L

        fun map(encoderPtsTicks: Long): Long {
            if (anchorTicks >= 0) {
                val expected = ladder(framesSinceAnchor)
                if (abs(encoderPtsTicks - expected) > discontinuityTicks) anchorTicks = -1
            }
            if (anchorTicks < 0) {
                anchorTicks = encoderPtsTicks
                framesSinceAnchor = 0
            }
            val pts = ladder(framesSinceAnchor)
            framesSinceAnchor++
            return pts
        }

        fun reset() {
            anchorTicks = -1
            framesSinceAnchor = 0
        }

        private fun ladder(n: Long): Long =
            anchorTicks + n * AAC_SAMPLES_PER_FRAME * TsToFmp4Remuxer.TICKS_PER_SECOND / sampleRate
    }

    companion object {
        const val TARGET_AAC_BITRATE = 160_000
        const val AAC_SAMPLES_PER_FRAME = 1024L

        /** Source PTS jump treated as a splice/reconnect: flush both
         *  codecs and re-anchor. 500 ms at 90 kHz. */
        const val DISCONTINUITY_TICKS = 45_000L

        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val MAX_STALL_ATTEMPTS = 50

        private val AC3_SAMPLE_RATES = intArrayOf(48_000, 44_100, 32_000)
        private val AC3_BITRATES_KBPS = intArrayOf(
            32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640,
        )
        /** Full-bandwidth channels per acmod (A/52 table 5.8); lfeon adds one. */
        private val AC3_ACMOD_CHANNELS = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)
        private val EAC3_BLOCKS = intArrayOf(1, 2, 3, 6)
        private val MPEG1_L2_BITRATES = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
        private val MPEG1_L3_BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val MPEG2_BITRATES = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        private val MPEG_SAMPLE_RATES = intArrayOf(44_100, 48_000, 32_000)

        /** Parse the frame header at [off]; null when [off] is not a
         *  plausible frame start (the caller scans on). */
        fun parseFrameHeader(codec: SourceCodec, data: ByteArray, off: Int): EsFrameInfo? = when (codec) {
            SourceCodec.AC3 -> parseAc3Header(data, off)
            SourceCodec.EAC3 -> parseEac3Header(data, off)
            SourceCodec.MP2 -> parseMpegAudioHeader(data, off)
        }

        /** Cheap syncword check, used to reject false syncs by verifying
         *  the NEXT frame starts where the parsed length says. */
        fun looksLikeSync(codec: SourceCodec, data: ByteArray, off: Int): Boolean = when (codec) {
            SourceCodec.AC3, SourceCodec.EAC3 ->
                off + 1 < data.size && data[off].toInt() and 0xFF == 0x0B &&
                    data[off + 1].toInt() and 0xFF == 0x77
            SourceCodec.MP2 ->
                off + 1 < data.size && data[off].toInt() and 0xFF == 0xFF &&
                    data[off + 1].toInt() and 0xE0 == 0xE0
        }

        private fun parseAc3Header(data: ByteArray, off: Int): EsFrameInfo? {
            if (off + 7 > data.size || !looksLikeSync(SourceCodec.AC3, data, off)) return null
            val fscod = (data[off + 4].toInt() shr 6) and 0x03
            val frmsizecod = data[off + 4].toInt() and 0x3F
            if (fscod == 3 || frmsizecod >= AC3_BITRATES_KBPS.size * 2) return null
            val bitrate = AC3_BITRATES_KBPS[frmsizecod shr 1]
            val words = when (fscod) {
                0 -> 2 * bitrate
                1 -> 320 * bitrate / 147 + (frmsizecod and 1)
                else -> 3 * bitrate
            }
            // acmod and lfeon sit behind variable mix-level fields; the
            // whole walk fits inside byte 6 (A/52 5.4.2).
            val acmod = (data[off + 6].toInt() shr 5) and 0x07
            var bit = 3
            if (acmod and 0x01 != 0 && acmod != 1) bit += 2 // cmixlev
            if (acmod and 0x04 != 0) bit += 2 // surmixlev
            if (acmod == 2) bit += 2 // dsurmod
            val lfeon = (data[off + 6].toInt() shr (7 - bit)) and 1
            return EsFrameInfo(words * 2, AC3_SAMPLE_RATES[fscod], 1536, AC3_ACMOD_CHANNELS[acmod] + lfeon)
        }

        private fun parseEac3Header(data: ByteArray, off: Int): EsFrameInfo? {
            if (off + 6 > data.size || !looksLikeSync(SourceCodec.EAC3, data, off)) return null
            val strmtyp = (data[off + 2].toInt() shr 6) and 0x03
            if (strmtyp == 3) return null
            val frmsiz = ((data[off + 2].toInt() and 0x07) shl 8) or (data[off + 3].toInt() and 0xFF)
            val b4 = data[off + 4].toInt() and 0xFF
            val fscod = (b4 shr 6) and 0x03
            val sampleRate: Int
            val blocks: Int
            if (fscod == 3) {
                val fscod2 = (b4 shr 4) and 0x03
                if (fscod2 == 3) return null
                sampleRate = AC3_SAMPLE_RATES[fscod2] / 2
                blocks = 6
            } else {
                sampleRate = AC3_SAMPLE_RATES[fscod]
                blocks = EAC3_BLOCKS[(b4 shr 4) and 0x03]
            }
            val acmod = (b4 shr 1) and 0x07
            val lfeon = b4 and 0x01
            return EsFrameInfo((frmsiz + 1) * 2, sampleRate, blocks * 256, AC3_ACMOD_CHANNELS[acmod] + lfeon)
        }

        private fun parseMpegAudioHeader(data: ByteArray, off: Int): EsFrameInfo? {
            if (off + 4 > data.size || !looksLikeSync(SourceCodec.MP2, data, off)) return null
            val b2 = data[off + 1].toInt() and 0xFF
            val version = (b2 shr 3) and 0x03 // 3 MPEG-1, 2 MPEG-2, 0 MPEG-2.5
            val layer = (b2 shr 1) and 0x03 // 2 layer II, 1 layer III
            if (version == 1 || layer == 0 || layer == 3) return null // reserved / layer I
            val b3 = data[off + 2].toInt() and 0xFF
            val bitrateIndex = (b3 shr 4) and 0x0F
            val srIndex = (b3 shr 2) and 0x03
            val padding = (b3 shr 1) and 0x01
            if (bitrateIndex == 0 || bitrateIndex == 15 || srIndex == 3) return null
            val mpeg1 = version == 3
            val bitrate = when {
                mpeg1 && layer == 2 -> MPEG1_L2_BITRATES[bitrateIndex]
                mpeg1 -> MPEG1_L3_BITRATES[bitrateIndex]
                else -> MPEG2_BITRATES[bitrateIndex]
            }
            val sampleRate = MPEG_SAMPLE_RATES[srIndex] / when (version) {
                3 -> 1
                2 -> 2
                else -> 4
            }
            val samples = if (mpeg1 || layer == 2) 1152 else 576
            val frameLen = samples / 8 * bitrate * 1000 / sampleRate + padding
            val channels = if ((data[off + 3].toInt() shr 6) and 0x03 == 3) 1 else 2
            return EsFrameInfo(frameLen, sampleRate, samples, channels)
        }

        /**
         * Interleaved 16-bit PCM to stereo. Android decoders emit the
         * standard order FL FR C LFE BL BR for 5.1; the mix is the plain
         * coefficient downmix
         *   L = FL + 0.707 * C + 0.707 * SL
         *   R = FR + 0.707 * C + 0.707 * SR
         * with LFE dropped and the result clamped to 16-bit. Mono
         * duplicates, stereo passes through untouched. Layouts other
         * than mono/stereo/3.0/5.1 are approximated by the same index
         * positions (extras past 5.1 are ignored), which is fine for a
         * cast downmix.
         */
        fun downmixToStereo(pcm: ShortArray, channels: Int): ShortArray {
            if (channels == 2) return pcm
            if (channels <= 0) return ShortArray(0)
            val frames = pcm.size / channels
            val out = ShortArray(frames * 2)
            for (f in 0 until frames) {
                val base = f * channels
                if (channels == 1) {
                    out[2 * f] = pcm[base]
                    out[2 * f + 1] = pcm[base]
                    continue
                }
                val c = if (channels >= 3) pcm[base + 2] * 0.707 else 0.0
                val sl = if (channels >= 5) pcm[base + 4] * 0.707 else 0.0
                val sr = if (channels >= 6) pcm[base + 5] * 0.707 else 0.0
                out[2 * f] = clamp16(pcm[base] + c + sl)
                out[2 * f + 1] = clamp16(pcm[base + 1] + c + sr)
            }
            return out
        }

        private fun clamp16(v: Double): Short = v.roundToInt().coerceIn(-32768, 32767).toShort()

        private fun ticksToUs(ticks: Long): Long = ticks * 100 / 9

        private fun usToTicks(us: Long): Long = us * 9 / 100
    }

    // ---- Codec plumbing (device only; never runs on the JVM) ----

    /**
     * The source-PCM decode step, behind an interface so the platform
     * MediaCodec path and the bundled FFmpeg software path can share the
     * downmix and the AAC encoder that follow. Implementations are
     * private nested classes, so loading [CastAudioTranscoder] itself
     * still never touches android.media or the media3 decoder on the JVM
     * test path.
     */
    private interface PcmDecoder {
        /** Milliseconds to sleep per stall retry; 0 when the
         *  implementation's own queue call already waits. */
        val stallWaitMs: Long

        /** Queue one access unit; false = no input slot free right now,
         *  so the caller should drain and retry. */
        fun queue(frame: ByteArray, offset: Int, length: Int, ptsUs: Long): Boolean

        /** Hand every ready PCM buffer to [sink] as (interleaved 16-bit
         *  samples, channel count, presentation stamp in microseconds). */
        fun drain(sink: (ShortArray, Int, Long) -> Unit)

        fun flush()
        fun release()
    }

    private inner class MediaCodecPcmDecoder(
        private val codec: MediaCodec,
        private var channels: Int,
    ) : PcmDecoder {
        /** dequeueInputBuffer already waits DEQUEUE_TIMEOUT_US. */
        override val stallWaitMs = 0L
        private val info = MediaCodec.BufferInfo()

        override fun queue(frame: ByteArray, offset: Int, length: Int, ptsUs: Long): Boolean {
            val idx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (idx < 0) return false
            val bb = codec.getInputBuffer(idx) ?: error("decoder input buffer missing")
            bb.clear()
            bb.put(frame, offset, length)
            codec.queueInputBuffer(idx, 0, length, ptsUs, 0)
            return true
        }

        override fun drain(sink: (ShortArray, Int, Long) -> Unit) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 0)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        warnRateMismatch(f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    }
                    idx >= 0 -> {
                        if (info.size > 0) {
                            val bb = codec.getOutputBuffer(idx) ?: error("decoder output buffer missing")
                            bb.position(info.offset)
                            bb.limit(info.offset + info.size)
                            val pcm = ShortArray(info.size / 2)
                            bb.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm)
                            sink(pcm, channels, info.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(idx, false)
                    }
                    else -> return // INFO_TRY_AGAIN_LATER
                }
            }
        }

        override fun flush() {
            runCatching { codec.flush() }
        }

        override fun release() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /**
     * Bundled media3 FFmpeg software decoder. Its SimpleDecoder runs its
     * own decode thread, so [queue] and [drain] are non-blocking and the
     * PTS rides along on the buffer: the stamp we put on the access unit
     * comes straight back out on the PCM buffer, which is exactly what
     * the encoder leg and [AacPtsMapper] already expect.
     */
    private inner class FfmpegPcmDecoder(
        private val ffmpeg: AerioFfmpegPcmDecoder,
        private var channels: Int,
    ) : PcmDecoder {
        /** Non-blocking queue, so pace the stall loop here instead; the
         *  same total patience the MediaCodec path has. */
        override val stallWaitMs = DEQUEUE_TIMEOUT_US / 1000

        private var formatChecked = false

        override fun queue(frame: ByteArray, offset: Int, length: Int, ptsUs: Long): Boolean =
            ffmpeg.queue(frame, offset, length, ptsUs)

        override fun drain(sink: (ShortArray, Int, Long) -> Unit) {
            while (true) {
                val out = ffmpeg.dequeueOutput() ?: return
                try {
                    val data = out.data
                    if (!out.shouldBeSkipped && data != null && data.hasRemaining()) {
                        if (!formatChecked) {
                            formatChecked = true
                            // Authoritative once the first frame decoded;
                            // the frame header only guessed the layout.
                            if (ffmpeg.channelCount > 0) channels = ffmpeg.channelCount
                            warnRateMismatch(ffmpeg.sampleRate)
                        }
                        val pcm = ShortArray(data.remaining() / 2)
                        data.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm)
                        sink(pcm, channels, out.timeUs)
                    }
                } finally {
                    out.release()
                }
            }
        }

        override fun flush() {
            runCatching { ffmpeg.flush() }
        }

        override fun release() {
            runCatching { ffmpeg.release() }
        }
    }

    private var pcmDecoder: PcmDecoder? = null
    private var encoder: MediaCodec? = null
    private var encoderSampleRate = 0
    private var decoderLabel = ""
    private var mapper: AacPtsMapper? = null
    private var configDelivered = false
    // Lazy so constructing the class (or a JVM-test fake subclass) never
    // touches android.media; only real feed() calls do.
    private val encInfo by lazy { MediaCodec.BufferInfo() }

    /**
     * Queue one source access unit (a whole AC-3/E-AC-3/MP2 frame) and
     * drain whatever both codecs have ready. Called on the ingest thread.
     *
     * Throws [UnsupportedCodecException] when neither the platform nor
     * the bundled FFmpeg decoder can handle [source] (first call only);
     * any other codec failure surfaces as a runtime exception the
     * session's reconnect path absorbs.
     */
    open fun feed(frame: ByteArray, offset: Int, length: Int, ptsTicks: Long, info: EsFrameInfo) {
        val dec = pcmDecoder ?: initCodecs(info)
        var attempts = 0
        while (!dec.queue(frame, offset, length, ticksToUs(ptsTicks))) {
            drainDecoder()
            drainEncoder()
            if (++attempts > MAX_STALL_ATTEMPTS) error("audio decoder input stalled")
            if (dec.stallWaitMs > 0) Thread.sleep(dec.stallWaitMs)
        }
        drainDecoder()
        drainEncoder()
    }

    /** Splice/reconnect: drop in-flight buffers and let the PTS mapper
     *  re-anchor on the next output stamp. */
    open fun flush() {
        pcmDecoder?.flush()
        runCatching { encoder?.flush() }
        mapper?.reset()
    }

    open fun release() {
        try {
            runCatching { encoder?.stop() }
        } finally {
            pcmDecoder?.release()
            runCatching { encoder?.release() }
            pcmDecoder = null
            encoder = null
        }
    }

    /** Platform decoder, or null when this device ships none. */
    private fun createMediaCodecDecoder(info: EsFrameInfo): PcmDecoder? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (mime in source.decoderMimes) {
            val fmt = MediaFormat.createAudioFormat(mime, info.sampleRate, info.channels)
            val name = runCatching { list.findDecoderForFormat(fmt) }.getOrNull() ?: continue
            val dec = MediaCodec.createByCodecName(name)
            try {
                dec.configure(MediaFormat.createAudioFormat(mime, info.sampleRate, info.channels), null, null, 0)
                dec.start()
            } catch (t: Throwable) {
                runCatching { dec.release() }
                throw t
            }
            decoderLabel = "$name ($mime)"
            return MediaCodecPcmDecoder(dec, info.channels)
        }
        return null
    }

    /**
     * Bundled FFmpeg software decoder, or null when it carries nothing
     * for [source] either (then there is nothing to transcode with and
     * the session refuses, as P1 did).
     */
    private fun createFfmpegDecoder(info: EsFrameInfo): PcmDecoder? {
        for (mime in source.decoderMimes) {
            if (!AerioFfmpegPcmDecoder.isSupported(mime)) continue
            val ff = try {
                AerioFfmpegPcmDecoder.create(mime, info.channels, info.sampleRate)
            } catch (t: Throwable) {
                log("ffmpeg $mime decoder init failed: $t")
                continue
            }
            decoderLabel = "${ff.name} ($mime, ffmpeg software)"
            log(
                "audio transcode active: ${source.displayName} ${info.channels}ch " +
                    "-> AAC-LC stereo (ffmpeg decoder)",
            )
            return FfmpegPcmDecoder(ff, info.channels)
        }
        return null
    }

    private fun initCodecs(info: EsFrameInfo): PcmDecoder {
        encoderSampleRate = info.sampleRate
        val dec = createMediaCodecDecoder(info)
            ?: createFfmpegDecoder(info)
            ?: throw UnsupportedCodecException("${source.displayName} audio")
        pcmDecoder = dec
        val encFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, info.sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, TARGET_AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
        } catch (t: Throwable) {
            runCatching { enc.release() }
            dec.release()
            pcmDecoder = null
            throw t
        }
        encoder = enc
        mapper = AacPtsMapper(info.sampleRate)
        log(
            "audio codecs up: decoder=$decoderLabel " +
                "encoder=AAC-LC stereo ${TARGET_AAC_BITRATE / 1000}kbps @${info.sampleRate}Hz",
        )
        return dec
    }

    /** A PCM rate the encoder was not configured for would need a
     *  resampler; log it so a field report of chipmunk audio is
     *  diagnosable. */
    private fun warnRateMismatch(rate: Int) {
        if (rate > 0 && rate != encoderSampleRate) {
            log("decoder pcm rate ${rate}Hz differs from encoder ${encoderSampleRate}Hz")
        }
    }

    private fun drainDecoder() {
        val dec = pcmDecoder ?: return
        dec.drain { pcm, channels, ptsUs -> feedEncoder(downmixToStereo(pcm, channels), ptsUs) }
    }

    private fun feedEncoder(stereo: ShortArray, ptsUs: Long) {
        val enc = encoder ?: return
        var off = 0
        var attempts = 0
        while (off < stereo.size) {
            val idx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (idx < 0) {
                drainEncoder()
                if (++attempts > MAX_STALL_ATTEMPTS) error("audio encoder input stalled")
                continue
            }
            val bb = enc.getInputBuffer(idx) ?: error("encoder input buffer missing")
            bb.clear()
            var n = min(bb.capacity() / 2, stereo.size - off)
            n -= n % 2 // whole stereo sample frames only
            bb.order(ByteOrder.nativeOrder()).asShortBuffer().put(stereo, off, n)
            // Chunks past the first re-stamp by their sample offset so
            // the encoder's timeline stays sample-accurate.
            val chunkPtsUs = ptsUs + (off / 2) * 1_000_000L / encoderSampleRate
            enc.queueInputBuffer(idx, 0, n * 2, chunkPtsUs, 0)
            off += n
        }
    }

    private fun drainEncoder() {
        val enc = encoder ?: return
        while (true) {
            val idx = enc.dequeueOutputBuffer(encInfo, 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> deliverConfig(enc.outputFormat)
                idx >= 0 -> {
                    val isConfig = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && encInfo.size > 0) {
                        val bb = enc.getOutputBuffer(idx) ?: error("encoder output buffer missing")
                        bb.position(encInfo.offset)
                        val out = ByteArray(encInfo.size)
                        bb.get(out)
                        val m = mapper
                        val pts = usToTicks(encInfo.presentationTimeUs)
                        listener.onAacFrame(out, m?.map(pts) ?: pts)
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
                else -> return
            }
        }
    }

    private fun deliverConfig(fmt: MediaFormat) {
        if (configDelivered) return
        val csd = fmt.getByteBuffer("csd-0") ?: return
        val asc = ByteArray(csd.remaining())
        csd.duplicate().get(asc)
        configDelivered = true
        listener.onEncoderConfig(asc, encoderSampleRate)
    }
}
