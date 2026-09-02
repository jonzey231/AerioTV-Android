package com.aeriotv.android.feature.player

import android.os.SystemClock

/**
 * MainActivity raises this just before it sets `preferredDisplayModeId` (a
 * real HDMI mode change: resolution or refresh class). PersistentExoWindow's
 * display listener then treats the resulting display-changed event as a
 * surface-recreate trigger even when the size did not change, which it must
 * ignore otherwise (the seamless frame-rate matcher's own switches arrive
 * the same way and must stay blink-free).
 */
object DisplayModeSwitchSignal {
    @Volatile private var pendingUntil = 0L

    fun raise(windowMs: Long = 6000L) {
        pendingUntil = SystemClock.elapsedRealtime() + windowMs
    }

    val pending: Boolean
        get() = SystemClock.elapsedRealtime() < pendingUntil
}
