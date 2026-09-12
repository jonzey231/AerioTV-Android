package com.aeriotv.android.core.cast.hlsproxy

import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Timeline instrumentation added 2026-09-12 after the Google TV Streamer
 * session at 15:10, where Shaka (sequenceMode=false, presentation delay
 * 9 s) picked its start position from the playlist's accumulated EXTINF
 * durations BEFORE any media was appended (seek to 2.439 s), then had to
 * relocate onto the media's own timeline (seek to 0.016 s, metric
 * MediaGapJumped=1) and auto-paused for good at position -58 ms. The two
 * timelines were only related by luck.
 *
 * Two fixes are asserted here:
 *
 *  1. EXT-X-PROGRAM-DATE-TIME, which gives Shaka the absolute clock it
 *     needs to map playlist positions onto the segments' own timestamps.
 *     It must be present on the window's first segment, must be valid
 *     ISO-8601, must advance by exactly the durations that rolled off as
 *     the window slides, and must reappear after a DISCONTINUITY (the
 *     spliced generation restarts its media clock at 0, so it carries its
 *     own anchor).
 *  2. Listener.onSegmentComposition's timeline census, which is what the
 *     per-segment "seg=" proxy log line prints. Over a real TS run every
 *     segment must report a non-negative first video and first audio
 *     presentation time (a negative one is audio below the append window
 *     start, the shape Chromium drops), and segmentStartSeconds must
 *     track the sum of the durations already emitted.
 */
class CastHlsProxyTimelineTest {

    private val ticks = 3L * TsToFmp4Remuxer.TICKS_PER_SECOND

    // ---- (1) EXT-X-PROGRAM-DATE-TIME ----

    private fun programDateTimes(playlist: String): List<String> =
        playlist.lines().filter { it.startsWith("#EXT-X-PROGRAM-DATE-TIME:") }
            .map { it.removePrefix("#EXT-X-PROGRAM-DATE-TIME:") }

    private fun parse(value: String): OffsetDateTime =
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    @Test
    fun `media playlist stamps an ISO-8601 program date time on its first segment`() {
        val server = CastHlsProxyServer(log = {})
        val gen = server.beginGeneration()
        server.setInitSegment(gen, byteArrayOf(1))
        repeat(2) { i -> server.addSegment(gen, byteArrayOf(i.toByte()), ticks) }
        val playlist = server.playlistText()
        val stamps = programDateTimes(playlist)
        assertEquals("exactly one PROGRAM-DATE-TIME in a single-generation window", 1, stamps.size)
        // Parseable, and with the milliseconds HLS parsers expect.
        assertNotNull(parse(stamps[0]))
        assertTrue("milliseconds present in '${stamps[0]}'", stamps[0].contains('.'))
        // It belongs to the FIRST segment: nothing but the playlist header
        // may sit between the tag and seg0.
        val lines = playlist.lines()
        val tagIndex = lines.indexOfFirst { it.startsWith("#EXT-X-PROGRAM-DATE-TIME:") }
        val firstSeg = lines.indexOfFirst { it == "seg0.m4s" }
        assertTrue("tag precedes the first segment URI", tagIndex in 0 until firstSeg)
    }

    @Test
    fun `program date time advances by the rolled-off durations as the window slides`() {
        val server = CastHlsProxyServer(log = {})
        val gen = server.beginGeneration()
        server.setInitSegment(gen, byteArrayOf(1))
        repeat(2) { i -> server.addSegment(gen, byteArrayOf(i.toByte()), ticks) }
        val before = parse(programDateTimes(server.playlistText()).single())
        // Window is 5 segments: publishing 5 more rolls seq 0 and 1 off, so
        // the window's first segment is now seq 2, two 3 s segments later.
        repeat(5) { i -> server.addSegment(gen, byteArrayOf((i + 2).toByte()), ticks) }
        val playlist = server.playlistText()
        assertTrue("window now starts at seq 2", playlist.contains("#EXT-X-MEDIA-SEQUENCE:2"))
        val after = parse(programDateTimes(playlist).single())
        val advancedMs = after.toInstant().toEpochMilli() - before.toInstant().toEpochMilli()
        val expectedMs = 2 * ticks * 1000L / TsToFmp4Remuxer.TICKS_PER_SECOND
        assertTrue(
            "PROGRAM-DATE-TIME advanced ${advancedMs}ms, expected ${expectedMs}ms " +
                "(the two segments that rolled off)",
            kotlin.math.abs(advancedMs - expectedMs) <= 50,
        )
    }

