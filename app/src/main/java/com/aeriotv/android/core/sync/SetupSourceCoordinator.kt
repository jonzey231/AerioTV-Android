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
        val token = drive.ensureSignedIn() ?: return null
        val d = drive.readSharedSummary(token) ?: return null
        val x = dsync.get().serverSummary(pid) ?: return null
        val decision = SetupSourceChoice.decide(d, x)
        Log.i(TAG, "[SYNC] setup source drive=${d.categories.values.sumOf { it.rows }} rows dsync=${x.categories.values.sumOf { it.rows }} rows decision=${decision.javaClass.simpleName}")
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
        when (source) {
            SetupSourceChoice.Source.Drive -> {
                dsync.get().requestForceReplace(playlistId)
                drive.releaseHold()
                drive.applySharedNow(ignoreOwnership)
            }
            SetupSourceChoice.Source.Dispatcharr -> pendingDriveReplace = true
        }
    }

    @Volatile private var pendingDriveReplace = false

    /** After Dispatcharr Sync is on: finish a Dispatcharr choice once its first pull is done. */
    fun afterDispatcharrOn() {
        if (!pendingDriveReplace) return
        pendingDriveReplace = false
        scope.launch {
            val done = dsync.get().awaitFirstPull(FIRST_PULL_WAIT_MS)
            if (!done) Log.i(TAG, "[SYNC] first Dispatcharr pull not finished; Drive backup replaced anyway")
            drive.releaseHold()
            drive.replaceBackupOnNextPush()
        }
    }

    /** No choice was needed or possible: apply whatever Drive held and carry on as before. */
    suspend fun releaseHeld() {
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
