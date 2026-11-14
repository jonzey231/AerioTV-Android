package com.aeriotv.android.feature.search

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.feature.livetv.LiveProgramHit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Programs row in Search does the same thing at every entry point (the TV
 * Search tab and the pushed Search route): a live program plays its channel,
 * a later one opens Program Info.
 */
class ProgramHitActionTest {
    private val ch = M3UChannel(id = "id-1", name = "Alpha", url = "http://x/alpha", channelNumber = "1", groupTitle = "G")
    private val now = 1_800_000_000_000L
    private fun hit(start: Long, end: Long) = LiveProgramHit(
        ch,
        EPGProgramme(channelId = "alpha", title = "Show", description = "", startMillis = start, endMillis = end, category = ""),
    )

    @Test fun aLiveProgramPlaysItsChannel() {
        assertEquals(ProgramHitAction.PlayChannel, programHitAction(hit(now - 60_000, now + 60_000), now, canPlay = true))
    }

    @Test fun aLaterProgramOpensProgramInfo() {
        assertEquals(ProgramHitAction.ProgramInfo, programHitAction(hit(now + 3_600_000, now + 7_200_000), now, canPlay = true))
        // An earlier one too; the end is exclusive.
        assertEquals(ProgramHitAction.ProgramInfo, programHitAction(hit(now - 7_200_000, now), now, canPlay = true))
    }

    @Test fun withNoWayToPlayEveryRowOpensProgramInfo() {
        assertEquals(ProgramHitAction.ProgramInfo, programHitAction(hit(now - 60_000, now + 60_000), now, canPlay = false))
    }
}
