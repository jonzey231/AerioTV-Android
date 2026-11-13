package com.aeriotv.android.core.network

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Local gate in front of every Dispatcharr API request (pure logic, no
 * Android types, so the JVM tests can drive it with a fake clock).
 *
 * Why: Dispatcharr throttles API-key auth per client IP (scope
 * `api_key_auth`, 10 failed key attempts per minute), and once tripped it
 * answers 429 to EVERY API-key request from that IP, valid keys included
 * (apps/accounts/authentication.py checks the limit before it looks at the
 * key). Measured 2026-11-09: a key-only playlist whose key was replaced on
 * the server kept retrying, tripped the limit, and a device setup landing in
 * that window failed with "Could not connect: HTTP 429".
 *
 * Rejected key (401 for a key the app cannot recover from): remembered per
 * playlist and key value (a hash, never the key). While set, every request
 * carrying that key fails fast with [DispatcharrError.KeyRejected] and no
 * network request, so nothing keeps feeding the server's failed-key budget.
 * Cleared when the playlist is edited, when a key is proven good again, or
 * on app relaunch (memory only). The Edit Playlist prompt is raised once per
 * rejection through [prompt].
 *
 * Server pause (HTTP 429): the first 429 from a server parks that server
 * (host and port) until Retry-After passes. While parked, requests fail fast
 * with [DispatcharrError.RateLimited] and no network request, so background
 * work (VOD lanes, DVR poll, EPG, sources) waits instead of spending its
 * retries, and interactive flows count down and try again
 * ([RateLimitRetry]).
 */
object DispatcharrRequestGate {
    /** Retry-After when the server sends none (or one we cannot read). */
    const val DEFAULT_RETRY_AFTER_S = 60L
    /** Never park longer than this, whatever the server asks. */
    const val MAX_RETRY_AFTER_S = 120L

