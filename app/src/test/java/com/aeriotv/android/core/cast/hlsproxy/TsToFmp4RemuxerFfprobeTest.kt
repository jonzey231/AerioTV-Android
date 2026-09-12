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
        val logs = ArrayList<String>()
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
            log = { cap.logs.add(it) },
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
    /**
     * The Dispatcharr PCE fixture, built by REWRITING the AAC fixture's
     * ADTS stream rather than by asking ffmpeg for it: ffmpeg's own AAC
     * encoder refuses every two-channel layout outside plain stereo
     * (FL+LFE and friends fail with EINVAL), so it cannot be made to emit
     * the exact shape Dispatcharr's `-c:a aac -ac 2` produces on this
     * machine. What it produces there is channel_configuration 0 plus a
     * program_config_element declaring one front channel_pair_element at
     * the start of EVERY raw_data_block, and that is synthesized here
     * byte for byte.
     *
     * Building it this way buys the strongest possible assertion: the
     * frames underneath the injected PCE are the untouched stereo
     * fixture's frames, so a correct strip has to reproduce them exactly.
     */
    private fun buildPceTs(): File {
        val out = File(workDir, "aac-pce.ts")
        if (out.isFile && out.length() > 0) return out
        val plain = File(workDir, "aac-plain.aac")
        val (demuxCode, demuxLog) = run(
            ffmpeg.path, "-y", "-v", "error", "-i", buildAacTs().path,
            "-map", "0:a", "-c", "copy", "-f", "adts", plain.path,
        )
        assertEquals("ffmpeg demuxed the ADTS audio: $demuxLog", 0, demuxCode)
        val injected = File(workDir, "aac-pce.aac")
        injected.writeBytes(injectPce(plain.readBytes()))
        val (muxCode, muxLog) = run(
            ffmpeg.path, "-y", "-v", "error",
            "-i", buildAacTs().path, "-i", injected.path,
            "-map", "0:v", "-map", "1:a", "-c", "copy", "-f", "mpegts", out.path,
        )
        assertEquals("ffmpeg muxed the PCE fixture: $muxLog", 0, muxCode)
        return out
    }

    /** A 7-byte program_config_element: one front channel_pair_element,
     *  no side/back/LFE/assoc/cc elements, no mixdowns, no comment. That
     *  is 56 bits including the byte_align() and comment_field_bytes, so
     *  it ends byte-aligned exactly as ISO/IEC 14496-3 4.4.1.1 promises. */
    private fun pceBytes(freqIndex: Int): ByteArray {
        val bits = StringBuilder()
        fun put(value: Int, width: Int) {
            for (i in width - 1 downTo 0) bits.append((value shr i) and 1)
        }
        put(5, 3) // id_syn_ele = PCE
        put(0, 4) // element_instance_tag
        put(1, 2) // object_type (AAC-LC)
        put(freqIndex, 4)
        put(1, 4); put(0, 4); put(0, 4) // num_front/side/back
        put(0, 2); put(0, 3); put(0, 4) // num_lfe/assoc_data/valid_cc
        put(0, 1); put(0, 1); put(0, 1) // no mono/stereo/matrix mixdown
        put(1, 1); put(0, 4) // front element: is_cpe = 1, tag 0
        while (bits.length % 8 != 0) bits.append(0) // byte_align()
        put(0, 8) // comment_field_bytes
        return ByteArray(bits.length / 8) { i ->
            bits.substring(i * 8, i * 8 + 8).toInt(2).toByte()
        }
    }

    /** Rewrite every ADTS frame of [adts] to channel_configuration 0 with
     *  [pceBytes] spliced in ahead of the raw_data_block, growing
     *  aac_frame_length to match. */
    private fun injectPce(adts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var p = 0
        while (p + 7 <= adts.size) {
            val protectionAbsent = adts[p + 1].toInt() and 0x01 != 0
            val headerLen = if (protectionAbsent) 7 else 9
            val freqIndex = (adts[p + 2].toInt() shr 2) and 0x0F
            val frameLen = ((adts[p + 3].toInt() and 0x03) shl 11) or
                ((adts[p + 4].toInt() and 0xFF) shl 3) or
                ((adts[p + 5].toInt() shr 5) and 0x07)
            if (frameLen < headerLen || p + frameLen > adts.size) break
            val pce = pceBytes(freqIndex)
            val newLen = frameLen + pce.size
            val header = adts.copyOfRange(p, p + headerLen)
            // channel_configuration is the low bit of byte 2 plus the top
            // two bits of byte 3; zero all three.
            header[2] = (header[2].toInt() and 0xFE).toByte()
            header[3] = (header[3].toInt() and 0x3F).toByte()
            header[3] = ((header[3].toInt() and 0xFC) or ((newLen shr 11) and 0x03)).toByte()
            header[4] = ((newLen shr 3) and 0xFF).toByte()
            header[5] = ((header[5].toInt() and 0x1F) or ((newLen and 0x07) shl 5)).toByte()
            out.write(header)
            out.write(pce)
            out.write(adts, p + headerLen, frameLen - headerLen)
            p += frameLen
        }
        return out.toByteArray()
    }

    /**
     * The Google TV Streamer failure of 2026-09-12: Dispatcharr's AAC
     * output profile hands us channel_configuration 0 frames whose raw
     * data blocks start with a program_config_element, and C2SoftAacDec
     * rejected all 5388 of them ("error 0x0005, substituting silence").
     * Stripping the PCE has to leave decodable AAC behind, and the track
     * has to be declared with the layout the PCE described.
     */
    @Test
    fun `aac frames carrying a program config element are stripped and still decode`() {
        assumeTrue("ffmpeg/ffprobe present", ffmpeg.canExecute() && ffprobe.canExecute())
        val cap = remux(buildPceTs(), allowAc3Passthrough = false)
        assertTrue("init segment emitted", cap.init != null)
        assertTrue("segments produced, got ${cap.segments.size}", cap.segments.size >= 2)

        val file = writePlayable("aac-pce.mp4", cap, segmentCount = 2)
        val streams = probeStreams(file)
        assertTrue("aac audio stream\n$streams", streams.contains("codec_name=\"aac\""))
        assertTrue("48 kHz audio\n$streams", streams.contains("sample_rate=\"48000\""))
        assertTrue("stereo declared from the PCE layout\n$streams", streams.contains("channels=2"))

        val (pts, err) = probeAudioPackets(file)
        assertTrue("audio packets present", pts.size >= 40)
        assertTrue("no decode errors from ffprobe: $err", err.isBlank())

        // Announced once for the session, not once per frame.
        val stripLogs = cap.logs.filter { it.startsWith("AAC PCE stripped:") }
        assertEquals("PCE strip logged exactly once: ${cap.logs}", 1, stripLogs.size)
        assertEquals("AAC PCE stripped: layout 2 ch -> config 2", stripLogs.first())
        assertTrue(
            "the config sanitizer has nothing left to complain about: ${cap.logs}",
            cap.logs.none { it.startsWith("ADTS audio config sanitized") },
        )

        // The decoder's verdict, not ours: a full decode of the whole
        // file. ffmpeg prints nothing on a clean decode, so any AAC error
        // (the "substituting silence" class of failure the Streamer hit)
        // shows up here as output.
        val (decodeCode, decodeLog) = run(ffmpeg.path, "-v", "error", "-i", file.path, "-f", "null", "-")
        assertEquals("ffmpeg decoded the stripped stream: $decodeLog", 0, decodeCode)
        assertTrue("no decoder output at all: $decodeLog", decodeLog.isBlank())

        // The AudioSpecificConfig in the esds: AAC-LC, 48 kHz, channel
        // configuration 2. 0x05 is the DecoderSpecificInfo tag, followed
        // by its 2-byte length and the ASC itself.
        val init = cap.init!!
        var ascFound = false
        for (i in 0 until init.size - 3) {
            if (init[i].toInt() == 0x05 && init[i + 1].toInt() == 0x02 &&
                init[i + 2].toInt() == 0x11 && init[i + 3].toInt() == 0x90.toByte().toInt()
            ) {
                ascFound = true
            }
        }
        assertTrue("the esds ASC says AAC-LC 48 kHz channel configuration 2", ascFound)
    }

    /**
     * Losslessness, asserted on the bytes: the PCE fixture's frames are
     * the plain fixture's frames with a PCE spliced in front, so the
     * samples the remuxer queues for the two must be IDENTICAL. This is
     * what proves the strip is a byte-wise copy and not a re-encode or a
     * mis-shifted payload.
     */
    @Test
    fun `stripping the program config element reproduces the original frames exactly`() {
        assumeTrue("ffmpeg/ffprobe present", ffmpeg.canExecute() && ffprobe.canExecute())
        val plain = remux(buildAacTs(), allowAc3Passthrough = false)
        val pce = remux(buildPceTs(), allowAc3Passthrough = false)
        // Concatenated across segments, not compared per segment: the
        // injected PCE makes every source frame 7 bytes longer, which
        // repacks the PES and shifts where a segment boundary falls by up
        // to one frame. The SAMPLES either side of that cut are still the
        // same frames in the same order.
        val plainSamples = plain.segments.flatMap { audioSampleBytes(it) }
        val pceSamples = pce.segments.flatMap { audioSampleBytes(it) }
        assertTrue("samples extracted, got ${plainSamples.size}", plainSamples.size >= 200)
        // The runs are aligned by CONTENT, not by index: every source
        // frame grew by the 7-byte PCE, which repacks the PES and shifts
        // both the first frame that clears the video presentation gate
        // and where the last partial segment ends. What must hold is that
        // from the first shared frame onward the two sample streams are
        // the same frames in the same order, byte for byte.
        val plainHex = plainSamples.map { it.toHex() }
        val pceHex = pceSamples.map { it.toHex() }
        val offset = pceHex.indexOf(plainHex[0])
        assertTrue("the plain run's first frame appears in the PCE run", offset >= 0)
        val common = minOf(plainHex.size, pceHex.size - offset)
        assertTrue("a long shared run to compare, got $common", common >= 200)
        for (i in 0 until common) {
            assertEquals(
                "audio sample $i is byte-identical after the PCE strip",
                plainHex[i],
                pceHex[i + offset],
            )
        }
        // And the init segments agree, so the declared track is the same
        // one the untouched stereo stream produces.
        assertEquals("identical init segment", plain.init!!.toHex(), pce.init!!.toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** The audio track's samples, cut out of a segment's mdat using the
     *  second traf's trun sample sizes and data_offset. */
    private fun audioSampleBytes(seg: ByteArray): List<ByteArray> {
        var moofStart = -1
        var i = 0
        while (i + 8 <= seg.size) {
            val size = be32(seg, i)
            if (size <= 0) break
            if (String(seg, i + 4, 4, Charsets.US_ASCII) == "moof") { moofStart = i; break }
            i += size
        }
        if (moofStart < 0) return emptyList()
        val moofSize = be32(seg, moofStart)
        val trafs = ArrayList<Pair<Int, Int>>()
        var j = moofStart + 8
        while (j + 8 <= moofStart + moofSize) {
            val s = be32(seg, j)
            if (s <= 0) break
            if (String(seg, j + 4, 4, Charsets.US_ASCII) == "traf") trafs.add(Pair(j, s))
            j += s
        }
        if (trafs.size < 2) return emptyList()
        val (off, sz) = trafs[1]
        var k = off + 8
        while (k + 8 <= off + sz) {
            val s = be32(seg, k)
            if (s <= 0) break
            if (String(seg, k + 4, 4, Charsets.US_ASCII) == "trun") {
                val count = be32(seg, k + 12)
                // data_offset is relative to the moof start.
                var cursor = moofStart + be32(seg, k + 16)
                val out = ArrayList<ByteArray>(count)
                var q = k + 20
                repeat(count) {
                    val len = be32(seg, q + 4)
                    out.add(seg.copyOfRange(cursor, cursor + len))
                    cursor += len
                    q += 8
                }
                return out
            }
            k += s
        }
        return emptyList()
    }

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
