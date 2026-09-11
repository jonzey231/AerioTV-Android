package com.aeriotv.android.feature.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.ExoPlayer
import com.aeriotv.android.core.data.VodLearnedStream
import kotlin.math.roundToInt

/**
 * Bridge layer between Media3's track / format APIs and the
 * pre-existing PlayerChromeOverlay data classes (SubtitleTrack,
 * AudioTrack, StreamInfoSnapshot). Keeps the UI side stable while the
 * data source flips from libmpv property-strings to ExoPlayer's
 * `Tracks` + `Format` model. Task #66.
 *
 * Track identity note: libmpv addressed tracks by an arbitrary integer
 * `aid` / `sid`. ExoPlayer addresses by `(TrackGroup, trackIndex)`. We
 * fold the pair into a deterministic Int (a stable hash) so the UI's
 * `currentSid` / `currentAid` plumbing stays Int-typed without
 * inventing parallel data classes. The integer ONLY flows between the
 * select-callback and `applyTrackSelection()` -- it never lives
 * anywhere durable, so we don't need it to survive a process restart.
 */
/**
 * The video facts BOTH the Stream Info panel and the chrome's format badge
 * read, so the two can never disagree (Logan 2026-09-11). One extraction, one
 * frame-rate spelling, one scan-type call.
 *
 * Source notes, audited 2026-09-11:
 * - [ExoPlayer.getVideoFormat] is the format of the track the video renderer
 *   is CURRENTLY decoding, not the first track in the group, so it already
 *   follows adaptive HLS variant switches.
 * - [Format.width] / [Format.height] are the CODED size. Anamorphic sources
 *   (1440x1080 PAR 4:3, common on broadcast MPEG-2) need
 *   [Format.pixelWidthHeightRatio] applied to the WIDTH to get what the
 *   viewer actually sees; height is unaffected by PAR, which is why the badge,
 *   which prints the height, was already right and the panel's "1440x1080"
 *   was not.
 * - [Format.frameRate] is [Format.NO_VALUE] (-1f) on most live MPEG-TS feeds
 *   until something measures it. It is never printed raw ("-1 fps" is
 *   impossible: every read goes through `takeIf { it > 0f }`), and when it is
 *   missing we fall back to the rate DisplayFrameRateMatcher already measures
 *   from rendered-frame presentation timestamps, rather than inventing a
 *   second measurement.
 * - Media3's [Format] exposes NO field-order / interlacing flag, and
 *   `onVideoSizeChanged` carries none either, so interlacing cannot be read
 *   out. [interlaced] is therefore a conservative INFERENCE, applied only
 *   where it is safe: 1080-line MPEG-2 or H.264 at a 25 / 29.97 / 30 frame
 *   rate is interlaced broadcast in practice (1080p25 / 1080p30 transmissions
 *   effectively do not exist), and 720 / 2160 lines are always progressive.
 *   Everything else stays "p". The FRAME rate is stated in both cases, so
 *   1080i broadcast reads "1080i · 29.97 fps" and never mixes in the 59.94
 *   field rate.
 */
@OptIn(UnstableApi::class)
internal data class VideoFormatFacts(
    /** Coded width, as the decoder sees it. */
    val codedWidth: Int?,
    /** Width after [Format.pixelWidthHeightRatio]: what the viewer sees. */
    val displayWidth: Int?,
    val height: Int?,
    val fps: Float?,
    val interlaced: Boolean,
) {
    /** "1080i" / "2160p", or null before the height is known. */
    val scanLabel: String?
        get() = height?.let { "$it" + if (interlaced) "i" else "p" }
}

@OptIn(UnstableApi::class)
internal fun videoFormatFacts(
    format: Format?,
    /** Measured rate used when the container carries no frameRate. */
    fallbackFps: Float? = null,
): VideoFormatFacts {
    val width = format?.width?.takeIf { it > 0 }
    val height = format?.height?.takeIf { it > 0 }
    val par = format?.pixelWidthHeightRatio?.takeIf { it > 0f && !it.isNaN() } ?: 1f
    val fps = format?.frameRate?.takeIf { it > 0f && !it.isNaN() }
        ?: fallbackFps?.takeIf { it > 0f && !it.isNaN() }
    val mime = format?.sampleMimeType.orEmpty()
    val broadcastCodec = mime.contains("mpeg2") || mime.contains("avc") || mime.contains("h263")
    val interlaced = height == 1080 && broadcastCodec &&
        fps != null && fps > 24.5f && fps < 31.5f
    return VideoFormatFacts(
        codedWidth = width,
        displayWidth = width?.let { (it * par).roundToInt().takeIf { w -> w > 0 } },
        height = height,
        fps = fps,
        interlaced = interlaced,
    )
}

