package com.aeriotv.android.core.data.repository

import com.aeriotv.android.core.data.db.entity.EpgProgrammeEntity

/**
 * One day of the background EPG sweep, fetched rows against the cached rows,
 * reduced to the writes that actually change something.
 *
 * Identity is (channel, start): the cache holds one row per pair (unique
 * index). A fetched row whose pair is not cached is ADDED. A cached pair the
 * fetch still carries is UNCHANGED when every stored field matches, and
 * CHANGED otherwise (a game that ends early keeps its start and moves its
 * end; a slot that turns into a different show keeps its start and changes
 * its title). A cached row is REMOVED when its channel is in scope, the fetch
 * carried that channel this day, the row starts inside the span the fetch
 * covered for that channel and not before now (already-aired rows are the
 * catch-up archive), and the fetch no longer carries its pair: a Game 7 that
 * is not played anymore.
 *
 * Rows outside [inScope] (channels fed by EPG sources that did not change)
 * are never written. An unchanged day costs zero writes.
 *
 * Pure: no Room, no clock, so the rules are unit tested
 * (EpgDayDiffTest).
 */
internal object EpgDayDiff {

    class Result(
        /** Added and changed rows, written with REPLACE on (channel, start). */
        val upserts: List<EpgProgrammeEntity>,
        /** Ids of removed rows. */
        val deleteIds: List<Long>,
        /**
         * (row id, new program id) for rows whose content is identical and
         * only the server's program id moved. A Dispatcharr source refresh
         * re-creates its programs wholesale, so every program gets a new id
         * even when nothing about it changed; the id still drives Program
         * Info and category lookups, so it is updated in place (one column
         * UPDATE), never by a delete and reinsert.
         */
        val programIdUpdates: List<Pair<Long, Int?>>,
        val fetchedInScope: Int,
        val unchanged: Int,
        val added: Int,
        val changed: Int,
        val removed: Int,
    ) {
        /** Rows deleted or inserted. Program id updates are counted apart. */
        val writes: Int get() = upserts.size + deleteIds.size
        val remapped: Int get() = programIdUpdates.size
    }

    /**
     * @param fetched the day's fetched rows, already in cache shape.
     * @param cached the cached rows overlapping the same day.
     * @param inScope channels this sweep may write; null means every channel.
     * @param nowMillis removals never reach back before this instant.
     */
    fun compute(
        fetched: List<EpgProgrammeEntity>,
        cached: List<EpgProgrammeEntity>,
        inScope: ((String) -> Boolean)?,
        nowMillis: Long,
    ): Result {
        val cachedByKey = HashMap<Key, EpgProgrammeEntity>(cached.size * 2)
        for (c in cached) cachedByKey[Key(c.channelId, c.startMillis)] = c

        // Last fetched row wins on a duplicated pair, like the REPLACE insert.
        val fetchedByKey = LinkedHashMap<Key, EpgProgrammeEntity>(fetched.size * 2)
        val spanByChannel = HashMap<String, LongArray>()
        for (f in fetched) {
            if (f.endMillis - f.startMillis < MIN_DRAWABLE_MS) continue
            if (inScope != null && !inScope(f.channelId)) continue
            fetchedByKey[Key(f.channelId, f.startMillis)] = f
            val span = spanByChannel[f.channelId]
            if (span == null) {
                spanByChannel[f.channelId] = longArrayOf(f.startMillis, f.endMillis)
            } else {
                if (f.startMillis < span[0]) span[0] = f.startMillis
                if (f.endMillis > span[1]) span[1] = f.endMillis
            }
        }

        val upserts = ArrayList<EpgProgrammeEntity>()
        val idUpdates = ArrayList<Pair<Long, Int?>>()
        var unchanged = 0
        var added = 0
        var changed = 0
        for ((key, f) in fetchedByKey) {
            val c = cachedByKey[key]
            when {
                c == null -> { added++; upserts.add(f) }
                sameContent(c, f) -> {
                    unchanged++
                    if (c.dispatcharrProgramId != f.dispatcharrProgramId) idUpdates.add(c.id to f.dispatcharrProgramId)
                }
                else -> { changed++; upserts.add(f) }
            }
        }

        val deleteIds = ArrayList<Long>()
        for (c in cached) {
            val span = spanByChannel[c.channelId] ?: continue
            if (c.startMillis < maxOf(nowMillis, span[0]) || c.startMillis >= span[1]) continue
            if (Key(c.channelId, c.startMillis) in fetchedByKey) continue
            deleteIds.add(c.id)
        }

        return Result(
            upserts = upserts,
            deleteIds = deleteIds,
            programIdUpdates = idUpdates,
            fetchedInScope = fetchedByKey.size,
            unchanged = unchanged,
            added = added,
            changed = changed,
            removed = deleteIds.size,
        )
    }

    /**
     * Every field the grid supplies. Not compared: id and fetchedAt (cache
     * bookkeeping), the server program id (see [Result.programIdUpdates]), category (the grid omits it and the lazy enrichment fills
     * it in later, so an empty fetched category is not a change), and source
     * (which layer wrote the row, not what the row says).
     */
    private fun sameContent(c: EpgProgrammeEntity, f: EpgProgrammeEntity): Boolean =
        c.endMillis == f.endMillis &&
            c.title == f.title &&
            c.description == f.description &&
            c.subTitle == f.subTitle &&
            c.season == f.season &&
            c.episode == f.episode &&
            c.isNew == f.isNew &&
            c.isLiveBroadcast == f.isLiveBroadcast &&
            c.isPremiere == f.isPremiere &&
            c.isFinale == f.isFinale &&
            c.isRepeat == f.isRepeat &&
            c.iconUrl == f.iconUrl &&
            (f.category.isEmpty() || c.category == f.category)

    private data class Key(val channelId: String, val startMillis: Long)

    /** Same floor the cache merge applies: shorter rows are feed noise. */
    private const val MIN_DRAWABLE_MS = 30_000L
}
