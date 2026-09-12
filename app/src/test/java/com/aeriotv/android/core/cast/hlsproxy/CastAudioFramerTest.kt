package com.aeriotv.android.core.cast.hlsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM checks for the cast audio framer: the elementary-stream
 * syncframe parsers (AC-3, E-AC-3, MPEG audio) the proxy cuts access
 * units with, and the bitstream fields the AC-3 / E-AC-3 sample entry
 * needs. The phone no longer transcodes cast audio (Logan 2026-09-12),
 * so the old downmix and AAC-encoder PTS-mapper cases are gone with the
 * code they covered.
 */
class CastAudioFramerTest {

    @Test
    fun `ac3 stereo header parses`() {
        val hdr = byteArrayOf(0x0B, 0x77, 0, 0, 0x00, 0x40, 0x40) // 48k, 32 kbps, acmod 2, lfeon 0
        val info = CastAudioFramer.parseFrameHeader(CastAudioFramer.SourceCodec.AC3, hdr, 0)!!
        assertEquals(128, info.frameLength) // 64 words
        assertEquals(48_000, info.sampleRate)
        assertEquals(1536, info.samplesPerFrame)
        assertEquals(2, info.channels)
        // Sample-entry fields: fscod 0, bsid 8, bsmod 0, acmod 2, lfeon 0.
        assertEquals(0, info.fscod)
        assertEquals(8, info.bsid)
        assertEquals(0, info.bsmod)
        assertEquals(2, info.acmod)
        assertEquals(0, info.lfeon)
        assertEquals(32, info.bitrateKbps)
    }

    @Test
    fun `ac3 5_1 header parses channels past the mix-level fields`() {
        // fscod 0, frmsizecod 28 (384 kbps -> 768 words); byte 6:
        // acmod 7 (3/2), cmixlev 00, surmixlev 00, lfeon 1.
        val hdr = byteArrayOf(0x0B, 0x77, 0, 0, 0x1C, 0x40, 0xE1.toByte())
        val info = CastAudioFramer.parseFrameHeader(CastAudioFramer.SourceCodec.AC3, hdr, 0)!!
        assertEquals(1536, info.frameLength)
        assertEquals(6, info.channels)
        assertEquals(7, info.acmod)
        assertEquals(1, info.lfeon)
        assertEquals(384, info.bitrateKbps)
    }

    @Test
    fun `eac3 header parses`() {
        // strmtyp 0, substreamid 0, frmsiz 511 -> 1024 B; byte 4: fscod 0
        // (48 kHz), numblkscod 3 (6 blocks), acmod 7, lfeon 1.
        val hdr = byteArrayOf(0x0B, 0x77, 0x01, 0xFF.toByte(), 0x3F, 0x00)
        val info = CastAudioFramer.parseFrameHeader(CastAudioFramer.SourceCodec.EAC3, hdr, 0)!!
        assertEquals(1024, info.frameLength)
        assertEquals(48_000, info.sampleRate)
        assertEquals(1536, info.samplesPerFrame)
        assertEquals(6, info.channels)
        // dec3 data_rate implied by this frame: 1024 B * 8 * 48000 / 1536.
        assertEquals(256, info.bitrateKbps)
    }

