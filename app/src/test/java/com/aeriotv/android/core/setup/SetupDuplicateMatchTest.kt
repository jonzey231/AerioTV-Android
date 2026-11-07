package com.aeriotv.android.core.setup

import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupDuplicateMatchTest {

    private fun row(type: SourceType, url: String, apiKey: String? = null, user: String? = null) =
        PlaylistEntity(name = "Home", urlString = url, sourceType = type.name, apiKey = apiKey, username = user)

    @Test fun dispatcharrSameKeyDifferentCaseHostAndSlash() {
        val r = row(SourceType.DispatcharrApiKey, "https://tv.example.com/", apiKey = "k1")
        assertTrue(SetupDuplicateMatch.matches(SourceType.DispatcharrApiKey, "HTTPS://TV.Example.com", "k1", null, r))
    }

    @Test fun dispatcharrDefaultPortIsSamePort() {
        val r = row(SourceType.DispatcharrApiKey, "http://10.0.0.5:80", apiKey = "k1")
        assertTrue(SetupDuplicateMatch.matches(SourceType.DispatcharrApiKey, "http://10.0.0.5", "k1", null, r))
    }

    @Test fun dispatcharrDifferentPortOrPathIsNotAMatch() {
        val r = row(SourceType.DispatcharrApiKey, "http://10.0.0.5:9191", apiKey = "k1")
        assertFalse(SetupDuplicateMatch.matches(SourceType.DispatcharrApiKey, "http://10.0.0.5:9192", "k1", null, r))
        assertFalse(SetupDuplicateMatch.matches(SourceType.DispatcharrApiKey, "http://10.0.0.5:9191/tv", "k1", null, r))
    }

    @Test fun dispatcharrSchemeMatters() {
        val r = row(SourceType.DispatcharrApiKey, "http://tv.example.com", apiKey = "k1")
        assertFalse(SetupDuplicateMatch.matches(SourceType.DispatcharrApiKey, "https://tv.example.com", "k1", null, r))
    }

    @Test fun dispatcharrDifferentUserIsNotAMatch() {
        val r = row(SourceType.DispatcharrApiKey, "https://tv.example.com", apiKey = "k1")
        assertFalse(SetupDuplicateMatch.matches(SourceType.DispatcharrApiKey, "https://tv.example.com", "k2", null, r))
    }

    @Test fun dispatcharrAuthModesShareAFamilyByUsername() {
        val r = row(SourceType.DispatcharrUserPass, "https://tv.example.com", user = "Logan")
        assertTrue(SetupDuplicateMatch.matches(SourceType.DispatcharrUserPass, "https://tv.example.com", null, "logan", r))
    }

    @Test fun xtreamNeedsSameUsername() {
        val r = row(SourceType.XtreamCodes, "http://xc.example.com:8080", user = "alice")
        assertTrue(SetupDuplicateMatch.matches(SourceType.XtreamCodes, "http://xc.example.com:8080/", null, "alice", r))
        assertFalse(SetupDuplicateMatch.matches(SourceType.XtreamCodes, "http://xc.example.com:8080", null, "bob", r))
    }

    @Test fun m3uComparesTheWholePlaylistUrl() {
        val r = row(SourceType.M3uUrl, "http://host.example.com/list.m3u?u=1")
        assertTrue(SetupDuplicateMatch.matches(SourceType.M3uUrl, "http://HOST.example.com/list.m3u?u=1", null, null, r))
        assertFalse(SetupDuplicateMatch.matches(SourceType.M3uUrl, "http://host.example.com/list.m3u?u=2", null, null, r))
    }

    @Test fun differentSourceTypeIsNotAMatch() {
        val r = row(SourceType.XtreamCodes, "http://tv.example.com", user = "a")
        assertFalse(SetupDuplicateMatch.matches(SourceType.DispatcharrUserPass, "http://tv.example.com", null, "a", r))
    }

    @Test fun findReturnsTheMatchingRow() {
        val a = row(SourceType.M3uUrl, "http://a.example.com/x.m3u")
        val b = row(SourceType.DispatcharrApiKey, "https://tv.example.com", apiKey = "k1")
        assertEquals(b, SetupDuplicateMatch.find(SourceType.DispatcharrApiKey, "https://tv.example.com/", "k1", null, listOf(a, b)))
        assertNull(SetupDuplicateMatch.find(SourceType.DispatcharrApiKey, "https://other.example.com", "k1", null, listOf(a, b)))
    }
}
