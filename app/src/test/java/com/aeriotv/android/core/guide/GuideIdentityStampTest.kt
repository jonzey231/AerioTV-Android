package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.M3UChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideIdentityStampTest {
    private fun ch(uuid: String, key: String) = M3UChannel(
        id = "disp:$uuid", name = uuid, url = "http://srv/proxy/ts/stream/$uuid", tvgID = key,
    )
    private val g = GuideIdentityHash
    private val server = g.serverFingerprint("DispatcharrApiKey", "http://srv/")
    private val admin = (1..10).map { ch("u$it", "k$it") }
    private val kids = admin.take(4)

    @Test
    fun profileSubsetMatchesAfterTheServerWideStamp() {
        val stamp = g.merged("", server, g.entries(admin), reset = false)
        val j = g.judge(stamp, server, g.entries(kids))
        assertEquals(GuideIdentityHash.Verdict.MATCH, j.verdict)
        assertFalse(j.purge)
    }

    @Test
    fun largerProfileAfterSmallerNeedsFetchButNeverPurges() {
        val stamp = g.merged("", server, g.entries(kids), reset = false)
        val j = g.judge(stamp, server, g.entries(admin))
        assertEquals(GuideIdentityHash.Verdict.NEW_CHANNELS, j.verdict)
        assertFalse(j.purge)
        assertTrue(j.needsFetch)
        val unioned = g.merged(stamp, server, g.entries(admin), reset = false)
        assertEquals(GuideIdentityHash.Verdict.MATCH, g.judge(unioned, server, g.entries(kids)).verdict)
    }

    @Test
    fun reKeyedChannelAndServerChangePurge() {
        val stamp = g.merged("", server, g.entries(admin), reset = false)
        val rekeyed = admin.map { if (it.id == "disp:u2") it.copy(tvgID = "other") else it }
        assertEquals(GuideIdentityHash.Verdict.REKEYED, g.judge(stamp, server, g.entries(rekeyed)).verdict)
        val other = g.serverFingerprint("DispatcharrApiKey", "http://srv2")
        assertTrue(g.judge(stamp, other, g.entries(admin)).purge)
    }

    @Test
    fun legacyStampRefetchesWithoutPurge() {
        val j = g.judge(GuideIdentityHash.of(admin), server, g.entries(admin))
        assertEquals(GuideIdentityHash.Verdict.LEGACY, j.verdict)
        assertFalse(j.purge)
    }
}
