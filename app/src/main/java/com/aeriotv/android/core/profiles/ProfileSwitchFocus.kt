package com.aeriotv.android.core.profiles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where focus lands once a profile switch's scrim drops (TV, 2026-10-17):
 * the guide's grid after a launch-picker switch, or the Settings row that
 * started the switch. Never a tab pill. A consume-once target plus a tick
 * that ProfileManager bumps when the scrim is released.
 */
object ProfileSwitchFocus {
    sealed interface Target {
        data object Guide : Target
        /** The Settings rail's selected row (Manage Profiles -> Admin lands on Playlists). */
        data object Rail : Target
        data class Row(val profileId: String) : Target
    }

    @Volatile private var target: Target? = null
    private val _released = MutableStateFlow(0L)
    val released: StateFlow<Long> = _released

    /** Bumped when a switch to a DIFFERENT profile completes: the app
     *  returns to the Live TV root (tab, Settings pages, detail routes). */
    private val _switches = MutableStateFlow(0L)
    val switches: StateFlow<Long> = _switches
    fun onSwitchCompleted() { _switches.value++ }

    fun arm(t: Target) { target = t }

    fun onScrimReleased() { _released.value++ }

    /** True exactly once when [t] is the armed target. */
    fun consume(t: Target): Boolean {
        if (target != t) return false
        target = null
        return true
    }
}