/** "59.94" / "60": two decimals when fractional, integer otherwise. */
internal fun formatFps(fps: Float): String {
    val rounded = (fps * 100f).roundToInt() / 100.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

@OptIn(UnstableApi::class)
fun ExoPlayer.captureStreamInfo(): StreamInfoSnapshot {
    val format = videoFormat
    val facts = videoFormatFacts(format, DisplayFrameRateMatcher.contentFps.value)
    val width = facts.displayWidth
    val height = facts.height
    val fps = facts.fps
    val codec = format?.codecs.orEmpty().ifBlank { format?.sampleMimeType.orEmpty().removePrefix("video/") }
    val colorInfo = format?.colorInfo
    val bitrate = format?.bitrate?.takeIf { it != Format.NO_VALUE }

    val videoLines = buildList {
        if (codec.isNotBlank()) add(codec)
        val resFps = buildString {
            // Display width (PAR applied) so an anamorphic 1440x1080 broadcast
            // reads as the 1920x1080 the viewer sees, and the scan-type suffix
            // the badge shows.
            if (width != null && height != null) {
                append("${width}x${height}")
                facts.scanLabel?.let { append(" ($it)") }
            }
            if (fps != null) {
                if (isNotEmpty()) append("  ")
                // Same spelling the format badge uses (formatFps).
                append("${formatFps(fps)}fps")
            }
        }
        if (resFps.isNotBlank()) add(resFps)
        if (colorInfo != null) {
            val parts = buildList {
                colorSpaceLabel(colorInfo.colorSpace)?.let(::add)
                colorTransferLabel(colorInfo.colorTransfer)?.let(::add)
                colorRangeLabel(colorInfo.colorRange)?.let(::add)
            }
            if (parts.isNotEmpty()) add(parts.joinToString("/"))
        }
        if (bitrate != null) add("${bitrate / 1000} kbps")
        // Media3 picks the codec automatically per platform; we don't
        // surface "hwdec=yes/no" the way libmpv did because every codec
        // that ends up running here goes through MediaCodec by default.
        // The audio renderer is the only fallback path, and that's a
        // separate log signal.
        add("decoder: MediaCodec")
    }

    val aFormat = audioFormat
    val audioLines = buildList {
        val aCodec = aFormat?.codecs.orEmpty().ifBlank { aFormat?.sampleMimeType.orEmpty().removePrefix("audio/") }
        if (aCodec.isNotBlank()) add(aCodec)
        val tail = buildList {
            aFormat?.sampleRate?.takeIf { it > 0 }?.let { add("${it}Hz") }
            aFormat?.channelCount?.takeIf { it > 0 }?.let { add("${it}ch") }
            aFormat?.bitrate?.takeIf { it != Format.NO_VALUE }?.let { add("${it / 1000} kbps") }
        }.joinToString("  ")
        if (tail.isNotBlank()) add(tail)
    }

    val cacheLines = buildList {
        val buf = totalBufferedDuration
        if (buf > 0L) add("buffered: ${buf / 1000}s")
    }
    val syncLines = buildList {
        // ExoPlayer doesn't expose a libmpv-style avsync number; the
        // closest proxy is `playbackState == READY` + `isPlaying`. We
        // surface the playback state explicitly so anyone reading the
        // overlay can see whether the player has fallen behind.
        val state = when (playbackState) {
            androidx.media3.common.Player.STATE_IDLE -> "idle"
            androidx.media3.common.Player.STATE_BUFFERING -> "buffering"
            androidx.media3.common.Player.STATE_READY -> if (isPlaying) "playing" else "ready"
            androidx.media3.common.Player.STATE_ENDED -> "ended"
            else -> "unknown"
        }
        add("state: $state")
    }
    return StreamInfoSnapshot(videoLines, audioLines, cacheLines, syncLines)
}

@OptIn(UnstableApi::class)
fun ExoPlayer.readSubtitleTracks(): List<SubtitleTrack> = readTracks(C.TRACK_TYPE_TEXT) { format, trackId ->
    SubtitleTrack(
        id = trackId,
        title = format.label.orEmpty().ifBlank { format.id.orEmpty() },
        lang = format.language.orEmpty(),
    )
}

@OptIn(UnstableApi::class)
fun ExoPlayer.readAudioTracks(): List<AudioTrack> = readTracks(C.TRACK_TYPE_AUDIO) { format, trackId ->
    AudioTrack(
        id = trackId,
        title = format.label.orEmpty().ifBlank { format.id.orEmpty() },
        lang = format.language.orEmpty(),
        codec = format.codecs.orEmpty().ifBlank { format.sampleMimeType.orEmpty().removePrefix("audio/") },
        channels = format.channelCount.takeIf { it > 0 }?.let { "${it}ch" }.orEmpty(),
    )
}

/**
 * What ExoPlayer ACTUALLY decoded for the current item, as the VOD Version
 * picker's learned measurement. [captureStreamInfo] above builds display
 * strings for the debug overlay; this returns the raw fields instead, because
 * they get PERSISTED and re-formatted later next to the server's own ffprobe
 * numbers.
 *
 * Codec names are normalised to the ffprobe spellings Dispatcharr reports
 * ("hevc", "eac3"), so both sources feed one formatter.
 */
@OptIn(UnstableApi::class)
fun ExoPlayer.readLearnedStream(): VodLearnedStream {
    val v = videoFormat
    val a = audioFormat
    return VodLearnedStream(
        width = v?.width?.takeIf { it > 0 },
        height = v?.height?.takeIf { it > 0 },
        videoCodec = ffprobeVideoCodecName(v?.sampleMimeType),
        audioCodec = ffprobeAudioCodecName(a?.sampleMimeType),
        audioChannels = a?.channelCount?.takeIf { it > 0 },
    )
}

/** Currently-selected sub track id, or null if subs are off / auto. */
@OptIn(UnstableApi::class)
fun ExoPlayer.readCurrentSid(): Int? = readCurrentTrackId(C.TRACK_TYPE_TEXT)

/** Currently-selected audio track id, or null if no audio. */
@OptIn(UnstableApi::class)
fun ExoPlayer.readCurrentAid(): Int? = readCurrentTrackId(C.TRACK_TYPE_AUDIO)

@OptIn(UnstableApi::class)
fun ExoPlayer.readSpeed(): Float = playbackParameters.speed

@OptIn(UnstableApi::class)
fun ExoPlayer.applySpeed(speed: Float) {
    playbackParameters = playbackParameters.withSpeed(speed)
}

/**
 * Select a sub track by the id surfaced from [readSubtitleTracks], or
 * null to disable subtitles.
 */
@OptIn(UnstableApi::class)
fun ExoPlayer.selectSubtitleTrack(trackId: Int?) {
    applyTrackSelection(C.TRACK_TYPE_TEXT, trackId)
}

@OptIn(UnstableApi::class)
fun ExoPlayer.selectAudioTrack(trackId: Int?) {
    applyTrackSelection(C.TRACK_TYPE_AUDIO, trackId)
}

// ─────────────────────── internals ────────────────────────────

@OptIn(UnstableApi::class)
private fun <T> ExoPlayer.readTracks(
    type: Int,
    build: (Format, Int) -> T,
): List<T> {
    val tracks = currentTracks
    val out = mutableListOf<T>()
    tracks.groups.forEach { group ->
        if (group.type != type) return@forEach
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val fmt = group.getTrackFormat(i)
            out += build(fmt, syntheticTrackId(group, i))
        }
    }
    return out
}

