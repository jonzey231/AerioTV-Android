package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aeriotv.android.core.data.db.entity.LocalRecordingEntity
import com.aeriotv.android.core.profiles.ProfileScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

@Dao
interface LocalRecordingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalRecordingEntity): Long

    // Profiles (DB v37): the recordings index is per profile.
    @Query("SELECT * FROM local_recording WHERE profileScope = :scope ORDER BY startedAt DESC")
    fun observeAllIn(scope: String): Flow<List<LocalRecordingEntity>>
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeAll(): Flow<List<LocalRecordingEntity>> = ProfileScope.token.flatMapLatest { observeAllIn(it) }

    @Query("DELETE FROM local_recording WHERE id = :id")
    suspend fun delete(id: Long)

    /** A row by id, any profile; null when it is gone. */
    @Query("SELECT * FROM local_recording WHERE id = :id")
    suspend fun byId(id: Long): LocalRecordingEntity?

    /** Rewrite an existing row; 0 when it was removed meanwhile (never re-creates it). */
    @androidx.room.Update
    suspend fun update(entity: LocalRecordingEntity): Int

    /** Rows still marked recording, any profile (InterruptedCapture). */
    @Query("SELECT * FROM local_recording WHERE status = 'recording'")
    suspend fun recordingRows(): List<LocalRecordingEntity>

    @Query("SELECT * FROM local_recording WHERE profileScope = :scope")
    suspend fun allOnceIn(scope: String): List<LocalRecordingEntity>

    @Query("DELETE FROM local_recording WHERE profileScope = :scope")
    suspend fun deleteScope(scope: String)

    /** Every on-device recording that came from [playlistId], any profile. */
    @Query("SELECT * FROM local_recording WHERE playlistId = :playlistId")
    suspend fun allForPlaylist(playlistId: String): List<LocalRecordingEntity>

    /**
     * A playlist delete that keeps its recordings: they belong to no playlist
     * now ("Saved on this device", the default profile's DVR under every
     * playlist), played from the file only. Returns how many.
     */
    @Query("UPDATE local_recording SET playlistId = NULL, profileScope = '' WHERE playlistId = :playlistId")
    suspend fun detachPlaylist(playlistId: String): Int

    @Query("DELETE FROM local_recording WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)
}
