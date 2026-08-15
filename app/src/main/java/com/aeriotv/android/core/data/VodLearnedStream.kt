package com.aeriotv.android.core.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * iOS `VODLearnedStream` parity (Models/VODModels.swift): what the PLAYER
 * measured while actually playing ONE provider copy of a movie.
 *
 * Dispatcharr relays each upstream panel's ffprobe output to the Version
 * picker, but coverage is uneven -- verified on a live server, several copies
 * publish a bitrate and nothing else, and some publish nothing at all. A copy
 * that has PLAYED is fully described though: ExoPlayer knows its real frame
 * size, video codec, audio codec and channel count. Remembering that per copy
 * lets the picker describe rows the server could not, and every field stays a
 * real measurement -- provider titles are never parsed.
 *
 * Codec names are stored in the ffprobe spellings the server uses ("hevc",
 * "eac3"), so both sources feed one formatter (VodStreamFormatting).
 */
@Immutable
@Serializable
data class VodLearnedStream(
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
) {
    /**
     * Nothing worth remembering. iOS parity, including which fields count:
     * [height] and [audioChannels] never stand alone (the reader sets each
     * one alongside its partner), so they do not make a record non-empty.
     */
    val isEmpty: Boolean
        get() = width == null && videoCodec == null && audioCodec == null
}
