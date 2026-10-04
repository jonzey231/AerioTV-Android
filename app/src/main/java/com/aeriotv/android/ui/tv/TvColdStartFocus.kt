package com.aeriotv.android.ui.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Cold launch on TV lands focus on the landing tab's pill (TvTopTabBar's
 * one-shot pull). Until that pull has run, tab content must not take focus
 * on first composition, or the guide focuses its top channel first and the
 * pull then moves it: a visible flash (Streamer 2026-10-03). Armed by
 * MainActivity.onCreate on a fresh launch only (no saved state); cleared by
 * the pull. Every other entry path (return from the player, tab switches)
 * never sees it armed.
 */
object TvColdStartFocus {
    var pending: Boolean by mutableStateOf(false)
}
