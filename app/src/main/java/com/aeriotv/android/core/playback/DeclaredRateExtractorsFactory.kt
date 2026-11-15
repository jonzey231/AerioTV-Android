package com.aeriotv.android.core.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * DefaultExtractorsFactory, except that a Matroska file's video Format
 * carries the frame rate the file declares (the video TrackEntry's
 * DefaultDuration), the way Media3 already fills Format.frameRate from an MP4
 * sample table or an HLS FRAME-RATE attribute.
 *
 * Media3 1.4.1's MatroskaExtractor parses DefaultDuration but never sets
 * Format.frameRate, so the VOD frame-rate match had only rendered timestamps
 * to go on. Matroska timestamps are usually whole milliseconds, which made a
 * 24/1 title ("#Xmas (2022)", device pass 2026-11-14) measure 23.976 and
 * switch the panel to 23.98 Hz; Apple TV reads the same DefaultDuration and
 * plays it at 24. Every other extractor, and every Matroska setting
 * (subtitle parser, text transcoding, flags), is exactly what
 * DefaultExtractorsFactory builds.
 */
@UnstableApi
class DeclaredRateExtractorsFactory : ExtractorsFactory {
    private val base = DefaultExtractorsFactory()
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var textTrackTranscodingEnabled = true

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        this.subtitleParserFactory = subtitleParserFactory
        base.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        this.textTrackTranscodingEnabled = textTrackTranscodingEnabled
        @Suppress("DEPRECATION")
        base.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun createExtractors(): Array<Extractor> = swap(base.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        swap(base.createExtractors(uri, responseHeaders))

    /** Same slot, same configuration as DefaultExtractorsFactory's Matroska
     *  extractor (FLAG_EMIT_RAW_SUBTITLE_DATA when not transcoding text). */
    private fun swap(extractors: Array<Extractor>): Array<Extractor> = Array(extractors.size) { i ->
        val e = extractors[i]
        if (e is MatroskaExtractor) {
            val flags = if (textTrackTranscodingEnabled) 0 else MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA
            DeclaredRateMatroska(RateCapturingMatroskaExtractor(subtitleParserFactory, flags))
        } else {
            e
        }
    }
}

/** The VOD player's media source factory: DefaultMediaSourceFactory with
 *  [DeclaredRateExtractorsFactory]. */
@UnstableApi
fun vodMediaSourceFactory(context: android.content.Context): androidx.media3.exoplayer.source.DefaultMediaSourceFactory =
    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context, DeclaredRateExtractorsFactory())

/** Remembers the DefaultDuration of the video TrackEntry being closed, for
 *  the moment its Format is handed to the output. */
@UnstableApi
private class RateCapturingMatroskaExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    flags: Int,
) : MatroskaExtractor(subtitleParserFactory, flags) {
    /** Frames per second the closing video track declares, 0 when none. */
    var pendingVideoFps = 0f
        private set

    override fun endMasterElement(id: Int) {
        if (id == ID_TRACK_ENTRY) {
            val track = runCatching { getCurrentTrack(id) }.getOrNull()
            pendingVideoFps = MatroskaRate.fps(track?.type ?: 0, track?.defaultSampleDurationNs ?: 0)
        }
        try {
            super.endMasterElement(id)
        } finally {
            if (id == ID_TRACK_ENTRY) pendingVideoFps = 0f
        }
    }

    companion object {
        const val ID_TRACK_ENTRY = 0xAE
    }
}

/** [Extractor.init] is final on MatroskaExtractor, so the output is wrapped
 *  here and everything else goes straight to the extractor. */
@UnstableApi
private class DeclaredRateMatroska(private val inner: RateCapturingMatroskaExtractor) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)
    override fun init(output: ExtractorOutput) = inner.init(RateOutput(output))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
    override fun release() = inner.release()
    override fun getUnderlyingImplementation(): Extractor = inner
    override fun getSniffFailureDetails() = inner.sniffFailureDetails

    private inner class RateOutput(private val out: ExtractorOutput) : ExtractorOutput by out {
        override fun track(id: Int, type: Int): TrackOutput {
            val track = out.track(id, type)
            return if (type == C.TRACK_TYPE_VIDEO) RateTrackOutput(track) else track
        }
    }

    private inner class RateTrackOutput(private val track: TrackOutput) : TrackOutput by track {
        override fun format(format: Format) {
            track.format(MatroskaRate.withDeclared(format, inner.pendingVideoFps))
        }
    }
}

/** Pure helpers, unit tested. */
internal object MatroskaRate {
    /** Matroska TrackType for video. */
    private const val TRACK_TYPE_VIDEO = 1

    /** The rate a TrackEntry declares: video with a DefaultDuration only. */
    fun fps(matroskaTrackType: Int, defaultDurationNs: Int): Float =
        if (matroskaTrackType == TRACK_TYPE_VIDEO && defaultDurationNs > 0) {
            (1_000_000_000.0 / defaultDurationNs).toFloat()
        } else {
            0f
        }

    /** [format] with [declaredFps] filled in, unless it already has a rate
     *  or nothing is declared. */
    fun withDeclared(format: Format, declaredFps: Float): Format =
        if (declaredFps > 0f && !(format.frameRate > 0f)) {
            format.buildUpon().setFrameRate(declaredFps).build()
        } else {
            format
        }
}
