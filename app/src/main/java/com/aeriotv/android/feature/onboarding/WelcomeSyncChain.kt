package com.aeriotv.android.feature.onboarding

import com.aeriotv.android.core.sync.SyncProviderId

/**
 * Welcome "Keep your devices in sync" (Logan, 2026-10-30): both cards are
 * selectable and Continue runs one chain. Pure logic so the order is unit
 * tested; Navigation.kt walks it.
 */
object WelcomeSyncChain {
    enum class Step {
        /** Google sign-in, Drive on, restore (tryRestoreAndAdvance). */
        DriveRestore,
        /** Connect a Server, Dispatcharr entry, Dispatcharr Sync on after the connect. */
        ConnectServer,
        /** "Turn on Dispatcharr Sync?" step (autoEnable when Dispatcharr was selected). */
        DispatcharrSync,
        /** "Also back up to Google Drive?" step. */
        DriveOffer,
        AddProfiles,
        Main,
    }

    /**
     * Steps after Continue. [restoredIsDispatcharr]: the Drive restore activated a
     * Dispatcharr Direct Connect playlist (ignored when Drive is not selected).
     * [driveAvailable]: the Google client id is configured in this build.
     */
    fun plan(
        drive: Boolean,
        dispatcharr: Boolean,
        restoredIsDispatcharr: Boolean,
        driveAvailable: Boolean,
    ): List<Step> {
        if (!drive && !dispatcharr) return emptyList()
        val steps = mutableListOf<Step>()
        if (drive) steps += Step.DriveRestore
        if (dispatcharr) {
            if (!(drive && restoredIsDispatcharr)) steps += Step.ConnectServer
            steps += Step.DispatcharrSync
            if (showDriveOffer(driveOn = drive, driveAvailable = driveAvailable)) steps += Step.DriveOffer
            steps += Step.AddProfiles
        } else if (restoredIsDispatcharr) {
            // Drive only, restore brought a Dispatcharr playlist: the existing
            // f01a69c1 step asks (no auto-on), then Add Profiles.
            steps += Step.DispatcharrSync
            steps += Step.AddProfiles
        }
        steps += Step.Main
        return steps
    }

    /** "Also back up to Google Drive?" runs after the Dispatcharr Sync step only while Drive is off and available. */
    fun showDriveOffer(driveOn: Boolean, driveAvailable: Boolean): Boolean = !driveOn && driveAvailable

    /** After the Drive restore: continue into Connect a Server (true) or the sync step on the restored playlist (false). */
    fun connectAfterRestore(dispatcharr: Boolean, restoredIsDispatcharr: Boolean): Boolean =
        dispatcharr && !restoredIsDispatcharr

    /**
     * Providers selected or answered in this onboarding flow; the one-time
     * providers offer (2eb421a4) leaves them out. Process memory only.
     */
    private val answered = java.util.Collections.synchronizedSet(mutableSetOf<SyncProviderId>())
    fun markAnswered(id: SyncProviderId) { answered.add(id) }
    fun wasAnswered(id: SyncProviderId): Boolean = id in answered
    fun filterOffers(offers: List<SyncProviderId>): List<SyncProviderId> = offers.filterNot { wasAnswered(it) }
    internal fun resetForTest() { answered.clear(); kept = null; returnedFromChoice = false }

    /** The Welcome card selections of the last Continue, restored after a Cancel on the setup prompt. */
    @Volatile var kept: Pair<Boolean, Boolean>? = null
        private set
    fun keepSelection(drive: Boolean, dispatcharr: Boolean) { kept = drive to dispatcharr }

    @Volatile private var returnedFromChoice = false
    fun markReturnedFromChoice() { returnedFromChoice = true }
    /** True once after a setup prompt Cancel, so Welcome does not auto-advance on the restored playlist. */
    fun consumeReturnedFromChoice(): Boolean = returnedFromChoice.also { returnedFromChoice = false }
}
