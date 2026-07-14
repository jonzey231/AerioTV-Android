package com.aeriotv.android.core.parser

import android.util.Xml
import com.aeriotv.android.core.data.EPGProgramme
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * XMLTV parser ported from iOS Aerio/Networking/PlaylistParsers.swift
 * (XMLTVParser class, lines 118-294). Uses Android's pull-parser
 * ([XmlPullParser]) for streaming low-memory parsing of large guides.
 *
 * Carries forward iOS gotcha fixes:
 *  - First non-empty <title>/<desc> wins (localised duplicates collapse)
 *  - Each <category> is emitted separately and joined with comma at close
 *  - Per-element text accumulator avoids multi-chunk character-yield bugs
 *  - Whitespace trimmed once at end-tag, not per-chunk
 *
 * Adds Android backlog item: detects gzip (URL .gz suffix OR magic bytes
 * 0x1F 0x8B) and streams through [GZIPInputStream]. iOS backlog item 1.
 */
object XMLTVParser {

    private val xmltvFmtTz: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    }
    private val xmltvFmtUtc: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    fun parseBytes(
        bytes: ByteArray,
        knownChannelKeys: Set<String>? = null,
    ): List<EPGProgramme> {
        val isGzip = bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
        return if (isGzip) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { parse(it, knownChannelKeys) }
        } else {
            ByteArrayInputStream(bytes).use { parse(it, knownChannelKeys) }
        }
    }

    /** GH #26: parse a downloaded guide FILE in constant memory. The parser
     *  itself already streams (XmlPullParser over an InputStream); this
     *  entry point just avoids [parseBytes]'s whole-body ByteArray, which a
     *  multi-hundred-MB provider XMLTV turned into an OOM on constrained
     *  heaps. Gzip sniff matches [parseBytes] (0x1F 0x8B magic). */
    fun parseFile(
        file: java.io.File,
        knownChannelKeys: Set<String>? = null,
    ): List<EPGProgramme> {
        val head = ByteArray(2)
        val isGzip = file.inputStream().use { it.read(head) == 2 } &&
            head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()
        val base = file.inputStream().buffered()
        val stream = if (isGzip) GZIPInputStream(base) else base
        return stream.use { parse(it, knownChannelKeys) }
    }

    /**
     * Common HTML entities found in XMLTV from sites that scrape HTML guides
     * (BBC, IMDB-driven feeds, etc.) but never declared in the DTD. We register
     * them with the parser so it can resolve them inline without a string-replace
     * pass over the entire decompressed payload — important when guides reach
     * 50+ MB. XML built-ins (`amp lt gt quot apos`) are already known to the parser.
     */
    private val HTML_ENTITIES = mapOf(
        "nbsp" to " ",
        "middot" to "·",
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
        "laquo" to "«",
        "raquo" to "»",
        "lsquo" to "‘",
        "rsquo" to "’",
        "ldquo" to "“",
        "rdquo" to "”",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "bull" to "•",
        "deg" to "°",
    )

    /**
     * Streaming XMLTV parse. Optional [knownChannelKeys] is a lowercased set
     * of every candidate channel key the caller's M3UChannel list would
     * accept (tvg-ids, channel numbers, dispatcharr channel ids, and any
     * uuid-shaped rawAttribute) -- see [com.aeriotv.android.core.data
     * .buildChannelEpgKeyBridge]. When non-null, programmes whose
     * `channel="..."` attribute isn't in the set are skipped before the
     * EPGProgramme allocation. iOS GuideStore audit P3 #13.
     *
     * For a 7000-channel guide that the user only has 700 active channels
     * for, this trims ~90% of programme allocations during parse -- the
     * downstream bridge / dedup / group pipeline never sees the dead rows.
     * Pass null (the default) to keep every programme; existing callers
     * that don't have a channel list at parse time (e.g. parser unit tests)
     * keep working unchanged.
     */
    fun parse(
        input: InputStream,
        knownChannelKeys: Set<String>? = null,
    ): List<EPGProgramme> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        // Teach KXmlParser about undeclared HTML entities before it sees them.
        // Method is part of the kxml2 implementation; if Android ever swaps the
        // backing parser, the catch keeps us building. Failing to register just
        // means those entities will throw at parse time as before.
        runCatching {
            val method = parser.javaClass.getMethod(
                "defineEntityReplacementText",
                String::class.java,
                String::class.java,
            )
            for ((name, replacement) in HTML_ENTITIES) {
                method.invoke(parser, name, replacement)
            }
        }

        val out = mutableListOf<EPGProgramme>()

        var insideProgramme = false
        var channelId = ""
        var startStr = ""
        var stopStr = ""
        var title = ""
        var desc = ""
        val categories = mutableListOf<String>()
        // EPG badge metadata (guide/list/info-sheet badges).
        var subTitle = ""
        var season: Int? = null
        var episode: Int? = null
        var isNew = false
        var isLiveBroadcast = false
        var isPremiere = false
        var isRepeat = false
        var epNumSystem = ""
        val text = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    when (parser.name) {
                        "programme" -> {
                            insideProgramme = true
                            channelId = parser.getAttributeValue(null, "channel").orEmpty()
                            startStr = parser.getAttributeValue(null, "start").orEmpty()
                            stopStr = parser.getAttributeValue(null, "stop").orEmpty()
                            title = ""
                            desc = ""
                            categories.clear()
                            subTitle = ""
                            season = null
                            episode = null
                            isNew = false
                            isLiveBroadcast = false
                            isPremiere = false
                            isRepeat = false
                            epNumSystem = ""
                        }
                        // Empty/self-closing marker elements: presence is the
                        // signal (they carry no text), so detect at START_TAG.
                        "new" -> if (insideProgramme) isNew = true
                        "live" -> if (insideProgramme) isLiveBroadcast = true
                        "premiere" -> if (insideProgramme) isPremiere = true
                        "previously-shown" -> if (insideProgramme) isRepeat = true
                        "episode-num" -> if (insideProgramme) {
                            epNumSystem = parser.getAttributeValue(null, "system").orEmpty()
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideProgramme) text.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (insideProgramme) {
                        val trimmed = text.toString().trim()
                        when (parser.name) {
                            "title" -> if (title.isEmpty() && trimmed.isNotEmpty()) title = trimmed
                            "desc" -> if (desc.isEmpty() && trimmed.isNotEmpty()) desc = trimmed
                            "category" -> if (trimmed.isNotEmpty()) categories.add(trimmed)
                            "sub-title" -> if (subTitle.isEmpty() && trimmed.isNotEmpty()) subTitle = trimmed
                            "episode-num" -> {
                                val (s, e) = parseEpisodeNum(epNumSystem, trimmed)
                                if (s != null && season == null) season = s
                                if (e != null && episode == null) episode = e
                            }
                        }
                    }
                    if (parser.name == "programme") {
                        insideProgramme = false
                        // Filter-during-parse (P3 #13): drop the programme
                        // before any further work when its channel key isn't
                        // one we'd ever match against. The bridge step
                        // downstream uses the SAME candidate-key shape, so a
                        // miss here is a guaranteed miss there.
                        val skipUnknown = knownChannelKeys != null &&
                            channelId.trim().lowercase().let { it.isEmpty() || it !in knownChannelKeys }
                        if (!skipUnknown) {
                            val startMs = parseXMLTVDate(startStr)
                            val stopMs = parseXMLTVDate(stopStr)
                            if (startMs != null && stopMs != null && title.isNotEmpty()) {
                                out.add(
                                    EPGProgramme(
                                        channelId = channelId,
                                        title = title,
                                        description = desc,
                                        startMillis = startMs,
                                        endMillis = stopMs,
                                        category = categories.joinToString(","),
                                        subTitle = subTitle.takeIf { it.isNotEmpty() },
                                        season = season,
                                        episode = episode,
                                        isNew = isNew,
                                        isLiveBroadcast = isLiveBroadcast,
                                        isPremiere = isPremiere,
                                        // XMLTV has no standard finale tag; only
                                        // repeat (<previously-shown>) is available.
                                        isRepeat = isRepeat,
                                    )
                                )
                            }
                        }
                    }
                    text.setLength(0)
                }
            }
            event = parser.next()
        }

        return out
    }

    /**
     * Decode an XMLTV `<episode-num>` value into 1-based (season, episode).
     *  - `system="xmltv_ns"`: dot-separated ZERO-based `season.episode.part`,
     *    each part optionally `n/total`; empty parts mean unknown.
     *    e.g. `"2.4.0/2"` -> season 3, episode 5.
     *  - `system="onscreen"` (or anything else, e.g. a bare string): human
     *    text; extract S/E digits tolerating `S3E5`, `S03E05`, `3x05`.
     *  - `dd_progid` and other id-only systems yield `null to null`.
     */
    private fun parseEpisodeNum(system: String, raw: String): Pair<Int?, Int?> {
        val value = raw.trim()
        if (value.isEmpty()) return null to null
        if (system == "xmltv_ns") {
            val parts = value.split(".")
            fun part(i: Int): Int? = parts.getOrNull(i)
                ?.substringBefore("/")?.trim()?.toIntOrNull()?.let { it + 1 }
            return part(0) to part(1)
        }
        Regex("[Ss]\\s*(\\d{1,4})\\s*[Ee]\\s*(\\d{1,4})").find(value)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        Regex("(\\d{1,4})\\s*[xX]\\s*(\\d{1,4})").find(value)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        return null to null
    }

    private fun parseXMLTVDate(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        runCatching { return xmltvFmtTz.get()!!.parse(s)?.time }
        runCatching { return xmltvFmtUtc.get()!!.parse(s)?.time }
        return null
    }
}
