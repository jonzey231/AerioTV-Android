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

    // ---- 2026-11-01 field run: forced replace must mirror Google Drive ----

    @Test fun forcedApplyReplacesEverySharedCategory() {
        listOf(SyncCategory.Preferences, SyncCategory.Favorites, SyncCategory.WatchProgress, SyncCategory.Reminders, SyncCategory.Watchlist)
            .forEach { assertTrue(it.name, DsyncCoexistence.replaceOnForcedApply(it)) }
        listOf(SyncCategory.Playlists, SyncCategory.Credentials)
            .forEach { assertFalse(it.name, DsyncCoexistence.replaceOnForcedApply(it)) }
    }

    @Test fun deferredForcedProgressStaysArmedAndRetries() {
        // A deferral keeps the key dirty (and the persisted armed flag is only cleared on a send).
        assertTrue(DsyncReadiness.dirtyAfter(DsyncReadiness.decide(DsyncKey.Progress, cacheReady = false, unresolvedRows = 8), putOk = false, dirtyBefore = true))
        assertEquals(DsyncReadiness.Decision.Defer, DsyncReadiness.decide(DsyncKey.Progress, false, 8))
        // Catalog built: the waiting armed key retries; unarmed or not waiting does not go through this path.
        assertTrue(DsyncCoexistence.forcedRetryDue(armed = true, awaiting = true, cacheReady = true))
        assertFalse(DsyncCoexistence.forcedRetryDue(armed = true, awaiting = true, cacheReady = false))
        assertFalse(DsyncCoexistence.forcedRetryDue(armed = false, awaiting = true, cacheReady = true))
        assertFalse(DsyncCoexistence.forcedRetryDue(armed = true, awaiting = false, cacheReady = true))
        // After the catalog is built, untranslated rows no longer hold the push.
        assertEquals(DsyncReadiness.Decision.PushPartial, DsyncReadiness.decide(DsyncKey.Progress, true, 2))
        assertTrue(DsyncKey.Progress in DsyncCoexistence.FORCE_KEYS)
    }

    @Test fun forcedSettingsMirrorCountMatchesDriveProbe() {
        val drive = mapOf(
            "v" to mapOf("a" to "1", "b" to "true", "c" to "x", "d" to "2", "e" to "y"),
            "tv" to mapOf("f" to "3"),
            "mobile" to mapOf("g" to "z", "blank" to ""),
        )
        val mirror = drive.mapValues { (_, m) -> m.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) } }
        // The server held more names than Drive; the forced build keeps only non-lane fields of its base.
        val serverBase = obj("""{"v":{"a":"9","old1":"q","old2":"r"},"tv":{"old3":"s"},"mobile":{"old4":"t"}}""")
        val base = JsonObject(serverBase.filterKeys { it !in DsyncPayloads.SETTINGS_LANES })
        val data = DsyncPayloads.encodeSettingsLanes(base, mirror)
        val driveCat = com.aeriotv.android.core.sync.DriveCanon.settingsCategory(drive, 0L)
        assertEquals(7, driveCat.rows)
        assertEquals(driveCat.rows, DsyncCoexistence.rowCount(DsyncKey.Settings, data))
        assertEquals(driveCat.fingerprint, DsyncCoexistence.fingerprint(DsyncKey.Settings, data))
        // The old merge over the server lanes is what produced the extra rows.
        assertEquals(11, DsyncCoexistence.rowCount(DsyncKey.Settings, DsyncPayloads.encodeSettingsLanes(serverBase, mirror)))
    }
}
