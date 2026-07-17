package com.aeriotv.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.room.withTransaction
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.aeriotv.android.core.data.db.AerioDatabase
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.data.sync.PlaylistRefreshWorker
import com.aeriotv.android.core.debug.DebugLogger
import com.aeriotv.android.core.debug.MemoryPressureBus
import com.aeriotv.android.core.debug.ResourceTelemetry
import com.aeriotv.android.core.network.ActivePlaylistCredentials
import com.aeriotv.android.core.network.DispatcharrImageAuthInterceptor
import com.aeriotv.android.core.network.DispatcharrWarmupCoordinator
import com.aeriotv.android.core.security.SafeUrlInterceptor
import okhttp3.OkHttpClient
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.feature.multiview.MultiviewStore
import com.aeriotv.android.feature.reminders.ReminderBannerBus
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Hilt Application + WorkManager Configuration.Provider so DriveSyncWorker can
 * have its Hilt-provided dependencies (DriveSyncManager, AppPreferences) injected
 * via @HiltWorker. Without the Configuration.Provider hook WorkManager auto-init
 * runs first and constructs workers via the default reflective factory, which
 * doesn't know about Hilt.
 *
 * Also binds the Dispatcharr warmup coordinator to the process-wide lifecycle
 * so JWT refresh runs on every foreground entry, and pipes the
 * `debugLoggingEnabled` DataStore flow into DebugLogger so the file writer
 * flips on/off the moment the user toggles it in Settings -> Developer.
 */
