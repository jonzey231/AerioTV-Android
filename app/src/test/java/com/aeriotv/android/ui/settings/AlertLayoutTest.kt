package com.aeriotv.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Alert prompts laid out like their Apple versions, in Material 3 (owner
 * 2026-11-12): iOS 26 alert rules on phones and tablets, the Apple TV
 * alert on TV.
 */
class AlertLayoutTest {
    private data class A(val label: String, val role: AlertRole)
    private fun order(list: List<A>, arrangement: AlertLayout.Arrangement) = AlertLayout.ordered(list, { it.role }, arrangement).map { it.label }

    @Test fun phonesFollowIosAlerts() {
        assertEquals(AlertLayout.Arrangement.Single, AlertLayout.arrangement(1, isTv = false))
        assertEquals(AlertLayout.Arrangement.SideBySide, AlertLayout.arrangement(2, isTv = false))
        assertEquals(AlertLayout.Arrangement.Stacked, AlertLayout.arrangement(3, isTv = false))
    }

    @Test fun tvAlwaysStacksLikeAppleTv() {
        assertEquals(AlertLayout.Arrangement.Stacked, AlertLayout.arrangement(2, isTv = true))
        assertEquals(AlertLayout.Arrangement.Stacked, AlertLayout.arrangement(3, isTv = true))
        assertEquals(AlertLayout.Arrangement.Single, AlertLayout.arrangement(1, isTv = true))
    }

    @Test fun applesOwnPromptsKeepTheirShape() {
        // One equal-width row ("This playlist already exists", the setup source choice) on every device.
        assertEquals(AlertLayout.Arrangement.SideBySide, AlertLayout.arrangement(3, isTv = true, kind = AlertLayout.Kind.Row))
        // An action sheet (Play Where) always stacks.
        assertEquals(AlertLayout.Arrangement.Stacked, AlertLayout.arrangement(2, isTv = false, kind = AlertLayout.Kind.Sheet))
    }

    @Test fun twoButtonsPutCancelOnTheLeft() {
        val delete = listOf(A("Delete", AlertRole.Destructive), A("Cancel", AlertRole.Cancel))
        assertEquals(listOf("Cancel", "Delete"), order(delete, AlertLayout.Arrangement.SideBySide))
        // Declared Cancel first (Refresh Everything?): still left.
        val refresh = listOf(A("Cancel", AlertRole.Cancel), A("Refresh", AlertRole.Destructive))
        assertEquals(listOf("Cancel", "Refresh"), order(refresh, AlertLayout.Arrangement.SideBySide))
        // Apple's one-row duplicate prompt: Cancel, Add Anyway, Replace.
        val dup = listOf(A("Add Anyway", AlertRole.Default), A("Replace", AlertRole.Primary), A("Cancel", AlertRole.Cancel))
        assertEquals(listOf("Cancel", "Add Anyway", "Replace"), order(dup, AlertLayout.Arrangement.SideBySide))
    }

    @Test fun aStackPutsThePrimaryFirstAndCancelLast() {
        // The key prompt on phones and on TV.
        val phone = listOf(A("Sign In", AlertRole.Primary), A("Edit Playlist", AlertRole.Default), A("Not Now", AlertRole.Cancel))
        assertEquals(listOf("Sign In", "Edit Playlist", "Not Now"), order(phone, AlertLayout.Arrangement.Stacked))
        val tv = listOf(A("Update from Phone", AlertRole.Primary), A("Sign In with Remote", AlertRole.Default), A("Not Now", AlertRole.Cancel))
        assertEquals(listOf("Update from Phone", "Sign In with Remote", "Not Now"), order(tv, AlertLayout.Arrangement.Stacked))
        // A two-button alert on TV: the action first, Cancel last (no preferred action: nothing filled).
        val push = listOf(A("Cancel", AlertRole.Cancel), A("Push", AlertRole.Default))
        assertEquals(listOf("Push", "Cancel"), order(push, AlertLayout.Arrangement.Stacked))
        // Declared order otherwise (Multiview: Continue, Don't Show Again, Cancel).
        val mv = listOf(A("Continue", AlertRole.Destructive), A("Don't Show Again", AlertRole.Default), A("Cancel", AlertRole.Cancel))
        assertEquals(listOf("Continue", "Don't Show Again", "Cancel"), order(mv, AlertLayout.Arrangement.Stacked))
    }

    @Test fun noPreferredActionMeansNoFilledButton() {
        // Apple alerts without a preferred action (Push, Turn On, Enable Logging): every button neutral.
        assertEquals(AlertLayout.Style.Tonal, AlertLayout.style(AlertRole.Default))
        assertEquals(AlertLayout.Style.Tonal, AlertLayout.style(AlertRole.Cancel))
        // TV focus still lands on the action, not Cancel.
        val push = listOf(A("Push", AlertRole.Default), A("Cancel", AlertRole.Cancel))
        assertEquals(0, AlertLayout.defaultFocus(push) { it.role })
    }

    @Test fun thePrimaryTakesFocus() {
        val tv = listOf(A("Update from Phone", AlertRole.Primary), A("Sign In with Remote", AlertRole.Default), A("Not Now", AlertRole.Cancel))
        assertEquals(0, AlertLayout.defaultFocus(tv) { it.role })
        // No primary: the first button that is not Cancel.
        val side = listOf(A("Cancel", AlertRole.Cancel), A("Delete", AlertRole.Destructive))
        assertEquals(1, AlertLayout.defaultFocus(side) { it.role })
    }

    @Test fun tvCardMatchesTheAppleTvAlert() {
        // About a quarter of the screen (500 of 1920 pt at 0.5 dp per pt), capsules 36 dp, 6 dp apart.
        assertEquals(250, AlertLayout.TV_ALERT_WIDTH_DP)
        assertEquals(36, AlertLayout.TV_BUTTON_HEIGHT_DP)
        assertEquals(6, AlertLayout.TV_BUTTON_GAP_DP)
    }

    @Test fun materialStylesByRole() {
        assertEquals(AlertLayout.Style.Filled, AlertLayout.style(AlertRole.Primary))
        assertEquals(AlertLayout.Style.Tonal, AlertLayout.style(AlertRole.Default))
        assertEquals(AlertLayout.Style.Tonal, AlertLayout.style(AlertRole.Cancel))
        assertEquals(AlertLayout.Style.TonalError, AlertLayout.style(AlertRole.Destructive))
        // TV: the focused button fills, as on Apple TV.
        assertEquals(AlertLayout.Style.Filled, AlertLayout.tvStyle(AlertRole.Default, focused = true))
        assertEquals(AlertLayout.Style.Tonal, AlertLayout.tvStyle(AlertRole.Primary, focused = false))
        assertEquals(AlertLayout.Style.TonalError, AlertLayout.tvStyle(AlertRole.Destructive, focused = false))
    }
}
