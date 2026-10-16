package com.aeriotv.android.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

class XMLTVParserTest {

    @Test
    fun parseProgrammeWithBadgesAndMetadata() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="20260912140000 +0000" stop="20260912150000 +0000" channel="espn.us">
                <title>SportsCenter</title>
                <sub-title>Morning Edition</sub-title>
                <desc>Live sports updates and news.</desc>
                <category>Sports</category>
                <category>News</category>
                <episode-num system="xmltv_ns">1.3.0/1</episode-num>
                <live/>
                <icon src="http://example.com/art.jpg"/>
              </programme>
            </tv>
        """.trimIndent()

        val progs = XMLTVParser.parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(1, progs.size)

        val p = progs[0]
        assertEquals("espn.us", p.channelId)
        assertEquals("SportsCenter", p.title)
        assertEquals("Morning Edition", p.subTitle)
        assertEquals("Live sports updates and news.", p.description)
        assertEquals("Sports,News", p.category)
        assertEquals(2, p.season) // 1 + 1 (1-based)
        assertEquals(4, p.episode) // 3 + 1 (1-based)
        assertTrue(p.isLiveBroadcast)
        assertEquals("http://example.com/art.jpg", p.iconUrl)
    }

    @Test
    fun parseProgrammesWithVariousTimezones() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="20260912140000 -0500" stop="20260912150000 -0500" channel="ch1">
                <title>Show 1</title>
              </programme>
              <programme start="20260912140000 +0200" stop="20260912150000 +0200" channel="ch2">
                <title>Show 2</title>
              </programme>
              <programme start="20260912140000" stop="20260912150000" channel="ch3">
                <title>Show 3</title>
              </programme>
            </tv>
        """.trimIndent()

        val progs = XMLTVParser.parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(3, progs.size)
        assertNotNull(progs[0].startMillis)
        assertNotNull(progs[1].startMillis)
        assertNotNull(progs[2].startMillis)
    }

    @Test
    fun filterKnownChannelKeys() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="20260912140000 +0000" stop="20260912150000 +0000" channel="wanted.us">
                <title>Wanted Show</title>
              </programme>
              <programme start="20260912140000 +0000" stop="20260912150000 +0000" channel="unwanted.us">
                <title>Unwanted Show</title>
              </programme>
            </tv>
        """.trimIndent()

        val knownKeys = setOf("wanted.us")
        val progs = XMLTVParser.parse(ByteArrayInputStream(xml.toByteArray()), knownChannelKeys = knownKeys)

        assertEquals(1, progs.size)
        assertEquals("wanted.us", progs[0].channelId)
    }

    @Test
    fun abortBudgetCutoff() {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>\n")
        for (i in 1..2000) {
            sb.append("<programme start=\"20260912140000 +0000\" stop=\"20260912150000 +0000\" channel=\"ch$i\"><title>Show $i</title></programme>\n")
        }
        sb.append("</tv>")

        val truncated = AtomicBoolean(false)
        val progs = XMLTVParser.parse(
            input = ByteArrayInputStream(sb.toString().toByteArray()),
            shouldAbort = { true },
            truncated = truncated,
        )

        assertTrue(truncated.get())
        assertTrue(progs.size < 2000)
    }

    @Test
    fun benchmarkXMLTVParserPerformance() {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>\n")
        for (i in 1..5000) {
            sb.append("<programme start=\"20260912140000 +0000\" stop=\"20260912150000 +0000\" channel=\"channel.$i\">\n")
            sb.append("  <title>Program Title $i</title>\n")
            sb.append("  <desc>Description for program $i with some details.</desc>\n")
            sb.append("  <category>Sports</category>\n")
            sb.append("  <episode-num system=\"xmltv_ns\">1.${i % 20}.0/1</episode-num>\n")
            sb.append("</programme>\n")
        }
        sb.append("</tv>")
        val content = sb.toString()

        // Warm up
        XMLTVParser.parse(ByteArrayInputStream(content.toByteArray()))

        val start = System.nanoTime()
        val progs = XMLTVParser.parse(ByteArrayInputStream(content.toByteArray()))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        assertEquals(5000, progs.size)
        println("XMLTVParser benchmark for 5,000 programmes: ${"%.2f".format(elapsedMs)} ms")
    }
}
