package com.aeriotv.android.core.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.utils.io.jvm.javaio.toInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * Xtream Codes `player_api.php` client for LIVE channels, VOD and series.
 * Mirrors the iOS `XtreamCodesAPI` (Aerio Networking/StreamingAPIs.swift +
 * XtreamSeriesAPI.swift).
 *
 * Live channels moved here from the `get.php?type=m3u_plus` M3U on
 * 2026-08-10 (see [getLiveStreams] for the measurements). The short version:
 * m3u_plus is a flat dump of live + all VOD + all series -- 538MB on a real
 * provider versus 23.7MB for the same channels here -- and on that panel it
 * carried NO tvg-id at all, so a guide built from it could never match the
 * XMLTV feed. This is also what iOS has always done.
 *
 * EPG programmes still come from `xmltv.php` in PlaylistRepository; this
 * client supplies the `epg_channel_id` they key against.
 *
 * Robustness: Xtream panels are notoriously loose with JSON types --
 * `stream_id` / `series_id` / episode `id` arrive as either an Int or a
 * String depending on the panel, `rating` may be a number or a string,
 * `category_id` is a string, and malformed entries are common. So rather
 * than rely on a strict @Serializable schema (one bad row fails the whole
 * decode), we parse to JsonElement and pull fields tolerantly -- the same
 * defensive per-field decode iOS does in its custom init(from:).
 */
/**
 * The panel answered with something that is not JSON at all -- a plain-text
 * error, an HTML challenge page, a rate-limit or ban notice. Distinct from an
 * empty library, which panels legitimately express as `[]`, `false`, `null` or
 * an object, and which stays lenient.
 */
class XtreamServerError(message: String) : IllegalStateException(message)

