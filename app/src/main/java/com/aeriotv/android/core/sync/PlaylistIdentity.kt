package com.aeriotv.android.core.sync

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * One server account is one playlist (Logan 2026-11-10, shared spec
 * playlist-identity-spec.md section 2). Pure logic: identity, grouping, the
 * winner every device picks, and the plan for moving a duplicate's data onto
 * the winner. [PlaylistCollapse] carries the plan out on this device.
 */
object PlaylistIdentity {

    enum class Kind { Dispatcharr, Xtream, M3u }

    fun kind(sourceType: String): Kind? = when (sourceType) {
        "DispatcharrApiKey", "DispatcharrUserPass" -> Kind.Dispatcharr
        "XtreamCodes" -> Kind.Xtream
        "M3uUrl" -> Kind.M3u
        else -> null
    }

    /**
     * Scheme and host lowercased, default port dropped (80 for http, 443 for
     * https), trailing slashes dropped. [keepQuery] for an M3U playlist URL.
     * Null when the string is not a URL with a host.
     */
    fun normalizeUrl(raw: String, keepQuery: Boolean = false): String? {
        val trimmed = raw.trim().let { if ("://" in it) it else "http://$it" }
        val u = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = u.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val port = when {
            u.port == -1 -> ""
            scheme == "http" && u.port == 80 -> ""
            scheme == "https" && u.port == 443 -> ""
            else -> ":${u.port}"
        }
        val path = u.rawPath.orEmpty().trimEnd('/')
        val query = if (keepQuery) u.rawQuery?.let { "?$it" }.orEmpty() else ""
        return "$scheme://$host$port$path$query"
    }

    /** SHA-256 of the key, first 8 bytes as lowercase hex. Never the key itself. */
    fun keyFingerprint(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.trim().toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }

    /**
     * The server account a playlist signs in as: type + normalized URL +
     * the username when known (Dispatcharr usernames compare without case),
     * else the API key's fingerprint. M3U: type + playlist URL. Null when the
     * row says too little (no URL, or neither a username nor a key).
     */
    fun of(sourceType: String, url: String, username: String?, apiKey: String?): String? {
        val k = kind(sourceType) ?: return null
        val user = username?.trim()?.takeIf { it.isNotEmpty() }
        return when (k) {
            Kind.M3u -> normalizeUrl(url, keepQuery = true)?.let { "m3u|$it" }
            Kind.Xtream -> {
                val server = normalizeUrl(url) ?: return null
                user?.let { "xtream|$server|u:$it" }
            }
            Kind.Dispatcharr -> {
                val server = normalizeUrl(url) ?: return null
                when {
                    user != null -> "dispatcharr|$server|u:${user.lowercase(Locale.ROOT)}"
                    !apiKey.isNullOrBlank() -> "dispatcharr|$server|k:${keyFingerprint(apiKey)}"
                    else -> null
                }
            }
        }
    }

    /** One playlist row as the merge sees it: id, identity, newest change time, creation time (null = unknown). */
    data class Row(val id: String, val identity: String?, val changedAt: Long, val createdAt: Long? = null)

    /** Rows that share an identity (two or more each), in a stable order. */
    fun groups(rows: List<Row>): List<List<Row>> =
        rows.filter { it.identity != null }
            .groupBy { it.identity!! }
            .values
            .filter { it.size > 1 }
            .map { g -> g.sortedBy { it.id.lowercase(Locale.ROOT) } }
            .sortedBy { it.first().id.lowercase(Locale.ROOT) }

    /**
     * Every device picks the same winner: the newest change time; on equal
     * change times the OLDER creation time (the original, not a later copy)
     * when every tied row has one, then the smaller lowercase id.
     */
    fun winner(group: List<Row>): Row {
        val newest = group.maxOf { it.changedAt }
        val tied = group.filter { it.changedAt == newest }
        val byId = compareBy<Row> { it.id.lowercase(Locale.ROOT) }
        if (tied.size > 1 && tied.all { it.createdAt != null }) {
            return tied.sortedWith(compareBy<Row> { it.createdAt!! }.then(byId)).first()
        }
        return tied.sortedWith(byId).first()
    }