    @Test
    fun `mp2 header parses`() {
        // MPEG-1 layer II, 256 kbps, 48 kHz, no padding, stereo.
        val hdr = byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0xC4.toByte(), 0x00)
        val info = CastAudioFramer.parseFrameHeader(CastAudioFramer.SourceCodec.MP2, hdr, 0)!!
        assertEquals(768, info.frameLength) // 144 * 256000 / 48000
        assertEquals(48_000, info.sampleRate)
        assertEquals(1152, info.samplesPerFrame)
        assertEquals(2, info.channels)
    }

    @Test
    fun `garbage is not a frame header`() {
        val junk = ByteArray(16) { (it * 17).toByte() }
        for (codec in CastAudioFramer.SourceCodec.entries) {
            assertNull(CastAudioFramer.parseFrameHeader(codec, junk, 0))
        }
    }

    @Test
    fun `syncword check distinguishes the families`() {
        val ac3 = byteArrayOf(0x0B, 0x77, 0, 0)
        val mpeg = byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0, 0)
        assertTrue(CastAudioFramer.looksLikeSync(CastAudioFramer.SourceCodec.AC3, ac3, 0))
        assertTrue(CastAudioFramer.looksLikeSync(CastAudioFramer.SourceCodec.EAC3, ac3, 0))
        assertTrue(!CastAudioFramer.looksLikeSync(CastAudioFramer.SourceCodec.MP2, ac3, 0))
        assertTrue(CastAudioFramer.looksLikeSync(CastAudioFramer.SourceCodec.MP2, mpeg, 0))
        assertTrue(!CastAudioFramer.looksLikeSync(CastAudioFramer.SourceCodec.AC3, mpeg, 0))
    }

    // ---- AAC program_config_element ----

    /** The PCE a `-c:a aac -ac 2` encoder emits: one front
     *  channel_pair_element, nothing else, empty comment. */
    private fun stereoPce(freqIndex: Int = 3): ByteArray {
        val bits = StringBuilder()
        fun put(value: Int, width: Int) {
            for (i in width - 1 downTo 0) bits.append((value shr i) and 1)
        }
        put(5, 3) // id_syn_ele = PCE
        put(0, 4) // element_instance_tag
        put(1, 2) // object_type
        put(freqIndex, 4)
        put(1, 4); put(0, 4); put(0, 4) // num_front/side/back
        put(0, 2); put(0, 3); put(0, 4) // num_lfe/assoc_data/valid_cc
        put(0, 1); put(0, 1); put(0, 1) // no mixdowns
        put(1, 1); put(0, 4) // front element is a CPE
        while (bits.length % 8 != 0) bits.append(0) // byte_align()
        put(0, 8) // comment_field_bytes
        return ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
    }

    @Test
    fun `a stereo program config element reports two channels and a byte length`() {
        val pce = stereoPce()
        val block = pce + byteArrayOf(0x21, 0x00, 0x00, 0x00)
        val info = CastAudioFramer.parseAacPce(block, 0, block.size)
        assertNotNull(info)
        assertEquals(2, info!!.channels)
        assertEquals(pce.size, info.lengthBytes)
        assertTrue(info.firstIsCpe)
    }

    /**
     * The property the whole lossless strip rests on: the element's size
     * is a whole number of bytes from the raw_data_block start, so the
     * elements behind it are byte-aligned and copy over verbatim (ISO/IEC
     * 14496-3 4.4.1.1 byte_align() before comment_field_bytes).
     */
    @Test
    fun `a program config element always ends on a byte boundary`() {
        // Vary the element counts so the pre-alignment bit length changes,
        // and a non-empty comment so the trailing bytes are exercised too.
        for (comment in 0..3) {
            for (front in 1..3) {
                val bits = StringBuilder()
                fun put(value: Int, width: Int) {
                    for (i in width - 1 downTo 0) bits.append((value shr i) and 1)
                }
                put(5, 3); put(0, 4); put(1, 2); put(3, 4)
                put(front, 4); put(0, 4); put(0, 4)
                put(1, 2); put(0, 3); put(0, 4) // one LFE
                put(0, 1); put(0, 1); put(0, 1)
                repeat(front) { put(1, 1); put(0, 4) } // every front element a CPE
                put(0, 4) // lfe_element_tag
                while (bits.length % 8 != 0) bits.append(0)
                put(comment, 8)
                repeat(comment) { put(0x41, 8) }
                val pce = ByteArray(bits.length / 8) {
                    bits.substring(it * 8, it * 8 + 8).toInt(2).toByte()
                }
                val block = pce + byteArrayOf(0x21, 0x00, 0x00, 0x00)
                val info = CastAudioFramer.parseAacPce(block, 0, block.size)
                assertNotNull("front=$front comment=$comment parsed", info)
                assertEquals("front=$front comment=$comment length", pce.size, info!!.lengthBytes)
                assertEquals("front=$front comment=$comment channels", front * 2 + 1, info.channels)
            }
        }
    }

    @Test
    fun `a block that does not start with a PCE is reported as absent`() {
        // id_syn_ele 0 is SCE, 1 is CPE, 7 is TERM: none of them a PCE.
        for (synEle in intArrayOf(0, 1, 2, 3, 4, 6, 7)) {
            val block = byteArrayOf((synEle shl 5).toByte(), 0x11, 0x22, 0x33)
            assertNull("syn_ele $synEle", CastAudioFramer.parseAacPce(block, 0, block.size))
        }
    }

    @Test
    fun `a truncated program config element is refused rather than guessed`() {
        val pce = stereoPce()
        for (cut in 1 until pce.size) {
            assertNull("cut at $cut", CastAudioFramer.parseAacPce(pce, 0, cut))
        }
    }
}