@Singleton
class XtreamCodesApi @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            // Mirror the iOS largeLibrarySession (StreamingAPIs.swift): a generous
            // TOTAL budget for a genuinely large VOD / series payload, but a short
            // INACTIVITY (socket) timeout so a dead host still fails fast. The old
            // 60s TOTAL cap truncated a big library mid-download -- the streaming
            // decoder then hit "Unexpected EOF" and On Demand fell back to a slow
            // per-category walk that iOS never does. With an idle-based timeout a
            // slow-but-steady stream completes in one fetch (the iOS behaviour),
            // and a stalled connection still dies within socketTimeout.
            requestTimeoutMillis = 180_000  // iOS timeoutIntervalForResource = 180
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000    // iOS timeoutIntervalForRequest = 30 (idle)
        }
        engine {
            config {
                // Concurrency gate. Dispatcharr's XC bridge needs per-category
                // enumeration (hundreds of small JSON requests for a full VOD +
                // series library), so allow a few more in flight than the
                // ultra-conservative 2 -- self-hosted panels handle 4/host fine,
                // and it roughly halves the full-library fill time. Movie and
                // series fetches are interleaved by the caller so neither starves.
                dispatcher(
                    Dispatcher().apply {
                        maxRequests = 6
                        maxRequestsPerHost = 3
                    },
                )
            }
        }
        // Debug-only sanitized request logging (method + URL + status + timing,
        // no headers/bodies), read with `adb logcat -s AerioNet`. Body logging
        // stays OFF (LogLevel.INFO) so the large VOD / series responses are
        // never buffered for logging -- the heavy fetches stream-decode straight
        // off the channel (see fetchAndMapArray), and INFO-level logging does
        // not touch the body, so it does not defeat that.
        installSanitizedLogging()
    }

    // ─────────────────────────── Models ───────────────────────────

    data class XtreamVod(
        val streamId: Int,
        val name: String,
        val icon: String?,
        val containerExtension: String,
        val rating: String?,
        val plot: String?,
        val genre: String?,
        val year: Int?,
        /** XC `category_id` from the row. The matching display name comes
         *  from getVodCategories() (id->name lookup) since the per-stream
         *  row only carries the id. Null when the panel omits it. */
        val categoryId: String?,
        /** v0.26.0: get_vod_streams carries the YouTube trailer key. youtubeUrl()
         *  in the detail screen builds the watch URL. Null when the panel omits it. */
        val youtubeTrailer: String?,
    )

    data class XtreamSeries(
        val seriesId: Int,
        val name: String,
        val cover: String?,
        val plot: String?,
        val genre: String?,
        val rating: String?,
        val year: Int?,
        /** XC `category_id`. Same semantics as XtreamVod.categoryId. */
        val categoryId: String?,
    )

    data class XtreamEpisode(
        val id: Int,
        val title: String,
        val season: Int,
        val episodeNum: Int?,
        val containerExtension: String,
        val plot: String?,
        val imageUrl: String?,
        val durationSecs: Int?,
    )

    // ─────────────────────────── Fetches ──────────────────────────

    /**
     * VOD streams. Pass [categoryId] to scope to one category. The standard
     * Xtream panel returns the FULL library when [categoryId] is null (the
     * fast path the caller tries first); some bridges -- notably Dispatcharr's
     * XC shim -- 500 or return empty on an unfiltered query and must be walked
     * per-category instead (the caller's fallback).
     */
    suspend fun getVodStreams(
        base: String,
        username: String,
        password: String,
        categoryId: String? = null,
    ): List<XtreamVod> {
        // Decode + map runs off-Main on Dispatchers.IO inside fetchAndMapArray
        // (a full-library enumeration is hundreds of these; decoding on Main
        // would jank the UI / ANR while the On Demand grid fills), one row at a
        // time so the whole library never materializes at once.
        val extra = categoryId?.let { arrayOf("category_id" to it) } ?: emptyArray()
        return fetchAndMapArray(base, username, password, "get_vod_streams", *extra) { o ->
            val id = o.flexInt("stream_id") ?: return@fetchAndMapArray null
            XtreamVod(
                streamId = id,
                name = o.str("name").orEmpty(),
                icon = o.str("stream_icon"),
                containerExtension = o.str("container_extension")?.takeIf { it.isNotBlank() } ?: "mp4",
                rating = o.str("rating"),
                plot = o.str("plot"),
                genre = o.str("genre"),
                year = o.str("releasedate")?.let { yearFrom(it) } ?: o.flexInt("year"),
                // XC `category_id` may arrive as Int or String depending on
                // panel; the helper coerces both to String. Drives the VOD
                // group-hide filter via the id->name map from getVodCategories.
                categoryId = o.str("category_id"),
                youtubeTrailer = o.str("youtube_trailer"),
            )
        }
    }

    /** Series list. [categoryId] behaves exactly like [getVodStreams]. */
    suspend fun getSeries(
        base: String,
        username: String,
        password: String,
        categoryId: String? = null,
    ): List<XtreamSeries> {
        val extra = categoryId?.let { arrayOf("category_id" to it) } ?: emptyArray()
        return fetchAndMapArray(base, username, password, "get_series", *extra) { o ->
            val id = o.flexInt("series_id") ?: return@fetchAndMapArray null
            XtreamSeries(
                seriesId = id,
                name = o.str("name").orEmpty(),
                cover = o.str("cover"),
                plot = o.str("plot"),
                genre = o.str("genre"),
                rating = o.str("rating"),
                year = o.str("releaseDate")?.let { yearFrom(it) }
                    ?: o.str("year")?.let { yearFrom(it) },
                categoryId = o.str("category_id"),
            )
        }
    }

    /**
     * Category ids for VOD / series. Used only by the per-category fallback
     * when an unfiltered [getVodStreams] / [getSeries] comes back empty (the
     * Dispatcharr XC bridge case). We only need the ids; names already arrive
     * on each stream/series row as `category_id`.
     */
    suspend fun getVodCategoryIds(base: String, username: String, password: String): List<String> =
        fetchCategoryIds(base, username, password, "get_vod_categories")

    /** id -> display-name pair, e.g. "5" -> "Action". The group-hide UI needs
     *  the human-readable name; the per-stream row only carries category_id.
     *  Mirrors iOS XtreamCodesAPI.getVODCategories. Cheap (a few dozen rows). */
    data class XtreamCategory(val id: String, val name: String)
    suspend fun getVodCategories(base: String, username: String, password: String): List<XtreamCategory> =
        fetchCategories(base, username, password, "get_vod_categories")
    suspend fun getSeriesCategories(base: String, username: String, password: String): List<XtreamCategory> =
        fetchCategories(base, username, password, "get_series_categories")

    private suspend fun fetchCategories(
        base: String,
        username: String,
        password: String,
        action: String,
    ): List<XtreamCategory> = fetchAndMapArray(base, username, password, action) { o ->
        val id = o.str("category_id") ?: return@fetchAndMapArray null
        val name = o.str("category_name")?.takeIf { it.isNotBlank() } ?: id
        XtreamCategory(id = id, name = name)
    }

    suspend fun getSeriesCategoryIds(base: String, username: String, password: String): List<String> =
        fetchCategoryIds(base, username, password, "get_series_categories")

    private suspend fun fetchCategoryIds(
        base: String,
        username: String,
        password: String,
        action: String,
    ): List<String> = fetchAndMapArray(base, username, password, action) { o ->
        o.str("category_id")
    }

    /**
     * get_series_info returns `{ info: {...}, episodes: { "1": [...], "2": [...] } }`,
     * the episodes object keyed by season number. Flatten to a single list,
     * stamping each episode with its season so the detail screen can group.
     */
    suspend fun getSeriesEpisodes(
        base: String,
        username: String,
        password: String,
        seriesId: Int,
    ): List<XtreamEpisode> {
        val body = fetchText(base, username, password, "get_series_info", "series_id" to seriesId.toString())
            ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val episodesObj = root["episodes"] as? JsonObject ?: return emptyList()
        val out = mutableListOf<XtreamEpisode>()
        for ((seasonKey, seasonEpisodes) in episodesObj) {
            val seasonNum = seasonKey.toIntOrNull() ?: continue
            val list = seasonEpisodes as? JsonArray ?: continue
            for (el in list) {
                val o = el as? JsonObject ?: continue
                val id = o.flexInt("id") ?: continue
                val info = o["info"] as? JsonObject
                out += XtreamEpisode(
                    id = id,
                    title = o.str("title")?.takeIf { it.isNotBlank() }
                        ?: "Episode ${o.flexInt("episode_num") ?: id}",
                    season = seasonNum,
                    episodeNum = o.flexInt("episode_num"),
                    containerExtension = o.str("container_extension")?.takeIf { it.isNotBlank() } ?: "mp4",
                    // Panels split on plot vs overview (Dispatcharr's XC uses
                    // overview); take whichever is present.
                    plot = info?.str("plot") ?: info?.str("overview"),
                    imageUrl = info?.str("movie_image"),
                    durationSecs = info?.str("duration_secs")?.toIntOrNull()
                        ?: info?.flexInt("duration_secs"),
                )
            }
        }
        return out
    }

    /** One live channel as the panel describes it in get_live_streams. */
    data class XtreamLiveStream(
        val streamId: Int,
        /** The panel's own channel number. Null when it omits `num`. */
        val num: Int?,
        val name: String,
        val icon: String,
        /** XMLTV id for this channel, blank when the panel has no guide for it. */
        val epgChannelId: String,
        val categoryId: String,
        /** Archive retention in days; 0 when the channel has no catch-up. */
        val catchupDays: Int,
        /**
         * The panel's own `direct_source` URL when it publishes one.
         * DELIBERATELY NOT PLAYED: AerioTV builds the standard /live/ form on
         * both platforms, and panels routinely put unreachable internal IPs
         * in this field, so honoring it blindly breaks more than it fixes.
         * Carried so the repository can LOG when a panel looks
         * direct_source-only - the one shape where the rebuilt /live/ URL may
         * 404 - which turns a future "channels load, nothing plays" report
         * into a one-line diagnosis.
         */
        val directSource: String,
    )

    /**
     * The full live channel list. THIS is the live-TV source of truth, not the
     * get.php m3u_plus (measured 2026-08-10 against a real provider):
     *
     *  - SIZE. m3u_plus is one flat dump of live + VOD + series: 538MB on that
     *    panel, of which ~95% was movie/episode rows we parsed and immediately
     *    threw away because On Demand loads them from JSON anyway. This
     *    endpoint is 23.7MB for the same 53,599 live channels.
     *  - EPG IDENTITY. That panel emits `tvg-id=""` on EVERY m3u_plus entry
     *    (2000/2000 sampled), so a guide built from the M3U can never match
     *    anything - it logged "EPG loaded: 0 programmes across 53306 channels"
     *    against a perfectly good 32MB xmltv.php. The JSON carries
     *    epg_channel_id (populated on 9,846 of those channels).
     *
     * Streams element-at-a-time via [fetchAndMapArray], so a huge library
     * never materializes as a DOM. Rows without a stream_id are dropped: no
     * id means no playable URL and no catch-up handle.
     */
    suspend fun getLiveStreams(
        base: String,
        username: String,
        password: String,
    ): List<XtreamLiveStream> =
        fetchAndMapArray(base, username, password, "get_live_streams") { o ->
            val id = o.flexInt("stream_id") ?: return@fetchAndMapArray null
            val hasArchive = o.flexInt("tv_archive") == 1
            val days = o.flexInt("tv_archive_duration") ?: 0
            XtreamLiveStream(
                streamId = id,
                num = o.flexInt("num"),
                name = o.str("name")?.trim().orEmpty().ifBlank { "Channel $id" },
                icon = o.str("stream_icon").orEmpty(),
                epgChannelId = o.str("epg_channel_id").orEmpty(),
                categoryId = o.str("category_id").orEmpty(),
                catchupDays = if (hasArchive && days > 0) days else 0,
                directSource = o.str("direct_source").orEmpty(),
            )
        }

    /** id -> display-name for live groups, e.g. "9905" -> "FAVORITES". */
    suspend fun getLiveCategories(base: String, username: String, password: String): List<XtreamCategory> =
        fetchCategories(base, username, password, "get_live_categories")

    /**
     * Live stream URL in the Xtream standard form. VERIFIED byte-identical to
     * what the panel itself serves in its m3u_plus (2026-08-10: the M3U line
     * for stream 39817 was ".../live/<user>/<pass>/39817.ts"), so channels
     * rebuilt from JSON play through exactly the same URL the M3U path used.
     * `.ts` matches the flavour the app prefers everywhere else.
     */
    fun liveStreamUrl(
        base: String,
        username: String,
        password: String,
        streamId: Int,
        ext: String = "ts",
    ): String =
        "${base.trimEnd('/')}/live/${enc(username)}/${enc(password)}/$streamId.${ext.ifBlank { "ts" }}"

    /**
     * `server_info.timezone` from the bare player_api handshake -- the IANA
     * zone the panel formats its EPG strings in AND interprets the timeshift
     * `start` parameter in. Formatting the catch-up start in any other zone
     * plays the wrong hour (the classic client bug), so callers must render
     * programme start times in THIS zone. Null when the panel omits it
     * (callers fall back to UTC; Dispatcharr's XC layer advertises UTC).
     */
    suspend fun getServerTimezone(base: String, username: String, password: String): String? {
        val b = base.trimEnd('/')
        val url = "$b/player_api.php?username=${enc(username)}&password=${enc(password)}"
        val body = runCatching { client.get(url).bodyAsText() }
            .onFailure { Log.w(TAG, "XC server_info fetch failed", it) }
            .getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null
        val serverInfo = root["server_info"] as? JsonObject ?: return null
        return serverInfo.str("timezone")
    }

    // ─────────────────────── Stream URLs ──────────────────────────
    // Xtream standard: VOD -> /movie/<user>/<pass>/<id>.<ext>,
    //                  episode -> /series/<user>/<pass>/<id>.<ext>.

    fun vodStreamUrl(base: String, username: String, password: String, streamId: Int, ext: String): String =
        "${base.trimEnd('/')}/movie/${enc(username)}/${enc(password)}/$streamId.${ext.ifBlank { "mp4" }}"

    fun episodeStreamUrl(base: String, username: String, password: String, episodeId: Int, ext: String): String =
        "${base.trimEnd('/')}/series/${enc(username)}/${enc(password)}/$episodeId.${ext.ifBlank { "mp4" }}"

    // ─────────────────────────── Internals ────────────────────────

    /**
     * Streams a top-level JSON array response and maps each element to [T] as
     * it is decoded, so the whole library never materializes in memory at once.
     * A large VOD / series library is tens of MB; the old path
     * (client.get().bodyAsText() -> Json.parseToJsonElement) made Ktor save()
     * the entire body to a byte array, built a UTF-16 String of it, AND then a
     * full JsonElement DOM -- several times the payload resident at once, which
     * OOM'd the constrained heap on a TV (heapgrowthlimit ~384 MB) and thrashed
     * GC into a multi-second stall (the retry-on-failure made it far worse,
     * re-OOMing each attempt). decodeToSequence pulls ONE array element at a
     * time off the response channel; only the current JsonObject plus the
     * (small) mapped domain list stay resident. [transform] returning null
     * drops that element (a non-object row, or one missing its id). A non-array
     * body (false / null / an object / a 500 HTML page for an empty or
     * unavailable library) throws inside the lazy decode and is swallowed to an
     * empty list.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> fetchAndMapArray(
        base: String,
        username: String,
        password: String,
        action: String,
        vararg extra: Pair<String, String>,
        transform: (JsonObject) -> T?,
    ): List<T> {
        val b = base.trimEnd('/')
        val params = buildString {
            append("username=${enc(username)}&password=${enc(password)}&action=$action")
            extra.forEach { (k, v) -> append("&$k=${enc(v)}") }
        }
        val url = "$b/player_api.php?$params"
        return runCatching {
            withContext(Dispatchers.IO) {
                client.prepareGet(url).execute { response ->
                    val raw = response.bodyAsChannel().toInputStream()
                    // Keep the first bytes so a non-JSON body can be reported
                    // with what the server actually said, then hand the FULL
                    // stream (head + rest) to the streaming decoder.
                    val head = ByteArray(512)
                    var n = 0
                    while (n < head.size) {
                        val r = raw.read(head, n, head.size - n)
                        if (r <= 0) break
                        n += r
                    }
                    val headText = String(head, 0, maxOf(n, 0)).trim()
                    if (!looksLikeJsonBody(headText)) throw notJsonError(action, headText)
                    val full = java.io.SequenceInputStream(
                        java.io.ByteArrayInputStream(head, 0, maxOf(n, 0)),
                        raw,
                    )
                    val out = ArrayList<T>()
                    json.decodeToSequence<JsonElement>(full, DecodeSequenceMode.ARRAY_WRAPPED)
                        .forEach { el -> (el as? JsonObject)?.let(transform)?.let(out::add) }
                    out
                }
            }
        }.onFailure { Log.w(TAG, "XC $action fetch failed", it) }.getOrElse { e ->
            // Transport hiccups and per-row decode noise stay lenient (an empty
            // list, as before). A server that answered with something that is
            // not JSON at all is NOT an empty library and must reach the user:
            // On Demand otherwise renders a blank grid indistinguishable from
            // an account with no movies.
            if (e is XtreamServerError) throw e
            emptyList()
        }
    }

    private suspend fun fetchText(
        base: String,
        username: String,
        password: String,
        action: String,
        vararg extra: Pair<String, String>,
    ): String? {
        val b = base.trimEnd('/')
        val params = buildString {
            append("username=${enc(username)}&password=${enc(password)}&action=$action")
            extra.forEach { (k, v) -> append("&$k=${enc(v)}") }
        }
        val url = "$b/player_api.php?$params"
        return runCatching { client.get(url).bodyAsText() }
            .onFailure { Log.w(TAG, "XC $action fetch failed", it) }
            .getOrNull()
    }

    private fun enc(value: String): String = value.encodeURLParameter()

    /** Pull a field that may be a JSON string or number, as a String. */
    /**
     * Whether a response body is plausibly the JSON these endpoints return.
     *
     * Panels legitimately answer an EMPTY library with `false`, `null` or an
     * object, and that is still treated as "no items". What must NOT be
     * swallowed is a body that is not JSON at all -- a plain-text error, an
     * HTML challenge page, a rate-limit notice -- because [fetchAndMapArray]
     * turns any failure into an empty list, and On Demand then looks exactly
     * like an account with no movies.
     *
     * Real case, 2026-08-10: a provider began answering every endpoint with
     * `[Bot-Protection]: You are banned for repeated abuse`. It STARTS WITH
     * `[`, so "does it begin like an array" is not a sufficient test - the
     * character after the bracket has to be array-ish too.
     */
    private fun looksLikeJsonBody(head: String): Boolean {
        val s = head.trimStart()
        if (s.isEmpty()) return false
        return when (s.first()) {
            '{' -> true
            'f' -> s.startsWith("false")
            'n' -> s.startsWith("null")
            '[' -> {
                val rest = s.drop(1).trimStart()
                rest.isEmpty() || rest.first() in "{]\"" || rest.first().isDigit()
            }
            else -> false
        }
    }

    /** Carries what the server actually said, so the user sees the real reason. */
    private fun notJsonError(action: String, head: String): XtreamServerError {
        val snippet = head.take(200).trim()
        return XtreamServerError(
            if (snippet.isEmpty()) "The server returned an empty response for $action."
            else "The server returned an error for $action: $snippet",
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    /** Pull a field that may be an Int or an int-shaped String. */
    private fun JsonObject.flexInt(key: String): Int? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.intOrNull ?: p.contentOrNull?.trim()?.toIntOrNull()
    }

    /** First 4-digit run in a date / year string -> Int (e.g. "2021-03-04" -> 2021). */
    private fun yearFrom(s: String): Int? =
        Regex("(\\d{4})").find(s)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private companion object {
        const val TAG = "XtreamCodesApi"
    }
}
