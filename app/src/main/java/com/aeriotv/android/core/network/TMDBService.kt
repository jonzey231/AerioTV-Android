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
 * One resolved title's TMDB art, the unit the persistent art cache stores
 * (Apple parity: TMDBArtCache.Entry). [poster] / [backdrop] are TMDB relative
 * paths, never URLs, so a size change costs nothing. Empty [poster] marks a
 * confirmed miss; a transport failure returns null instead and stays
 * retryable.
 */
data class TmdbArtEntry(
    val tmdbId: String,
    val poster: String,
    val backdrop: String,
    val overview: String?,
    val at: Long,
)

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
    /** Landscape art for hero cards (media-center redesign). */
    val backdropPath: String? = null,
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
 * One title in a person's "Known For" strip: display [title] plus the
 * [posterPath] for its artwork (TMDB drops the path for obscure titles, so
 * those entries are filtered out before display). [isMovie] mirrors the
 * row's `media_type` ("movie" -> true, "tv" -> false) so a tile can be
 * routed to the matching library detail screen; rows with any other or
 * absent media_type are skipped at parse time.
 */
data class TmdbKnownForItem(
    val id: String,
    val title: String,
    val posterPath: String?,
    val isMovie: Boolean,
)

/**
 * One `/search/person` hit for cast & crew search (iOS
 * TMDBService.PersonHit): the person id, display name and TMDB popularity
 * (most popular first; the caller keeps the one with the most library hits).
 */
data class TmdbPersonHit(
    val id: String,
    val name: String,
    val popularity: Float,
)

/**
 * `/person/{id}` profile payload for the bio sheet. [name] is the only
 * field TMDB guarantees; the rest arrive blank-stripped to null. [knownFor]
 * is the person's most popular credits, parsed from the `combined_credits`
 * block appended to the same request and ordered like TMDB's own "Known For"
 * row (most popular first).
 */
