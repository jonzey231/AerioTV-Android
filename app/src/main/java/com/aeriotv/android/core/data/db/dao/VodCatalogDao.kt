package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aeriotv.android.core.data.db.entity.VodCategoryEntity
import com.aeriotv.android.core.data.db.entity.VodEpisodeEntity
import com.aeriotv.android.core.data.db.entity.VodMovieEntity
import com.aeriotv.android.core.data.db.entity.VodSeriesEntity
import kotlinx.coroutines.flow.Flow

/**
 * Catalog reads for the Movies & TV rebuild.
 *
 * Two deliberate shapes here:
 *
 *  - Grids read [VodCard] PROJECTIONS, not full entities. A 17k-row grid needs
 *    a title, a poster and a couple of badges; loading plots and cast for every
 *    row is what would put this over the Z Fold 5's heap budget (the field
 *    report that killed the old in-memory model).
 *  - Sorting and filtering happen in SQL, never in Kotlin. That is what makes
 *    Title / Date Added / Year / Rating instant on a full library, and it is
 *    what the alpha-jump letter buckets query against.
 *
 * If profiling ever disagrees, this interface is shaped so a Paging 3 swap is
 * local: replace the Flow<List<VodCard>> returns with PagingSource and nothing
 * above the DAO changes shape.
 */
@Dao
interface VodCatalogDao {

    // ---- Writes ------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMovies(rows: List<VodMovieEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(rows: List<VodSeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(rows: List<VodEpisodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(rows: List<VodCategoryEntity>)

    /** Chunked writes inside ONE transaction: the same discipline
     *  saveChannelsToCache uses to avoid binding a whole catalog at once. */
    @Transaction
    suspend fun upsertMoviesChunked(rows: List<VodMovieEntity>, chunk: Int = 300) {
        rows.chunked(chunk).forEach { upsertMovies(it) }
    }

    @Transaction
    suspend fun upsertSeriesChunked(rows: List<VodSeriesEntity>, chunk: Int = 300) {
        rows.chunked(chunk).forEach { upsertSeries(it) }
    }

    // ---- Reconciliation ----------------------------------------------------

    @Query("DELETE FROM vod_movies WHERE playlistId = :playlistId AND syncedAt < :cutoff")
    suspend fun pruneMovies(playlistId: String, cutoff: Long): Int

