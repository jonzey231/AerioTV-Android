package com.aeriotv.android.core.cast.hlsproxy

import java.io.File
import org.junit.Assert.assertEquals
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
        /** Declared segment durations, i.e. the EXTINF values the playlist
         *  will carry. Shaka anchors the generation AFTER a discontinuity
         *  on their accumulated sum, so the splice arithmetic needs them. */
        val durations = ArrayList<Long>()
        override fun onInitSegment(data: ByteArray) { init = data }
        override fun onMediaSegment(data: ByteArray, durationTicks: Long) {
            segments.add(data)
            durations.add(durationTicks)
        }
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

    /** A transport stream whose AUDIO timestamps trail its video by
     *  [audioLagSeconds] at the same position in the mux, which is what
     *  every measured provider feed looks like (session22.txt, gen 7:
     *  a segment starting at video dts 40.040 carried audio from 39.927).
     *  Built by offsetting the VIDEO input, so no timestamp is negative.
     *
     *  That lag is what opens the hole at a channel change: when the cut
     *  keyframe arrives, the audio that belongs to the last 120 ms of the
     *  segment's video has not been demuxed yet, so it stays queued for a
     *  segment that the channel change then throws away. */
    private fun buildLaggedTs(audioLagSeconds: Double = 0.12): File {
        val out = File(workDir, "audiolag60.ts")
        if (out.isFile && out.length() > 0) return out
        val p = ProcessBuilder(
            ffmpeg.path, "-y", "-v", "error",
            "-itsoffset", audioLagSeconds.toString(),
            // 60 fps with one B-frame of reorder, matching the measured
            // feed: its first video presentation time sat 16 to 34 ms
            // above its decode time, and its audio 2 to 21 ms above that.
            // That pair is the irreducible part of the seam (a generation
            // cannot present media it has not decoded, and audio is
            // quantized to 21.33 ms frames), so the fixture carries the
            // same magnitude rather than an exaggerated one.
            "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=60:duration=30",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=30",
            "-c:v", "libx264", "-preset", "veryfast", "-bf", "1", "-g", "120", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-ac", "2", "-ar", "48000",
            "-f", "mpegts", out.path,
        ).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    /** One ingest connection: feed [limit] bytes in wire-sized chunks,
     *  then the per-connection teardown the session always runs. */
    private fun ingest(bytes: ByteArray, limit: Int): Capture {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(listener = cap, log = {}, allowAc3Passthrough = false)
        var off = 0
        while (off < limit) {
            val n = minOf(64 * 1024, limit - off)
            remuxer.feed(bytes, off, n)
            off += n
        }
        remuxer.release()
        return cap
    }

    @Test
    fun `a channel-change splice leaves one contiguous range in both tracks`() {
        assumeTrue("ffmpeg present", ffmpeg.canExecute())
        val bytes = buildLaggedTs().readBytes()
        val frameTicks = 1024L * TsToFmp4Remuxer.TICKS_PER_SECOND / 48_000L

        // The channel change lands mid-segment: the ingest stops partway
        // through the stream, the session begins a new generation, and a
        // fresh remuxer serves it (one remuxer per ingest connection).
        val genA = ingest(bytes, limit = (bytes.size * 0.6).toInt())
        val genB = ingest(bytes, limit = (bytes.size * 0.4).toInt())
        assertTrue("gen A produced segments", genA.segments.size >= 3)
        assertTrue("gen B produced segments", genB.segments.size >= 2)

        // Shaka parses our EXT-X-DISCONTINUITY with sequenceMode false, so
        // generation B is placed at the accumulated EXTINF position of
        // generation A (its tfdt values restart at 0 and ride on top of
        // that offset). Chromium then reports a two-track SourceBuffer's
        // buffered range as the INTERSECTION of the tracks, so the old
        // range ends at min(video end, audio end) and the new one starts
        // at max(video start, audio start).
        val playlistEndA = genA.durations.sum()
        val lastA = spans(genA.segments.last())
        val videoEndA = (lastA[1] ?: error("gen A tail has no video")).end
        val audioEndA = (lastA[2] ?: error("gen A tail has no audio")).end
        val firstB = spans(genB.segments.first())
        val videoStartB = (firstB[1] ?: error("gen B seg0 has no video")).minPts
        val audioStartB = (firstB[2] ?: error("gen B seg0 has no audio")).start

        val oldEnd = minOf(videoEndA, audioEndA)
        val newStart = playlistEndA + maxOf(videoStartB, audioStartB)
        val holeTicks = newStart - oldEnd
        val holeMs = holeTicks * 1000.0 / TsToFmp4Remuxer.TICKS_PER_SECOND
        println(
            "SPLICE HOLE ${"%.1f".format(holeMs)} ms " +
                "(playlist end ${playlistEndA}, video end ${videoEndA}, " +
                "audio end ${audioEndA}, new start +${maxOf(videoStartB, audioStartB)})",
        )

        // The generation's tail must end both tracks together, or the
        // playlist promises media one of them does not have.
        assertTrue(
            "gen A ends video at $videoEndA but audio at $audioEndA, " +
                "more than one $frameTicks-tick frame apart",
            Math.abs(videoEndA - audioEndA) <= frameTicks,
        )
        // ONE contiguous range across the splice: 40 ms is well inside the
        // quantum a single video frame and a single audio frame allow, and
        // far below the 122 ms hole the receiver flapped on.
        assertTrue(
            "splice hole ${"%.1f".format(holeMs)} ms between the generations",
            holeTicks < TsToFmp4Remuxer.TICKS_PER_SECOND * 40 / 1000,
        )
    }

    /** default_sample_flags of one traf's tfhd, or null when the box does
     *  not carry the field at all. */
    private fun tfhdDefaultSampleFlags(segment: ByteArray, trackId: Int): Long? {
        for ((t, s, e) in children(segment, 0, segment.size)) {
            if (t != "moof") continue
            for ((t2, s2, e2) in children(segment, s, e)) {
                if (t2 != "traf") continue
                for ((t3, s3, e3) in children(segment, s2, e2)) {
                    if (t3 != "tfhd" || e3 - s3 < 8) continue
                    val boxFlags = (u32(segment, s3) and 0xFFFFFF).toInt()
                    if (u32(segment, s3 + 4).toInt() != trackId) continue
                    // Optional fields in ISO order; we never set the
                    // earlier ones, but skip them properly anyway.
                    var p = s3 + 8
                    if (boxFlags and 0x01 != 0) p += 8
                    if (boxFlags and 0x02 != 0) p += 4
                    if (boxFlags and 0x08 != 0) p += 4
                    if (boxFlags and 0x10 != 0) p += 4
                    if (boxFlags and 0x20 == 0 || p + 4 > e3) return null
                    return u32(segment, p)
                }
            }
        }
        return null
    }

    @Test
    fun `every audio sample is declared a sync sample`() {
        // Chromium reads sample flags from the trun, then the tfhd default,
        // then the trex default. Our audio trun carries no per-sample
        // flags, so the tfhd default is what decides whether the receiver
        // treats an AAC frame as a random access point. Without it
        // Chromium logs, once per frame, "indicated the frame is not a
        // random access point (key frame)" and the first packet after the
        // load's seek fails to decode, costing every load a decoder swap
        // (Google TV Streamer, 2026-09-12 14:22:20.237 and .246).
        assumeTrue("ffmpeg present", ffmpeg.canExecute())
        val segment = remux(buildTs()).segments.firstOrNull()
            ?: error("no media segment produced")
        val flags = tfhdDefaultSampleFlags(segment, trackId = 2)
            ?: error("the audio tfhd does not set default-sample-flags")
        // bit 16 is sample_is_non_sync_sample; it must be clear.
        assertTrue(
            "audio default_sample_flags 0x${flags.toString(16)} marks non-sync samples",
            flags and 0x00010000L == 0L,
        )
        // bits 25-24 are sample_depends_on; 2 is "does not depend on others".
        assertEquals(2L, (flags shr 24) and 0x03L)
    }
}
