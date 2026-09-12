package com.aeriotv.android.core.cast.hlsproxy

/**
 * Elementary-stream framing for the cast HLS proxy's audio passthrough.
 *
 * Since 2026-09-12 the phone does NO cast audio transcoding at all
 * (Logan's ruling): casting a Dispatcharr channel requests the server's
 * built-in "Web Player (AAC Audio)" output profile, so the proxy ingests
 * stereo AAC and passes it straight through, and AC-3 / E-AC-3 is passed
 * through untouched to receivers that decode it. What survives from the
 * old on-phone transcode is exactly this: the syncframe parsers the
 * framer needs to cut access units out of a PES payload, plus the
 * bitstream fields the fMP4 writer needs for the dac3 / dec3 sample
 * entry.
 *
 * Pure Kotlin on purpose: nothing here touches android.media, so the
 * parsers are unit-tested on the JVM.
 */
object CastAudioFramer {

    /** Audio families the proxy can frame out of a PES payload. */
    enum class SourceCodec(val displayName: String) {
        AC3("AC-3"),
        EAC3("E-AC-3"),

        /** MPEG-1/2 audio; L2 is the broadcast norm. Framed only so the
         *  refusal can name it: no Cast receiver decodes it and the phone
         *  no longer transcodes. */
        MP2("MP2"),
    }

    /**
     * Parsed syncframe header: the framing fields plus everything the
     * AC-3 / E-AC-3 sample entry needs. MP2 leaves the bitstream-id
     * fields at zero (it never reaches the fMP4 writer).
     */
    class EsFrameInfo(
        val frameLength: Int,
        val sampleRate: Int,
        val samplesPerFrame: Int,
        val channels: Int,
        val fscod: Int = 0,
        val bsid: Int = 0,
        val bsmod: Int = 0,
        val acmod: Int = 0,
        val lfeon: Int = 0,
        /** Nominal bit rate in kbit/s (dac3 bit_rate_code / dec3 data_rate). */
        val bitrateKbps: Int = 0,
    )

    /** A/52 sample rates by fscod. */
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

    /**
     * A program_config_element found at the start of a raw_data_block.
     *
     * [lengthBytes] is the element's size measured from the block start,
     * which is always a whole number of bytes: the PCE byte-aligns
     * relative to the block start before comment_field_bytes, and the
     * comment itself is a whole number of bytes after it (ISO/IEC
     * 14496-3 4.4.1.1). That is what lets the PCE be removed with a
     * byte-wise copy instead of shifting the whole remaining payload
     * left bit by bit; [CastAudioFramer.parseAacPce] refuses to report a
     * PCE that does not end aligned, so the byte-wise caller can never
     * corrupt a frame.
     */
    class AacPceInfo(
        /** Channels the declared layout adds up to: CPE 2, SCE 1, LFE 1. */
        val channels: Int,
        /** PCE size in bytes, measured from the raw_data_block start. */
        val lengthBytes: Int,
        /** True when the first front element is a channel_pair_element,
         *  i.e. the element the stripped frame will start with is the
         *  stereo pair a channel_configuration of 2 implies. */
        val firstIsCpe: Boolean,
    )

