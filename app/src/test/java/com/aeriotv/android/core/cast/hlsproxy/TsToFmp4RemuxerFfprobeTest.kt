package com.aeriotv.android.core.cast.hlsproxy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end validation of the remuxer against a REAL transport stream
 * and a real demuxer, added 2026-09-12 after a Google TV Streamer cast of
 * a Dispatcharr `?output_profile=2` channel (server-side `-c:v copy -c:a
 * aac -b:a 192k -ac 2 -f mpegts`) went IDLE/ERROR within a second of the
 * receiver's first playlist fetch. The synthetic-TS tests in
 * [TsToFmp4RemuxerTest] prove the box plumbing is SHAPED right; they
 * cannot prove a player will accept it, because their SPS/PPS and AAC
 * payloads are made up.
 *
 * So: ffmpeg builds a transport stream with exactly the profile's stream
 * types (H.264 in 0x1B, ADTS AAC-LC 48 kHz stereo in 0x0F), the bytes go
 * through [TsToFmp4Remuxer] in 64 KB chunks the way the ingest loop feeds
 * them, and ffprobe is pointed at init + first segment concatenated into
 * one file. Anything a real MSE implementation would refuse (a broken
 * esds, a wrong mdat offset, audio samples with no sync flag) shows up
 * here as a failed probe instead of as a one-second IDLE on the couch.
 *
 * The test SKIPS itself when ffmpeg/ffprobe are absent so CI without
 * Homebrew stays green.
 */
class TsToFmp4RemuxerFfprobeTest {

    private val ffmpeg = File("/opt/homebrew/bin/ffmpeg")
    private val ffprobe = File("/opt/homebrew/bin/ffprobe")

    private val workDir: File by lazy {
        File("build/tmp/castHlsProxyFfprobe").apply { mkdirs() }
    }

