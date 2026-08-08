package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Persistent VOD catalog for the Movies & TV rebuild (design:
 * ~/Desktop/MoviesTVRedesign.md, plan B1/B2).
 *
 * Before this, NOTHING about the VOD library was persisted: the ViewModel swept
 * categories into memory on every launch with a 5,000-item cap, and process
 * death dropped the whole catalog. That is what made sort, filters, alpha-jump,
 * instant relaunch and instant full-library search impossible on the 17k+ item
 * libraries users actually have.
 *
 * All four tables follow the [WatchProgressEntity] conventions: a composite key
 * scoped by playlist, a CASCADE foreign key so removing a playlist takes its
 * catalog with it, and indices on every column the UI sorts or filters by.
 * `sortTitle` is computed at ingest (articles stripped, lowercased) so Title
 * sort and the alpha-jump letter buckets are pure SQL, not per-row work.
 *
 * `isPersonalLibrary` / `librarySource` / `libraryName` are the personal-media
 * facets derived by MediaLibraryAdapter. On a stock server they stay false and
 * empty, and everything reads as a provider catalog.
 */

@Entity(
    tableName = "vod_categories",
    primaryKeys = ["playlistId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index("categoryType"), Index("isPersonalLibrary")],
)
data class VodCategoryEntity(
    val playlistId: String,
    val id: String,
    val name: String,
    /** "movie" or "series", matching the server's category_type. */
    val categoryType: String,
    val enabled: Boolean = true,
    val isPersonalLibrary: Boolean = false,
    /** The user's own name for the source that owns this library. */
    val librarySource: String = "",
    val libraryName: String = "",
    /** Diagnostics only; never shown in user-facing copy. */
    val provider: String = "",
    val syncedAt: Long = 0L,
)

@Entity(
    tableName = "vod_movies",
    primaryKeys = ["playlistId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("playlistId"),
        Index("uuid"),
        Index("sortTitle"),
        Index("serverCreatedAt"),
        Index("category"),
    ],
)
data class VodMovieEntity(
    val playlistId: String,
    val id: String,
    /** Dispatcharr uuid; the join key with watch_progress.videoId. */
    val uuid: String,
    val title: String,
    /** Lowercased, article-stripped. Drives Title sort and alpha buckets. */
    val sortTitle: String,
    val plot: String = "",
    val genre: String = "",
    val rating: String = "",
    val year: Int? = null,
    val durationSecs: Int? = null,
    val tmdbId: String = "",
    val imdbId: String = "",
    val trailerUrl: String = "",
    /** Resolved ONCE at ingest by MediaLibraryAdapter so the UI never touches
     *  logo objects, and a server filesystem path can never reach an image
     *  loader. */
    val posterUrl: String = "",
    val category: String = "",
    val isPersonalLibrary: Boolean = false,
    val libraryKey: String = "",
    /** Server-side creation time; the ordering key for Recently Added and for
     *  delta refresh. Null on Xtream, which has no created_at. */
    val serverCreatedAt: Long? = null,
    val syncedAt: Long = 0L,
    /** "dispatcharr" or "xtream". */
    val sourceKind: String = "",
)

@Entity(
    tableName = "vod_series",
    primaryKeys = ["playlistId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("playlistId"),
        Index("uuid"),
        Index("sortTitle"),
        Index("serverCreatedAt"),
        Index("category"),
    ],
)
data class VodSeriesEntity(
    val playlistId: String,
    val id: String,
    val uuid: String,
    val title: String,
    val sortTitle: String,
    val plot: String = "",
    val genre: String = "",
    val rating: String = "",
    val year: Int? = null,
    val tmdbId: String = "",
    val posterUrl: String = "",
    val category: String = "",
    val isPersonalLibrary: Boolean = false,
    val libraryKey: String = "",
    val episodeCount: Int = 0,
    val serverCreatedAt: Long? = null,
    val syncedAt: Long = 0L,
    val sourceKind: String = "",
)

/**
 * Episodes. The server has no seasons model (only `season_number`), so seasons
 * are synthesized by grouping on [seasonNumber] exactly as the API implies.
 */
@Entity(
    tableName = "vod_episodes",
    primaryKeys = ["playlistId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index("seriesId"), Index("uuid")],
)
data class VodEpisodeEntity(
    val playlistId: String,
    val id: String,
    val uuid: String,
    val seriesId: String,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val title: String = "",
    val plot: String = "",
    val airDate: String = "",
    val durationSecs: Int? = null,
    val stillUrl: String = "",
    val syncedAt: Long = 0L,
)