    // ── Credentials on the winner ─────────────────────────────────────────

    enum class CredentialSource { Winner, Loser }

    /** What this device knows about one side's sign-in. */
    data class CredView(
        val hasCredential: Boolean,
        /** The request gate holds its key as rejected, or a merge here set it aside. */
        val rejected: Boolean,
        /** The playlist this device is using (active here). */
        val inUseHere: Boolean,
        /** Its credential change time (0 = unknown). */
        val changedAt: Long,
    )

    /**
     * The existing credential rules plus the spec's: a working sign-in is
     * never given up for a rejected or empty one. Between two that both look
     * fine, the one this device is using is the working one (the other may
     * never have been tried here); otherwise the newer change wins, and a tie
     * keeps the winner's.
     */
    fun credentialSource(winner: CredView, loser: CredView): CredentialSource {
        val winnerOk = winner.hasCredential && !winner.rejected
        val loserOk = loser.hasCredential && !loser.rejected
        return when {
            !loserOk -> CredentialSource.Winner
            !winnerOk -> CredentialSource.Loser
            loser.inUseHere && !winner.inUseHere -> CredentialSource.Loser
            winner.inUseHere -> CredentialSource.Winner
            loser.changedAt > winner.changedAt -> CredentialSource.Loser
            else -> CredentialSource.Winner
        }
    }

    // ── Profiles ──────────────────────────────────────────────────────────

    data class Profile(val id: String, val username: String, val isDefault: Boolean)

    /** Loser profiles re-pointed to the winner ([move]) and profiles removed ([drop], either side). */
    data class ProfilePlan(val move: List<String>, val drop: List<String>)

    /**
     * One account means one default profile. With the loser's sign-in kept,
     * its profiles (PINs, roles, avatars this device uses) win a username
     * clash; otherwise the winner's do. Profiles only one side has move over.
     */
    fun profilePlan(winner: List<Profile>, loser: List<Profile>, credentials: CredentialSource): ProfilePlan {
        val move = ArrayList<String>()
        val drop = ArrayList<String>()
        val loserWins = credentials == CredentialSource.Loser
        val winnerDefault = winner.firstOrNull { it.isDefault }
        for (p in loser) {
            val clash = if (p.isDefault) winnerDefault
            else winner.firstOrNull { !it.isDefault && it.username.equals(p.username, ignoreCase = true) }
            when {
                clash == null -> move += p.id
                loserWins -> { move += p.id; drop += clash.id }
                else -> drop += p.id
            }
        }
        return ProfilePlan(move, drop.distinct())
    }

    // ── The whole plan for one loser ──────────────────────────────────────

    data class Plan(
        val winnerId: String,
        val loserId: String,
        /** The loser is active here: the winner becomes active. */
        val activateWinner: Boolean,
        val credentials: CredentialSource,
        val profiles: ProfilePlan,
    )

    fun plan(
        winner: Row,
        loser: Row,
        loserActive: Boolean,
        winnerCred: CredView,
        loserCred: CredView,
        winnerProfiles: List<Profile>,
        loserProfiles: List<Profile>,
    ): Plan {
        val source = credentialSource(winnerCred, loserCred)
        return Plan(
            winnerId = winner.id,
            loserId = loser.id,
            activateWinner = loserActive,
            credentials = source,
            profiles = profilePlan(winnerProfiles, loserProfiles, source),
        )
    }

    /**
     * A per-playlist preference key of the loser, renamed for the winner, or
     * null when the key is not the loser's. Cache keys (guide and fetch
     * fingerprints, cache epochs) are not moved: the winner keeps its own and
     * the loser's caches are purged with it.
     */
    fun movedKeyName(name: String, loserId: String, winnerId: String): String? {
        if (!name.contains(loserId)) return null
        if (CACHE_KEY_PREFIXES.any { name.startsWith(it) }) return null
        return name.replace(loserId, winnerId)
    }

    private val CACHE_KEY_PREFIXES = listOf("epg_", "dispatcharr_account_facts_")
}
