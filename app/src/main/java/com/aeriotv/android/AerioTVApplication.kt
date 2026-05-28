package com.aeriotv.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aeriotv.android.core.debug.DebugLogger
import com.aeriotv.android.core.debug.ResourceTelemetry
import com.aeriotv.android.core.network.DispatcharrWarmupCoordinator
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
class AerioTVApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var dispatcharrWarmup: DispatcharrWarmupCoordinator
    @Inject lateinit var debugLogger: DebugLogger
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var reminderBannerBus: ReminderBannerBus
    @Inject lateinit var resourceTelemetry: ResourceTelemetry
    @Inject lateinit var multiviewStore: MultiviewStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

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
    }
}
