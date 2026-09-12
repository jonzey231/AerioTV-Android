package com.aeriotv.android.core.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aeriotv.android.core.data.repository.EpgSweepGate
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared "the app has settled" signal for the quiet background refreshes
 * (EPG sweep, VOD library sweep, DVR recordings list). Logan 2026-09-12:
 * cached data always serves instantly at launch, and only once the app has
 * settled does a very low resource background sweep refresh it.
 *
 * Settled means all three of:
 *  - the guide has had its first composition (or the launch plainly never
 *    opens the guide, capped by [GUIDE_WAIT_MAX_MS] so a launch straight into
 *    Movies or Settings still settles),
 *  - roughly [SETTLE_DELAY_MS] has passed since launch or since the app came
 *    back to the foreground,
 *  - the process is in the foreground.
 *
 * Separate from settling, [awaitSweepWindow] holds a running sweep still
 * while a tune has not reached steady playback yet and while the app is
 * backgrounded, so a sweep never competes with channel start time (a top
 * priority) or burns battery behind the user's back.
 */
@Singleton
class AppSettleGate @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _settled = MutableStateFlow(false)

    /** True once the launch (or the foreground return) has quieted down. */
    val settled: StateFlow<Boolean> = _settled.asStateFlow()

    private val guideRendered = MutableStateFlow(false)
    private var settleJob: Job? = null

    init {
        // addObserver is main-thread only, and a Hilt singleton can be built on
        // any thread (the first injection point wins).
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    restartSettleCycle()
                }

                override fun onStop(owner: LifecycleOwner) {
                    // A backgrounded app is not settled: the next foreground
                    // return starts its own quiet period.
                    settleJob?.cancel()
                    _settled.value = false
                }
            })
        }
        restartSettleCycle()
    }

    /** Called from the guide's first composition. Sticky for the process. */
    fun noteGuideRendered() {
        if (!guideRendered.value) {
            guideRendered.value = true
            Log.i(TAG, "guide rendered")
        }
    }

    /** Suspends until [settled] is true. */
    suspend fun awaitSettled() {
        settled.first { it }
    }

    /**
     * True while a background sweep may do a unit of work: foreground, and no
     * tune still waiting on its first frame. Delegates to [EpgSweepGate],
     * which the player screen and the scaffold already drive, so every
     * background sweep pauses on exactly the same signal.
     */
    fun sweepWindowOpen(): Boolean = EpgSweepGate.sweepAllowed

    /** Suspends until [sweepWindowOpen] is true, polling cheaply. */
    suspend fun awaitSweepWindow() {
        while (!sweepWindowOpen()) delay(WINDOW_POLL_MS)
    }

    private fun restartSettleCycle() {
        settleJob?.cancel()
        settleJob = scope.launch {
            _settled.value = false
            val startedAt = SystemClock.elapsedRealtime()
            withTimeoutOrNull(GUIDE_WAIT_MAX_MS) { guideRendered.first { it } }
            val remaining = SETTLE_DELAY_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remaining > 0) delay(remaining)
            _settled.value = true
            Log.i(TAG, "settled (+${SystemClock.elapsedRealtime() - startedAt} ms)")
        }
    }

    private companion object {
        const val TAG = "AppSettleGate"
        const val SETTLE_DELAY_MS = 20_000L
        const val GUIDE_WAIT_MAX_MS = 20_000L
        const val WINDOW_POLL_MS = 500L
    }
}

/** Hilt reach-in so a composable can post the guide-rendered signal. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppSettleGateEntryPoint {
    fun appSettleGate(): AppSettleGate
}

/** The process-wide [AppSettleGate], for composables outside a ViewModel. */
@Composable
fun rememberAppSettleGate(): AppSettleGate {
    val context: Context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AppSettleGateEntryPoint::class.java,
        ).appSettleGate()
    }
}
