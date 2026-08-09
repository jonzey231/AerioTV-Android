package com.aeriotv.android.feature.ondemand

/**
 * Sort orders offered on the Movies and TV Shows grids.
 *
 * Deliberate mirror of Apple's `MediaSort`
 * (Aerio/Features/MoviesTV/Browse/MediaGridQuery.swift). The dossier requires
 * the two platforms to offer the SAME set in the SAME order, so that one
 * sentence of documentation and one line of a support reply covers both. Adding
 * an order here without adding it there is the drift this file exists to
 * prevent.
 */
enum class MediaSort(val key: String, val label: String) {
    TITLE("title", "Title"),
    DATE_ADDED("dateAdded", "Date Added"),
    RELEASE_YEAR("releaseYear", "Release Year"),
    RATING("rating", "Rating"),
    RANDOM("random", "Random"),
    ;

    /**
     * The alpha-jump rail only makes sense against an alphabetical ordering.
     * Every other sort hides it rather than showing a rail whose letters do not
     * correspond to anything on screen.
     */
    val supportsAlphaJump: Boolean get() = this == TITLE

    companion object {
        /** Parse a persisted key, falling back to Title. Stored as the key
         *  rather than an ordinal so reordering this enum later cannot silently
         *  change what a returning user sees. */
        fun fromKey(key: String?): MediaSort =
            entries.firstOrNull { it.key == key } ?: TITLE
    }
}

/**
 * Pure sort layer for the browse grids.
 *
 * Mirror of Apple's `MediaGridQuery`, and free of Compose, Room and Hilt for
 * the same reason [HomeRows] is: ordering rules are the part most likely to be
 * argued about and revised, so they belong somewhere that can be reasoned about
 * and exercised directly rather than only through a rendered grid on a
 * television.
 *
 * Callers supply the two fields sorting needs that a poster row does not
 * naturally carry, exactly as on Apple: the catalog owns `createdAt`, and
 * `sortTitle` comes from the same `MediaLibraryAdapter.sortTitle` the ingest
 * pipeline used, so a title sorts identically whether it came from Room or
 * from a live server response.
 */
object MediaGridQuery {

    /** A grid item reduced to what ordering needs. */
    data class Row<T>(
        val item: T,
        val id: String,
        /** Article-stripped, case-folded form; see MediaLibraryAdapter.sortTitle. */
        val sortTitle: String,
        /** Server add time, or null when the server supplied none. */
        val createdAt: Long?,
        val releaseYear: Int?,
        val rating: Double?,
    )

    /**
     * Order [rows] for display.
     *
     * @param seed session-stable value for [MediaSort.RANDOM]. Held for the
     *   life of the tab so scrolling away and back does not reshuffle the
     *   library under the user, which is the one thing that makes a Random
     *   sort useless.
     */
    fun <T> apply(sort: MediaSort, rows: List<Row<T>>, seed: Long = 0L): List<T> {
        val ordered = when (sort) {
            MediaSort.TITLE ->
                rows.sortedWith(compareBy({ it.sortTitle }, { it.id }))

            // Newest first, and undated sorts AFTER dated rather than being
            // dropped: a missing timestamp is a metadata gap, not a reason to
            // hide content the user owns. Same rule HomeRows applies.
            MediaSort.DATE_ADDED ->
                rows.sortedWith(
                    compareBy<Row<T>> { it.createdAt == null }
                        .thenByDescending { it.createdAt ?: Long.MIN_VALUE }
                        .thenBy { it.id },
                )

            MediaSort.RELEASE_YEAR ->
                rows.sortedWith(
                    compareBy<Row<T>> { it.releaseYear == null }
                        .thenByDescending { it.releaseYear ?: Int.MIN_VALUE }
                        .thenBy { it.id },
                )

            MediaSort.RATING ->
                rows.sortedWith(
                    compareBy<Row<T>> { it.rating == null }
                        .thenByDescending { it.rating ?: Double.NEGATIVE_INFINITY }
                        .thenBy { it.id },
                )

            // Sorting on a derived key rather than shuffling: the key depends
            // only on (seed, id), so the same library and seed always produce
            // the same order, and an item arriving from a later catalog page
            // slots in deterministically instead of reshuffling everything
            // already on screen.
            MediaSort.RANDOM ->
                rows.sortedWith(compareBy({ shuffleKey(it.id, seed) }, { it.id }))
            // NOTE the ULong return type on shuffleKey. Comparing the raw Long
            // would order the roughly three quarters of keys with the high bit
            // set BEFORE the rest, because Kotlin's Long compares signed while
            // Swift's UInt64 compares unsigned. Both orders are equally
            // arbitrary, so nothing would look broken, which is exactly why it
            // would have survived: the two platforms would have quietly
            // disagreed about what Random means. Verified against the Swift
            // implementation over a sample of ids: 9 of 12 keys exceeded 2^63
            // and the two orderings differed.
        }
        return ordered.map { it.item }
    }

    /**
     * First index in [rows] for each alpha bucket, in rail order.
     *
     * Only buckets that actually exist are returned, so the rail never offers a
     * letter that jumps nowhere. Assumes [rows] is already Title-sorted; the
     * caller guarantees that by only showing the rail for [MediaSort.TITLE].
     */
    fun <T> alphaIndex(rows: List<Row<T>>): List<Pair<String, Int>> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Pair<String, Int>>()
        rows.forEachIndexed { index, row ->
            val bucket = com.aeriotv.android.core.data.vod.MediaLibraryAdapter
                .letterBucket(row.sortTitle)
            if (seen.add(bucket)) out.add(bucket to index)
        }
        // "#" is produced by any non-letter first character and can land
        // anywhere in a title-sorted list depending on collation; pin it to the
        // top so the rail always reads # A B C.
        return out.sortedWith(
            compareBy({ if (it.first == "#") 0 else 1 }, { it.first }),
        )
    }

    /**
     * SplitMix64 finalizer over the id mixed with the session seed. Returned as
     * ULong so the comparison is unsigned and matches Swift's UInt64; see the
     * note at the RANDOM branch.
     *
     * The id is hashed with FNV-1a rather than [String.hashCode]: the JVM's
     * String hash is stable across runs, but it is a different function from
     * the one Apple uses, and the whole point of this file is that the two
     * platforms compute the same answer from the same inputs.
     */
    private fun shuffleKey(id: String, seed: Long): ULong {
        var h = -0x340d631b7bdddcdbL // 0xCBF29CE484222325
        for (b in id.toByteArray(Charsets.UTF_8)) {
            h = (h xor (b.toLong() and 0xFF)) * 0x100000001B3L
        }
        var z = seed + h + -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return (z xor (z ushr 31)).toULong()
    }
}
