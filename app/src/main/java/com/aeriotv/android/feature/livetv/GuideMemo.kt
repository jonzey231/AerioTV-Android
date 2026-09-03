package com.aeriotv.android.feature.livetv

/**
 * Small per-computation memo for the guide's expensive derivations (grouped
 * channels, group names, grid rows). Tabs are composed one at a time, so
 * every tab switch used to recompute all of them from scratch on the
 * Streamer (Logan 2026-09-03: "why does every tab have to reload?"). Keys
 * hold the inputs; large collections are compared by REFERENCE (they are the
 * same instances out of the ViewModel StateFlows between entries), small
 * inputs by value. A few slots per name so the Live TV and Favorites guides
 * do not evict each other.
 */
object GuideMemo {
    /** Reference-identity wrapper for large inputs. */
    class Ref(val obj: Any?) {
        override fun equals(other: Any?) = other is Ref && other.obj === obj
        override fun hashCode() = System.identityHashCode(obj)
    }

    private class Slot(val key: Any, val value: Any?)
    private val slots = HashMap<String, ArrayDeque<Slot>>()
    private const val PER_NAME = 4

    @Suppress("UNCHECKED_CAST")
    fun <V> get(name: String, key: Any, compute: () -> V): V {
        synchronized(slots) {
            slots[name]?.firstOrNull { it.key == key }?.let { return it.value as V }
        }
        val v = compute()
        synchronized(slots) {
            val q = slots.getOrPut(name) { ArrayDeque() }
            q.removeAll { it.key == key }
            q.addFirst(Slot(key, v))
            while (q.size > PER_NAME) q.removeLast()
        }
        return v
    }
}
