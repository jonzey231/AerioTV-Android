package com.aeriotv.android.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aeriotv.android.core.preferences.AppPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Periodic Drive sync. Runs every 6h on unmetered networks while the device
 * is idle, but only if (a) the user has signed in to Drive (DriveSyncManager
 * holds an access token) and (b) the master Sync toggle is on.
 *
 * The token in DriveSyncManager.status is process-lifetime; this worker fires
 * even after the process has been recycled, so we no-op when there's nothing
 * authorized. The user can still trigger Sync Now manually any time.
 *
 * Conflict policy is the same as manual sync: push-then-pull serially, with
 * push winning by virtue of a fresher envelope timestamp.
 */
@HiltWorker
class DriveSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sync: DriveSyncManager,
    private val prefs: AppPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        if (!prefs.syncMasterEnabled.first()) {
            Log.i(TAG, "Master sync disabled — skipping")
            return@runCatching Result.success()
        }
        // Restore the session from the persisted token (or silently refresh)
        // rather than relying on in-process status, which is empty in the
        // fresh process WorkManager spawns hours later.
        val token = sync.ensureSignedIn()
        if (token == null) {
            Log.i(TAG, "No Drive token (not signed in / consent needed) — skipping")
            return@runCatching Result.success()
        }
        val enabled = SyncCategory.entries
            .filter { prefs.syncCategoryEnabled(it).first() }
            .toSet()
        if (enabled.isEmpty()) {
            Log.i(TAG, "No categories enabled — skipping")
            return@runCatching Result.success()
        }
        // Never auto-push before this install's first successful pull: a
        // blank fresh install would overwrite a populated Drive backup with
        // empty snapshots (user report). The pull below sets the flag, so
        // the next cycle pushes normally.
        val initialPullDone = prefs.syncInitialPullDone.first()
        val pushed = if (initialPullDone) {
            sync.pushAll(token, enabled)
        } else {
            Log.i(TAG, "Skipping push: no pull completed on this install yet")
            emptyMap()
        }
        val pulled = sync.pullAll(token, enabled)
        val failures = (pushed + pulled).count { !it.value }
        Log.i(TAG, "Periodic sync done: ${pushed.size}↑ ${pulled.size}↓ failures=$failures")
        if (failures == 0) Result.success() else Result.retry()
    }.getOrElse { t ->
        Log.w(TAG, "Periodic sync threw", t)
        Result.retry()
    }

    companion object {
        const val TAG = "DriveSyncWorker"
        const val UNIQUE_NAME = "aeriotv-drive-sync"
        private const val PERIOD_HOURS = 6L

        /** Register the periodic worker. Idempotent: call from app startup or
         * after a successful sign-in to (re)arm. KEEP policy preserves the
         * existing schedule so we don't reset the next-run timer. */
        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(false)
                .build()
            val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Tear down the periodic schedule — invoked when the user signs out
         * or disables the master toggle. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
