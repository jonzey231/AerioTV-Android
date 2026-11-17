package com.aeriotv.android.core.debug

/**
 * Strips credentials out of log lines before they are written to the
 * persistent debug log file (audit task #53).
 *
 * Conservative pattern set, false positives are acceptable (a redacted
 * line is still readable), but a missed key shipped to a bug-report
 * attachment is not. Patterns:
 *
 * 1. URL query parameters whose name implies a secret
 *    (`?api_key=abc&password=xyz`).
 * 2. HTTP request/response header lines that carry credentials
 *    (`Authorization: ApiKey foo`, `X-API-Key: foo`, `Cookie: ...`).
 * 3. Bearer/ApiKey/Token prefixes inside free-form text.
 * 4. JWTs (3-segment base64url tokens beginning with `eyJ`).
 * 5. Dispatcharr-shaped API key form-encoded body (`apikey=...`).
 * 6. JSON body fields whose name implies a secret
 *    (`"access":"..."`, `"password":"..."`, etc.).
 *
 * The set is intentionally case-insensitive. The replacement is `***` so
 * the structure of the line is preserved (a downstream reader still sees
 * `?api_key=***` and knows what kind of URL it was).
 *
 * Caller-side: every line that goes into [DebugLogger]'s queue passes
 * through [redact] first; the logcat echo (the dev-only path) is left
 * alone so live debugging via `adb logcat` still shows full values. The
 * persistent file is the bug-report surface we're protecting.
 */
internal object LogSanitizer {

    private val URL_USERINFO = Regex(
        // HTTP basic-auth userinfo embedded in a URL: scheme://user:pass@host.
        // Common in raw M3U playlists, which QUERY_PARAM does NOT cover. Redacts
        // the whole userinfo to ***@, keeping scheme+host so the log still shows
        // which server was contacted. Mirrors iOS urlUserInfoRegex (24297b5ea).
        "(://)[^/@\\s]+@",
    )

    private val XTREAM_PATH = Regex(
        // Xtream stream URLs carry credentials as PATH segments, not query
        // params: /live|movie|series/<user>/<pass>/<id>.ext . Neither
        // QUERY_PARAM nor URL_USERINFO covers this shape, so these URLs (logged
        // by the player / VOD / multiview tune paths) would otherwise leak a
        // full credential pair into the shareable log. Redact BOTH the user and
        // password slots (GH #117: the username alone identifies the provider
        // account on a public issue); the stream kind and id stay visible.
        "(?i)(/(?:live|movie|series|timeshift)/)[^/\\s]+/[^/\\s]+(/)",
    )

    private val QUERY_PARAM = Regex(
        // [?&]name=value where name is one of the secret-y param names. The
        // value runs until the next `&`, whitespace, or end-of-line. We
        // capture the prefix so the replacement keeps `&password=` intact.
        "(?i)([?&](?:api[_-]?key|apikey|key|token|auth|authkey|sig|signature|password|pass|pwd|secret|hash|username|user)=)([^&\\s]+)",
    )

    private val HEADER_LINE = Regex(
        "(?i)\\b((?:Authorization|X-API-Key|X-Api-Key|Cookie|Set-Cookie|Proxy-Authorization)\\s*:\\s*)([^\\r\\n]+)",
    )

    private val INLINE_PREFIX = Regex(
        // "Bearer xyz", "ApiKey xyz", "Token xyz" -- 8+ chars to avoid eating
        // legit prose like "Bearer 1" or "Token A".
        "(?i)\\b(Bearer|ApiKey|Api-Key|Token)\\s+([A-Za-z0-9._=\\-]{8,})",
    )

    private val JWT = Regex(
        "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b",
    )

    // JSON response body field: "access":"...", "password":"...", etc.
    // Keeps the field name visible; replaces the value with ***.
    private val JSON_CREDENTIAL = Regex(
        """(?i)"(access|refresh|token|api[_-]?key|apikey|password|secret|auth)"\s*:\s*"([^"]+)"""",
    )

    fun redact(message: String): String {
        if (message.isEmpty()) return message
        var out = message
        // Order matters: userinfo first (scheme://user:pass@ in raw M3U URLs),
        // then header form (keeps the header name visible), then JWT (longest
        // pattern, prevents query-param regex from chopping a JWT mid-base64),
        // then JSON body fields, then prefix forms, then query params last.
        out = URL_USERINFO.replace(out, "$1***@")
        out = XTREAM_PATH.replace(out) { mr -> mr.groupValues[1] + "***/***" + mr.groupValues[2] }
        out = HEADER_LINE.replace(out) { mr -> mr.groupValues[1] + "***" }
        out = JWT.replace(out, "eyJ***")
        out = JSON_CREDENTIAL.replace(out) { mr -> "\"${mr.groupValues[1]}\":\"***\"" }
        out = INLINE_PREFIX.replace(out) { mr -> "${mr.groupValues[1]} ***" }
        out = QUERY_PARAM.replace(out) { mr -> mr.groupValues[1] + "***" }
        return out
    }

