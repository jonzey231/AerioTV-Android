package com.aeriotv.android.core.profiles

import com.aeriotv.android.core.sync.dsync.DsyncGate
import com.aeriotv.android.core.sync.dsync.ProfileRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A manual add ("Enter details", or an API key) of a user the synced roster
 * already has finishes as a roster add (review finding A2, 2026-11-11): the
 * row's role, PIN, avatar, name, order, and stamp, so the next push sends the
 * row back unchanged instead of a newer one with no PIN and the picked role.
 */
class ManualRosterAddTest {

    private val hash = "a".repeat(64)
    private val salt = "b".repeat(32)
    private val kidRow = ProfileRow(
        userId = 12, username = "kid", displayName = "Sam", pinType = "numbers", pinHash = hash, pinSalt = salt,
        role = "child", updatedAt = 1_794_000_000L, avatar = AvatarSpec("rocket", "orange"), order = 2,
    )
    private val adultRow = ProfileRow(20, "pat", "Pat", "none", "", "", "user", 1_793_000_000L)

    @Test fun aManualAddOfARosterUserFindsItsRowWhateverTheCaseOrSpaces() {
        val rows = listOf(adultRow, kidRow)
        assertEquals(kidRow, ProfileRoster.rowForManualAdd(rows, listOf("admin"), "kid"))
        assertEquals(kidRow, ProfileRoster.rowForManualAdd(rows, listOf("admin"), "  KID "))
    }

    @Test fun aNonRosterUserIsAPlainAddAsBefore() {
        assertNull(ProfileRoster.rowForManualAdd(listOf(kidRow), listOf("admin"), "newcomer"))
        assertNull(ProfileRoster.rowForManualAdd(emptyList(), listOf("admin"), "kid"))
        assertNull(ProfileRoster.rowForManualAdd(listOf(kidRow), listOf("admin"), "  "))
        // A plain add stamps now.
        assertEquals(0L, ProfileRoster.addStampAt(null))
    }

    @Test fun aUserAlreadyOnThisDeviceHasNoRosterRowToFinishWith() {
        assertNull(ProfileRoster.rowForManualAdd(listOf(kidRow), listOf("Kid"), "kid"))
    }

    @Test fun aManualAddOfARosterChildKeepsItsRolePinAndStampAndTheNextPushChangesNothing() {
        val row = ProfileRoster.rowForManualAdd(listOf(kidRow), listOf("admin"), "kid")!!
        // What the roster add writes: the row's role (the picked role only fills
        // in for a row with none), then the row's name, avatar, and order.
        val picked = ProfileRole.User
        val role = ProfileRole.fromWire(row.role) ?: picked
        val signedIn = DispatcharrProfile(id = "new", playlistId = "p", displayName = "kid", username = "kid", userLevel = 1, password = "typed", apiKey = "typed-key")
        val added = ProfileRoster.applyRow(signedIn.copy(role = ProfileRole.resolve(signedIn.userLevel, role)), row)
        assertEquals(ProfileRole.Child, added.effectiveRole())
        assertEquals("Sam", added.displayName)
        assertEquals(2, added.order)
        assertEquals(12, added.userId)
        // The credentials typed here stay.
        assertEquals("typed", added.password)
        assertEquals("typed-key", added.apiKey)
        // The PIN record is imported as synced.
        val type = PinRules.typeFromWire(row.pinType)
        assertNotNull(type)
        assertEquals(64, row.pinHash.length)
        assertEquals(32, row.pinSalt.length)
        // The stamp is the row's own, not now.
        val stamp = ProfileRoster.addStampAt(row)
        assertEquals(1_794_000_000L, stamp)
        // The row the next push builds from that profile, PIN, and stamp.
        val pushed = ProfileRow(
            row.userId, added.username, added.displayName, PinRules.wireType(type), row.pinHash, row.pinSalt,
            role = added.effectiveRole().wire, updatedAt = stamp, avatar = added.avatar, order = added.order,
        )
        assertEquals("child", pushed.role)
        assertEquals(row.pinType, pushed.pinType)
        assertEquals(row.pinHash, pushed.pinHash)
        assertEquals(row.updatedAt, pushed.updatedAt)
        // Every other device holds the row at that stamp: the push is not newer, so nothing changes there.
        assertFalse(DsyncGate.profileRowWins(row.updatedAt, pushed))
    }

    @Test fun anOlderRowWithNoRoleOrStampTakesThePickedRoleAndStampsNow() {
        val old = ProfileRow(30, "old", "", "none", "", "")
        assertEquals(ProfileRole.User, ProfileRole.fromWire(old.role) ?: ProfileRole.User)
        assertEquals(0L, ProfileRoster.addStampAt(old))
    }
}
