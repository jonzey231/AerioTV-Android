package com.aeriotv.android.core.data.repository

import com.aeriotv.android.core.network.DispatcharrChannel
import com.aeriotv.android.core.network.DispatcharrEpgData
import com.aeriotv.android.core.network.DispatcharrEpgSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DispatcharrEpgSourceScopeTest {
    @Test
    fun `same tvg id is accepted only from the source assigned to the loaded channel`() {
        val channels = listOf(
            DispatcharrChannel(
                id = 2001,
                name = "MAX PPV 1",
                uuid = "max-1",
                epgDataId = 20,
            ),
        )
        val epgData = listOf(
            DispatcharrEpgData(id = 10, tvgId = "max.ppv.1", epgSourceId = 1),
            DispatcharrEpgData(id = 20, tvgId = "max.ppv.1", epgSourceId = 2),
        )
        val sources = listOf(
            DispatcharrEpgSource(id = 1, sourceType = "xmltv", url = "https://wrong/epg.xml"),
            DispatcharrEpgSource(id = 2, sourceType = "xmltv", url = "https://chosen/epg.xml"),
        )

        assertEquals(
            listOf(DispatcharrEpgLayer("https://chosen/epg.xml", setOf("max.ppv.1"))),
            dispatcharrEpgLayers(channels, epgData, sources),
        )
    }

    @Test
    fun `effective EPG assignment wins and unused inactive or non XMLTV sources are excluded`() {
        val channels = listOf(
            DispatcharrChannel(
                id = 7,
                name = "Seven",
                uuid = "seven",
                epgDataId = 70,
                effectiveEpgDataId = 71,
            ),
        )
        val epgData = listOf(
            DispatcharrEpgData(id = 70, tvgId = "old.seven", epgSourceId = 1),
            DispatcharrEpgData(id = 71, tvgId = "new.seven", epgSourceId = 2),
        )
        val sources = listOf(
            DispatcharrEpgSource(id = 1, sourceType = "xmltv", url = "https://old/epg.xml"),
            DispatcharrEpgSource(id = 2, sourceType = "xmltv", url = "https://new/epg.xml"),
            DispatcharrEpgSource(id = 3, sourceType = "xmltv", url = "https://inactive/epg.xml", isActive = false),
            DispatcharrEpgSource(id = 4, sourceType = "dummy", url = "https://dummy/epg.xml"),
        )

        assertEquals(
            listOf(DispatcharrEpgLayer("https://new/epg.xml", setOf("new.seven"))),
            dispatcharrEpgLayers(channels, epgData, sources),
        )
    }

    @Test
    fun `mixed-case EPG tvg ids are normalized like XMLTV parser channel ids`() {
        val channels = listOf(
            DispatcharrChannel(id = 1, name = "Mixed", uuid = "mixed", epgDataId = 10),
        )
        val epgData = listOf(
            DispatcharrEpgData(id = 10, tvgId = "  Mixed.Case.TV  ", epgSourceId = 1),
        )
        val sources = listOf(
            DispatcharrEpgSource(id = 1, sourceType = "xmltv", url = "https://chosen/epg.xml"),
        )

        assertEquals(
            listOf(DispatcharrEpgLayer("https://chosen/epg.xml", setOf("mixed.case.tv"))),
            dispatcharrEpgLayers(channels, epgData, sources, setOf("mixed.case.tv")),
        )
    }

    @Test
    fun `source records sharing one URL combine their independently assigned channel keys`() {
        val channels = listOf(
            DispatcharrChannel(id = 1, name = "One", uuid = "one", epgDataId = 10),
            DispatcharrChannel(id = 2, name = "Two", uuid = "two", epgDataId = 20),
        )
        val epgData = listOf(
            DispatcharrEpgData(id = 10, tvgId = "one.tv", epgSourceId = 1),
            DispatcharrEpgData(id = 20, tvgId = "two.tv", epgSourceId = 2),
        )
        val sources = listOf(
            DispatcharrEpgSource(id = 1, sourceType = "xmltv", url = "https://shared/epg.xml"),
            DispatcharrEpgSource(id = 2, sourceType = "xmltv", url = "https://shared/epg.xml"),
        )

        assertEquals(
            listOf(DispatcharrEpgLayer("https://shared/epg.xml", setOf("one.tv", "two.tv"))),
            dispatcharrEpgLayers(channels, epgData, sources),
        )
    }
}