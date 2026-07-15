package com.aeriotv.android.core.cast.companion

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt access to the companion-remote singletons from a Composable that isn't
 * ViewModel-scoped (GH #33). Mirrors DebugLoggerEntryPoint's usage via
 * EntryPointAccessors.fromApplication(...).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CompanionEntryPoint {
    fun discovery(): CompanionDiscovery
    fun remoteController(): CompanionRemoteController
}
