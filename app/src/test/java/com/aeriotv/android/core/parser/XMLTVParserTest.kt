package com.aeriotv.android.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class XMLTVParserTest {

    // JVM unit tests: android.util.Xml.newPullParser() is a stub that throws,
    // so the parser comes from kxml2 (test classpath) here -- the fallback
    // deliberately lives in this test setup, not in production code.
    private fun parseXmltv(
        input: InputStream,
        knownChannelKeys: Set<String>? = null,
        shouldAbort: (() -> Boolean)? = null,
        truncated: AtomicBoolean? = null,
    ) = XMLTVParser.parseWith(
        org.kxml2.io.KXmlParser().apply {
            setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        },
        knownChannelKeys,
        shouldAbort,
        truncated,
    )

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

        val progs = parseXmltv(ByteArrayInputStream(xml.toByteArray()))
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

        val progs = parseXmltv(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(3, progs.size)
        assertNotNull(progs[0].startMillis)
        assertNotNull(progs[1].startMillis)
        assertNotNull(progs[2].startMillis)
    }

    @Test
    fun namedZoneDatesFallBackAndAreNotUTC() {
        // The fast path can only handle numeric offsets; a named zone like
        // EST must fall back to SimpleDateFormat, which resolves it to
        // UTC-05:00 -- NOT silently parse as UTC.
        val expected = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
            .parse("20260912140000 EST")!!.time
        val parsed = XMLTVParser.parseXMLTVDate("20260912140000 EST")

        assertEquals(expected, parsed)
        val utc = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse("20260912140000")!!.time
        assertTrue("EST must not collapse to UTC", parsed != utc)

        // And end-to-end through the parser.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="20260912140000 EST" stop="20260912150000 EST" channel="ch1">
                <title>Show</title>
              </programme>
            </tv>
        """.trimIndent()
        val progs = parseXmltv(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(1, progs.size)
        assertEquals(expected, progs[0].startMillis)
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
        val progs = parseXmltv(ByteArrayInputStream(xml.toByteArray()), knownChannelKeys = knownKeys)

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
        val progs = parseXmltv(
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
        parseXmltv(ByteArrayInputStream(content.toByteArray()))

        val start = System.nanoTime()
        val progs = parseXmltv(ByteArrayInputStream(content.toByteArray()))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        assertEquals(5000, progs.size)
        println("XMLTVParser benchmark for 5,000 programmes: ${"%.2f".format(elapsedMs)} ms")
    }

    @Test
    @Ignore("Manual benchmark test")
    fun realDataXMLTVBenchmark() {
        val path = System.getenv("AERIO_BENCH_XMLTV_FILE")
        assumeTrue("Set AERIO_BENCH_XMLTV_FILE to a local XMLTV file to run", !path.isNullOrBlank())
        val file = java.io.File(path!!)
        assumeTrue("Real feed test file missing", file.exists())

        val times = mutableListOf<Double>()
        var count = 0
        for (runIdx in 1..3) {
            val start = System.nanoTime()
            val progs = XMLTVParser.parseFile(file)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            times.add(elapsedMs)
            count = progs.size
        }

        val avgMs = times.average()
        println("=== REAL XMLTV EPG BENCHMARK (53 MB File) ===")
        println("Parsed $count EPG programmes from 53MB real XML feed")
        println("Average parse time: ${"%.2f".format(avgMs)} ms (${"%.2f".format(avgMs / 1000.0)} seconds)")
    }

    @Test
    @Ignore("Manual benchmark test")
    fun compareOldVsNewXMLTVDate() {
        val dates = listOf(
            "20260912140000 +0000",
            "20260912140000 -0500",
            "20260912140000 +0200",
            "20260912140000",
            // Named zone: the fast path must NOT claim this as UTC -- it has
            // to fall back so SimpleDateFormat resolves EST to -05:00.
            "20260912140000 EST",
        )

        val fmtTz = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
        val fmtUtc = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
        fmtUtc.timeZone = java.util.TimeZone.getTimeZone("UTC")

        fun parseXMLTVDateOld(s: String): Long? {
            if (s.isEmpty()) return null
            try { return fmtTz.parse(s)?.time } catch (_: Throwable) {}
            try { return fmtUtc.parse(s)?.time } catch (_: Throwable) {}
            return null
        }

        // Warmup
        repeat(1000) {
            dates.forEach { parseXMLTVDateOld(it) }
        }

        // Measure OLD (SimpleDateFormat)
        val startOld = System.nanoTime()
        repeat(500000) {
            dates.forEach { parseXMLTVDateOld(it) }
        }
        val durationOld = (System.nanoTime() - startOld) / 1_000_000.0

        // Measure NEW (Fast Date Parser)
        val startNew = System.nanoTime()
        repeat(500000) {
            dates.forEach { XMLTVParser.parseXMLTVDate(it) }
        }
        val durationNew = (System.nanoTime() - startNew) / 1_000_000.0

        val speedup = ((durationOld - durationNew) / durationOld) * 100.0
        val ratio = durationOld / durationNew

        println("=== XMLTV DATE PARSER BENCHMARK (500k runs) ===")
        println("Old Date Parser (SimpleDateFormat): ${"%.2f".format(durationOld)} ms")
        println("New Date Parser (Fast Non-Throwing): ${"%.2f".format(durationNew)} ms")
        println("Speedup: ${"%.1f".format(speedup)}% faster (${"%.2f".format(ratio)}x speedup)")
    }
}
