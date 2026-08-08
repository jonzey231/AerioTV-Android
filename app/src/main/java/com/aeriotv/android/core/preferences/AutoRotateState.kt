package com.aeriotv.android.core.preferences

import android.content.pm.ActivityInfo

/**
 * Process-wide cache of App Behaviors > Auto-rotate (Logan 2026-08-07),
 * kept hot by MainActivity's collector so the player's orientation
 * release paths (which run in composables without a prefs handle) can
 * consult it synchronously.
 *
 * iOS twin: `AppOrientationLock.autoRotateEnabled` / `.base`.
 */
object AutoRotateState {
    @Volatile
    var enabled: Boolean = true

    /**
     * The activity orientation to apply when nothing is forcing landscape:
     * follow the sensor when auto-rotate is on, else freeze the activity in
     * its current orientation. TVs never reach this (MainActivity gates the
     * collector on !isTelevisionDevice and TV players don't toggle
     * orientation).
     */
    val restingOrientation: Int
        get() = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
}
