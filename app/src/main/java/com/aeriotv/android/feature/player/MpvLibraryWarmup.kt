package com.aeriotv.android.feature.player

import android.content.Context
import android.util.Log
import `is`.xyz.mpv.MPV
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide libmpv warm-up. Ports iOS Aerio/App/MPVPlayerView.swift's
 * `MPVLibraryWarmup` (lines 12-235). The whole point is to pay libmpv's
 * one-time initialization cost (codec/protocol/hwdec registration,
 * profile=fast filter chain, mediacodec JNI bridge setup) on a background
 * thread shortly after cold launch, so the user's first channel tap hits
 * the warm path instead of waiting ~1-2 seconds for a fresh
 * `mpv.create()` + `mpv.init()` cycle to complete in the foreground.
 *
 * On iOS this single change is the headline reason channel-tap startup
 * feels "wicked fast" -- without the warmup, the first tap blocks the
 * tap target while libmpv lazily registers everything for the first time;
 * with it, registration is already done and `loadfile` fires immediately
 * on the user-facing MPV handle.
 *
 * Lifecycle:
 *  - [start] kicks off the warmup on a background dispatcher, after an
 *    800 ms launch grace period (Activity first-paint + DataStore
 *    bootstrap should land inside that window, so the warmup never
 *    competes with the visible cold-launch sequence for the JNI mutex).
 *  - Safe to call from any thread; only the first call does anything.
 *  - [waitUntilComplete] spin-polls with 50 ms sleeps so a caller in
 *    PlayerScreen's AndroidView.factory can synchronize the user-facing
 *    `mpv.create()` to the warmup result. Returns true on success, false
 *    on timeout. After timeout the caller proceeds anyway with the cold
 *    path -- the warmup is an optimization, not a hard requirement.
 *  - Warmup never retries on failure. If `mpv.create` / `mpv.init`
 *    throws on the throwaway handle, the warmup logs and bails;
 *    subsequent real playback hits its own (cold) error path.
 */
object MpvLibraryWarmup {
    private const val TAG = "MpvLibraryWarmup"

    /**
     * Bumped to match iOS warmup launch deadline. SwiftUI cold paint +
     * SwiftData bootstrap typically finish inside ~500-800 ms on iPhone
     * hardware; Android's equivalent (Activity create + Compose first
     * frame + DataStore bootstrap on Dispatchers.IO) is similar. Holding
     * the warmup behind this delay keeps the JNI thread off the GPU
     * driver mutex during the launch sequence's visible work.
     */
    private const val LAUNCH_DELAY_MILLIS = 800L

    private val hasStarted = AtomicBoolean(false)
    @Volatile private var completed: Boolean = false

    /**
     * The warmed libmpv handle, kept alive for the whole process. This is the
     * crux of the don't-destroy approach: calling [MPV.destroy] on a throwaway
     * handle (the Phase 78/82 attempt) runs `nativeDestroy()`, which on
     * mpv-android-lib 0.1.12 releases JNI globals (JavaVM cache / class refs)
     * the NEXT `nativeCreate()` needs -- so every subsequent user-facing MPV
     * instance came up dead (chrome loaded, stream never started). By NEVER
     * destroying this handle we keep both the JNI globals and libmpv's
     * one-time codec/protocol/hwdec registrations resident, so the first real
     * channel tap's create()+init() hits the warm path. The handle is idle
     * (no loadfile, no render context, no surface) so it costs ~nothing;
     * libmpv supports many concurrent contexts (multiview runs up to 9).
     *
     * Deliberately retained for the process lifetime -- there is no destroy().
     */
    @Volatile private var warmHandle: MPV? = null

    /** True once the background warm-up has finished (success or failure). */
    val isComplete: Boolean get() = completed

    /**
     * Trigger libmpv process-wide init on a background coroutine. Safe to
     * call from any thread, any number of times -- only the first call
     * does anything. Mirrors iOS [MPVLibraryWarmup.warmUp].
     *
     * The [appContext] must be the Application context: the warmed MPV
     * handle is retained for the whole process (see [warmHandle]), so it must
     * not hold an Activity reference.
     */
    fun start(appContext: Context) {
        if (!hasStarted.compareAndSet(false, true)) return
        // Application-scoped because the warmup is a process-wide thing
        // that should outlive any single Activity. SupervisorJob isn't
        // strictly needed (we don't have children) but it makes the
        // semantics explicit.
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            // Defer 800 ms behind app launch so first-paint + cold DataStore
            // reads get the JNI mutex first.
            delay(LAUNCH_DELAY_MILLIS)
            runWarmup(appContext.applicationContext)
        }
    }

    /**
     * Block up to [timeoutMillis] waiting for the warmup to complete.
     * Returns true if the warmup finished within the timeout, false if
     * we ran out of patience (caller should fall through to the cold
     * path and accept the slower first-frame). Spin-polls with 50 ms
     * sleeps -- the warmup typically completes inside 1-2 s on real
     * hardware so the spin is brief.
     *
     * If [start] was never called this kicks it off first so the wait
     * isn't pointless. Idempotent.
     */
    suspend fun waitUntilComplete(
        appContext: Context,
        timeoutMillis: Long = 5_000L,
    ): Boolean {
        if (completed) return true
        start(appContext)
        val result = withTimeoutOrNull(timeoutMillis) {
            while (!completed) {
                delay(50L)
            }
            true
        }
        return result == true
    }

    private fun runWarmup(appContext: Context) {
        val total = measureTimeMillis {
            val mpv = MPV()
            val ok = runCatching {
                // Match the minimum option set the user-facing
                // MPVPlayerView.initOptions() uses, so the same lazy
                // registrations fire here:
                //   - vo=libmpv (forces the libmpv render path setup)
                //   - profile=fast (loads the fast-profile filter chain)
                //   - hwdec=mediacodec-copy (registers the MediaCodec JNI
                //     bridge + Surface plumbing)
                // No per-stream config (URL, headers, cache) since we
                // never call loadfile here -- we're just paying the
                // global init cost. The handle is then retained, not
                // destroyed, so those registrations stay resident.
                mpv.create(appContext)
                mpv.setOptionString("vo", "libmpv")
                mpv.setOptionString("profile", "fast")
                mpv.setOptionString("hwdec", "mediacodec-copy")
                mpv.init()
            }.onFailure { t ->
                Log.w(TAG, "Warm-up create/init failed; first channel tap will hit cold path", t)
            }.isSuccess
            // RETAIN the handle on success (don't destroy -- see [warmHandle]).
            // On failure, also do NOT destroy: nativeDestroy() is the very thing
            // that corrupts the JNI globals, so a half-initialised handle is
            // safer left alone than torn down. We just drop our extra options.
            if (ok) {
                warmHandle = mpv
            } else {
                // Leave the failed handle unreferenced but undestroyed.
                Log.w(TAG, "Warm-up incomplete; not retaining handle")
            }
        }
        completed = true
        Log.i(TAG, "Process-wide libmpv warm-up complete in ${total}ms (retained=${warmHandle != null})")
    }
}