    /** Monotonic clock in ms (tests replace it). */
    @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000 }
    /** Wall clock in ms, for an HTTP-date Retry-After (tests replace it). */
    @Volatile var wallClock: () -> Long = { System.currentTimeMillis() }

    private val pausedUntil = ConcurrentHashMap<String, Long>()

    /** Key hash -> holders that saw it rejected ([holder]: the playlist's own sign-in, or one profile's). */
    private val rejected = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * One prompt: [passwordToo] = the playlist also has a password (re-login
     * was refused as well); [noApiKey] = the re-login signed in, but the
     * account has no API key on the server.
     */
    data class Prompt(
        val playlistId: String,
        val playlistName: String,
        val passwordToo: Boolean,
        val noApiKey: Boolean = false,
        /** The active non-default profile whose own key was refused (its Sign In signs that profile in), or null for the playlist's own. */
        val profileId: String? = null,
    ) {
        val holder: String get() = holder(playlistId, profileId)
    }

    private val _prompt = kotlinx.coroutines.flow.MutableStateFlow<Prompt?>(null)
    /** The pending Edit Playlist prompt; the UI calls [consumePrompt] when it is answered. */
    val prompt: kotlinx.coroutines.flow.StateFlow<Prompt?> = _prompt

    /** SHA-256 prefix of [key]: what the gate remembers instead of the key. */
    fun keyHash(key: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(key.trim().toByteArray(Charsets.UTF_8))
        return d.take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * Holds are per identity (2026-11-12, security): [playlistId] for the
     * playlist's own sign-in, "playlistId@profileId" for a non-default
     * profile's. Repairing the playlist's own sign-in never releases a
     * profile's held key, so that still-refused key adds no failed-key
     * attempts.
     */
    fun holder(playlistId: String, profileId: String? = null): String =
        if (profileId.isNullOrEmpty()) playlistId else "$playlistId@$profileId"

    private fun playlistOf(holder: String): String = holder.substringBefore('@')

    /**
     * Remember that the server rejected [key] for [playlistId] (signed in as
     * [profileId], null = the playlist's own sign-in). Returns true only on
     * the transition (first rejection of this key for this identity), and
     * only then raises the prompt.
     */
    fun markKeyRejected(
        playlistId: String,
        key: String,
        playlistName: String = "",
        passwordToo: Boolean = false,
        /** The server the automatic re-check probes (empty = no re-check). */
        baseUrl: String = "",
        /** The re-login signed in, but the account has no API key (the prompt says so). */
        noApiKey: Boolean = false,
        /** The non-default profile the key is, or null for the playlist's own sign-in. */
        profileId: String? = null,
    ): Boolean {
        val hash = keyHash(key)
        val set = rejected.getOrPut(hash) { ConcurrentHashMap.newKeySet() }
        val fresh = set.add(holder(playlistId, profileId))
        if (fresh) {
            // Drive may already carry this playlist's good key (DriveSyncManager).
            runCatching { onKeyHeld?.invoke(playlistId) }
            _prompt.value = Prompt(playlistId, playlistName, passwordToo, noApiKey, profileId?.takeIf { it.isNotEmpty() })
            if (baseUrl.isNotBlank()) {
                rechecks.putIfAbsent(hash, Recheck(hash, key.trim(), baseUrl, serverKey(baseUrl), playlistId, passwordToo, clock()))
            }
        }
        return fresh
    }

    // ---- Automatic re-check while a key is held (owner 2026-11-09: "We
    // don't want to just block access but we don't want constant retries
    // that force a server-side block from repeated attempts either.") ----

    /** Told when a key is newly held for a playlist (Drive Sync re-pulls credentials). */
    @Volatile var onKeyHeld: ((playlistId: String) -> Unit)? = null

    /** Scheduled probes after the rejection: 1, 5, and 15 minutes. */
    val RECHECK_OFFSETS_MS = longArrayOf(60_000, 300_000, 900_000)
    /** Then one every 30 minutes. */
    const val RECHECK_INTERVAL_MS = 1_800_000L
    /** Never two probes for one key within this, whatever asked (schedule, foreground, user refresh). */
    const val RECHECK_MIN_GAP_MS = 60_000L

    /** One held key's re-check state. [key] is kept in memory only, to probe with. */
    class Recheck internal constructor(
        val hash: String,
        val key: String,
        val baseUrl: String,
        val serverKey: String,
        val playlistId: String,
        /** Password playlist whose re-login was refused: the probe is one login. */
        val passwordToo: Boolean,
        val rejectedAt: Long,
    ) {
        /** Scheduled probes run so far. */
        @Volatile var scheduledDone = 0
            internal set
        @Volatile var lastProbeAt = Long.MIN_VALUE / 2
            internal set
        @Volatile var inFlight = false
            internal set
    }

    private val rechecks = ConcurrentHashMap<String, Recheck>()

    /** Due time of the scheduled probe number [done] (0-based) after [rejectedAt]. */
    fun scheduledDueAt(rejectedAt: Long, done: Int): Long =
        if (done < RECHECK_OFFSETS_MS.size) rejectedAt + RECHECK_OFFSETS_MS[done]
        else rejectedAt + RECHECK_OFFSETS_MS.last() + (done - RECHECK_OFFSETS_MS.size + 1) * RECHECK_INTERVAL_MS

    /** Held keys with a re-check (snapshot). */
    fun rechecks(): List<Recheck> = rechecks.values.toList()

    /**
     * Earliest time [r]'s next scheduled probe may run: its schedule slot,
     * no sooner than [RECHECK_MIN_GAP_MS] after the last probe, and never
     * inside a 429 pause for its server ([extraBlockedUntil] adds another
     * pause, for one the caller tracks, such as the login gate's).
     */
    fun nextScheduledAt(r: Recheck, extraBlockedUntil: Long = 0): Long = maxOf(
        scheduledDueAt(r.rejectedAt, r.scheduledDone),
        r.lastProbeAt + RECHECK_MIN_GAP_MS,
        pausedUntil[r.serverKey] ?: 0,
        extraBlockedUntil,
    )

    /** Claim a scheduled probe for [r] if one is due now; true = run it (exactly one request). */
    @Synchronized
    fun claimScheduled(r: Recheck, extraBlockedUntil: Long = 0): Boolean {
        if (r.inFlight || rechecks[r.hash] !== r) return false
        val now = clock()
        if (now < nextScheduledAt(r, extraBlockedUntil)) return false
        r.scheduledDone++
        r.lastProbeAt = now
        r.inFlight = true
        return true
    }

    /**
     * Claim an on-demand probe (app foreground, a user refresh) for [r]:
     * allowed only [RECHECK_MIN_GAP_MS] after the last probe of any kind and
     * outside a 429 pause. Does not advance the schedule.
     */
    @Synchronized
    fun claimOnDemand(r: Recheck, extraBlockedUntil: Long = 0): Boolean {
        if (r.inFlight || rechecks[r.hash] !== r) return false
        val now = clock()
        if (now < r.lastProbeAt + RECHECK_MIN_GAP_MS) return false
        if (now < (pausedUntil[r.serverKey] ?: 0) || now < extraBlockedUntil) return false
        r.lastProbeAt = now
        r.inFlight = true
        return true
    }

    /** A probe for [r] finished; [accepted] clears the hold (and its prompt). */
    fun finishProbe(r: Recheck, accepted: Boolean) {
        r.inFlight = false
        if (accepted) {
            rechecks.remove(r.hash, r)
            val holders = rejected.remove(r.hash).orEmpty()
            if (_prompt.value?.holder in holders) _prompt.value = null
        }
    }

    /** Earliest next scheduled probe over every held key, or null with none held. */
    fun earliestScheduledAt(extraBlockedUntil: (Recheck) -> Long = { 0 }): Long? =
        rechecks.values.minOfOrNull { nextScheduledAt(it, extraBlockedUntil(it)) }

    /** Absolute end of [serverKey]'s 429 pause on [clock], 0 when not paused. */
    fun pausedUntilAt(serverKey: String): Long = pausedUntil[serverKey] ?: 0

    /** True while [key] is known rejected (any playlist), or its playlist was deleted here. */
    fun isKeyRejected(key: String): Boolean {
        if (key.isBlank()) return false
        val h = keyHash(key)
        return !rejected[h].isNullOrEmpty() || h in deleted
    }

    /**
     * Keys of playlists deleted on this device, and of their profiles
     * (playlist delete cutoff, Logan 2026-11-12: "the app should no longer
     * have access to anything within that playlist. Immediately."). Nothing
     * goes out with them, and no prompt is raised for them. Only hashes are
     * kept. A playlist or profile saved again with the key lifts it.
     */
    private val deleted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun markDeleted(key: String?) {
        key?.trim()?.takeIf { it.isNotEmpty() }?.let { deleted += keyHash(it) }
    }

    fun forgetDeleted(key: String?) {
        key?.trim()?.takeIf { it.isNotEmpty() }?.let { deleted -= keyHash(it) }
        PlaylistRevocation.liftKey(key)
    }

    fun isKeyDeleted(key: String): Boolean = key.isNotBlank() && keyHash(key) in deleted

    /** True while [playlistId] has a rejected key on record (its own or a profile's). */
    fun isPlaylistRejected(playlistId: String): Boolean = rejected.values.any { set -> set.any { playlistOf(it) == playlistId } }

    /**
     * The playlist's own sign-in was edited or repaired (Sign In, Edit
     * Playlist, Fix Login, a Drive pull, a merge): forget ITS rejection and
     * any prompt for the playlist. A non-default profile's held key stays
     * held until that profile's own key is repaired ([clearProfile], or
     * [clearKey] for the same key).
     */
    fun clearPlaylist(playlistId: String) {
        dropHolder(playlistId)
        // A profile's prompt stays: its key is still held.
        if (_prompt.value?.holder == playlistId) _prompt.value = null
    }

    /** [profileId]'s own sign-in on [playlistId] was repaired: forget its rejection and its prompt. */
    fun clearProfile(playlistId: String, profileId: String) {
        val h = holder(playlistId, profileId)
        dropHolder(h)
        if (_prompt.value?.holder == h) _prompt.value = null
    }

    private fun dropHolder(h: String) {
        rejected.values.forEach { it.remove(h) }
        rejected.entries.removeIf { it.value.isEmpty() }
        rechecks.keys.removeIf { !rejected.containsKey(it) }
    }

    /** [key] just authenticated: it is good again everywhere. */
    fun clearKey(key: String) {
        // A key just proven good is never swapped for another one again.
        replaced.remove(keyHash(key))
        deleted.remove(keyHash(key))
        rechecks.remove(keyHash(key))
        val holders = rejected.remove(keyHash(key)) ?: return
        if (_prompt.value?.holder in holders) _prompt.value = null
    }

    /** The prompt was shown and answered (or dismissed). */
    fun consumePrompt() { _prompt.value = null }

    /** "host:port" for [url]; the scheme's default port when none is given. */
    fun serverKey(url: String): String {
        val u = runCatching { URI(url.trim()) }.getOrNull()
        val host = u?.host?.lowercase() ?: return url.trim().trimEnd('/').lowercase()
        val port = when {
            u.port > 0 -> u.port
            u.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        return "$host:$port"
    }

    /**
     * Seconds to wait from a Retry-After header: delta seconds or an HTTP
     * date. Missing, unreadable, or in the past = [DEFAULT_RETRY_AFTER_S];
     * capped at [MAX_RETRY_AFTER_S]; at least 1.
     */
    fun parseRetryAfter(value: String?, nowWallMs: Long = wallClock()): Long {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return DEFAULT_RETRY_AFTER_S
        val secs = v.toLongOrNull() ?: v.toDoubleOrNull()?.let { kotlin.math.ceil(it).toLong() }
            ?: runCatching {
                val date = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                val ms = date.toInstant().toEpochMilli() - nowWallMs
                if (ms <= 0) null else (ms + 999) / 1000
            }.getOrNull()
            ?: return DEFAULT_RETRY_AFTER_S
        if (secs <= 0) return DEFAULT_RETRY_AFTER_S
        return secs.coerceIn(1, MAX_RETRY_AFTER_S)
    }

    /** Why each server is paused: the reason of its latest 429. */
    private val pausedReasons = ConcurrentHashMap<String, RateLimitReason>()

    /** Park [serverKey] for [seconds] (a later, longer 429 extends it; a shorter one never shortens it). */
    fun pause(serverKey: String, seconds: Long, reason: RateLimitReason = RateLimitReason.Other) {
        val until = clock() + seconds.coerceIn(1, MAX_RETRY_AFTER_S) * 1000
        pausedUntil.merge(serverKey, until) { old, new -> maxOf(old, new) }
        pausedReasons[serverKey] = reason
    }

    /** The reason of the 429 that paused [serverKey] ([RateLimitReason.Other] when unknown). */
    fun pausedReason(serverKey: String): RateLimitReason = pausedReasons[serverKey] ?: RateLimitReason.Other

    /** Seconds left on [serverKey]'s pause, 0 when requests may go out. */
    fun pausedSeconds(serverKey: String): Long {
        val until = pausedUntil[serverKey] ?: return 0
        val left = until - clock()
        if (left <= 0) { pausedUntil.remove(serverKey, until); return 0 }
        return (left + 999) / 1000
    }

    // ---- Replaced keys (a silent re-sign-in swapped the key) ----

    /** How long requests built with a replaced key are rewritten to the new one. */
    const val REPLACED_KEY_TTL_MS = 600_000L

    /** Old key hash -> the key that replaced it, and until when (on [clock]). */
    private val replaced = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * A re-sign-in replaced [old] with [new]. Requests already built with
     * [old] (a sweep or poll that captured the key, a call in flight) go out
     * with [new] for [REPLACED_KEY_TTL_MS] instead of spending a counted
     * failed-key attempt each on a key the server no longer knows.
     */
    fun noteKeyReplaced(old: String, new: String) {
        val o = old.trim()
        val n = new.trim()
        if (o.isEmpty() || n.isEmpty() || o == n) return
        replaced[keyHash(o)] = n to clock() + REPLACED_KEY_TTL_MS
    }

    /** The current key for [key] when it was replaced in the last [REPLACED_KEY_TTL_MS] (follows a chain), else null. */
    fun replacementFor(key: String): String? {
        var current = key.trim()
        var found: String? = null
        repeat(4) {
            val h = keyHash(current)
            val entry = replaced[h] ?: return found
            if (clock() >= entry.second) {
                replaced.remove(h, entry)
                return found
            }
            found = entry.first
            current = entry.first
        }
        return found
    }

    /** Test hook: forget every pause. */
    fun resetForTests() {
        pausedUntil.clear()
        pausedReasons.clear()
        rejected.clear()
        rechecks.clear()
        replaced.clear()
        deleted.clear()
        _prompt.value = null
    }
}

/**
 * Pure rule for a 401 on [usedKey]: retry with [current] when the playlist's
 * key has changed since the call was built and the new key is not itself held
 * as rejected; null = the key did not change, so probe it as usual.
 */
internal fun currentKeyAfterSwap(usedKey: String, current: String?, isRejected: (String) -> Boolean): String? {
    val c = current?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (c == usedKey.trim() || isRejected(c)) return null
    return c
}
