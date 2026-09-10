package com.aeriotv.android.ui.tv

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TV poster grids: Compose's own focus search cannot see a row that is not
 * composed yet, so Down at the last visible row either did nothing or
 * hopped sideways (Logan 2026-09-02, Streamer). Scroll the target row in
 * and focus it directly; consume a Down with no row below so the sideways
 * hop never happens. Up at the top row falls through to whatever sits
 * above the grid (pills, header).
 *
 * Shared by the On Demand grids and the media-center TV page.
 */
fun vodGridDpadFallback(
    event: KeyEvent,
    index: Int,
    count: Int,
    gridState: LazyGridState,
    focusManager: FocusManager,
    scope: CoroutineScope,
    requesterAt: (Int) -> FocusRequester,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val dir = when (event.key) {
        Key.DirectionDown -> 1
        Key.DirectionUp -> -1
        else -> return false
    }
    val cols = (gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: -1) + 1
    if (cols <= 0) return false
    val lastRow = (count - 1) / cols
    val row = index / cols
    val targetRow = row + dir
    if (targetRow < 0) return false
    if (targetRow > lastRow) return true
    val target = minOf(index + dir * cols, count - 1)
    if (focusManager.moveFocus(if (dir > 0) FocusDirection.Down else FocusDirection.Up)) return true
    scope.launch {
        runCatching { gridState.scrollToItem(target) }
        repeat(6) {
            withFrameNanos { }
            if (runCatching { requesterAt(target).requestFocus() }.isSuccess) return@launch
        }
    }
    return true
}

/**
 * BACK-from-detail D-pad focus restoration for TV grids and rails (every
 * member no-ops off TV so phone behavior is untouched).
 *
 * Detail screens are nav routes pushed ON TOP of MAIN, so the whole tab is
 * disposed while they are up; an in-composition focusRestorer cannot
 * survive that. Instead the clicked item's key is written to a
 * rememberSaveable slot (which survives via the back stack's saved state),
 * the matching card re-attaches [requester] when the tab recomposes on
 * return, and [restoreIfPending] pulls focus onto it.
 */
class VodReturnFocusState(
    private val isTv: Boolean,
    private val pendingKeyState: MutableState<String?>,
) {
    val requester = FocusRequester()
    private val pendingKey: String? get() = pendingKeyState.value

    /** The armed key, for pages that restore scroll + focus themselves (TvMediaPage). */
    val pendingKeyOrNull: String? get() = pendingKey

    /** Forget the armed key once a page has restored focus its own way. */
    fun clear() { pendingKeyState.value = null }

    /** Record the item being opened so focus can return to it after BACK.
     *  Call right before the navigation callback. */
    fun arm(key: String) {
        if (isTv) pendingKeyState.value = key
    }

    /** The [FocusRequester] for [key]'s item, or null for every other item. */
    fun requesterFor(key: String): FocusRequester? =
        if (isTv && key == pendingKey) requester else null

    /** One-shot on the return composition: focus the armed item, retrying
     *  until it is attached, then re-assert once in case the initial-focus
     *  fallback lands after the first success. Gives up quietly if the item
     *  is gone. */
    suspend fun restoreIfPending() {
        if (!isTv || pendingKey == null) return
        repeat(20) {
            if (runCatching { requester.requestFocus() }.isSuccess) {
                delay(48L)
                runCatching { requester.requestFocus() }
                pendingKeyState.value = null
                return
            }
            delay(16L)
        }
        pendingKeyState.value = null
    }
}

@Composable
fun rememberVodReturnFocus(
    isTv: Boolean,
    /** false for pages that restore scroll + focus themselves off [VodReturnFocusState.pendingKeyOrNull]. */
    autoRestore: Boolean = true,
): VodReturnFocusState {
    // The key lives in rememberSaveable so it survives the tab's disposal
    // while a detail route sits on top of MAIN. arm() writes it
    // synchronously in the click handler, before navigation saves state.
    val pendingKey = rememberSaveable { mutableStateOf<String?>(null) }
    val state = remember { VodReturnFocusState(isTv, pendingKey) }
    if (autoRestore) LaunchedEffect(Unit) { state.restoreIfPending() }
    return state
}
