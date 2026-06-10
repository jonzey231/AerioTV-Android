package com.aeriotv.android.core.update

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** github flavor: bind the real GitHub-releases updater. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds
    @Singleton
    abstract fun bindUpdateManager(impl: GithubUpdateManager): UpdateManager
}
