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

    /** The single scroll owner threw an anchor away: the geometry it needed was
     *  never measured (Logan 2026-09-11: a freshly inserted Watchlist shelf
     *  anchored with no measured height and the page silently did not move).
     *  One line per dropped anchor so the next such case is readable. */
    fun anchorDropped(anchor: Any, reason: String) {
        Log.i(TAG, "[ANCHOR] $anchor DROPPED ($reason)")
    }

    /** The single scroll owner resolved an anchor: one line per move, with the
     *  offset it starts from, the absolute offset it is going to and the
     *  geometry it was computed from. Paired with [anchorDropped], every
     *  anchor in the trace now ends in exactly one of the two. */
    fun move(anchor: Any, from: Int, to: Int, detail: String) {
        Log.i(TAG, "[MOVE] $anchor $from -> $to $detail")
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

    /** Live TV guide grid events (gate snapshots, focus retries, refused
     *  vertical steps): one line per event, never per frame.
     *
     *   [GUIDE] gates sidebarOpen=false layout=overlay ...
     *   [GUIDE] refocus after=sidebar-close requested=true gridHasFocus=true
     */
    fun guide(detail: String) {
        Log.i(TAG, "[GUIDE] $detail")
    }

    /** Settings rail decisions, e.g. a rail focus gain ignored because the
     *  window had lost focus to an overlay activity (GMS sign-in). */
    fun settings(detail: String) {
        Log.i(TAG, "[SETTINGS] $detail")
    }

    // MARK: Settings (TV only; every call site is gated on the TV form factor)

    /** The last Settings node reported focused ("rail:<key>" or
     *  "row <page>/<title>"), read by [settingsLanding]. "none" after a blur
     *  that nothing replaced. */
    @Volatile
    private var lastSettingsNode: String = "none"

    @Volatile
    private var underSheet: String = "none"

    private val mainHandler by lazy {
        android.os.Handler(android.os.Looper.getMainLooper())
    }

    /** Rail row focus: records the node, the caller still logs its own line. */
    fun settingsRailFocused(key: String) {
        lastSettingsNode = "rail:$key"
    }

    /** A detail-pane (or sheet) row gained focus. */
    fun settingsRowFocused(page: String, title: String) {
        val node = "row $page/$title"
        if (node == lastSettingsNode) return
        lastSettingsNode = node
        Log.i(TAG, "[SETTINGS] row focus page=$page row=$title")
    }

    /** A detail-pane row lost focus; clears the register only if it was this row. */
    fun settingsRowBlurred(page: String, title: String) {
        if (lastSettingsNode == "row $page/$title") lastSettingsNode = "none"
    }

    /** Sheet / sub-page lifecycle: "sheet open X", "sheet close X", "push R", "pop R". */
    fun settingsLifecycle(event: String, name: String) {
        Log.i(TAG, "[SETTINGS] $event $name")
        // A sheet is its own window: the row under it never loses Compose
        // focus, so no regain event fires on close. Keep the node it held and
        // put it back unless something in the main window moved since.
        if (event == "sheet open") {
            underSheet = lastSettingsNode
        } else if (event == "sheet close") {
            val last = lastSettingsNode
            if (last == "none" || last.startsWith("row sheet:")) lastSettingsNode = underSheet
        }
        if (event == "sheet close" || event == "pop") settingsLanding("$event $name")
    }

    /** 300 ms after [after], log which traced node holds focus. */
    fun settingsLanding(after: String) {
        mainHandler.postDelayed({
            Log.i(TAG, "[SETTINGS] landing after=$after on=$lastSettingsNode")
        }, 300L)
    }

    /** A host focus request, its reason and its result (true, false or threw). */
    fun settingsRequest(
        target: String,
        reason: String,
        requester: androidx.compose.ui.focus.FocusRequester,
    ) {
        val result = try {
            requester.requestFocus(androidx.compose.ui.focus.FocusDirection.Enter).toString()
        } catch (t: Throwable) {
            "threw ${t.javaClass.simpleName}"
        }
        Log.i(TAG, "[SETTINGS] request $target reason=$reason result=$result")
    }

    /** A Settings BackHandler fired. */
    fun settingsBack(handler: String) {
        Log.i(TAG, "[SETTINGS] back handled by=$handler")
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
