package com.aeriotv.android.core.update

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Play-flavor stub. Play policy forbids self-update of Play-distributed
 * builds; this flavor carries no REQUEST_INSTALL_PACKAGES permission and no
 * updater code path. Every UI entry point hides itself off [isEnabled].
 * Play's own store mechanism delivers updates to this lineage.
 */
@Singleton
class NoOpUpdateManager @Inject constructor() : UpdateManager {
    override val isEnabled: Boolean = false
    override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle)
    override suspend fun check(manual: Boolean) = Unit
    override fun startDownload() = Unit
    override fun install() = Unit
    override fun skipAvailableVersion() = Unit
    override fun dismissError() = Unit
    override suspend fun resumePending() = Unit
}

/** play flavor: bind the inert stub. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds
    @Singleton
    abstract fun bindUpdateManager(impl: NoOpUpdateManager): UpdateManager
}
