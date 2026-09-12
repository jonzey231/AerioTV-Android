package com.aeriotv.android.core.cast.hlsproxy

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Track continuity across a RUN of segments, added 2026-09-12 after
 * Logan's "launches now but it's frozen" on a Google TV Streamer.
 *
 * The failure was reproduced in a real Chromium MediaSource: the Cast
 * receiver appends our muxed segments in MSE 'sequence' AppendMode
 * (Shaka's HLS default, and Chromium logs the multitrack warning on
 * every one of our loads). In that mode Chromium ignores tfdt and
 * re-anchors each append on the PRESENTATION timestamp of the first
 * coded frame, so two properties of the emitted bytes decide whether
 * the receiver plays or freezes:
 *
 *  1. No audio may sit before the first video presentation time. Audio
 *     that does lands before zero and Chromium drops and truncates it
 *     ("Dropping audio frame (DTS -24000us ...)", "Truncating audio
 *     buffer which overlaps append window start"), and the truncated
 *     frame then fails to decode and costs the load a decoder swap.
 *  2. Both tracks must run continuously across consecutive segments,
 *     so a hole can never open between appends. In Chromium a run of
 *     eight of these segments buffers as ONE range in both 'segments'
 *     and 'sequence' mode; with a segment missing, sequence mode turns
 *     the honest 6 s hole into a 0.147 s one the video renderer never
 *     crosses while the audio track plays straight through it, which is
 *     exactly the reported symptom.
 *
 * Asserted here on the emitted boxes rather than through a browser so
 * it runs in CI: same numbers, no Chromium needed.
 */
class CastSegmentContinuityTest {

    private val ffmpeg = File("/opt/homebrew/bin/ffmpeg")
    private val workDir: File by lazy { File("build/tmp/castHlsProxyContinuity").apply { mkdirs() } }

    /** One segment's per-track span, in 90 kHz ticks, read back out of
     *  the moof: (tfdt, tfdt + sum of trun sample durations) plus the
     *  minimum presentation time for the video track. */
    private class Span(val start: Long, val end: Long, val minPts: Long)

    private class Capture : TsToFmp4Remuxer.Listener {
        var init: ByteArray? = null
        val segments = ArrayList<ByteArray>()
        override fun onInitSegment(data: ByteArray) { init = data }
        override fun onMediaSegment(data: ByteArray, durationTicks: Long) { segments.add(data) }
    }

    // ---- minimal box reader, enough for moof/traf/tfhd/tfdt/trun ----

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun type(b: ByteArray, o: Int) = String(b, o + 4, 4, Charsets.US_ASCII)

    /** Children of the box body [start, end). */
    private fun children(b: ByteArray, start: Int, end: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var o = start
        while (o + 8 <= end) {
            val size = u32(b, o).toInt().let { if (it == 0) end - o else it }
            if (size < 8) break
            out.add(Triple(type(b, o), o + 8, o + size))
            o += size
        }
        return out
    }

    /** Per-track spans of one media segment, keyed by track id. */
    private fun spans(segment: ByteArray): Map<Int, Span> {
        val result = HashMap<Int, Span>()
        for ((t, s, e) in children(segment, 0, segment.size)) {
            if (t != "moof") continue
            for ((t2, s2, e2) in children(segment, s, e)) {
                if (t2 != "traf") continue
                var trackId = -1
                var tfdt = 0L
                var total = 0L
                var minPts = Long.MAX_VALUE
                for ((t3, s3, e3) in children(segment, s2, e2)) {
                    when (t3) {
                        "tfhd" -> trackId = u32(segment, s3 + 4).toInt()
                        "tfdt" -> tfdt = if (segment[s3].toInt() == 1) {
                            (u32(segment, s3 + 4) shl 32) or u32(segment, s3 + 8)
                        } else {
                            u32(segment, s3 + 4)
                        }
                        "trun" -> {
                            val version = segment[s3].toInt()
                            val flags = (u32(segment, s3) and 0xFFFFFF).toInt()
                            val count = u32(segment, s3 + 4).toInt()
                            var p = s3 + 8
                            if (flags and 0x1 != 0) p += 4 // data offset
                            if (flags and 0x4 != 0) p += 4 // first sample flags
                            var decode = 0L
                            repeat(count) {
                                var duration = 0L
                                if (flags and 0x100 != 0) { duration = u32(segment, p); p += 4 }
                                if (flags and 0x200 != 0) p += 4 // size
                                if (flags and 0x400 != 0) p += 4 // flags
                                if (flags and 0x800 != 0) {
                                    val raw = u32(segment, p).toInt()
                                    val cto = if (version == 1) raw.toLong() else (raw.toLong() and 0xFFFFFFFFL)
                                    minPts = minOf(minPts, decode + cto)
                                    p += 4
                                } else {
                                    minPts = minOf(minPts, decode)
                                }
                                decode += duration
                                total += duration
                            }
                        }
                    }
                }
                if (trackId > 0) {
                    val base = if (minPts == Long.MAX_VALUE) 0L else minPts
                    result[trackId] = Span(tfdt, tfdt + total, tfdt + base)
                }
            }
        }
        return result
    }

    private fun buildTs(): File {
        val out = File(workDir, "bframes.ts")
        if (out.isFile && out.length() > 0) return out
        val p = ProcessBuilder(
            ffmpeg.path, "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30:duration=40",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=40",
            // B-frames on purpose: the composition offset of the first
            // sample is what the receiver anchors a sequence-mode append
            // on, and it is what used to push our audio below zero.
            "-c:v", "libx264", "-preset", "veryfast", "-bf", "3", "-g", "90", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-ac", "2", "-ar", "48000",
            "-f", "mpegts", out.path,
        ).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    private fun remux(ts: File): Capture {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(listener = cap, log = {}, allowAc3Passthrough = false)
        val bytes = ts.readBytes()
        var off = 0
        while (off < bytes.size) {
            val n = minOf(64 * 1024, bytes.size - off)
            remuxer.feed(bytes, off, n)
            off += n
        }
        return cap
    }

    @Test
    fun `a run of segments keeps both tracks contiguous and audio never precedes video`() {
        assumeTrue("ffmpeg present", ffmpeg.canExecute())
        val cap = remux(buildTs())
        val segments = cap.segments
        assertTrue("at least 6 segments, got ${segments.size}", segments.size >= 6)

        // One AAC frame at 48 kHz: the largest quantization error the
        // audio partition can carry at a segment boundary.
        val frameTicks = 1024L * TsToFmp4Remuxer.TICKS_PER_SECOND / 48_000L

        var previousVideoEnd = -1L
        var previousAudioEnd = -1L
        segments.forEachIndexed { i, segment ->
            val bySpan = spans(segment)
            val video = bySpan[1] ?: error("segment $i has no video traf")
            val audio = bySpan[2] ?: error("segment $i has no audio traf")

            if (i == 0) {
                // Property 1: nothing before the first video presentation
                // time. A sequence-mode append anchors on video.minPts, so
                // any audio below it is dropped or truncated by Chromium.
                assertTrue(
                    "segment 0 audio starts at ${audio.start} but the first video " +
                        "presentation time is ${video.minPts}",
                    audio.start >= video.minPts,
                )
            } else {
                // Property 2: no holes between appends, in either track.
                assertTrue(
                    "video gap before segment $i: ${video.start - previousVideoEnd} ticks",
                    video.start == previousVideoEnd,
                )
                assertTrue(
                    "audio gap before segment $i: ${audio.start - previousAudioEnd} ticks " +
                        "(one frame is $frameTicks)",
                    audio.start == previousAudioEnd,
                )
            }
            // The audio partition trails the video cut by at most one
            // frame; more than that and the seam starts accumulating.
            assertTrue(
                "segment $i audio ends ${video.end - audio.end} ticks before the video, " +
                    "more than the $frameTicks-tick quantum allows",
                video.end - audio.end < frameTicks * 9,
            )
            previousVideoEnd = video.end
            previousAudioEnd = audio.end
        }
    }
}