    /**
     * Parse the program_config_element at [offset] in [data], or return
     * null when the block does not start with one.
     *
     * Dispatcharr's "Web Player (AAC Audio)" output profile (ffmpeg
     * `-c:a aac -ac 2`) emits ADTS frames whose channel_configuration is
     * 0 with the real layout carried in a PCE at the start of the
     * raw_data_block ("Using a PCE to encode channel layout"). An
     * AudioSpecificConfig cannot carry config 0 (Chromium's
     * SkipGASpecificConfig does RCHECK(channel_config_ != 0)), and the
     * Google TV Streamer's C2SoftAacDec rejects every frame that still
     * contains the PCE ("error 0x0005, substituting silence", 5388 times
     * in one session). Parsing the element here gives the remuxer both
     * halves of the lossless fix: the real channel count for the ASC,
     * and the exact byte length to drop off the front of each frame.
     *
     * Field order is ISO/IEC 14496-3 4.4.1.1.
     */
    fun parseAacPce(data: ByteArray, offset: Int, end: Int): AacPceInfo? {
        if (offset >= end) return null
        var bit = 0 // bit position RELATIVE to the raw_data_block start
        val limit = (end - offset) * 8
        fun read(n: Int): Int {
            if (bit + n > limit) return -1
            var v = 0
            repeat(n) {
                val p = offset + (bit shr 3)
                v = (v shl 1) or ((data[p].toInt() shr (7 - (bit and 7))) and 1)
                bit++
            }
            return v
        }
        // id_syn_ele: PCE is 0x5. Anything else is a normal element and
        // the frame passes through untouched.
        if (read(3) != 5) return null
        read(4) // element_instance_tag
        read(2) // object_type
        read(4) // sampling_frequency_index
        val numFront = read(4)
        val numSide = read(4)
        val numBack = read(4)
        val numLfe = read(2)
        val numAssoc = read(3)
        val numCc = read(4)
        if (numCc < 0) return null
        // Each mixdown flag is followed by its index only when present.
        if (read(1) == 1) read(4) // mono_mixdown_element_number
        if (read(1) == 1) read(4) // stereo_mixdown_element_number
        if (read(1) == 1) read(3) // matrix_mixdown_idx + pseudo_surround_enable
        var channels = 0
        var firstIsCpe = false
        var firstSeen = false
        // front, side and back elements each carry is_cpe + a 4-bit tag;
        // a channel_pair_element is two channels, a single is one.
        for (group in 0 until 3) {
            val count = when (group) {
                0 -> numFront
                1 -> numSide
                else -> numBack
            }
            repeat(count) {
                val isCpe = read(1)
                read(4) // element tag
                if (isCpe < 0) return null
                if (!firstSeen) {
                    firstSeen = true
                    firstIsCpe = isCpe == 1
                }
                channels += if (isCpe == 1) 2 else 1
            }
        }
        repeat(numLfe) {
            read(4) // lfe_element_tag: one channel each
            channels += 1
        }
        repeat(numAssoc) { read(4) } // assoc_data_element_tag: no channels
        repeat(numCc) {
            read(1) // cc_element_is_ind_sw
            read(4) // valid_cc_element_tag
        }
        if (bit > limit) return null
        // byte_align() is relative to the raw_data_block start, which is
        // exactly where [bit] is counted from.
        if (bit and 7 != 0) read(8 - (bit and 7))
        val commentBytes = read(8)
        if (commentBytes < 0) return null
        if (bit + commentBytes * 8 > limit) return null
        bit += commentBytes * 8
        // Spec-guaranteed, asserted anyway: a PCE that did not end on a
        // byte boundary could not be dropped with a byte-wise copy.
        if (bit and 7 != 0) return null
        if (channels <= 0) return null
        return AacPceInfo(channels = channels, lengthBytes = bit shr 3, firstIsCpe = firstIsCpe)
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
        val bsid = (data[off + 5].toInt() shr 3) and 0x1F
        val bsmod = data[off + 5].toInt() and 0x07
        // acmod and lfeon sit behind variable mix-level fields; the
        // whole walk fits inside byte 6 (A/52 5.4.2).
        val acmod = (data[off + 6].toInt() shr 5) and 0x07
        var bit = 3
        if (acmod and 0x01 != 0 && acmod != 1) bit += 2 // cmixlev
        if (acmod and 0x04 != 0) bit += 2 // surmixlev
        if (acmod == 2) bit += 2 // dsurmod
        val lfeon = (data[off + 6].toInt() shr (7 - bit)) and 1
        return EsFrameInfo(
            frameLength = words * 2,
            sampleRate = AC3_SAMPLE_RATES[fscod],
            samplesPerFrame = 1536,
            channels = AC3_ACMOD_CHANNELS[acmod] + lfeon,
            fscod = fscod,
            bsid = bsid,
            bsmod = bsmod,
            acmod = acmod,
            lfeon = lfeon,
            bitrateKbps = bitrate,
        )
    }

    private fun parseEac3Header(data: ByteArray, off: Int): EsFrameInfo? {
        if (off + 6 > data.size || !looksLikeSync(SourceCodec.EAC3, data, off)) return null
        val strmtyp = (data[off + 2].toInt() shr 6) and 0x03
        if (strmtyp == 3) return null
        val frmsiz = ((data[off + 2].toInt() and 0x07) shl 8) or (data[off + 3].toInt() and 0xFF)
        val b4 = data[off + 4].toInt() and 0xFF
        val rawFscod = (b4 shr 6) and 0x03
        val sampleRate: Int
        val blocks: Int
        if (rawFscod == 3) {
            val fscod2 = (b4 shr 4) and 0x03
            if (fscod2 == 3) return null
            sampleRate = AC3_SAMPLE_RATES[fscod2] / 2
            blocks = 6
        } else {
            sampleRate = AC3_SAMPLE_RATES[rawFscod]
            blocks = EAC3_BLOCKS[(b4 shr 4) and 0x03]
        }
        val acmod = (b4 shr 1) and 0x07
        val lfeon = b4 and 0x01
        val bsid = (data[off + 5].toInt() shr 3) and 0x1F
        val bsmod = data[off + 5].toInt() and 0x07
        val frameLength = (frmsiz + 1) * 2
        val samplesPerFrame = blocks * 256
        // dec3 data_rate: nominal kbit/s implied by this frame's size.
        val bitrateKbps = if (samplesPerFrame > 0) {
            frameLength * 8L * sampleRate / samplesPerFrame / 1000L
        } else {
            0L
        }
        return EsFrameInfo(
            frameLength = frameLength,
            sampleRate = sampleRate,
            samplesPerFrame = samplesPerFrame,
            channels = AC3_ACMOD_CHANNELS[acmod] + lfeon,
            fscod = rawFscod,
            bsid = bsid,
            bsmod = bsmod,
            acmod = acmod,
            lfeon = lfeon,
            bitrateKbps = bitrateKbps.toInt(),
        )
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
        return EsFrameInfo(
            frameLength = frameLen,
            sampleRate = sampleRate,
            samplesPerFrame = samples,
            channels = channels,
            bitrateKbps = bitrate,
        )
    }
}
