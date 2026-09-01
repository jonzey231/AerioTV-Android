package com.aeriotv.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The legacy key-bridge API on top of the rebuilt identity module. Numeric
 * fallbacks (channel number, Dispatcharr integer id) are gone by design:
 * a raw key can only ever mean a declared tvg-id or a dummy-EPG uuid.
 */
class ChannelEpgKeyBridgeTest {
    private fun ch(name: String, tvgID: String = "", number: String? = null, dispId: Int? = null, uuid: String? = null) =
        M3UChannel(
            id = uuid?.let { "disp:$it" } ?: "m3u:$name", name = name, url = "http://x/$name",
            tvgID = tvgID, channelNumber = number, dispatcharrChannelId = dispId,
        )

    @Test
    fun declaredTvgIdIsTheOnlyRouteToAChannel() {
        // ESPN (Dispatcharr id 19, number 1) bound to "espn.us"; KNBC declares tvg-id "19".
        val espn = ch("ESPN HD", tvgID = "espn.us", number = "1", dispId = 19, uuid = "aaaa")
        val knbc = ch("KNBC", tvgID = "19", number = "15019")
        val bridge = buildChannelEpgKeyBridge(listOf(espn, knbc))
        assertEquals(knbc.guideMatchKey, bridge["19"])
        assertEquals(espn.guideMatchKey, bridge["espn.us"])
        // Channel number "1" and the integer id are not keys any more.
        assertNull(bridge["1"])
        assertNull(bridge["15019"])
    }

    @Test
    fun dummyEpgUuidBridgesToItsChannel() {
        val cam = ch("Cam", tvgID = "bbbb", uuid = "bbbb")
        val bridge = buildChannelEpgKeyBridge(listOf(cam))
        assertEquals(cam.guideMatchKey, bridge["bbbb"])
    }

    @Test
    fun bridgeChannelIdsAttachesOneToMany() {
        val a = ch("East", tvgID = "espn.us")
        val b = ch("West", tvgID = "espn.us")
        val progs = listOf(EPGProgramme(channelId = "ESPN.us", title = "SC", description = "", startMillis = 0, endMillis = 1, category = ""))
        val out = bridgeChannelIds(progs, listOf(a, b))
        assertEquals(setOf(a.guideMatchKey, b.guideMatchKey), out.map { it.channelId }.toSet())
    }
}