@HiltAndroidApp
class AerioTVApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var dispatcharrWarmup: DispatcharrWarmupCoordinator
    @Inject lateinit var debugLogger: DebugLogger
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var reminderBannerBus: ReminderBannerBus
    @Inject lateinit var resourceTelemetry: ResourceTelemetry
    @Inject lateinit var multiviewStore: MultiviewStore
    @Inject lateinit var memoryPressureBus: MemoryPressureBus
    @Inject lateinit var activeCredentials: ActivePlaylistCredentials
    @Inject lateinit var castReceiver: com.aeriotv.android.core.cast.AerioCastReceiverController
    @Inject lateinit var castNotificationController: com.aeriotv.android.core.cast.CastNotificationController
    @Inject lateinit var companionHost: com.aeriotv.android.core.cast.companion.CompanionHostController
    @Inject lateinit var playlistRepository: PlaylistRepository
    @Inject lateinit var playlistDao: PlaylistDao
    @Inject lateinit var aerioDatabase: AerioDatabase
    @Inject lateinit var timeshiftStore: com.aeriotv.android.core.timeshift.TimeshiftBufferStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Custom Coil ImageLoader with explicit cache caps. Coil's defaults are
     * 25% of app heap for the memory cache and 2% of free disk for the disk
     * cache, which on a 4GB Android TV with 384MB heap works out to ~96MB
     * resident bitmaps + potentially hundreds of MB on disk. With ~700 channel
     * logos cached as users scroll the guide, this fills fast and pushes the
     * system into swap-thrash (Archie reported the Streamer staying slow even
     * after uninstalling AerioTV; matches the swap-pressure signature).
     *
     * Trimmed to 32MB memory + 100MB disk. The memory cap is more than enough
     * for ~50 channel logos worth of bitmap pixels - LazyColumn / LazyVerticalGrid
     * recycle off-screen views, so we don't need a giant cache to keep the
     * visible row smooth. The disk cap keeps repeat-visit cold-starts fast
     * without filling /data/data/com.aeriotv.android over time.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Audit task #54: dedicated OkHttp client for Coil that injects the
        // Dispatcharr X-API-Key (+ Authorization fallback) on any image
        // request whose URL matches the active playlist's base prefix.
        // Header injection is scoped via ActivePlaylistCredentials so a
        // third-party tvg-logo CDN doesn't see the key.
        val imageHttp = OkHttpClient.Builder()
            .addInterceptor(DispatcharrImageAuthInterceptor(activeCredentials))
            .build()
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(32L * 1024L * 1024L)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_disk_cache"))
                    .maxSizeBytes(100L * 1024L * 1024L)
                    .build()
            }
            .components {
                // The OkHttp fetcher factory + custom client run BEFORE the
                // SSRF gate so the scheme check still kicks in on every
                // request, but the auth header arrives in time for the
                // network call.
                add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp }))
                // Audit task #53: SSRF-style gate -- block image fetches
                // whose source URL uses file:// / content:// / javascript:
                // / etc. Playlist providers can put anything in `tvg-logo`
                // and a few other text fields; this keeps Coil from
                // honouring a hostile value.
                add(SafeUrlInterceptor(activeCredentials))
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        dispatcharrWarmup.bind()
        // Track foreground state so reminders that fire while the app is open
        // surface as an in-app banner instead of a system notification.
        reminderBannerBus.bind()
        // Cast Connect receiver (GH #33). No-op on phones/tablets and on any
        // device without Google Play services; only an Android TV can be
        // launched as a receiver. Wired here so the receiver is ready the moment
        // a sender casts, before MainActivity forwards the LAUNCH intent.
        castReceiver.bootstrap(this)
        // GH #33: standalone "Casting to <TV>" notification driven by cast state,
        // so it reappears after a force-close/reopen while the session resumes
        // (the media FGS notification can't cover that case).
        castNotificationController.start()
        // GH #33 companion remote (second-screen): on Android TV, advertise over
        // mDNS/NSD + run the pairing WebSocket server so a phone can drive this
        // TV's native player. No-op on phones (FEATURE_LEANBACK-gated internally).
        companionHost.start()
        // Live Rewind launch sweep (user clarification 2026-07-11: buffers
        // die an hour after the SESSION ends, "which may end up meaning it
        // should be deleted the NEXT time the app is launched").
        // TimeshiftController's own reaper only runs when playback first
        // touches it, so a launch where the user never tunes a channel
        // would otherwise leave yesterday's buffers on disk.
        appScope.launch {
            runCatching {
                timeshiftStore.pruneExpired(
                    com.aeriotv.android.core.timeshift.TimeshiftController.FIXED_RETENTION_MS,
                )
                timeshiftStore.enforceBudget(timeshiftStore.freeSpaceBudgetBytes())
            }
        }
        // libmpv is gone (task #67). Media3's ExoPlayer + MediaCodec
        // path doesn't need a process-wide warmup pre-pay -- the first
        // ExoPlayer.Builder allocation handles the framework warm-up
        // implicitly.
        // Audit task #37: periodic resource snapshots (PSS, FD count, thermal,
        // sys memory) into logcat, debug builds only. Diagnostic trail for the
        // "AerioTV crashes on the Google TV Streamer" reports - by the time a
        // crash hits we have a recent timeline of memory + thermal pressure.
        resourceTelemetry.start()
        appScope.launch {
            // distinctUntilChanged: DataStore re-emits on EVERY write to ANY
            // key in the store, and setEnabled(true) appends an "ENABLED"
            // anchor line each time it runs. Only react to actual flips.
            appPreferences.debugLoggingEnabled.distinctUntilChanged().collectLatest { enabled ->
                debugLogger.setEnabled(enabled)
            }
        }
        // Audit task #48: periodic background EPG + channel refresh so the
        // cache is always warm. Driven by a DataStore toggle (default ON,
        // user-overridable in Network Settings). collectLatest re-evaluates
        // whenever the user flips it.
        appScope.launch {
            // P1 #7: react to BOTH the on/off toggle AND the user's chosen
            // interval. `combine` re-fires whenever either flow emits, so a
            // user changing the interval from 6h to 24h immediately
            // re-anchors the WorkManager schedule (UPDATE policy on the
            // worker side). The pair is observed once at startup; a fresh
            // install collects the default (true, 360min).
            combine(
                appPreferences.backgroundRefreshEnabled,
                appPreferences.backgroundRefreshIntervalMins,
            ) { enabled, mins -> enabled to mins }
                .collectLatest { (enabled, mins) ->
                    if (enabled) {
                        PlaylistRefreshWorker.enqueuePeriodic(
                            this@AerioTVApplication,
                            intervalMins = mins,
                        )
                    } else {
                        PlaylistRefreshWorker.cancel(this@AerioTVApplication)
                    }
                }
        }
        // Audit task #54: prime the credential cache from disk on cold
        // launch so Coil's first batch of Dispatcharr logo requests (the
        // ones fired in the splash -> bootstrap window, before any
        // user-initiated load) carry X-API-Key. PlaylistRepository
        // .activePlaylist() also publishes; this is the bootstrap nudge.
        appScope.launch {
            runCatching { playlistRepository.activePlaylist() }
        }
        // Audit task #53: one-time pass that re-encrypts existing plaintext
        // playlist credentials at rest. New writes already encrypt via the
        // EncryptingPlaylistDao decorator; this upgrades rows saved by older
        // builds. Reading through the decorator yields cleartext, the targeted
        // updateCredentials() re-encrypts only the three credential columns.
        //
        // Wrapped in a single Room transaction so the read+writes are atomic:
        // a concurrent cold-start write (warmup refreshing apiKey on 401,
        // refresh() stamping lastRefreshedAt/channelCount) can neither be
        // clobbered by this pass nor interleave with it, and Room coalesces the
        // invalidations into one Flow emission. Idempotent and flag-guarded, so
        // a fresh install (no rows) just sets the flag and a kill mid-pass
        // re-runs harmlessly next launch. Rows with no credentials are skipped.
        appScope.launch {
            runCatching {
                if (!appPreferences.credentialsEncryptedOnce()) {
                    aerioDatabase.withTransaction {
                        playlistDao.allOnce().forEach { row ->
                            if (!row.apiKey.isNullOrBlank() ||
                                !row.username.isNullOrBlank() ||
                                !row.password.isNullOrBlank()
                            ) {
                                playlistDao.updateCredentials(
                                    row.id,
                                    row.apiKey,
                                    row.username,
                                    row.password,
                                )
                            }
                        }
                    }
                    appPreferences.setCredentialsEncrypted(true)
                }
            }
        }
    }

    /**
     * Forward system memory-pressure callbacks to the telemetry so a
     * TRIM_MEMORY_RUNNING_CRITICAL / TRIM_MEMORY_COMPLETE is logged
     * immediately before the system kills us (best diagnostic signal we have
     * for OOM-class TV crashes). Future phases can react here to shed memory
     * proactively - drop the in-memory EPG cache, close multiview tiles past
     * the soft limit, etc.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        resourceTelemetry.onTrimMemory(level)
        // Audit task #35 OOM guard: shed inactive multiview tiles when the
        // system signals critical pressure. Multiview is the single largest
        // resource consumer (up to 9 concurrent mpv handles + SurfaceViews +
        // audio tracks), so dropping non-focused tiles is the most effective
        // way to keep the process alive. No-op for softer trim levels.
        multiviewStore.onMemoryPressure(level)
        // Audit task #58 (Phase 144): fan-out to any nav-scoped ViewModel
        // that can drop large in-memory state (PlaylistViewModel's
        // epgByChannel map; future: parsed playlists, large bitmap arenas).
        // The bus's replay=1 means a ViewModel created after this fires
        // still sees the most recent signal and can shed accordingly.
        memoryPressureBus.emit(level)
    }
}
