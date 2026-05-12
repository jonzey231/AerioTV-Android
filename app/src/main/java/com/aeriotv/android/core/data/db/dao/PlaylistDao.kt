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

    @Query("SELECT * FROM playlists WHERE isActive = 1 ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE isActive = 1 ORDER BY createdAt DESC LIMIT 1")
    suspend fun firstActive(): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun allOnce(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

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

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists")
    suspend fun clear()
}
