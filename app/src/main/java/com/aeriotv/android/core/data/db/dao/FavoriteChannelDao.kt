package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aeriotv.android.core.data.db.entity.FavoriteChannelEntity
import com.aeriotv.android.core.profiles.ProfileScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

@Dao
interface FavoriteChannelDao {
    // Profiles (DB v37): every read and write is scoped to ProfileScope.current().
    // The *In variants take the scope explicitly; the defaults below keep the
    // pre-profile call sites unchanged.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteChannelEntity)

    @Query("DELETE FROM favorite_channel WHERE channelId = :channelId AND profileScope = :scope")
    suspend fun deleteIn(channelId: String, scope: String)
    suspend fun delete(channelId: String) = deleteIn(channelId, ProfileScope.current())

    @Query("SELECT * FROM favorite_channel WHERE profileScope = :scope ORDER BY displayOrder ASC")
    fun observeAllIn(scope: String): Flow<List<FavoriteChannelEntity>>
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeAll(): Flow<List<FavoriteChannelEntity>> =
        ProfileScope.token.flatMapLatest { observeAllIn(it) }

    @Query("SELECT * FROM favorite_channel WHERE profileScope = :scope ORDER BY displayOrder ASC")
    suspend fun allOnceIn(scope: String): List<FavoriteChannelEntity>
    suspend fun allOnce(): List<FavoriteChannelEntity> = allOnceIn(ProfileScope.current())

    @Query("SELECT * FROM favorite_channel WHERE channelId = :channelId AND profileScope = :scope")
    suspend fun getOnceIn(channelId: String, scope: String): FavoriteChannelEntity?
    suspend fun getOnce(channelId: String): FavoriteChannelEntity? = getOnceIn(channelId, ProfileScope.current())

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_channel WHERE channelId = :channelId AND profileScope = :scope)")
    fun observeIsFavoriteIn(channelId: String, scope: String): Flow<Boolean>
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeIsFavorite(channelId: String): Flow<Boolean> =
        ProfileScope.token.flatMapLatest { observeIsFavoriteIn(channelId, it) }

    @Query("SELECT COUNT(*) FROM favorite_channel WHERE profileScope = :scope")
    fun observeCountIn(scope: String): Flow<Int>
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeCount(): Flow<Int> = ProfileScope.token.flatMapLatest { observeCountIn(it) }

    @Query("UPDATE favorite_channel SET displayOrder = :order WHERE channelId = :channelId AND profileScope = :scope")
    suspend fun setDisplayOrderIn(channelId: String, order: Long, scope: String)
    suspend fun setDisplayOrder(channelId: String, order: Long) =
        setDisplayOrderIn(channelId, order, ProfileScope.current())

    @androidx.room.Transaction
    suspend fun applyDisplayOrder(orderedChannelIds: List<String>) {
        val scope = ProfileScope.current()
        orderedChannelIds.forEachIndexed { index, id -> setDisplayOrderIn(id, index.toLong(), scope) }
    }

    /**
     * Dispatcharr Sync pull: delete and upsert inside ONE scope, in one
     * transaction. Rows stamped with another scope are skipped, never written.
     */
    @androidx.room.Transaction
    suspend fun applySyncIn(scope: String, deletes: List<String>, upserts: List<FavoriteChannelEntity>): Int {
        deletes.forEach { deleteIn(it, scope) }
        var n = 0
        upserts.forEach { if (it.profileScope == scope) { upsert(it); n++ } }
        return n
    }

    /** Remove-profile cleanup. */
    @Query("DELETE FROM favorite_channel WHERE profileScope = :scope")
    suspend fun deleteScope(scope: String)
}
