package com.aeriotv.android.core.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Detail metadata TMDB can backfill when the Dispatcharr server provides
 * none (bare playlists with no provider-info). All fields pre-joined for
 * direct display: [genres] and [castTop] (first 6 names) are ", "-joined,
 * [director] is the movie crew's Director entries (or `created_by` for tv),
 * [year] is the 4-char release_date / first_air_date prefix, [voteAverage]
 * is pre-formatted "%.1f" with 0-votes dropped.
 */
data class TmdbDetails(
    val overview: String?,
    val genres: String?,
    val castTop: String?,
    val director: String?,
    val year: String?,
    val voteAverage: String?,
    val posterPath: String?,
)

/**
 * One credited person, structured (vs. the pre-joined display strings in
 * [TmdbDetails]) so the detail screens can render headshots and deep-link
 * into the person bio sheet. [role] is the character name for cast,
 * "Director" / "Creator" for crew. [id] stays a String even though TMDB
 * emits an Int, because every caller round-trips it straight back into a
 * `/person/{id}` path.
 */
data class TmdbPerson(
    val id: String,
    val name: String,
    val role: String?,
    val profilePath: String?,
)

/**
 * Structured credits for one title: top-billed [cast] (first 20 entries)
 * plus [directors] (movie crew "Director" rows, or the top-level
 * `created_by` array for tv, where the role reads "Creator" because TMDB
 * tv shows have no per-series director).
 */
data class TmdbCredits(
    val cast: List<TmdbPerson>,
    val directors: List<TmdbPerson>,
)

/**
 * `/person/{id}` profile payload for the bio sheet. [name] is the only
 * field TMDB guarantees; the rest arrive blank-stripped to null.
 */
data class TmdbPersonBio(
    val name: String,
    val biography: String?,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val profilePath: String?,
)

/**
 * Minimal TMDB v3 client, the Android port of iOS `TMDBService`
 * (Aerio Networking/VODService.swift). Used ONLY to (a) validate the user's
 * own free API key, (b) look up a poster image when the playlist provides
 * none, and (c) backfill VOD detail metadata (plot / genre / cast / director
 * / year / rating) the server left blank. Public image CDN, no auth beyond
 * the user's key.
 *
 * Opt-in + off by default + user-supplied key (see [com.aeriotv.android.core
 * .preferences.AppPreferences.programPostersTmdbEnabled]); the no-bundled-keys
 * rule means there is no default/embedded key anywhere.
 *
 * Credential shapes: TMDB accepts the classic v3 API key (sent as an `api_key`
 * query param) OR the newer v4 read-access token (a JWT sent as a Bearer
 * header). The user may paste either, so we detect the JWT shape and route auth
 * accordingly -- a pasted v4 token sent as api_key would silently 401.
 *
 * SECURITY: a v3 key rides in the request URL's query string, so this client
 * deliberately does NOT install request logging (which would print the URL and
 * leak the key). The only breadcrumb logged is the resolved poster path -- never
 * the key.
 */
