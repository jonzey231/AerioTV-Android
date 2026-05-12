package com.aeriotv.android.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Dispatcharr REST client for Phase 4a. Mirrors iOS DispatcharrAPI
 * (Aerio/Networking/StreamingAPIs.swift) but only the endpoints needed for the
 * channel list: groups + channels + version. EPG, VOD, recordings, and the
 * user/pass JWT flow land in later phases.
 *
 * Auth header strategy (iOS lines 778-829): for API-key mode send BOTH
 *   `X-API-Key: <key>` AND `Authorization: ApiKey <key>`
 * to maximise compatibility with locked-down reverse proxies (the Freyguy1975
 * Synology case in v1.6.22). Bearer / auto-detect lands in Phase 4b alongside
 * the username-and-password JWT flow.
 *
 * Response shapes can be either a flat array OR a paginated wrapper
 *   `{count, next, previous, results: [...]}`
 * depending on whether the `page` query param is present. We don't request
 * pagination, so flat arrays are the common case; the helper handles both.
 */
@Singleton
class DispatcharrClient @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    /**
     * POST /api/accounts/token/ - exchanges admin username + password for a JWT pair.
     * Mirrors iOS DispatcharrAPI.login (DispatcharrDirectConnect.swift lines 319-378).
     * The returned access token has ~30 min TTL; refresh token ~24 h. We don't
     * cache the refresh token here (single-source per session), but the access
     * token feeds the immediate /users/me/ call to extract a usable api_key.
     */
    suspend fun login(baseUrl: String, username: String, password: String): JwtPair {
        val response: HttpResponse = client.post("${baseUrl.trimEnd('/')}/api/accounts/token/") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(LoginRequest(username = username, password = password))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Login failed: HTTP ${response.status.value} ${response.status.description}")
        }
        return response.body()
    }

    /**
     * GET /api/accounts/users/me/ with Bearer access token. Used after [login]
     * to extract the user's `api_key` so subsequent calls can run via the
     * stable X-API-Key path. Mirrors iOS line 534-588 silent-rebootstrap.
     */
    suspend fun fetchCurrentUserApiKey(baseUrl: String, accessToken: String): String {
        val response: HttpResponse = client.get("${baseUrl.trimEnd('/')}/api/accounts/users/me/") {
            accept(ContentType.Application.Json)
            header("Authorization", "Bearer $accessToken")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Couldn't read user profile: HTTP ${response.status.value}")
        }
        val me: MeResponse = response.body()
        return me.apiKey.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Server did not return an api_key for this user")
    }

    /**
     * GET /api/core/version/ - cheapest endpoint to verify connectivity + auth.
     * Returns the server version on success; surfaces HTTP status in the exception
     * message on failure so the UI can show a useful diagnostic.
     */
    suspend fun verifyConnection(baseUrl: String, apiKey: String): VersionResponse {
        val response: HttpResponse = client.get("${baseUrl.trimEnd('/')}/api/core/version/") {
            applyAuth(apiKey)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Connection check failed: HTTP ${response.status.value} ${response.status.description}")
        }
        return response.body()
    }

    /**
     * GET /api/channels/channels/ - all channels for the API-key's user.
     * Per iOS comment (line 1303): omitting the `page` query param disables
     * pagination on Dispatcharr's ChannelViewSet, returning a flat JSON array.
     */
    suspend fun listChannels(baseUrl: String, apiKey: String): List<DispatcharrChannel> =
        fetchListOrResults("${baseUrl.trimEnd('/')}/api/channels/channels/", apiKey)

    /** GET /api/channels/groups/ - channel group names and IDs. */
    suspend fun listGroups(baseUrl: String, apiKey: String): List<DispatcharrGroup> =
        fetchListOrResults("${baseUrl.trimEnd('/')}/api/channels/groups/", apiKey)

    /**
     * GET /api/epg/grid/ - bulk EPG window covering roughly -1h to +24h. iOS uses
     * this as the universal EPG source for Dispatcharr-backed playlists; the
     * `/output/epg` XMLTV path is gated by Dispatcharr 0.23+ LAN-only policy and
     * mostly redundant when this endpoint is available (EPGGuideView.swift:641-660).
     *
     * Response shape:
     *   {"data": [{id, start_time (ISO 8601 UTC), end_time, title, sub_title,
     *              description, tvg_id, season, episode, is_new, is_live, ...}, ...]}
     *
     * `<category>` is intentionally stripped from this bulk endpoint for perf;
     * categories are lazy-loaded per programme via /api/epg/programs/<id>/ when the
     * user opens ProgramInfoView (Phase 6+ in the Android port).
     */
    suspend fun getEpgGrid(baseUrl: String, apiKey: String): List<DispatcharrEpgEntry> {
        val response: HttpResponse = client.get("${baseUrl.trimEnd('/')}/api/epg/grid/") {
            applyAuth(apiKey)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("EPG grid failed: HTTP ${response.status.value} ${response.status.description}")
        }
        val wrapper: EpgGridResponse = response.body()
        return wrapper.data
    }

    private suspend inline fun <reified T> fetchListOrResults(
        url: String,
        apiKey: String,
    ): List<T> {
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value} ${response.status.description} from $url")
        }
        val raw: JsonElement = response.body()
        val array: JsonArray = when {
            raw is JsonArray -> raw
            raw is JsonObject && raw["results"] is JsonArray -> raw["results"]!!.jsonArray
            else -> throw IllegalStateException("Unexpected response shape from $url: ${raw::class.simpleName}")
        }
        return array.map { json.decodeFromJsonElement(serializer<T>(), it) }
    }

    private inline fun <reified T> serializer(): kotlinx.serialization.KSerializer<T> =
        kotlinx.serialization.serializer()

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth(apiKey: String) {
        accept(ContentType.Application.Json)
        header("X-API-Key", apiKey)
        header("Authorization", "ApiKey $apiKey")
    }

    /**
     * Returns the canonical playback URL for a Dispatcharr channel via the
     * proxy. iOS uses `/proxy/ts/stream/<uuid>` (line 2128). UUID is preferred
     * over numeric id because Dispatcharr can apply failover on the UUID path.
     */
    fun streamUrl(baseUrl: String, channelUuid: String): String =
        "${baseUrl.trimEnd('/')}/proxy/ts/stream/$channelUuid"

    /**
     * POST /api/channels/recordings/ — schedules a server-side DVR recording.
     * Mirrors iOS DispatcharrAPI.createRecording (StreamingAPIs.swift:2334).
     *
     * The iOS implementation supports an `applyServerOffsets` mode where the
     * caller embeds a `program` dict and lets Dispatcharr re-apply its own
     * pre/post-roll defaults. Phase 9a always passes the pre-rolled times
     * directly so the math is obvious; Phase 9b can revisit when local
     * recordings need parity.
     */
    suspend fun createRecording(
        baseUrl: String,
        apiKey: String,
        channelId: Int,
        startIso: String,
        endIso: String,
        title: String,
        description: String,
        comskip: Boolean,
    ): DispatcharrRecording {
        val customProps = buildJsonObject {
            put("title", JsonPrimitive(title))
            put("description", JsonPrimitive(description))
            if (comskip) put("comskip", JsonPrimitive(true))
        }
        val body = buildJsonObject {
            put("channel", JsonPrimitive(channelId))
            put("start_time", JsonPrimitive(startIso))
            put("end_time", JsonPrimitive(endIso))
            put("custom_properties", customProps)
        }
        val response: HttpResponse = client.post("${baseUrl.trimEnd('/')}/api/channels/recordings/") {
            applyAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "Recording create failed: HTTP ${response.status.value} ${response.status.description}",
            )
        }
        return response.body()
    }

    /**
     * GET /api/channels/recordings/ — returns every recording the active user
     * can see. Client filters by status (scheduled / recording / completed /
     * failed / stopped) for the DVR tab filter chips.
     */
    suspend fun listRecordings(baseUrl: String, apiKey: String): List<DispatcharrRecording> =
        fetchListOrResults("${baseUrl.trimEnd('/')}/api/channels/recordings/", apiKey)

    /** DELETE /api/channels/recordings/{id}/ — cancels a scheduled recording or removes a completed file. */
    suspend fun deleteRecording(baseUrl: String, apiKey: String, recordingId: Int) {
        val response: HttpResponse = client.delete("${baseUrl.trimEnd('/')}/api/channels/recordings/$recordingId/") {
            applyAuth(apiKey)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "Recording delete failed: HTTP ${response.status.value} ${response.status.description}",
            )
        }
    }

    /**
     * Logo URL for a channel that has a logoID. Dispatcharr serves through
     * `/api/channels/logos/<id>/cache/`. AllowAny on the server, no auth header
     * required (matches Coil's anonymous fetch).
     */
    fun logoUrl(baseUrl: String, logoId: Int): String =
        "${baseUrl.trimEnd('/')}/api/channels/logos/$logoId/cache/"
}

