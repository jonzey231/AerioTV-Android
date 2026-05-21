package com.aeriotv.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aeriotv.android.core.debug.DebugLogger
import com.aeriotv.android.core.network.DispatcharrWarmupCoordinator
import com.aeriotv.android.core.preferences.AppPreferences
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
        // MpvLibraryWarmup.start(this) — DISABLED.
        //
        // The iOS-canonical pattern (mpv_create + mpv_initialize +
        // mpv_terminate_destroy on a throwaway handle at app launch)
        // does not port cleanly to mpv-android-lib 0.1.12. Calling
        // MPV.destroy() on the throwaway breaks subsequent user-facing
        // MPV instances on both emulator + physical Samsung devices --
        // the player chrome loads but the stream never begins, no
        // events, no first frame. Working theory: mpv-android-lib's
        // nativeDestroy releases JNI globals or the JavaVM cache that
        // are then unavailable to the next nativeCreate. Reproducible
        // 100% with the warmup on, gone 100% with it off.
        //
        // Future work: investigate keeping the warmed handle alive
        // (don't destroy() it -- "leak" the JNI globals deliberately)
        // OR reuse the warmed handle as the user-facing handle. Both
        // need API changes in MPVPlayerHolder to thread the existing
        // MPV instance through instead of always letting BaseMPVView
        // create its own.
        appScope.launch {
            appPreferences.debugLoggingEnabled.collectLatest { enabled ->
                debugLogger.setEnabled(enabled)
            }
        }
    }
}
