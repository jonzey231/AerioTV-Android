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
 * Xtream Codes `player_api.php` client for VOD + series. Mirrors the iOS
 * `XtreamCodesAPI` (Aerio Networking/StreamingAPIs.swift + XtreamSeriesAPI
 * .swift). Live channels + EPG do NOT go through here -- those use the M3U
 * (`get.php?type=m3u_plus`) and `xmltv.php` in PlaylistRepository, which is
 * exactly what iOS does too (the M3U carries the real playable stream URLs).
 *
 * This client only covers what the M3U can't: VOD movies, series, and the
 * per-series episode list, via the JSON player_api actions.
 *
 * Robustness: Xtream panels are notoriously loose with JSON types --
 * `stream_id` / `series_id` / episode `id` arrive as either an Int or a
 * String depending on the panel, `rating` may be a number or a string,
 * `category_id` is a string, and malformed entries are common. So rather
 * than rely on a strict @Serializable schema (one bad row fails the whole
 * decode), we parse to JsonElement and pull fields tolerantly -- the same
 * defensive per-field decode iOS does in its custom init(from:).
 */
@Singleton
class XtreamCodesApi @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            // VOD / series libraries can be large; give the resource fetch
            // generous headroom while keeping connect snappy.
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
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
    )

    data class XtreamSeries(
        val seriesId: Int,
        val name: String,
        val cover: String?,
        val plot: String?,
        val genre: String?,
        val rating: String?,
        val year: Int?,
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
                    val out = ArrayList<T>()
                    json.decodeToSequence<JsonElement>(
                        response.bodyAsChannel().toInputStream(),
                        DecodeSequenceMode.ARRAY_WRAPPED,
                    ).forEach { el -> (el as? JsonObject)?.let(transform)?.let(out::add) }
                    out
                }
            }
        }.onFailure { Log.w(TAG, "XC $action fetch failed", it) }.getOrElse { emptyList() }
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
