package com.aeriotv.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelEpgKeyBridgeTest {
    private fun ch(name: String, tvgID: String = "", number: String? = null, dispId: Int? = null) =
        M3UChannel(id = "disp:$name", name = name, url = "http://x/$name", tvgID = tvgID,
            channelNumber = number, dispatcharrChannelId = dispId)

    @Test
    fun declaredTvgIdWinsOverAnotherChannelsNumericId() {
        // ESPN iterates first with Dispatcharr id 19; KNBC declares tvg-id "19".
        val espn = ch("ESPN HD", tvgID = "espn.us", number = "1", dispId = 19)
        val knbc = ch("KNBC", tvgID = "19", number = "15019")
        val bridge = buildChannelEpgKeyBridge(listOf(espn, knbc))
        assertEquals(knbc.guideMatchKey, bridge["19"])
        assertEquals(espn.guideMatchKey, bridge["espn.us"])
        assertEquals(espn.guideMatchKey, bridge["1"])
    }

    @Test
    fun numericFallbacksStillBridgeWhenUnclaimed() {
        val espn = ch("ESPN HD", tvgID = "espn.us", number = "1", dispId = 19)
        val bridge = buildChannelEpgKeyBridge(listOf(espn))
        assertEquals(espn.guideMatchKey, bridge["19"])
    }
}
