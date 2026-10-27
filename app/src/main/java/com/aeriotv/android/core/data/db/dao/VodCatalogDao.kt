package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.aeriotv.android.core.data.db.entity.VodSweepLaneEntity
import com.aeriotv.android.core.data.db.entity.VodSweepStateEntity
import com.aeriotv.android.core.data.db.entity.VodTitleEntity

/**
 * Persisted VOD catalog (GH #109). Only the simple statements live here; the
 * sorted rowid scans and the grid's window reads are raw queries in
 * VodCatalogStore because their WHERE clause is built per filter.
 */
@Dao
interface VodCatalogDao {

    // ---- Titles

    /** Refresh a row a previous generation wrote. A row this generation
     *  already wrote is left alone: the first category to deliver a title
     *  keeps its group stamp, as the in-memory `seen` set used to decide. */
    @Query(
        "UPDATE vod_title SET title = :title, sortKey = :sortKey, bucket = :bucket, year = :year, " +
            "rating = :rating, ratingValue = :ratingValue, posterUrl = :posterUrl, category = :category, " +
            "tmdbId = :tmdbId, normTitle = :normTitle, cleanTitle = :cleanTitle, artKey = :artKey, " +
            "payload = :payload, generation = :generation " +
            "WHERE playlistKey = :playlistKey AND kind = :kind AND itemKey = :itemKey AND generation <> :generation"
    )
    suspend fun refreshOlder(
        playlistKey: String, kind: String, itemKey: String,
        title: String, sortKey: String, bucket: String, year: Int?, rating: String?, ratingValue: Double?,
        posterUrl: String?, category: String?, tmdbId: String?, normTitle: String, cleanTitle: String,
        artKey: String, payload: String, generation: Long,
    ): Int

    /** New title. IGNORE covers the same-generation duplicate. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: VodTitleEntity): Long

    @Query("SELECT COUNT(*) FROM vod_title WHERE playlistKey = :playlistKey AND kind = :kind")
    suspend fun count(playlistKey: String, kind: String): Int

    @Query("SELECT COUNT(*) FROM vod_title WHERE playlistKey = :playlistKey AND kind = :kind AND generation = :generation")
    suspend fun countGeneration(playlistKey: String, kind: String, generation: Long): Int

    @Query("SELECT * FROM vod_title WHERE playlistKey = :playlistKey AND kind = :kind AND itemKey = :itemKey LIMIT 1")
    suspend fun byItemKey(playlistKey: String, kind: String, itemKey: String): VodTitleEntity?

    /** Dispatcharr Sync: item key plus the start of the payload, which begins `{"id":<int>,`. */
    @Query("SELECT itemKey, substr(payload, 1, 24) AS head FROM vod_title WHERE playlistKey = :playlistKey AND kind = :kind")
    suspend fun itemHeads(playlistKey: String, kind: String): List<VodItemHead>

    @Query("SELECT * FROM vod_title WHERE playlistKey = :playlistKey AND kind = :kind AND tmdbId IN (:tmdbIds)")
    suspend fun byTmdbIds(playlistKey: String, kind: String, tmdbIds: List<String>): List<VodTitleEntity>

    /** Normalized-title match for rows WITHOUT a tmdb id (the cast search and
     *  Known For matcher only title-match entities that carry no id). */
    @Query(
        "SELECT * FROM vod_title WHERE playlistKey = :playlistKey AND kind = :kind " +
            "AND (tmdbId IS NULL OR tmdbId = '') AND normTitle IN (:titles)"
    )
    suspend fun byNormTitlesWithoutTmdb(playlistKey: String, kind: String, titles: List<String>): List<VodTitleEntity>

    @Query("SELECT * FROM vod_title WHERE playlistKey = :playlistKey AND kind = :kind AND cleanTitle IN (:titles)")
    suspend fun byCleanTitles(playlistKey: String, kind: String, titles: List<String>): List<VodTitleEntity>

    /** Client-side search for sources without a server search (Xtream). */
    @Query(
        "SELECT * FROM vod_title WHERE playlistKey = :playlistKey AND kind = :kind " +
            "AND title LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY sortKey LIMIT :limit"
    )
    suspend fun searchTitle(playlistKey: String, kind: String, query: String, limit: Int): List<VodTitleEntity>

