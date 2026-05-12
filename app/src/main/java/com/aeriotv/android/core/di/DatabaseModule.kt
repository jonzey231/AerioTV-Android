package com.aeriotv.android.core.di

import android.content.Context
import androidx.room.Room
import com.aeriotv.android.core.data.db.AerioDatabase
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AerioDatabase =
        Room.databaseBuilder(context, AerioDatabase::class.java, "aerio.db")
            // Pre-1.0 app, no migration story yet; nuke and drop all tables on schema bump.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun providePlaylistDao(db: AerioDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideWatchProgressDao(db: AerioDatabase): WatchProgressDao = db.watchProgressDao()
}
