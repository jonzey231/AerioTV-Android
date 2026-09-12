package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aeriotv.android.core.data.db.entity.ChannelSnapshotEntity

@Dao
interface ChannelSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ChannelSnapshotEntity>)

    /** Cached channels for a playlist, in the same order they were saved. */
    @Query("SELECT * FROM channel_snapshot WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun forPlaylist(playlistId: String): List<ChannelSnapshotEntity>

    /** Most recent fetch time for this source, or null when nothing is cached. */
    @Query("SELECT MAX(fetchedAt) FROM channel_snapshot WHERE playlistId = :playlistId")
    suspend fun newestFetchedAt(playlistId: String): Long?

    /**
     * Largest catch-up window any channel of this playlist exposes, in days
     * (null when nothing is cached, 0 when no channel has an archive). The
     * EPG catch-up reach prune and the grid history clamp both need only this
     * one number, so they ask for it instead of reading the whole snapshot.
     */
    @Query("SELECT MAX(catchupDays) FROM channel_snapshot WHERE playlistId = :playlistId")
    suspend fun maxCatchupDays(playlistId: String): Int?

    @Query("DELETE FROM channel_snapshot WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)

    /**
     * Replace the whole cached snapshot for one playlist in a single transaction
     * so a reader never sees a half-written batch (matches EpgProgrammeDao
     * replaceForPlaylist).
     */
    @Transaction
    suspend fun replaceForPlaylist(playlistId: String, rows: List<ChannelSnapshotEntity>) {
        deleteForPlaylist(playlistId)
        insertAll(rows)
    }
}
