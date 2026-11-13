package com.aeriotv.android.core.network

import android.util.Log

/**
 * What identifies a deleted playlist's traffic (Apple PlaylistFingerprint,
 * 17f1d07): its API keys (the playlist's own and its profiles'), the secrets
 * an Xtream Codes or M3U playlist carries in its URLs, and the server hosts.
 * Before it is revoked, everything a remaining playlist also uses (the same
 * key, URL secret, or host) is taken out ([minus]), so that playlist keeps
 * working. Never logged.
 */
data class PlaylistFingerprint(
    val playlistId: String,
    /** Dispatcharr API keys (X-API-Key, or Authorization ApiKey / Bearer). */
    val keys: Set<String> = emptySet(),
    /** URL fragments only this playlist's requests carry: "/<user>/<pass>/", "username=<user>&password=<pass>", the M3U and XMLTV URLs. */
    val urlSecrets: Set<String> = emptySet(),
    /** "host:port" keys ([DispatcharrRequestGate.serverKey]) of its server, LAN, M3U, and XMLTV addresses. */
    val hosts: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = keys.isEmpty() && urlSecrets.isEmpty() && hosts.isEmpty()

    /** This fingerprint without anything [others] carry: a remaining playlist's key, URL secret, or host stays usable. */
    fun minus(others: Collection<PlaylistFingerprint>): PlaylistFingerprint {
        if (others.isEmpty()) return this
        val k = others.flatMapTo(HashSet()) { it.keys }
        val s = others.flatMapTo(HashSet()) { it.urlSecrets }
        val h = others.flatMapTo(HashSet()) { it.hosts }
        return copy(keys = keys - k, urlSecrets = urlSecrets - s, hosts = hosts - h)
    }

    /** True when [other] shares its id, or any key, URL secret, or host. */
    fun overlaps(other: PlaylistFingerprint): Boolean =
        other.playlistId == playlistId ||
            keys.any { it in other.keys } ||
            hosts.any { it in other.hosts } ||
            urlSecrets.any { it in other.urlSecrets }

    companion object {
        /** Xtream Codes credentials as they appear in request URLs. */
        fun xtreamSecrets(username: String?, password: String?): Set<String> {
            val u = username?.trim().orEmpty()
            val p = password?.trim().orEmpty()
            if (u.isEmpty() || p.isEmpty()) return emptySet()
            return setOf("/$u/$p/", "username=$u&password=$p")
        }

        /**
         * A playlist's fingerprint from its saved fields. [sourceType] is the
         * SourceType name; [url] the M3U URL or the server's base URL;
         * [profileKeys] its profiles' API keys.
         */
        fun of(
            playlistId: String,
            sourceType: String,
            url: String?,
            lanUrl: String? = null,
            epgUrl: String? = null,
            apiKey: String? = null,
            username: String? = null,
            password: String? = null,
            profileKeys: Collection<String> = emptyList(),
        ): PlaylistFingerprint {
            val keys = (listOfNotNull(apiKey) + profileKeys).mapNotNull { it.trim().takeIf { k -> k.isNotEmpty() } }.toSet()
            val hosts = listOfNotNull(url, lanUrl, epgUrl).map { it.trim() }.filter { it.isNotEmpty() }
                .map { DispatcharrRequestGate.serverKey(it) }.toSet()
            val secrets = HashSet<String>()
            when (sourceType) {
                "M3uUrl" -> listOfNotNull(url, epgUrl).map { it.trim() }.filter { it.isNotEmpty() }.forEach { secrets += it }
                "XtreamCodes" -> {
                    secrets += xtreamSecrets(username, password)
                    epgUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { secrets += it }
                }
                else -> Unit
            }
            return PlaylistFingerprint(playlistId, keys, secrets, hosts)
        }
    }
}

/**
 * Deleted playlists, by fingerprint, in memory: a request that carries one
 * of them is refused before it is sent, and an answer that arrives after the
 * delete is dropped. A playlist the user signs in to again (re-added, set up
 * from another device, restored) lifts the entries it matches. Pure, so the
 * unit test pins it.
 */
class PlaylistRevocationBook {
    private val revoked = LinkedHashMap<String, PlaylistFingerprint>()

    val isEmpty: Boolean get() = revoked.isEmpty()

    /** Adds [f], merged with what is already revoked for its playlist. True when that added anything. */
    fun revoke(f: PlaylistFingerprint): Boolean {
        if (f.isEmpty) return false
        val had = revoked[f.playlistId]
        val next = if (had == null) f else had.copy(
            keys = had.keys + f.keys, urlSecrets = had.urlSecrets + f.urlSecrets, hosts = had.hosts + f.hosts,
        )
        revoked[f.playlistId] = next
        return next != had
    }