    private class Capture : TsToFmp4Remuxer.Listener {
        var init: ByteArray? = null
        val segments = ArrayList<ByteArray>()
        val durations = ArrayList<Long>()
        var audioCodec: String? = null
        override fun onInitSegment(data: ByteArray) { init = data }
        override fun onMediaSegment(data: ByteArray, durationTicks: Long) {
            segments.add(data); durations.add(durationTicks)
        }
        override fun onAudioCodec(name: String) { audioCodec = name }
    }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        return Pair(p.waitFor(), text)
    }

    /** A transport stream shaped like what Dispatcharr's AAC output
     *  profile emits: video copied as H.264, audio re-encoded to AAC-LC
     *  192k stereo 48 kHz, muxed to MPEG-TS (so ADTS in stream_type 0x0F). */
    private fun buildAacTs(): File {
        val out = File(workDir, "aac.ts")
        if (out.isFile && out.length() > 0) return out
        val (code, log) = run(
            ffmpeg.path, "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=20",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=20",
            "-c:v", "libx264", "-preset", "ultrafast", "-g", "60", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-ac", "2", "-ar", "48000",
            "-f", "mpegts", out.path,
        )
        assertEquals("ffmpeg built the AAC fixture: $log", 0, code)
        return out
    }

    /** The AC-3 passthrough fixture: the lineup shape the proxy serves
     *  when the receiver decodes AC-3 and no output profile is used. */
    private fun buildAc3Ts(): File {
        val out = File(workDir, "ac3.ts")
        if (out.isFile && out.length() > 0) return out
        val (code, log) = run(
            ffmpeg.path, "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=20",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=20",
            "-c:v", "libx264", "-preset", "ultrafast", "-g", "60", "-pix_fmt", "yuv420p",
            "-c:a", "ac3", "-b:a", "192k", "-ac", "2", "-ar", "48000",
            "-f", "mpegts", out.path,
        )
        assertEquals("ffmpeg built the AC-3 fixture: $log", 0, code)
        return out
    }

    /** Feed [ts] through the remuxer in 64 KB chunks, exactly the ingest
     *  loop's read size, so chunk-boundary carry is exercised too. */
    private fun remux(ts: File, allowAc3Passthrough: Boolean): Capture {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(
            listener = cap,
            log = {},
            allowAc3Passthrough = allowAc3Passthrough,
        )
        val bytes = ts.readBytes()
        var off = 0
        while (off < bytes.size) {
            val n = minOf(64 * 1024, bytes.size - off)
            remuxer.feed(bytes, off, n)
            off += n
        }
        return cap
    }

    /** init + segment concatenated: a standalone fMP4 any demuxer reads. */
    private fun writePlayable(name: String, cap: Capture, segmentCount: Int): File {
        val out = File(workDir, name)
        out.outputStream().use { os ->
            os.write(cap.init!!)
            for (i in 0 until minOf(segmentCount, cap.segments.size)) os.write(cap.segments[i])
        }
        return out
    }

    private fun probeStreams(file: File): String {
        val (code, text) = run(
            ffprobe.path, "-v", "error", "-show_streams", "-of", "flat", file.path,
        )
        assertEquals("ffprobe read $file: $text", 0, code)
        return text
    }

    /** Decoded audio packets, as "pts|size" lines, so both presence and
     *  monotonicity can be asserted. ffprobe's own stderr is captured
     *  separately: a decode error there is the failure signal. */
    private fun probeAudioPackets(file: File): Pair<List<Long>, String> {
        val p = ProcessBuilder(
            ffprobe.path, "-v", "error",
            "-select_streams", "a:0", "-show_packets",
            "-show_entries", "packet=pts,size,flags",
            "-of", "csv=p=0", file.path,
        ).start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        val pts = out.lineSequence().mapNotNull { it.split(',').firstOrNull()?.trim()?.toLongOrNull() }.toList()
        return Pair(pts, err)
    }

    @Test
    fun `adts aac output profile remuxes to a probeable fmp4 with decodable audio`() {
        assumeTrue("ffmpeg/ffprobe present", ffmpeg.canExecute() && ffprobe.canExecute())
        val cap = remux(buildAacTs(), allowAc3Passthrough = false)
        assertEquals("PMT reports AAC", "AAC", cap.audioCodec)
        assertTrue("init segment emitted", cap.init != null)
        assertTrue("segments produced, got ${cap.segments.size}", cap.segments.size >= 2)

        val file = writePlayable("aac.mp4", cap, segmentCount = 2)
        val streams = probeStreams(file)
        assertTrue("h264 video stream\n$streams", streams.contains("codec_name=\"h264\""))
        assertTrue("aac audio stream\n$streams", streams.contains("codec_name=\"aac\""))
        assertTrue("48 kHz audio\n$streams", streams.contains("sample_rate=\"48000\""))
        assertTrue("stereo audio\n$streams", streams.contains("channels=2"))

        val (pts, err) = probeAudioPackets(file)
        assertTrue("audio packets present", pts.size >= 40)
        assertTrue("no decode errors from ffprobe: $err", err.isBlank())
        for (i in 1 until pts.size) {
            assertTrue("audio pts monotonic at $i: ${pts[i - 1]} then ${pts[i]}", pts[i] > pts[i - 1])
        }
    }

    @Test
    fun `ac3 passthrough remuxes to a probeable fmp4 with decodable audio`() {
        assumeTrue("ffmpeg/ffprobe present", ffmpeg.canExecute() && ffprobe.canExecute())
        val cap = remux(buildAc3Ts(), allowAc3Passthrough = true)
        assertEquals("PMT reports AC-3", "AC-3", cap.audioCodec)
        assertTrue("init segment emitted", cap.init != null)
        assertTrue("segments produced, got ${cap.segments.size}", cap.segments.size >= 2)

        val file = writePlayable("ac3.mp4", cap, segmentCount = 2)
        val streams = probeStreams(file)
        assertTrue("h264 video stream\n$streams", streams.contains("codec_name=\"h264\""))
        assertTrue("ac3 audio stream\n$streams", streams.contains("codec_name=\"ac3\""))
        assertTrue("48 kHz audio\n$streams", streams.contains("sample_rate=\"48000\""))

        val (pts, err) = probeAudioPackets(file)
        assertTrue("audio packets present", pts.size >= 40)
        assertTrue("no decode errors from ffprobe: $err", err.isBlank())
        for (i in 1 until pts.size) {
            assertTrue("audio pts monotonic at $i: ${pts[i - 1]} then ${pts[i]}", pts[i] > pts[i - 1])
        }
    }

    /**
     * Every audio sample must be a sync sample. The track's trun carries
     * no sample-flags, so the flags come from trex default_sample_flags,
     * and a non-sync default there means the audio track advertises no
     * random access point at all: Chromium's MP4 parser then has nothing
     * to start an audio append at. Asserted on the bytes rather than via
     * ffprobe because ffmpeg is forgiving about it and Chromium is not.
     */
    @Test
    fun `audio trex default sample flags mark sync samples`() {
        assumeTrue("ffmpeg/ffprobe present", ffmpeg.canExecute() && ffprobe.canExecute())
        val init = remux(buildAacTs(), allowAc3Passthrough = false).init!!
        val flags = trexDefaultSampleFlags(init)
        assertEquals("one trex per track", 2, flags.size)
        val audioFlags = flags.getValue(2)
        assertEquals(
            "audio default_sample_flags declares a sync sample (non_sync bit clear)",
            0,
            audioFlags and 0x00010000,
        )
    }

    /**
     * Audio must be GAPLESS and non-overlapping across segment
     * boundaries: each segment's audio tfdt has to equal the previous
     * segment's tfdt plus the sum of its trun sample durations, because
     * that sum is the only timeline a player derives (the trun carries no
     * per-sample decode times). This is the check that caught the
     * 2026-09-12 cast failure: segment 1 declared its audio from tfdt 0
     * spanning 355200 ticks while segment 2's audio tfdt was 348000, an
     * 80 ms backwards append one segment into playback.
     */
    @Test
    fun `audio timeline is continuous across segment boundaries`() {
        assumeTrue("ffmpeg/ffprobe present", ffmpeg.canExecute() && ffprobe.canExecute())
        val cap = remux(buildAacTs(), allowAc3Passthrough = false)
        assertTrue("several segments to chain, got ${cap.segments.size}", cap.segments.size >= 3)
        var expected = -1L
        for ((index, seg) in cap.segments.withIndex()) {
            val (tfdt, count, span) = audioTrafInfo(seg)
                ?: error("segment $index has no audio traf")
            assertTrue("segment $index carries audio samples", count > 0)
            if (expected >= 0) {
                assertEquals("audio tfdt of segment $index continues the previous", expected, tfdt)
            }
            expected = tfdt + span
        }
    }

    /** tfdt, sample count and summed sample durations of a segment's
     *  audio traf (the second traf; video is written first). */
    private fun audioTrafInfo(seg: ByteArray): Triple<Long, Int, Long>? {
        var i = 0
        while (i + 8 <= seg.size) {
            val size = be32(seg, i)
            if (size <= 0) return null
            if (String(seg, i + 4, 4, Charsets.US_ASCII) == "moof") {
                val trafs = ArrayList<Pair<Int, Int>>()
                var j = i + 8
                while (j + 8 <= i + size) {
                    val s = be32(seg, j)
                    if (s <= 0) break
                    if (String(seg, j + 4, 4, Charsets.US_ASCII) == "traf") trafs.add(Pair(j, s))
                    j += s
                }
                if (trafs.size < 2) return null
                val (off, sz) = trafs[1]
                var tfdt = -1L
                var count = 0
                var span = 0L
                var k = off + 8
                while (k + 8 <= off + sz) {
                    val s = be32(seg, k)
                    if (s <= 0) break
                    when (String(seg, k + 4, 4, Charsets.US_ASCII)) {
                        "tfdt" -> {
                            var v = 0L
                            for (b in 0 until 8) v = (v shl 8) or (seg[k + 12 + b].toLong() and 0xFF)
                            tfdt = v
                        }
                        // trun: version/flags(4) sample_count(4) data_offset(4)
                        // then 8 bytes per sample (duration, size).
                        "trun" -> {
                            count = be32(seg, k + 12)
                            var q = k + 20
                            repeat(count) { span += be32(seg, q).toLong(); q += 8 }
                        }
                    }
                    k += s
                }
                return Triple(tfdt, count, span)
            }
            i += size
        }
        return null
    }

    /** trackId to default_sample_flags for every trex in an init segment. */
    private fun trexDefaultSampleFlags(init: ByteArray): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        var i = 0
        while (i + 28 <= init.size) {
            if (init[i] == 't'.code.toByte() && init[i + 1] == 'r'.code.toByte() &&
                init[i + 2] == 'e'.code.toByte() && init[i + 3] == 'x'.code.toByte()
            ) {
                // trex body: version/flags(4) track_ID(4) default_sample_description_index(4)
                // default_sample_duration(4) default_sample_size(4) default_sample_flags(4)
                val trackId = be32(init, i + 8)
                out[trackId] = be32(init, i + 24)
                i += 28
            } else {
                i++
            }
        }
        return out
    }

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
}
