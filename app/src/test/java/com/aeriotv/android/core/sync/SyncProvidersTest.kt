package com.aeriotv.android.core.sync

import com.aeriotv.android.core.sync.SyncProviderId.Dispatcharr
import com.aeriotv.android.core.sync.SyncProviderId.GoogleDrive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProvidersTest {
    private val r = SyncProvidersRules
    private fun rec(d: Boolean = false, g: Boolean = false, i: Boolean = false, at: Long = 100L, dev: String = "A") =
        SyncProvidersRecord(d, g, i, at, dev)

    @Test fun unionKeepsRemoteTrueAndAddsLocal() {
        val p = r.forPush(rec(d = true, i = true), true, dispatcharrOn = false, driveOn = true, reported = emptySet(), deviceId = "B", nowMs = 200)
        assertEquals(rec(d = true, g = true, i = true, at = 200, dev = "B"), p.record)
        assertEquals(setOf(GoogleDrive), p.reported)
    }

    @Test fun unchangedFlagsKeepStamp() {
        val stored = rec(d = true, g = true)
        val p = r.forPush(stored, true, dispatcharrOn = true, driveOn = true, reported = emptySet(), deviceId = "B", nowMs = 999)
        assertSame(stored, p.record)
    }

    @Test fun explicitOffWritesFalseOnceThenLetsOthersSetIt() {
        val p = r.forPush(rec(d = true, g = true), true, dispatcharrOn = true, driveOn = false, reported = setOf(GoogleDrive), deviceId = "B", nowMs = 300)
        assertEquals(rec(d = true, g = false, at = 300, dev = "B"), p.record)
        assertTrue(GoogleDrive !in p.reported)
        // Another device still on Drive pulls the newer false and ORs its own true back.
        val other = r.forPush(p.record, true, dispatcharrOn = false, driveOn = true, reported = setOf(GoogleDrive), deviceId = "C", nowMs = 400)
        assertEquals(true, other.record!!.googleDrive)
    }

    @Test fun offNeverReportedKeepsRemoteTrue() {
        val p = r.forPush(rec(g = true), true, dispatcharrOn = false, driveOn = false, reported = emptySet(), deviceId = "B", nowMs = 300)
        assertEquals(true, p.record!!.googleDrive)
    }

    @Test fun subProfileNeverChangesRecord() {
        val stored = rec(d = false, g = false)
        val p = r.forPush(stored, isBaseProfile = false, dispatcharrOn = true, driveOn = true, reported = setOf(GoogleDrive), deviceId = "B", nowMs = 500)
        assertSame(stored, p.record)
        assertEquals(setOf(GoogleDrive), p.reported)
        assertNull(r.forPush(null, false, true, true, emptySet(), "B", 500).record)
    }

    @Test fun newerWinsOnPullTiesByDeviceId() {
        assertEquals(rec(at = 200), r.newer(rec(d = true, at = 100), rec(at = 200)))
        assertEquals("B", r.newer(rec(dev = "A"), rec(dev = "B"))!!.deviceId)
        assertEquals(rec(d = true), r.newer(rec(d = true), null))
    }

    @Test fun offersOnlyMissingProvidersNeverIcloud() {
        assertEquals(listOf(GoogleDrive, Dispatcharr), r.offers(rec(d = true, g = true, i = true), false, false, true))
        assertEquals(listOf(GoogleDrive), r.offers(rec(d = true, g = true), false, false, activeIsDispatcharr = false))
        assertEquals(emptyList<SyncProviderId>(), r.offers(rec(d = true, g = true), true, true, true))
        assertEquals(emptyList<SyncProviderId>(), r.offers(rec(i = true), false, false, true))
        assertEquals(emptyList<SyncProviderId>(), r.offers(null, false, false, true))
    }

    @Test fun copy() {
        assertEquals("Your other devices also use Google Drive. Turn it on here too?", r.offerText(listOf(GoogleDrive)))
        assertEquals("Your other devices also use Google Drive and Dispatcharr Sync. Turn it on here too?", r.offerText(listOf(GoogleDrive, Dispatcharr)))
    }

    @Test fun wireRoundTripAndTolerance() {
        val x = rec(d = true, i = true, at = 1761700000000, dev = "X")
        assertEquals(x, r.fromJson(r.toJson(x)))
        assertNull(r.fromJson(null))
        assertNull(r.fromDriveBody("""{"envelope":{"version":1,"lastModified":1,"device":"d"}}"""))
        assertEquals(true, r.fromDriveBody("""{"envelope":{"syncProviders":{"googleDrive":true,"updatedAt":5}}}""")!!.googleDrive)
    }
}
