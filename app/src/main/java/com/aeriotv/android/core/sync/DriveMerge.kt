package com.aeriotv.android.core.sync

/**
 * Pure Drive merge rules (Logan 2026-11-10: pre-migration data never
 * overwrites post-migration data). A push never replaces a whole file: it
 * re-reads the remote file and merges per item, newest change time wins, and
 * a tie keeps the remote copy, so a device can only ever ADD what it changed
 * after the remote did. Deletions travel as tombstones (removedAt), merged by
 * the same rule, so a removed item is never brought back by an older copy.
 *
 * All functions here are pure; the caller supplies local change times
 * ([SyncStamps]) and the current time.
 */
internal object DriveMerge {

    /**
     * Newest [stamp] per [id] wins; a tie keeps the remote. Remote order first,
     * then local-only items. [onLocalWin] lets a category keep fields only the
     * remote copy carries (canonical ids).
     */
    fun <T> byStamp(
        remote: List<T>,
        local: List<T>,
        id: (T) -> String,
        stamp: (T) -> Long,
        onLocalWin: (local: T, remote: T) -> T = { l, _ -> l },
    ): List<T> {
        val localById = LinkedHashMap<String, T>()
        local.forEach { l -> localById[id(l)] = l }
        val out = ArrayList<T>(remote.size + local.size)
        val seen = HashSet<String>()
        for (r in remote) {
            val k = id(r)
            if (!seen.add(k)) continue
            val l = localById[k]
            out += if (l != null && stamp(l) > stamp(r)) onLocalWin(l, r) else r
        }
        for ((k, l) in localById) if (seen.add(k)) out += l
        return out
    }

    // ── Watch progress ────────────────────────────────────────────────────

    fun progressStamp(e: WatchProgressSnapshotEntry): Long = maxOf(e.updatedAt, e.removedAt ?: 0L)

    fun progressTombstone(videoId: String, at: Long) = WatchProgressSnapshotEntry(
        videoId = videoId, title = "", positionMs = 0L, durationMs = 0L, updatedAt = 0L, removedAt = at,
    )

    /** Local rows plus local tombstones (ids with no live row) for the merge. */
    fun progressLocal(rows: List<WatchProgressSnapshotEntry>, removed: Map<String, Long>): List<WatchProgressSnapshotEntry> {
        val live = rows.map { it.videoId }.toHashSet()
        return rows + removed.filterKeys { it !in live }.map { (id, at) -> progressTombstone(id, at) }
    }

    fun mergeProgress(remote: List<WatchProgressSnapshotEntry>, local: List<WatchProgressSnapshotEntry>): List<WatchProgressSnapshotEntry> =
        byStamp(remote, local, { it.videoId }, ::progressStamp) { l, r ->
            if (l.removedAt == null && l.dispatcharrVodId == null && r.removedAt == null) l.copy(dispatcharrVodId = r.dispatcharrVodId) else l
        }

    // ── Favorites ─────────────────────────────────────────────────────────

    fun favoriteStamp(e: FavoriteSnapshotEntry): Long = maxOf(e.addedAt, e.updatedAt ?: 0L, e.removedAt ?: 0L)

    /** Local favorites stamped with their own change time (add or reorder), plus tombstones. */
    fun favoritesLocal(rows: List<FavoriteSnapshotEntry>, touched: Map<String, Long>, removed: Map<String, Long>): List<FavoriteSnapshotEntry> {
        val live = rows.map { r -> r.copy(updatedAt = maxOf(r.addedAt, touched[r.channelId] ?: 0L, r.updatedAt ?: 0L)) }
        val ids = live.map { it.channelId }.toHashSet()
        return live + removed.filterKeys { it !in ids }.map { (id, at) ->
            FavoriteSnapshotEntry(channelId = id, channelName = "", displayOrder = 0L, addedAt = 0L, removedAt = at)
        }
    }

