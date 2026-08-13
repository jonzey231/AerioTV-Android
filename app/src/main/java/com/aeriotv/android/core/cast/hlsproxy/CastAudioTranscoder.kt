package com.aeriotv.android.core.cast.hlsproxy

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
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
 * fix is to decode on the phone with the platform MediaCodec decoder
 * (nearly all Samsung/Pixel phones ship AC-3/E-AC-3 decoders, MP2 has a
 * platform decoder), downmix the PCM to stereo, and encode AAC-LC at
 * about 160 kbps. H.264 video stays pure passthrough in the remuxer.
 *
 * Threading: synchronous MediaCodec driven entirely by the caller. The
 * remuxer invokes [feed] from the proxy's ingest thread; each call queues
 * one source access unit and opportunistically drains both codecs. No
 * internal threads.
 *
 * PTS flow: the source AU's unwrapped 90 kHz ticks enter the decoder as
 * microseconds via queueInputBuffer, MediaCodec carries them through the
 * PCM buffers to the encoder (chunked PCM re-stamps by sample offset),
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

    // ---- MediaCodec plumbing (device only; never runs on the JVM) ----

    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    private var encoderSampleRate = 0
    private var pcmChannels = 0
    private var mapper: AacPtsMapper? = null
    private var configDelivered = false
    // Lazy so constructing the class (or a JVM-test fake subclass) never
    // touches android.media; only real feed() calls do.
    private val decInfo by lazy { MediaCodec.BufferInfo() }
    private val encInfo by lazy { MediaCodec.BufferInfo() }

    /**
     * Queue one source access unit (a whole AC-3/E-AC-3/MP2 frame) and
     * drain whatever both codecs have ready. Called on the ingest thread.
     *
     * Throws [UnsupportedCodecException] when the device has no decoder
     * for [source] (first call only); any other codec failure surfaces
     * as a runtime exception the session's reconnect path absorbs.
     */
    open fun feed(frame: ByteArray, offset: Int, length: Int, ptsTicks: Long, info: EsFrameInfo) {
        val dec = decoder ?: initCodecs(info)
        var attempts = 0
        while (true) {
            val idx = dec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (idx >= 0) {
                val bb = dec.getInputBuffer(idx) ?: error("decoder input buffer missing")
                bb.clear()
                bb.put(frame, offset, length)
                dec.queueInputBuffer(idx, 0, length, ticksToUs(ptsTicks), 0)
                break
            }
            drainDecoder()
            drainEncoder()
            if (++attempts > MAX_STALL_ATTEMPTS) error("audio decoder input stalled")
        }
        drainDecoder()
        drainEncoder()
    }

    /** Splice/reconnect: drop in-flight buffers and let the PTS mapper
     *  re-anchor on the next output stamp. */
    open fun flush() {
        runCatching { decoder?.flush() }
        runCatching { encoder?.flush() }
        mapper?.reset()
    }

    open fun release() {
        try {
            runCatching { decoder?.stop() }
            runCatching { encoder?.stop() }
        } finally {
            runCatching { decoder?.release() }
            runCatching { encoder?.release() }
            decoder = null
            encoder = null
        }
    }

    private fun initCodecs(info: EsFrameInfo): MediaCodec {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var chosenMime: String? = null
        var chosenName: String? = null
        for (mime in source.decoderMimes) {
            val fmt = MediaFormat.createAudioFormat(mime, info.sampleRate, info.channels)
            val name = runCatching { list.findDecoderForFormat(fmt) }.getOrNull()
            if (name != null) {
                chosenMime = mime
                chosenName = name
                break
            }
        }
        if (chosenMime == null || chosenName == null) {
            // Same refusal P1 made for every non-AAC codec: with no
            // device decoder there is nothing to transcode with.
            throw UnsupportedCodecException("${source.displayName} audio")
        }
        val dec = MediaCodec.createByCodecName(chosenName)
        try {
            dec.configure(MediaFormat.createAudioFormat(chosenMime, info.sampleRate, info.channels), null, null, 0)
            dec.start()
        } catch (t: Throwable) {
            runCatching { dec.release() }
            throw t
        }
        decoder = dec
        pcmChannels = info.channels
        encoderSampleRate = info.sampleRate
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
            runCatching { dec.stop() }
            runCatching { dec.release() }
            decoder = null
            throw t
        }
        encoder = enc
        mapper = AacPtsMapper(info.sampleRate)
        log(
            "audio codecs up: decoder=$chosenName ($chosenMime) " +
                "encoder=AAC-LC stereo ${TARGET_AAC_BITRATE / 1000}kbps @${info.sampleRate}Hz",
        )
        return dec
    }

    private fun drainDecoder() {
        val dec = decoder ?: return
        while (true) {
            val idx = dec.dequeueOutputBuffer(decInfo, 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = dec.outputFormat
                    pcmChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (rate != encoderSampleRate) {
                        // Would need a resampler; log it so a field report
                        // with chipmunk audio is diagnosable.
                        log("decoder pcm rate ${rate}Hz differs from encoder ${encoderSampleRate}Hz")
                    }
                }
                idx >= 0 -> {
                    if (decInfo.size > 0) {
                        val bb = dec.getOutputBuffer(idx) ?: error("decoder output buffer missing")
                        bb.position(decInfo.offset)
                        bb.limit(decInfo.offset + decInfo.size)
                        val pcm = ShortArray(decInfo.size / 2)
                        bb.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm)
                        feedEncoder(downmixToStereo(pcm, pcmChannels), decInfo.presentationTimeUs)
                    }
                    dec.releaseOutputBuffer(idx, false)
                }
                else -> return // INFO_TRY_AGAIN_LATER
            }
        }
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
