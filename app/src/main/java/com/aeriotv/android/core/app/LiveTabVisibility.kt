package com.aeriotv.android.core.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the Live TV tab is the selected tab (MainScaffold drives it).
 * Guide catalog publishes and category paints that land while another tab
 * is active wait for the tab to show again: Streamer 2026-10-27 14:25:53, a
 * full guide rows-install on main while the user was moving through
 * Settings. Defaults to true so nothing is deferred before the scaffold has
 * reported a tab.
 */
object LiveTabVisibility {
    private val _visible = MutableStateFlow(true)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun set(visible: Boolean) {
        if (_visible.value != visible) _visible.value = visible
    }
}
