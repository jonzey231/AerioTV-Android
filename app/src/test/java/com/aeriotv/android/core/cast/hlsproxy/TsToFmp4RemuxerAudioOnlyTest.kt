package com.aeriotv.android.core.cast.hlsproxy

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audio-only cast (GH AerioTV#90): a TS program with audio and no video
 * PID (or a video PID that never sends parameter sets within 3 s of audio)
 * is served as a single audio rendition: an audio-only init, segments cut
 * on AAC frame boundaries about every 2 s with a continuous tfdt, and a
 * master playlist whose only variant is the audio media playlist. A
 * program WITH video keeps the demuxed two-rendition output.
 */
class TsToFmp4RemuxerAudioOnlyTest {

    private class Capture : TsToFmp4Remuxer.Listener {
        var videoInit: ByteArray? = null
        var demuxedAudioInit: ByteArray? = null
        var demuxedInitCount = 0
        var audioOnlyInit: ByteArray? = null
        var audioOnlyInitCount = 0
        val audioOnlySegments = ArrayList<ByteArray>()
        val audioOnlyDurations = ArrayList<Long>()
        val demuxedSegments = ArrayList<ByteArray>()
        override fun onInitSegments(video: ByteArray, audio: ByteArray?) {
            videoInit = video
            demuxedAudioInit = audio
            demuxedInitCount++
        }
        override fun onMediaSegment(
            video: ByteArray,
            audio: ByteArray?,
            videoDurationTicks: Long,
            audioDurationTicks: Long,
        ) {
            demuxedSegments.add(video)
        }
        override fun onAudioOnlyInit(audio: ByteArray) {
            audioOnlyInit = audio
            audioOnlyInitCount++
        }
        override fun onAudioOnlySegment(audio: ByteArray, durationTicks: Long) {
            audioOnlySegments.add(audio)
            audioOnlyDurations.add(durationTicks)
        }
    }

    // ---- fixture: TS crafting (same shapes as TsToFmp4RemuxerTest) ----

    private val continuity = HashMap<Int, Int>()

    private fun tsPacket(pid: Int, payload: ByteArray, pusi: Boolean): ByteArray {
        val cc = continuity.getOrDefault(pid, 0)
        continuity[pid] = (cc + 1) and 0x0F
        val pkt = ByteArray(188)
        pkt[0] = 0x47
        pkt[1] = ((if (pusi) 0x40 else 0x00) or ((pid shr 8) and 0x1F)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        if (payload.size == 184) {
            pkt[3] = (0x10 or cc).toByte()
            System.arraycopy(payload, 0, pkt, 4, 184)
        } else {
            pkt[3] = (0x30 or cc).toByte()
            val afLen = 183 - payload.size
            pkt[4] = afLen.toByte()
            if (afLen > 0) {
                pkt[5] = 0x00
                for (i in 6 until 5 + afLen) pkt[i] = 0xFF.toByte()
            }
            System.arraycopy(payload, 0, pkt, 5 + afLen, payload.size)
        }
        return pkt
    }

    private fun packetize(pid: Int, bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var off = 0
        var first = true
        while (off < bytes.size) {
            val n = minOf(184, bytes.size - off)
            out.write(tsPacket(pid, bytes.copyOfRange(off, off + n), first))
            first = false
            off += n
        }
        return out.toByteArray()
    }

    private fun patPacket(): ByteArray {
        val section = byteArrayOf(
            0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, 0xE1.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        return tsPacket(0x0000, byteArrayOf(0x00) + section, pusi = true)
    }

    /** PMT with an optional H.264 stream on PID 0x101 and ADTS AAC on 0x102. */
    private fun pmtPacket(withVideo: Boolean): ByteArray {
        val streams = ByteArrayOutputStream().apply {
            if (withVideo) { write(0x1B); write(0xE1); write(0x01); write(0xF0); write(0x00) }
            write(0x0F); write(0xE1); write(0x02); write(0xF0); write(0x00)
        }.toByteArray()
        val sectionLen = 9 + streams.size + 4
        val section = ByteArrayOutputStream().apply {
            write(0x02); write(0xB0); write(sectionLen)
            write(0x00); write(0x01); write(0xC1); write(0x00); write(0x00)
            write(0xE1); write(0x02) // PCR PID
            write(0xF0); write(0x00)
            write(streams)
            write(ByteArray(4))
        }.toByteArray()
        return tsPacket(0x0100, byteArrayOf(0x00) + section, pusi = true)
    }

    private fun ptsBytes(marker: Int, ts: Long): ByteArray = byteArrayOf(
        ((marker shl 4) or (((ts shr 30) and 0x07).toInt() shl 1) or 1).toByte(),
        ((ts shr 22) and 0xFF).toByte(),
        ((((ts shr 15) and 0x7F).toInt() shl 1) or 1).toByte(),
        ((ts shr 7) and 0xFF).toByte(),
        (((ts and 0x7F).toInt() shl 1) or 1).toByte(),
    )

    private fun pes(streamId: Int, payload: ByteArray, pts: Long): ByteArray {
        val header = byteArrayOf(0x00, 0x00, 0x01, streamId.toByte(), 0x00, 0x00, 0x80.toByte(), 0x80.toByte(), 5) +
            ptsBytes(0x2, pts)
        return header + payload
    }

    private fun adtsFrame(payloadSize: Int): ByteArray {
        val frameLen = 7 + payloadSize
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), 0x4C, 0x80.toByte(), // LC, 48 kHz, stereo
            ((frameLen shr 3) and 0xFF).toByte(),
            (((frameLen and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        ) + ByteArray(payloadSize) { (it * 3).toByte() }
    }

    private val framesPerPes = 5
    private val aacFrameTicks = 1024L * TsToFmp4Remuxer.TICKS_PER_SECOND / 48_000 // 1920

    private fun audioPes(pts: Long): ByteArray {
        val body = ByteArrayOutputStream().apply { repeat(framesPerPes) { write(adtsFrame(32)) } }.toByteArray()
        return packetize(0x0102, pes(0xC0, body, pts))
    }

    private fun feed(remuxer: TsToFmp4Remuxer, bytes: ByteArray) = remuxer.feed(bytes, 0, bytes.size)

    private fun feedAudio(remuxer: TsToFmp4Remuxer, startPts: Long, pesCount: Int) {
        for (i in 0 until pesCount) {
            feed(remuxer, audioPes(startPts + i * framesPerPes * aacFrameTicks))
        }
    }

    // ---- box helpers ----

    private fun count(data: ByteArray, type: String): Int {
        val t = type.toByteArray(Charsets.US_ASCII)
        var n = 0
        outer@ for (i in 0..data.size - 4) {
            for (j in 0 until 4) if (data[i + j] != t[j]) continue@outer
            n++
        }
        return n
    }

    private fun boxPayloadAfter(data: ByteArray, type: String): Int {
        for (i in 4..data.size - 4) {
            if (String(data, i, 4, Charsets.US_ASCII) == type) return i + 4
        }
        error("no $type")
    }

    private fun u32(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0xFF) shl 24) or ((d[o + 1].toLong() and 0xFF) shl 16) or
            ((d[o + 2].toLong() and 0xFF) shl 8) or (d[o + 3].toLong() and 0xFF)

    /** tfdt version 1: 4 bytes version/flags then a 64-bit time. */
    private fun tfdt(seg: ByteArray): Long {
        val p = boxPayloadAfter(seg, "tfdt") + 4
        return (u32(seg, p) shl 32) or u32(seg, p + 4)
    }

    /** trun: version/flags then sample_count. */
    private fun trunSamples(seg: ByteArray): Int = u32(seg, boxPayloadAfter(seg, "trun") + 4).toInt()

    // ---- tests ----

    @Test
    fun `audio-only PMT produces an audio-only init and 2 s frame-aligned segments`() {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(cap)
        feed(remuxer, patPacket() + pmtPacket(withVideo = false) + pmtPacket(withVideo = false))
        // 100 PES x 5 frames = 500 AAC frames = 10.67 s.
        feedAudio(remuxer, startPts = 900_000L, pesCount = 100)

        assertTrue("audio-only mode", remuxer.isAudioOnly)
        assertEquals("no demuxed init", 0, cap.demuxedInitCount)
        assertEquals("audio-only init once", 1, cap.audioOnlyInitCount)
        val init = cap.audioOnlyInit!!
        assertEquals("ftyp", String(init, 4, 4, Charsets.US_ASCII))
        assertEquals("one trak", 1, count(init, "trak"))
        assertEquals("mp4a sample entry", 1, count(init, "mp4a"))
        assertEquals("esds", 1, count(init, "esds"))
        assertEquals("no video sample entry", 0, count(init, "avcC"))
        assertEquals("no video handler", 0, count(init, "vide"))

        // ceil(2 s / 1920 ticks) = 94 frames per segment.
        val framesPerSegment = 94
        assertEquals(500 / framesPerSegment, cap.audioOnlySegments.size)
        var expectedTfdt = 0L
        for ((i, seg) in cap.audioOnlySegments.withIndex()) {
            assertEquals("moof", String(seg, 4, 4, Charsets.US_ASCII))
            assertEquals("segment $i is audio-only", 0, count(seg, "avcC"))
            assertEquals("segment $i samples", framesPerSegment, trunSamples(seg))
            assertEquals("segment $i duration", framesPerSegment * aacFrameTicks, cap.audioOnlyDurations[i])
            assertEquals("segment $i tfdt is continuous", expectedTfdt, tfdt(seg))
            expectedTfdt += cap.audioOnlyDurations[i]
        }
        assertTrue("no demuxed segments", cap.demuxedSegments.isEmpty())

        // release() flushes the frames not yet cut as one last short segment.
        val before = cap.audioOnlySegments.size
        remuxer.release()
        assertEquals(before + 1, cap.audioOnlySegments.size)
        // The final PES is still open (a PES completes at the next PUSI), so
        // its 5 frames are not part of the tail.
        assertEquals(500 - framesPerPes - before * framesPerSegment, trunSamples(cap.audioOnlySegments.last()))
        assertEquals(expectedTfdt, tfdt(cap.audioOnlySegments.last()))
    }

    @Test
    fun `silent video PID falls back to audio-only after 3 s of audio`() {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(cap)
        feed(remuxer, patPacket() + pmtPacket(withVideo = true) + pmtPacket(withVideo = true))
        // 2.9 s of audio: still waiting for video.
        feedAudio(remuxer, startPts = 0L, pesCount = 28) // 27 x 9600 = 2.88 s span
        assertFalse(remuxer.isAudioOnly)
        assertNull(cap.audioOnlyInit)
        // Past 3 s with no SPS: the video PID is dropped.
        feedAudio(remuxer, startPts = 28L * framesPerPes * aacFrameTicks, pesCount = 60)
        assertTrue(remuxer.isAudioOnly)
        assertNotNull(cap.audioOnlyInit)
        assertTrue(cap.audioOnlySegments.isNotEmpty())
        assertEquals(0L, tfdt(cap.audioOnlySegments.first()))
    }

    @Test
    fun `pinned video session never falls back to audio-only`() {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(cap, audioOnlyPolicy = TsToFmp4Remuxer.AudioOnlyPolicy.NEVER)
        feed(remuxer, patPacket() + pmtPacket(withVideo = true) + pmtPacket(withVideo = true))
        feedAudio(remuxer, startPts = 0L, pesCount = 80)
        assertFalse(remuxer.isAudioOnly)
        assertNull(cap.audioOnlyInit)
    }

    @Test
    fun `PMT with video keeps the demuxed two-rendition output`() {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(cap)
        feed(remuxer, patPacket() + pmtPacket(withVideo = true) + pmtPacket(withVideo = true))
        val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E) + ByteArray(8) { (it + 1).toByte() }
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        fun annexB(vararg nals: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            for (nal in nals) { out.write(byteArrayOf(0, 0, 0, 1)); out.write(nal) }
            return out.toByteArray()
        }
        feed(remuxer, audioPes(0L))
        for (f in 0 until 121) {
            val pts = 9_600L + f * 3_000L
            val key = f % 30 == 0
            val slice = (if (key) byteArrayOf(0x65) else byteArrayOf(0x41)) + ByteArray(64) { (it * 7).toByte() }
            val payload = if (key) annexB(sps, pps, slice) else annexB(slice)
            feed(remuxer, packetize(0x0101, pes(0xE0, payload, pts)))
            if (f % 10 == 0) feed(remuxer, audioPes(pts))
        }
        assertFalse(remuxer.isAudioOnly)
        assertEquals(0, cap.audioOnlyInitCount)
        assertEquals(1, cap.demuxedInitCount)
        assertEquals(1, count(cap.videoInit!!, "avcC"))
        assertNotNull(cap.demuxedAudioInit)
        assertTrue(cap.demuxedSegments.isNotEmpty())
        assertTrue(cap.audioOnlySegments.isEmpty())
    }

    @Test
    fun `master playlist renders the audio-only variant`() {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(cap)
        feed(remuxer, patPacket() + pmtPacket(withVideo = false) + pmtPacket(withVideo = false))
        feedAudio(remuxer, startPts = 0L, pesCount = 60)

        val server = CastHlsProxyServer(log = {})
        val gen = server.beginGeneration()
        server.setInitSegments(gen, null, cap.audioOnlyInit!!)
        for ((i, seg) in cap.audioOnlySegments.withIndex()) {
            server.addSegment(gen, null, seg, cap.audioOnlyDurations[i])
        }
        val master = server.demuxedMasterPlaylistText()
        assertEquals(
            "#EXTM3U\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=${CastHlsProxyServer.AUDIO_ONLY_BANDWIDTH},CODECS=\"mp4a.40.2\"\n" +
                "audio.m3u8\n",
            master,
        )
        val media = server.audioPlaylistText()
        assertTrue(media.contains("#EXT-X-MAP:URI=\"ainit$gen.mp4\""))
        assertTrue(media.contains("#EXTINF:2.005,\naseg0.m4s"))
        assertTrue(media.contains("#EXT-X-TARGETDURATION:3"))
    }

    @Test
    fun `master with video still declares the video variant and audio group`() {
        val server = CastHlsProxyServer(log = {})
        val gen = server.beginGeneration()
        server.setInitSegments(gen, byteArrayOf(1), "....mp4a....".toByteArray())
        server.addSegment(gen, byteArrayOf(1), byteArrayOf(2), 3L * TsToFmp4Remuxer.TICKS_PER_SECOND)
        val master = server.demuxedMasterPlaylistText()
        assertTrue(master.contains("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\""))
        assertTrue(master.contains("AUDIO=\"aud\",CLOSED-CAPTIONS=NONE\nvideo.m3u8"))
    }
}
