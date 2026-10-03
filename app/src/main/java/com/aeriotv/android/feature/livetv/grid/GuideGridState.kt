package com.aeriotv.android.feature.livetv.grid

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aeriotv.android.core.data.EPGProgramme
import kotlin.math.abs

/**
 * The single source of truth for guide focus and the timeline viewport
 * (docs/guide-semantics.md section 6). The grid composable is the only focus
 * owner; every D-pad press is resolved here, synchronously, and the renderer
 * draws whatever this says. Nothing in here touches the Compose focus engine.
 *
 * Rules implemented:
 * - anchor column = viewport left + [leadMs];
 * - UP/DOWN land on the cell in the next row that CONTAINS the anchor; the
 *   timeline never moves on a vertical move;
 * - LEFT/RIGHT pan the timeline by [panStepMs] and retarget, on the same row,
 *   to the cell containing the anchor; the focus ring rides the viewport;
 * - holes are cells (synthesised by [GuideGridRows]), so focus always lands;
 * - the focused CHANNEL survives a row-list swap (group change, sort, EPG
 *   refresh): focus follows the channel id, not the row index.
 */
@Stable
class GuideGridState(
    initialViewportStartMs: Long,
    val leadMs: Long = 15 * 60_000L,
    val panStepMs: Long = 30 * 60_000L,
    val slopMs: Long = 30 * 60_000L,
) {
    var rows: GuideGridRows by mutableStateOf(GuideGridRows.EMPTY)
        private set

    var viewportStartMs: Long by mutableLongStateOf(initialViewportStartMs)
        private set

    /**
     * What the canvas draws: eases toward [viewportStartMs] after a D-pad
     * pan or jump (tvOS animates its timeline 0.3 s ease-out), tracks it
     * directly during a touch drag. Owned by GuideGrid's animation driver.
     */
    var drawViewportStartMs: Long by mutableLongStateOf(initialViewportStartMs)

    /** False when the last viewport change came from a touch drag (no easing). */
    var viewportChangeAnimated: Boolean = true
        private set

    /** Set by layout from the strip width and the hour width. */
    var viewportDurationMs: Long by mutableLongStateOf(3 * 3_600_000L)

    var focusRow: Int by mutableIntStateOf(-1)
        private set

    /** Start of the focused cell; with [focusRow] it identifies the cell. */
    var focusCellStartMs: Long by mutableLongStateOf(Long.MIN_VALUE)
        private set

    /** The channel focus follows across row-list swaps. */
    var focusChannelId: String? = null
        private set

    /**
     * What the renderer must actually walk from: [drawViewportStartMs] held
     * inside the current rows window. The clamp and installRows keep the
     * stored value honest, but the draw pass can run in the same frame a new
     * rows window arrives, so the walk reads through this and never starts
     * outside the window (empty program lanes, Logan 2026-09-19).
     */
    val drawStartMs: Long
        get() = if (rows.isEmpty) drawViewportStartMs
        else drawViewportStartMs.coerceIn(minViewportStart(), maxViewportStart())

    val anchorMs: Long get() = viewportStartMs + leadMs
    val viewportEndMs: Long get() = viewportStartMs + viewportDurationMs
    val hasFocus: Boolean get() = focusRow >= 0

    fun focusedCell(): EPGProgramme? {
        if (focusRow !in 0 until rows.size) return null
        val i = rows.cellIndexAt(focusRow, focusCellStartMs)
        return if (i >= 0) rows.cells(focusRow)[i] else null
    }

    /**
     * Put the state back to what a freshly constructed one holds (focus
     * unset, viewport at [viewportStartMs]) while KEEPING [rows]. Used when
     * the guide re-enters composition (returning from the player) with a
     * retained state: the next [installRows] then lands exactly as a first
     * install would, but the grid's first frame already has its rows instead
     * of composing empty and then installing the whole window (perf
     * 2026-10-03: 800 to 1360 ms frames on return).
     */
    fun resetForEntry(viewportStartMs: Long) {
        this.viewportStartMs = viewportStartMs
        drawViewportStartMs = viewportStartMs
        viewportChangeAnimated = true
        focusRow = -1
        focusCellStartMs = Long.MIN_VALUE
        focusChannelId = null
    }

    /**
     * Install a new row list. The previously focused channel keeps focus if
     * it is still listed; otherwise focus clamps to the nearest row index.
     * A first install lands on row 0 at the anchor.
     */
    fun installRows(newRows: GuideGridRows) {
        val previousId = focusChannelId
        val windowMoved = newRows.windowStartMs != rows.windowStartMs || newRows.windowEndMs != rows.windowEndMs
        rows = newRows
        if (newRows.isEmpty) { focusRow = -1; focusCellStartMs = Long.MIN_VALUE; return }
        val byId = previousId?.let { newRows.indexOfChannel(it) } ?: -1
        val row = when {
            byId >= 0 -> byId
            focusRow < 0 -> 0
            else -> focusRow.coerceIn(0, newRows.size - 1)
        }
        clampViewport()
        // EMPTY LANES AFTER RETURNING TO THE GUIDE (Logan 2026-09-19). The
        // draw viewport is normally eased toward viewportStartMs by
        // GuideGrid's animation driver, so on a rows swap that moved the
        // window (a new "now" quantum, a jump, a wider Guide Days extent) the
        // draw start could still sit OUTSIDE the new rows window while the
        // logical viewport had already been clamped into it. The cell walk
        // starts from the draw start, so every row painted nothing: the guide
        // came back with channels and no programs. A window move snaps the
        // draw to the clamped viewport instead of easing from a stale value.
        if (windowMoved) drawViewportStartMs = viewportStartMs
        land(row)
    }

    /** UP/DOWN by [delta] rows. Returns false at the edge so the host can move focus out of the grid. */
    fun moveRows(delta: Int): Boolean {
        if (rows.isEmpty) return false
        val target = (focusRow.coerceAtLeast(0) + delta)
        if (target < 0 || target >= rows.size) {
            // Partial page at the edge still moves; a single step past the edge escapes.
            val clamped = target.coerceIn(0, rows.size - 1)
            if (clamped == focusRow) return false
            land(clamped)
            return true
        }
        land(target)
        return true
    }

    /** LEFT/RIGHT: pan by one step, then retarget on the same row. Returns false when the window edge stops the pan. */
    fun pan(direction: Int): Boolean {
        if (rows.isEmpty) return false
        val next = (viewportStartMs + direction * panStepMs).coerceIn(minViewportStart(), maxViewportStart())
        if (next == viewportStartMs) return false
        viewportChangeAnimated = true
        viewportStartMs = next
        land(focusRow.coerceAtLeast(0))
        return true
    }

    /**
     * A short Left, tvOS rule (Logan 2026-09-10, "the entire EPG shifts"):
     * step the ring onto the previous cell when that cell's start is
     * already on screen, with no pan; landing on the LIVE programme
     * re-anchors the timeline to now instead (nothing moves when it is
     * already there); only when the previous cell starts off screen, or
     * there is none, does the timeline pan a step and the ring ride it.
     */
    fun stepLeft(nowMs: Long): Boolean {
        if (rows.isEmpty) return false
        val row = focusRow.coerceAtLeast(0)
        val cells = rows.cells(row)
        val idx = cells.indexOfFirst { it.startMillis == focusCellStartMs }
        val prev = if (idx > 0) cells[idx - 1] else null
        if (prev != null) {
            if (prev.startMillis <= nowMs && nowMs < prev.endMillis) {
                focusRow = row
                focusChannelId = rows.channel(row).id
                focusCellStartMs = prev.startMillis
                // Exact like tvOS (3 min), not the half-hour Back slop.
                if (abs(viewportStartMs - (nowMs - leadMs)) > 3 * 60_000L) {
                    viewportChangeAnimated = true
                    viewportStartMs = (nowMs - leadMs).coerceIn(minViewportStart(), maxViewportStart())
                }
                return true
            }
            // The edge sits a few minutes past the half hour (now minus the
            // lead), so a cell starting ON the half hour counts as on screen
            // when its start is within the lead of the edge.
            if (prev.startMillis >= viewportStartMs - leadMs) {
                focusRow = row
                focusChannelId = rows.channel(row).id
                focusCellStartMs = prev.startMillis
                return true
            }
        }
        return pan(-1)
    }

    /**
     * Gate for a short Left remapped to a non-program action (Logan
     * 2026-09-14, matching Apple TV): the action runs only when the focused
     * cell is the one airing now on its row, the same now-cell test
     * [stepLeft] uses; on any other cell Left keeps moving focus. The locked
     * rule itself is untouched.
     */
    fun focusedCellIsAiringNow(nowMs: Long): Boolean {
        val cell = focusedCell() ?: return false
        return cell.startMillis <= nowMs && nowMs < cell.endMillis
    }

    /**
     * Second half of the remapped-Left gate (Logan 2026-09-14): true while
     * the timeline sits at its live position, meaning it has not been panned
     * earlier than now by more than the Back to Now slop ([slopMs], the same
     * tolerance [isAwayFromNow] and [back] use). A hold that browses earlier
     * programs can leave focus on the live cell; there a remapped Left must
     * keep stepping back through history instead of firing. Panning later
     * than now does not count. Read-only; the locked rule is untouched.
     */
    fun isTimelineAtLive(nowMs: Long): Boolean = anchorMs >= nowMs - slopMs

    /** True when the focused cell is the last one in its row (no later program to step onto). */
    fun atLastCell(): Boolean {
        if (focusRow !in 0 until rows.size) return false
        val idx = rows.cellIndexAt(focusRow, focusCellStartMs)
        return idx >= 0 && idx + 1 >= rows.cells(focusRow).size
    }

    /**
     * A short Right, tvOS rule: the timeline pans a step and the ring moves
     * onto the NEXT cell (the tvOS focus engine steps while the grid pans),
     * so a long live programme is left in one press instead of the ring
     * sitting still while the grid slides under it.
     */
    fun stepRight(): Boolean {
        if (rows.isEmpty) return false
        val row = focusRow.coerceAtLeast(0)
        val cells = rows.cells(row)
        val idx = cells.indexOfFirst { it.startMillis == focusCellStartMs }
        val next = if (idx >= 0 && idx + 1 < cells.size) cells[idx + 1] else null
        val nextStart = (viewportStartMs + panStepMs).coerceIn(minViewportStart(), maxViewportStart())
        if (nextStart != viewportStartMs) {
            viewportChangeAnimated = true
            viewportStartMs = nextStart
        }
        if (next != null && next.startMillis < viewportStartMs + viewportDurationMs) {
            focusRow = row
            focusChannelId = rows.channel(row).id
            focusCellStartMs = next.startMillis
            return true
        }
        if (nextStart == viewportStartMs && next == null) return false
        land(row)
        return true
    }

    /**
     * Layout reports a new strip width (first layout, or the TV sidebar's
     * Shift guide layout narrowing / widening the grid). Re-clamps the
     * viewport against the window end without easing. Focus is left alone:
     * the pane owns it while the grid is narrow, and the width it returns to
     * on close shows the same cell again.
     */
    fun resizeViewport(durationMs: Long) {
        if (durationMs == viewportDurationMs) return
        viewportDurationMs = durationMs
        if (rows.isEmpty) return
        val clamped = viewportStartMs.coerceIn(minViewportStart(), maxViewportStart())
        if (clamped != viewportStartMs) {
            viewportChangeAnimated = false
            viewportStartMs = clamped
        }
    }

    /** Timeline jump by [ms] (remote-mapped page). */
    fun panBy(ms: Long): Boolean {
        if (rows.isEmpty) return false
        val next = (viewportStartMs + ms).coerceIn(minViewportStart(), maxViewportStart())
        if (next == viewportStartMs) return false
        viewportChangeAnimated = true
        viewportStartMs = next
        land(focusRow.coerceAtLeast(0))
        return true
    }

    /** Move the viewport without retargeting focus (focus retargets on the next D-pad press).
     *  [animated] = false for a touch drag, which must track the finger. */
    fun scrollViewportTo(startMs: Long, animated: Boolean = true) {
        viewportChangeAnimated = animated
        viewportStartMs = startMs.coerceIn(minViewportStart(), maxViewportStart())
    }

    /** Put NOW at the lead offset inside the left edge; keep the row. */
    fun anchorToNow(nowMs: Long) {
        viewportChangeAnimated = true
        viewportStartMs = (nowMs - leadMs).coerceIn(minViewportStart(), maxViewportStart())
        if (!rows.isEmpty) land(focusRow.coerceAtLeast(0))
    }

    fun isAwayFromNow(nowMs: Long): Boolean = abs(anchorMs - nowMs) > slopMs

    /** Focus a row directly (touch tap, programmatic jump). */
    fun focusRowAt(row: Int, cellStartMs: Long? = null) {
        if (row !in 0 until rows.size) return
        if (cellStartMs == null) { land(row); return }
        focusRow = row
        focusCellStartMs = cellStartMs
        focusChannelId = rows.channel(row).id
    }

    fun focusChannel(channelId: String): Boolean {
        val row = rows.indexOfChannel(channelId)
        if (row < 0) return false
        land(row)
        return true
    }

    /** The Back ladder: away from now -> restore now and top; not at top -> top; else nothing. */
    fun back(nowMs: Long): BackStep {
        if (rows.isEmpty) return BackStep.NONE
        if (isAwayFromNow(nowMs)) {
            anchorToNow(nowMs)
            land(0)
            return BackStep.RESTORED_NOW_AND_TOP
        }
        if (focusRow > 0) { land(0); return BackStep.TOP }
        return BackStep.NONE
    }

    private fun land(row: Int) {
        focusRow = row
        focusChannelId = rows.channel(row).id
        focusCellStartMs = rows.nearestCell(row, anchorMs)?.startMillis ?: Long.MIN_VALUE
    }

    private fun minViewportStart(): Long = rows.windowStartMs
    private fun maxViewportStart(): Long = (rows.windowEndMs - viewportDurationMs).coerceAtLeast(rows.windowStartMs)
    private fun clampViewport() {
        if (rows.isEmpty) return
        val lo = minViewportStart()
        val hi = maxViewportStart()
        viewportStartMs = viewportStartMs.coerceIn(lo, hi)
        // The clamp owns BOTH viewports: a draw start left outside the rows
        // window paints empty lanes (see installRows).
        drawViewportStartMs = drawViewportStartMs.coerceIn(lo, hi)
    }

    enum class BackStep { RESTORED_NOW_AND_TOP, TOP, NONE }
}
