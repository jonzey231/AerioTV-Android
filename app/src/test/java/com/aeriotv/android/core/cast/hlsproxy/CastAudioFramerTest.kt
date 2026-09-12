package com.aeriotv.android.core.cast.hlsproxy

import org.junit.Assert.assertEquals
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
}
