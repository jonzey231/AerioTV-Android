package com.aeriotv.android.core.data.repository

/**
 * Splits a guide save into batches of about [maxRows] programmes, whole
 * channels at a time (Apple 6ae3fdd saves about 10k rows per batch).
 *
 * A guide save used to map EVERY programme of the window to a cache row and
 * insert them all at once: the rows, the filtered copy and the insert's
 * bindings were all resident together, so the save's peak grew with the
 * guide. Batching keeps one batch of rows resident at a time.
 *
 * A channel is never split across batches: the merge deletes, per channel,
 * the span between that channel's first and last programme in the batch, so
 * a channel's later batch would delete rows its earlier batch just wrote.
 * One channel with more than [maxRows] programmes is a batch of its own.
 * Channels keep their first-seen order; a channel's programmes keep theirs.
 */
internal object EpgSaveBatches {
    /** Apple's GuideStore.saveBatchRows. */
    const val ROWS = 10_000

    fun <T> byChannel(items: List<T>, maxRows: Int = ROWS, channel: (T) -> String): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        if (items.size <= maxRows) return listOf(items)
        val byChannel = LinkedHashMap<String, MutableList<T>>()
        for (item in items) byChannel.getOrPut(channel(item)) { ArrayList() }.add(item)
        val batches = ArrayList<List<T>>()
        var current = ArrayList<T>()
        for (rows in byChannel.values) {
            if (current.isNotEmpty() && current.size + rows.size > maxRows) {
                batches.add(current)
                current = ArrayList()
            }
            current.addAll(rows)
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }
}
