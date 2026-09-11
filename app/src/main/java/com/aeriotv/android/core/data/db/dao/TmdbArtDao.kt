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

    @Query("SELECT * FROM tmdb_art WHERE `key` IN (:keys)")
    suspend fun forKeys(keys: List<String>): List<TmdbArtEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<TmdbArtEntity>)

    @Query("DELETE FROM tmdb_art")
    suspend fun deleteAll()
}