    /** Groups present in the stored catalog (sources with no sweep lanes). */
    @Query(
        "SELECT DISTINCT category FROM vod_title WHERE playlistKey = :playlistKey AND kind = :kind " +
            "AND category IS NOT NULL ORDER BY category"
    )
    suspend fun distinctCategories(playlistKey: String, kind: String): List<String>

    /** Keyset page for the background TMDB art pass. */
    @Query(
        "SELECT id, artKey, title FROM vod_title WHERE playlistKey = :playlistKey AND kind = :kind " +
            "AND id > :afterId ORDER BY id LIMIT :limit"
    )
    suspend fun artPage(playlistKey: String, kind: String, afterId: Long, limit: Int): List<VodArtRow>

    @Query(
        "DELETE FROM vod_title WHERE playlistKey = :playlistKey AND kind = :kind AND generation < :generation " +
            "AND (category IS NULL OR category NOT IN (:keepCategories))"
    )
    suspend fun deleteOlderGenerations(playlistKey: String, kind: String, generation: Long, keepCategories: List<String>): Int

    @Query("DELETE FROM vod_title WHERE playlistKey = :playlistKey AND kind = :kind")
    suspend fun deleteKind(playlistKey: String, kind: String)

    // ---- Identity pruning

    @Query("SELECT DISTINCT playlistKey FROM vod_sweep_state")
    suspend fun knownPlaylistKeys(): List<String>

    @Query("SELECT DISTINCT playlistKey FROM vod_title")
    suspend fun titlePlaylistKeys(): List<String>

    @Query("DELETE FROM vod_title WHERE playlistKey = :playlistKey")
    suspend fun deleteTitlesFor(playlistKey: String)

    @Query("DELETE FROM vod_sweep_state WHERE playlistKey = :playlistKey")
    suspend fun deleteStateFor(playlistKey: String)

    @Query("DELETE FROM vod_sweep_lane WHERE playlistKey = :playlistKey")
    suspend fun deleteLanesFor(playlistKey: String)

    // Identity re-key (v1 with the API key hash -> v2 without it).
    @Query("UPDATE vod_title SET playlistKey = :newKey WHERE playlistKey = :oldKey")
    suspend fun rekeyTitles(oldKey: String, newKey: String)

    @Query("UPDATE vod_sweep_state SET playlistKey = :newKey WHERE playlistKey = :oldKey")
    suspend fun rekeyStates(oldKey: String, newKey: String)

    @Query("UPDATE vod_sweep_lane SET playlistKey = :newKey WHERE playlistKey = :oldKey")
    suspend fun rekeyLanes(oldKey: String, newKey: String)

    @Query("SELECT MAX(completedAtMs) FROM vod_sweep_state WHERE playlistKey = :playlistKey")
    suspend fun newestCompletion(playlistKey: String): Long?

    // ---- Sweep state

    @Query("SELECT * FROM vod_sweep_state WHERE playlistKey = :playlistKey AND kind = :kind")
    suspend fun state(playlistKey: String, kind: String): VodSweepStateEntity?

    @Upsert
    suspend fun upsertState(row: VodSweepStateEntity)

    @Query("SELECT * FROM vod_sweep_lane WHERE playlistKey = :playlistKey AND kind = :kind")
    suspend fun lanes(playlistKey: String, kind: String): List<VodSweepLaneEntity>

    @Upsert
    suspend fun upsertLane(row: VodSweepLaneEntity)

    @Query("DELETE FROM vod_sweep_lane WHERE playlistKey = :playlistKey AND kind = :kind")
    suspend fun deleteLanes(playlistKey: String, kind: String)

    @Query("DELETE FROM vod_sweep_lane WHERE playlistKey = :playlistKey AND kind = :kind AND lane = :lane")
    suspend fun deleteLane(playlistKey: String, kind: String, lane: String)
}

/** Projection for [VodCatalogDao.artPage]. */
data class VodArtRow(val id: Long, val artKey: String, val title: String)

/** Item key and the first bytes of its payload (Dispatcharr Sync id lookup). */
data class VodItemHead(val itemKey: String, val head: String)