@OptIn(UnstableApi::class)
private fun ExoPlayer.readCurrentTrackId(type: Int): Int? {
    val tracks = currentTracks
    tracks.groups.forEach { group ->
        if (group.type != type) return@forEach
        for (i in 0 until group.length) {
            if (group.isTrackSelected(i)) return syntheticTrackId(group, i)
        }
    }
    return null
}

@OptIn(UnstableApi::class)
private fun ExoPlayer.applyTrackSelection(type: Int, trackId: Int?) {
    val current = trackSelectionParameters
    if (trackId == null) {
        // Disable: setTrackTypeDisabled clears the type-wide selection.
        trackSelectionParameters = current.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, true)
            .build()
        return
    }
    val tracks = currentTracks
    var foundOverride: TrackSelectionOverride? = null
    outer@ for (group in tracks.groups) {
        if (group.type != type) continue
        for (i in 0 until group.length) {
            if (syntheticTrackId(group, i) == trackId) {
                foundOverride = TrackSelectionOverride(group.mediaTrackGroup, i)
                break@outer
            }
        }
    }
    val builder = current.buildUpon()
        .setTrackTypeDisabled(type, false)
        .clearOverridesOfType(type)
    foundOverride?.let { builder.addOverride(it) }
    trackSelectionParameters = builder.build()
}

/**
 * Deterministic Int identity for an (group, trackIndex) pair. We use the
 * group's mediaTrackGroup hashCode XOR'd with the index so the same
 * track in the same MediaItem always maps to the same Int -- which keeps
 * `readCurrentSid()` consistent with what the chrome shows as selected.
 */
@OptIn(UnstableApi::class)
private fun syntheticTrackId(group: Tracks.Group, trackIndex: Int): Int {
    return group.mediaTrackGroup.hashCode() xor (trackIndex * 31)
}

