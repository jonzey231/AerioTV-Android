package com.aeriotv.android.core.cast.multiview

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewCompositePcmTest {

    private fun s16(vararg v: Short): ByteArray =
        ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putShort(it) } }.array()

    @Test
    fun `48 kHz stereo passes through unchanged`() {
        val n = CompositePcmNormalizer()
        val out = n.convert(s16(1000, -1000, 2000, -2000), 48_000, 2, CompositePcmNormalizer.ENCODING_PCM_16BIT)
        assertEquals(listOf<Short>(1000, -1000, 2000, -2000), out.toList())
    }

    @Test
    fun `44_1 kHz mono resamples to 48 kHz stereo with the right length across calls`() {
        val n = CompositePcmNormalizer()
        var total = 0
        repeat(10) {
            val chunk = s16(*ShortArray(441) { 8000 })
            total += n.convert(chunk, 44_100, 1, CompositePcmNormalizer.ENCODING_PCM_16BIT).size / 2
        }
        // 4410 input frames = 0.1 s = 4800 output frames (+-1).
        assertTrue("got $total", kotlin.math.abs(total - 4800) <= 1)
    }

    @Test
    fun `5_1 float folds to stereo without clipping`() {
        val n = CompositePcmNormalizer()
        val bb = ByteBuffer.allocate(6 * 4).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f).forEach { bb.putFloat(it) }
        val out = n.convert(bb.array(), 48_000, 6, CompositePcmNormalizer.ENCODING_PCM_FLOAT)
        assertEquals(2, out.size)
        assertTrue(out[0] in 32000..32767)
        assertTrue(out[1] in 32000..32767)
    }

    @Test
    fun `adts header carries length, LC profile, 48 kHz and stereo`() {
        val f = Adts.wrap(ByteArray(100), 48_000, 2)
        assertEquals(107, f.size)
        assertEquals(0xFF, f[0].toInt() and 0xFF)
        assertEquals(1, (f[2].toInt() shr 6) and 3) // LC = object type 2 - 1
        assertEquals(3, (f[2].toInt() shr 2) and 0xF) // 48 kHz
        assertEquals(2, ((f[2].toInt() and 1) shl 2) or ((f[3].toInt() shr 6) and 3))
        val len = ((f[3].toInt() and 3) shl 11) or ((f[4].toInt() and 0xFF) shl 3) or ((f[5].toInt() and 0xFF) shr 5)
        assertEquals(107, len)
    }
}
