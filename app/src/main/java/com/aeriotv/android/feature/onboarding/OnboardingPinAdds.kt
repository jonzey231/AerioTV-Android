package com.aeriotv.android.feature.onboarding

/**
 * Item 14 of the profile follow-ups (Apple parity, 2026-11-11): the Add
 * Profiles step asks for a missing PIN (a Child exists) right after each
 * manual add and right after each "Add selected" batch, as Apple does.
 * Cancel on that PIN step removes only that add or that batch (item 7's
 * rule, RoleRules.pinStepCancelDiscards); Continue's PIN walk passes no
 * adds, so cancelling it removes nothing. Pure, for the unit tests.
 */
class OnboardingPinAdds {
    private val batch = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** "Add selected" starts a batch: earlier adds are no longer this action's. */
    fun startBatch() = batch.clear()

    /** A profile the current batch added (a keyed row, or one from a credential prompt). */
    fun batchAdded(id: String) { batch += id }

    /** The adds the PIN step right after the batch undoes on Cancel. */
    fun batchAdds(): List<String> = batch.toList()

    /** Continue: its PIN walk passes no adds. */
    fun continueAdds(): List<String> {
        batch.clear()
        return emptyList()
    }

    companion object {
        /** A manual add asks right away, with only that add, when it needs a PIN; null = no PIN step. */
        fun afterManualAdd(id: String, needsPin: Boolean): List<String>? = if (needsPin) listOf(id) else null
    }
}
