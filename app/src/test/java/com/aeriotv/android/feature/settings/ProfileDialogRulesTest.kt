package com.aeriotv.android.feature.settings

import com.aeriotv.android.core.profiles.ProfileRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile dialogs' one layout (owner 2026-11-12, Apple 98fc424): the
 * action list's groups, the outside-tap rule, the Set Avatar label, and the
 * role descriptions the Role dialog now shows under each role.
 */
class ProfileDialogRulesTest {

    @Test fun actionCapsulesSit8ApartWith20BeforeRemoveAndCancel() {
        // Switch, Rename, Role, Set Avatar, Set PIN, Remove, Cancel.
        assertEquals(listOf(0, 8, 8, 8, 8, 20, 20), ProfileDialogRules.gapsAbove(count = 7, removeAt = 5))
        // No Remove: only the gap before Cancel.
        assertEquals(listOf(0, 8, 8, 20), ProfileDialogRules.gapsAbove(count = 4, removeAt = -1))
        assertEquals(48, ProfileDialogRules.ACTION_MIN_HEIGHT_DP)
        assertEquals(0.8f, ProfileDialogRules.MAX_HEIGHT_FRACTION)
    }

    @Test fun onlyADialogWithACancelClosesOnAnOutsideTap() {
        assertTrue(ProfileDialogRules.outsideTapCancels(ProfileDialogRules.Kind.WithCancel))
        for (k in ProfileDialogRules.Kind.entries.filter { it != ProfileDialogRules.Kind.WithCancel }) {
            assertFalse(k.name, ProfileDialogRules.outsideTapCancels(k))
        }
    }

    @Test fun theAvatarActionIsSetAvatar() {
        assertEquals("Set Avatar", ProfileDialogRules.SET_AVATAR)
    }

    @Test fun eachRoleCarriesApplesDescription() {
        assertEquals("Full access in AerioTV. Has admin rights on the Dispatcharr server.", roleDescription(ProfileRole.Admin))
        assertEquals("Full access in AerioTV. A standard user on the Dispatcharr server.", roleDescription(ProfileRole.User))
        assertEquals(
            "Sees only its own content and basic settings. Switching from a Child profile to a non-Child profile requires PIN entry.",
            roleDescription(ProfileRole.Child),
        )
    }
}
