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

    /** Key hash -> playlist ids that saw it rejected. */
    private val rejected = ConcurrentHashMap<String, MutableSet<String>>()

    /** One Edit Playlist prompt: [passwordToo] = the playlist also has a password (re-login was refused as well). */
    data class Prompt(val playlistId: String, val playlistName: String, val passwordToo: Boolean)

    private val _prompt = kotlinx.coroutines.flow.MutableStateFlow<Prompt?>(null)
    /** The pending Edit Playlist prompt; the UI calls [consumePrompt] when it is answered. */
    val prompt: kotlinx.coroutines.flow.StateFlow<Prompt?> = _prompt

    /** SHA-256 prefix of [key]: what the gate remembers instead of the key. */
    fun keyHash(key: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(key.trim().toByteArray(Charsets.UTF_8))
        return d.take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * Remember that the server rejected [key] for [playlistId]. Returns true
     * only on the transition (first rejection of this key for this
     * playlist), and only then raises the prompt.
     */
    fun markKeyRejected(playlistId: String, key: String, playlistName: String = "", passwordToo: Boolean = false): Boolean {
        val set = rejected.getOrPut(keyHash(key)) { ConcurrentHashMap.newKeySet() }
        val fresh = set.add(playlistId)
        if (fresh) _prompt.value = Prompt(playlistId, playlistName, passwordToo)
        return fresh
    }

    /** True while [key] is known rejected (any playlist). */
    fun isKeyRejected(key: String): Boolean =
        key.isNotBlank() && !rejected[keyHash(key)].isNullOrEmpty()

    /** True while [playlistId] has a rejected key on record. */
    fun isPlaylistRejected(playlistId: String): Boolean = rejected.values.any { playlistId in it }

    /** The playlist was edited (key or credentials): forget its rejection and any prompt for it. */
    fun clearPlaylist(playlistId: String) {
        rejected.values.forEach { it.remove(playlistId) }
        rejected.entries.removeIf { it.value.isEmpty() }
        if (_prompt.value?.playlistId == playlistId) _prompt.value = null
    }

    /** [key] just authenticated: it is good again everywhere. */
    fun clearKey(key: String) {
        val ids = rejected.remove(keyHash(key)) ?: return
        if (_prompt.value?.playlistId in ids) _prompt.value = null
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

    /** Park [serverKey] for [seconds] (a later, longer 429 extends it; a shorter one never shortens it). */
    fun pause(serverKey: String, seconds: Long) {
        val until = clock() + seconds.coerceIn(1, MAX_RETRY_AFTER_S) * 1000
        pausedUntil.merge(serverKey, until) { old, new -> maxOf(old, new) }
    }

    /** Seconds left on [serverKey]'s pause, 0 when requests may go out. */
    fun pausedSeconds(serverKey: String): Long {
        val until = pausedUntil[serverKey] ?: return 0
        val left = until - clock()
        if (left <= 0) { pausedUntil.remove(serverKey, until); return 0 }
        return (left + 999) / 1000
    }

    /** Test hook: forget every pause. */
    fun resetForTests() {
        pausedUntil.clear()
        rejected.clear()
        _prompt.value = null
    }
}