    /** What is revoked for [playlistId], or null. */
    fun entry(playlistId: String): PlaylistFingerprint? = revoked[playlistId]

    /** The deleted playlist whose traffic this is, or null. */
    fun refusal(url: String, apiKey: String?): String? {
        if (revoked.isEmpty()) return null
        val host = if (url.isBlank()) "" else DispatcharrRequestGate.serverKey(url)
        val key = apiKey?.trim().orEmpty()
        for ((id, f) in revoked) {
            if (key.isNotEmpty() && key in f.keys) return id
            if (host.isNotEmpty() && host in f.hosts) return id
            if (f.urlSecrets.any { it.isNotEmpty() && url.contains(it) }) return id
        }
        return null
    }

    internal fun clear() = revoked.clear()

    /** A playlist is in use again: entries it overlaps go. True when any did. */
    fun lift(live: PlaylistFingerprint): Boolean {
        val before = revoked.size
        revoked.values.removeAll { it.overlaps(live) }
        return revoked.size != before
    }
}

/**
 * The app's revocation gate (playlist delete cutoff, Logan 2026-11-12: "If
 * the playlist is deleted then the app should no longer have access to
 * anything within that playlist. Immediately."; Apple 17f1d07). Every HTTP
 * path that can reach a playlist checks it: the Dispatcharr API
 * (DispatcharrGatePlugin), streams, recordings, tiles, and casts
 * (StreamGate), and logos, guide and playlist downloads, the Xtream Codes
 * bridge, and the LAN probe (HeldRouteGate). An explicit sign-in check (the
 * Add Playlist form's) is never refused, and a save lifts what it matches.
 */
object PlaylistRevocation {
    private const val TAG = "PlaylistDelete"

    /** A request for a deleted playlist, refused before it went out (or its late answer, dropped). */
    class Refused(val playlistId: String) : java.io.IOException("This playlist was deleted.")

    private val lock = Any()
    private val book = PlaylistRevocationBook()
    @Volatile private var active = false

    /** Log sink (tests replace it; android.util.Log is not available on the JVM). */
    @Volatile internal var logSink: ((String) -> Unit)? = null
    private fun log(line: String) = logSink?.invoke(line) ?: run { Log.i(TAG, line); Unit }

    /** The deleted playlist whose traffic this is, or null. */
    fun refusal(url: String, apiKey: String?): String? {
        if (!active) return null
        return synchronized(lock) { book.refusal(url, apiKey) }
    }

    /** Throws [Refused] when [url] or [apiKey] belongs to a deleted playlist. */
    fun check(url: String, apiKey: String?) {
        refusal(url, apiKey)?.let { throw Refused(it) }
    }

    /** The playlist was deleted: refuse its traffic from now on (counts only are logged). */
    fun revoke(f: PlaylistFingerprint) {
        if (f.isEmpty) {
            log("[PLAYLIST] delete ${f.playlistId.take(8)}: nothing of it refused (every key and host is another playlist's too)")
            return
        }
        val now = synchronized(lock) {
            val added = book.revoke(f)
            active = !book.isEmpty
            if (added) book.entry(f.playlistId) else null
        } ?: return
        log("[PLAYLIST] delete ${now.playlistId.take(8)}: ${now.keys.size} key(s), ${now.urlSecrets.size} URL secret(s), ${now.hosts.size} host(s) refused from now on")
    }

    /** Playlists in use (re-added, set up, restored, edited): the deleted entries they overlap are lifted. */
    fun lift(live: Collection<PlaylistFingerprint>) {
        if (!active || live.isEmpty()) return
        val lifted = synchronized(lock) {
            val any = live.fold(false) { acc, f -> book.lift(f) || acc }
            active = !book.isEmpty
            any
        }
        if (lifted) log("[PLAYLIST] a deleted playlist's sign-in or address is in use again: lifted")
    }

    /** A key saved again (a playlist or profile sign-in): entries carrying it are lifted. */
    fun liftKey(key: String?) {
        val k = key?.trim()?.takeIf { it.isNotEmpty() } ?: return
        lift(listOf(PlaylistFingerprint(playlistId = "", keys = setOf(k))))
    }

    /**
     * OkHttp: refuses a request for a deleted playlist before it goes out,
     * and drops an answer that arrives after the delete.
     */
    val interceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        val key = requestApiKey(request.header("X-API-Key"), request.header("Authorization"))
        check(url, key)
        val response = chain.proceed(request)
        refusal(url, key)?.let { id ->
            response.close()
            throw Refused(id)
        }
        response
    }

    internal fun resetForTests() {
        synchronized(lock) {
            book.clear()
            active = false
        }
    }
}
