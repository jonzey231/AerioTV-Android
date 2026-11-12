package com.aeriotv.android.core.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PIN copy approved on 2026-11-12 (items 1 to 21), byte for byte: Apple
 * ships the same strings. Two terms only: "profile PIN" and "Device Admin
 * PIN" in full.
 */
class PinCopyTest {
    private val name = "Dave"

    @Test fun everyStringIsTheApprovedText() {
        assertEquals("Enter an Admin or Standard profile PIN, or the Device Admin PIN", PinCopy.ADMIN_PAD) // 1, 19
        assertEquals("To switch or add profiles, enter an Admin or Standard profile PIN, or the Device Admin PIN.", PinCopy.MANAGE_PROFILES_GATE) // 2
        assertEquals("Enter an Admin or Standard profile PIN, or the Device Admin PIN, to remove Dave", PinCopy.removeChild(name)) // 3
        assertEquals("Lets a parent leave a Child profile and manage profiles on this device. Separate from profile PINs. Never synced.", PinCopy.DEVICE_ADMIN_PIN_ROW) // 4
        assertEquals("Switch or add profiles (PIN required)", PinCopy.MANAGE_PROFILES_ROW) // 5
        assertEquals("Pick Child and you'll set a Device Admin PIN next. A parent uses it to leave the Child profile on this device. It is never synced.", PinCopy.ONBOARDING_ROLE) // 6
        assertEquals("Enter the PIN for Dave, or the Device Admin PIN, to show this code", PinCopy.userQr(name)) // 7
        assertEquals("Sees only its own content and basic settings. Leaving it needs an Admin or Standard profile PIN, or the Device Admin PIN.", PinCopy.CHILD_ROLE) // 8
        assertEquals("While a Child profile exists, every Admin and Standard profile needs its own profile PIN.", PinCopy.KEEP_PIN_REASON) // 9
        assertEquals("Leaving a Child profile needs an Admin or Standard profile PIN, or the Device Admin PIN. Set one first.", PinCopy.NO_PIN_LEAVES_CHILD) // 10
        assertEquals("Set PIN for Dave", PinCopy.setPin(name)) // 11
        assertEquals("Change PIN for Dave", PinCopy.changePin(name))
        assertEquals("Confirm PIN for Dave", PinCopy.confirmPin(name)) // 12
        assertEquals("Confirm Device Admin PIN", PinCopy.CONFIRM_DEVICE_ADMIN_PIN)
        assertEquals("Enter the current PIN for Dave", PinCopy.currentPin(name)) // 13
        assertEquals("Enter your profile PIN to reset the PIN for Dave", PinCopy.resetActor(name)) // 14
        assertEquals("Set a PIN on your own profile first.", PinCopy.RESET_NEEDS_OWN_PIN) // 15
        assertEquals("The Device Admin PIN is the only way to leave a Child profile on this device, so it stays while any profile is a Child.", PinCopy.DEVICE_ADMIN_PIN_STAYS) // 16
        assertEquals("PIN type for Dave", PinCopy.pinType(name)) // 17
        assertEquals("Device Admin PIN type", PinCopy.DEVICE_ADMIN_PIN_TYPE)
        assertEquals("Remove PIN for Dave", PinCopy.removePin(name)) // 18
        assertEquals("Enter the PIN for Dave", PinCopy.switchNeedsPin(name)) // 20
        assertEquals("Use 4 to 6 numbers or directions.", PinCopy.PIN_LENGTH) // 21
    }

    @Test fun theRulesCarryTheNewText() {
        // 9: the lead of PIN Required and the refusals.
        assertEquals(PinCopy.KEEP_PIN_REASON, RoleRules.KEEP_PIN_REASON)
        // 15: Reset PIN refused for an actor with no PIN while a Child exists.
        assertEquals(
            RoleRules.ResetGate.Denied("Set a PIN on your own profile first."),
            RoleRules.resetGate(ProfileRole.User, actorHasPin = false, childExists = true),
        )
    }

    @Test fun theTwoTermsOnly() {
        val all = listOf(
            PinCopy.ADMIN_PAD, PinCopy.MANAGE_PROFILES_GATE, PinCopy.removeChild(name), PinCopy.DEVICE_ADMIN_PIN_ROW,
            PinCopy.MANAGE_PROFILES_ROW, PinCopy.ONBOARDING_ROLE, PinCopy.userQr(name), PinCopy.CHILD_ROLE,
            PinCopy.KEEP_PIN_REASON, PinCopy.NO_PIN_LEAVES_CHILD, PinCopy.setPin(name), PinCopy.changePin(name),
            PinCopy.confirmPin(name), PinCopy.CONFIRM_DEVICE_ADMIN_PIN, PinCopy.currentPin(name), PinCopy.resetActor(name),
            PinCopy.RESET_NEEDS_OWN_PIN, PinCopy.DEVICE_ADMIN_PIN_STAYS, PinCopy.pinType(name), PinCopy.DEVICE_ADMIN_PIN_TYPE,
            PinCopy.removePin(name), PinCopy.switchNeedsPin(name), PinCopy.PIN_LENGTH,
        )
        all.forEach { s ->
            // Never "Admin PIN" on its own, never "device PIN".
            assertFalse(s, s.replace("Device Admin PIN", "").contains("Admin PIN"))
            assertFalse(s, s.contains("device PIN"))
            assertTrue(s, s.all { it.code < 128 })
        }
    }
}
