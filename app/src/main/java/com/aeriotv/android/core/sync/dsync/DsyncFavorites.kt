package com.aeriotv.android.core.sync.dsync

import com.aeriotv.android.core.data.db.entity.FavoriteChannelEntity

/**
 * Dispatcharr Sync `favorites` apply, as pure logic: the server's integer
 * channel ids mapped onto the session's channel snapshot, written to the
 * session's OWN profile scope. The scope is always passed in (the session's
 * token), never read from ProfileScope.current(), so a pull can never write
 * rows another profile owns or that no profile reads.
 */
internal object DsyncFavorites {

    data class Plan(
        /** Local channel ids the server ids map to, in server order. */
        val wanted: List<String>,
        /** Local favorites of this scope that the server list dropped. */
        val deletes: List<String>,
        /** Rows to upsert, every one stamped with [scope]. */
        val upserts: List<FavoriteChannelEntity>,
    )

    /**
     * [local] must be the favorites of [scope] only. A local favorite whose
     * channel is not in the snapshot ([toInt] has no entry) is kept: this
     * device cannot tell whether the server list meant to drop it.
     */
    fun plan(
        ids: List<Int>,
        toLocal: Map<Int, String>,
        toInt: Map<String, Int>,
        nameOf: Map<Int, String>,
        local: List<FavoriteChannelEntity>,
        scope: String,
        now: Long,
    ): Plan {
        val wanted = ids.mapNotNull { toLocal[it] }.distinct()
        val wantedSet = wanted.toHashSet()
        val mine = local.filter { it.profileScope == scope }
        val deletes = mine.filter { it.channelId in toInt && it.channelId !in wantedSet }.map { it.channelId }
        val byId = mine.associateBy { it.channelId }
        val upserts = wanted.mapIndexed { i, lid ->
            val cur = byId[lid]
            FavoriteChannelEntity(
                channelId = lid,
                channelName = toInt[lid]?.let { nameOf[it] } ?: cur?.channelName.orEmpty(),
                displayOrder = i.toLong(),
                addedAt = cur?.addedAt ?: now,
                profileScope = scope,
            )
        }
        return Plan(wanted, deletes, upserts)
    }

    /** "[DSYNC] favorites in|out" log line: ids, mapped, written, scope, playlist. */
    fun logLine(dir: String, ids: Int, mapped: Int, written: Int, scope: String, playlistId: String): String =
        "[DSYNC] favorites $dir: $ids ids, $mapped mapped, $written written, " +
            "scope=${scope.take(8).ifEmpty { "default" }}, playlist=${playlistId.take(8)}"
}
