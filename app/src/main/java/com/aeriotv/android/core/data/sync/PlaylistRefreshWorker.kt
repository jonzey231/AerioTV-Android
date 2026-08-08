package com.aeriotv.android.core.data.sync

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
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.preferences.AppPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Periodic background refresh of the active playlist's channel list + EPG.
 * Audit task #48: addresses Archie's "loading times shouldn't be an issue if
 * caching is working" complaint by keeping the cache warm so the user never
 * cold-paints blank UI.
 *
 * Each successful run rewrites:
 *   - channel_snapshot (Phase 130) so the rail re-paints instantly on the
 *     next cold launch, no network needed.
 *   - epg_programme (Phase 121) so now-playing / the guide is current.
 *
 * Runs every 6h on an unmetered network with a charging-not-required +
 * battery-not-low constraint. Periodic WorkManager fires opportunistically
 * once those constraints become true; users on mobile data will only get
 * a refresh on their next Wi-Fi+power session, which is exactly the
 * iOS BGTaskScheduler behaviour we're matching.
 *
 * The user can toggle this entirely via [AppPreferences.backgroundRefreshEnabled]
 * (Network Settings). Default ON. With the toggle off, [enqueuePeriodic] is
 * a no-op and any previously-scheduled work is canceled.
 *
 * No `lastRefreshedAt` freshness check here — WorkManager already paces the
 * runs, and the periodic interval is the only knob we need. A miss (no
 * active playlist, no network mid-window, etc) returns Result.success() so
 * WorkManager re-schedules the next interval at the normal cadence, not a
 * backoff penalty.
 */
@HiltWorker
class PlaylistRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: PlaylistRepository,
    private val prefs: AppPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        if (!prefs.backgroundRefreshEnabled.first()) {
            Log.i(TAG, "Background refresh disabled in settings; skipping")
            return@runCatching Result.success()
        }
        val playlist = repository.activePlaylist()
        if (playlist == null) {
            Log.i(TAG, "No active playlist; nothing to refresh")
            return@runCatching Result.success()
        }
        // Channel refresh: writes channel_snapshot via the same path the
        // user-initiated refresh uses. saveChannelsToCache is best-effort
        // inside PlaylistRepository.refresh -- a cache write failure won't
        // fail the network fetch, and vice versa.
        val channels = repository.refresh(playlist)
        if (channels.isFailure) {
            Log.w(TAG, "Channel refresh failed", channels.exceptionOrNull())
            return@runCatching Result.retry()
        }
        // EPG refresh: writes epg_programme. loadEpg already updates the
        // playlist's lastEpgRefreshedAt on success. Pass the candidate-key
        // set (P3 #13) so the XMLTV parser filters dead programmes inline.
        val knownKeys = com.aeriotv.android.core.data.buildChannelEpgKeyBridge(
            channels.getOrThrow(),
        ).keys
        val epg = repository.loadEpg(playlist, knownKeys, channels.getOrThrow())
        if (epg.isFailure) {
            Log.w(TAG, "EPG refresh failed", epg.exceptionOrNull())
            // EPG miss is recoverable: channels are still fresh.
            return@runCatching Result.retry()
        }
        // iOS parity (EPGGuideView.swift lines 1395-1414): bridge programme
        // channelIds onto the canonical key the M3UChannel will look up under,
        // so Dispatcharr `/output/epg` (channel-number keyed) and Dummy EPG
        // (UUID keyed) feeds populate the rail even when tvgID is blank.
        // Saved-as-bridged means a relaunch can paint cached programmes onto
        // the rail without the in-memory bridge step at all.
        val bridged = com.aeriotv.android.core.data.bridgeChannelIds(
            epg.getOrThrow(),
            channels.getOrThrow(),
        )
        runCatching { repository.saveEpgToCache(playlist.id, bridged) }
            .onFailure { Log.w(TAG, "saveEpgToCache failed", it) }
        Log.i(
            TAG,
            "Background refresh OK: ${channels.getOrThrow().size} channels, " +
                "${epg.getOrThrow().size} programmes",
        )
        Result.success()
    }.getOrElse { t ->
        Log.w(TAG, "Background refresh threw", t)
        Result.retry()
    }

    companion object {
        const val TAG = "PlaylistRefreshWorker"
        const val UNIQUE_NAME = "aeriotv-playlist-refresh"

        /**
         * Idempotent registration. Call from app startup with the user's
         * configured interval (default 360 minutes = 6 hours, matching the
         * prior hardcoded `PERIOD_HOURS`). Uses `UPDATE` policy so a changed
         * interval re-registers immediately instead of waiting for the
         * existing schedule to expire -- the iOS `bgRefreshIntervalMins`
         * setting takes effect within seconds of toggling.
         */
        fun enqueuePeriodic(context: Context, intervalMins: Int = 360) {
            val safeMins = intervalMins.coerceAtLeast(15)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<PlaylistRefreshWorker>(
                safeMins.toLong(), TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // UPDATE so changing the interval in Settings re-anchors the
                // schedule immediately. The OLD KEEP policy left a 6h job
                // running until its next firing, ignoring the user's pick.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Cancel the schedule -- called when the user disables background refresh. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