@Singleton
class TMDBService @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
        // NB: no installSanitizedLogging() here -- see SECURITY note above.
    }

    /**
     * title (lowercased) -> poster path, or "" for a confirmed miss (so a title
     * TMDB has nothing for is not re-queried). Also keyed by "id:movie|tv:<id>"
     * for the exact-id path. Thread-safe; shared across the app.
     */
    private val cache = ConcurrentHashMap<String, String>()

    /** "details:movie|tv:<id>" -> parsed details. Successes only; a failed
     *  request (offline, bad key, 404) stays retryable. */
    private val detailsCache = ConcurrentHashMap<String, TmdbDetails>()

    /** "credits:movie|tv:<id>" -> parsed credits. Successes only, same
     *  retryability rule as [detailsCache]. */
    private val creditsCache = ConcurrentHashMap<String, TmdbCredits>()

    /** "person:<id>" -> parsed bio. Successes only. */
    private val personBioCache = ConcurrentHashMap<String, TmdbPersonBio>()

    /** Drop every cached lookup, including misses recorded under an old key.
     *  Called when the user saves a new key so prior 401-era state can't
     *  outlive the credential that produced it. */
    fun clearCache() {
        cache.clear()
        detailsCache.clear()
        creditsCache.clear()
        personBioCache.clear()
    }

    /** v4 read-access token = a JWT: starts with "eyJ" and has exactly 2 dots. */
    private fun isBearerToken(key: String): Boolean =
        key.startsWith("eyJ") && key.count { it == '.' } == 2

    /** Trailing "(YYYY)" suffix many playlists append to VOD display names. */
    private val trailingYear = Regex("""\(((?:19|20)\d{2})\)\s*$""")

    /**
     * Split "#1 Cheerleader Camp (2010)" into "#1 Cheerleader Camp" + "2010".
     * TMDB's search endpoints choke on the embedded year (it is not part of
     * the canonical title), so it is stripped from the query text and
     * re-applied as a year filter instead. A name that is ONLY "(2010)"
     * keeps its original text rather than sending an empty query. Returns
     * the trimmed title + null when no trailing year is present.
     */
    private fun splitTitleYear(raw: String): Pair<String, String?> {
        val trimmed = raw.trim()
        val match = trailingYear.find(trimmed) ?: return trimmed to null
        val cleaned = trimmed.removeRange(match.range).trim()
        return if (cleaned.isEmpty()) trimmed to null
        else cleaned to match.groupValues[1]
    }

    /**
     * Search-query attempts, in order: the cleaned title, then (when it
     * differs) the title without leading punctuation -- TMDB search also
     * trips on a leading "#" et al., so "#1 Cheerleader Camp" gets a second
     * try as "1 Cheerleader Camp" if the first attempt finds nothing.
     */
    private fun searchAttempts(cleaned: String): List<String> =
        listOf(cleaned, cleaned.trimStart { !it.isLetterOrDigit() }.trim())
            .filter { it.isNotEmpty() }
            .distinct()

    private suspend fun getJsonOrNull(path: String, query: String, key: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bearer = isBearerToken(key)
                val params = buildList {
                    if (query.isNotEmpty()) add(query)
                    if (!bearer) add("api_key=${key.encodeURLParameter()}")
                }
                val url = buildString {
                    append("https://api.themoviedb.org/3")
                    append(path)
                    if (params.isNotEmpty()) {
                        append('?')
                        append(params.joinToString("&"))
                    }
                }
                val resp = client.get(url) {
                    if (bearer) header("Authorization", "Bearer $key")
                }
                if (resp.status == HttpStatusCode.OK) resp.bodyAsText() else null
            }.getOrNull()
        }

    /** Validate a credential by hitting `/configuration`. 200 = valid. */
    suspend fun validateKey(rawKey: String): Boolean {
        val key = rawKey.trim()
        if (key.isEmpty()) return false
        return getJsonOrNull("/configuration", "", key) != null
    }

    private fun imageUrl(path: String, size: String): String =
        "https://image.tmdb.org/t/p/$size$path"

    /** Headshot URL for a person's `profile_path`, null/blank-safe (TMDB
     *  omits the path for most minor cast). w185 is the grid-sized profile
     *  rendition; the bio sheet can ask for a larger one. */
    fun profileImageUrl(path: String?, size: String = "w185"): String? =
        path?.takeIf { it.isNotBlank() }?.let { imageUrl(it, size) }

    /**
     * Poster image URL for a program/VOD title via `/search/multi`
     * (include_adult=false). Returns an image.tmdb.org URL or null. Cached by
     * lowercased ORIGINAL title (misses cached too) so callers' cache
     * semantics are independent of the query cleaning below.
     *
     * The query itself is sanitized: a trailing "(YYYY)" is stripped (and
     * used to prefer the year-matching hit -- /search/multi has no year
     * param, so the year filter is applied to the parsed results instead),
     * and a leading-punctuation-stripped variant is retried when the first
     * attempt finds nothing.
     */
    suspend fun posterUrlForTitle(title: String, rawKey: String, size: String = "w500"): String? {
        val key = rawKey.trim()
        val cacheKey = title.trim().lowercase()
        if (key.isEmpty() || cacheKey.isEmpty()) return null
        cache[cacheKey]?.let { return if (it.isEmpty()) null else imageUrl(it, size) }
        val (cleaned, year) = splitTitleYear(title)
        var sawResponse = false
        var path: String? = null
        for (attempt in searchAttempts(cleaned)) {
            val body = getJsonOrNull(
                "/search/multi",
                "query=${attempt.encodeURLParameter()}&include_adult=false",
                key,
            ) ?: continue
            sawResponse = true
            path = parseSearchPosterPath(body, year)
            if (path != null) break
        }
        // Cache only parsed 200 responses; a failed request (offline, bad key)
        // must stay retryable rather than being poisoned as a confirmed miss.
        if (sawResponse) cache[cacheKey] = path ?: ""
        Log.d(TAG, "search '$title' -> ${path ?: "no match"}")
        return path?.let { imageUrl(it, size) }
    }

    /** Poster by exact TMDB id (no fuzzy match) -- for VOD items with a tmdb_id. */
    suspend fun posterUrlForId(tmdbId: String, isMovie: Boolean, rawKey: String, size: String = "w500"): String? {
        val key = rawKey.trim()
        val id = tmdbId.trim()
        if (key.isEmpty() || id.isEmpty()) return null
        val cacheKey = "id:${if (isMovie) "movie" else "tv"}:$id"
        cache[cacheKey]?.let { return if (it.isEmpty()) null else imageUrl(it, size) }
        val body = getJsonOrNull("/${if (isMovie) "movie" else "tv"}/$id", "", key)
        val path = body?.let { runCatching { json.parseToJsonElement(it).jsonObject["poster_path"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
        // Same rule as posterUrlForTitle: never cache a failed request.
        if (body != null) cache[cacheKey] = path ?: ""
        Log.d(TAG, "id $id (${if (isMovie) "movie" else "tv"}) -> ${path ?: "no match"}")
        return path?.let { imageUrl(it, size) }
    }

    /**
     * Full detail metadata by exact TMDB id, `credits` appended so cast and
     * crew ride along in one request. Cached per id; only a parsed 200 is
     * cached (same rule as the poster lookups).
     */
    suspend fun detailsForId(tmdbId: String, isMovie: Boolean, rawKey: String): TmdbDetails? {
        val key = rawKey.trim()
        val id = tmdbId.trim()
        if (key.isEmpty() || id.isEmpty()) return null
        val kind = if (isMovie) "movie" else "tv"
        val cacheKey = "details:$kind:$id"
        detailsCache[cacheKey]?.let { return it }
        val body = getJsonOrNull("/$kind/$id", "append_to_response=credits", key)
        val details = body?.let { parseDetails(it, isMovie) }
        if (details != null) detailsCache[cacheKey] = details
        Log.d(TAG, "details $id ($kind) -> ${if (details != null) "ok" else "no match"}")
        return details
    }

    /**
     * Detail metadata when no tmdb_id is known: resolve an id via
     * [resolveIdForTitle], then fetch details by id.
     */
    suspend fun detailsForTitle(title: String, isMovie: Boolean, rawKey: String): TmdbDetails? {
        val key = rawKey.trim()
        if (key.isEmpty()) return null
        val id = resolveIdForTitle(title, isMovie, key) ?: return null
        return detailsForId(id, isMovie, key)
    }

    /**
     * Structured cast + directors by exact TMDB id. Same
     * `append_to_response=credits` endpoint as [detailsForId], but parsed
     * into [TmdbPerson] rows (instead of pre-joined display strings) so the
     * detail screens can show headshots and open the person bio sheet.
     * Cached per id; only a parsed 200 is cached.
     */
    suspend fun creditsForId(tmdbId: String, isMovie: Boolean, rawKey: String): TmdbCredits? {
        val key = rawKey.trim()
        val id = tmdbId.trim()
        if (key.isEmpty() || id.isEmpty()) return null
        val kind = if (isMovie) "movie" else "tv"
        val cacheKey = "credits:$kind:$id"
        creditsCache[cacheKey]?.let { return it }
        val body = getJsonOrNull("/$kind/$id", "append_to_response=credits", key)
        val credits = body?.let { parseCredits(it, isMovie) }
        if (credits != null) creditsCache[cacheKey] = credits
        Log.d(TAG, "credits $id ($kind) -> ${if (credits != null) "ok" else "no match"}")
        return credits
    }

    /**
     * Structured credits when no tmdb_id is known: same title-to-id
     * resolution as [detailsForTitle] (shared via [resolveIdForTitle], so
     * the search round-trip happens at most once per title per session
     * regardless of which lookup fired first), then [creditsForId].
     */
    suspend fun creditsForTitle(title: String, isMovie: Boolean, rawKey: String): TmdbCredits? {
        val key = rawKey.trim()
        if (key.isEmpty()) return null
        val id = resolveIdForTitle(title, isMovie, key) ?: return null
        return creditsForId(id, isMovie, key)
    }

    /**
     * `/person/{id}` profile for the bio sheet: biography text, birth and
     * death dates, birthplace, headshot path. Cached per id, successes only.
     */
    suspend fun personBio(personId: String, rawKey: String): TmdbPersonBio? {
        val key = rawKey.trim()
        val id = personId.trim()
        if (key.isEmpty() || id.isEmpty()) return null
        val cacheKey = "person:$id"
        personBioCache[cacheKey]?.let { return it }
        val body = getJsonOrNull("/person/$id", "", key)
        val bio = body?.let { parsePersonBio(it) }
        if (bio != null) personBioCache[cacheKey] = bio
        Log.d(TAG, "person $id -> ${if (bio != null) "ok" else "no match"}")
        return bio
    }

    /**
     * Resolve a display title to a TMDB id via the typed `/search/movie` |
     * `/search/tv` endpoint (which, unlike `/search/multi`, accepts a year
     * filter: `year=` for movies, `first_air_date_year=` for tv). The query
     * gets the same sanitize rules as [posterUrlForTitle] -- trailing
     * "(YYYY)" stripped into the year param, leading-punctuation variant
     * retried, and a final attempt WITHOUT the year constraint in case the
     * playlist's year disagrees with TMDB's. The resolved id is cached in
     * [cache] under a "details-id:" prefix keyed by the ORIGINAL
     * trim+lowercase title ("" = confirmed miss); failed requests are never
     * cached. Shared by [detailsForTitle] and [creditsForTitle].
     */
    private suspend fun resolveIdForTitle(title: String, isMovie: Boolean, key: String): String? {
        val kind = if (isMovie) "movie" else "tv"
        val normalizedTitle = title.trim().lowercase()
        if (normalizedTitle.isEmpty()) return null
        val cacheKey = "details-id:$kind:$normalizedTitle"
        when (val cached = cache[cacheKey]) {
            null -> Unit
            "" -> return null
            else -> return cached
        }
        val (cleaned, year) = splitTitleYear(title)
        val yearParam = year?.let {
            if (isMovie) "&year=$it" else "&first_air_date_year=$it"
        } ?: ""
        // (query text, extra params) attempts in order; the no-year
        // retry only exists when a year was actually extracted.
        val attempts = buildList {
            searchAttempts(cleaned).forEach { add(it to yearParam) }
            if (year != null) add(cleaned to "")
        }
        var sawResponse = false
        var found: String? = null
        for ((attempt, extra) in attempts) {
            val body = getJsonOrNull(
                "/search/$kind",
                "query=${attempt.encodeURLParameter()}&include_adult=false$extra",
                key,
            ) ?: continue
            sawResponse = true
            found = parseFirstSearchId(body)
            if (found != null) break
        }
        if (sawResponse) cache[cacheKey] = found ?: ""
        Log.d(TAG, "details search '$title' -> ${found ?: "no match"}")
        return found
    }

    /** First hit's id from a typed `/search/movie` | `/search/tv` response.
     *  Typed endpoints carry no media_type field, and the type is already
     *  fixed by the path, so no cross-type filtering is needed (or possible:
     *  a tv id fed to /movie/{id} 404s, which is why the endpoint is typed). */
    private fun parseFirstSearchId(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("id")
            ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseDetails(body: String, isMovie: Boolean): TmdbDetails? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        fun field(name: String): String? =
            obj[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        fun names(elements: List<kotlinx.serialization.json.JsonElement>?, limit: Int = Int.MAX_VALUE): String? =
            elements
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.takeIf { n -> n.isNotBlank() } }
                ?.take(limit)
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
        val credits = obj["credits"]?.jsonObject
        TmdbDetails(
            overview = field("overview"),
            genres = names(obj["genres"]?.jsonArray),
            castTop = names(credits?.get("cast")?.jsonArray, limit = 6),
            director = if (isMovie) {
                names(
                    credits?.get("crew")?.jsonArray?.filter {
                        it.jsonObject["job"]?.jsonPrimitive?.contentOrNull == "Director"
                    },
                )
            } else {
                names(obj["created_by"]?.jsonArray)
            },
            year = field(if (isMovie) "release_date" else "first_air_date")?.take(4),
            voteAverage = obj["vote_average"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let { String.format("%.1f", it) },
            posterPath = field("poster_path"),
        )
    }.getOrNull()

    /** [TmdbPerson] from one cast / crew / created_by entry, or null when
     *  the name or id is missing (a row we can neither render nor deep-link).
     *  The id arrives as an Int on the wire; contentOrNull tolerates either
     *  Int or String. */
    private fun parsePerson(element: kotlinx.serialization.json.JsonElement, role: String?): TmdbPerson? {
        val obj = element.jsonObject
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return TmdbPerson(
            id = id,
            name = name,
            role = role,
            profilePath = obj["profile_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseCredits(body: String, isMovie: Boolean): TmdbCredits? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        val credits = obj["credits"]?.jsonObject
        val cast = credits?.get("cast")?.jsonArray
            ?.mapNotNull { entry ->
                parsePerson(
                    entry,
                    entry.jsonObject["character"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                )
            }
            ?.take(20)
            ?: emptyList()
        // Movies credit a Director in the crew; tv has no equivalent job, so
        // the top-level `created_by` array stands in (role reads "Creator").
        val directors = if (isMovie) {
            credits?.get("crew")?.jsonArray
                ?.filter { it.jsonObject["job"]?.jsonPrimitive?.contentOrNull == "Director" }
                ?.mapNotNull { parsePerson(it, "Director") }
                ?: emptyList()
        } else {
            obj["created_by"]?.jsonArray?.mapNotNull { parsePerson(it, "Creator") } ?: emptyList()
        }
        TmdbCredits(cast = cast, directors = directors)
    }.getOrNull()

    private fun parsePersonBio(body: String): TmdbPersonBio? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        fun field(name: String): String? =
            obj[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val name = field("name") ?: return@runCatching null
        TmdbPersonBio(
            name = name,
            biography = field("biography"),
            birthday = field("birthday"),
            deathday = field("deathday"),
            placeOfBirth = field("place_of_birth"),
            profilePath = field("profile_path"),
        )
    }.getOrNull()

    /** Prefer (when [year] is known) a movie/tv hit with a poster released
     *  that year, then any movie/tv hit with a poster, then any hit with a
     *  poster. The year tier is a preference, not a hard filter, so a
     *  playlist year that disagrees with TMDB's still finds art. */
    private fun parseSearchPosterPath(body: String, year: String? = null): String? = runCatching {
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return null
        fun posterOf(o: kotlinx.serialization.json.JsonElement): String? =
            o.jsonObject["poster_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        fun mediaOf(o: kotlinx.serialization.json.JsonElement): String? =
            o.jsonObject["media_type"]?.jsonPrimitive?.contentOrNull
        fun yearOf(o: kotlinx.serialization.json.JsonElement): String? =
            (o.jsonObject["release_date"] ?: o.jsonObject["first_air_date"])
                ?.jsonPrimitive?.contentOrNull?.take(4)
        if (year != null) {
            results.firstOrNull {
                posterOf(it) != null && (mediaOf(it) == "movie" || mediaOf(it) == "tv") &&
                    yearOf(it) == year
            }?.let { return posterOf(it) }
        }
        results.firstOrNull { posterOf(it) != null && (mediaOf(it) == "movie" || mediaOf(it) == "tv") }
            ?.let { return posterOf(it) }
        results.firstOrNull { posterOf(it) != null }?.let { return posterOf(it) }
        null
    }.getOrNull()

    private companion object {
        const val TAG = "TMDBService"
    }
}
