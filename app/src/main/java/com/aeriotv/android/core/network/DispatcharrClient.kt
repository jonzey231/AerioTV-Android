package com.aeriotv.android.core.network

import com.aeriotv.android.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
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
        installSanitizedLogging()
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        install(ContentNegotiation) {
            json(json)
        }
        engine {
            // Phase 131: Concurrency gate at the OkHttp dispatcher. Default
            // OkHttp caps are maxRequests=64 / maxRequestsPerHost=5 - plenty
            // for any single host. Dispatcharr deployments are commonly behind
            // a reverse proxy on a Synology / Raspberry Pi / etc., which sheds
            // connections under bursty parallel load (e.g. the category-
            // enrichment fan-out + EPG grid fetch + channel list refresh + 50
            // Coil logo requests all firing concurrently on a cold launch).
            // That manifests as UI stutter (the "slow / stuttery / laggy"
            // complaint) because each shed connection retries / times out and
            // blocks the calling coroutine. Capping concurrency in one place
            // here keeps every code path well-behaved without scattering
            // Semaphore.withPermit{} across 20+ call sites.
            config {
                dispatcher(
                    Dispatcher().apply {
                        // Cap TOTAL concurrent requests at 4 (covers the rare
                        // case where multiple hosts are in play - LAN + WAN +
                        // logo CDN), and per-host at 2 so any single
                        // Dispatcharr server gets at most two parallel
                        // requests in flight.
                        maxRequests = 4
                        maxRequestsPerHost = 2
                    },
                )
            }
        }
    }

    /**
     * Bare OkHttp client used only by [resolveVODStreamUrl]. Disables both
     * HTTP and HTTPS redirect-following at the engine level so the `Location`
     * header on Dispatcharr's 301 is readable to us. Ktor's wrapper currently
     * hangs when both `followRedirects = false` and the HttpRedirect plugin
     * are configured, so we drop down to OkHttp directly for this one call.
     */
    private val noRedirectOkHttp: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * POST /api/accounts/token/ - exchanges admin username + password for a JWT pair.
     * Mirrors iOS DispatcharrAPI.login (DispatcharrDirectConnect.swift lines 319-378).
     * The returned access token has ~30 min TTL; refresh token ~24 h.
     *
     * Throws [DispatcharrError.InvalidCredentials] on 401/403 (the user typed the
     * wrong password — message carries the Dashboard-vs-XC distinction so the
     * UX shows the actionable copy iOS line 296 spells out),
     * [DispatcharrError.UnexpectedResponse] on a 200 OK whose body isn't JWT
     * shaped (catches the SPA-shell case where the URL points at a non-Dispatcharr
     * host that 200s with HTML), and [DispatcharrError.Transport] on every other
     * network or HTTP failure.
     */
    suspend fun login(baseUrl: String, username: String, password: String): JwtPair {
        val response: HttpResponse = try {
            client.post("${baseUrl.trimEnd('/')}/api/accounts/token/") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                header("User-Agent", dispatcharrUserAgent)
                setBody(LoginRequest(username = username, password = password))
            }
        } catch (t: Throwable) {
            throw DispatcharrError.Transport(
                "Login transport error: ${t.message ?: t::class.simpleName}",
            )
        }
        val code = response.status.value
        if (code == 401 || code == 403) {
            throw DispatcharrError.InvalidCredentials(
                "Invalid username or password. AerioTV uses your Dispatcharr " +
                    "Dashboard password (System -> Users -> Account tab), " +
                    "not your Dispatcharr XC password.",
            )
        }
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("Login transport error: HTTP $code")
        }
        return try {
            response.body()
        } catch (e: SerializationException) {
            throw DispatcharrError.UnexpectedResponse(
                "Server returned an unexpected response shape during login. " +
                    "Verify the URL points at a Dispatcharr 0.23.0 or newer instance.",
            )
        }
    }

    /**
     * POST /api/accounts/token/refresh/ - exchange a refresh token for a fresh
     * access token. Mirrors iOS DispatcharrAPI.refreshAccessToken
     * (DispatcharrDirectConnect.swift lines 384-414). The refresh token is NOT
     * rotated by the server; only a new access token is emitted, so the caller
     * keeps reusing the existing refresh until it itself expires (24h+ idle).
     *
     * Throws [DispatcharrError.RefreshExpired] on 401/403 (refresh token stale —
     * caller should fall back to a fresh login from saved credentials),
     * [DispatcharrError.Transport] on any other failure.
     */
    suspend fun refreshAccessToken(baseUrl: String, refresh: String): String {
        val response: HttpResponse = try {
            client.post("${baseUrl.trimEnd('/')}/api/accounts/token/refresh/") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                header("User-Agent", dispatcharrUserAgent)
                setBody(RefreshRequest(refresh = refresh))
            }
        } catch (t: Throwable) {
            throw DispatcharrError.Transport(
                "Refresh transport error: ${t.message ?: t::class.simpleName}",
            )
        }
        val code = response.status.value
        if (code == 401 || code == 403) {
            throw DispatcharrError.RefreshExpired(
                "Refresh token expired. AerioTV will re-authenticate from saved credentials.",
            )
        }
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("Refresh transport error: HTTP $code")
        }
        val body: RefreshResponse = try {
            response.body()
        } catch (e: SerializationException) {
            throw DispatcharrError.UnexpectedResponse(
                "Refresh returned an unexpected response shape.",
            )
        }
        return body.access
    }

    /**
     * GET /api/accounts/users/me/ with Bearer access token. Used after [login]
     * to extract the user's `api_key` so subsequent calls can run via the
     * stable X-API-Key path. Mirrors iOS DispatcharrAPI.fetchCurrentUser
     * (DispatcharrDirectConnect.swift line 421-435).
     */
    suspend fun fetchCurrentUserApiKey(baseUrl: String, accessToken: String): String {
        val response: HttpResponse = try {
            client.get("${baseUrl.trimEnd('/')}/api/accounts/users/me/") {
                accept(ContentType.Application.Json)
                header("Authorization", "Bearer $accessToken")
                header("User-Agent", dispatcharrUserAgent)
            }
        } catch (t: Throwable) {
            throw DispatcharrError.Transport(
                "Couldn't read user profile: ${t.message ?: t::class.simpleName}",
            )
        }
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("Couldn't read user profile: HTTP ${response.status.value}")
        }
        val me: MeResponse = try {
            response.body()
        } catch (e: SerializationException) {
            throw DispatcharrError.UnexpectedResponse(
                "Server returned an unexpected user profile shape. Verify the URL " +
                    "points at a Dispatcharr 0.23.0 or newer instance.",
            )
        }
        return me.apiKey.takeIf { it.isNotBlank() }
            ?: throw DispatcharrError.UnexpectedResponse("Server did not return an api_key for this user")
    }

    /**
     * Best-effort fetch of the connected user's Dispatcharr account level via
     * GET /api/accounts/users/me/ with the X-API-Key (10 = admin, 1 = standard,
     * 0 = streamer). Only admins (>= 10) can create server-side recordings, so
     * this gates the Record affordances. Returns null on any failure (transport,
     * non-2xx, missing field) so the caller can fall back to the
     * recording-capable default. Mirrors iOS d8aa76b user_level capture.
     */
    suspend fun fetchUserLevel(baseUrl: String, apiKey: String): Int? = runCatching {
        val url = "${baseUrl.trimEnd('/')}/api/accounts/users/me/"
        val response = client.get(url) { applyAuth(apiKey) }
        if (!response.status.isSuccess()) return@runCatching null
        val me: MeResponse = response.body()
        me.userLevel
    }.getOrNull()

    /**
     * Default User-Agent for every Dispatcharr API call. Mirrors iOS
     * DeviceInfo.defaultUserAgent format so the Dispatcharr admin Stats
     * panel can attribute traffic to AerioTV alongside the iOS app:
     *
     *     AerioTV/<versionName> (Android; <Build.MODEL>)
     *
     * Example: `AerioTV/0.1.0 (Android; Pixel 8 Pro)`. Device nickname
     * customisation lands when the Android Appearance / Device-name UI
     * ships (iOS deviceNickname pref equivalent).
     */
    private val dispatcharrUserAgent: String by lazy {
        "AerioTV/${BuildConfig.VERSION_NAME} (Android; ${android.os.Build.MODEL})"
    }

    /**
     * GET /api/core/version/ - cheapest endpoint to verify connectivity + auth.
     * Returns the server version on success; surfaces HTTP status in the exception
     * message on failure so the UI can show a useful diagnostic.
     */
    suspend fun verifyConnection(baseUrl: String, apiKey: String): VersionResponse {
        val url = "${baseUrl.trimEnd('/')}/api/core/version/"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("Connection check failed: HTTP ${response.status.value} ${response.status.description}")
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
     * GET /api/channels/profiles/ - Dispatcharr "channel profiles": named,
     * admin-curated subsets of channels (e.g. "Plex", "Emby"). Each profile
     * object carries the full list of member channel ids under `channels`.
     *
     * Dispatcharr exposes no server-side per-account default profile (the
     * user's /api/accounts/users/me/ `channel_profiles` is empty for admins,
     * and there is no implicit "All" row), so AerioTV lets the user pick a
     * profile per playlist (Settings -> Edit Playlist -> Channel Profile) and
     * resolves the membership against this list client-side. A null selection
     * means "All Channels" (no filter).
     */
    suspend fun listProfiles(baseUrl: String, apiKey: String): List<DispatcharrProfile> =
        fetchListOrResults("${baseUrl.trimEnd('/')}/api/channels/profiles/", apiKey)

    /**
     * GET /api/epg/epgdata/ - EPGData records (one per ingested XMLTV guide
     * channel). Used to resolve a channel's `epg_data_id` FK to the EPGData's
     * `tvg_id`, which is the key /api/epg/grid/ programmes are bucketed under.
     * A channel's own tvg_id (from the M3U/stream) routinely differs from the
     * matched EPGData's tvg_id (e.g. channel "NPO3.nl" -> EPGData
     * "NPO3(NPO3).nl"), so matching the grid by the channel's raw tvg_id misses
     * every channel that Dispatcharr auto-mapped on the server. Resolving this
     * FK is how Dispatcharr's own guide attaches EPG.
     */
    suspend fun listEpgData(baseUrl: String, apiKey: String): List<DispatcharrEpgData> =
        fetchListOrResults("${baseUrl.trimEnd('/')}/api/epg/epgdata/", apiKey)

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
    /**
     * GET /api/epg/programs/<id>/ — rich detail (categories, rating, etc.)
     * for one program. Mirrors iOS DispatcharrAPI.getProgramDetail
     * (StreamingAPIs.swift line 1456). `/api/epg/grid/` deliberately strips
     * the `<category>` payload for perf, so AerioTV calls this endpoint
     * lazily when the user opens the Program Info sheet.
     */
    suspend fun getProgramDetail(baseUrl: String, apiKey: String, programId: Int): DispatcharrProgramDetail {
        val url = "${baseUrl.trimEnd('/')}/api/epg/programs/$programId/"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("Program detail failed: HTTP ${response.status.value}")
        }
        return response.body()
    }

    suspend fun getEpgGrid(baseUrl: String, apiKey: String): List<DispatcharrEpgEntry> {
        val url = "${baseUrl.trimEnd('/')}/api/epg/grid/"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("EPG grid failed: HTTP ${response.status.value} ${response.status.description}")
        }
        val wrapper: EpgGridResponse = response.body()
        return wrapper.data
    }

    /**
     * iOS parity (StreamingAPIs.swift:1358 `getCurrentPrograms`). Fallback
     * for older Dispatcharr deployments that don't have `/api/epg/grid/`
     * (the grid endpoint shipped in v0.7.x): POSTs an empty body to
     * `/api/epg/current-programs/` to get every channel's currently-airing
     * programme in one shot. Used by [PlaylistRepository.loadEpg]'s
     * Dispatcharr branch when the bulk grid request throws.
     *
     * The endpoint accepts ONLY POST -- a GET returns 405 -- and accepts
     * either an empty body (= all channels) or `{"channel_uuids":[...]}`
     * to filter by UUID. We always send empty since the caller wants every
     * channel for the rail / guide paint.
     */
    suspend fun getCurrentPrograms(baseUrl: String, apiKey: String): List<DispatcharrEpgEntry> {
        val url = "${baseUrl.trimEnd('/')}/api/epg/current-programs/"
        val response: HttpResponse = client.post(url) {
            applyAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport(
                "Current programs failed: HTTP ${response.status.value} ${response.status.description}",
            )
        }
        // The endpoint emits either a flat JSON array or a DRF-wrapped
        // `{count, next, previous, results}` envelope depending on
        // Dispatcharr version. fetchListOrResults handles both shapes.
        return fetchListOrResultsPost(url, apiKey, "{}")
    }

    /**
     * iOS parity (StreamingAPIs.swift:1627 `getBulkUpcomingPrograms`).
     * Paginated fetch over `/api/epg/programs/` for the full
     * future-airings list -- one ~5 round-trip batch instead of the
     * 40+ per-channel requests an upcoming-only walk would require.
     *
     * Pages are 1000 entries by default; bails out at [maxPages] so a
     * misconfigured server with millions of EPG rows can't hang the cold
     * launch. A non-DRF flat-array response short-circuits the pagination
     * (older Dispatcharr) and returns the first batch as-is.
     */
    suspend fun getBulkUpcomingPrograms(
        baseUrl: String,
        apiKey: String,
        maxPages: Int = 10,
    ): List<DispatcharrEpgEntry> {
        val all = mutableListOf<DispatcharrEpgEntry>()
        var nextUrl: String? = "${baseUrl.trimEnd('/')}/api/epg/programs/?page_size=1000"
        var pagesLeft = maxPages
        while (nextUrl != null && pagesLeft > 0) {
            pagesLeft -= 1
            val response: HttpResponse = client.get(nextUrl) { applyAuth(apiKey) }
            unauthorizedCheck(response, nextUrl)
            if (!response.status.isSuccess()) break
            val raw: JsonElement = response.body()
            when {
                raw is JsonArray -> {
                    // Flat array = no pagination; absorb + done.
                    raw.forEach { all.add(json.decodeFromJsonElement(serializer<DispatcharrEpgEntry>(), it)) }
                    nextUrl = null
                }
                raw is JsonObject -> {
                    val results = (raw["results"] as? JsonArray) ?: return all
                    results.forEach { all.add(json.decodeFromJsonElement(serializer<DispatcharrEpgEntry>(), it)) }
                    nextUrl = (raw["next"] as? JsonPrimitive)?.contentOrNull
                }
                else -> nextUrl = null
            }
        }
        return all
    }

    /**
     * Tiny variant of [fetchListOrResults] that POSTs the given JSON body
     * (the current-programs endpoint is POST-only). Honours the same
     * flat-array-or-DRF-wrapped acceptance pattern.
     */
    private suspend inline fun <reified T> fetchListOrResultsPost(
        url: String,
        apiKey: String,
        body: String,
    ): List<T> {
        val response: HttpResponse = client.post(url) {
            applyAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport(
                "HTTP ${response.status.value} ${response.status.description} from $url",
            )
        }
        val raw: JsonElement = response.body()
        val array: JsonArray = when {
            raw is JsonArray -> raw
            raw is JsonObject && raw["results"] is JsonArray -> raw["results"]!!.jsonArray
            else -> throw DispatcharrError.UnexpectedResponse(
                "Unexpected response shape from $url: ${raw::class.simpleName}",
            )
        }
        return array.map { json.decodeFromJsonElement(serializer<T>(), it) }
    }

    private suspend inline fun <reified T> fetchListOrResults(
        url: String,
        apiKey: String,
    ): List<T> {
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("HTTP ${response.status.value} ${response.status.description} from $url")
        }
        val raw: JsonElement = response.body()
        val array: JsonArray = when {
            raw is JsonArray -> raw
            raw is JsonObject && raw["results"] is JsonArray -> raw["results"]!!.jsonArray
            else -> throw DispatcharrError.UnexpectedResponse("Unexpected response shape from $url: ${raw::class.simpleName}")
        }
        return array.map { json.decodeFromJsonElement(serializer<T>(), it) }
    }

    /**
     * Promote any 401/403 from an api_key-authenticated call to
     * [DispatcharrError.Unauthorized] so the AuthBroker's retry helper can
     * recognise the case where an admin rotated the user's api_key and
     * trigger silent rebootstrap. Used by every applyAuth() call site.
     */
    private fun unauthorizedCheck(response: HttpResponse, url: String) {
        val code = response.status.value
        if (code == 401 || code == 403) {
            throw DispatcharrError.Unauthorized(
                "Dispatcharr rejected the api_key for $url (HTTP $code). " +
                    "Admin probably rotated it.",
            )
        }
    }

    private inline fun <reified T> serializer(): kotlinx.serialization.KSerializer<T> =
        kotlinx.serialization.serializer()

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth(apiKey: String) {
        accept(ContentType.Application.Json)
        header("X-API-Key", apiKey)
        header("Authorization", "ApiKey $apiKey")
        header("User-Agent", dispatcharrUserAgent)
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
        val url = "${baseUrl.trimEnd('/')}/api/channels/recordings/"
        val response: HttpResponse = client.post(url) {
            applyAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport(
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

    /**
     * PATCH /api/channels/recordings/{id}/ — partial-update a scheduled DVR
     * row. Used by the DVR-tab edit sheet to bump pre-roll / post-roll on an
     * already-scheduled recording without canceling and re-creating it (which
     * would lose the row's id and any custom_properties Dispatcharr added).
     *
     * Currently we only mutate start_time, end_time, and the title/description
     * embedded in custom_properties. The Dispatcharr REST ViewSet accepts
     * either a full PUT or partial PATCH; PATCH is safer because we don't
     * have to round-trip every field the server filled in.
     */
    suspend fun updateRecording(
        baseUrl: String,
        apiKey: String,
        recordingId: Int,
        startIso: String,
        endIso: String,
        title: String,
        description: String,
    ): DispatcharrRecording {
        val customProps = buildJsonObject {
            put("title", JsonPrimitive(title))
            put("description", JsonPrimitive(description))
        }
        val body = buildJsonObject {
            put("start_time", JsonPrimitive(startIso))
            put("end_time", JsonPrimitive(endIso))
            put("custom_properties", customProps)
        }
        val url = "${baseUrl.trimEnd('/')}/api/channels/recordings/$recordingId/"
        val response: HttpResponse = client.patch(url) {
            applyAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport(
                "Recording update failed: HTTP ${response.status.value} ${response.status.description}",
            )
        }
        return response.body()
    }

    /**
     * GET /api/vod/movies/?page_size=100 — first page of VOD movies. iOS
     * walks all pages via fetchAllPages; the Android first cut shows page 1
     * and a "Load more" affordance lands when the user request comes.
     */
    suspend fun getVODMoviesFirstPage(baseUrl: String, apiKey: String): VODMoviesPage =
        getVODMoviesPage("${baseUrl.trimEnd('/')}/api/vod/movies/?page_size=100", apiKey)

    /**
     * Audit task #42: fetch an arbitrary VOD movies page by its absolute URL
     * (typically the `next` cursor returned by the previous page). Same
     * envelope shape, same auth headers. OnDemandViewModel loops on this
     * after the first-page paint to backfill the full library.
     */
    suspend fun getVODMoviesPage(url: String, apiKey: String): VODMoviesPage {
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("VOD movies fetch failed: HTTP ${response.status.value}")
        }
        val raw: JsonElement = response.body()
        return when {
            raw is JsonArray -> VODMoviesPage(
                count = raw.size,
                next = null,
                results = raw.map { json.decodeFromJsonElement(serializer<DispatcharrVODMovie>(), it) },
            )
            raw is JsonObject -> {
                val results = (raw["results"] as? JsonArray)?.map {
                    json.decodeFromJsonElement(serializer<DispatcharrVODMovie>(), it)
                } ?: emptyList()
                val count = (raw["count"]?.toString()?.toIntOrNull()) ?: results.size
                val next = raw["next"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                VODMoviesPage(count = count, next = next, results = results)
            }
            else -> throw IllegalStateException("Unexpected /api/vod/movies/ shape: ${raw::class.simpleName}")
        }
    }

    /**
     * GET /api/vod/series/?page_size=100 — first page of VOD series. Mirrors
     * iOS DispatcharrAPI.getVODSeries (StreamingAPIs.swift:1727), pagination
     * support deferred until the user hits the bottom of the grid.
     */
    suspend fun getVODSeriesFirstPage(baseUrl: String, apiKey: String): VODSeriesPage =
        getVODSeriesPage("${baseUrl.trimEnd('/')}/api/vod/series/?page_size=100", apiKey)

    /** Audit task #42: fetch an arbitrary VOD series page by absolute URL. */
    suspend fun getVODSeriesPage(url: String, apiKey: String): VODSeriesPage {
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("VOD series fetch failed: HTTP ${response.status.value}")
        }
        val raw: JsonElement = response.body()
        return when {
            raw is JsonArray -> VODSeriesPage(
                count = raw.size,
                next = null,
                results = raw.map { json.decodeFromJsonElement(serializer<DispatcharrVODSeries>(), it) },
            )
            raw is JsonObject -> {
                val results = (raw["results"] as? JsonArray)?.map {
                    json.decodeFromJsonElement(serializer<DispatcharrVODSeries>(), it)
                } ?: emptyList()
                val count = (raw["count"]?.toString()?.toIntOrNull()) ?: results.size
                val next = raw["next"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                VODSeriesPage(count = count, next = next, results = results)
            }
            else -> throw IllegalStateException("Unexpected /api/vod/series/ shape: ${raw::class.simpleName}")
        }
    }

    /**
     * GET /api/vod/movies/<id>/provider-info/ — rich-metadata fetch for a
     * single movie. Mirrors iOS DispatcharrAPI.getMovieProviderInfo
     * (StreamingAPIs.swift line 1702). Returns the cast / director / country /
     * trailer URL / backdrop paths that the slim list endpoint doesn't carry.
     *
     * Latency caveat: Dispatcharr server-side throttles refresh to 24h per
     * movie. The first call for a never-visited movie synchronously triggers
     * `refresh_movie_advanced_data` upstream and can take several seconds;
     * subsequent calls within 24h return immediately from cache. Render
     * whatever's available immediately and upgrade fields when this resolves.
     */
    suspend fun getMovieProviderInfo(baseUrl: String, apiKey: String, movieId: Int): DispatcharrVODProviderInfo {
        val url = "${baseUrl.trimEnd('/')}/api/vod/movies/$movieId/provider-info/"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("Movie provider-info failed: HTTP ${response.status.value}")
        }
        return response.body()
    }

    /**
     * GET /api/vod/series/<id>/provider-info/ — same lazy-refresh contract as
     * [getMovieProviderInfo] but for series. Dispatcharr internally calls
     * this `series_info()`; same 24h server-side throttle, same first-call
     * latency note. Mirrors iOS DispatcharrAPI.getSeriesProviderInfo
     * (StreamingAPIs.swift line 1718).
     */
    suspend fun getSeriesProviderInfo(baseUrl: String, apiKey: String, seriesId: Int): DispatcharrVODProviderInfo {
        val url = "${baseUrl.trimEnd('/')}/api/vod/series/$seriesId/provider-info/"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("Series provider-info failed: HTTP ${response.status.value}")
        }
        return response.body()
    }

    /**
     * GET /api/vod/series/<id>/episodes/?page=N&page_size=100. Mirrors iOS
     * DispatcharrAPI.fetchEpisodesPage (StreamingAPIs.swift:2086). Phase
     * 10c-2 fetches page 1 only; long-running shows (One Piece etc.) will
     * paginate properly in a later cut.
     */
    suspend fun getSeriesEpisodesFirstPage(
        baseUrl: String,
        apiKey: String,
        seriesId: Int,
    ): VODEpisodesPage {
        val url = "${baseUrl.trimEnd('/')}/api/vod/series/$seriesId/episodes/?page=1&page_size=100"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("Series episodes fetch failed: HTTP ${response.status.value}")
        }
        val raw: JsonElement = response.body()
        return when {
            raw is JsonArray -> VODEpisodesPage(
                count = raw.size,
                next = null,
                results = raw.map { json.decodeFromJsonElement(serializer<DispatcharrVODEpisode>(), it) },
            )
            raw is JsonObject -> {
                val results = (raw["results"] as? JsonArray)?.map {
                    json.decodeFromJsonElement(serializer<DispatcharrVODEpisode>(), it)
                } ?: emptyList()
                val count = (raw["count"]?.toString()?.toIntOrNull()) ?: results.size
                val next = raw["next"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                VODEpisodesPage(count = count, next = next, results = results)
            }
            else -> throw IllegalStateException("Unexpected episodes response shape: ${raw::class.simpleName}")
        }
    }

    /**
     * Resolves the redirect-bound proxy URL to the session-bound playback URL
     * for an episode. Same mechanism as [resolveVODStreamUrl] — Dispatcharr
     * emits a 301 to a one-time `/proxy/vod/episode/<uuid>/vod_<session>`
     * path that libmpv on Android can't follow itself.
     */
    suspend fun resolveVODEpisodeStreamUrl(
        baseUrl: String,
        apiKey: String,
        episodeUuid: String,
        streamId: Int? = null,
    ): String = withContext(Dispatchers.IO) {
        val base = "${baseUrl.trimEnd('/')}/proxy/vod/episode/$episodeUuid"
        val entry = if (streamId != null) "$base?stream_id=$streamId" else base
        val request = Request.Builder()
            .url(entry)
            .header("X-API-Key", apiKey)
            .header("Authorization", "ApiKey $apiKey")
            .header("Accept", "*/*")
            .build()
        noRedirectOkHttp.newCall(request).execute().use { response ->
            val code = response.code
            if (code in 300..399) {
                val location = response.header("Location") ?: return@use entry
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    val originRoot = Regex("(https?://[^/]+)").find(baseUrl)?.value
                        ?: baseUrl.trimEnd('/')
                    originRoot + location
                }
            } else {
                entry
            }
        }
    }

    /**
     * Returns the unresolved Dispatcharr VOD entry URL for a movie. Mirrors
     * iOS DispatcharrAPI.proxyMovieURL (StreamingAPIs.swift:2105).
     *
     * The server emits a 301 from this URL to a session-bound path
     * (`/proxy/vod/movie/<uuid>/vod_<session>`). Callers that intend to hand
     * the URL to libmpv must first resolve the redirect via
     * [resolveVODStreamUrl] - libmpv on Android does not re-attach custom
     * HTTP headers on a 301 hop, so playback fails before the session URL
     * is reached.
     */
    fun vodMovieUrl(baseUrl: String, movieUuid: String, streamId: Int? = null): String {
        val base = "${baseUrl.trimEnd('/')}/proxy/vod/movie/$movieUuid"
        return if (streamId != null) "$base?stream_id=$streamId" else base
    }

    /**
     * Hits Dispatcharr's VOD entry URL with redirects disabled and returns the
     * resolved session URL from the `Location` header. The session URL doesn't
     * require any further auth headers - it's a one-time playback handle the
     * server emits per request. Passing it to mpv works on the first try.
     *
     * Falls back to the entry URL if the response is unexpectedly non-3xx
     * (e.g. older Dispatcharr builds that serve direct content from the entry
     * path); mpv can still try that URL itself.
     */
    suspend fun resolveVODStreamUrl(
        baseUrl: String,
        apiKey: String,
        movieUuid: String,
        streamId: Int? = null,
    ): String = withContext(Dispatchers.IO) {
        val entry = vodMovieUrl(baseUrl, movieUuid, streamId)
        val request = Request.Builder()
            .url(entry)
            .header("X-API-Key", apiKey)
            .header("Authorization", "ApiKey $apiKey")
            .header("Accept", "*/*")
            .build()
        noRedirectOkHttp.newCall(request).execute().use { response ->
            val code = response.code
            if (code in 300..399) {
                val location = response.header("Location") ?: return@use entry
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    val originRoot = Regex("(https?://[^/]+)").find(baseUrl)?.value
                        ?: baseUrl.trimEnd('/')
                    originRoot + location
                }
            } else {
                entry
            }
        }
    }

    /**
     * POST /api/channels/recordings/{id}/comskip/ — kick off commercial
     * detection / removal on a completed recording. Idempotent server-side
     * so repeated taps from the row context menu are safe (matches iOS
     * StreamingAPIs.swift line 2432 `applyComskip`).
     */
    suspend fun applyComskip(baseUrl: String, apiKey: String, recordingId: Int) {
        val url = "${baseUrl.trimEnd('/')}/api/channels/recordings/$recordingId/comskip/"
        val response: HttpResponse = client.post(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport(
                "Remove Commercials failed: HTTP ${response.status.value} ${response.status.description}",
            )
        }
    }

    /**
     * POST /api/channels/recordings/{id}/stop/ — stops an in-flight recording
     * early, keeping the partial file on disk. Caller should call
     * [deleteRecording] separately if they want the partial gone too
     * (matches iOS StreamingAPIs.swift line 2408 `stopRecording`).
     */
    suspend fun stopRecording(baseUrl: String, apiKey: String, recordingId: Int) {
        val url = "${baseUrl.trimEnd('/')}/api/channels/recordings/$recordingId/stop/"
        val response: HttpResponse = client.post(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport(
                "Stop Recording failed: HTTP ${response.status.value} ${response.status.description}",
            )
        }
    }

    /**
     * Playback URL for a completed Dispatcharr recording. The endpoint is
     * `AllowAny` on the server (no auth headers required), supports HTTP
     * Range, and serves the raw media file — safe to hand straight to
     * MPV. Mirrors iOS recordingPlaybackURL (StreamingAPIs.swift line 2444).
     */
    fun recordingPlaybackUrl(baseUrl: String, recordingId: Int): String =
        "${baseUrl.trimEnd('/')}/api/channels/recordings/$recordingId/file/"

    /** DELETE /api/channels/recordings/{id}/ — cancels a scheduled recording or removes a completed file. */
    suspend fun deleteRecording(baseUrl: String, apiKey: String, recordingId: Int) {
        val url = "${baseUrl.trimEnd('/')}/api/channels/recordings/$recordingId/"
        val response: HttpResponse = client.delete(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport(
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
data class RefreshRequest(
    val refresh: String,
)

@Serializable
data class RefreshResponse(
    val access: String,
)

@Serializable
data class MeResponse(
    val id: Int,
    val username: String,
    @SerialName("api_key")
    val apiKey: String,
    /** Dispatcharr account level: 10 = admin, 1 = standard, 0 = streamer. Only
     *  admins (>= 10) can POST server recordings. Nullable + defaulted so older
     *  servers without the field still parse. */
    @SerialName("user_level")
    val userLevel: Int? = null,
)

@Serializable
data class DispatcharrGroup(
    val id: Int,
    val name: String,
)

/**
 * One row from `/api/channels/profiles/`. `channels` is the list of member
 * channel ids (matching [DispatcharrChannel.id]) used to filter a playlist
 * down to a single profile. Tolerant of older builds that omit `channels`.
 */
@Serializable
data class DispatcharrProfile(
    val id: Int,
    val name: String,
    val channels: List<Int> = emptyList(),
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
    @SerialName("effective_epg_data_id")
    val effectiveEpgDataId: Int? = null,
)

/**
 * One EPGData record from /api/epg/epgdata/. Maps the channel's epg_data_id FK
 * to the `tvg_id` that /api/epg/grid/ buckets programmes under.
 */
@Serializable
data class DispatcharrEpgData(
    val id: Int,
    @SerialName("tvg_id")
    val tvgId: String? = null,
)

@Serializable
data class EpgGridResponse(
    val data: List<DispatcharrEpgEntry>,
)

@Serializable
data class VODMoviesPage(
    val count: Int,
    val next: String?,
    val results: List<DispatcharrVODMovie>,
)

@Serializable
data class VODSeriesPage(
    val count: Int,
    val next: String?,
    val results: List<DispatcharrVODSeries>,
)

@Serializable
data class VODEpisodesPage(
    val count: Int,
    val next: String?,
    val results: List<DispatcharrVODEpisode>,
)

@Serializable
data class DispatcharrVODEpisode(
    val id: Int,
    val uuid: String = "",
    val title: String = "",
    val name: String? = null,
    @SerialName("season_number")
    val seasonNumber: Int? = null,
    @SerialName("episode_number")
    val episodeNumber: Int? = null,
    val plot: String? = null,
    val overview: String? = null,
    val description: String? = null,
    @SerialName("air_date")
    val airDate: String? = null,
    val rating: String? = null,
    @SerialName("duration_secs")
    val durationSecs: Int? = null,
    @SerialName("tmdb_id")
    val tmdbId: String? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    @SerialName("custom_properties")
    val customProperties: JsonObject? = null,
    val streams: List<DispatcharrVODStreamOption> = emptyList(),
) {
    val displayName: String get() = title.ifBlank { name.orEmpty() }
    /** Mirrors iOS DispatcharrVODEpisode plot resolution (StreamingAPIs line 3701):
     *  prefer `description`, fall back to `plot`, then `overview`. */
    val effectivePlot: String? get() = description ?: plot ?: overview
    val firstStreamId: Int? get() = streams.firstOrNull()?.streamId

    /** Episode still / thumbnail from custom_properties. Dispatcharr stores
     *  the upstream-provider thumbnail under `movie_image`; some forks use
     *  `cover` or `image` instead. iOS VODService treats movie_image as the
     *  preferred slot. Fallback chain matches that. */
    val stillImageUrl: String?
        get() = customProperties?.stringField("movie_image")
            ?: customProperties?.stringField("cover")
            ?: customProperties?.stringField("image")

    /** Per-episode director from custom_properties.crew (TMDB-derived). */
    val crew: String?
        get() = customProperties?.stringField("crew")
}

/**
 * Response shape for `/api/vod/movies/<id>/provider-info/` and
 * `/api/vod/series/<id>/provider-info/`. Dispatcharr emits a slightly
 * different flatten for movies vs. series (movie endpoint hoists everything
 * onto the root, series endpoint keeps the rich blob nested under
 * `custom_properties`), so we accept BOTH shapes here and let the getter
 * helpers resolve to the right field regardless of which endpoint produced
 * the payload. Mirrors iOS DispatcharrVODMovieProviderInfo +
 * DispatcharrVODSeriesProviderInfo (StreamingAPIs.swift 3504-3637).
 *
 * Every field is optional + tolerant — an older or forked Dispatcharr build
 * that omits any of them still decodes the rest.
 */
@Serializable
data class DispatcharrVODProviderInfo(
    val description: String? = null,
    val plot: String? = null,
    val overview: String? = null,
    val name: String? = null,
    val year: Int? = null,
    @SerialName("release_date")
    val releaseDate: String? = null,
    val genre: String? = null,
    val director: String? = null,
    val actors: String? = null,
    val cast: String? = null,
    val country: String? = null,
    val rating: String? = null,
    @SerialName("tmdb_id")
    val tmdbId: String? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    @SerialName("youtube_trailer")
    val youtubeTrailer: String? = null,
    @SerialName("duration_secs")
    val durationSecs: Int? = null,
    val age: String? = null,
    @SerialName("backdrop_path")
    val backdropPath: JsonElement? = null,
    val cover: JsonElement? = null,
    @SerialName("cover_big")
    val coverBig: String? = null,
    @SerialName("movie_image")
    val movieImage: String? = null,
    @SerialName("custom_properties")
    val customProperties: JsonObject? = null,
) {
    /** Plot copy. Movies set `plot` at root, series nest it as `description`.
     *  Episodes occasionally use `overview`. */
    val effectivePlot: String?
        get() = plot?.takeIf { it.isNotBlank() }
            ?: description?.takeIf { it.isNotBlank() }
            ?: customProperties?.stringField("plot")
            ?: customProperties?.stringField("description")
            ?: overview?.takeIf { it.isNotBlank() }

    val effectiveCast: String?
        get() = (cast ?: actors)?.takeIf { it.isNotBlank() }
            ?: customProperties?.stringField("cast")
            ?: customProperties?.stringField("actors")

    val effectiveDirector: String?
        get() = director?.takeIf { it.isNotBlank() }
            ?: customProperties?.stringField("director")

    val effectiveCountry: String?
        get() = country?.takeIf { it.isNotBlank() }
            ?: customProperties?.stringField("country")

    val effectiveGenre: String?
        get() = genre?.takeIf { it.isNotBlank() }
            ?: customProperties?.stringField("genre")

    val effectiveTrailer: String?
        get() = youtubeTrailer?.takeIf { it.isNotBlank() }
            ?: customProperties?.stringField("youtube_trailer")

    /** Backdrop URL. Dispatcharr stores backdrops as an array of strings;
     *  forked builds sometimes send a scalar string. We accept both and
     *  return the first non-blank entry. iOS VODService uses the same
     *  preference order. */
    val backdropUrl: String?
        get() {
            val direct = pickBackdrop(backdropPath)
            if (direct != null) return direct
            return pickBackdrop(customProperties?.get("backdrop_path"))
        }

    /** Poster fallback chain — movieImage > coverBig > cover (string-shaped).
     *  When `cover` is a JsonObject (the series endpoint shape) we read its
     *  `url` field instead. */
    val posterUrl: String?
        get() {
            movieImage?.takeIf { it.isNotBlank() }?.let { return it }
            coverBig?.takeIf { it.isNotBlank() }?.let { return it }
            cover?.let { c ->
                if (c is JsonPrimitive && c.isString) return c.content
                if (c is JsonObject) c.stringField("url")?.let { return it }
            }
            return null
        }

    private fun pickBackdrop(element: JsonElement?): String? {
        if (element == null) return null
        if (element is JsonPrimitive && element.isString) {
            return element.content.takeIf { it.isNotBlank() }
        }
        if (element is JsonArray) {
            for (item in element) {
                if (item is JsonPrimitive && item.isString) {
                    val s = item.content
                    if (s.isNotBlank()) return s
                }
            }
        }
        return null
    }
}

@Serializable
data class DispatcharrVODSeries(
    val id: Int,
    val uuid: String = "",
    val name: String = "",
    val title: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val rating: String? = null,
    val year: Int? = null,
    @SerialName("tmdb_id")
    val tmdbId: String? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    val logo: DispatcharrVODLogo? = null,
    /** Server-side group name (e.g. "K-Drama", "Latino", "Stand-Up"). Populated
     *  by OnDemandViewModel from the Xtream get_series_categories id->name
     *  lookup; null for sources that don't expose categories (Dispatcharr
     *  Direct Connect REST). Drives the per-group hide filter exposed via
     *  ManageGroupsSheet on the Series tab. Mirrors iOS VODSeries.categoryName. */
    val categoryName: String? = null,
) {
    val displayName: String get() = name.ifBlank { title.orEmpty() }
    val posterUrl: String? get() = logo?.url
}

@Serializable
data class DispatcharrVODMovie(
    val id: Int,
    val uuid: String,
    val title: String = "",
    val name: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val rating: String? = null,
    val year: Int? = null,
    @SerialName("duration_secs")
    val durationSecs: Int? = null,
    @SerialName("tmdb_id")
    val tmdbId: String? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    val logo: DispatcharrVODLogo? = null,
    val streams: List<DispatcharrVODStreamOption> = emptyList(),
    /** Server-side group name. See DispatcharrVODSeries.categoryName. */
    val categoryName: String? = null,
) {
    val displayName: String get() = title.ifBlank { name.orEmpty() }
    val posterUrl: String? get() = logo?.url
    val firstStreamId: Int? get() = streams.firstOrNull()?.streamId
}

@Serializable
data class DispatcharrVODLogo(
    val url: String? = null,
    @SerialName("cache_url")
    val cacheUrl: String? = null,
)

@Serializable
data class DispatcharrVODStreamOption(
    @SerialName("stream_id")
    val streamId: Int? = null,
    @SerialName("provider_id")
    val providerId: Int? = null,
)

/**
 * Server-reported recording shape from `/api/channels/recordings/`. Wire shape
 * matches iOS `DispatcharrAPI.Recording` (StreamingAPIs.swift:2246) — only
 * `id`, `channel`, `start_time`, `end_time`, and `custom_properties` come back
 * at the top level. **Everything else (status, title, description, file size,
 * comskip flag) lives inside `custom_properties`**, and titles emitted by the
 * server-side scheduler are nested one level deeper under
 * `custom_properties.program.{title,description}`.
 *
 * Earlier Android revisions read `status` from a hypothetical top-level field,
 * which always decoded as null — that left every server recording in the
 * `Unknown` status bucket so it never matched the default Scheduled filter.
 * Re-aligning the getters with iOS (lines 2296-2316).
 */
@Serializable
data class DispatcharrRecording(
    val id: Int,
    val channel: Int? = null,
    @SerialName("start_time")
    val startTime: String,
    @SerialName("end_time")
    val endTime: String,
    @SerialName("task_id")
    val taskId: String? = null,
    @SerialName("custom_properties")
    val customProperties: JsonObject? = null,
) {
    /** Recording status (`scheduled`, `recording`, `in_progress`, `completed`,
     *  `stopped`, `failed`, ...). iOS reads this from custom_properties.status
     *  (line 2297); the top-level field doesn't exist on the wire. */
    val status: String?
        get() = customProperties?.stringField("status")

    /** Display title. Server-scheduled rows nest the program metadata under
     *  `custom_properties.program`; AerioTV's own createRecording call sets
     *  it as a flat key (`custom_properties.title`). Try the iOS-style nested
     *  path first, fall back to flat — matches StreamingAPIs.swift line 2308-2314. */
    val title: String
        get() {
            val program = customProperties?.objectField("program")
            return program?.stringField("title")
                ?: customProperties?.stringField("title")
                ?: ""
        }

    val description: String
        get() {
            val program = customProperties?.objectField("program")
            return program?.stringField("description")
                ?: customProperties?.stringField("description")
                ?: ""
        }

    val comskip: Boolean
        get() = customProperties?.boolField("comskip") ?: false

    val filePath: String?
        get() = customProperties?.stringField("file_path")

    val fileName: String?
        get() = customProperties?.stringField("file_name")

    /**
     * Server-provided playback URL for the recording, relative (e.g.
     * `/api/channels/recordings/<id>/file/`) or already absolute. For
     * finalized recordings this is the raw media file; the new DVR pipeline
     * also emits an HLS playlist for in-progress rows. Mirrors iOS
     * StreamingAPIs.swift line 2347-2348: prefer `output_file_url` (the
     * remuxed final file) then `file_url`. Null on older Dispatcharr builds,
     * in which case callers fall back to the constructed `/file/` path.
     */
    val fileUrl: String?
        get() = customProperties?.stringField("output_file_url")
            ?: customProperties?.stringField("file_url")

    /** Best-effort file-size lookup. Older Dispatcharr builds occasionally
     *  surface this as a flat key on the row; the new pipeline keeps it
     *  inside custom_properties. Try both. */
    val fileSize: Long?
        get() = customProperties?.longField("file_size")
            ?: customProperties?.longField("file_size_bytes")
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.boolField(name: String): Boolean? {
    val prim = this[name] as? JsonPrimitive ?: return null
    return prim.booleanOrNull ?: prim.content.toBooleanStrictOrNull()
}

private fun JsonObject.longField(name: String): Long? {
    val prim = this[name] as? JsonPrimitive ?: return null
    return prim.longOrNull ?: prim.content.toLongOrNull()
}

private fun JsonObject.objectField(name: String): JsonObject? =
    this[name] as? JsonObject

@Serializable
data class DispatcharrEpgEntry(
    // `id` accepted as a JsonElement so the parser tolerates both shapes
    // Dispatcharr emits: real EPG entries carry an Int (e.g. 17284) and the
    // synthetic "Dummy EPG" channels emit a string (e.g.
    // `"dummy-custom-16444-21"`). The Int-only path feeds the
    // /api/epg/programs/<id>/ lazy-category lookup; the string path stays
    // unsupported by detail fetch (no integer to address) and the EPG row
    // just renders without categories until the user picks a real EPG.
    val id: JsonElement? = null,
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
    // Newer Dispatcharr builds occasionally emit a top-level `categories`
    // array on the bulk grid — accept it as a free upgrade. Falls back to
    // the per-program lazy fetch when null.
    val categories: List<String>? = null,
) {
    /** Best-effort coercion of the heterogeneous `id` field to Int. */
    val programIdInt: Int?
        get() = (id as? JsonPrimitive)?.intOrNull
}

/**
 * Response shape for `/api/epg/programs/<id>/` — Dispatcharr's rich-detail
 * fetch that carries the category list the bulk grid intentionally strips.
 * Mirrors iOS DispatcharrProgramDetail (StreamingAPIs.swift `getProgramDetail`,
 * line 1456). Categories are joined with comma to match the EPGProgramme
 * .category contract.
 */
@Serializable
data class DispatcharrProgramDetail(
    val id: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val categories: List<String> = emptyList(),
)
