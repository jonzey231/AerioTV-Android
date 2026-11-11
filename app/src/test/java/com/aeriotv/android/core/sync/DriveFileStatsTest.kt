package com.aeriotv.android.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveFileStatsTest {
    @Test fun countsLiveAndRemovedWithoutValues() {
        val body = """{"envelope":{"lastModified":1000,"device":"Phone","format":2},
            "entries":[{"channelId":"a","channelName":"Secret Name","displayOrder":0,"addedAt":5},
                       {"channelId":"b","channelName":"Other","displayOrder":1,"addedAt":6,"removedAt":9}]}"""
        val line = DriveFileStats.line(SyncCategory.Favorites, body)
        assertTrue(line, line.contains("live=1 removed=1"))
        assertTrue(line, line.contains("fmt=2 by=Phone"))
        assertFalse(line, line.contains("Secret"))
    }

    @Test fun credentialsLineNeverCarriesAKey() {
        val body = """{"envelope":{"lastModified":1000,"device":"Phone"},
            "entries":[{"playlistId":"p1","username":"user","password":"pw","apiKey":"KEYVALUE","updatedAt":7}]}"""
        val line = DriveFileStats.line(SyncCategory.Credentials, body)
        assertTrue(line, line.contains("live=1 removed=0"))
        assertTrue(line, line.contains("fmt=none"))
        listOf("KEYVALUE", "user", "pw").forEach { assertFalse(line, line.contains(it)) }
    }

    @Test fun prefKeysOnlyAndMissingFile() {
        val body = """{"envelope":{"lastModified":1000,"device":"Phone"},"keys":{"tmdbApiKey":"SECRET"},"times":{"v:tmdbApiKey":5000,"v:old":10}}"""
        val keys = DriveFileStats.recentPrefKeys(body, 100)
        assertTrue(keys, keys.startsWith("v:tmdbApiKey@"))
        assertFalse(keys, keys.contains("SECRET") || keys.contains("v:old"))
        assertEquals("absent", DriveFileStats.line(SyncCategory.Preferences, null))
        assertEquals("unreadable", DriveFileStats.line(SyncCategory.Preferences, "not json"))
    }
}