data class TmdbPersonBio(
    val name: String,
    val biography: String?,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val profilePath: String?,
    val knownFor: List<TmdbKnownForItem> = emptyList(),
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

    /**
     * One typed search (movie or tv) for the persistent art cache, mirroring
     * Apple's TMDBService.lookupArt (VODService.swift:1182). A trailing year
     * in the title becomes a `year` / `first_air_date_year` filter instead of
     * query text. Returns null on a transport failure, a non-200 or a 429 (the
     * caller backs off and retries later) and an entry with empty paths on a
     * confirmed miss, which the cache remembers for 30 days.
     */
    suspend fun lookupArt(title: String, isMovie: Boolean, rawKey: String): TmdbArtEntry? {
        val key = rawKey.trim()
        if (key.isEmpty()) return null
        val (cleaned, year) = splitTitleYear(title)
        if (cleaned.isBlank()) return TmdbArtEntry("", "", "", null, System.currentTimeMillis())
        val query = buildList {
            add("query=${cleaned.encodeURLParameter()}")
            add("include_adult=false")
            year?.let { add((if (isMovie) "year=" else "first_air_date_year=") + it) }
        }.joinToString("&")
        val body = getJsonOrNull(if (isMovie) "/search/movie" else "/search/tv", query, key) ?: return null
        return parseArt(body)
    }

    private fun parseArt(body: String): TmdbArtEntry? = runCatching {
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray?.map { it.jsonObject }
            ?: emptyList()
        fun field(o: kotlinx.serialization.json.JsonObject, name: String): String? =
            o[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val hit = results.firstOrNull { field(it, "poster_path") != null } ?: results.firstOrNull()
        TmdbArtEntry(
            tmdbId = hit?.let { field(it, "id") } ?: "",
            poster = hit?.let { field(it, "poster_path") } ?: "",
            backdrop = hit?.let { field(it, "backdrop_path") } ?: "",
            overview = hit?.let { field(it, "overview") },
            at = System.currentTimeMillis(),
        )
    }.getOrNull()

    /** Validate a credential by hitting `/configuration`. 200 = valid. */
    suspend fun validateKey(rawKey: String): Boolean {
        val key = rawKey.trim()
        if (key.isEmpty()) return false
        return getJsonOrNull("/configuration", "", key) != null
    }

    private fun imageUrl(path: String, size: String): String =
        "https://image.tmdb.org/t/p/$size$path"

    /** Public image URL for a TMDB path (hero backdrops use w1280). */
    fun imageUrlFor(path: String, size: String = "w1280"): String = imageUrl(path, size)

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
        // combined_credits comes back in the same response, so the "Known For"
        // strip costs no extra round-trip.
        val body = getJsonOrNull("/person/$id", "append_to_response=combined_credits", key)
        val bio = body?.let { parsePersonBio(it) }
        if (bio != null) personBioCache[cacheKey] = bio
        Log.d(TAG, "person $id -> ${if (bio != null) "ok" else "no match"}")
        return bio
    }

    /**
     * People matching a typed name (iOS TMDBService.searchPeople): TMDB's
     * first `/search/person` page, most popular first, requiring the query to
     * appear in the name (a two-letter query would otherwise hit random
     * people). The caller tries the top few and keeps the one with the most
     * films in the library: "boyle" alone resolved to Peter Boyle (0 in
     * library) over Danny Boyle (28 Years Later is there), Logan 2026-09-04.
     * Queries shorter than 3 characters return nothing.
     */
    suspend fun searchPeople(query: String, rawKey: String, limit: Int = 5): List<TmdbPersonHit> {
        val key = rawKey.trim()
        val q = query.trim()
        if (key.isEmpty() || q.length < 3) return emptyList()
        val body = getJsonOrNull(
            "/search/person",
            "query=${q.encodeURLParameter()}&include_adult=false",
            key,
        ) ?: return emptyList()
        val needle = q.lowercase()
        return runCatching {
            json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { element ->
                val o = element.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                if (!name.lowercase().contains(needle)) return@mapNotNull null
                val popularity = o["popularity"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                TmdbPersonHit(id, name, popularity)
            }.sortedByDescending { it.popularity }.take(limit)
        }.getOrDefault(emptyList())
    }

    /**
     * Every film (or show) a person acted in or crewed on, deduped by id
     * (iOS TMDBService.personCredits): `/person/{id}/movie_credits` |
     * `/person/{id}/tv_credits`, cast + crew. Poster-less rows are kept: the
     * caller matches against the library, which supplies its own artwork.
     * The media type is fixed by the endpoint, so every row inherits
     * [isMovie].
     */
    suspend fun personCredits(personId: String, isMovie: Boolean, rawKey: String): List<TmdbKnownForItem> {
        val key = rawKey.trim()
        val id = personId.trim()
        if (key.isEmpty() || id.isEmpty()) return emptyList()
        val path = if (isMovie) "/person/$id/movie_credits" else "/person/$id/tv_credits"
        val body = getJsonOrNull(path, "", key) ?: return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val rows = root["cast"]?.jsonArray.orEmpty() + root["crew"]?.jsonArray.orEmpty()
            rows.mapNotNull { element ->
                val o = element.jsonObject
                val creditId = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val title = (o["title"] ?: o["name"])?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val poster = o["poster_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                TmdbKnownForItem(creditId, title, poster, isMovie)
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
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
    /** Public wrapper over [resolveIdForTitle] for callers that hold a raw
     *  (untrimmed) key, e.g. the Related strip resolving a title with no
     *  stored TMDB id. */
    suspend fun resolveIdForTitleOrNull(title: String, isMovie: Boolean, rawKey: String): String? {
        val key = rawKey.trim()
        if (key.isEmpty()) return null
        return resolveIdForTitle(title, isMovie, key)
    }

    /**
     * `/movie/{id}/recommendations` | `/tv/{id}/recommendations` (page 1)
     * for the Related strip. Rows are mapped like [parseKnownFor] (title or
     * name, poster path, id); the media type is fixed by the endpoint, so
     * every row inherits [isMovie]. Rows without a title are dropped, rows
     * without a poster are kept (the library card supplies its own art).
     */
    suspend fun recommendations(tmdbId: String, isMovie: Boolean, rawKey: String): List<TmdbKnownForItem> {
        val key = rawKey.trim()
        val id = tmdbId.trim()
        if (key.isEmpty() || id.isEmpty()) return emptyList()
        val kind = if (isMovie) "movie" else "tv"
        val body = getJsonOrNull("/$kind/$id/recommendations", "page=1", key) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { element ->
                val o = element.jsonObject
                val recId = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val title = (o["title"] ?: o["name"])?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val poster = o["poster_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val rowIsMovie = when (o["media_type"]?.jsonPrimitive?.contentOrNull) {
                    "movie" -> true
                    "tv" -> false
                    else -> isMovie
                }
                TmdbKnownForItem(recId, title, poster, rowIsMovie)
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

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
            backdropPath = field("backdrop_path"),
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
            knownFor = parseKnownFor(obj),
        )
    }.getOrNull()

    /**
     * The person's "Known For" row, parsed from the `combined_credits.cast`
     * block appended to the person request. TMDB's own person page hides
     * talk-show / reality cameos and one-off guest spots from this row, so a
     * raw popularity sort (which floats daily talk shows to the top) does not
     * match it. We reproduce the intent by dropping:
     *   - Talk (10767), News (10763), Reality (10764) genre entries,
     *   - "Self" appearances (the person playing themselves),
     *   - tv credits with fewer than 3 episodes (guest spots),
     *   - movie credits billed past order 8 (bit parts),
     *   - rows whose `media_type` is anything but "movie" / "tv" (or absent);
     *     [TmdbKnownForItem.isMovie] needs an unambiguous answer so a tile
     *     can open the right library detail screen,
     * then ordering the survivors by popularity and capping at 8 (one TV
     * row). Movie rows carry `title`, tv rows carry `name`. Returns an empty
     * list when the block is absent rather than failing the whole bio parse.
     */
    private fun parseKnownFor(personObj: kotlinx.serialization.json.JsonObject): List<TmdbKnownForItem> {
        val cast = personObj["combined_credits"]?.jsonObject?.get("cast")?.jsonArray
            ?: return emptyList()
        val excludedGenres = setOf(10767, 10763, 10764)
        return cast.mapNotNull { element ->
            val o = element.jsonObject
            fun int(name: String): Int? =
                o[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = (o["title"] ?: o["name"])?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = o["poster_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val genres = o["genre_ids"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                ?.toSet().orEmpty()
            if (genres.any { it in excludedGenres }) return@mapNotNull null
            val character = o["character"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            if (character == "self" || character.startsWith("self ") ||
                character == "herself" || character == "himself"
            ) {
                return@mapNotNull null
            }
            int("episode_count")?.let { if (it < 3) return@mapNotNull null }
            int("order")?.let { if (it > 8) return@mapNotNull null }
            val isMovie = when (o["media_type"]?.jsonPrimitive?.contentOrNull) {
                "movie" -> true
                "tv" -> false
                // Anything else has no detail screen to land on; drop it.
                else -> return@mapNotNull null
            }
            val popularity = o["popularity"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
            Triple(id, TmdbKnownForItem(id, title, poster, isMovie), popularity)
        }
            .sortedByDescending { it.third }
            .distinctBy { it.first }
            .take(8)
            .map { it.second }
    }

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