@Serializable
data class VersionResponse(
    val version: String,
    val timestamp: String? = null,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class JwtPair(
    val access: String,
    val refresh: String,
)

@Serializable
data class MeResponse(
    val id: Int,
    val username: String,
    @SerialName("api_key")
    val apiKey: String,
)

@Serializable
data class DispatcharrGroup(
    val id: Int,
    val name: String,
)

@Serializable
data class DispatcharrChannel(
    val id: Int,
    val name: String,
    val uuid: String? = null,
    @SerialName("channel_number")
    val channelNumber: Double? = null,
    @SerialName("logo_id")
    val logoId: Int? = null,
    @SerialName("channel_group_id")
    val channelGroupId: Int? = null,
    @SerialName("tvg_id")
    val tvgId: String? = null,
    @SerialName("epg_data_id")
    val epgDataId: Int? = null,
)

@Serializable
data class EpgGridResponse(
    val data: List<DispatcharrEpgEntry>,
)

/**
 * Server-reported recording shape from /api/channels/recordings/. Mirrors
 * iOS `Recording` (Models.swift:680) on the wire fields. `custom_properties`
 * is a free-form bag — title, description, comskip flag, and (when present)
 * the program metadata block live in there per the iOS createRecording call.
 */
@Serializable
data class DispatcharrRecording(
    val id: Int,
    val channel: Int? = null,
    @SerialName("start_time")
    val startTime: String,
    @SerialName("end_time")
    val endTime: String,
    val status: String? = null,
    @SerialName("file_size")
    val fileSize: Long? = null,
    @SerialName("custom_properties")
    val customProperties: JsonObject? = null,
) {
    val title: String
        get() = customProperties?.get("title")?.toString()?.trim('"').orEmpty()
    val description: String
        get() = customProperties?.get("description")?.toString()?.trim('"').orEmpty()
    val comskip: Boolean
        get() = customProperties?.get("comskip")?.toString() == "true"
}

@Serializable
data class DispatcharrEpgEntry(
    // NB: `id` is intentionally omitted. Real EPG entries carry a numeric `id`,
    // but Dispatcharr's "Dummy EPG" channels emit string ids like
    // `"dummy-custom-16444-21"`, which kotlinx-serialization can't coerce into
    // a single Kotlin numeric type. We don't need the id until per-programme
    // category lazy-load (Phase 6+), at which point we can switch to JsonElement
    // or split the model into typed/untyped variants.
    @SerialName("start_time")
    val startTime: String,
    @SerialName("end_time")
    val endTime: String,
    val title: String = "",
    @SerialName("sub_title")
    val subTitle: String? = null,
    val description: String = "",
    @SerialName("tvg_id")
    val tvgId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("is_new")
    val isNew: Boolean = false,
    @SerialName("is_live")
    val isLive: Boolean = false,
    @SerialName("is_premiere")
    val isPremiere: Boolean = false,
    @SerialName("is_finale")
    val isFinale: Boolean = false,
)
