package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists WHERE isActive = 1 ORDER BY displayOrder ASC, createdAt DESC")
    fun observeActive(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE isActive = 1 ORDER BY displayOrder ASC, createdAt DESC LIMIT 1")
    suspend fun firstActive(): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists ORDER BY displayOrder ASC, createdAt DESC")
    suspend fun allOnce(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists ORDER BY displayOrder ASC, createdAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("UPDATE playlists SET displayOrder = :order WHERE id = :id")
    suspend fun setDisplayOrder(id: String, order: Int)

    /**
     * Persist the user's new playlist order in one transaction. Caller passes
     * the ids in their desired top-to-bottom order; we stamp displayOrder
     * 0..n-1 across the set.
     */
    @androidx.room.Transaction
    suspend fun applyDisplayOrder(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id -> setDisplayOrder(id, index) }
    }

    /**
     * Mark a single playlist row active, deactivating every other row in one
     * transaction. Used by the multi-playlist switcher. Without the wrapping
     * transaction a partial failure could leave the table with zero active
     * rows or two, both of which break the bootstrap path.
     */
    @androidx.room.Transaction
    suspend fun switchActive(targetId: String) {
        setAllInactive()
        setActiveById(targetId)
    }

    /**
     * Atomically deactivate every other row and upsert [entity] as the active
     * playlist. Mirrors iOS commit f72b942 (multi-server-add race fix): two
     * concurrent saveServer() calls used to interleave between the deactivate
     * pass and the upsert, leaving stale active rows or skipping the new one
     * entirely. Wrapping the whole sequence in a Room @Transaction serialises
     * concurrent inserts so the active-set invariant (exactly one active row)
     * holds.
     */
    @androidx.room.Transaction
    suspend fun upsertAsActive(entity: PlaylistEntity) {
        setAllInactive()
        upsert(entity)
    }

    @Query("UPDATE playlists SET isActive = 0")
    suspend fun setAllInactive()

    @Query("UPDATE playlists SET isActive = 1 WHERE id = :id")
    suspend fun setActiveById(id: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity)

    @Update
    suspend fun update(playlist: PlaylistEntity)

    /**
     * Targeted write of ONLY the three credential columns by id. Used by the
     * one-time at-rest re-encryption pass so it can re-encrypt credentials
     * without a full-row @Update that would clobber a concurrent targeted write
     * (displayOrder, lastRefreshedAt, channelCount, a 401-rebootstrapped apiKey)
     * landing in the same window. The EncryptingPlaylistDao override encrypts
     * these three values before they reach the column.
     */
    @Query("UPDATE playlists SET apiKey = :apiKey, username = :username, password = :password WHERE id = :id")
    suspend fun updateCredentials(id: String, apiKey: String?, username: String?, password: String?)

    /**
     * Targeted write of the channel count by id. Targeted rather than a full-row
     * @Update for the same reason as [updateCredentials]: this is written from
     * inside the channel-snapshot transaction, and a whole-row write there could
     * clobber a concurrent targeted write landing in the same window.
     */
    @Query("UPDATE playlists SET channelCount = :count WHERE id = :id")
    suspend fun updateChannelCount(id: String, count: Int)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists")
    suspend fun clear()
}
