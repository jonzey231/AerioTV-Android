package com.aeriotv.android.core.sync

import com.aeriotv.android.core.sync.PlaylistIdentity.CredView
import com.aeriotv.android.core.sync.PlaylistIdentity.CredentialSource
import com.aeriotv.android.core.sync.PlaylistIdentity.Profile
import com.aeriotv.android.core.sync.PlaylistIdentity.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistIdentityTest {

    // ── identity ──

    @Test fun urlNormalizationDropsDefaultPortsCaseAndTrailingSlash() {
        assertEquals("https://tv.example.com", PlaylistIdentity.normalizeUrl("HTTPS://TV.Example.com:443/"))
        assertEquals("http://10.0.0.5", PlaylistIdentity.normalizeUrl("http://10.0.0.5:80"))
        assertEquals("http://10.0.0.5:9191", PlaylistIdentity.normalizeUrl("10.0.0.5:9191/"))
        assertEquals("https://h.example/tv", PlaylistIdentity.normalizeUrl("https://h.example/tv//"))
        assertEquals("http://h.example/get.php?u=a", PlaylistIdentity.normalizeUrl("http://H.example/get.php?u=a", keepQuery = true))
        assertNull(PlaylistIdentity.normalizeUrl("not a url"))
    }

    @Test fun keyFingerprintIsEightBytesAndNeverTheKey() {
        val fp = PlaylistIdentity.keyFingerprint("SECRETKEY123")
        assertEquals(16, fp.length)
        assertFalse(fp.contains("SECRET"))
        assertEquals(fp, PlaylistIdentity.keyFingerprint(" SECRETKEY123 "))
        assertNotEquals(fp, PlaylistIdentity.keyFingerprint("OTHER"))
    }

    @Test fun oneAccountOneIdentityAcrossAuthModes() {
        // API-key row whose username is known and a username/password row: one account.
        val a = PlaylistIdentity.of("DispatcharrApiKey", "https://tv.example.com/", "Logan", "k-old")
        val b = PlaylistIdentity.of("DispatcharrUserPass", "HTTPS://tv.example.com:443", "logan", null)
        assertEquals(a, b)
        // Without a username, the key's fingerprint stands in: a different key is another account.
        assertNotEquals(
            PlaylistIdentity.of("DispatcharrApiKey", "https://tv.example.com", null, "k1"),
            PlaylistIdentity.of("DispatcharrApiKey", "https://tv.example.com", null, "k2"),
        )
        // Another user, server, or type is another playlist.
        assertNotEquals(a, PlaylistIdentity.of("DispatcharrUserPass", "https://tv.example.com", "sam", null))
        assertNotEquals(a, PlaylistIdentity.of("DispatcharrUserPass", "https://tv2.example.com", "logan", null))
        assertNotEquals(PlaylistIdentity.of("XtreamCodes", "http://x.example", "u", null), PlaylistIdentity.of("XtreamCodes", "http://x.example", "U", null))
        assertEquals(PlaylistIdentity.of("M3uUrl", "http://m.example/list.m3u?k=1", null, null), PlaylistIdentity.of("M3uUrl", "HTTP://m.example:80/list.m3u?k=1", null, null))
        assertNull(PlaylistIdentity.of("DispatcharrApiKey", "https://tv.example.com", null, null))
    }

    // ── grouping and winner ──

    @Test fun groupsOnlyRowsThatShareAnIdentity() {
        val rows = listOf(Row("b", "x", 1), Row("a", "x", 2), Row("c", "y", 3), Row("d", null, 4), Row("e", null, 5))
        val g = PlaylistIdentity.groups(rows)
        assertEquals(1, g.size)
        assertEquals(listOf("a", "b"), g.single().map { it.id })
    }

    @Test fun winnerIsNewestThenSmallerLowercaseId() {
        assertEquals("b", PlaylistIdentity.winner(listOf(Row("a", "x", 1), Row("b", "x", 2))).id)
        // Tie: the smaller lowercase id, whatever the case it was written in.
        assertEquals("35CD", PlaylistIdentity.winner(listOf(Row("e831", "x", 0), Row("35CD", "x", 0))).id)
        assertEquals("ab", PlaylistIdentity.winner(listOf(Row("AC", "x", 0), Row("ab", "x", 0))).id)
    }

    @Test fun onEqualChangeTimesTheOlderPlaylistStays() {
        // The original (created first) beats a later copy, whatever their ids.
        assertEquals("e831", PlaylistIdentity.winner(listOf(Row("e831", "x", 5, createdAt = 100), Row("35cd", "x", 5, createdAt = 900))).id)
        // A newer change still wins first.
        assertEquals("35cd", PlaylistIdentity.winner(listOf(Row("e831", "x", 5, createdAt = 100), Row("35cd", "x", 6, createdAt = 900))).id)
        // Same creation time: the smaller lowercase id.
        assertEquals("AB", PlaylistIdentity.winner(listOf(Row("ac", "x", 5, createdAt = 100), Row("AB", "x", 5, createdAt = 100))).id)
        // A creation time unknown on a tied row: the id rule.
        assertEquals("35cd", PlaylistIdentity.winner(listOf(Row("e831", "x", 5, createdAt = 100), Row("35cd", "x", 5, createdAt = null))).id)
        // Rows not tied on change time do not affect the tie-break.
        assertEquals("b", PlaylistIdentity.winner(listOf(Row("a", "x", 1, createdAt = 1), Row("b", "x", 5, createdAt = 50), Row("c", "x", 5, createdAt = 60))).id)
    }

    // ── credentials ──

    private fun cv(has: Boolean = true, rejected: Boolean = false, inUse: Boolean = false, at: Long = 0) = CredView(has, rejected, inUse, at)

    @Test fun aWorkingSignInIsNeverGivenUpForARejectedOrEmptyOne() {
        assertEquals(CredentialSource.Loser, PlaylistIdentity.credentialSource(cv(rejected = true), cv()))
        assertEquals(CredentialSource.Loser, PlaylistIdentity.credentialSource(cv(has = false), cv()))
        assertEquals(CredentialSource.Winner, PlaylistIdentity.credentialSource(cv(), cv(rejected = true, inUse = true, at = 9)))
        assertEquals(CredentialSource.Winner, PlaylistIdentity.credentialSource(cv(rejected = true), cv(has = false)))
    }

    @Test fun theSignInInUseHereIsTheWorkingOneThenNewerWins() {
        // The stale winner was never used here; the loser is the playlist on screen.
        assertEquals(CredentialSource.Loser, PlaylistIdentity.credentialSource(cv(at = 5), cv(inUse = true, at = 1)))
        assertEquals(CredentialSource.Winner, PlaylistIdentity.credentialSource(cv(inUse = true), cv(at = 9)))
        assertEquals(CredentialSource.Loser, PlaylistIdentity.credentialSource(cv(at = 1), cv(at = 2)))
        assertEquals(CredentialSource.Winner, PlaylistIdentity.credentialSource(cv(at = 2), cv(at = 2)))
    }

    // ── profiles ──

    @Test fun profilesMoveAndOneDefaultRemains() {
        val winner = listOf(Profile("w0", "logan", true), Profile("w1", "sam", false))
        val loser = listOf(Profile("l0", "logan", true), Profile("l1", "sam", false), Profile("l2", "kid", false))
        // Winner's sign-in kept: its profiles win clashes; the loser's extra profile moves.
        val keepWinner = PlaylistIdentity.profilePlan(winner, loser, CredentialSource.Winner)
        assertEquals(listOf("l2"), keepWinner.move)
        assertEquals(listOf("l0", "l1"), keepWinner.drop)
        // Loser's sign-in kept: its profiles (the ones in use here) win clashes.
        val keepLoser = PlaylistIdentity.profilePlan(winner, loser, CredentialSource.Loser)
        assertEquals(listOf("l0", "l1", "l2"), keepLoser.move)
        assertEquals(listOf("w0", "w1"), keepLoser.drop)
        // A winner with no profiles here takes all of the loser's.
        assertEquals(listOf("l0", "l1", "l2"), PlaylistIdentity.profilePlan(emptyList(), loser, CredentialSource.Winner).move)
    }

    @Test fun planActivatesTheWinnerWhenTheLoserWasInUse() {
        val p = PlaylistIdentity.plan(
            Row("w", "x", 0), Row("l", "x", 0), loserActive = true,
            winnerCred = cv(rejected = true), loserCred = cv(inUse = true),
            winnerProfiles = emptyList(), loserProfiles = listOf(Profile("l0", "logan", true)),
        )
        assertTrue(p.activateWinner)
        assertEquals(CredentialSource.Loser, p.credentials)
        assertEquals(listOf("l0"), p.profiles.move)
    }

    // ── per-playlist settings ──

    @Test fun settingsKeysMoveButCacheKeysStay() {
        val l = "e831a7c4-34ff-43bb-a264-8bfe7f4b2a5d"
        val w = "35cd15d1-0000-4000-8000-000000000000"
        assertEquals("live_group_token_$w@prof:p1", PlaylistIdentity.movedKeyName("live_group_token_$l@prof:p1", l, w))
        assertEquals("catchupTimeOffsetMinutes.$w", PlaylistIdentity.movedKeyName("catchupTimeOffsetMinutes.$l", l, w))
        assertNull(PlaylistIdentity.movedKeyName("epg_identity_hash_$l", l, w))
        assertNull(PlaylistIdentity.movedKeyName("epg_cache_epoch.$l", l, w))
        assertNull(PlaylistIdentity.movedKeyName("dispatcharr_account_facts_$l", l, w))
        assertNull(PlaylistIdentity.movedKeyName("selected_theme", l, w))
    }
}