/**
 * Media3 sample MIME type -> the ffprobe codec spelling the server uses, so a
 * learned measurement and a server-reported one format the same way. An
 * unrecognised type falls back to the bare subtype rather than being dropped:
 * it is still a real measurement, and the formatter uppercases it as-is.
 */
private fun ffprobeVideoCodecName(mime: String?): String? {
    val m = mime?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return when (m) {
        "video/hevc" -> "hevc"
        "video/avc" -> "h264"
        "video/av01" -> "av01"
        "video/x-vnd.on2.vp9" -> "vp9"
        "video/mpeg2" -> "mpeg2video"
        else -> m.removePrefix("video/").takeIf { it.isNotBlank() }
    }
}

/** Audio counterpart of [ffprobeVideoCodecName]. The DTS and E-AC-3 families
 *  collapse to their base name, matching what ffprobe reports for them. */
private fun ffprobeAudioCodecName(mime: String?): String? {
    val m = mime?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return when (m) {
        "audio/eac3", "audio/eac3-joc" -> "eac3"
        "audio/ac3" -> "ac3"
        "audio/mp4a-latm" -> "aac"
        "audio/true-hd" -> "truehd"
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.uhd" -> "dts"
        "audio/opus" -> "opus"
        "audio/mpeg" -> "mp3"
        else -> m.removePrefix("audio/").takeIf { it.isNotBlank() }
    }
}

private fun colorSpaceLabel(space: Int): String? = when (space) {
    C.COLOR_SPACE_BT709 -> "BT.709"
    C.COLOR_SPACE_BT601 -> "BT.601"
    C.COLOR_SPACE_BT2020 -> "BT.2020"
    else -> null
}

private fun colorTransferLabel(transfer: Int): String? = when (transfer) {
    C.COLOR_TRANSFER_SDR -> "SDR"
    C.COLOR_TRANSFER_ST2084 -> "PQ"
    C.COLOR_TRANSFER_HLG -> "HLG"
    C.COLOR_TRANSFER_GAMMA_2_2 -> "gamma2.2"
    C.COLOR_TRANSFER_LINEAR -> "linear"
    else -> null
}

private fun colorRangeLabel(range: Int): String? = when (range) {
    C.COLOR_RANGE_LIMITED -> "limited"
    C.COLOR_RANGE_FULL -> "full"
    else -> null
}

/**
 * "1080i · 29.97 fps" / "2160p · 60 fps" for the player chrome's format badge
 * (Logan 2026-09-11). Built from [videoFormatFacts] and [formatFps], the exact
 * values and spelling the Stream Info panel shows, so the badge and the panel
 * can never disagree. Null until the height is known; the fps half is dropped
 * when neither the container nor the measurement has a rate yet, so "-1 fps"
 * can never appear.
 */
@OptIn(UnstableApi::class)
fun videoFormatBadge(format: Format?, fallbackFps: Float? = null): String? {
    val facts = videoFormatFacts(format, fallbackFps)
    val scan = facts.scanLabel ?: return null
    val fps = facts.fps ?: return scan
    return "$scan · ${formatFps(fps)} fps"
}

/**
 * [videoFormatBadge] for the player currently bound, recomputed only when the
 * video INPUT FORMAT actually changes (which covers adaptive HLS variant
 * switches that keep the same resolution), when the video size changes, or
 * when the measured fallback rate lands. Never polled per frame.
 *
 * [resetKey] (the channel / media identity) clears the badge immediately on a
 * channel change, so the new channel never shows the previous one's numbers
 * while its first format is still on the way; a media-item transition clears
 * it too.
 */
@OptIn(UnstableApi::class)
@androidx.compose.runtime.Composable
fun rememberVideoFormatBadge(player: ExoPlayer?, resetKey: Any? = null): String? {
    val measuredFps by DisplayFrameRateMatcher.contentFps
        .collectAsStateWithLifecycle()
    var format by androidx.compose.runtime.remember(player, resetKey) {
        androidx.compose.runtime.mutableStateOf<Format?>(null)
    }
    androidx.compose.runtime.DisposableEffect(player, resetKey) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                newFormat: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                format = newFormat
            }
            override fun onVideoSizeChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                videoSize: androidx.media3.common.VideoSize,
            ) {
                format = p.videoFormat
            }
            override fun onVideoDisabled(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderCounters: androidx.media3.exoplayer.DecoderCounters,
            ) {
                // Channel flip / stop: drop the old numbers rather than
                // carrying them into the next stream.
                format = null
            }
            override fun onMediaItemTransition(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int,
            ) {
                format = null
            }
        }
        format = p.videoFormat
        p.addAnalyticsListener(listener)
        onDispose { p.removeAnalyticsListener(listener) }
    }
    return videoFormatBadge(format, measuredFps)
}
