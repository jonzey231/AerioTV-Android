package com.aeriotv.android.core.guide

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

/**
 * Pulls an event start out of a channel NAME. Xtream event feeds name
 * their dynamic channels like "NFHS 01: A vs B @ Sep 02 03:30AM ET" or
 * "FOX ONE 01: ... @ 3 Sep 01:30 AM ET" (Logan 2026-09-03); with no EPG
 * behind them the guide shows the name as the programme, and the parsed
 * time bounds that placeholder so the event does not read as "airing now"
 * all day. Returns null when the name carries no recognisable time.
 */
object EventTimeParser {
    private const val MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec"
    private const val TZ = "(?:\\s*(ET|EST|EDT|CT|CST|CDT|MT|MST|MDT|PT|PST|PDT|GMT|UTC|BST|CET|CEST|AEST|AEDT))?"
    private const val TIME = "(\\d{1,2})(?::(\\d{2}))?\\s*([AaPp]\\.?[Mm]\\.?)?"

    // "Sep 02 03:30AM ET", "Sep 2, 2026 8:00 PM", "September 02 20:00"
    private val monthFirst = Pattern.compile(
        "\\b($MONTHS)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?(?:\\s+(\\d{4}))?\\s*[,@-]?\\s*(?:at\\s+)?$TIME$TZ\\b",
        Pattern.CASE_INSENSITIVE,
    )
    // "3 Sep 01:30 AM ET", "03 Sep 2026 20:00"
    private val dayFirst = Pattern.compile(
        "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+($MONTHS)[a-z]*\\.?,?(?:\\s+(\\d{4}))?\\s*[,@-]?\\s*(?:at\\s+)?$TIME$TZ\\b",
        Pattern.CASE_INSENSITIVE,
    )
    // "09/02 8:00PM ET", "9/2/2026 20:00"
    private val numeric = Pattern.compile(
        "\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\s*[,@-]?\\s*(?:at\\s+)?$TIME$TZ\\b",
        Pattern.CASE_INSENSITIVE,
    )

    private val zones = mapOf(
        "ET" to "America/New_York", "EST" to "America/New_York", "EDT" to "America/New_York",
        "CT" to "America/Chicago", "CST" to "America/Chicago", "CDT" to "America/Chicago",
        "MT" to "America/Denver", "MST" to "America/Denver", "MDT" to "America/Denver",
        "PT" to "America/Los_Angeles", "PST" to "America/Los_Angeles", "PDT" to "America/Los_Angeles",
        "GMT" to "UTC", "UTC" to "UTC", "BST" to "Europe/London",
        "CET" to "Europe/Paris", "CEST" to "Europe/Paris", "AEST" to "Australia/Sydney", "AEDT" to "Australia/Sydney",
    )

    /** Epoch millis of the event start, or null. [nowMs] resolves the year. */
    fun parse(name: String, nowMs: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Long? {
        monthFirst.matcher(name).let { m ->
            if (m.find()) return build(month(m.group(1)), m.group(2)!!.toInt(), m.group(3), m.group(4), m.group(5), m.group(6), m.group(7), nowMs, zone)
        }
        dayFirst.matcher(name).let { m ->
            if (m.find()) return build(month(m.group(2)), m.group(1)!!.toInt(), m.group(3), m.group(4), m.group(5), m.group(6), m.group(7), nowMs, zone)
        }
        numeric.matcher(name).let { m ->
            if (m.find()) return build(m.group(1)!!.toInt() - 1, m.group(2)!!.toInt(), m.group(3), m.group(4), m.group(5), m.group(6), m.group(7), nowMs, zone)
        }
        return null
    }

    private fun month(token: String?): Int {
        val t = token?.lowercase(Locale.US)?.take(3) ?: return -1
        return listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(t)
    }

    private fun build(
        month: Int, day: Int, year: String?, hour: String?, minute: String?, ampm: String?, tz: String?,
        nowMs: Long, zone: TimeZone,
    ): Long? {
        if (month !in 0..11 || day !in 1..31) return null
        var h = hour?.toIntOrNull() ?: return null
        val min = minute?.toIntOrNull() ?: 0
        val mer = ampm?.replace(".", "")?.uppercase(Locale.US)
        if (mer != null) {
            if (h !in 1..12) return null
            if (mer == "PM" && h < 12) h += 12
            if (mer == "AM" && h == 12) h = 0
        } else if (h !in 0..23) return null
        if (min !in 0..59) return null
        val tzId = tz?.uppercase(Locale.US)?.let { zones[it] }
        val cal = Calendar.getInstance(tzId?.let { TimeZone.getTimeZone(it) } ?: zone, Locale.US)
        cal.timeInMillis = nowMs
        val nowYear = cal.get(Calendar.YEAR)
        val y = year?.let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
        fun at(yr: Int): Long {
            cal.clear()
            cal.set(yr, month, day, h, min, 0)
            return cal.timeInMillis
        }
        if (y != null) return at(y)
        // No year in the name: this year, unless that is more than two days
        // past, in which case the feed already rolled into next year.
        val thisYear = at(nowYear)
        return if (thisYear < nowMs - 2 * 86_400_000L) at(nowYear + 1) else thisYear
    }
}
