package com.aeriotv.android.core.data.vod

import com.aeriotv.android.core.data.db.dao.LetterBucket
import com.aeriotv.android.core.data.db.dao.VodCard
import com.aeriotv.android.core.data.db.dao.VodCatalogDao
import com.aeriotv.android.core.data.db.entity.VodCategoryEntity
import com.aeriotv.android.core.data.db.entity.VodEpisodeEntity
import com.aeriotv.android.core.data.db.entity.VodMovieEntity
import com.aeriotv.android.core.data.db.entity.VodSeriesEntity
import com.aeriotv.android.core.network.DispatcharrVODMovie
import com.aeriotv.android.core.network.DispatcharrVODSeries
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * The catalog's read/write face for the Movies & TV rebuild.
 *
 * Reads are Room Flows so the UI paints from disk instantly and then updates
 * itself as a refresh fills in; writes go through chunked transactions. Detail
 * screens look up by uuid HERE rather than reaching into a tab-scoped
 * ViewModel, which is what kills the "Series not found" bug class that comes
 * from resolving details via getBackStackEntry(Routes.MAIN).
 */
@Singleton
class VodCatalogRepository @Inject constructor(
    private val dao: VodCatalogDao,
) {

    // ---- Reads -------------------------------------------------------------

    fun movieCards(playlistId: String, sort: VodSort, libraryKey: String = ""): Flow<List<VodCard>> =
        dao.movieCards(playlistId, sort.sqlKey, libraryKey)

    fun seriesCards(playlistId: String, sort: VodSort, libraryKey: String = ""): Flow<List<VodCard>> =
        dao.seriesCards(playlistId, sort.sqlKey, libraryKey)

    fun recentMovies(playlistId: String, libraryKey: String = ""): Flow<List<VodCard>> =
        dao.recentMovies(playlistId, libraryKey)

    fun recentSeries(playlistId: String, libraryKey: String = ""): Flow<List<VodCard>> =
        dao.recentSeries(playlistId, libraryKey)

    suspend fun search(playlistId: String, query: String): List<VodCard> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val pattern = "%${MediaLibraryAdapter.sortTitle(trimmed)}%"
        return dao.searchMovies(playlistId, pattern) + dao.searchSeries(playlistId, pattern)
    }

    suspend fun letterBuckets(playlistId: String, libraryKey: String = ""): List<LetterBucket> =
        dao.movieLetterBuckets(playlistId, libraryKey)

    suspend fun movie(playlistId: String, uuid: String): VodMovieEntity? =
        dao.movieByUuid(playlistId, uuid)

    suspend fun series(playlistId: String, uuid: String): VodSeriesEntity? =
        dao.seriesByUuid(playlistId, uuid)

    suspend fun episodes(playlistId: String, seriesId: String): List<VodEpisodeEntity> =
        dao.episodesForSeries(playlistId, seriesId)

    suspend fun movieCount(playlistId: String): Int = dao.movieCount(playlistId)

    suspend fun seriesCount(playlistId: String): Int = dao.seriesCount(playlistId)

    // ---- Writes ------------------------------------------------------------

    suspend fun upsertMovies(rows: List<VodMovieEntity>) = dao.upsertMoviesChunked(rows)

    suspend fun upsertSeries(rows: List<VodSeriesEntity>) = dao.upsertSeriesChunked(rows)

    suspend fun upsertEpisodes(rows: List<VodEpisodeEntity>) = dao.upsertEpisodes(rows)

    suspend fun upsertCategories(rows: List<VodCategoryEntity>) = dao.upsertCategories(rows)

    /** Deletion reconciliation. Only ever called after a sweep that COMPLETED:
     *  a partial walk must never be read as upstream deletions. */
    suspend fun pruneNotSeenSince(playlistId: String, cutoff: Long): Int =
        dao.pruneMovies(playlistId, cutoff) + dao.pruneSeries(playlistId, cutoff)

    // ---- Mapping -----------------------------------------------------------

    /**
     * Wire model to catalog row. The poster URL is resolved ONCE here through
     * [MediaLibraryAdapter.posterUrl], so no downstream code ever sees a logo
     * object and a server filesystem path can never reach Coil.
     */
    fun toEntity(
        movie: DispatcharrVODMovie,
        playlistId: String,
        serverBaseUrl: String,
        library: MediaLibraryAdapter.Library?,
        syncedAt: Long,
        sourceKind: String,
    ): VodMovieEntity {
        val title = movie.displayName
        return VodMovieEntity(
            playlistId = playlistId,
            id = movie.id.toString(),
            uuid = movie.uuid,
            title = title,
            sortTitle = MediaLibraryAdapter.sortTitle(title),
            plot = movie.plot.orEmpty(),
            genre = movie.genre.orEmpty(),
            rating = movie.rating.orEmpty(),
            year = movie.year,
            durationSecs = movie.durationSecs,
            tmdbId = movie.tmdbId.orEmpty(),
            imdbId = movie.imdbId.orEmpty(),
            trailerUrl = movie.youtubeTrailer.orEmpty(),
            posterUrl = MediaLibraryAdapter.posterUrl(
                cacheUrl = movie.logo?.cacheUrl,
                url = movie.logo?.url,
                serverBaseUrl = serverBaseUrl,
            ),
            category = movie.categoryName.orEmpty(),
            isPersonalLibrary = library?.isPersonal ?: false,
            libraryKey = library?.key.orEmpty(),
            serverCreatedAt = null,
            syncedAt = syncedAt,
            sourceKind = sourceKind,
        )
    }

    fun toEntity(
        series: DispatcharrVODSeries,
        playlistId: String,
        serverBaseUrl: String,
        library: MediaLibraryAdapter.Library?,
        syncedAt: Long,
        sourceKind: String,
    ): VodSeriesEntity {
        val title = series.displayName
        return VodSeriesEntity(
            playlistId = playlistId,
            id = series.id.toString(),
            uuid = series.uuid,
            title = title,
            sortTitle = MediaLibraryAdapter.sortTitle(title),
            plot = series.plot.orEmpty(),
            genre = series.genre.orEmpty(),
            rating = series.rating.orEmpty(),
            year = series.year,
            tmdbId = series.tmdbId.orEmpty(),
            posterUrl = MediaLibraryAdapter.posterUrl(
                cacheUrl = series.logo?.cacheUrl,
                url = series.logo?.url,
                serverBaseUrl = serverBaseUrl,
            ),
            category = series.categoryName.orEmpty(),
            isPersonalLibrary = library?.isPersonal ?: false,
            libraryKey = library?.key.orEmpty(),
            episodeCount = 0,
            serverCreatedAt = null,
            syncedAt = syncedAt,
            sourceKind = sourceKind,
        )
    }
}

/** Sort options, shared by both grids. `sqlKey` is what the DAO branches on. */
enum class VodSort(val sqlKey: String, val label: String) {
    Title("title", "Title"),
    DateAdded("added", "Date Added"),
    ReleaseYear("year", "Release Year"),
    Rating("rating", "Rating"),
    Random("random", "Random"),
}
