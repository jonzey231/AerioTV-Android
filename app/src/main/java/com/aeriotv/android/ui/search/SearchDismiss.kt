package com.aeriotv.android.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.aeriotv.android.feature.main.LocalTabIsActive

/**
 * Process-wide "close every open search field" signal.
 *
 * A search field left open on a tab used to re-summon the keyboard when the
 * user fullscreened media and then minimized to the mini player or PiP: the
 * MAIN route is disposed while the player route is on top, so popping back
 * re-mounted the tab with its saved `searchActive = true` and the field's
 * auto-focus effect ran again from scratch.
 *
 * Entering a fullscreen player raises [serial]; every search surface hosts
 * [CloseSearchOnLeave], which clears focus, hides the IME and closes its own
 * search state when the serial moves (or when its tab stops being active).
 *
 * A plain module-level signal rather than an injected singleton: this is
 * transient UI state with exactly one writer path, the same idiom as
 * DisplayModeSwitchSignal and PhoneMiniChrome.
 */
object SearchDismissSignal {
    var serial by mutableIntStateOf(0)
        private set

    /** Called when a fullscreen player takes over the screen. */
    fun dismissAll() {
        serial++
    }
}

/**
 * Cancel and close this surface's search whenever the user leaves the tab or
 * enters the fullscreen player.
 *
 * [onClose] must leave the surface's search state closed (and its query
 * cleared where the query is local); focus clearing and hiding the IME are
 * handled here, so the keyboard cannot be re-summoned by the minimize or PiP
 * transition that follows.
 *
 * [keepOnPlayer] (Live TV list and guide, Logan 2026-11-05): entering the
 * player only hides the keyboard and clears focus, so leaving a channel opened
 * from a search result returns to the same field, query, and results. Its
 * field has no auto-focus effect, so the keyboard does not come back.
 *
 * Safe to host unconditionally: it does nothing while [searchActive] is false.
 */
@Composable
fun CloseSearchOnLeave(
    searchActive: Boolean,
    keepOnPlayer: Boolean = false,
    onClose: () -> Unit,
) {
    val tabActive = LocalTabIsActive.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val active by rememberUpdatedState(searchActive)
    val close by rememberUpdatedState(onClose)

    val dismiss: () -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        close()
    }

    // Leaving the tab.
    LaunchedEffect(tabActive) {
        if (!tabActive && active) dismiss()
    }

    // A fullscreen player took over. Ignore whatever a previous screen left
    // behind: only a dismissal raised while this surface is composed counts.
    val baseSerial = androidx.compose.runtime.remember { SearchDismissSignal.serial }
    LaunchedEffect(SearchDismissSignal.serial) {
        if (SearchDismissSignal.serial != baseSerial && active) {
            if (keepOnPlayer) {
                keyboard?.hide()
                focusManager.clearFocus(force = true)
            } else {
                dismiss()
            }
        }
    }

    // The surface itself is going away (tab disposed, route popped): make sure
    // the IME goes with it rather than surviving into whatever composes next.
    DisposableEffect(Unit) {
        onDispose {
            if (active) {
                keyboard?.hide()
                // keepOnPlayer: a player route covering this surface keeps
                // the search (the caller restores it with rememberSaveable).
                val coveredByPlayer = keepOnPlayer && SearchDismissSignal.serial != baseSerial
                if (!coveredByPlayer) close()
            }
        }
    }
}
