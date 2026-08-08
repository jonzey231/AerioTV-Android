package com.aeriotv.android.core.data.repository

import com.aeriotv.android.core.network.DispatcharrChannel
import com.aeriotv.android.core.network.DispatcharrEpgData
import com.aeriotv.android.core.network.DispatcharrEpgEntry
import com.aeriotv.android.core.network.DispatcharrEpgSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DispatcharrEpgSourceScopeTest {
    @Test
    fun `generated dummy grid entry survives domain conversion`() {
        val programmes = listOf(
            DispatcharrEpgEntry(
                startTime = "2026-08-08T08:35:00+00:00",
                endTime = "2026-08-08T11:35:00+00:00",
                title = "DAZN PPV",
                description = "Live event",
                tvgId = "06b645cf-a3dc-48fc-8a5b-50f78aac579b",
            ),
        ).toProgrammes()

        assertEquals(1, programmes.size)
        assertEquals("06b645cf-a3dc-48fc-8a5b-50f78aac579b", programmes.single().channelId)
        assertEquals("DAZN PPV", programmes.single().title)
        assertEquals(parseDispatcharrTimestamp("2026-08-08T08:35:00Z"), programmes.single().startMillis)
        assertEquals(parseDispatcharrTimestamp("2026-08-08T11:35:00Z"), programmes.single().endMillis)
    }

    @Test
    fun `generated dummy timestamp with explicit UTC offset is accepted`() {
        assertEquals(
            parseDispatcharrTimestamp("2026-08-08T08:35:00Z"),
            parseDispatcharrTimestamp("2026-08-08T08:35:00+00:00"),
        )
    }

    @Test
    fun `shared dummy assignment uses each channel UUID as its guide key`() {
        val data = mapOf(10 to DispatcharrEpgData(10, "shared.dummy", 1))
        val sourceTypes = mapOf(1 to "dummy")
        val one = DispatcharrChannel(1, "One", uuid = "uuid-one", epgDataId = 10)
        val two = DispatcharrChannel(2, "Two", uuid = "uuid-two", epgDataId = 10)

        val metadata = DispatcharrEpgMetadata.Available(data, sourceTypes)
        assertEquals("uuid-one", dispatcharrGuideKey(one, metadata))
        assertEquals("uuid-two", dispatcharrGuideKey(two, metadata))
    }

    @Test
    fun `XMLTV assignment keeps EPGData tvg id and effective assignment wins`() {
        val data = mapOf(
            10 to DispatcharrEpgData(10, "old.tv", 1),
            20 to DispatcharrEpgData(20, "effective.tv", 2),
        )
        val channel = DispatcharrChannel(
            1, "One", uuid = "uuid-one", tvgId = "raw.tv",
            epgDataId = 10, effectiveEpgDataId = 20,
        )

        assertEquals(
            "effective.tv",
            dispatcharrGuideKey(
                channel,
                DispatcharrEpgMetadata.Available(data, mapOf(1 to "xmltv", 2 to "xmltv")),
            ),
        )
    }

    @Test
    fun `confirmed channel without EPGData uses UUID for Dispatcharr generated placeholder`() {
        val channel = DispatcharrChannel(1, "One", uuid = "uuid-one", tvgId = "raw.tv")
        assertEquals(
            "uuid-one",
            dispatcharrGuideKey(
                channel,
                DispatcharrEpgMetadata.Available(emptyMap(), emptyMap()),
            ),
        )
    }

    @Test
    fun `failed EPG metadata fetch preserves raw channel key`() {
        val channel = DispatcharrChannel(1, "One", uuid = "uuid-one", tvgId = "raw.tv")

        assertEquals("raw.tv", dispatcharrGuideKey(channel, DispatcharrEpgMetadata.Unavailable))
    }

    @Test
    fun `failed source metadata fetch preserves raw channel key instead of collapsing shared dummy`() {
        val channel = DispatcharrChannel(
            1, "One", uuid = "uuid-one", tvgId = "raw.tv", epgDataId = 10,
        )
        val metadata = DispatcharrEpgMetadata.Available(
            epgDataById = mapOf(10 to DispatcharrEpgData(10, "shared.dummy", 1)),
            sourceTypesById = null,
        )

        assertEquals("raw.tv", dispatcharrGuideKey(channel, metadata))
    }

    @Test
    fun `fresh cache is refetched when a migrated Dispatcharr guide bucket is missing`() {
        assertEquals(
            true,
            shouldFetchDispatcharrEpg(
                forceRefresh = false,
                cacheFresh = true,
                cachedGuideKeys = setOf("shared.dummy", "regular.tv"),
                channelGuideKeys = setOf("uuid-one", "regular.tv"),
            ),
        )
    }

    @Test
    fun `fresh complete Dispatcharr cache still skips network`() {
        assertEquals(
            false,
            shouldFetchDispatcharrEpg(
                forceRefresh = false,
                cacheFresh = true,
                cachedGuideKeys = setOf("uuid-one", "regular.tv"),
                channelGuideKeys = setOf("uuid-one", "regular.tv"),
            ),
        )
    }

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