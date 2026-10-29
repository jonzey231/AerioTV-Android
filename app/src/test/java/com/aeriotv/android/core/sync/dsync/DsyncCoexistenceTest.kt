package com.aeriotv.android.core.sync.dsync

import com.aeriotv.android.core.sync.SyncCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DsyncCoexistenceTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun sharedCategoriesExcludePlaylistsAndCredentials() {
        assertEquals(
            setOf(SyncCategory.Favorites, SyncCategory.WatchProgress, SyncCategory.Reminders, SyncCategory.Watchlist, SyncCategory.Preferences),
            DsyncCoexistence.SHARED_CATEGORIES,
        )
    }

    @Test fun ownsOnlyWhenAllThreeConditionsHold() {
        assertTrue(DsyncCoexistence.owns(SyncCategory.Favorites, true, true, true))
        assertFalse(DsyncCoexistence.owns(SyncCategory.Favorites, false, true, true))
        assertFalse(DsyncCoexistence.owns(SyncCategory.Favorites, true, false, true))
        // Before the first Dispatcharr pull, Drive may still apply (restore then enable).
        assertFalse(DsyncCoexistence.owns(SyncCategory.Favorites, true, true, false))
    }

    @Test fun neverOwnsDriveOnlyCategories() {
        assertFalse(DsyncCoexistence.owns(SyncCategory.Playlists, true, true, true))
        assertFalse(DsyncCoexistence.owns(SyncCategory.Credentials, true, true, true))
    }

    @Test fun emptyServerCopySeedsFromLocalOnFirstPull() {
        assertTrue(DsyncCoexistence.seedFromLocal(DsyncKey.Favorites, false, obj("""{"ids":[]}""")))
        assertTrue(DsyncCoexistence.seedFromLocal(DsyncKey.Progress, false, obj("""{"rows":[]}""")))
        assertTrue(DsyncCoexistence.seedFromLocal(DsyncKey.Settings, false, obj("""{"v":{},"tv":{}}""")))
        assertTrue(DsyncCoexistence.seedFromLocal(DsyncKey.HiddenGroups, false, obj("""{"live":[],"movies":[],"series":[]}""")))
        assertTrue(DsyncCoexistence.seedFromLocal(DsyncKey.GroupOrder, false, obj("""{"mode":"default","order":[]}""")))
        assertTrue(DsyncCoexistence.seedFromLocal(DsyncKey.Watchlist, false, obj("{}")))
    }

    @Test fun populatedOrAlreadyConsumedCopiesApplyAsBefore() {
        assertFalse(DsyncCoexistence.seedFromLocal(DsyncKey.Favorites, false, obj("""{"ids":[5]}""")))
        assertFalse(DsyncCoexistence.seedFromLocal(DsyncKey.GroupOrder, false, obj("""{"mode":"alphabetical","order":[]}""")))
        assertFalse(DsyncCoexistence.seedFromLocal(DsyncKey.RadioDefault, false, obj("""{"on":false}""")))
        // A later empty copy (another device cleared it) is a real change, not a seed.
        assertFalse(DsyncCoexistence.seedFromLocal(DsyncKey.Favorites, true, obj("""{"ids":[]}""")))
        assertFalse(DsyncCoexistence.seedFromLocal(DsyncKey.Profiles, false, obj("""{"rows":[]}""")))
    }
}