    fun mergeFavorites(remote: List<FavoriteSnapshotEntry>, local: List<FavoriteSnapshotEntry>): List<FavoriteSnapshotEntry> =
        byStamp(remote, local, { it.channelId }, ::favoriteStamp) { l, r ->
            if (l.removedAt == null && l.dispatcharrChannelId == null && r.removedAt == null) l.copy(dispatcharrChannelId = r.dispatcharrChannelId) else l
        }

    // ── Reminders ─────────────────────────────────────────────────────────

    fun reminderStamp(e: ReminderSnapshotEntry): Long = maxOf(e.updatedAt ?: 0L, e.removedAt ?: 0L)

    fun remindersLocal(rows: List<ReminderSnapshotEntry>, touched: Map<String, Long>, removed: Map<String, Long>): List<ReminderSnapshotEntry> {
        val live = rows.map { r -> r.copy(updatedAt = maxOf(touched[r.reminderKey] ?: 0L, r.updatedAt ?: 0L)) }
        val ids = live.map { it.reminderKey }.toHashSet()
        return live + removed.filterKeys { it !in ids }.map { (key, at) ->
            ReminderSnapshotEntry(reminderKey = key, channelName = "", programTitle = "", startMillis = 0L, endMillis = 0L, removedAt = at)
        }
    }

    /** Merged reminders without the spent ones: a live reminder that ended a day ago, a tombstone past its TTL. */
    fun mergeReminders(remote: List<ReminderSnapshotEntry>, local: List<ReminderSnapshotEntry>, nowMs: Long): List<ReminderSnapshotEntry> =
        byStamp(remote, local, { it.reminderKey }, ::reminderStamp).filter { e ->
            val gone = e.removedAt
            if (gone != null) gone >= nowMs - SyncStamps.TOMBSTONE_TTL_MS else e.endMillis >= nowMs - 24L * 60 * 60 * 1000
        }

    // ── Watchlist (already carries its own stamps) ────────────────────────

    private fun watchKey(vodType: String, vodId: String, serverId: String?) = "$vodType|$vodId|${serverId.orEmpty()}"

    fun mergeWatchlist(remote: WatchlistSnapshot?, local: WatchlistSnapshot): Pair<List<WatchlistSnapshotEntry>, List<HiddenTitleSnapshotEntry>> {
        val entries = byStamp(
            remote?.entries.orEmpty(), local.entries,
            { watchKey(it.vodType, it.vodId, it.serverId) }, { maxOf(it.addedAt, it.removedAt ?: 0L) },
        ) { l, r -> if (l.dispatcharrVodId == null) l.copy(dispatcharrVodId = r.dispatcharrVodId) else l }
        val hidden = byStamp(
            remote?.hidden.orEmpty(), local.hidden,
            { watchKey(it.vodType, it.vodId, it.serverId) }, { maxOf(it.hiddenAt, it.unhiddenAt ?: 0L) },
        ) { l, r -> if (l.dispatcharrVodId == null) l.copy(dispatcharrVodId = r.dispatcharrVodId) else l }
        return entries to hidden
    }

    // ── Playlists ─────────────────────────────────────────────────────────

    fun playlistStamp(e: PlaylistSnapshotEntry): Long = maxOf(e.updatedAt ?: 0L, e.removedAt ?: 0L)

    fun playlistsLocal(rows: List<PlaylistSnapshotEntry>, touched: Map<String, Long>, removed: Map<String, Long>): List<PlaylistSnapshotEntry> {
        val live = rows.map { r -> r.copy(updatedAt = maxOf(touched[r.id] ?: 0L, r.updatedAt ?: 0L)) }
        val ids = live.map { it.id }.toHashSet()
        return live + removed.filterKeys { it !in ids }.map { (id, at) ->
            PlaylistSnapshotEntry(id = id, name = "", sourceType = "", urlString = "", removedAt = at)
        }
    }

