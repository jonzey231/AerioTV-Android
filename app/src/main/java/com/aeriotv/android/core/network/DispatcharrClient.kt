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
import io.ktor.client.statement.bodyAsText
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
import kotlinx.serialization.json.JsonNull
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
     * Best-effort fetch of the connected account's ASSIGNED Channel Profile
     * id(s) (/api/accounts/users/me/ `channel_profiles`). A non-empty result is
     * a child-safety filter: the load path keeps only channels in the union of
     * these profiles' memberships. Returns null on ANY failure (transport,
     * non-2xx, decode) so the caller falls back to the persisted snapshot rather
     * than treating a network blip as "no profile = show all" (that would defeat
     * the fail-closed policy). An EMPTY list is a real result (account has no
     * profile = admin = show all). Mirrors iOS 1cc51fc59 self-heal.
     */
    suspend fun fetchCurrentUserProfileIds(baseUrl: String, apiKey: String): List<Int>? = runCatching {
        val url = "${baseUrl.trimEnd('/')}/api/accounts/users/me/"
        val response = client.get(url) { applyAuth(apiKey) }
        if (!response.status.isSuccess()) return@runCatching null
        val me: MeResponse = response.body()
        me.channelProfiles
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
     * Dispatcharr DOES assign Channel Profiles per account (non-admin accounts
     * return them under /api/accounts/users/me/ `channel_profiles`; see
     * [fetchCurrentUserProfileIds]); admins typically have none. AerioTV applies
     * BOTH: the account's assigned profiles (child-safety, FAIL-CLOSED) AND an
     * optional user-chosen profile per playlist (Settings -> Edit Playlist ->
     * Channel Profile, fail-open). This endpoint backs the user-chosen picker;
     * membership is resolved per-id via [listProfiles] (fail-open, manual pick)
     * and [fetchChannelProfileChannelIds] (fail-closed, account filter).
     */
    suspend fun listProfiles(baseUrl: String, apiKey: String): List<DispatcharrProfile> =
        fetchListOrResults("${baseUrl.trimEnd('/')}/api/channels/profiles/", apiKey)

    /**
     * Child-safety FAIL-CLOSED variant of profile-membership resolution. GET
     * /api/channels/profiles/<id>/ and return its `channels` (enabled channel
     * ids). Unlike [listProfiles] (fail-OPEN at its manual-pick call site), this
     * THROWS on any transport/decode error so the account-profile filter caller
     * can let the whole channel load fail rather than leak the full list.
     * Reuses [DispatcharrProfile] (tolerates a missing `channels`). iOS 3eb4ae3d8.
     */
    suspend fun fetchChannelProfileChannelIds(baseUrl: String, apiKey: String, profileId: Int): List<Int> {
        val url = "${baseUrl.trimEnd('/')}/api/channels/profiles/$profileId/"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport(
                "Couldn't read channel profile $profileId: HTTP ${response.status.value}",
            )
        }
        return try {
            response.body<DispatcharrProfile>().channels
        } catch (e: SerializationException) {
            throw DispatcharrError.UnexpectedResponse(
                "Server returned an unexpected channel-profile shape for id $profileId.",
            )
        }
    }

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

    /**
     * GET /api/epg/programs/?tvg_id=<id> -- every programme (past AND future)
     * for one EPG feed, NOT bounded to the live now-window the way the cached
     * grid is. Mirrors iOS getUpcomingPrograms' tvg_id query
     * (StreamingAPIs.swift line 1818). Used to resolve a genre for a COMPLETED
     * DVR recording whose programme already rolled out of the on-disk EPG
     * window: the recording carries no programme id, but it does carry the
     * channel's tvg-id + air window, so we list the feed and overlap-match.
     *
     * Pagination matters here. The endpoint is a DRF ListAPIView ordered by
     * start_time ascending, so a busy channel's multi-day XMLTV feed runs to
     * hundreds of rows and the FIRST page is the OLDEST programmes. A single
     * 50-row page anchored at the feed start therefore routinely fails to
     * reach a recently-completed programme (1-3 days ago), which is exactly
     * how the completed-recording genre pills came up blank on-device. We
     * follow the DRF `next` cursor up to [maxPages] so the recording's window
     * is reachable regardless of where it falls in the ordering. Accepts both
     * the DRF `{results:[...]}` envelope and a flat array (older builds).
     *
     * Note the bulk list serializer strips `<category>` for perf the same way
     * the grid does, so the per-programme `categories` array is usually null
     * on these rows; the caller resolves the genre via the per-id detail
     * endpoint (`getProgramDetail`) using each row's `programIdInt`.
     */
    suspend fun getProgramsByTvgId(
        baseUrl: String,
        apiKey: String,
        tvgId: String,
        pageSize: Int = 200,
        maxPages: Int = 8,
    ): List<DispatcharrEpgEntry> {
        val encoded = java.net.URLEncoder.encode(tvgId, "UTF-8")
        val all = mutableListOf<DispatcharrEpgEntry>()
        var nextUrl: String? =
            "${baseUrl.trimEnd('/')}/api/epg/programs/?tvg_id=$encoded&page_size=$pageSize"
        var pagesLeft = maxPages
        while (nextUrl != null && pagesLeft > 0) {
            pagesLeft -= 1
            val response: HttpResponse = client.get(nextUrl) { applyAuth(apiKey) }
            unauthorizedCheck(response, nextUrl)
            if (!response.status.isSuccess()) {
                if (all.isEmpty()) {
                    throw DispatcharrError.Transport("Programs-by-tvg-id failed: HTTP ${response.status.value}")
                }
                break
            }
            val raw: JsonElement = response.body()
            when {
                raw is JsonArray -> {
                    // Flat array = no pagination; absorb + done.
                    raw.forEach { all.add(json.decodeFromJsonElement(serializer<DispatcharrEpgEntry>(), it)) }
                    nextUrl = null
                }
                raw is JsonObject -> {
                    val rows = (raw["results"] as? JsonArray) ?: JsonArray(emptyList())
                    rows.forEach { all.add(json.decodeFromJsonElement(serializer<DispatcharrEpgEntry>(), it)) }
                    nextUrl = (raw["next"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                }
                else -> nextUrl = null
            }
        }
        return all
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
     * GET /api/vod/movies/?page_size=N&category=<name>|movie -- first page of
     * movies in ONE Dispatcharr VOD category. The movie LIST response omits the
     * item's category (custom_properties.category_id is absent on this server;
     * verified: unfiltered rows carry only actors/director/backdrop_path), so a
     * per-category fetch is the only way to know a movie's real group. Dispatcharr
     * MovieFilter.filter_category matches m3u_relations__category name+type when
     * the value is "name|type"; a bare name is ambiguous across the movie/series
     * category namespaces, so we pin |movie. Mirrors iOS StreamingAPIs.swift
     * moviesPath(category:) (line 2046). Caller walks .next via getVODMoviesPage.
     */
    suspend fun getVODMoviesByCategory(
        baseUrl: String,
        apiKey: String,
        category: String,
        pageSize: Int = 100,
    ): VODMoviesPage {
        val typed = if (category.contains('|')) category else "$category|movie"
        val encoded = java.net.URLEncoder.encode(typed, "UTF-8")
        val url = "${baseUrl.trimEnd('/')}/api/vod/movies/?page_size=$pageSize&category=$encoded"
        return getVODMoviesPage(url, apiKey)
    }

    /** Series counterpart of [getVODMoviesByCategory]; pins |series. Mirrors iOS
     *  StreamingAPIs.swift seriesPath(category:) (line 2061). */
    suspend fun getVODSeriesByCategory(
        baseUrl: String,
        apiKey: String,
        category: String,
        pageSize: Int = 100,
    ): VODSeriesPage {
        val typed = if (category.contains('|')) category else "$category|series"
        val encoded = java.net.URLEncoder.encode(typed, "UTF-8")
        val url = "${baseUrl.trimEnd('/')}/api/vod/series/?page_size=$pageSize&category=$encoded"
        return getVODSeriesPage(url, apiKey)
    }

    /**
     * GET /api/vod/categories/ - every VOD category (movie + series) the
     * server knows, as a plain JSON array (no pagination). The list payloads
     * from /api/vod/movies|series/ carry an item's category ONLY inside
     * `custom_properties.category_id`, so this endpoint is the id -> name
     * join table for the On Demand group filter. A `category_type` query
     * param exists server-side but one unfiltered fetch covers both tabs.
     */
    suspend fun getVODCategories(baseUrl: String, apiKey: String): List<DispatcharrVODCategory> =
        fetchListOrResults("${baseUrl.trimEnd('/')}/api/vod/categories/", apiKey)

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
     * GET /api/channels/channels/{channelId}/streams/ — the ordered list of a
     * Dispatcharr channel's member streams (highest-priority first), each with
     * the quality stats Dispatcharr probed for it. [channelId] is the channel's
     * INTEGER pk (M3UChannel.dispatcharrChannelId). Direct Connect only.
     */
    suspend fun listChannelStreams(
        baseUrl: String,
        apiKey: String,
        channelId: Int,
    ): List<DispatcharrChannelStream> =
        fetchListOrResults(
            "${baseUrl.trimEnd('/')}/api/channels/channels/$channelId/streams/",
            apiKey,
        )

    /**
     * GET /api/m3u/accounts/ -- the playlist's M3U source accounts. Used to map
     * a stream's m3u_account id to a human source name in the Switch Stream
     * picker ("which M3U is this stream from"). Direct Connect only.
     */
    suspend fun listM3uAccounts(
        baseUrl: String,
        apiKey: String,
    ): List<DispatcharrM3uAccount> =
        fetchListOrResults(
            "${baseUrl.trimEnd('/')}/api/m3u/accounts/",
            apiKey,
        )

    /**
     * POST /proxy/ts/change_stream/{channelUuid} — switch the channel's active
     * upstream to [streamId] (a Stream pk from [listChannelStreams]). Dispatcharr
     * swaps the source server-side behind the same /proxy/ts/stream/<uuid> URL;
     * the caller re-primes that URL so ExoPlayer pulls the new source. Keyed by
     * the channel UUID (the proxy path uses UUIDs, like [streamUrl]). Requires an
     * admin-level api_key (Direct Connect authenticates as admin).
     */
    /**
     * POST /proxy/ts/change_stream/{uuid}. Returns the resolved upstream URL the
     * server will swap to (from the response body's `url`), or null if absent.
     *
     * The caller gates its client-side re-prime on /proxy/ts/status reporting this
     * SAME url, NOT on stream_id: when the request lands on a non-owner worker
     * (owner:false) the switch is applied via a Redis event, and that event-apply
     * path on the server updates metadata.url but never metadata.stream_id (see
     * apps/proxy/live_proxy/server.py STREAM_SWITCH handler) -- so status.stream_id
     * stays stale even though the stream really did switch.
     */
    suspend fun changeStream(
        baseUrl: String,
        apiKey: String,
        channelUuid: String,
        streamId: Int,
    ): String? {
        val url = "${baseUrl.trimEnd('/')}/proxy/ts/change_stream/$channelUuid"
        val response: HttpResponse = client.post(url) {
            applyAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("stream_id" to JsonPrimitive(streamId))))
        }
        val respBody = runCatching { response.bodyAsText() }.getOrNull()
        android.util.Log.i(
            "DispatcharrSwitch",
            "change_stream POST $url stream_id=$streamId -> HTTP ${response.status.value} body=$respBody",
        )
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport(
                "Switch Stream failed: HTTP ${response.status.value} ${response.status.description} body=$respBody",
            )
        }
        return respBody?.let { body ->
            runCatching {
                (kotlinx.serialization.json.Json.parseToJsonElement(body) as? JsonObject)
                    ?.get("url")?.let { (it as? JsonPrimitive)?.contentOrNull }
            }.getOrNull()
        }
    }

    /**
     * GET /proxy/ts/status/{channelUuid} — the channel's live status. Returns the
     * currently-active stream's pk (info['stream_id']). NOTE: this is only reliable
     * on first read / after an owner-direct switch; after an event-apply switch
     * (owner:false) the server leaves metadata.stream_id stale, so the caller
     * prefers the in-session selection. Used to radio-mark the active row in the
     * Switch Stream sheet when nothing has been switched yet this session.
     * Returns null when the channel has no active session or the call fails.
     */
    suspend fun getCurrentStreamId(
        baseUrl: String,
        apiKey: String,
        channelUuid: String,
    ): Int? {
        val url = "${baseUrl.trimEnd('/')}/proxy/ts/status/$channelUuid"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            android.util.Log.i("DispatcharrSwitch", "status $channelUuid -> HTTP ${response.status.value}")
            return null
        }
        val obj = runCatching { response.body<JsonElement>() }.getOrNull() as? JsonObject ?: return null
        val sid = (obj["stream_id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        android.util.Log.i("DispatcharrSwitch", "status $channelUuid -> stream_id=$sid")
        return sid
    }

    /**
     * Active upstream URL for a channel, read from /proxy/ts/status. Reliable
     * across both the owner-direct and event-apply switch paths (the server keeps
     * metadata.url current on both), unlike stream_id. Used to confirm a switch
     * landed before the client re-primes its connection.
     */
    suspend fun getCurrentStreamUrl(
        baseUrl: String,
        apiKey: String,
        channelUuid: String,
    ): String? {
        val url = "${baseUrl.trimEnd('/')}/proxy/ts/status/$channelUuid"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            android.util.Log.i("DispatcharrSwitch", "status $channelUuid -> HTTP ${response.status.value}")
            return null
        }
        val obj = runCatching { response.body<JsonElement>() }.getOrNull() as? JsonObject ?: return null
        val u = (obj["url"] as? JsonPrimitive)?.contentOrNull
        val sid = (obj["stream_id"] as? JsonPrimitive)?.contentOrNull
        android.util.Log.i("DispatcharrSwitch", "status $channelUuid -> url=$u stream_id=$sid")
        return u
    }

    /**
     * Constructed playback URL for a Dispatcharr recording's raw media file.
     * The endpoint is `AllowAny` on the server (no auth headers required),
     * supports HTTP Range, and serves the raw media file. For a COMPLETED
     * recording this is the finalized file; for an IN-PROGRESS recording it
     * serves the partial captured so far (plain VOD, not a seekable DVR
     * window). Used as the fallback when the server doesn't report
     * custom_properties.file_url/output_file_url. Mirrors iOS
     * recordingPlaybackURL (StreamingAPIs.swift line 2444).
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
    /** Child-safety: the Channel Profile id(s) ASSIGNED to this account on the
     *  server (e.g. a "Kids" profile = [44]). /api/channels/channels/ ignores
     *  this and returns EVERY channel, so the load path intersects against the
     *  union of these profiles' memberships. Empty (admin, or older servers /
     *  payloads that omit the key) = no account filter. Mirrors iOS 3eb4ae3d8
     *  DispatcharrUser.channelProfiles (decodeIfPresent ?? []). */
    @SerialName("channel_profiles")
    val channelProfiles: List<Int> = emptyList(),
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
 * One member stream of a Dispatcharr channel, from
 * GET /api/channels/channels/{channelId}/streams/ (highest-priority first).
 * [id] is the Stream pk that change_stream switches to. Quality params live in
 * [streamStats], a freeform JSON blob Dispatcharr fills from its ffmpeg probe
 * -- it is null until that source has actually been played, so the typed
 * accessors below degrade to null and the UI falls back to a name-only row.
 * Parsed as a JsonObject (not a typed class) so a number-vs-string probe field
 * can never crash deserialization.
 */
