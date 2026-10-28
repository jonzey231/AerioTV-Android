package com.aeriotv.android.core.profiles

import com.aeriotv.android.core.sync.dsync.ProfileRow
import com.aeriotv.android.core.sync.dsync.ProfileTombstone
import com.aeriotv.android.core.sync.dsync.ProfileTombstones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The synced roster is a catalog (owner 2026-10-27): pulls add nothing; adding is opt-in. */
class ProfileRosterTest {

    private fun row(id: Int, u: String, role: String = "user", order: Int = -1, avatar: AvatarSpec? = null, name: String = u, at: Long = 1_793_000_000L) =
        ProfileRow(id, u, name, "none", "", "", role, at, avatar, order)

    private fun local(u: String) = DispatcharrProfile(id = "id-$u", playlistId = "p", displayName = u, username = u)

    @Test fun pullWithUnknownRowsCreatesNothing() {
        val rows = listOf(row(1, "admin"), row(2, "jonzey231"), row(3, "kid", role = "child"))
        val plan = ProfileRoster.plan(rows, listOf("Admin"))
        // Only the local user merges; the rest are offered, never added.
        assertEquals(listOf("admin"), plan.merge.map { it.username })
        assertEquals(listOf("jonzey231", "kid"), plan.available.map { it.username })
        assertEquals(setOf("jonzey231", "kid"), ProfileRoster.suggestions(rows, listOf("admin")).map { it.username }.toSet())
    }

    @Test fun suggestionsNeverIncludeLocalUsersAndFollowRosterOrder() {
        val rows = listOf(row(2, "b", order = 2), row(3, "c", order = 1), row(4, "a"), row(1, "me", order = 0))
        assertEquals(listOf("c", "b", "a"), ProfileRoster.suggestions(rows, listOf("ME")).map { it.username })
    }

    @Test fun addingFromSuggestionAppliesRoleAvatarNameAndOrder() {
        val avatar = AvatarSpec(icon = AvatarCatalog.ICONS.first(), color = AvatarCatalog.COLORS.first())
        val r = row(7, "jonzey231", role = "child", order = 3, avatar = avatar, name = "Logan")
        val out = ProfileRoster.applyRow(local("jonzey231"), r)
        assertEquals(ProfileRole.Child, out.role)
        assertEquals(3, out.order)
        assertEquals(AvatarCatalog.normalize(avatar), out.avatar)
        assertEquals("Logan", out.displayName)
        assertEquals(7, out.userId)
    }

    @Test fun absentFieldsKeepLocalValues() {
        val p = local("x").copy(role = ProfileRole.Admin, order = 5, displayName = "Mine")
        val out = ProfileRoster.applyRow(p, row(1, "x", role = "", order = -1, name = ""))
        assertEquals(ProfileRole.Admin, out.role)
        assertEquals(5, out.order)
        assertEquals("Mine", out.displayName)
    }

    @Test fun suggestedPinIsImportableAsIs() {
        // The row's v2 PIN (64 hex hash, 32 hex salt) is what addFromRoster imports.
        val salt = "0".repeat(32)
        val hash = PinRules.hashV2(salt, "1234")
        val r = ProfileRow(1, "kid", "Kid", "numbers", hash, salt, "child", 1L)
        assertEquals(PinType.Numbers, PinRules.typeFromWire(r.pinType))
        assertTrue(r.pinHash.length == 64 && r.pinSalt.length == 32)
        assertTrue(PinRules.matches(PinRules.hashV2(r.pinSalt, "1234"), r.pinHash))
    }

    @Test fun tombstoneStillRemovesFromRosterAndSuggestions() {
        val rows = listOf(row(1, "admin"), row(8, "test2", at = 1_792_000_000L))
        val r = ProfileTombstones.resolve(rows, listOf(ProfileTombstone("test2", 1_793_500_000L, 8)))
        assertEquals(listOf("test2"), r.dropped.map { it.username })
        assertTrue(ProfileRoster.suggestions(r.rows, listOf("admin")).isEmpty())
    }

    @Test fun devicePinIdIsNeverAProfileId() {
        assertTrue(DevicePin.isDevice(DevicePin.id("abc")))
        assertTrue(!DevicePin.isDevice("3f2c-uuid"))
    }
}
