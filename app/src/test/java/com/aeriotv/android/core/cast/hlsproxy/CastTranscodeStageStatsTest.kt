package com.aeriotv.android.core.cast.hlsproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class CastTranscodeStageStatsTest {
    private val ms = 1_000_000L

    @Test fun reportsRatesAveragesAndResets() {
        val s = CastTranscodeStageStats()
        repeat(500) { s.noteDecoded() }
        repeat(400) { s.noteRender(frameWait = 4 * ms, draw = 1 * ms, swap = 10 * ms) }
        s.noteRender(frameWait = 30 * ms, draw = 1 * ms, swap = 9 * ms)
        s.noteInputWait(2_000 * ms)
        s.noteCapWait(500 * ms)
        s.noteBusy(9_000 * ms)
        assertEquals(
            "video transcode: stages decoded 50.0 fps, rendered 40.1 fps, render 15.1 ms avg 40.0 max " +
                "(frame wait 4.1, draw 1.0, swap 10.0), waits decoder input 200 ms/s encoder cap 50 ms/s, " +
                "work thread busy 90%",
            s.report(10_000),
        )
        assertEquals(
            "video transcode: stages decoded 0.0 fps, rendered 0.0 fps, render 0.0 ms avg 0.0 max " +
                "(frame wait 0.0, draw 0.0, swap 0.0), waits decoder input 0 ms/s encoder cap 0 ms/s, " +
                "work thread busy 0%",
            s.report(10_000),
        )
    }
}
