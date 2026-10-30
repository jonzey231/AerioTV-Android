package com.aeriotv.android.core.sync.dsync

import com.aeriotv.android.core.sync.dsync.DsyncReadiness.Cache
import com.aeriotv.android.core.sync.dsync.DsyncReadiness.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DsyncReadinessTest {

    @Test fun cacheDependencies() {
        listOf(DsyncKey.Favorites, DsyncKey.Recents, DsyncKey.Reminders, DsyncKey.Collections)
            .forEach { assertEquals(it.name, Cache.Channels, DsyncReadiness.cacheFor(it)) }
        listOf(DsyncKey.Progress, DsyncKey.Watchlist, DsyncKey.HiddenTitles)
            .forEach { assertEquals(it.name, Cache.VodCatalog, DsyncReadiness.cacheFor(it)) }
        listOf(DsyncKey.Settings, DsyncKey.GroupOrder, DsyncKey.HiddenGroups, DsyncKey.Profiles)
            .forEach { assertNull(it.name, DsyncReadiness.cacheFor(it)) }
    }

    /** Field run 2026-10-30: eight progress rows, catalog not built yet. */
    @Test fun progressBeforeCatalogDefers() {
        assertEquals(Decision.Defer, DsyncReadiness.decide(DsyncKey.Progress, cacheReady = false, unresolvedRows = 8))
    }

    @Test fun progressAllResolvedPushesWithoutCatalog() {
        assertEquals(Decision.Push, DsyncReadiness.decide(DsyncKey.Progress, cacheReady = false, unresolvedRows = 0))
    }

    @Test fun catalogReadyMissesAreRealAndPush() {
        assertEquals(Decision.Push, DsyncReadiness.decide(DsyncKey.Watchlist, cacheReady = true, unresolvedRows = 3))
    }

    @Test fun channelKeysWaitForChannelMap() {
        assertEquals(Decision.Defer, DsyncReadiness.decide(DsyncKey.Favorites, cacheReady = false, unresolvedRows = 0))
        assertEquals(Decision.Push, DsyncReadiness.decide(DsyncKey.Favorites, cacheReady = true, unresolvedRows = 0))
    }

    @Test fun uncachedKeysAlwaysPush() {
        assertEquals(Decision.Push, DsyncReadiness.decide(DsyncKey.Settings, cacheReady = false, unresolvedRows = 5))
    }

    @Test fun skippedRowsKeepKeyDirty() {
        assertTrue(DsyncReadiness.dirtyAfter(Decision.Defer, putOk = false, dirtyBefore = false))
        assertTrue(DsyncReadiness.dirtyAfter(Decision.Defer, putOk = false, dirtyBefore = true))
    }

    @Test fun successfulPushClearsDirty() {
        assertFalse(DsyncReadiness.dirtyAfter(Decision.Push, putOk = true, dirtyBefore = true))
        assertTrue(DsyncReadiness.dirtyAfter(Decision.Push, putOk = false, dirtyBefore = true))
    }
}
