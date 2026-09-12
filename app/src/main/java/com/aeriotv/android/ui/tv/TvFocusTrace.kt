package com.aeriotv.android.ui.tv

import android.util.Log
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key

/**
 * Release-safe focus tracer for the Android TV pages: the analog of the Apple
 * app's TVFocusTracer (feedback_tvos_focus_tracer). It is PERMANENT and it
 * ships: any focus or D-pad complaint on a TV media page is read off this
 * trace first, so it must be there in the build the user is running.
 *
 *   adb logcat -s AerioFocus:I
 *
 * Four line shapes, all one line per EVENT (never per frame):
 *
 *   [FOCUS] grid r0c1 Britney -> pill:All
 *   [ANCHOR] GridRow(row=0, seatAtTop=false) from grid-cell
 *   [KEY] Up consumed=true by=grid-top-row
 *   [RESTORE] returnOffset=4/0 row=0 col=1
 *
 * "what" vocabulary: tab:<name> | header:<button> | pill:<label> |
 * grid r<row>c<col> <title> | rail:<letter> | hero:<button> |
 * detail:<button> | none.
 *
 * Cost: the call sites build their string ONLY inside a focus-gained branch
 * (a change event, a handful per second at most); [focus] then drops the
 * duplicate that Compose's paired lose/gain callbacks produce. Nothing here
 * allocates on a frame callback, in a measure pass, or in a composition.
 */
object TvFocusTrace {
    const val TAG = "AerioFocus"

    /** The last thing reported focused, so every line reads "<from> -> <to>". */
    @Volatile
    private var current: String = "none"

    /** Report the newly focused element. [what] uses the vocabulary above. */
    fun focus(what: String) {
        val from = current
        if (from == what) return
        current = what
        Log.i(TAG, "[FOCUS] $from -> $what")
    }

    /** Focus left [what] and nothing else took it (Compose cleared to the root). */
    fun blurred(what: String) {
        if (current != what) return
        current = "none"
        Log.i(TAG, "[FOCUS] $what -> none")
    }

    /** A page anchor going to the single scroll owner (TvMediaPage). */
    fun anchor(anchor: Any, source: String) {
        Log.i(TAG, "[ANCHOR] $anchor from $source")
    }

    /** A D-pad / Back decision at a page-level handler. */
    fun key(key: String, consumed: Boolean, by: String) {
        Log.i(TAG, "[KEY] $key consumed=$consumed by=$by")
    }

    /** The return-from-detail restore: what the page put back, where it aimed,
     *  and WHICH element opened the detail ("hero:Details", "shelf:2",
     *  "grid r0 c1"). The source is the authority on where focus goes back
     *  (Logan 2026-09-11: hero Details returned to the grid or to the tab bar). */
    fun restore(returnOffset: String, row: Int, col: Int, source: String) {
        Log.i(TAG, "[RESTORE] returnOffset=$returnOffset row=$row col=$col source=$source")
    }

    /** Up/Down/Left/Right/Back only; null for every other key so the trace
     *  stays readable and no string is built for the rest of the remote. */
    fun nameOf(event: KeyEvent): String? = when (event.key) {
        Key.DirectionUp -> "Up"
        Key.DirectionDown -> "Down"
        Key.DirectionLeft -> "Left"
        Key.DirectionRight -> "Right"
        Key.Back, Key.Escape -> "Back"
        else -> null
    }
}