/** One Dispatcharr M3U source account (GET /api/m3u/accounts/). [id] matches a
 *  stream's m3u_account; [name] is the user-facing source name shown in Switch
 *  Stream so the user knows which M3U each alternate comes from. */
@Serializable
data class DispatcharrM3uAccount(
    val id: Int,
    val name: String? = null,
)

@Serializable
data class DispatcharrChannelStream(
    val id: Int,
    val name: String? = null,
    @SerialName("m3u_account")
    val m3uAccount: Int? = null,
    @SerialName("stream_stats")
    val streamStats: JsonObject? = null,
) {
    private fun stat(key: String): String? =
        (streamStats?.get(key) as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    /** e.g. "1920x1080" (lowercase, as Dispatcharr stores it). */
    val resolution: String? get() = stat("resolution")
    val sourceFps: Double? get() = stat("source_fps")?.toDoubleOrNull()
    val videoCodec: String? get() = stat("video_codec")
    /** ffmpeg output bitrate in kbps (the "Output Bitrate" the Dispatcharr UI shows). */
    val outputBitrateKbps: Double? get() = stat("ffmpeg_output_bitrate")?.toDoubleOrNull()
    val audioCodec: String? get() = stat("audio_codec")
}

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
    // v0.26.0: director/actors/cast may arrive as a JSON ARRAY (["A","B"])
    // instead of a string. A typed String? throws SerializationException on the
    // array shape, which nukes the WHOLE provider-info decode (response.body()
    // in getMovie/SeriesProviderInfo has no per-field tolerance) so every field
    // would vanish. JsonElement? accepts both; the effective* getters join
    // arrays via flexString(). Mirrors iOS decodeFlexibleString (4ba8d1aaf).
    val director: JsonElement? = null,
    val actors: JsonElement? = null,
    val cast: JsonElement? = null,
    val country: String? = null,
    val rating: String? = null,
    @SerialName("tmdb_id")
    val tmdbId: String? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    @SerialName("youtube_trailer")
    // Usually a scalar key string; tolerate an array shape for safety.
    val youtubeTrailer: JsonElement? = null,
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
        get() = (cast.flexString() ?: actors.flexString())
            ?: customProperties?.stringField("cast")
            ?: customProperties?.stringField("actors")

    val effectiveDirector: String?
        get() = director.flexString()
            ?: customProperties?.stringField("director")

    val effectiveCountry: String?
        get() = country?.takeIf { it.isNotBlank() }
            ?: customProperties?.stringField("country")

    val effectiveGenre: String?
        get() = genre?.takeIf { it.isNotBlank() }
            ?: customProperties?.stringField("genre")

    val effectiveTrailer: String?
        get() = youtubeTrailer.flexString()
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

/**
 * One row of a VOD category's `m3u_accounts` join table: which M3U account
 * the category came from and whether the admin left it enabled there.
 */
@Serializable
data class DispatcharrVODCategoryRelation(
    val category: Int,
    @SerialName("m3u_account")
    val m3uAccount: Int,
    val enabled: Boolean = true,
)

/**
 * One row from `/api/vod/categories/`. `categoryType` is "movie" or
 * "series"; per-account enablement rides in [m3uAccounts]. The On Demand
 * group filter joins these against each item's
 * `custom_properties.category_id`.
 */
@Serializable
data class DispatcharrVODCategory(
    val id: Int,
    val name: String,
    @SerialName("category_type")
    val categoryType: String = "movie",
    @SerialName("m3u_accounts")
    val m3uAccounts: List<DispatcharrVODCategoryRelation> = emptyList(),
) {
    /** A category the admin disabled on EVERY account shouldn't be offered
     *  as a group; an empty join list (older builds) counts as enabled. */
    val enabledOnAnyAccount: Boolean get() = m3uAccounts.isEmpty() || m3uAccounts.any { it.enabled }
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
     *  lookup, or (Dispatcharr Direct Connect) from the /api/vod/categories/
     *  join on [vodCategoryId]. Drives the per-group hide filter exposed via
     *  ManageGroupsSheet on the Series tab. Mirrors iOS VODSeries.categoryName. */
    val categoryName: String? = null,
    /** Raw `custom_properties` blob. The list endpoint hides the item's
     *  category here (`category_id`), not at the top level, so the object is
     *  kept raw and read lazily via [vodCategoryId]. */
    @SerialName("custom_properties")
    val customPropertiesRaw: JsonObject? = null,
) {
    val displayName: String get() = name.ifBlank { title.orEmpty() }
    val posterUrl: String? get() = logo?.url

    /** `custom_properties.category_id`, Int-or-String tolerant (same wire
     *  variance as DispatcharrProgramDetail's tmdb_id). */
    val vodCategoryId: String?
        get() = (customPropertiesRaw?.get("category_id") as? JsonPrimitive)
            ?.contentOrNull?.takeIf { it.isNotBlank() }
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
    /** YouTube trailer key/URL. Populated for XC movies by toMovie() (the XC
     *  list endpoint carries it in v0.26.0); native Dispatcharr movies surface
     *  the trailer via provider-info.effectiveTrailer, so this stays null for
     *  them. Defaulted + nullable so the list decode is unaffected. */
    @SerialName("youtube_trailer")
    val youtubeTrailer: String? = null,
    val logo: DispatcharrVODLogo? = null,
    val streams: List<DispatcharrVODStreamOption> = emptyList(),
    /** Server-side group name. See DispatcharrVODSeries.categoryName. */
    val categoryName: String? = null,
    /** Raw `custom_properties` blob; see DispatcharrVODSeries.customPropertiesRaw. */
    @SerialName("custom_properties")
    val customPropertiesRaw: JsonObject? = null,
) {
    val displayName: String get() = title.ifBlank { name.orEmpty() }
    val posterUrl: String? get() = logo?.url
    val firstStreamId: Int? get() = streams.firstOrNull()?.streamId

    /** `custom_properties.category_id`, Int-or-String tolerant.
     *  See DispatcharrVODSeries.vodCategoryId. */
    val vodCategoryId: String?
        get() = (customPropertiesRaw?.get("category_id") as? JsonPrimitive)
            ?.contentOrNull?.takeIf { it.isNotBlank() }
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
    // Possible TOP-LEVEL program-id foreign keys. The wire shape varies by
    // Dispatcharr build / how the recording was created: some rows carry an
    // integer FK to the EPG programme at the top level, under one of several
    // names. They are NOT inside custom_properties. We have to declare each
    // explicitly because the decoder runs with ignoreUnknownKeys=true, so an
    // undeclared field would be silently dropped before we could read it. Any
    // that the server omits decode as null. The first non-null one (plus the
    // nested custom_properties.program.id fallback) drives [programId], which
    // the DVR tab feeds to getProgramDetail to resolve a completed
    // recording's category. Stored as JsonElement for Int-or-String tolerance.
    @SerialName("program")
    val programField: JsonElement? = null,
    @SerialName("epg")
    val epgField: JsonElement? = null,
    @SerialName("epg_program")
    val epgProgramField: JsonElement? = null,
    @SerialName("program_id")
    val programIdField: JsonElement? = null,
) {
    /**
     * Resolved EPG programme id for this recording, or null when none is
     * surfaced. This is the ONLY reliable handle for fetching the
     * recording's category: /api/epg/programs/<id>/ (getProgramDetail) is the
     * single endpoint that returns the `<category>` list (the bulk list /
     * grid strip it for perf), and a finalized recording carries no category
     * field of its own anywhere in its JSON.
     *
     * Two places the id can live, checked in order:
     *  1. A TOP-LEVEL integer FK: `program`, `epg`, `epg_program`, or
     *     `program_id`. Only an int counts as an id; a `program` that arrived
     *     as a nested object is handled in (2), not here.
     *  2. The nested `custom_properties.program.id` (server-scheduled rows
     *     that embed the programme object sometimes include its id).
     *
     * Measured on a v0.27.0 server: some completed recordings expose the
     * nested program.id, others expose nothing. Rows with neither resolve
     * null and stay best-effort blank (no pill).
     */
    val programId: Int?
        get() {
            // Top-level integer FKs. A JsonObject `program` (the embedded
            // programme) is intentionally skipped here; its id is read in the
            // nested branch below.
            fun asTopLevelInt(el: JsonElement?): Int? =
                (el as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }
            asTopLevelInt(programField)?.let { return it }
            asTopLevelInt(epgField)?.let { return it }
            asTopLevelInt(epgProgramField)?.let { return it }
            asTopLevelInt(programIdField)?.let { return it }
            // Nested custom_properties.program.id. Tolerate the id arriving
            // as a JSON number or a numeric string.
            val nested = customProperties?.objectField("program")
                ?.get("id") as? JsonPrimitive
            return nested?.let { it.intOrNull ?: it.content.toIntOrNull() }
        }

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

/**
 * v0.26.0 tolerance: a Dispatcharr field that may arrive as a JSON string
 * ("Actor A, Actor B") OR a JSON array (["Actor A","Actor B"]). Returns the
 * primitive string, or an array joined with ", ", null/blank-safe. Mirrors iOS
 * KeyedDecodingContainer.decodeFlexibleString (commit 4ba8d1aaf). Null when the
 * element is absent, JsonNull, an empty/blank/"null" string, or an array with
 * no non-blank entries.
 */
fun JsonElement?.flexString(): String? {
    val el = this ?: return null
    if (el is JsonNull) return null
    if (el is JsonPrimitive) {
        val s = el.contentOrNull?.trim()
        return s?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }
    if (el is JsonArray) {
        val joined = el.mapNotNull { item ->
            (item as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }.joinToString(", ")
        return joined.takeIf { it.isNotBlank() }
    }
    return null
}

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
    /** XMLTV `<programme><icon>`; the bulk grid strips it, only this detail
     *  endpoint carries it. */
    val icon: String? = null,
    /** XMLTV `<image>` list; first non-blank url is the candidate. */
    val images: List<DispatcharrProgramImage> = emptyList(),
    /** Absolute Schedules-Direct poster proxy URL (SD sources only). */
    @SerialName("poster_url")
    val posterUrl: String? = null,
    // tmdb_id arrives as Int or String depending on the source (iOS decodes
    // both, StreamingAPIs.swift:3085); accept any primitive shape.
    @SerialName("tmdb_id")
    val tmdbIdRaw: JsonElement? = null,
) {
    val tmdbId: String?
        get() = (tmdbIdRaw as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Best server-provided artwork, iOS precedence (StreamingAPIs.swift:3098):
     *  poster_url > first images[].url > icon. Null when the programme carries
     *  none (the TMDB-by-title fallback then applies, if enabled). */
    val bestPosterString: String?
        get() {
            posterUrl?.takeIf { it.isNotBlank() }?.let { return it }
            images.firstNotNullOfOrNull { it.url?.takeIf(String::isNotBlank) }?.let { return it }
            return icon?.takeIf { it.isNotBlank() }
        }
}

@Serializable
data class DispatcharrProgramImage(
    val url: String? = null,
)