    /**
     * Reduce a (possibly credentialed) URL to scheme://host[:port]/path for a
     * USER-FACING message. Strips query, fragment, and userinfo entirely so a
     * screenshot or pasted bug report can't leak an Xtream username/password or
     * an api_key. Falls back to [redact] if the string can't be parsed as a
     * URI. Mirrors iOS safeURL (commit 22b125a65).
     */
    fun redactUrl(raw: String): String = runCatching {
        val u = java.net.URI(raw)
        val scheme = u.scheme ?: return@runCatching redact(raw)
        val host = u.host ?: return@runCatching redact(raw)
        val port = if (u.port > 0) ":${u.port}" else ""
        val path = u.rawPath.orEmpty()
        "$scheme://$host$port$path"
    }.getOrElse { redact(raw) }

    // ---- stream URL masking for log lines ----

    /** Xtream route kinds whose next two path segments are the account's
     *  username and password. */
    private val XTREAM_KINDS = setOf("live", "movie", "series", "timeshift")

    /** Path segments kept verbatim: route words only, never values. Anything
     *  else (ids, uuids, user names, tokens) becomes a placeholder. */
    private val ROUTE_WORDS = setOf(
        "live", "movie", "movies", "series", "timeshift", "proxy", "ts", "hls",
        "stream", "streams", "api", "vod", "episode", "episodes", "file", "files",
        "recording", "recordings", "dvr", "channel", "channels", "output", "play",
        "epg", "m3u", "xmltv", "grid", "programs", "accounts", "profiles", "groups",
        "logos", "core", "settings", "version", "status", "token", "refresh",
        "change_stream", "stop", "plugins", "users", "me", "auth", "playlist",
        "player_api.php", "get.php", "xmltv.php", "panel_api.php", "index.m3u8",
    )

    /** Query parameter names whose values are not secret and help a reader
     *  (the Xtream API action, an output format). Every other value is masked. */
    private val PLAIN_QUERY_PARAMS = setOf(
        "action", "output", "output_format", "type", "format", "page", "page_size", "limit",
    )

    private val QUERY_NAME = Regex("[A-Za-z0-9_.\\-]{1,32}")
    private val PLAIN_VALUE = Regex("[A-Za-z0-9_\\-]{1,24}")
    private val EXTENSION = Regex("[A-Za-z0-9]{1,5}")
    private val URL_IN_TEXT = Regex("(?i)\\b(?:https?|rtmp|rtsp|rtp|udp)://[^\\s\"'<>]+")

    /**
     * Mask a stream or API URL for a log line: scheme + `<host>` + a path that
     * keeps only route words and the file extension, and query parameter names
     * with their values masked. The host, port, userinfo, Xtream user and
     * password, ids, tokens, and query values never appear. Example:
     * `https://h:8080/movie/u/p/6001342.mkv` -> `https://<host>/movie/<user>/<pass>/<id>.mkv`.
     * Pure string work: the URL actually requested is never touched.
     */
    fun maskStreamUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return "<none>"
        return runCatching {
            val u = java.net.URI(raw.trim())
            val scheme = u.scheme?.lowercase() ?: return@runCatching "<url>"
            val segs = u.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
            if (scheme == "file" || scheme == "content") {
                return@runCatching "$scheme://<local>/<id>" + extensionOf(segs.lastOrNull())
            }
            val out = ArrayList<String>(segs.size)
            var i = 0
            if (segs.size >= 4 && segs[0].lowercase() in XTREAM_KINDS) {
                out += segs[0].lowercase()
                out += "<user>"
                out += "<pass>"
                i = 3
            }
            while (i < segs.size) {
                val s = segs[i]
                val word = s.lowercase()
                out += when {
                    word in ROUTE_WORDS -> word
                    i == segs.size - 1 -> "<id>" + extensionOf(s)
                    else -> "<seg>"
                }
                i++
            }
            val trailing = if (out.isNotEmpty() && u.rawPath.orEmpty().endsWith("/")) "/" else ""
            val path = if (out.isEmpty()) u.rawPath.orEmpty().take(1) else "/" + out.joinToString("/") + trailing
            val query = u.rawQuery?.takeIf { it.isNotEmpty() }?.split('&')?.joinToString("&") { p ->
                val name = p.substringBefore('=')
                when {
                    !QUERY_NAME.matches(name) -> "<p>"
                    name.lowercase() in PLAIN_QUERY_PARAMS && '=' in p &&
                        PLAIN_VALUE.matches(p.substringAfter('=')) -> p
                    '=' in p -> "$name=<v>"
                    else -> name
                }
            }
            "$scheme://<host>$path" + (query?.let { "?$it" } ?: "")
        }.getOrElse { "<url>" }
    }

    /** [maskStreamUrl] applied to every URL found inside free text (a Ktor
     *  request line, an exception message). */
    fun maskUrlsIn(text: String): String {
        if (text.isEmpty() || !text.contains("://")) return text
        return URL_IN_TEXT.replace(text) { mr -> maskStreamUrl(mr.value) }
    }

    private fun extensionOf(segment: String?): String {
        val ext = segment?.substringAfterLast('.', "").orEmpty()
        return if (ext.isNotEmpty() && EXTENSION.matches(ext)) ".${ext.lowercase()}" else ""
    }
}
