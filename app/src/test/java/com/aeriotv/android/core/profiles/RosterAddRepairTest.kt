package com.aeriotv.android.core.profiles

import com.aeriotv.android.core.sync.dsync.ProfileRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Roster Add pill: one-step add, push guard, stripped-row repair (2026-10-27 clobber). */
class RosterAddRepairTest {
    private val cat = AvatarSpec(icon = "cat", color = "purple")
    private val jordan = ProfileRow(
        userId = 7, username = "jwilliams4362", displayName = "Jordan", pinType = "none", pinHash = "", pinSalt = "",
        role = "admin", updatedAt = 1793138031L, avatar = cat, order = 1,
    )

    @Test fun rosterAddCarriesAllFieldsInOneStep() {
        val base = DispatcharrProfile(id = "x", playlistId = "p", displayName = "jwilliams4362", username = "jwilliams4362")
        val p = ProfileRoster.applyRow(base, jordan)
        assertEquals("Jordan", p.displayName)
        assertEquals(cat, p.avatar)
        assertEquals(1, p.order)
        assertEquals(ProfileRole.Admin, p.role)
        assertEquals(7, p.userId)
    }

    @Test fun pushAfterRosterAddCarriesRosterValues() {
        val stripped = jordan.copy(displayName = "jwilliams4362", avatar = AvatarSpec(), order = 3, updatedAt = 1793138200L)
        val g = ProfileRoster.guardFreshRosterAdd(stripped, jordan, freshRosterAdd = true)!!
        assertEquals("Jordan", g.displayName)
        assertEquals(cat, g.avatar)
        assertEquals(1, g.order)
        // Not fresh, or a complete row: unchanged.
        assertNull(ProfileRoster.guardFreshRosterAdd(stripped, jordan, freshRosterAdd = false))
        assertNull(ProfileRoster.guardFreshRosterAdd(jordan, jordan, freshRosterAdd = true))
    }

    @Test fun strippedServerRowWithinWindowReSendsLocal() {
        val server = jordan.copy(displayName = "jwilliams4362", avatar = AvatarSpec(), updatedAt = 1793138031L + 120)
        assertEquals(ProfileRoster.Repair.ResendLocal, ProfileRoster.repair("Jordan", cat, 1793138031L, server))
        // Outside 10 minutes: a real later change, normal merge.
        val late = server.copy(updatedAt = 1793138031L + 601)
        assertEquals(ProfileRoster.Repair.None, ProfileRoster.repair("Jordan", cat, 1793138031L, late))
    }

    @Test fun strippedLocalRowTakesServer() {
        assertEquals(ProfileRoster.Repair.ApplyServer, ProfileRoster.repair("jwilliams4362", null, 1793138200L, jordan))
        assertEquals(ProfileRoster.Repair.None, ProfileRoster.repair("Jordan", cat, 1793138031L, jordan))
    }
}
