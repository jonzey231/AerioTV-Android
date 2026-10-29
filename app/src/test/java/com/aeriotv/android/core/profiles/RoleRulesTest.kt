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

    @Test fun channelProfilesNeedServerAdminAndProfilesKeyNeedsFullAccess() {
        assertTrue(RoleRules.canAssignChannelProfiles(true, true, A))
        assertTrue(RoleRules.canAssignChannelProfiles(true, true, U))
        assertFalse(RoleRules.canAssignChannelProfiles(false, true, A))
        assertFalse(RoleRules.canAssignChannelProfiles(true, false, U))
        assertFalse(RoleRules.canAssignChannelProfiles(true, true, C))
        assertTrue(RoleRules.canWriteProfilesKey(A))
        assertTrue(RoleRules.canWriteProfilesKey(U))
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

    @Test fun adminAndStandardManageAlike() {
        assertTrue(RoleRules.canManageProfiles(A))
        assertTrue(RoleRules.canManageProfiles(U))
        assertFalse(RoleRules.canManageProfiles(C))
        assertTrue(RoleRules.canChangeRoles(A))
        assertTrue(RoleRules.canChangeRoles(U))
        assertFalse(RoleRules.canChangeRoles(C))
        assertTrue(RoleRules.isFullAccess(U))
        assertFalse(RoleRules.isFullAccess(C))
        assertFalse(RoleRules.isFullAccess(null))
    }

    @Test fun roleChangeGates() {
        // Child cannot change roles; Admin and Standard act alike.
        assertTrue(RoleRules.roleChange(C, U, C, 0, true, false) is RoleChange.Denied)
        listOf(A, U).forEach { actor ->
            assertEquals(RoleChange.NeedsPinsCreated, RoleRules.roleChange(actor, U, C, missingPins = 1, anyPinExists = true, targetIsLastFullAccess = false))
            assertEquals(RoleChange.NeedsPinsCreated, RoleRules.roleChange(actor, U, C, missingPins = 0, anyPinExists = false, targetIsLastFullAccess = false))
            assertEquals(RoleChange.Allowed, RoleRules.roleChange(actor, U, C, missingPins = 0, anyPinExists = true, targetIsLastFullAccess = false))
            assertEquals(RoleChange.NeedsPinEntry, RoleRules.roleChange(actor, C, U, 0, true, false))
            assertEquals(RoleChange.NeedsPinEntry, RoleRules.roleChange(actor, C, A, 0, true, false))
            assertTrue(RoleRules.roleChange(actor, A, C, 0, true, targetIsLastFullAccess = true) is RoleChange.Denied)
            assertTrue(RoleRules.roleChange(actor, U, C, 0, true, targetIsLastFullAccess = true) is RoleChange.Denied)
            // Admin to Standard is free: both are full access.
            assertEquals(RoleChange.Allowed, RoleRules.roleChange(actor, A, U, 0, false, targetIsLastFullAccess = true))
            assertEquals(RoleChange.Allowed, RoleRules.roleChange(actor, U, U, 0, false, false))
        }
    }

    @Test fun pinsMissingForChildListsAdminAndStandardOwnFirst() {
        val holders = listOf(
            RoleRules.PinHolder("a", A, hasPin = true),
            RoleRules.PinHolder("u1", U, hasPin = false),
            RoleRules.PinHolder("me", A, hasPin = false, isActor = true),
            RoleRules.PinHolder("c", C, hasPin = false),
            RoleRules.PinHolder("u2", U, hasPin = false),
        )
        assertEquals(listOf("me", "u1", "u2"), RoleRules.pinsMissingForChild(holders))
        assertEquals(listOf("me", "u2"), RoleRules.pinsMissingForChild(holders, becomingChild = "u1"))
        assertTrue(RoleRules.pinsMissingForChild(holders.filter { it.hasPin || it.role == C }).isEmpty())
    }

    @Test fun pinRemovalAndReset() {
        assertTrue(RoleRules.removeNeedsPin(C))
        assertFalse(RoleRules.removeNeedsPin(U))
        // While a Child exists Admin and Standard keep a PIN (covers the old last Admin PIN rule).
        assertFalse(RoleRules.canRemovePin(A, childExists = true))
        assertFalse(RoleRules.canRemovePin(U, childExists = true))
        assertTrue(RoleRules.canRemovePin(C, childExists = true))
        assertTrue(RoleRules.canRemovePin(A, childExists = false))
        assertTrue(RoleRules.canRemovePin(U, childExists = false))
        assertFalse(RoleRules.canRemoveDevicePin(childExists = true, otherPins = 0))
        assertTrue(RoleRules.canRemoveDevicePin(childExists = true, otherPins = 1))
        assertTrue(RoleRules.canRemoveDevicePin(childExists = false, otherPins = 0))
        listOf(A, U).forEach { actor ->
            assertEquals(ResetGate.EnterOwnPin, RoleRules.resetGate(actor, actorHasPin = true, childExists = true))
            assertEquals(ResetGate.ConfirmOnly, RoleRules.resetGate(actor, actorHasPin = false, childExists = false))
            assertTrue(RoleRules.resetGate(actor, actorHasPin = false, childExists = true) is ResetGate.Denied)
        }
        assertTrue(RoleRules.resetGate(C, actorHasPin = true, childExists = false) is ResetGate.Denied)
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
        assertTrue(RoleRules.canEditAvatar(ProfileRole.User, isOwn = false))
        assertFalse(RoleRules.canEditAvatar(ProfileRole.Child, isOwn = false))
    }
}
