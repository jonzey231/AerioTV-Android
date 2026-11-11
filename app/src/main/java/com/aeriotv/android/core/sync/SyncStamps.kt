package com.aeriotv.android.core.sync

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Device-local change times for synced items that carry none of their own
 * (Logan's rule, 2026-11-10: pre-migration data never overwrites
 * post-migration data, so every merge needs WHEN each item changed, not when
 * a device happened to push it).
 *
 *  - [removed]: a tombstone time per item id (a removed favorite, reminder,
 *    watch-progress row, or playlist), so a merge never brings it back.
 *  - [touched]: the last local change time per item id for rows without a
 *    usable timestamp (a favorite's order, a reminder, credentials, a
 *    playlist's settings, a preference key).
 *
 * Only the default profile is recorded (Drive carries the default profile).
 * Never synced itself. Tombstones older than [TOMBSTONE_TTL_MS] are dropped.
 */
object SyncStamps {
    enum class Cat(val key: String) {
        WatchProgress("wp"), Favorites("fav"), Reminders("rem"), Credentials("cred"),
        Playlists("pl"), Preferences("pref"),
    }

    /** Ninety days: long enough for every device to sync, short enough to stay small. */
    const val TOMBSTONE_TTL_MS = 90L * 24 * 60 * 60 * 1000

    /** Storage (SharedPreferences in the app, a map in tests). */
    interface Store {
        fun get(name: String): String?
        fun put(name: String, value: String)
    }

    @Volatile private var store: Store? = null
    private val cache = ConcurrentHashMap<String, MutableMap<String, Long>>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Clock (tests replace it). */
    @Volatile var now: () -> Long = { System.currentTimeMillis() }

    /** Whether the current write belongs to the default profile (tests replace it). */
    @Volatile var recordable: () -> Boolean = {
        com.aeriotv.android.core.profiles.ProfileScope.current().isEmpty()
    }

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("aerio_sync_stamps", Context.MODE_PRIVATE)
        install(object : Store {
            override fun get(name: String): String? = prefs.getString(name, null)
            override fun put(name: String, value: String) { prefs.edit().putString(name, value).apply() }
        })
    }

    /** Tests: an in-memory store, everything recordable. */
    fun install(s: Store) {
        store = s
        cache.clear()
        lastSeenCache = null
    }

    private fun map(kind: String, cat: Cat): MutableMap<String, Long> {
        val name = "$kind|${cat.key}"
        return cache.getOrPut(name) {
            val raw = store?.get(name)
            val decoded: Map<String, Long> = raw?.let { runCatching { json.decodeFromString<Map<String, Long>>(it) }.getOrNull() } ?: emptyMap()
            ConcurrentHashMap(decoded)
        }
    }

    private fun save(kind: String, cat: Cat) {
        val m = map(kind, cat)
        if (kind == "removed") {
            val cutoff = now() - TOMBSTONE_TTL_MS
            m.entries.removeIf { it.value < cutoff }
        }
        val copy: Map<String, Long> = HashMap(m)
        store?.put("$kind|${cat.key}", json.encodeToString(copy))
    }

    /** [id] was removed locally (default profile only). */
    fun removed(cat: Cat, id: String, at: Long = now()) {
        if (!recordable()) return
        map("removed", cat)[id] = at
        save("removed", cat)
    }

    /** [id] changed locally (default profile only); clears any tombstone it had. */
    fun touched(cat: Cat, id: String, at: Long = now()) {
        if (!recordable()) return
        map("touched", cat)[id] = at
        save("touched", cat)
        if (map("removed", cat).remove(id) != null) save("removed", cat)
    }

    /** A merge or pull settled [id] at [at]: remember it without the profile check. */
    fun settleTouched(cat: Cat, id: String, at: Long) {
        map("touched", cat)[id] = at
        save("touched", cat)
    }

    fun settleRemoved(cat: Cat, id: String, at: Long) {
        map("removed", cat)[id] = at
        save("removed", cat)
    }

    fun clearRemoved(cat: Cat, id: String) {
        if (map("removed", cat).remove(id) != null) save("removed", cat)
    }

    /** Preference values last synced ("lane:key" to value), null before the first sync of this build. */
    fun prefBaseline(): Map<String, String>? =
        store?.get("prefBaseline")?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }

    /** When [setPrefBaseline] last ran (0 = never). */
    fun prefBaselineAt(): Long = store?.get("prefBaselineAt")?.toLongOrNull() ?: 0L

    /** [stampTime] false keeps [prefBaselineAt] (a pull recording what it applied). */
    fun setPrefBaseline(values: Map<String, String>, stampTime: Boolean = true) {
        val copy: Map<String, String> = HashMap(values)
        store?.put("prefBaseline", json.encodeToString(copy))
        if (stampTime) store?.put("prefBaselineAt", now().toString())
    }

    // ── Setting change times at the moment of the change (Logan 2026-11-10) ──

    private val prefLock = Any()
    @Volatile private var lastSeenCache: Map<String, String>? = null

    /** The synced setting values as of the last write seen here, null before the first. */
    fun prefLastSeen(): Map<String, String>? = synchronized(prefLock) {
        lastSeenCache ?: store?.get("prefLastSeen")
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            ?.also { lastSeenCache = it }
    }

    /** Records [values] as seen without stamping anything (after a sync apply). */
    fun setPrefLastSeen(values: Map<String, String>) = synchronized(prefLock) {
        if (lastSeenCache == values) return@synchronized
        val copy: Map<String, String> = HashMap(values)
        lastSeenCache = copy
        store?.put("prefLastSeen", json.encodeToString(copy))
    }

    /**
     * A local write changed settings: every synced key whose value differs
     * from the last write seen gets a change time of [at], the moment of the
     * change. The first call only records the values (their age is unknown).
     * Returns the keys stamped. A key that disappeared (back to its default)
     * is not stamped: the snapshot no longer carries it.
     */
    fun notePrefValues(values: Map<String, String>, at: Long = now()): List<String> = synchronized(prefLock) {
        val last = prefLastSeen()
        if (last == null) { setPrefLastSeen(values); return@synchronized emptyList() }
        val changed = values.filter { (k, v) -> last[k] != v }.keys.sorted()
        changed.forEach { settleTouched(Cat.Preferences, it, at) }
        setPrefLastSeen(values)
        changed
    }

    // ── Playlist merge (one server account is one playlist) ──

    private fun strMap(name: String): Map<String, String> =
        store?.get(name)?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap()

    private fun putStrMap(name: String, m: Map<String, String>) {
        val copy: Map<String, String> = HashMap(m)
        store?.put(name, json.encodeToString(copy))
    }

    /** The account another device reported for [playlistId] (identity only). */
    fun learnedAccount(playlistId: String): String? = strMap("accounts")[playlistId]

    @Synchronized
    fun learnAccount(playlistId: String, account: String?) {
        val a = account?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val m = strMap("accounts")
        if (m[playlistId] != a) putStrMap("accounts", m + (playlistId to a))
    }

    /** Keys (request gate hashes) a playlist merge set aside here: never adopted or pushed again. */
    fun isSuperseded(keyHash: String): Boolean = keyHash in strMap("supersededKeys")

    @Synchronized
    fun supersede(keyHash: String) {
        val m = strMap("supersededKeys")
        if (keyHash !in m) putStrMap("supersededKeys", m + (keyHash to now().toString()))
    }

    fun removedAt(cat: Cat, id: String): Long? = map("removed", cat)[id]
    fun touchedAt(cat: Cat, id: String): Long? = map("touched", cat)[id]
    fun removedAll(cat: Cat): Map<String, Long> = HashMap(map("removed", cat))
    fun touchedAll(cat: Cat): Map<String, Long> = HashMap(map("touched", cat))
}
