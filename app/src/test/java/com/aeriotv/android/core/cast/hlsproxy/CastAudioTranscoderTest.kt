package com.aeriotv.android.core.cast.hlsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/**
 * Pure-JVM checks for the transcode stage's testable core: the stereo
 * downmix coefficients, the elementary-stream frame-header parsers
 * (AC-3, E-AC-3, MPEG audio), and the AAC output PTS mapper. The
 * MediaCodec plumbing itself cannot run on the JVM and is
 * device-verified (AC-3 channel cast to the web receiver).
 */
class CastAudioTranscoderTest {

    // ---- downmix ----

    @Test
    fun `stereo passes through untouched`() {
        val pcm = shortArrayOf(100, -200, 300, -400)
        assertTrue(CastAudioTranscoder.downmixToStereo(pcm, 2) === pcm)
    }

    @Test
    fun `mono duplicates to both channels`() {
        val out = CastAudioTranscoder.downmixToStereo(shortArrayOf(1234, -5678), 1)
        assertEquals(listOf<Short>(1234, 1234, -5678, -5678), out.toList())
    }

    @Test
    fun `5_1 downmix applies the documented coefficients`() {
        // FL FR C LFE SL SR: LFE must be dropped, C and surrounds at 0.707.
        val out = CastAudioTranscoder.downmixToStereo(
            shortArrayOf(1000, 2000, 1000, 30000, 1000, 2000), 6,
        )
        // L = 1000 + 707 + 707, R = 2000 + 707 + 1414
        assertEquals(2414, out[0].toInt())
        assertEquals(4121, out[1].toInt())
    }

    @Test
    fun `downmix clamps to 16-bit`() {
        val out = CastAudioTranscoder.downmixToStereo(
            shortArrayOf(32000, -32000, 32000, 0, 32000, -32000), 6,
        )
        assertEquals(32767, out[0].toInt())
        // R = -32000 + 22624 - 22624 = -32000: inside range, untouched.
        assertEquals(-32000, out[1].toInt())
    }

    // ---- frame header parsers ----

    @Test
    fun `ac3 stereo header parses`() {
        val hdr = byteArrayOf(0x0B, 0x77, 0, 0, 0x00, 0x40, 0x40) // 48k, 32 kbps, acmod 2, lfeon 0
        val info = CastAudioTranscoder.parseFrameHeader(CastAudioTranscoder.SourceCodec.AC3, hdr, 0)!!
        assertEquals(128, info.frameLength) // 64 words
        assertEquals(48_000, info.sampleRate)
        assertEquals(1536, info.samplesPerFrame)
        assertEquals(2, info.channels)
    }

    @Test
    fun `ac3 5_1 header parses channels past the mix-level fields`() {
        // fscod 0, frmsizecod 28 (384 kbps -> 768 words); byte 6:
        // acmod 7 (3/2), cmixlev 00, surmixlev 00, lfeon 1.
        val hdr = byteArrayOf(0x0B, 0x77, 0, 0, 0x1C, 0x40, 0xE1.toByte())
        val info = CastAudioTranscoder.parseFrameHeader(CastAudioTranscoder.SourceCodec.AC3, hdr, 0)!!
        assertEquals(1536, info.frameLength)
        assertEquals(6, info.channels)
    }

    @Test
    fun `eac3 header parses`() {
        // strmtyp 0, substreamid 0, frmsiz 511 -> 1024 B; byte 4: fscod 0
        // (48 kHz), numblkscod 3 (6 blocks), acmod 7, lfeon 1.
        val hdr = byteArrayOf(0x0B, 0x77, 0x01, 0xFF.toByte(), 0x3F, 0x00)
        val info = CastAudioTranscoder.parseFrameHeader(CastAudioTranscoder.SourceCodec.EAC3, hdr, 0)!!
        assertEquals(1024, info.frameLength)
        assertEquals(48_000, info.sampleRate)
        assertEquals(1536, info.samplesPerFrame)
        assertEquals(6, info.channels)
    }

    @Test
    fun `mp2 header parses`() {
        // MPEG-1 layer II, 256 kbps, 48 kHz, no padding, stereo.
        val hdr = byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0xC4.toByte(), 0x00)
        val info = CastAudioTranscoder.parseFrameHeader(CastAudioTranscoder.SourceCodec.MP2, hdr, 0)!!
        assertEquals(768, info.frameLength) // 144 * 256000 / 48000
        assertEquals(48_000, info.sampleRate)
        assertEquals(1152, info.samplesPerFrame)
        assertEquals(2, info.channels)
    }

    @Test
    fun `garbage is not a frame header`() {
        val junk = ByteArray(16) { (it * 17).toByte() }
        for (codec in CastAudioTranscoder.SourceCodec.entries) {
            assertNull(CastAudioTranscoder.parseFrameHeader(codec, junk, 0))
        }
    }

    // ---- PTS mapper ----

    @Test
    fun `mapper regularizes jitter onto the anchor ladder`() {
        val mapper = CastAudioTranscoder.AacPtsMapper(48_000) // 1024 samples = 1920 ticks
        assertEquals(1000L, mapper.map(1000))
        assertEquals(2920L, mapper.map(1000 + 1920 + 7)) // jitter absorbed
        assertEquals(4840L, mapper.map(1000 + 2 * 1920 - 12))
    }

    @Test
    fun `mapper re-anchors past the discontinuity threshold`() {
        val mapper = CastAudioTranscoder.AacPtsMapper(48_000)
        mapper.map(1000)
        mapper.map(1000 + 1920)
        val spliced = 1000 + 2 * 1920 + 90_000L // 1 s jump
        assertEquals(spliced, mapper.map(spliced))
        assertEquals(spliced + 1920, mapper.map(spliced + 1920))
    }

    @Test
    fun `mapper reset re-anchors on the next stamp`() {
        val mapper = CastAudioTranscoder.AacPtsMapper(48_000)
        mapper.map(1000)
        mapper.reset()
        assertEquals(5000L, mapper.map(5000))
    }

    @Test
    fun `44_1 kHz ladder does not drift against the exact rational`() {
        // 1024 / 44100 s is not a whole tick count; the ladder must be
        // computed from the anchor, not by accumulating a rounded step.
        val mapper = CastAudioTranscoder.AacPtsMapper(44_100)
        val anchor = 123_456L
        val exactTicksPerFrame = 1024.0 * 90_000.0 / 44_100.0
        for (n in 0 until 10_000) {
            val encoderPts = anchor + (n * exactTicksPerFrame).roundToLong()
            val mapped = mapper.map(encoderPts)
            val expected = anchor + n * 1024L * 90_000L / 44_100L
            assertEquals("frame $n", expected, mapped)
        }
    }
}
