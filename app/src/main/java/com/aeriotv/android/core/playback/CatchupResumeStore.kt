package com.aeriotv.android.core.playback

import android.content.Context
import org.json.JSONObject

/**
 * GH #116: resume position for catch-up programs.
 *
 * Keyed by channel id + program start (UTC millis). Stored as one JSON
 * object in a private SharedPreferences file so it never touches the
 * shared AppPreferences DataStore. Entries older than 7 days are pruned
 * and the map is capped at 200 (oldest saves dropped first).
 *
 * The resume window matches the VOD rule: only a position past the first
 * 60 s and before the last 2 minutes is kept; anything else clears the
 * entry, so a program watched to (near) the end starts over next time.
 */
object CatchupResumeStore {
    private const val TAG = "CatchupResume"
    private const val PREFS = "catchup_resume"
    private const val KEY = "entries"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_ENTRIES = 200
    const val MIN_POSITION_MS = 60_000L
    const val END_MARGIN_MS = 120_000L

    fun key(channelId: String, programStartMillis: Long): String = "$channelId|$programStartMillis"

    fun isResumable(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs > MIN_POSITION_MS && positionMs < durationMs - END_MARGIN_MS

    @Synchronized
    fun get(context: Context, channelId: String, programStartMillis: Long, durationMs: Long): Long? {
        if (channelId.isBlank()) return null
        val all = load(context)
        val e = all.optJSONObject(key(channelId, programStartMillis)) ?: return null
        val pos = e.optLong("pos", -1L)
        return pos.takeIf { isResumable(it, durationMs) }
    }

    /** Saves a resumable position, or clears the entry when the position is
     *  outside the resume window (start or the last 2 minutes). */
    @Synchronized
    fun save(context: Context, channelId: String, programStartMillis: Long, positionMs: Long, durationMs: Long) {
        if (channelId.isBlank()) return
        val all = load(context)
        val k = key(channelId, programStartMillis)
        if (isResumable(positionMs, durationMs)) {
            all.put(k, JSONObject().put("pos", positionMs).put("at", System.currentTimeMillis()))
            android.util.Log.i(TAG, "[CATCHUP] resume saved at ${positionMs / 1000L}s")
        } else {
            val removed = all.remove(k) != null
            if (removed && durationMs > 0L && positionMs >= durationMs - END_MARGIN_MS) {
                android.util.Log.i(TAG, "[CATCHUP] resume cleared (reached end)")
            }
        }
        store(context, prune(all))
    }

    @Synchronized
    fun clear(context: Context, channelId: String, programStartMillis: Long) {
        if (channelId.isBlank()) return
        val all = load(context)
        if (all.remove(key(channelId, programStartMillis)) != null) {
            store(context, all)
            android.util.Log.i(TAG, "[CATCHUP] resume cleared (reached end)")
        }
    }

    private fun prune(all: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        val kept = mutableListOf<Pair<String, Long>>()
        all.keys().forEach { k ->
            val at = all.optJSONObject(k)?.optLong("at", 0L) ?: 0L
            if (now - at <= MAX_AGE_MS) kept += k to at
        }
        kept.sortByDescending { it.second }
        val out = JSONObject()
        kept.take(MAX_ENTRIES).forEach { (k, _) -> out.put(k, all.getJSONObject(k)) }
        return out
    }

    private fun load(context: Context): JSONObject {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun store(context: Context, all: JSONObject) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, all.toString()).apply()
    }
}
