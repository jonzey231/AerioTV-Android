package com.aeriotv.android.core.playback

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil

/**
 * Builds Xtream-Codes / Dispatcharr catch-up (timeshift) playback URLs and the
 * per-source credential + timezone context they need.
 *
 * The protocol (verified from the leaked XC 2.9 panel source, Kodi
 * pvr.iptvsimple, and Dispatcharr PR #1242): a finished programme within a
 * channel's `tv_archive_duration` window is playable via the PATH form
 *
 *   {base}/timeshift/{user}/{pass}/{durationMinutes}/{start}/{streamId}.ts
 *
 * which returns a bounded, byte-range-seekable MPEG-TS of that one programme.
 *
 * THE FOOTGUN is [start]: the panel parses it as wall-clock time in ITS OWN
 * timezone (the same zone it advertises in server_info.timezone and uses for
 * the EPG start/end strings), NOT UTC and NOT device-local. Formatting in the
 * wrong zone yields the classic "catch-up plays the wrong hour" bug. Dispatcharr
 * pins its zone to UTC and serves UTC EPG, so for a Dispatcharr source we render
 * the (UTC-epoch) programme start in UTC. For a raw XC provider we render in the
 * zone captured from its player_api handshake, plus an optional manual
 * correction offset for misconfigured panels (several IPTV clients ship one).
 */
object CatchupUrlBuilder {

    /**
     * Everything needed to turn a past programme into a playable timeshift URL
     * for one source. [streamId] is the id the panel timeshifts by: the XC
     * `stream_id` for a raw provider, or Dispatcharr's Channel.id (which its XC
     * layer exposes as stream_id) for a Direct-Connect source.
     */
    data class Context(
        val baseUrl: String,
        val username: String,
        val password: String,
        val streamId: String,
        /** IANA zone the panel interprets `start` in. Dispatcharr = "UTC". */
        val panelTimeZoneId: String = "UTC",
    )

    /**
     * Build the catch-up URL for the programme spanning [startMillis, endMillis)
     * (both UTC epoch ms). Duration is the programme length in whole minutes
     * (rounded up); most panels ignore it (Dispatcharr recomputes from its own
     * EPG) but a genuine XC 2.x panel queues that many minute-files, so it must
     * be at least the programme length.
     */
    fun build(ctx: Context, startMillis: Long, endMillis: Long): String {
        // (A former correctionMinutes field was removed: never plumbed to
        // any caller, and it was applied in build() but not in
        // rebuildForOffset(), so wiring it up would have made the first
        // seek silently drop the correction.)
        val start = formatStart(startMillis, ctx.panelTimeZoneId)
        val durationMin = ceil((endMillis - startMillis).coerceAtLeast(60_000L) / 60_000.0).toInt()
        val base = ctx.baseUrl.trimEnd('/')
        return "$base/timeshift/${enc(ctx.username)}/${enc(ctx.password)}/" +
            "$durationMin/$start/${ctx.streamId}.ts"
    }

    /**
     * The canonical XC start shape `YYYY-MM-DD:HH-MM` (the shape other IPTV clients
     * colon-dash, minute precision), rendered in the panel's timezone.
     */
    private fun formatStart(epochMillis: Long, zoneId: String): String {
        // TimeZone.getTimeZone never throws; it silently returns GMT for
        // any string it doesn't recognize (panels advertise things like
        // "UTC+3" or misspellings), which reintroduced the wrong-hour bug
        // with no signal. Validate against the real zone table and LOG
        // the fallback so a bad panel string is diagnosable.
        val zone = if (TimeZone.getAvailableIDs().contains(zoneId)) {
            TimeZone.getTimeZone(zoneId)
        } else {
            android.util.Log.w(
                "CatchupUrlBuilder",
                "unrecognized panel timezone '$zoneId'; falling back to UTC",
            )
            TimeZone.getTimeZone("UTC")
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = zone }
        return fmt.format(Date(epochMillis))
    }

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
            // XC credential path segments must keep '@' and '.' literal (common
            // in provider usernames) but URLEncoder turns space into '+'; the
            // path context wants %20. Panels are tolerant of '@'/'.' unescaped.
            .replace("+", "%20")

    /**
     * Rebuild an existing timeshift URL to start [offsetMillis] into the
     * programme, for scrub/seek support (task #136): the timeshift protocol
     * has no in-stream random access beyond the buffered window, but the URL
     * itself encodes the start time, so a seek is a re-tune at
     * programmeStart + offset with the remaining duration. Credentials, base
     * host, and stream id are reused verbatim from [url]; only the
     * {durationMinutes}/{start} path segments are replaced. Returns null when
     * [url] doesn't look like a timeshift URL (caller falls back to a plain
     * player seek).
     */
    fun rebuildForOffset(
        url: String,
        panelTimeZoneId: String,
        programmeStartMillis: Long,
        programmeEndMillis: Long,
        offsetMillis: Long,
    ): String? {
        val m = TIMESHIFT_SEGMENTS.find(url) ?: return null
        val newStartMillis = programmeStartMillis + offsetMillis.coerceAtLeast(0L)
        val durationMin = ceil(
            (programmeEndMillis - newStartMillis).coerceAtLeast(60_000L) / 60_000.0,
        ).toInt()
        val start = formatStart(newStartMillis, panelTimeZoneId)
        return url.replaceRange(
            m.range,
            "/timeshift/${m.groupValues[1]}/${m.groupValues[2]}/$durationMin/$start/",
        )
    }

    /** {user}/{pass}/{durationMin}/{start}/ segments of a timeshift URL. */
    private val TIMESHIFT_SEGMENTS =
        Regex("/timeshift/([^/]+)/([^/]+)/(\\d+)/([^/]+)/")

    /**
     * Derive the source base (scheme://host:port) from a Dispatcharr live stream
     * URL ("{base}/proxy/ts/stream/{uuid}") so the timeshift request uses the
     * SAME LAN/WAN host the live stream was rebuilt for (PlaylistRepository's
     * rebuildLiveStreamUrl). Returns null if the URL isn't a recognizable
     * Dispatcharr proxy URL.
     */
    fun dispatcharrBaseFromStreamUrl(streamUrl: String): String? {
        val marker = "/proxy/"
        val i = streamUrl.indexOf(marker)
        return if (i > 0) streamUrl.substring(0, i) else null
    }
}
