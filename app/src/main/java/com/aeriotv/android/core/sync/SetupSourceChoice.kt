package com.aeriotv.android.core.sync

/**
 * Which setup a device starts from when both Google Drive and Dispatcharr
 * Sync hold data for the same (base) user and they differ (Logan,
 * 2026-10-30; docs/DispatcharrSyncSchema.md section 10a, Rule 4). Pure so
 * the rules and the copy are unit tested (SetupSourceChoiceTest).
 */
object SetupSourceChoice {
    /** The shared categories compared, in display order. Profiles are per-row merge and never compared. */
    val SHARED = listOf(
        "favorites", "progress", "reminders", "watchlist", "collections", "recents",
        "hiddenTitles", "hiddenGroups", "groupOrder", "settings",
    )

    /**
     * One category on one side. [updatedAt] is Unix milliseconds. [fingerprint]
     * is a content hash built from identifiers BOTH sides carry (for example
     * reminders by channel name and start time); null when the two sides use
     * different ids (Drive keeps local ids, Dispatcharr keeps server integer
     * ids), so equal content cannot be proven from the data alone.
     */
    data class Category(val rows: Int, val updatedAt: Long, val fingerprint: String? = null)

    data class Summary(val categories: Map<String, Category>) {
        val isEmpty: Boolean get() = categories.values.all { it.rows <= 0 }
        val updatedAt: Long get() = categories.values.filter { it.rows > 0 }.maxOfOrNull { it.updatedAt } ?: 0L
        fun rows(name: String): Int = categories[name]?.rows ?: 0
        companion object { val EMPTY = Summary(emptyMap()) }
    }

    enum class Source { Drive, Dispatcharr }

    sealed interface Decision {
        data object UseDrive : Decision
        data object UseDispatcharr : Decision
        data class Ask(val drive: Summary, val dsync: Summary) : Decision
    }

    /**
     * One side empty: use the other (both empty: Dispatcharr, nothing to
     * replace). Both non-empty and every shared category provably equal:
     * either works, Dispatcharr is preferred. Otherwise ask.
     */
    fun decide(drive: Summary, dsync: Summary): Decision = when {
        dsync.isEmpty && drive.isEmpty -> Decision.UseDispatcharr
        dsync.isEmpty -> Decision.UseDrive
        drive.isEmpty -> Decision.UseDispatcharr
        equal(drive, dsync) -> Decision.UseDispatcharr
        else -> Decision.Ask(drive, dsync)
    }

    /** Per category: both empty, or the same row count and the same non-null fingerprint. */
    fun equal(a: Summary, b: Summary): Boolean =
        (a.categories.keys + b.categories.keys).all { name ->
            val x = a.categories[name]
            val y = b.categories[name]
            val xr = x?.rows ?: 0
            val yr = y?.rows ?: 0
            (xr <= 0 && yr <= 0) ||
                (xr == yr && x?.fingerprint != null && x.fingerprint == y?.fingerprint)
        }

    /** The card selected when the prompt opens: the more recently updated side (ties: Dispatcharr). */
    fun defaultSelection(drive: Summary, dsync: Summary): Source =
        if (drive.updatedAt > dsync.updatedAt) Source.Drive else Source.Dispatcharr

    // ---- copy ----

    const val TITLE = "Which setup should this device start from?"
    const val FOOTER = "The other will be replaced with the setup you choose."
    const val DISPATCHARR = "Dispatcharr"
    const val GOOGLE_DRIVE = "Google Drive"
    const val CONTINUE = "Continue"
    const val CANCEL = "Cancel"
    const val NOTHING = "Nothing saved yet"

    private fun label(name: String, n: Int): String = when (name) {
        "favorites" -> if (n == 1) "favorite" else "favorites"
        "progress" -> "in progress"
        "reminders" -> if (n == 1) "reminder" else "reminders"
        "watchlist" -> "in Watchlist"
        "collections" -> if (n == 1) "collection" else "collections"
        "recents" -> if (n == 1) "recent channel" else "recent channels"
        "hiddenTitles" -> if (n == 1) "hidden title" else "hidden titles"
        "hiddenGroups" -> if (n == 1) "hidden group" else "hidden groups"
        "groupOrder" -> if (n == 1) "ordered group" else "ordered groups"
        "settings" -> if (n == 1) "setting" else "settings"
        else -> name
    }

    /** "12 favorites, 8 in progress, 3 reminders": non-zero categories only, at most four, then "and more". */
    fun countLine(s: Summary): String {
        val parts = SHARED.mapNotNull { name -> s.rows(name).takeIf { it > 0 }?.let { "$it ${label(name, it)}" } }
        if (parts.isEmpty()) return NOTHING
        return if (parts.size > MAX_COUNTS) parts.take(MAX_COUNTS).joinToString(", ") + ", and more"
        else parts.joinToString(", ")
    }

    const val MAX_COUNTS = 4

    /** "Updated just now", "Updated 5 minutes ago", "Updated 1 hour ago", "Updated 3 days ago". */
    fun updatedLine(updatedAtMs: Long, nowMs: Long): String {
        if (updatedAtMs <= 0L) return "Not updated yet"
        val s = ((nowMs - updatedAtMs) / 1000).coerceAtLeast(0)
        fun unit(n: Long, one: String) = "$n $one${if (n == 1L) "" else "s"} ago"
        val rel = when {
            s < 60 -> "just now"
            s < 3600 -> unit(s / 60, "minute")
            s < 86_400 -> unit(s / 3600, "hour")
            s < 86_400L * 30 -> unit(s / 86_400, "day")
            s < 86_400L * 365 -> unit(s / (86_400L * 30), "month")
            else -> unit(s / (86_400L * 365), "year")
        }
        return "Updated $rel"
    }

    /** Order-independent fingerprint of comparable row identities. */
    fun fingerprint(items: Collection<String>): String =
        items.map { it.trim().lowercase() }.sorted().joinToString("\u0001").hashCode().toString(16)
}