    fun mergePlaylists(remote: List<PlaylistSnapshotEntry>, local: List<PlaylistSnapshotEntry>): List<PlaylistSnapshotEntry> {
        // The creation time is the earliest either side has seen (the original's).
        val created = HashMap<String, Long>()
        (remote + local).forEach { e -> e.createdAt?.let { c -> created[e.id] = minOf(created[e.id] ?: c, c) } }
        return byStamp(remote, local, { it.id }, ::playlistStamp).map { e -> created[e.id]?.let { e.copy(createdAt = it) } ?: e }
    }

    // ── Credentials ───────────────────────────────────────────────────────

    /**
     * Newest per playlist wins, field by field never blank: a winner's empty
     * field keeps the other side's non-empty value, so an empty password never
     * overwrites a saved one. A key this device holds as rejected is dropped
     * from its own side before the merge ([rejectedKey]), so it is never pushed.
     */
    fun mergeCredentials(
        remote: List<CredentialsSnapshotEntry>,
        local: List<CredentialsSnapshotEntry>,
        rejectedKey: (String) -> Boolean,
    ): List<CredentialsSnapshotEntry> {
        val cleanLocal = local.map { l -> if (l.apiKey != null && rejectedKey(l.apiKey)) l.copy(apiKey = null) else l }
        val merged = byStamp(remote, cleanLocal, { it.playlistId }, { it.updatedAt ?: 0L }) { l, r ->
            l.copy(
                username = l.username.nonBlank() ?: r.username,
                password = l.password.nonBlank() ?: r.password,
                apiKey = l.apiKey.nonBlank() ?: r.apiKey,
                account = l.account.nonBlank() ?: r.account,
            )
        }
        val localAccount = local.associate { it.playlistId to it.account }
        val remoteAccount = remote.associate { it.playlistId to it.account }
        return merged.map { m ->
            m.copy(
                // A key this device knows is rejected stays out of Drive, whoever wrote it.
                apiKey = m.apiKey?.takeIf { !rejectedKey(it) },
                // The account name fills in from either side (identity only).
                account = m.account.nonBlank() ?: localAccount[m.playlistId].nonBlank() ?: remoteAccount[m.playlistId].nonBlank(),
            )
        }
    }

    /** Credentials of playlists deleted here ([deleted]: a deletion time newer than any change) are not kept in Drive. */
    fun dropDeletedPlaylists(entries: List<CredentialsSnapshotEntry>, deleted: (String) -> Boolean): List<CredentialsSnapshotEntry> =
        entries.filterNot { deleted(it.playlistId) }

    /**
     * What a pulled credentials entry may change locally: only when it is newer
     * than this device's own change time, a field at a time, never a non-empty
     * value to empty, and never to a key held as rejected. Null when nothing
     * applies. A field this device has empty is filled whatever the times say
     * (a fresh device, or a file written before change times existed). A key
     * the server rejected here always gives way to a synced key that is not
     * itself rejected, whatever the times (Apple parity, 2026-11-10: a stale
     * device kept its dead key over the synced good one when neither side had
     * a change time).
     */
    fun credentialsToApply(
        localUsername: String?, localPassword: String?, localApiKey: String?, localTime: Long,
        remote: CredentialsSnapshotEntry,
        rejectedKey: (String) -> Boolean,
    ): Triple<String?, String?, String?>? {
        val newer = (remote.updatedAt ?: 0L) > localTime
        fun pick(local: String?, rem: String?, isKey: Boolean = false): String? {
            val r = rem.nonBlank() ?: return local
            if (isKey && rejectedKey(r)) return local
            if (isKey && local.nonBlank() != null && rejectedKey(local!!) && r != local.trim()) return r
            return if (local.nonBlank() == null || newer) r else local
        }
        val u = pick(localUsername, remote.username)
        val p = pick(localPassword, remote.password)
        val k = pick(localApiKey, remote.apiKey, isKey = true)
        return if (u == localUsername && p == localPassword && k == localApiKey) null else Triple(u, p, k)
    }

    // ── Preferences (per key) ─────────────────────────────────────────────

