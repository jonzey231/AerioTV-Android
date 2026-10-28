package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aeriotv.android.core.data.db.entity.TmdbArtEntity

@Dao
interface TmdbArtDao {
    /** The whole cache; read once per process into memory. */
    @Query("SELECT * FROM tmdb_art")
    suspend fun getAll(): List<TmdbArtEntity>

    /**
     * The newest [limit] rows, overview left out (Apple parity 2026-10-27:
     * the persisted cache is capped and carries no synopsis text).
     */
    @Query("SELECT `key`, tmdbId, poster, backdrop, NULL AS overview, at FROM tmdb_art ORDER BY at DESC LIMIT :limit")
    suspend fun newest(limit: Int): List<TmdbArtEntity>

    @Query("SELECT COUNT(*) FROM tmdb_art")
    suspend fun count(): Int

    /** Keep [cap] rows: real posters before confirmed misses, newest first. */
    @Query("DELETE FROM tmdb_art WHERE `key` NOT IN (SELECT `key` FROM tmdb_art ORDER BY (poster != '') DESC, at DESC LIMIT :cap)")
    suspend fun pruneTo(cap: Int): Int

    /** Strip synopsis text persisted by older builds. */
    @Query("UPDATE tmdb_art SET overview = NULL WHERE overview IS NOT NULL")
    suspend fun clearOverviews(): Int

    @Query("SELECT * FROM tmdb_art WHERE `key` IN (:keys)")
    suspend fun forKeys(keys: List<String>): List<TmdbArtEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<TmdbArtEntity>)

    @Query("DELETE FROM tmdb_art")
    suspend fun deleteAll()
}
