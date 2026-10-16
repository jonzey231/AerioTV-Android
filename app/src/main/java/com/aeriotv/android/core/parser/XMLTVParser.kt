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
    // Compiled once. These used to be constructed inside parseEpisodeNum,
    // i.e. compiled fresh for every programme in every feed.
    private val SEASON_EPISODE_REGEX = Regex("[Ss]\\s*(\\d{1,4})\\s*[Ee]\\s*(\\d{1,4})")
    private val NXM_REGEX = Regex("(\\d{1,4})\\s*[xX]\\s*(\\d{1,4})")

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
        shouldAbort: (() -> Boolean)? = null,
        truncated: java.util.concurrent.atomic.AtomicBoolean? = null,
    ): List<EPGProgramme> {
        val head = ByteArray(2)
        val isGzip = file.inputStream().use { it.read(head) == 2 } &&
            head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()
        val base = file.inputStream().buffered()
        val stream = if (isGzip) GZIPInputStream(base) else base
        return stream.use { parse(it, knownChannelKeys, shouldAbort, truncated) }
    }

    /**
     * Common HTML entities found in XMLTV from sites that scrape HTML guides
     * (BBC, IMDB-driven feeds, etc.) but never declared in the DTD. We register
     * them with the parser so it can resolve them inline without a string-replace
     * pass over the entire decompressed payload, important when guides reach
     * 50+ MB. XML built-ins (`amp lt gt quot apos`) are already known to the parser.
     */
    /** Poll cadence for [parse]'s shouldAbort hook. A parser event is one
     *  tag/text token, so 8192 events is a few hundred programmes: frequent
     *  enough that a budget overrun is caught within tens of milliseconds,
     *  rare enough to cost nothing measurable on the happy path. */
    private const val ABORT_CHECK_EVERY = 8192

    private val HTML_ENTITIES = mapOf(
        "nbsp" to " ",
        "middot" to "·",
        "mdash" to "-",
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
        /**
         * Cooperative budget hook, polled every [ABORT_CHECK_EVERY] parser
         * events. When it returns true the parse STOPS and returns whatever
         * it has so far (partial data is still valid programmes).
         *
         * This exists because withTimeout cannot interrupt this loop: it is
         * CPU-bound and never suspends, so a multi-million-programme national
         * feed (epg.guru 7-day files, Discord reports 2026-08-08) blew
         * minutes past its "90s ceiling" while the timeout sat unfired.
         * Wall-clock enforcement has to live INSIDE the loop.
         */
        shouldAbort: (() -> Boolean)? = null,
        /** Set to true when [shouldAbort] cut the parse short, so the caller
         *  knows the returned list is a FRAGMENT of the feed. */
        truncated: java.util.concurrent.atomic.AtomicBoolean? = null,
    ): List<EPGProgramme> {
        val parser: XmlPullParser = try {
            Xml.newPullParser()
        } catch (_: Throwable) {
            (java.lang.Class.forName("org.kxml2.io.KXmlParser").getDeclaredConstructor().newInstance() as XmlPullParser)
        }
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

        var skippingProgramme = false
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
        var programmeIcon = ""
        val text = StringBuilder()

        var event = parser.eventType
        var eventsSinceCheck = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            if (shouldAbort != null && ++eventsSinceCheck >= ABORT_CHECK_EVERY) {
                eventsSinceCheck = 0
                if (shouldAbort()) {
                    runCatching {
                        android.util.Log.w(
                            "XMLTVParser",
                            "parse aborted by budget; keeping ${out.size} programmes parsed so far",
                        )
                    }
                    // Tell the caller this list is a FRAGMENT. A partial
                    // feed must not be treated as authoritative for the
                    // channels it happens to mention, or the cache merge
                    // deletes rows it never carried (mergeForPlaylist).
                    truncated?.set(true)
                    return out
                }
            }
            when (event) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    when (parser.name) {
                        "programme" -> {
                            channelId = parser.getAttributeValue(null, "channel").orEmpty()
                            // Decide the channel filter HERE, not at the END tag:
                            // a 224MB national feed is ~99% programmes for channels
                            // this playlist never matches, and accumulating their
                            // titles/descs/categories and running the episode-num
                            // regexes on each cost ~85s of parse for zero output
                            // (Streamer, 2026-09-01). Skipped programmes now cost
                            // one attribute read and nothing else.
                            skippingProgramme = knownChannelKeys != null &&
                                channelId.trim().lowercase().let { it.isEmpty() || it !in knownChannelKeys }
                            insideProgramme = !skippingProgramme
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
                            programmeIcon = ""
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
                        // `<icon src="...">` is an attribute-only element, so
                        // read it at START_TAG like the marker tags. This is
                        // the programme's own artwork and the last art
                        // fallback in Program Info / the guide preview banner.
                        "icon" -> if (insideProgramme && programmeIcon.isEmpty()) {
                            programmeIcon = parser.getAttributeValue(null, "src").orEmpty().trim()
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
                        val skipUnknown = skippingProgramme
                        insideProgramme = false
                        skippingProgramme = false
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
                                        iconUrl = programmeIcon.takeIf { it.isNotEmpty() },
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
        SEASON_EPISODE_REGEX.find(value)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        NXM_REGEX.find(value)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        return null to null
    }

    internal fun parseXMLTVDate(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null

        val fastParsed = parseXMLTVDateFast(s)
        if (fastParsed != null) return fastParsed

        runCatching { return xmltvFmtTz.get()!!.parse(s)?.time }
        runCatching { return xmltvFmtUtc.get()!!.parse(s)?.time }
        return null
    }

    private fun parseXMLTVDateFast(s: String): Long? {
        if (s.length < 14) return null

        for (idx in 0 until 14) {
            if (s[idx] !in '0'..'9') return null
        }

        val year = (s[0] - '0') * 1000 + (s[1] - '0') * 100 + (s[2] - '0') * 10 + (s[3] - '0')
        val month = (s[4] - '0') * 10 + (s[5] - '0')
        val day = (s[6] - '0') * 10 + (s[7] - '0')
        val hour = (s[8] - '0') * 10 + (s[9] - '0')
        val minute = (s[10] - '0') * 10 + (s[11] - '0')
        val second = (s[12] - '0') * 10 + (s[13] - '0')

        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return null
        }

        var zoneOffset = java.time.ZoneOffset.UTC
        if (s.length > 14) {
            val rest = s.substring(14).trim()
            if (rest.isNotEmpty() && rest != "Z" && rest != "+0000" && rest != "-0000" && rest != "+00:00") {
                val signIndex = if (rest[0] == '+' || rest[0] == '-') 0 else -1
                if (signIndex == 0 && rest.length >= 5) {
                    val isNeg = rest[0] == '-'
                    val h1 = rest[1] - '0'
                    val h2 = rest[2] - '0'
                    val mOffset = if (rest[3] == ':') {
                        if (rest.length >= 6 && rest[4] in '0'..'9' && rest[5] in '0'..'9') {
                            (h1 * 10 + h2) * 60 + (rest[4] - '0') * 10 + (rest[5] - '0')
                        } else null
                    } else if (rest[3] in '0'..'9' && rest[4] in '0'..'9') {
                        (h1 * 10 + h2) * 60 + (rest[3] - '0') * 10 + (rest[4] - '0')
                    } else null

                    if (mOffset != null) {
                        val totalSeconds = if (isNeg) -mOffset * 60 else mOffset * 60
                        runCatching { zoneOffset = java.time.ZoneOffset.ofTotalSeconds(totalSeconds) }
                    }
                }
            }
        }

        return runCatching {
            java.time.LocalDateTime.of(year, month, day, hour, minute, second)
                .toInstant(zoneOffset)
                .toEpochMilli()
        }.getOrNull()
    }
}
