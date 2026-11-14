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

    @Test fun receivePromptKeepsItsRowOnlyWhileEveryLabelFits() {
        // Apple dc7cae2: "This playlist already exists" is a row while it fits.
        val k = AlertLayout.Kind.RowWhileFits
        assertEquals(AlertLayout.Arrangement.SideBySide, AlertLayout.arrangement(3, isTv = false, kind = k, labelsFit = true))
        assertEquals(AlertLayout.Arrangement.Stacked, AlertLayout.arrangement(3, isTv = false, kind = k, labelsFit = false))
        // Apple TV keeps the row.
        assertEquals(AlertLayout.Arrangement.SideBySide, AlertLayout.arrangement(3, isTv = true, kind = k, labelsFit = false))
        // Stacked: the primary first and Cancel last; the row keeps Cancel, Add Anyway, Replace.
        val dup = listOf(A("Add Anyway", AlertRole.Default), A("Replace", AlertRole.Primary), A("Cancel", AlertRole.Cancel))
        assertEquals(listOf("Replace", "Add Anyway", "Cancel"), order(dup, AlertLayout.Arrangement.Stacked))
        assertEquals(listOf("Cancel", "Add Anyway", "Replace"), order(dup, AlertLayout.Arrangement.SideBySide))
    }

    @Test fun anEqualWidthRowFitsWhileEveryButtonFitsItsShare() {
        // 300 wide, two 8 gaps: each of three buttons gets 94.67.
        assertEquals(true, AlertLayout.equalRowFits(listOf(80f, 94f, 60f), available = 300f, gap = 8f))
        assertEquals(false, AlertLayout.equalRowFits(listOf(80f, 95f, 60f), available = 300f, gap = 8f))
        assertEquals(true, AlertLayout.equalRowFits(emptyList(), available = 0f, gap = 8f))
    }

    @Test fun theRowGivesEveryLabelOneLineOrTheButtonsStack() {
        // Equal shares while every label fits one (the row's usual look).
        assertEquals(listOf(1f, 1f, 1f), AlertLayout.rowWeights(listOf(80f, 94f, 60f), available = 300f, gap = 8f))
        // "Add Anyway" outgrows its share, but the labels still fit side by side: widths in proportion.
        val wide = listOf(80f, 120f, 60f)
        val w = AlertLayout.rowWeights(wide, available = 300f, gap = 8f)!!
        assertEquals(wide, w)
        wide.forEachIndexed { i, label -> assertEquals(true, (300f - 16f) * w[i] / w.sum() >= label) }
        // A tablet-wide prompt at 150% (dp): Cancel, Add Anyway, Replace keep the row, as an iPad does.
        assertEquals(true, AlertLayout.rowWeights(listOf(117.5f, 171.5f, 129f), available = 512f, gap = 8f) != null)
        // No row can: stacked.
        assertEquals(null, AlertLayout.rowWeights(listOf(120f, 140f, 110f), available = 300f, gap = 8f))
    }

    @Test fun aNaturalWidthRowFitsWhileItsButtonsAndGapsFit() {
        // Exactly full, then one over.
        assertEquals(true, AlertLayout.naturalRowFits(listOf(184f, 220f), available = 420f, gap = 16f))
        assertEquals(false, AlertLayout.naturalRowFits(listOf(185f, 220f), available = 420f, gap = 16f))
        // A phone's 371 dp, Cancel at its label's width, 16 apart: "Import this user only"
        // keeps the row at 140% and stacks at 150%; "New Code" keeps it at 150%.
        assertEquals(true, AlertLayout.naturalRowFits(listOf(237f, 112f), available = 371f, gap = 16f))
        assertEquals(false, AlertLayout.naturalRowFits(listOf(250f, 117f), available = 371f, gap = 16f))
        assertEquals(true, AlertLayout.naturalRowFits(listOf(138f, 117f), available = 371f, gap = 16f))
    }
}
