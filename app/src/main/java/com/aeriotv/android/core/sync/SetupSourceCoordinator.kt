package com.aeriotv.android.core.sync

import android.content.Context
import android.util.Log
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.isDispatcharrDirectConnect
import com.aeriotv.android.core.profiles.ProfileScope
import com.aeriotv.android.core.sync.dsync.DispatcharrSyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Rule 4 (docs/DispatcharrSyncSchema.md section 10a): when Google Drive and
 * Dispatcharr Sync both hold data for the signed-in Dispatcharr user, the
 * user picks the setup this device starts from and the other side is
 * replaced with it. Base profile only; sub-profiles never take part.
 */
@Singleton
class SetupSourceCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drive: DriveSyncManager,
    private val dsync: Provider<DispatcharrSyncManager>,
    private val playlistDao: PlaylistDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Probe(
        val playlistId: String,
        val drive: SetupSourceChoice.Summary,
        val dsync: SetupSourceChoice.Summary,
        val decision: SetupSourceChoice.Decision,
    )

    /**
     * Reads both sides without applying anything. Null (no choice to make)
     * for a sub-profile, no active Dispatcharr Direct Connect playlist, no
     * Drive sign-in, or either side unreadable.
     */
    suspend fun probe(): Probe? {
        if (ProfileScope.token.value.isNotEmpty()) return null
        val pid = withContext(ProfileScope.defaultOnly()) {
            playlistDao.allOnce().firstOrNull { it.isActive && it.isDispatcharrDirectConnect() }?.id
        } ?: return null
        val token = drive.ensureSignedIn()
        if (token == null && !drive.needsSignIn.value) return null // never signed in: no Drive side
        val d = token?.let { drive.readSharedSummary(it) }
        if (token == null) Log.w(TAG, SetupSourceChoice.driveProbeFailedLine(DriveNeedsSignInException()))
        val x = dsync.get().serverSummary(pid) ?: return null
        val decision = SetupSourceChoice.decideKnown(d, x) ?: run {
            // Unknown Drive (section 10a): do not decide. Dispatcharr goes on
            // alone; shared Drive files wait, and the probe runs again once
            // Drive answers (each Dispatcharr pull, or signing in again).
            drive.markDriveUnknown()
            Log.i(TAG, "[SYNC] setup source drive=unknown dsync=${x.categories.values.sumOf { it.rows }} rows decision=none (retry when Drive works)")
            return null
        }
        if (d == null) return null
        // Rule 5: no silent converge while this choice is open (the prompt decides).
        drive.setupChoicePending = decision is SetupSourceChoice.Decision.Ask
        Log.i(TAG, "[SYNC] setup source drive=${d.categories.values.sumOf { it.rows }} rows dsync=${x.categories.values.sumOf { it.rows }} rows decision=${SetupSourceChoice.decisionName(decision)}")
        return Probe(pid, d, x, decision)
    }

    /**
     * Before Dispatcharr Sync turns on (or Drive Sync, from Settings).
     * Drive: arm the forced Dispatcharr push, then apply the Drive snapshot.
     * Dispatcharr: Drive's shared categories stay unapplied (held) and the
     * next Drive push replaces the backup once the first Dispatcharr pull ran.
     */
    suspend fun choose(source: SetupSourceChoice.Source, playlistId: String, ignoreOwnership: Boolean = false) {
        Log.i(TAG, "[SYNC] setup source chosen: $source")
        drive.setupChoicePending = false
        drive.clearDriveUnknown()
        when (source) {
            SetupSourceChoice.Source.Drive -> {
                drive.clearReplaceWaits()
                // Dispatcharr Sync neither pulls nor pushes shared keys until the
                // apply is done; the forced replace is armed only per category
                // whose Drive backup applied (onboarding and Settings alike).
                val d = dsync.get()
                d.holdForChoice()
                try {
                    drive.releaseHold()
                    val applied = drive.applySharedForReplace(ignoreOwnership)
                    val cancel = com.aeriotv.android.core.sync.dsync.DsyncCoexistence.cancelledForceKeys(applied)
                    d.cancelForceReplace(playlistId, cancel) // leftovers from an earlier arm
                    cancel.mapNotNull { it.category }.distinct().forEach {
                        Log.i(TAG, "[SYNC] forced replace cancelled: Drive backup did not apply (${it.name})")
                    }
                    d.requestForceReplace(playlistId, com.aeriotv.android.core.sync.dsync.DsyncCoexistence.armedForceKeys(applied))
                } finally {
                    d.releaseChoiceHold()
                }
            }
            SetupSourceChoice.Source.Dispatcharr -> {
                // Fresh-device order (section 10a): armed before any hold is
                // released, so no Drive push of a shared file goes out before
                // the Dispatcharr pull has applied it here.
                drive.armReplaceWait()
                pendingDriveReplace = true
            }
        }
    }

    @Volatile private var pendingDriveReplace = false

    /**
     * After Dispatcharr Sync is on: finish a Dispatcharr choice. The Drive
     * backup is replaced in this session, right after the first pull (or its
     * time limit); a file whose Dispatcharr pull has not applied yet waits
     * (fresh-device order) and goes out with the pull's Drive follow.
     */
    fun afterDispatcharrOn() {
        if (!pendingDriveReplace) return
        pendingDriveReplace = false
        scope.launch {
            val done = dsync.get().awaitFirstPull(FIRST_PULL_WAIT_MS)
            if (!done) Log.i(TAG, "[SYNC] first Dispatcharr pull not finished; Drive files wait for it")
            drive.releaseHold()
            pullThenReplace()
        }
    }

    /**
     * Pull before replace (section 10a, field run 2026-11-01: the probe read
     * recents=14 on the server, this device pushed its own 13 to Drive): a
     * full Dispatcharr pull with the stamp check forced applies every shared
     * key first, and only then is the Drive backup replaced. A failed pull
     * replaces nothing; the next Dispatcharr cycle (converge) retries.
     */
    private suspend fun pullThenReplace() = replaceLock.withLock {
        if (!dsync.get().pullForChoice()) {
            drive.markReplaceDeferred()
            Log.i("DriveSyncManager", "[DRIVE] replace deferred: Dispatcharr pull failed")
            return@withLock
        }
        drive.clearReplaceDeferred()
        // The pulled progress rows become local first, so the replace
        // snapshot counts them as local, not as placeholders (section 10a).
        runCatching { dsync.get().awaitProgressLocal() }
            .onFailure { Log.w("DriveSyncManager", "[DSYNC] progress before replace failed: ${it.javaClass.simpleName}") }
        drive.replaceBackupNow()
    }

    private val replaceLock = kotlinx.coroutines.sync.Mutex()

    /** A deferred Dispatcharr-choice replace: retry on the next cycle (never inline, never twice at once). */
    fun retryDeferredReplace() {
        if (!drive.replaceDeferred() || replaceLock.isLocked) return
        scope.launch { pullThenReplace() }
    }

    /**
     * Unknown Drive retry (section 10a): the earlier probe could not read
     * Drive. Probe again; a readable Drive decides now (UseDispatcharr or
     * UseDrive, both providers already on). Ask stays pending for the prompt
     * the Sync screen opens. Still unreadable: stay unknown.
     */
    suspend fun retryUnknownDrive() {
        if (!drive.driveUnknown() || retrying) return
        retrying = true
        try {
            val p = probe() ?: return
            when (p.decision) {
                SetupSourceChoice.Decision.UseDispatcharr -> {
                    choose(SetupSourceChoice.Source.Dispatcharr, p.playlistId)
                    afterDispatcharrOn()
                }
                SetupSourceChoice.Decision.UseDrive -> choose(SetupSourceChoice.Source.Drive, p.playlistId, ignoreOwnership = true)
                is SetupSourceChoice.Decision.Ask -> Log.i(TAG, "[SYNC] setup source needs the prompt: open Settings > Sync")
            }
        } finally {
            retrying = false
        }
    }

    @Volatile private var retrying = false

    /** The Sync screen opens the prompt for a pending unknown-Drive choice. */
    fun driveUnknownPending(): Boolean = drive.driveUnknown()

    /** No choice was needed or possible: apply whatever Drive held and carry on as before. */
    suspend fun releaseHeld() {
        drive.setupChoicePending = false
        if (!drive.sharedHeld()) return
        drive.releaseHold()
        drive.applySharedNow(ignoreOwnership = false)
    }

    fun holdShared() = drive.holdShared()

    private companion object {
        const val TAG = "AerioSync"
        const val FIRST_PULL_WAIT_MS = 120_000L
    }
}
