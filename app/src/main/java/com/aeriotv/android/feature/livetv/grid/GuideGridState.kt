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

    val anchorMs: Long get() = viewportStartMs + leadMs
    val viewportEndMs: Long get() = viewportStartMs + viewportDurationMs
    val hasFocus: Boolean get() = focusRow >= 0

    fun focusedCell(): EPGProgramme? {
        if (focusRow !in 0 until rows.size) return null
        val i = rows.cellIndexAt(focusRow, focusCellStartMs)
        return if (i >= 0) rows.cells(focusRow)[i] else null
    }

    /**
     * Install a new row list. The previously focused channel keeps focus if
     * it is still listed; otherwise focus clamps to the nearest row index.
     * A first install lands on row 0 at the anchor.
     */
    fun installRows(newRows: GuideGridRows) {
        val previousId = focusChannelId
        rows = newRows
        if (newRows.isEmpty) { focusRow = -1; focusCellStartMs = Long.MIN_VALUE; return }
        val byId = previousId?.let { newRows.indexOfChannel(it) } ?: -1
        val row = when {
            byId >= 0 -> byId
            focusRow < 0 -> 0
            else -> focusRow.coerceIn(0, newRows.size - 1)
        }
        clampViewport()
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
        viewportStartMs = next
        land(focusRow.coerceAtLeast(0))
        return true
    }

    /** Timeline jump by [ms] (remote-mapped page). */
    fun panBy(ms: Long): Boolean {
        if (rows.isEmpty) return false
        val next = (viewportStartMs + ms).coerceIn(minViewportStart(), maxViewportStart())
        if (next == viewportStartMs) return false
        viewportStartMs = next
        land(focusRow.coerceAtLeast(0))
        return true
    }

    /** Touch drag: move the viewport without retargeting focus (focus retargets on the next D-pad press). */
    fun scrollViewportTo(startMs: Long) {
        viewportStartMs = startMs.coerceIn(minViewportStart(), maxViewportStart())
    }

    /** Put NOW at the lead offset inside the left edge; keep the row. */
    fun anchorToNow(nowMs: Long) {
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
        viewportStartMs = viewportStartMs.coerceIn(minViewportStart(), maxViewportStart())
    }

    enum class BackStep { RESTORED_NOW_AND_TOP, TOP, NONE }
}
