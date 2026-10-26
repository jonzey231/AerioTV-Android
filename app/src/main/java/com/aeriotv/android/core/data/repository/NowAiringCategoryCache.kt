package com.aeriotv.android.core.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Persisted 10-minute cache of `/api/epg/programs/<id>/` category answers
 * for now-airing programmes, one small JSON file per playlist in the cache
 * dir. An empty string records "this programme has no categories" so it is
 * not asked again inside the window. Entries past the TTL are dropped on
 * every write.
 */
class NowAiringCategoryCache(context: Context) {
    private val dir = File(context.cacheDir, "now_airing_categories").apply { mkdirs() }
    private val lock = Mutex()
    private val memory = HashMap<String, MutableMap<Int, Pair<String, Long>>>()

    private fun file(playlistId: String) = File(dir, "${playlistId.filter { it.isLetterOrDigit() || it == '-' }}.json")

    private fun load(playlistId: String): MutableMap<Int, Pair<String, Long>> =
        memory.getOrPut(playlistId) {
            val out = HashMap<Int, Pair<String, Long>>()
            runCatching {
                val f = file(playlistId)
                if (f.exists()) {
                    val o = JSONObject(f.readText())
                    o.keys().forEach { k ->
                        val e = o.getJSONObject(k)
                        k.toIntOrNull()?.let { out[it] = e.getString("c") to e.getLong("t") }
                    }
                }
            }
            out
        }

    /** Fresh answers (blank = no categories) for the ids in [ids]. */
    suspend fun fresh(playlistId: String, ids: Set<Int>): Map<Int, String> = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = System.currentTimeMillis()
            val m = load(playlistId)
            ids.mapNotNull { id -> m[id]?.takeIf { now - it.second < TTL_MS }?.let { id to it.first } }.toMap()
        }
    }

    suspend fun put(playlistId: String, answers: Map<Int, String>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = System.currentTimeMillis()
            val m = load(playlistId)
            answers.forEach { (id, c) -> m[id] = c to now }
            m.entries.removeAll { now - it.value.second >= TTL_MS }
            val o = JSONObject()
            m.forEach { (id, v) -> o.put(id.toString(), JSONObject().put("c", v.first).put("t", v.second)) }
            runCatching {
                val f = file(playlistId)
                val tmp = File(f.path + ".tmp")
                tmp.writeText(o.toString())
                tmp.renameTo(f)
            }
        }
    }

    private companion object {
        const val TTL_MS = 10 * 60 * 1000L
    }
}
