// TabReselect.kt
//
// Re-tapping the bottom-nav tab you are already on (phone and tablet, Logan
// 2026-09-18, iPhone parity): the tab first pops any pushed page back to its
// own root, and if it is already at its root it scrolls its main scroll
// container to the top.
//
// One event, emitted by the ONE place that knows a press landed on the already
// selected tab (MainScaffold's bottom bar). Each tab screen
// observes it where its own nav stack and scroll state already live, which is
// the only place that can answer "am I at my root?" -- hoisting either of those
// up to the scaffold would mean threading a LazyListState through five screens
// that are otherwise independent.
//
// TV never emits: the D-pad nav has no reselect gesture (selection follows
// focus), so a TV press can never mean "again".

package com.aeriotv.android.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** The reselect channel. Process-wide because the scaffold and the tab screens
 *  are composed in different subtrees and only ever one scaffold exists. */
object TabReselect {
    private val _events = MutableSharedFlow<AppTab>(extraBufferCapacity = 8)

    /** Events for [OnTabReselect]; a tab screen filters for its own tab. */
    val events: SharedFlow<AppTab> = _events.asSharedFlow()

    /** Called from the nav bar when the pressed tab is the selected one. */
    fun emit(tab: AppTab) {
        _events.tryEmit(tab)
    }
}

/**
 * Runs [onReselect] whenever [tab] is re-tapped while it is already selected.
 *
 * Callers keep the "pop first, then scroll to top" order themselves: only the
 * screen knows whether it has something pushed.
 */
@Composable
fun OnTabReselect(tab: AppTab, onReselect: suspend () -> Unit) {
    val handler = rememberUpdatedState(onReselect)
    LaunchedEffect(tab) {
        TabReselect.events.collect { if (it == tab) handler.value.invoke() }
    }
}
