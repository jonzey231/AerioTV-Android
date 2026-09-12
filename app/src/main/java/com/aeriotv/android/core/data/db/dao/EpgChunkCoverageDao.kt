package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aeriotv.android.core.data.db.entity.EpgChunkCoverage

/**
 * Read/write side of the incremental EPG grid coverage map
 * (see [EpgChunkCoverage]).
 */
@Dao
interface EpgChunkCoverageDao {
    /** Upsert: the composite PK (playlistId, chunkStartMs) makes REPLACE an
     *  in-place refresh of the chunk's fetch stamp, never a duplicate row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: EpgChunkCoverage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<EpgChunkCoverage>)

    @Query("SELECT * FROM epg_chunk_coverage WHERE playlistId = :playlistId")
    suspend fun forPlaylist(playlistId: String): List<EpgChunkCoverage>

    /** Identity change / user Refresh: the coverage map dies with the rows it
     *  vouches for. */
    @Query("DELETE FROM epg_chunk_coverage WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)

    /**
     * Drop coverage for chunks that fell outside the playlist's current Guide
     * Days range. Shrinking Guide Days therefore forgets the chunks it no
     * longer wants; growing it leaves every kept row alone, so only the NEW
     * chunks are fetched.
     */
    @Query(
        "DELETE FROM epg_chunk_coverage WHERE playlistId = :playlistId " +
            "AND (chunkStartMs < :fromMs OR chunkEndMs > :toMs)"
    )
    suspend fun pruneOutside(playlistId: String, fromMs: Long, toMs: Long)
}
