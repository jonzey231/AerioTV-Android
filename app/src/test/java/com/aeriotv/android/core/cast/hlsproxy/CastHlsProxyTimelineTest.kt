package com.aeriotv.android.core.cast.hlsproxy

import java.io.File
import org.junit.Assert.assertEquals
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
 * The absolute-clock fix tried that day (EXT-X-PROGRAM-DATE-TIME) was
 * REVERTED the same day: the anchor it was derived from was the wall clock
 * at the moment a segment was stored, one whole segment later than the
 * media that segment begins with, and Shaka makes a PDT the authority for
 * segment POSITIONS (hls_parser.js createSegments_ ->
 * SegmentReference.syncAgainst, setInitialProgramDateTime in
 * determineDuration_), so its live window slid a segment past the media in
 * the buffer (receiver telemetry seek=[51.368-52.373] against
 * buffered=[46.537-51.593], playhead 51.357, BUFFERING forever).
 *
 * Two things are asserted here instead:
 *
 *  1. The playlist carries NO absolute clock, and its window span (the
 *     value Shaka turns into segmentAvailabilityDuration via
 *     determineDuration_ -> getLiveDuration_) stays comfortably wider than
 *     the 4 s presentation delay the receiver configures, so the live seek
 *     range [availEnd - span, availEnd - delay] is never degenerate.
 *  2. Listener.onSegmentComposition's timeline census, which is what the
 *     per-segment "seg=" proxy log line prints. Over a real TS run every
 *     segment must report a non-negative first video and first audio
 *     presentation time (a negative one is audio below the append window
 *     start, the shape Chromium drops), and segmentStartSeconds must
 *     track the sum of the durations already emitted.
 */
class CastHlsProxyTimelineTest {

    private val ticks = 3L * TsToFmp4Remuxer.TICKS_PER_SECOND

    // ---- (1) no absolute clock, and a seekable window ----

    private fun programDateTimes(playlist: String): List<String> =
        playlist.lines().filter { it.startsWith("#EXT-X-PROGRAM-DATE-TIME:") }

    /** Total EXTINF seconds in the playlist: the window media span Shaka
     *  turns into its segment availability duration. */
    private fun windowSpanSeconds(playlist: String): Double =
        playlist.lines().filter { it.startsWith("#EXTINF:") }
            .sumOf { it.removePrefix("#EXTINF:").substringBefore(',').toDouble() }

    /** LIVE_START_BEHIND_SECONDS in receiver.html, which the receiver hands
     *  Shaka as manifest.defaultPresentationDelay. */
    private val receiverPresentationDelay = 4.0

    @Test
    fun `media playlist carries no program date time`() {
        val server = CastHlsProxyServer(log = {})
        val gen = server.beginGeneration()
        server.setInitSegment(gen, byteArrayOf(1))
        repeat(4) { i -> server.addSegment(gen, byteArrayOf(i.toByte()), ticks) }
        val playlist = server.playlistText()
        assertEquals("no PROGRAM-DATE-TIME on a live playlist", emptyList<String>(), programDateTimes(playlist))
    }

    @Test
    fun `the narrowest window the receiver can see still leaves a seek range`() {
        val server = CastHlsProxyServer(log = {})
        val gen = server.beginGeneration()
        server.setInitSegment(gen, byteArrayOf(1))
        // The load gate is four segments (CastHlsProxySession.READY_MIN_SEGMENTS).
        repeat(CastHlsProxySession.READY_MIN_SEGMENTS) { i ->
            server.addSegment(gen, byteArrayOf(i.toByte()), ticks)
        }
        val span = windowSpanSeconds(server.playlistText())
        assertEquals("four 3 s segments span 12 s", 12.0, span, 0.001)
        assertTrue(
            "seek range would be ${span - receiverPresentationDelay}s wide",
            span - receiverPresentationDelay >= 3.0,
        )
    }

    @Test
    fun `a full window spans five segments and still carries no program date time`() {
        val server = CastHlsProxyServer(log = {})
        val gen = server.beginGeneration()
        server.setInitSegment(gen, byteArrayOf(1))
        repeat(9) { i -> server.addSegment(gen, byteArrayOf(i.toByte()), ticks) }
        val playlist = server.playlistText()
        assertTrue("window head advanced", playlist.contains("#EXT-X-MEDIA-SEQUENCE:4"))
        assertEquals("five 3 s segments span 15 s", 15.0, windowSpanSeconds(playlist), 0.001)
        assertEquals("still no PROGRAM-DATE-TIME", emptyList<String>(), programDateTimes(playlist))
    }

    @Test
    fun `playlist across a discontinuity puts the new MAP straight after the tag`() {
        val server = CastHlsProxyServer(log = {})
        val gen1 = server.beginGeneration()
        server.setInitSegment(gen1, byteArrayOf(1))
        repeat(5) { i -> server.addSegment(gen1, byteArrayOf(i.toByte()), ticks) } // seq 0..4
        val gen2 = server.beginGeneration()
        server.setInitSegment(gen2, byteArrayOf(2))
        repeat(2) { i -> server.addSegment(gen2, byteArrayOf((i + 5).toByte()), ticks) } // seq 5,6
        val playlist = server.playlistText()
        val lines = playlist.lines()
        assertEquals("no PROGRAM-DATE-TIME across a splice", emptyList<String>(), programDateTimes(playlist))
        val disc = lines.indexOf("#EXT-X-DISCONTINUITY")
        assertTrue("DISCONTINUITY present", disc >= 0)
        assertTrue(
            "line after the DISCONTINUITY is '${lines[disc + 1]}'",
            lines[disc + 1].startsWith("#EXT-X-MAP:"),
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
