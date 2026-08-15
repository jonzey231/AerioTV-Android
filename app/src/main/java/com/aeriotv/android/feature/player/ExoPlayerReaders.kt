package com.aeriotv.android.feature.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
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
@OptIn(UnstableApi::class)
fun ExoPlayer.captureStreamInfo(): StreamInfoSnapshot {
    val format = videoFormat
    val width = format?.width?.takeIf { it > 0 }
    val height = format?.height?.takeIf { it > 0 }
    val fps = format?.frameRate?.takeIf { it > 0f }
    val codec = format?.codecs.orEmpty().ifBlank { format?.sampleMimeType.orEmpty().removePrefix("video/") }
    val colorInfo = format?.colorInfo
    val bitrate = format?.bitrate?.takeIf { it != Format.NO_VALUE }

    val videoLines = buildList {
        if (codec.isNotBlank()) add(codec)
        val resFps = buildString {
            if (width != null && height != null) append("${width}x${height}")
            if (fps != null) {
                if (isNotEmpty()) append("  ")
                // Media3 reports a precise frame rate; round to one decimal for the chrome.
                append("${(fps * 10).roundToInt() / 10.0}fps")
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
