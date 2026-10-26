package com.aeriotv.android.core.profiles

import com.aeriotv.android.core.profiles.RoleRules.ResetGate
import com.aeriotv.android.core.profiles.RoleRules.RoleChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleRulesTest {
    private val A = ProfileRole.Admin
    private val U = ProfileRole.User
    private val C = ProfileRole.Child

    @Test fun defaultRoleFollowsLevel() {
        assertEquals(A, ProfileRole.defaultFor(10))
        assertEquals(U, ProfileRole.defaultFor(1))
        assertEquals(U, ProfileRole.defaultFor(0))
        assertEquals(C, ProfileRole.fromWire("child"))
        assertEquals(null, ProfileRole.fromWire("boss"))
    }

    @Test fun baseProfileIsAlwaysAdmin() {
        assertEquals(A, ProfileRole.defaultFor(1, isBase = true))
        assertEquals(A, ProfileRole.defaultFor(0, isBase = true))
        assertEquals(A, ProfileRole.defaultFor(10, isBase = true))
        assertEquals(U, ProfileRole.defaultFor(1, isBase = false))
        assertEquals(A, DispatcharrProfile("b", "p", "Base", "base", isDefault = true, userLevel = 1).effectiveRole())
        assertEquals(U, DispatcharrProfile("o", "p", "Other", "other", isDefault = false, userLevel = 1).effectiveRole())
        assertEquals(C, DispatcharrProfile("b", "p", "Base", "base", isDefault = true, userLevel = 1, role = C).effectiveRole())
    }

    @Test fun basePromotionKeepsAdminAndChild() {
        assertEquals(A, ProfileRole.basePromotion(null))
        assertEquals(A, ProfileRole.basePromotion(U))
        assertEquals(null, ProfileRole.basePromotion(A))
        assertEquals(null, ProfileRole.basePromotion(C))
    }

    @Test fun addPathSelection() {
        assertEquals(RoleRules.AddPath.ServerList, RoleRules.addPath(baseIsServerAdmin = true, usersListForbidden = false))
        assertEquals(RoleRules.AddPath.Manual, RoleRules.addPath(baseIsServerAdmin = true, usersListForbidden = true))
        assertEquals(RoleRules.AddPath.Manual, RoleRules.addPath(baseIsServerAdmin = false, usersListForbidden = false))
        assertEquals(RoleRules.AddPath.Manual, RoleRules.addPath(baseIsServerAdmin = false, usersListForbidden = true))
    }

    @Test fun channelProfilesNeedServerAdminAndProfilesKeyNeedsLocalAdmin() {
        assertTrue(RoleRules.canAssignChannelProfiles(true, true, A))
        assertFalse(RoleRules.canAssignChannelProfiles(false, true, A))
        assertFalse(RoleRules.canAssignChannelProfiles(true, false, A))
        assertFalse(RoleRules.canAssignChannelProfiles(true, true, U))
        assertTrue(RoleRules.canWriteProfilesKey(A))
        assertFalse(RoleRules.canWriteProfilesKey(U))
        assertFalse(RoleRules.canWriteProfilesKey(C))
        assertFalse(RoleRules.canWriteProfilesKey(null))
    }

    @Test fun childSeesOnlyBasics() {
        assertFalse(RoleRules.canSeeSwitcher(C))
        assertTrue(RoleRules.canSeeSwitcher(U))
        assertTrue(RoleRules.canSeeSwitcher(null))
        assertFalse(RoleRules.canEditServer(C))
        assertFalse(RoleRules.playlistsVisible(C))
        listOf("LiveTV", "Player", "Appearance", "About").forEach { assertTrue(it, RoleRules.sectionVisible(C, it)) }
        listOf("MoviesAndTvShows", "DvrSettings", "General", "RemoteControl", "Sync", "AppUpdates", "Developer")
            .forEach { assertFalse(it, RoleRules.sectionVisible(C, it)) }
        listOf("DvrSettings", "Developer").forEach { assertTrue(RoleRules.sectionVisible(U, it)) }
        assertFalse(RoleRules.deepLinkAllowed(C, "playlist-detail"))
        assertFalse(RoleRules.deepLinkAllowed(C, "Sync"))
        assertTrue(RoleRules.deepLinkAllowed(C, "LiveTV"))
        assertTrue(RoleRules.deepLinkAllowed(U, "developer"))
    }

    @Test fun onlyAdminManages() {
        assertTrue(RoleRules.canManageProfiles(A))
        assertFalse(RoleRules.canManageProfiles(U))
        assertFalse(RoleRules.canManageProfiles(C))
        assertFalse(RoleRules.canChangeRoles(U))
    }

    @Test fun roleChangeGates() {
        assertTrue(RoleRules.roleChange(U, U, C, true, false) is RoleChange.Denied)
        assertEquals(RoleChange.NeedsAdminPinCreated, RoleRules.roleChange(A, U, C, adminHasPin = false, targetIsLastAdmin = false))
        assertEquals(RoleChange.Allowed, RoleRules.roleChange(A, U, C, adminHasPin = true, targetIsLastAdmin = false))
        assertEquals(RoleChange.NeedsAdminPinEntry, RoleRules.roleChange(A, C, U, true, false))
        assertEquals(RoleChange.NeedsAdminPinEntry, RoleRules.roleChange(A, C, A, true, false))
        assertTrue(RoleRules.roleChange(A, A, U, true, targetIsLastAdmin = true) is RoleChange.Denied)
        assertEquals(RoleChange.Allowed, RoleRules.roleChange(A, A, U, false, targetIsLastAdmin = false))
        assertEquals(RoleChange.Allowed, RoleRules.roleChange(A, U, U, false, false))
    }

    @Test fun pinRemovalAndReset() {
        assertTrue(RoleRules.removeNeedsAdminPin(C))
        assertFalse(RoleRules.removeNeedsAdminPin(U))
        assertFalse(RoleRules.canRemoveAdminPin(A, childExists = true, otherAdminPins = 0))
        assertTrue(RoleRules.canRemoveAdminPin(A, childExists = true, otherAdminPins = 1))
        assertTrue(RoleRules.canRemoveAdminPin(A, childExists = false, otherAdminPins = 0))
        assertTrue(RoleRules.canRemoveAdminPin(U, childExists = true, otherAdminPins = 0))
        assertEquals(ResetGate.EnterOwnPin, RoleRules.resetGate(A, actorHasPin = true, childExists = true))
        assertEquals(ResetGate.ConfirmOnly, RoleRules.resetGate(A, actorHasPin = false, childExists = false))
        assertTrue(RoleRules.resetGate(A, actorHasPin = false, childExists = true) is ResetGate.Denied)
        assertTrue(RoleRules.resetGate(U, actorHasPin = true, childExists = false) is ResetGate.Denied)
    }

    @Test fun launchPicker() {
        assertTrue(RoleRules.showLaunchPicker(true, true, true, 2, A))
        assertTrue(RoleRules.showLaunchPicker(true, true, true, 3, null))
        assertFalse(RoleRules.showLaunchPicker(false, true, true, 2, A))
        assertFalse(RoleRules.showLaunchPicker(true, false, true, 2, A))
        assertFalse(RoleRules.showLaunchPicker(true, true, false, 2, A))
        assertFalse(RoleRules.showLaunchPicker(true, true, true, 1, A))
        assertFalse(RoleRules.showLaunchPicker(true, true, true, 2, C))
    }

    @Test fun avatarEditing() {
        assertTrue(RoleRules.canEditAvatar(ProfileRole.Admin, isOwn = false))
        assertTrue(RoleRules.canEditAvatar(ProfileRole.User, isOwn = true))
        assertTrue(RoleRules.canEditAvatar(ProfileRole.Child, isOwn = true))
        assertFalse(RoleRules.canEditAvatar(ProfileRole.User, isOwn = false))
        assertFalse(RoleRules.canEditAvatar(ProfileRole.Child, isOwn = false))
    }
}
