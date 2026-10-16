package com.aeriotv.android.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3UParserTest {

    @Test
    fun parseStandardExtInfAttributesAndName() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="CNN.us" tvg-name="CNN" tvg-logo="http://example.com/cnn.png" group-title="News" tvg-chno="101",CNN HD
            http://stream.example.com/cnn
        """.trimIndent()

        val channels = M3UParser.parse(m3u)
        assertEquals(1, channels.size)

        val ch = channels[0]
        assertEquals("m3u:CNN.us", ch.id)
        assertEquals("CNN HD", ch.name)
        assertEquals("http://stream.example.com/cnn", ch.url)
        assertEquals("CNN.us", ch.tvgID)
        assertEquals("CNN", ch.tvgName)
        assertEquals("http://example.com/cnn.png", ch.tvgLogo)
        assertEquals("News", ch.groupTitle)
        assertEquals("101", ch.channelNumber)
        assertFalse(ch.isRadio)
    }

    @Test
    fun parseRadioAndKodiProp() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="Radio1" radio="true",BBC Radio 1
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=http://license.example.com
            http://stream.example.com/radio1
        """.trimIndent()

        val channels = M3UParser.parse(m3u)
        assertEquals(1, channels.size)

        val ch = channels[0]
        assertEquals("m3u:Radio1", ch.id)
        assertEquals("BBC Radio 1", ch.name)
        assertTrue(ch.isRadio)
        assertEquals("com.widevine.alpha", ch.drmLicenseType)
        assertEquals("http://license.example.com", ch.drmLicenseKey)
    }

    @Test
    fun parseExtInfWithEqualsInAttributeValue() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ch1" tvg-logo="http://example.com/logo?a=1&b=2" group-title="Sports",Sports 1
            http://stream.example.com/s1
        """.trimIndent()

        val channels = M3UParser.parse(m3u)
        assertEquals(1, channels.size)

        val ch = channels[0]
        assertEquals("http://example.com/logo?a=1&b=2", ch.tvgLogo)
        assertEquals("Sports", ch.groupTitle)
        assertEquals("Sports 1", ch.name)
    }

    @Test
    fun parseMultipleChannelsAndDeduplicateIds() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="canal" group-title="Sports",Canal+ FHD
            http://stream.example.com/fhd
            #EXTINF:-1 tvg-id="canal" group-title="Sports",Canal+ HD
            http://stream.example.com/hd
        """.trimIndent()

        val channels = M3UParser.parse(m3u)
        assertEquals(2, channels.size)

        assertEquals("m3u:canal", channels[0].id)
        assertEquals("m3u:canal|http://stream.example.com/hd", channels[1].id)
    }

    @Test
    fun benchmarkM3UParserPerformance() {
        val sb = StringBuilder("#EXTM3U\n")
        for (i in 1..5000) {
            sb.append("#EXTINF:-1 tvg-id=\"channel.$i\" tvg-name=\"Channel $i\" tvg-logo=\"http://example.com/logo$i.png\" group-title=\"Category ${i % 10}\" tvg-chno=\"$i\",Channel Name $i\n")
            sb.append("http://stream.example.com/live/$i\n")
        }
        val content = sb.toString()

        // Warm up
        M3UParser.parse(content)

        val start = System.nanoTime()
        val channels = M3UParser.parse(content)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        assertEquals(5000, channels.size)
        println("M3UParser benchmark for 5,000 channels: ${"%.2f".format(elapsedMs)} ms")
    }
}
