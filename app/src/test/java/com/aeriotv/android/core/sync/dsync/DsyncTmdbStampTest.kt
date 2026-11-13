package com.aeriotv.android.core.sync.dsync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tmdbKey category carries the key's edit time, not the push time
 * (docs/DispatcharrSyncSchema.md section 4.13, owner 2026-11-13). The same
 * cases as Apple's Scripts/dsync-tests block for dce5791, including its
 * two-device model of the race, which converges with edit-time stamps and
 * lets the older edit win with push-time stamps.
 */
class DsyncTmdbStampTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    // ---- The push stamp ----

    @Test fun theTmdbKeyGoesUpWithItsEditTime() {
        assertEquals("tmdbKey goes up with its edit time", 100L, DsyncPayloads.categoryStamp(DsyncKey.TmdbKey, pushedAt = 900, tmdbEditedAt = 100))
        assertEquals("unknown edit time: the push time", 900L, DsyncPayloads.categoryStamp(DsyncKey.TmdbKey, pushedAt = 900, tmdbEditedAt = 0))
        assertEquals("other categories keep the push time", 900L, DsyncPayloads.categoryStamp(DsyncKey.Favorites, pushedAt = 900, tmdbEditedAt = 100))
        assertEquals("other categories keep the push time (no edit time)", 900L, DsyncPayloads.categoryStamp(DsyncKey.Settings, pushedAt = 900, tmdbEditedAt = 0))
    }

    // ---- The pull: a copy other than the one consumed is weighed by edit time, whatever its stamp ----

    private val kB = obj("""{"apiKey":"key-b"}""")
    private val kC = obj("""{"apiKey":"key-c"}""")

    private fun applies(
        remoteAt: Long,
        remoteDevice: String = "B",
        data: JsonObject = kB,
        consumedAt: Long = 500,
        consumedDevice: String = "A",
        consumed: JsonObject? = kC,
    ) = DsyncPayloads.tmdbPullApplies(remoteAt, remoteDevice, data, consumedAt, consumedDevice, consumed)

    @Test fun everyCopyButTheConsumedOneIsWeighed() {
        assertTrue("an older stamp than the copy last seen is still weighed (not skipped as not newer)", applies(remoteAt = 300))
        assertTrue("a newer stamp is weighed", applies(remoteAt = 700))
        assertFalse("the very copy consumed is skipped", applies(remoteAt = 500, remoteDevice = "A", data = kC))
        assertFalse("consumed copy whose key could not be kept: stamp and device decide",
            applies(remoteAt = 500, remoteDevice = "A", data = kC, consumed = obj("{}")))
        assertTrue("same stamp and device, another key: weighed", applies(remoteAt = 500, remoteDevice = "A", data = kB, consumed = kC))
        assertTrue("first read applies", applies(remoteAt = 300, consumedAt = 0, consumedDevice = "", consumed = null))
        // The pull decision for tmdbKey reads no dirty state at all, so an unsent
        // change's queue time cannot hold a copy back. The general gate, which
        // tmdbKey no longer goes through, would have kept the queued change.
        assertEquals("the general gate keeps a queued change over an older stamp",
            DsyncGate.DirtyPull.KeepLocal, DsyncGate.onNewerPull(DsyncKey.TmdbKey, dirty = true, dirtyAt = 900, remoteAt = 300))
        assertTrue("tmdbKey: that copy is still weighed", applies(remoteAt = 300))
        // Other categories still skip a stamp that is not newer than the one consumed.
        assertFalse("favorites still skip a stamp that is not newer", DsyncEnvelopeCodec.remoteWins(500, "A", 300, "B"))
    }

    @Test fun theConsumedCopyIsExactlyThatCopy() {
        assertTrue("same copy", DsyncPayloads.tmdbSameAsConsumed(5, "A", kB, 5, "A", kB))
        assertFalse("older stamp is another copy", DsyncPayloads.tmdbSameAsConsumed(4, "A", kB, 5, "A", kB))
        assertFalse("another device is another copy", DsyncPayloads.tmdbSameAsConsumed(5, "B", kB, 5, "A", kB))
    }

    // ---- Before a PUT: send this device's key unless the server's edit is newer ----

    @Test fun beforeAPutAnOlderEditIsNeverSentOverANewerOne() {
        assertTrue("same key: send (nothing to decide)", DsyncPayloads.tmdbSendsLocal("k", "k", localEditedAt = 1, remoteAt = 9))
        assertTrue("local edit newer: send", DsyncPayloads.tmdbSendsLocal("a", "b", localEditedAt = 300, remoteAt = 200))
        assertFalse("server edit newer: take it, never overwrite it", DsyncPayloads.tmdbSendsLocal("a", "b", localEditedAt = 100, remoteAt = 200))
        assertFalse("unknown local time, two keys: the server's wins", DsyncPayloads.tmdbSendsLocal("a", "b", localEditedAt = 0, remoteAt = 200))
        assertTrue("unknown local time: a key beats a clear", DsyncPayloads.tmdbSendsLocal("a", "", localEditedAt = 0, remoteAt = 200))
    }

    // ---- Two devices and the server, run through the rules above as the manager runs them ----
    // push = the check before a PUT (same copy, or tmdbSendsLocal, else take) then the stamped PUT;
    // pull = tmdbPullApplies, then apply (tmdbKeepsLocal: keep and resend, or take with the server's time).

    private data class Copy(val key: String, val at: Long, val dev: String)

    private class Dev(val id: String, var key: String, var editedAt: Long) {
        var consumed: Copy? = null
    }

    private class World(val pushTimeStamp: Boolean) {
        var server: Copy? = null

        fun data(k: String): JsonObject = DsyncPayloads.encodeTMDBKey(null, k)

        fun push(d: Dev, now: Long) {
            server?.let { s ->
                val same = d.consumed?.let { c -> DsyncPayloads.tmdbSameAsConsumed(s.at, s.dev, data(s.key), c.at, c.dev, data(c.key)) } ?: false
                if (!same && !DsyncPayloads.tmdbSendsLocal(d.key, s.key, d.editedAt, s.at)) {
                    d.key = s.key; d.editedAt = s.at; d.consumed = s
                    return
                }
            }
            val at = if (pushTimeStamp) now else DsyncPayloads.categoryStamp(DsyncKey.TmdbKey, now, d.editedAt)
            server = Copy(d.key, at, d.id)
            d.consumed = server
        }

        fun pull(d: Dev, now: Long) {
            val s = server ?: return
            val c = d.consumed
            if (!DsyncPayloads.tmdbPullApplies(s.at, s.dev, data(s.key), c?.at ?: 0, c?.dev ?: "", c?.let { data(it.key) })) return
            d.consumed = s
            if (DsyncPayloads.tmdbKeepsLocal(d.key, s.key, d.editedAt, s.at)) {
                push(d, now) // keep and resend
            } else if (d.key != s.key) {
                d.key = s.key; d.editedAt = s.at // setTmdbApiKeyFromSync(remote, server time)
            }
        }
    }

    /** The race: A's edit (100) is older, B's (200) newer; A reaches the server first, at 300; B at 400. */
    private fun race(pushTimeStamp: Boolean): Triple<World, Dev, Dev> {
        val w = World(pushTimeStamp)
        val a = Dev("A", "key-from-a", 100)
        val b = Dev("B", "key-from-b", 200)
        w.push(a, now = 300)
        w.push(b, now = 400)
        w.pull(a, now = 500); w.pull(b, now = 500); w.pull(a, now = 600)
        return Triple(w, a, b)
    }

    @Test fun twoDevicesConvergeOnTheNewestEdit() {
        val (w, a, b) = race(pushTimeStamp = false)
        assertEquals("race: the server ends with the newest edit", "key-from-b", w.server?.key)
        assertEquals("race: stamped with that edit's time, not a push time", 200L, w.server?.at)
        assertEquals("race: the older editor converges on the newest edit", "key-from-b", a.key)
        assertEquals("race: the newer editor keeps its edit", "key-from-b", b.key)
        assertEquals("race: the applied key keeps the server's time", 200L, a.editedAt)
    }

    @Test fun pushTimeStampsLetTheOlderEditWin() {
        // The same race stamped with the push time (the old behavior) lets the older edit win: the model discriminates.
        val (_, _, b) = race(pushTimeStamp = true)
        assertEquals("push-time stamps: the older edit wins (the bug this fixes)", "key-from-a", b.key)
    }

    @Test fun anOlderEditSentLaterNeverBeatsANewerOneAlreadyThere() {
        // Reverse order: the newer edit reaches the server first; the older one is never sent.
        val w = World(pushTimeStamp = false)
        val a = Dev("A", "key-from-a", 100)
        val b = Dev("B", "key-from-b", 200)
        w.push(b, now = 300); w.push(a, now = 400)
        assertEquals("newer edit first: the later push of an older edit takes it instead", Copy("key-from-b", 200, "B"), w.server)
        assertEquals("and that device converges", "key-from-b", a.key)
    }

    @Test fun aReceivingDeviceAppliesAKeyStampedOlderThanTheCopyItConsumed() {
        // D's own key was edited at 100, and the last copy it consumed carried that same key
        // stamped 500 (nothing to apply, so D's edit time stayed 100). Another device's key
        // then arrives stamped 300: older than the copy consumed, newer than D's edit.
        val w = World(pushTimeStamp = false)
        val d = Dev("D", "k", 100)
        d.consumed = Copy("k", 500, "X")
        w.server = Copy("k2", 300, "Y")
        // Stamp order alone (the general rule) would skip it as not newer than the consumed copy.
        assertFalse(DsyncEnvelopeCodec.remoteWins(500, "X", 300, "Y"))
        w.pull(d, now = 600)
        assertEquals("applied by edit time", "k2", d.key)
        assertEquals("with the server's time", 300L, d.editedAt)
        assertEquals("and the server copy is left as it is", Copy("k2", 300, "Y"), w.server)
    }

    @Test fun aPutRaceConvergesOnTheNewestEdit() {
        // Both checked the server before either PUT: the later PUT holds the OLDER stamp. The device whose newer
        // edit was overwritten sees an older-stamped copy, weighs it, and resends; a third device that saw the
        // newer copy (and applied it, keeping its time) resends it with that time.
        val w = World(pushTimeStamp = false)
        val a = Dev("A", "key-from-a", 300)
        val b = Dev("B", "key-from-b", 200)
        val c = Dev("C", "", 0)
        w.server = Copy("key-zero", 50, "Z")
        a.consumed = w.server; b.consumed = w.server; c.consumed = w.server
        w.push(a, now = 400) // A: key-from-a at 300
        w.pull(c, now = 410) // C applies key-from-a, keeping its time 300
        assertEquals("third device applied the newer edit", "key-from-a", c.key)
        assertEquals("with the server's time", 300L, c.editedAt)
        w.server = Copy("key-from-b", 200, "B"); b.consumed = w.server // B's PUT lands after A's
        w.pull(c, now = 420) // C: an older stamp than it saw (200 < 300), weighed, kept, resent
        assertEquals("re-sent with the original edit time, not the push time", Copy("key-from-a", 300, "C"), w.server)
        w.pull(b, now = 430); w.pull(a, now = 430)
        assertEquals("PUT race: B converges on the newest edit", "key-from-a", b.key)
        assertEquals("PUT race: A keeps the newest edit", "key-from-a", a.key)
    }
}
