package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.M3UChannel
import java.security.MessageDigest

/**
 * Stable fingerprint of a loaded channel list's identity: the sorted set of
 * canonical ids plus each channel's declared guide key. The EPG cache is
 * stamped with it on a successful fetch; a different fingerprint at paint
 * time means the cache was built for a different channel list and must be
 * refetched (docs/guide-semantics.md, section 4). Order-independent, so
 * re-sorting or re-grouping the same channels never invalidates the cache.
 */
object GuideIdentityHash {
    fun of(channels: List<M3UChannel>): String {
        if (channels.isEmpty()) return ""
        val md = MessageDigest.getInstance("SHA-1")
        channels
            .map { it.guideChannelId().value + "|" + GuideMatchMaps.normalize(it.tvgID) }
            .sorted()
            .forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
        val sb = StringBuilder(40)
        for (b in md.digest()) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    // ---- Server-scoped identity stamp (2026-10-27) -------------------------
    //
    // The EPG cache belongs to the SERVER (the playlist row), not to the
    // active profile. Two profiles on one Dispatcharr server see different
    // channel lists (Kids vs the admin user), so a single hash over the active
    // profile's list flipped on every profile switch and purged a cache that
    // was still valid (Streamer log 2026-10-27: stored=8d86c2fd
    // current=22a38d3b channels=597). The stamp is now the server fingerprint
    // plus the UNION of per-channel identity entries every profile on this
    // playlist has loaded. A channel list is stale against it only when the
    // server fingerprint differs or a loaded channel carries an entry the
    // stamp has never seen (a new channel, or a re-keyed canonical id or guide
    // key). A profile that sees a subset of the server's channels is never
    // stale; its visibility is applied when the guide renders.

    private const val STAMP_PREFIX = "v2|"

    /** Normalized server identity: source type plus the playlist base URL. */
    fun serverFingerprint(sourceType: String, url: String): String =
        sourceType + "@" + url.trim().trimEnd('/').lowercase()

    /**
     * Compact per-channel identity entries "cccccc:pppppp": a 6-byte hash of
     * the canonical id, and a 6-byte hash of canonical id plus guide key. The
     * split lets [judge] tell a NEW channel (canonical never seen: fetch, keep
     * the cache) from a RE-KEYED one (canonical seen under another guide key:
     * its cached rows may sit under the wrong key, purge).
     */
    fun entries(channels: List<M3UChannel>): Set<String> {
        val out = HashSet<String>(channels.size * 2)
        for (ch in channels) {
            val canonical = ch.guideChannelId().value
            out.add(short(canonical) + ":" + short(canonical + "|" + GuideMatchMaps.normalize(ch.tvgID)))
        }
        return out
    }

    private fun short(v: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(v.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(12)
        for (i in 0 until 6) sb.append(String.format("%02x", d[i]))
        return sb.toString()
    }

    data class Stamp(val server: String, val entries: Set<String>)

    /** Null for an empty or legacy (pre-v2 single hash) stamp. */
    fun decode(raw: String): Stamp? {
        if (!raw.startsWith(STAMP_PREFIX)) return null
        val body = raw.removePrefix(STAMP_PREFIX)
        val bar = body.indexOf('|')
        if (bar < 0) return null
        val list = body.substring(bar + 1)
        return Stamp(body.substring(0, bar), if (list.isEmpty()) emptySet() else list.split(',').toSet())
    }

    fun encode(stamp: Stamp): String =
        STAMP_PREFIX + stamp.server + "|" + stamp.entries.sorted().joinToString(",")

    enum class Verdict { MATCH, LEGACY, SERVER_CHANGED, REKEYED, NEW_CHANNELS }

    data class Judgement(
        val verdict: Verdict,
        val rekeyed: Int,
        val newChannels: Int,
        val storedEntries: Int,
        val storedServer: String,
    ) {
        /** Rows may be keyed to a channel identity that no longer holds. */
        val purge: Boolean get() = verdict == Verdict.SERVER_CHANGED || verdict == Verdict.REKEYED
        /** The cache is valid but does not cover every loaded channel yet. */
        val needsFetch: Boolean get() = verdict != Verdict.MATCH
    }

    fun judge(raw: String, server: String, current: Set<String>): Judgement {
        val stamp = decode(raw)
            ?: return Judgement(Verdict.LEGACY, 0, 0, 0, "")
        if (stamp.server != server) {
            return Judgement(Verdict.SERVER_CHANGED, 0, current.size, stamp.entries.size, stamp.server)
        }
        val storedCanonical = stamp.entries.mapTo(HashSet()) { it.substringBefore(':') }
        var rekeyed = 0
        var fresh = 0
        for (e in current) {
            if (e in stamp.entries) continue
            if (e.substringBefore(':') in storedCanonical) rekeyed++ else fresh++
        }
        val verdict = when {
            rekeyed > 0 -> Verdict.REKEYED
            fresh > 0 -> Verdict.NEW_CHANNELS
            else -> Verdict.MATCH
        }
        return Judgement(verdict, rekeyed, fresh, stamp.entries.size, stamp.server)
    }

    /**
     * The stamp to write after a successful fetch. Same server: union with
     * what other profiles loaded (the fetch replaced rows only for the keys
     * it saw, other profiles' rows stay valid). Different server or legacy:
     * start over from [current].
     */
    fun merged(raw: String, server: String, current: Set<String>, reset: Boolean): String {
        val stamp = decode(raw)
        val base = if (!reset && stamp != null && stamp.server == server) stamp.entries else emptySet()
        return encode(Stamp(server, base + current))
    }
}
