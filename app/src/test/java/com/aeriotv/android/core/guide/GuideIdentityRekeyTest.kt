package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.M3UChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Items 4 and 5 (2026-10-27): per-channel re-key purge, stamp only after a saved fetch. */
class GuideIdentityRekeyTest {
    private fun ch(uuid: String, key: String) = M3UChannel(
        id = "disp:$uuid", name = uuid, url = "http://srv/proxy/ts/stream/$uuid", tvgID = key,
    )
    private val g = GuideIdentityHash
    private val server = g.serverFingerprint("DispatcharrApiKey", "http://srv/")
    private val admin = (1..10).map { ch("u$it", "k$it") }

    @Test
    fun onlyTheReKeyedChannelsAreNamed() {
        val stamp = g.merged("", server, g.entries(admin), reset = false)
        val now = admin.map { if (it.id == "disp:u2" || it.id == "disp:u7") it.copy(tvgID = "other") else it }
        val ids = g.rekeyedCanonicalIds(stamp, server, now)
        assertEquals(setOf("disp:u2", "disp:u7"), ids)
        // A brand-new channel is not re-keyed.
        assertTrue(g.rekeyedCanonicalIds(stamp, server, admin + ch("u99", "k99")).isEmpty())
        // Another server or a legacy stamp names nothing (the full purge applies).
        assertTrue(g.rekeyedCanonicalIds(stamp, g.serverFingerprint("DispatcharrApiKey", "http://x"), now).isEmpty())
        assertTrue(g.rekeyedCanonicalIds(GuideIdentityHash.of(admin), server, now).isEmpty())
    }

    @Test
    fun mergeAfterReKeyDropsTheOldEntriesAndKeepsTheRest() {
        val stamp = g.merged("", server, g.entries(admin), reset = false)
        val now = admin.map { if (it.id == "disp:u2") it.copy(tvgID = "other") else it }
        val ids = g.rekeyedCanonicalIds(stamp, server, now)
        val next = g.merged(stamp, server, g.entries(now), reset = false, rekeyedCanonicalIds = ids)
        assertEquals(GuideIdentityHash.Verdict.MATCH, g.judge(next, server, g.entries(now)).verdict)
        // Flipping back to the old key is judged re-keyed again, not MATCH.
        assertEquals(GuideIdentityHash.Verdict.REKEYED, g.judge(next, server, g.entries(admin)).verdict)
        // Every other channel's entry survived.
        assertEquals(admin.size, g.decode(next)!!.entries.size)
    }

    @Test
    fun stampOnlyAfterANonEmptySavedFetch() {
        assertTrue(g.shouldStamp(programmeCount = 120, saved = true))
        assertFalse(g.shouldStamp(programmeCount = 0, saved = true))
        assertFalse(g.shouldStamp(programmeCount = 120, saved = false))
    }
}
