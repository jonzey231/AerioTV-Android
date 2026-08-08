package com.aeriotv.android.feature.ondemand

/**
 * Pure row computation for the Movies & TV Home section.
 *
 * Deliberate mirror of Apple's `HomeRowsBuilder`
 * (Aerio/Features/MoviesTV/Home/HomeRowsBuilder.swift): both platforms must
 * compute the SAME Home layout from the same data, and the cheapest way to keep
 * that true is to keep the rules in one small, comparable, dependency-free file
 * per repo rather than scattered through composables.
 *
 * No Compose, no Room, no Hilt: every input is a plain data class, so the rules
 * are unit-testable without an in-memory database or a Robolectric shell.
 *
 * Row order (dossier section 5.3), each row hidden when it would be empty:
 *   1. Continue Watching  (in-progress movies + in-progress episodes merged,
 *                          deduped per series, sorted by last activity)
 *   2. Recently Added, one row per PERSONAL library
 *   3. Recently Added, one row per provider catalog
 */
object HomeRows {

    /** Default shelf depth. Matches Apple's `defaultShelfLimit`. */
    const val DEFAULT_SHELF_LIMIT = 20

    /**
     * An item is "in progress" if it has a position, is not flagged finished,
     * and is not within five minutes of the end. Same heuristic the old rails
     * used and the same one Apple applies, kept here so Home and any other
     * caller cannot drift apart. Phase 5 replaces this with the shared
     * WatchedRule; until then this is the single Android definition.
     */
    private const val NEAR_END_MS = 5L * 60L * 1000L

    /**
     * A watch-progress row reduced to what Home needs. Mapping from
     * WatchProgressEntity happens at the call site so this file stays free of
     * the persistence layer.
     */
    data class ProgressSnapshot(
        val videoId: String,
        /** "movie" or "episode". */
        val vodType: String,
        /** Parent show identifier for episodes; null for movies. */
        val seriesId: String?,
        val positionMs: Long,
        val durationMs: Long,
        val isFinished: Boolean,
        val updatedAt: Long,
    )

    /** A catalog item plus the library facts Home groups on. */
    data class CatalogSnapshot<T>(
        val item: T,
        val libraryKey: String,
        val libraryDisplayName: String,
        val isPersonalLibrary: Boolean,
        /**
         * Server-reported add time, or null when the server did not supply one.
         * Undated items sort last within their row rather than being dropped: a
         * missing timestamp is a metadata gap, not a reason to hide content the
         * user owns.
         */
        val createdAt: Long?,
        /** Title, used only as the tie-break when add times are equal or absent. */
        val sortName: String,
    )

    data class Shelf<T>(
        val id: String,
        val title: String,
        val isPersonalLibrary: Boolean,
        val items: List<T>,
    )

    /**
     * Merges in-progress movies and episodes into one ordered list of ids.
     *
     * Two rules carry the weight:
     *  - **Deduped per series.** A show the user is midway through appears
     *    exactly once, represented by its most recently touched episode.
     *    Without this, binge-watching a season floods the row and pushes every
     *    other title off screen. Movies are never deduped against each other;
     *    they have no parent to collapse into.
     *  - **Sorted by last activity**, newest first, across BOTH types, so the
     *    thing the user last watched is always the first card. The old Android
     *    layout split these into two separate rails, which meant the most
     *    recent thing could be the second row's third card.
     *
     * The id tie-break is not cosmetic: a sync import writes many rows sharing
     * an updatedAt, and without a stable secondary sort the row reshuffles
     * between recompositions.
     */
    fun continueWatching(progress: List<ProgressSnapshot>): List<String> {
        val live = progress.filter { row ->
            row.positionMs > 0L && !row.isFinished &&
                (row.durationMs <= 0L || row.positionMs < row.durationMs - NEAR_END_MS)
        }
        val ordered = live.sortedWith(
            compareByDescending<ProgressSnapshot> { it.updatedAt }.thenBy { it.videoId },
        )

        val seenSeries = HashSet<String>()
        val result = ArrayList<String>(ordered.size)
        for (row in ordered) {
            if (row.vodType == "episode" && !row.seriesId.isNullOrEmpty()) {
                // Already newest-first, so the first episode seen for a show IS
                // its most recent one.
                if (!seenSeries.add(row.seriesId)) continue
            }
            result.add(row.videoId)
        }
        return result
    }

    /**
     * One shelf per library, personal libraries first.
     *
     * Within each group libraries are ordered by name so Home is stable across
     * launches: a map-iteration order would reshuffle rows on every cold start,
     * which reads as a bug even though the content is identical.
     */
    fun <T> recentlyAddedShelves(
        catalog: List<CatalogSnapshot<T>>,
        limit: Int = DEFAULT_SHELF_LIMIT,
    ): List<Shelf<T>> {
        if (limit <= 0) return emptyList()

        return catalog
            .groupBy { it.libraryKey }
            .mapNotNull { (key, entries) ->
                val first = entries.firstOrNull() ?: return@mapNotNull null
                val items = entries
                    .sortedWith(
                        // Dated before undated, newest first, then by title.
                        compareBy<CatalogSnapshot<T>> { it.createdAt == null }
                            .thenByDescending { it.createdAt ?: Long.MIN_VALUE }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.sortName },
                    )
                    .take(limit)
                    .map { it.item }
                if (items.isEmpty()) return@mapNotNull null
                Shelf(
                    id = key,
                    title = first.libraryDisplayName,
                    isPersonalLibrary = first.isPersonalLibrary,
                    items = items,
                )
            }
            .sortedWith(
                compareByDescending<Shelf<T>> { it.isPersonalLibrary }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                    .thenBy { it.id },
            )
    }
}
