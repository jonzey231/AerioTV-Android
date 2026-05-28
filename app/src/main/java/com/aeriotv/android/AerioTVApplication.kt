package com.aeriotv.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
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
import com.aeriotv.android.feature.player.MpvLibraryWarmup
import com.aeriotv.android.feature.reminders.ReminderBannerBus
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
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
    @Inject lateinit var playlistRepository: PlaylistRepository

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
                add(SafeUrlInterceptor())
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
        // Process-wide libmpv warmup (Phase 94: the "don't-destroy" retry of
        // the Phase 78/82 attempt). The original warmup called MPV.destroy()
        // on its throwaway handle, whose nativeDestroy() released JNI globals
        // the next nativeCreate() needed -- every subsequent stream came up
        // dead. MpvLibraryWarmup now RETAINS the warmed handle for the process
        // lifetime instead of destroying it, so the codec/protocol/hwdec
        // registrations + JNI globals stay resident and the first channel tap
        // hits libmpv's warm path. See MpvLibraryWarmup.warmHandle.
        MpvLibraryWarmup.start(this)
        // Audit task #37: periodic resource snapshots (PSS, FD count, thermal,
        // sys memory) into logcat, debug builds only. Diagnostic trail for the
        // "AerioTV crashes on the Google TV Streamer" reports - by the time a
        // crash hits we have a recent timeline of memory + thermal pressure.
        resourceTelemetry.start()
        appScope.launch {
            appPreferences.debugLoggingEnabled.collectLatest { enabled ->
                debugLogger.setEnabled(enabled)
            }
        }
        // Audit task #48: periodic background EPG + channel refresh so the
        // cache is always warm. Driven by a DataStore toggle (default ON,
        // user-overridable in Network Settings). collectLatest re-evaluates
        // whenever the user flips it.
        appScope.launch {
            appPreferences.backgroundRefreshEnabled.collectLatest { enabled ->
                if (enabled) {
                    PlaylistRefreshWorker.enqueuePeriodic(this@AerioTVApplication)
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
