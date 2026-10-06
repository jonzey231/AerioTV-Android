package com.aeriotv.android.core.cast.multiview

import com.aeriotv.android.core.cast.hlsproxy.TsToFmp4Remuxer
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composite's MPEG-TS (as the compositor writes it: encoder Annex-B
 * output with SPS/PPS kept aside, AUD + parameter sets added on key frames,
 * ADTS AAC from the audio encoder) fed through the cast proxy's
 * [TsToFmp4Remuxer], the same sample-input contract a channel uses.
 */
class MultiviewCompositeRemuxTest {

    private class Capture : TsToFmp4Remuxer.Listener {
        var initCount = 0
        var audioInit: ByteArray? = null
        val durations = ArrayList<Long>()
        val segments = ArrayList<ByteArray>()
        val audioSegments = ArrayList<ByteArray?>()
        override fun onInitSegments(video: ByteArray, audio: ByteArray?) {
            initCount++
            audioInit = audio
        }
        override fun onMediaSegment(video: ByteArray, audio: ByteArray?, videoDurationTicks: Long, audioDurationTicks: Long) {
            segments.add(video)
            audioSegments.add(audio)
            durations.add(videoDurationTicks)
        }
    }

    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x4D, 0x00, 0x1F) + ByteArray(8) { (it + 1).toByte() }
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())
    private val frameTicks = 3_000L // 30 fps
    private val aacTicks = 1_920L // 1024 samples at 48 kHz

    private fun slice(key: Boolean) =
        byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41) + ByteArray(200) { (it * 7).toByte() }

    /** Compositor key schedule: encoder interval 2 s, forced IDR every 3 s. */
    private fun isKey(frame: Int): Boolean = frame % 60 == 0 || frame % 90 == 0

    private fun tfdt(seg: ByteArray): Long {
        for (i in 0..seg.size - 8) {
            if (String(seg, i + 4, 4, Charsets.US_ASCII) == "tfdt") {
                var v = 0L
                for (b in 0 until 8) v = (v shl 8) or (seg[i + 12 + b].toLong() and 0xFF)
                return v
            }
        }
        error("no tfdt")
    }

    /** First sample's flags in the video traf's trun (or the tfhd/trun
     *  first-sample-flags): bit 0x00010000 = non-sync sample. */
    private fun firstSampleIsSync(seg: ByteArray): Boolean {
        for (i in 0..seg.size - 8) {
            if (String(seg, i + 4, 4, Charsets.US_ASCII) == "trun") {
                val flags = ((seg[i + 9].toInt() and 0xFF) shl 16) or ((seg[i + 10].toInt() and 0xFF) shl 8) or (seg[i + 11].toInt() and 0xFF)
                var p = i + 16 // after size, type, version/flags, sample_count
                if (flags and 0x1 != 0) p += 4 // data_offset
                if (flags and 0x4 != 0) {
                    val f = ((seg[p].toInt() and 0xFF) shl 24) or ((seg[p + 1].toInt() and 0xFF) shl 16)
                    return f and 0x00010000 == 0
                }
                // Per-sample flags: first sample entry.
                if (flags and 0x100 != 0) p += 4
                if (flags and 0x200 != 0) p += 4
                if (flags and 0x400 != 0) {
                    val f = ((seg[p].toInt() and 0xFF) shl 24) or ((seg[p + 1].toInt() and 0xFF) shl 16)
                    return f and 0x00010000 == 0
                }
                return true
            }
        }
        error("no trun")
    }

    private fun feed(seconds: Int, startTicks: Long = CompositeClock.BASE_TICKS): Pair<Capture, List<Long>> {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(cap)
        val ts = ByteArrayOutputStream()
        val muxer = MultiviewTsMuxer { ts.write(it) }
        val keyTicks = ArrayList<Long>()
        val frames = seconds * 30 + 1
        var audioPts = startTicks - 4 * aacTicks // audio leads video a little, as the tap does
        for (f in 0 until frames) {
            val pts = startTicks + f * frameTicks
            while (audioPts <= pts + 3 * aacTicks) {
                muxer.writeAudio(Adts.wrap(ByteArray(64) { 1 }, 48_000, 2), audioPts)
                audioPts += aacTicks
            }
            val key = isKey(f)
            if (key) keyTicks.add(pts)
            muxer.writeVideo(slice(key), pts, key, sps + pps)
            val bytes = ts.toByteArray()
            ts.reset()
            remuxer.feed(bytes, 0, bytes.size)
        }
        return cap to keyTicks
    }

    @Test
    fun `every segment starts on a key frame at the 3 s cadence`() {
        val (cap, keys) = feed(seconds = 13)
        assertEquals(1, cap.initCount)
        assertTrue("AAC rendition present", cap.audioInit != null)
        assertTrue("at least 4 segments", cap.segments.size >= 4)
        var start = CompositeClock.BASE_TICKS
        for ((i, d) in cap.durations.withIndex()) {
            assertTrue("segment $i starts on a key frame", start in keys)
            assertTrue("segment $i first sample is sync", firstSampleIsSync(cap.segments[i]))
            assertEquals("segment $i is 3 s", 3 * 90_000L, d)
            start += d
        }
    }

    @Test
    fun `segment timeline is continuous from zero`() {
        val (cap, _) = feed(seconds = 13)
        var expected = 0L
        for (i in cap.segments.indices) {
            assertEquals("tfdt of segment $i", expected, tfdt(cap.segments[i]))
            expected += cap.durations[i]
        }
        // Audio is cut on the same boundaries and is never empty.
        cap.audioSegments.forEachIndexed { i, a -> assertTrue("audio in segment $i", a != null && a.size > 100) }
    }

    @Test
    fun `key frames carry AUD and in-band SPS and PPS once`() {
        val out = ByteArrayOutputStream()
        val muxer = MultiviewTsMuxer { out.write(it) }
        muxer.writeVideo(slice(true), 900_000L, true, sps + pps)
        val ts = out.toByteArray()
        assertEquals(0, ts.size % 188)
        assertTrue(ts.indices.step(188).all { ts[it] == 0x47.toByte() })
        // PAT, PMT, then video.
        assertEquals(0, ((ts[1].toInt() and 0x1F) shl 8) or (ts[2].toInt() and 0xFF))
        assertEquals(MultiviewTsMuxer.PMT_PID, ((ts[189].toInt() and 0x1F) shl 8) or (ts[190].toInt() and 0xFF))
        // The standard single-program PAT (tsid 1, PMT 0x1000) CRC.
        val patCrc = ((ts[17].toLong() and 0xFF) shl 24) or ((ts[18].toLong() and 0xFF) shl 16) or
            ((ts[19].toLong() and 0xFF) shl 8) or (ts[20].toLong() and 0xFF)
        assertEquals(0x2AB104B2L, patCrc)
        // An encoder key frame that already has its SPS is not doubled.
        val au = sps + pps + slice(true)
        assertEquals(listOf(7, 8, 5), MultiviewTsMuxer.nalTypes(au))
    }
}