    @Query("DELETE FROM vod_series WHERE playlistId = :playlistId AND syncedAt < :cutoff")
    suspend fun pruneSeries(playlistId: String, cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM vod_movies WHERE playlistId = :playlistId")
    suspend fun movieCount(playlistId: String): Int

    @Query("SELECT COUNT(*) FROM vod_series WHERE playlistId = :playlistId")
    suspend fun seriesCount(playlistId: String): Int

    /** Ids already known locally, for the delta walk's early stop. */
    @Query("SELECT uuid FROM vod_movies WHERE playlistId = :playlistId")
    suspend fun knownMovieUuids(playlistId: String): List<String>

    @Query("SELECT uuid FROM vod_series WHERE playlistId = :playlistId")
    suspend fun knownSeriesUuids(playlistId: String): List<String>

    // ---- Grid reads --------------------------------------------------------

    @Query(
        """
        SELECT id, uuid, title, posterUrl, year, rating, category, isPersonalLibrary
        FROM vod_movies
        WHERE playlistId = :playlistId
          AND (:library = '' OR libraryKey = :library)
        ORDER BY
          CASE WHEN :sort = 'title'  THEN sortTitle END COLLATE NOCASE ASC,
          CASE WHEN :sort = 'added'  THEN serverCreatedAt END DESC,
          CASE WHEN :sort = 'year'   THEN year END DESC,
          CASE WHEN :sort = 'rating' THEN CAST(rating AS REAL) END DESC,
          sortTitle COLLATE NOCASE ASC
        """
    )
    fun movieCards(playlistId: String, sort: String, library: String): Flow<List<VodCard>>

    @Query(
        """
        SELECT id, uuid, title, posterUrl, year, rating, category, isPersonalLibrary
        FROM vod_series
        WHERE playlistId = :playlistId
          AND (:library = '' OR libraryKey = :library)
        ORDER BY
          CASE WHEN :sort = 'title'  THEN sortTitle END COLLATE NOCASE ASC,
          CASE WHEN :sort = 'added'  THEN serverCreatedAt END DESC,
          CASE WHEN :sort = 'year'   THEN year END DESC,
          CASE WHEN :sort = 'rating' THEN CAST(rating AS REAL) END DESC,
          sortTitle COLLATE NOCASE ASC
        """
    )
    fun seriesCards(playlistId: String, sort: String, library: String): Flow<List<VodCard>>

    /** Recently Added for a Home row: newest first, capped. */
    @Query(
        """
        SELECT id, uuid, title, posterUrl, year, rating, category, isPersonalLibrary
        FROM vod_movies
        WHERE playlistId = :playlistId AND (:library = '' OR libraryKey = :library)
        ORDER BY serverCreatedAt DESC, syncedAt DESC
        LIMIT :limit
        """
    )
    fun recentMovies(playlistId: String, library: String, limit: Int = 30): Flow<List<VodCard>>

    @Query(
        """
        SELECT id, uuid, title, posterUrl, year, rating, category, isPersonalLibrary
        FROM vod_series
        WHERE playlistId = :playlistId AND (:library = '' OR libraryKey = :library)
        ORDER BY serverCreatedAt DESC, syncedAt DESC
        LIMIT :limit
        """
    )
    fun recentSeries(playlistId: String, library: String, limit: Int = 30): Flow<List<VodCard>>

    /** Instant full-library search, no network. */
    @Query(
        """
        SELECT id, uuid, title, posterUrl, year, rating, category, isPersonalLibrary
        FROM vod_movies
        WHERE playlistId = :playlistId AND (sortTitle LIKE :pattern OR title LIKE :pattern)
        ORDER BY sortTitle COLLATE NOCASE ASC LIMIT :limit
        """
    )
    suspend fun searchMovies(playlistId: String, pattern: String, limit: Int = 100): List<VodCard>

    @Query(
        """
        SELECT id, uuid, title, posterUrl, year, rating, category, isPersonalLibrary
        FROM vod_series
        WHERE playlistId = :playlistId AND (sortTitle LIKE :pattern OR title LIKE :pattern)
        ORDER BY sortTitle COLLATE NOCASE ASC LIMIT :limit
        """
    )
    suspend fun searchSeries(playlistId: String, pattern: String, limit: Int = 100): List<VodCard>

    /** Letter buckets for the alpha-jump rail: one row per starting letter with
     *  the index of its first item, computed entirely in SQL. */
    @Query(
        """
        SELECT UPPER(SUBSTR(sortTitle, 1, 1)) AS letter, COUNT(*) AS count
        FROM vod_movies
        WHERE playlistId = :playlistId AND (:library = '' OR libraryKey = :library)
        GROUP BY letter ORDER BY letter
        """
    )
    suspend fun movieLetterBuckets(playlistId: String, library: String): List<LetterBucket>

    // ---- Detail reads ------------------------------------------------------

    @Query("SELECT * FROM vod_movies WHERE playlistId = :playlistId AND uuid = :uuid LIMIT 1")
    suspend fun movieByUuid(playlistId: String, uuid: String): VodMovieEntity?

    @Query("SELECT * FROM vod_series WHERE playlistId = :playlistId AND uuid = :uuid LIMIT 1")
    suspend fun seriesByUuid(playlistId: String, uuid: String): VodSeriesEntity?

    /** Series routes carry the numeric server id, not the uuid. */
    @Query("SELECT * FROM vod_series WHERE playlistId = :playlistId AND id = :id LIMIT 1")
    suspend fun seriesByIdValue(playlistId: String, id: String): VodSeriesEntity?

    @Query(
        """
        SELECT * FROM vod_episodes
        WHERE playlistId = :playlistId AND seriesId = :seriesId
        ORDER BY seasonNumber ASC, episodeNumber ASC
        """
    )
    suspend fun episodesForSeries(playlistId: String, seriesId: String): List<VodEpisodeEntity>

    // ---- Libraries / filters ----------------------------------------------

    @Query(
        """
        SELECT * FROM vod_categories
        WHERE playlistId = :playlistId AND categoryType = :type AND enabled = 1
        ORDER BY isPersonalLibrary DESC, librarySource COLLATE NOCASE, name COLLATE NOCASE
        """
    )
    fun categories(playlistId: String, type: String): Flow<List<VodCategoryEntity>>

    @Query(
        """
        SELECT DISTINCT genre FROM vod_movies
        WHERE playlistId = :playlistId AND genre != '' ORDER BY genre COLLATE NOCASE
        """
    )
    suspend fun movieGenres(playlistId: String): List<String>
}

/** Grid/row projection: only what a poster card actually draws. */
data class VodCard(
    val id: String,
    val uuid: String,
    val title: String,
    val posterUrl: String,
    val year: Int?,
    val rating: String,
    val category: String,
    val isPersonalLibrary: Boolean,
)

data class LetterBucket(val letter: String, val count: Int)
