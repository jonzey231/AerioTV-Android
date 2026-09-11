package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent TMDB art for one library title (Apple parity: TMDBArtCache's
 * tmdb-art-cache.json). PATHS are stored, never pixels: the poster and
 * backdrop columns hold TMDB's relative `poster_path` / `backdrop_path`, and
 * the image bytes themselves live in Coil's disk cache, so a relaunch repaints
 * the grid from disk without a single TMDB request or a re-download.
 *
 * [key] is the library key ("m:" / "t:" plus the cleaned title, see
 * `tmdbArtKey`) rather than a provider id, so the art survives a playlist
 * switch and is shared by every copy of the same title.
 *
 * An entry whose [poster] is empty is a CONFIRMED MISS (TMDB answered with
 * nothing) and is not re-queried until it is 30 days old; a transport failure
 * stores nothing at all and stays retryable.
 */
@Entity(tableName = "tmdb_art")
data class TmdbArtEntity(
    @PrimaryKey val key: String,
    val tmdbId: String,
    val poster: String,
    val backdrop: String,
    /** TMDB's synopsis; the provider's can be in another language. */
    val overview: String?,
    /** Wall-clock millis the lookup answered (miss-expiry check). */
    val at: Long,
)
