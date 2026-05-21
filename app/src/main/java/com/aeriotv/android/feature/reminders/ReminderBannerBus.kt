package com.aeriotv.android.feature.reminders

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Payload for the in-app reminder banner. */
data class ReminderBannerData(
    val channelId: String,
    val channelName: String,
    val programTitle: String,
)

/**
 * App-scoped bridge between the AlarmManager-fired [ReminderBroadcastReceiver]
 * and the in-app reminder banner overlay. Mirrors iOS ReminderManager's
 * foreground intercept: when a reminder fires while the app is in the
 * foreground, the receiver routes it here (an in-app banner) instead of
 * posting a system notification. When backgrounded, the receiver posts the
 * notification as before.
 *
 * Foreground state is tracked via [ProcessLifecycleOwner] (same pattern as
 * DispatcharrWarmupCoordinator). [bind] is called once from
 * AerioTVApplication.onCreate.
 */
@Singleton
class ReminderBannerBus @Inject constructor() : DefaultLifecycleObserver {

    private val _banner = MutableStateFlow<ReminderBannerData?>(null)
    val banner: StateFlow<ReminderBannerData?> = _banner.asStateFlow()

    @Volatile
    var isForeground: Boolean = false
        private set

    private var bound = false

    /** Idempotent attach to the process lifecycle. Safe to call from onCreate. */
    fun bind() {
        if (bound) return
        bound = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) { isForeground = true }
    override fun onStop(owner: LifecycleOwner) { isForeground = false }

    /** Show (or replace) the in-app banner. Called from the receiver when foreground. */
    fun post(data: ReminderBannerData) { _banner.value = data }

    /** Dismiss the banner (tap-through, swipe, or auto-timeout). */
    fun dismiss() { _banner.value = null }
}
