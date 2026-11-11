package com.aeriotv.android.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One log line describing a Drive sync file: envelope time, format, writer,
 * live and removed item counts, and the newest item change time. Counts and
 * times only: never a value, a name, a URL, or a credential.
 */
object DriveFileStats {
    private val json = Json { ignoreUnknownKeys = true }

    fun time(ms: Long?): String =
        if (ms == null || ms <= 0L) "none" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    /** "env=... fmt=... by=... live=N removed=M newest=..." for [body], or "unreadable". */
    fun line(category: SyncCategory, body: String?): String {
        if (body == null) return "absent"
        val root = runCatching { json.parseToJsonElement(body).obj() }.getOrNull() ?: return "unreadable"
        val env = root["envelope"].obj()
        val head = "env=${time(env?.get("lastModified").long())} fmt=${env?.get("format").long() ?: "none"} by=${env?.get("device").str() ?: "?"}"
        fun rows(name: String, vararg stamps: String): String {
            val a = root[name].arr() ?: return "$name=0"
            val items = a.mapNotNull { it.obj() }
            val removed = items.count { it["removedAt"].long() != null || it["unhiddenAt"].long() != null }
            val newest = items.maxOfOrNull { o -> stamps.maxOf { o[it].long() ?: 0L } }
            return "$name live=${items.size - removed} removed=$removed newest=${time(newest)}"
        }
        val tail = when (category) {
            SyncCategory.Playlists -> rows("playlists", "updatedAt", "removedAt")
            SyncCategory.WatchProgress -> rows("entries", "updatedAt", "removedAt")
            SyncCategory.Reminders -> rows("entries", "updatedAt", "removedAt")
            SyncCategory.Favorites -> rows("entries", "addedAt", "updatedAt", "removedAt")
            SyncCategory.Watchlist -> rows("entries", "addedAt", "removedAt") + " " + rows("hidden", "hiddenAt", "unhiddenAt")
            SyncCategory.Credentials -> rows("entries", "updatedAt")
            SyncCategory.Preferences -> {
                val n = listOf("keys", "tv", "mobile").sumOf { root[it].obj()?.size ?: 0 }
                val times = root["times"].obj()
                "prefs=$n timed=${times?.size ?: 0} newest=${time(times?.values?.maxOfOrNull { it.long() ?: 0L })}"
            }
        }
        return "$head $tail"
    }

    /**
     * Preference keys changed after [sinceMs] in [body] ("lane:key@time"), the
     * keys only (a value may be private, a TMDB key for one).
     */
    fun recentPrefKeys(body: String?, sinceMs: Long): String {
        val times = runCatching { json.parseToJsonElement(body ?: return "none").obj()?.get("times").obj() }.getOrNull() ?: return "none"
        return times.entries.mapNotNull { (k, v) -> v.long()?.takeIf { it > sinceMs }?.let { "$k@${time(it)}" } }
            .sorted().joinToString(",").ifEmpty { "none" }
    }

    /** The most recently changed watch-progress row: "pos=Ns at=time" with no title, or "none". */
    fun newestProgress(body: String?): String {
        val a = runCatching { json.parseToJsonElement(body ?: return "none").obj()?.get("entries").arr() }.getOrNull() ?: return "none"
        val o = a.mapNotNull { it.obj() }.maxByOrNull { it["updatedAt"].long() ?: 0L } ?: return "none"
        val id = o["videoId"].str()?.let { "#" + Integer.toHexString(it.hashCode()).takeLast(6) } ?: "?"
        return "row=$id pos=${(o["positionMs"].long() ?: 0L) / 1000}s at=${time(o["updatedAt"].long())}${if (o["removedAt"].long() != null) " removed" else ""}"
    }
}
