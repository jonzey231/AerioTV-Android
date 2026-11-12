package com.aeriotv.android.core.profiles

/**
 * The PIN copy, approved by the owner on 2026-11-12 and byte-identical with
 * Apple (pin copy review, items 1 to 21). Two terms only: "profile PIN" (a
 * PIN on one profile) and "Device Admin PIN" (always in full; never "Admin
 * PIN" alone, never "device PIN"). [name] is the profile's display name as
 * the profile list shows it. Pure, for the tests.
 */
object PinCopy {
    /** 1, 19: the Admin pad (Manage Profiles gate, role change away from Child, TV picker Add Profile) and its refusals. */
    const val ADMIN_PAD = "Enter an Admin or Standard profile PIN, or the Device Admin PIN"

    /** 2: the gate sentence on a Child's Manage Profiles page. */
    const val MANAGE_PROFILES_GATE = "To switch or add profiles, enter an Admin or Standard profile PIN, or the Device Admin PIN."

    /** 3: removing a Child profile. */
    fun removeChild(name: String) = "Enter an Admin or Standard profile PIN, or the Device Admin PIN, to remove $name"

    /** 4: the Device Admin PIN row's subtitle, everywhere the row shows. */
    const val DEVICE_ADMIN_PIN_ROW = "Lets a parent leave a Child profile and manage profiles on this device. Separate from profile PINs. Never synced."

    /** 5: the Manage Profiles row in a Child's Settings (phone list, tablet sidebar, TV rail). */
    const val MANAGE_PROFILES_ROW = "Switch or add profiles (PIN required)"

    /** 6: onboarding's "Who is this profile for?" subtext. */
    const val ONBOARDING_ROLE = "Pick Child and you'll set a Device Admin PIN next. A parent uses it to leave the Child profile on this device. It is never synced."

    /** 7: Show User QR Code's PIN pad. */
    fun userQr(name: String) = "Enter the PIN for $name, or the Device Admin PIN, to show this code"

    /** 8: the Child role's description. */
    const val CHILD_ROLE = "Sees only its own content and basic settings. Leaving it needs an Admin or Standard profile PIN, or the Device Admin PIN."

    /** 9: the lead of PIN Required, the Remove PIN refusal, and the role refusal (RoleRules.KEEP_PIN_REASON). */
    const val KEEP_PIN_REASON = "While a Child profile exists, every Admin and Standard profile needs its own profile PIN."

    /** 10: PIN Required when no PIN can leave a Child. */
    const val NO_PIN_LEAVES_CHILD = "Leaving a Child profile needs an Admin or Standard profile PIN, or the Device Admin PIN. Set one first."

    /** 11: Set or Change PIN, first entry (also the PIN Required walk-through and Reset with a temporary PIN). */
    fun setPin(name: String) = "Set PIN for $name"
    fun changePin(name: String) = "Change PIN for $name"

    /** 12: the confirm step. */
    fun confirmPin(name: String) = "Confirm PIN for $name"
    const val CONFIRM_DEVICE_ADMIN_PIN = "Confirm Device Admin PIN"

    /** 13: Change PIN, the current PIN step. */
    fun currentPin(name: String) = "Enter the current PIN for $name"

    /** 14: Reset another profile's PIN, the actor's pad. */
    fun resetActor(name: String) = "Enter your profile PIN to reset the PIN for $name"

    /** 15: Reset PIN refused (the actor has no PIN while a Child exists). */
    const val RESET_NEEDS_OWN_PIN = "Set a PIN on your own profile first."

    /** 16: Remove Device Admin PIN refused. */
    const val DEVICE_ADMIN_PIN_STAYS = "The Device Admin PIN is the only way to leave a Child profile on this device, so it stays while any profile is a Child."

    /** 17: the PIN type step. */
    fun pinType(name: String) = "PIN type for $name"
    const val DEVICE_ADMIN_PIN_TYPE = "Device Admin PIN type"

    /** 18: Remove PIN, the current PIN pad. */
    fun removePin(name: String) = "Remove PIN for $name"

    /** 20: a switch refused without a PIN pass. */
    fun switchNeedsPin(name: String) = "Enter the PIN for $name"

    /** 21: a PIN of the wrong length. */
    const val PIN_LENGTH = "Use 4 to 6 numbers or directions."
}