    @Test
    fun `playlist across a discontinuity carries a second program date time`() {
        val server = CastHlsProxyServer(log = {})
        val gen1 = server.beginGeneration()
        server.setInitSegment(gen1, byteArrayOf(1))
        repeat(5) { i -> server.addSegment(gen1, byteArrayOf(i.toByte()), ticks) } // seq 0..4
        val gen2 = server.beginGeneration()
        server.setInitSegment(gen2, byteArrayOf(2))
        repeat(2) { i -> server.addSegment(gen2, byteArrayOf((i + 5).toByte()), ticks) } // seq 5,6
        val playlist = server.playlistText()
        val lines = playlist.lines()
        val stamps = programDateTimes(playlist)
        assertEquals("one stamp for the window start, one for the splice", 2, stamps.size)
        stamps.forEach { assertNotNull(parse(it)) }
        // The second stamp sits IMMEDIATELY after the DISCONTINUITY: the
        // spliced generation restarts its media clock at 0, so the tag has
        // to re-anchor right there rather than be extrapolated from the
        // window start.
        val disc = lines.indexOf("#EXT-X-DISCONTINUITY")
        assertTrue("DISCONTINUITY present", disc >= 0)
        assertTrue(
            "line after the DISCONTINUITY is '${lines[disc + 1]}'",
            lines[disc + 1].startsWith("#EXT-X-PROGRAM-DATE-TIME:"),
        )
    }

    // ---- (2) onSegmentComposition timeline census ----

    private val ffmpeg = File("/opt/homebrew/bin/ffmpeg")
    private val workDir: File by lazy { File("build/tmp/castHlsProxyTimeline").apply { mkdirs() } }

    private class Census(
        val videoSamples: Int,
        val audioSamples: Int,
        val videoDts: Double,
        val videoPts: Double,
        val audioPts: Double,
        val start: Double,
    )

    private class Capture : TsToFmp4Remuxer.Listener {
        val census = ArrayList<Census>()
        val durations = ArrayList<Long>()
        override fun onInitSegment(data: ByteArray) {}
        override fun onMediaSegment(data: ByteArray, durationTicks: Long) { durations.add(durationTicks) }
        override fun onSegmentComposition(
            videoSamples: Int,
            audioSamples: Int,
            firstVideoDtsSeconds: Double,
            firstVideoPtsSeconds: Double,
            firstAudioPtsSeconds: Double,
            segmentStartSeconds: Double,
        ) {
            census.add(
                Census(
                    videoSamples, audioSamples,
                    firstVideoDtsSeconds, firstVideoPtsSeconds, firstAudioPtsSeconds,
                    segmentStartSeconds,
                ),
            )
        }
    }

    /** Same real-ffmpeg TS fixture shape the continuity test uses: B-frames
     *  on purpose, because the composition offset of the first sample is
     *  what separates the DTS timeline from the presentation timeline. */
    private fun buildTs(): File {
        val out = File(workDir, "timeline.ts")
        if (out.isFile && out.length() > 0) return out
        val p = ProcessBuilder(
            ffmpeg.path, "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30:duration=40",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=40",
            "-c:v", "libx264", "-preset", "veryfast", "-bf", "3", "-g", "90", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-ac", "2", "-ar", "48000",
            "-f", "mpegts", out.path,
        ).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    @Test
    fun `segment composition reports a usable timeline for every segment`() {
        assumeTrue("ffmpeg present", ffmpeg.canExecute())
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(listener = cap, log = {}, allowAc3Passthrough = false)
        val bytes = buildTs().readBytes()
        var off = 0
        while (off < bytes.size) {
            val n = minOf(64 * 1024, bytes.size - off)
            remuxer.feed(bytes, off, n)
            off += n
        }
        assertTrue("at least 6 segments, got ${cap.census.size}", cap.census.size >= 6)
        assertEquals("one census per emitted segment", cap.durations.size, cap.census.size)

        // One 90 kHz tick, the arithmetic's own resolution.
        val tick = 1.0 / TsToFmp4Remuxer.TICKS_PER_SECOND
        var emitted = 0L
        cap.census.forEachIndexed { i, c ->
            assertTrue("segment $i has video samples", c.videoSamples > 0)
            assertTrue("segment $i has audio samples", c.audioSamples > 0)
            // Non-negative: everything is relative to timelineBase, and
            // audio below the first video presentation time is precisely
            // what Chromium drops out of the append window.
            assertTrue("segment $i video pts ${c.videoPts} is negative", c.videoPts >= 0.0)
            assertTrue("segment $i audio pts ${c.audioPts} is negative", c.audioPts >= 0.0)
            assertTrue("segment $i video dts ${c.videoDts} is negative", c.videoDts >= 0.0)
            // The presentation time is at or after the decode time; with
            // B-frames the two differ, which is the whole point of logging
            // both.
            assertTrue("segment $i pts ${c.videoPts} precedes dts ${c.videoDts}", c.videoPts >= c.videoDts)
            // segmentStartSeconds is the PLAYLIST-side position: the sum of
            // the durations emitted before this segment.
            val expected = emitted / TsToFmp4Remuxer.TICKS_PER_SECOND.toDouble()
            assertTrue(
                "segment $i start ${c.start} should be $expected (sum of emitted durations)",
                kotlin.math.abs(c.start - expected) <= tick,
            )
            emitted += cap.durations[i]
        }
        // The first segment of the generation starts the playlist timeline.
        assertEquals(0.0, cap.census.first().start, tick)
    }
}
