package com.aeriotv.android.feature.settings

import com.aeriotv.android.core.profiles.ProfileRole
import com.aeriotv.android.core.profiles.RoleRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A refused profile action shows a dialog (Logan, device testing
 * 2026-11-12): the action's existing title, its existing reason word for
 * word, and "OK", instead of small red text under the Profiles list.
 */
class ProfileRefusalTest {

    @Test fun theOnlyProfileTurnedChildShowsTheRoleDialog() {
        val g = RoleRules.roleChange(
            actor = ProfileRole.Admin, from = ProfileRole.User, to = ProfileRole.Child,
            missingPins = 0, anyPinExists = true, targetIsLastFullAccess = true,
        )
        val reason = (g as RoleRules.RoleChange.Denied).reason
        val r = ProfileRefusal.of("Role", reason)!!
        assertEquals("Role", r.title)
        assertEquals("At least one profile must stay Admin or Standard.", r.reason)
        assertEquals("OK", ProfileRefusal.OK)
    }

    @Test fun anActionThatWentThroughShowsNothing() {
        assertNull(ProfileRefusal.of("Reset PIN", null))
        assertNull(ProfileRefusal.of("Remove PIN", " "))
    }

    @Test fun theReasonIsShownWordForWord() {
        val r = ProfileRefusal.of("Reset PIN", "Only an Admin or Standard profile can change roles.")!!
        assertEquals("Reset PIN", r.title)
        assertEquals("Only an Admin or Standard profile can change roles.", r.reason)
        // The pick-up reorder has no title of its own: none is made up.
        assertNull(ProfileRefusal.of(null, "x")!!.title)
    }
}
