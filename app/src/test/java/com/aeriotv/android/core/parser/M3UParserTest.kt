package com.aeriotv.android.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

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
    fun differentialTestOldRegexVsNewScanner() {
        val testLines = listOf(
            "#EXTINF:-1 tvg-id=\"CNN.us\" tvg-name=\"CNN\" tvg-logo=\"http://example.com/cnn.png\" group-title=\"News\" tvg-chno=\"101\",CNN HD",
            "#EXTINF:-1 tvg-id=\"ch1\" tvg-logo=\"http://example.com/logo?a=1&b=2\" group-title=\"Sports | Action\",Sports 1",
            "#EXTINF:-1 tvg-id=\"\" tvg-name=\"ABC\" radio=\"true\",ABC Radio",
            "#EXTINF:-1 tvg-id=\"100\" tvg-name=\"Foo, Bar\",Foo, Bar Channel",
            "#EXTINF:-1 channel-id=\"uuid-1234\" uuid=\"uuid-5678\",Test Channel",
        )

        val regex = Regex("""([\w-]+)="([^"]*)"""")
        fun parseExtInfOld(line: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            val commaIndex = line.lastIndexOf(',')
            if (commaIndex >= 0 && commaIndex < line.length - 1) {
                result["name"] = line.substring(commaIndex + 1).trim()
            }
            for (match in regex.findAll(line)) {
                val key = match.groupValues[1].lowercase()
                val value = match.groupValues[2]
                result[key] = value
            }
            return result
        }

        for (line in testLines) {
            val oldResult = parseExtInfOld(line)
            val newResult = M3UParser.parseExtInf(line)
            assertEquals("Mismatch for line: $line", oldResult, newResult)
        }
    }

    @Test
    @Ignore("Manual benchmark test")
    fun realDataM3UBenchmark() {
        val file = File("/home/luke/.cache/Google/AndroidStudio2026.2.1/projects/aeriotv-android.f0381c01/.artifacts/fa34c772-b13c-4d60-a027-a33c21b632d7/scratch/test_feed.m3u")
        assumeTrue("Real feed test file missing", file.exists())

        // Warm up
        M3UParser.parseFile(file)

        val times = mutableListOf<Double>()
        var count = 0
        for (runIdx in 1..10) {
            val start = System.nanoTime()
            val channels = M3UParser.parseFile(file)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            times.add(elapsedMs)
            count = channels.size
        }

        val avgMs = times.average()
        println("=== REAL M3U BENCHMARK ===")
        println("Parsed $count channels from real feed")
        println("Average parse time: ${"%.2f".format(avgMs)} ms (min: ${"%.2f".format(times.minOrNull())} ms, max: ${"%.2f".format(times.maxOrNull())} ms)")
    }

    @Test
    @Ignore("Manual benchmark test")
    fun compareOldVsNewM3U() {
        val file = File("/home/luke/.cache/Google/AndroidStudio2026.2.1/projects/aeriotv-android.f0381c01/.artifacts/fa34c772-b13c-4d60-a027-a33c21b632d7/scratch/test_feed.m3u")
        assumeTrue("Real feed test file missing", file.exists())

        val lines = file.readLines().filter { it.startsWith("#EXTINF:") }

        val regex = Regex("""([\w-]+)="([^"]*)"""")
        fun parseExtInfOld(line: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            val commaIndex = line.lastIndexOf(',')
            if (commaIndex >= 0 && commaIndex < line.length - 1) {
                result["name"] = line.substring(commaIndex + 1).trim()
            }
            for (match in regex.findAll(line)) {
                val key = match.groupValues[1].lowercase()
                val value = match.groupValues[2]
                result[key] = value
            }
            return result
        }

        // Warmup
        repeat(5) {
            lines.forEach { parseExtInfOld(it) }
            lines.forEach { M3UParser.parseExtInf(it) }
        }

        // Measure OLD (Regex on #EXTINF lines)
        val startOld = System.nanoTime()
        repeat(100) {
            lines.forEach { parseExtInfOld(it) }
        }
        val durationOld = (System.nanoTime() - startOld) / 1_000_000.0

        // Measure NEW (Scanner on #EXTINF lines - Isolated Stage)
        val startNew = System.nanoTime()
        repeat(100) {
            lines.forEach { M3UParser.parseExtInf(it) }
        }
        val durationNew = (System.nanoTime() - startNew) / 1_000_000.0

        val speedup = ((durationOld - durationNew) / durationOld) * 100.0
        val ratio = durationOld / durationNew

        println("=== REAL M3U EXTINF ATTRIBUTE PARSER STAGE COMPARISON (${lines.size} ExtInf Lines x 100 iterations) ===")
        println("Old (Regex) Time: ${"%.2f".format(durationOld)} ms")
        println("New (Zero-Regex Scanner) Time: ${"%.2f".format(durationNew)} ms")
        println("Speedup: ${"%.1f".format(speedup)}% faster (${"%.2f".format(ratio)}x speedup)")
    }
}