    /** One lane's values plus their change times, keyed "lane:key". */
    data class PrefState(val v: Map<String, String>, val tv: Map<String, String>, val mobile: Map<String, String>, val times: Map<String, Long>) {
        fun lane(name: String): Map<String, String> = when (name) { "tv" -> tv; "mobile" -> mobile; else -> v }
    }

    /**
     * Remote change time of [lane]:[key]: its per-key time, or 0 (unknown age)
     * for a key without one. An older file's envelope time is its PUSH time
     * (a stale device that came online stamped it "now"), so it never stands
     * in for a change time.
     */
    fun remotePrefTime(times: Map<String, Long>, lane: String, key: String): Long = times["$lane:$key"] ?: 0L

    /** Per key newest wins; a tie keeps the remote. Keys only one side carries are kept. */
    fun mergePrefs(remote: PrefState?, local: PrefState): PrefState {
        val lanes = listOf("v", "tv", "mobile")
        val out = lanes.associateWith { LinkedHashMap<String, String>() }
        val times = LinkedHashMap<String, Long>()
        for (lane in lanes) {
            val rl = remote?.lane(lane).orEmpty()
            val ll = local.lane(lane)
            for ((k, rv) in rl) {
                val rt = remotePrefTime(remote?.times.orEmpty(), lane, k)
                val lv = ll[k]
                val lt = local.times["$lane:$k"] ?: 0L
                if (lv != null && lt > rt) { out.getValue(lane)[k] = lv; times["$lane:$k"] = lt }
                else { out.getValue(lane)[k] = rv; times["$lane:$k"] = rt }
            }
            for ((k, lv) in ll) if (k !in rl) {
                out.getValue(lane)[k] = lv
                times["$lane:$k"] = local.times["$lane:$k"] ?: 0L
            }
        }
        return PrefState(out.getValue("v"), out.getValue("tv"), out.getValue("mobile"), times)
    }

    /**
     * Local change times from the values last synced ([baseline], "lane:key"
     * to value): a key whose value differs changed here since, at [nowMs]. A
     * device with no baseline yet (first run of this build) stamps nothing:
     * its values are of unknown age and never outrank the remote's.
     */
    fun localPrefTimes(
        local: PrefState,
        baseline: Map<String, String>?,
        previous: Map<String, Long>,
        nowMs: Long,
        /** When the baseline was recorded: a change time after it was stamped when the change happened. */
        baselineAt: Long = 0L,
    ): Map<String, Long> {
        if (baseline == null) return previous
        val out = HashMap(previous)
        for (lane in listOf("v", "tv", "mobile")) for ((k, v) in local.lane(lane)) {
            val id = "$lane:$k"
            // Fallback only: a change the moment-of-change stamp missed gets "now".
            if (baseline[id] != v && (previous[id] ?: 0L) <= baselineAt) out[id] = nowMs
        }
        return out
    }

    /**
     * Pull: whether [lane]:[key] from the remote applies here: it changed after
     * this device's own value did. [freshDevice] (no pull ever completed on
     * this install, the restore case) takes every key, as before.
     */
    fun prefApplies(remoteTime: Long, localTime: Long?, freshDevice: Boolean = false): Boolean =
        freshDevice || remoteTime > (localTime ?: 0L)

    // ── Envelope ──────────────────────────────────────────────────────────

    /**
     * The merged file's change time: the newest item time it carries, never
     * the push time, so a device that was offline for weeks never looks newest.
     * Never older than the remote's own time.
     */
    fun mergedTime(remoteTime: Long?, itemTimes: List<Long>): Long = maxOf(remoteTime ?: 0L, itemTimes.maxOrNull() ?: 0L)

    /**
     * The merged file's format: an existing remote file keeps its own format
     * (the explicit backup update is the only path that raises it, so pre-
     * migration rows are never stamped current); a brand-new file is current.
     */
    fun mergedFormat(remoteExists: Boolean, remoteFormat: Int?, current: Int): Int? = if (remoteExists) remoteFormat else current

    private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }
}
