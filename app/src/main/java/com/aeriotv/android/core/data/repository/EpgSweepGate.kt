package com.aeriotv.android.core.data.repository

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Process-wide "is it a good moment to spend a background request?" gate for
 * the quiet EPG sweep in [PlaylistRepository.startEpgBackgroundSweep]
 * (Logan 2026-09-12).
 *
 * The sweep must be invisible: it pauses while the app is backgrounded,
 * while a tune is still waiting on its first frame, and while Multiview is
 * on screen, and resumes at the chunk it stopped on. Two volatile flags polled by the sweep, deliberately not a
 * flow: nothing here should cost a subscription or a recomposition.
 */
object EpgSweepGate {
    /** True while ANY activity of the process is started (on screen).
     *  Driven by [ProcessLifecycleOwner] via [install], never by a nav
     *  destination's lifecycle: the MAIN destination reads STOPPED while the
     *  full-screen player route sits on top of it (Android#131), which made a
     *  RUNNING_CRITICAL trim during playback shed the guide as if the app
     *  were backgrounded. Starts true so a cold launch (which is by definition
     *  foreground) never stalls the sweep waiting for an ON_START it already
     *  missed. */
    @Volatile
    var appInForeground: Boolean = true
        private set

    private val _returnedToForeground = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits on every process ON_START after a process ON_STOP (not on the
     *  cold-launch start), so the guide owner can repaint a shed guide. */
    val returnedToForeground: SharedFlow<Unit> = _returnedToForeground

    @Volatile
    private var installed = false

    /** Hook the process lifecycle. Call once from Application.onCreate (main
     *  thread). Idempotent. */
    fun install() {
        if (installed) return
        installed = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onProcessStart()
            override fun onStop(owner: LifecycleOwner) = onProcessStop()
        })
    }

    /** Process became visible. Internal for the unit test. */
    internal fun onProcessStart() {
        val wasBackground = !appInForeground
        appInForeground = true
        if (wasBackground) _returnedToForeground.tryEmit(Unit)
    }

    /** Process left the screen (every activity stopped). */
    internal fun onProcessStop() {
        appInForeground = false
    }

    @Volatile
    private var tuneStartedAtMs: Long = 0L

    /** A tune has begun; the sweep holds off until its first frame. */
    fun onTuneStart() {
        tuneStartedAtMs = SystemClock.elapsedRealtime()
    }

    /** First frame (or STATE_READY) landed; the sweep may spend requests again. */
    fun onTunePlaying() {
        tuneStartedAtMs = 0L
    }

    /**
     * True while a tune is before its first frame. Ceilinged at
     * [TUNE_MAX_MS]: a missed [onTunePlaying] (a tune that failed, a screen
     * torn down mid-prime) must not wedge the sweep for the life of the
     * process.
     */
    val tuneInProgress: Boolean
        get() {
            val started = tuneStartedAtMs
            return started != 0L && SystemClock.elapsedRealtime() - started < TUNE_MAX_MS
        }

    /**
     * Local Multiview screens on screen right now. Measured 2026-11-06 on the
     * Google TV Streamer (two tiles, 4K HEVC plus 1080p): while the launch EPG
     * sweep walked its 38 day chunks (grid JSON parse plus a Room merge per
     * chunk, about 6.5 minutes), the app ran a concurrent GC every 2 to 3 s
     * freeing up to 1 million objects (35 MB) with 10 to 22 ms pauses, the
     * four cores sat at 2 to 20 percent idle, and BOTH tiles dropped 5 to 84
     * frames per 10 s in step with each other. Once the sweep finished, with
     * the same tiles and channels: one GC a minute and 0 to 4 drops per 10 s
     * on the 4K tile. A guide refresh is never worth a frame of playback, so
     * every sweep holds while Multiview is up and resumes at the chunk it
     * stopped on.
     */
    private val multiviewScreens = java.util.concurrent.atomic.AtomicInteger(0)

    /** Multiview screen entered composition. Pair with [onMultiviewHidden]. */
    fun onMultiviewShown() {
        multiviewScreens.incrementAndGet()
    }

    /** Multiview screen left composition. */
    fun onMultiviewHidden() {
        multiviewScreens.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /** True while at least one local Multiview screen is up. */
    val multiviewActive: Boolean
        get() = multiviewScreens.get() > 0

    /** The one question the sweep asks between chunks. */
    val sweepAllowed: Boolean
        get() = appInForeground && !tuneInProgress && !multiviewActive

    private const val TUNE_MAX_MS = 30_000L
}
