package com.aeriotv.android.core.cast.multiview

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Converts a tile's decoded PCM (16-bit or float, any channel count, any
 * rate) to the composite's fixed audio shape: interleaved 16-bit stereo at
 * [OUT_RATE]. Multichannel is folded to stereo (front pair plus center and
 * the surround pair at -3 dB), mono is duplicated, and other rates are
 * linearly resampled. Streaming: state carries across calls until [reset]
 * (a format change or a focus switch). Pure; unit tested.
 */
class CompositePcmNormalizer {
    companion object {
        const val OUT_RATE = 48_000
        const val OUT_CHANNELS = 2
        /** media3 C.ENCODING_PCM_16BIT / C.ENCODING_PCM_FLOAT. */
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_FLOAT = 4
        private const val MINUS_3DB = 0.7071f
    }

    private var inRate = 0
    private var inChannels = 0
    private var encoding = 0
    // Resampler state: the last input frame and the read position, in input
    // frames, relative to that frame (index -1).
    private var prevL = 0f
    private var prevR = 0f
    private var havePrev = false
    private var pos = 0.0

    fun reset() {
        havePrev = false
        pos = 0.0
    }

    /** Converts [bytes] (native little-endian PCM). Returns interleaved
     *  stereo samples at [OUT_RATE]; empty for an unsupported encoding. */
    fun convert(bytes: ByteArray, sampleRate: Int, channels: Int, pcmEncoding: Int): ShortArray {
        if (sampleRate != inRate || channels != inChannels || pcmEncoding != encoding) {
            inRate = sampleRate
            inChannels = channels
            encoding = pcmEncoding
            reset()
        }
        if (channels <= 0 || sampleRate <= 0) return ShortArray(0)
        val bytesPerSample = when (pcmEncoding) {
            ENCODING_PCM_16BIT -> 2
            ENCODING_PCM_FLOAT -> 4
            else -> return ShortArray(0)
        }
        val frames = bytes.size / (bytesPerSample * channels)
        if (frames == 0) return ShortArray(0)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val s = FloatArray(channels)
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                s[c] = if (bytesPerSample == 2) buf.short.toFloat() / 32768f else buf.float
            }
            if (channels == 1) {
                left[f] = s[0]; right[f] = s[0]
            } else {
                var l = s[0]
                var r = s[1]
                var norm = 1f
                if (channels >= 3) { l += MINUS_3DB * s[2]; r += MINUS_3DB * s[2]; norm += MINUS_3DB }
                if (channels >= 6) { l += MINUS_3DB * s[4]; r += MINUS_3DB * s[5]; norm += MINUS_3DB }
                left[f] = l / norm; right[f] = r / norm
            }
        }
        return if (sampleRate == OUT_RATE) interleave(left, right, frames) else resample(left, right, frames)
    }

    private fun interleave(l: FloatArray, r: FloatArray, n: Int): ShortArray {
        val out = ShortArray(n * 2)
        for (i in 0 until n) { out[2 * i] = toShort(l[i]); out[2 * i + 1] = toShort(r[i]) }
        if (n > 0) { prevL = l[n - 1]; prevR = r[n - 1]; havePrev = true }
        return out
    }

    private fun resample(l: FloatArray, r: FloatArray, n: Int): ShortArray {
        val step = inRate.toDouble() / OUT_RATE
        if (!havePrev) { prevL = l[0]; prevR = r[0]; havePrev = true; pos = 1.0 }
        val out = ShortArray(((n + 1) / step).toInt() * 2 + 4)
        var w = 0
        // pos is measured with index 0 = prev, index k = input k-1.
        while (true) {
            val i = pos.toInt()
            if (i + 1 > n) break
            val frac = (pos - i).toFloat()
            val al = if (i == 0) prevL else l[i - 1]
            val ar = if (i == 0) prevR else r[i - 1]
            val bl = l[i]
            val br = r[i]
            if (w + 1 >= out.size) break
            out[w++] = toShort(al + (bl - al) * frac)
            out[w++] = toShort(ar + (br - ar) * frac)
            pos += step
        }
        pos -= n
        prevL = l[n - 1]; prevR = r[n - 1]
        return out.copyOf(w)
    }

    private fun toShort(v: Float): Short = (v.coerceIn(-1f, 0.99997f) * 32768f).toInt().toShort()
}

/**
 * In-process byte pipe from the compositor's TS muxer to the cast proxy's
 * ingest loop, which reads it as it would a provider socket. Writes never
 * block; a reader that falls more than [MAX_BUFFERED_BYTES] behind loses
 * the newest bytes (the remuxer re-syncs at the next key frame, which
 * carries PAT, PMT, SPS and PPS). [close] ends the stream (read returns -1).
 */
class LocalTsPipe : InputStream() {
    companion object {
        const val MAX_BUFFERED_BYTES = 16L * 1024 * 1024
    }

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val buffered = AtomicLong(0)
    @Volatile private var closed = false
    private var current: ByteArray? = null
    private var currentPos = 0
    val droppedBytes = AtomicLong(0)

    fun write(bytes: ByteArray) {
        if (closed) return
        if (buffered.get() + bytes.size > MAX_BUFFERED_BYTES) {
            droppedBytes.addAndGet(bytes.size.toLong())
            return
        }
        buffered.addAndGet(bytes.size.toLong())
        queue.offer(bytes)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var cur = current
        while (cur == null || currentPos >= cur.size) {
            if (closed && queue.isEmpty()) return -1
            cur = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                throw IOException("interrupted", e)
            }
            if (cur != null) {
                buffered.addAndGet(-cur.size.toLong())
                current = cur
                currentPos = 0
            }
        }
        val n = minOf(len, cur.size - currentPos)
        System.arraycopy(cur, currentPos, b, off, n)
        currentPos += n
        return n
    }

    override fun close() {
        closed = true
    }
}
