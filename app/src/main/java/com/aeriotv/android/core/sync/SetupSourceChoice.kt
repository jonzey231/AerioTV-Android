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
     * is a content hash over canonical row identities (DriveCanon): the ids
     * Dispatcharr Sync puts on the wire, which Drive snapshots also carry
     * since the 2026-10-30 id migration (older Drive rows resolve their local
     * ids at probe time). Null when any row cannot be resolved, so equal
     * content is not claimed and the prompt still appears.
     */
    data class Category(val rows: Int, val updatedAt: Long, val fingerprint: String? = null)

    /**
     * [lastWrite], when set, is the side's latest write time (Drive: the newest
     * envelope lastModified across the shared files, the same moment the Sync
     * screen's Push to Drive row reports) and wins over the per-category times.
     */
    data class Summary(val categories: Map<String, Category>, val lastWrite: Long? = null) {
        val isEmpty: Boolean get() = categories.values.all { it.rows <= 0 }
        val updatedAt: Long get() = lastWrite?.takeIf { it > 0 && !isEmpty }
            ?: categories.values.filter { it.rows > 0 }.maxOfOrNull { it.updatedAt } ?: 0L
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

    /** Plain name for logs (class names are renamed in release builds). */
    fun decisionName(d: Decision): String = when (d) {
        Decision.UseDrive -> "UseDrive"
        Decision.UseDispatcharr -> "UseDispatcharr"
        is Decision.Ask -> "Ask"
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

    // ---- "Your backup needs an update" (Drive canonical-id migration offer) ----

    const val UPDATE_TITLE = "Your backup needs an update"
    const val UPDATE_BODY = "AerioTV backups changed format. Updating keeps your backup working with the newest " +
        "version of AerioTV on every device. This takes a moment and does not change your favorites, progress, or settings."
    const val UPDATE_ACCEPT = "Update backup"
    const val UPDATE_NOT_NOW = "Not now"

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

/**
 * When to offer the one-time Drive id migration (DriveCanon). Session flags
 * live in process memory per base user (playlist id): "Not now" keeps the
 * old format until the next app session; onboarding never shows it twice,
 * and Settings offers it at most once per app session.
 */
object BackupUpdateOffer {
    private val declined = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val settingsShown = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** A Drive restore found an old-format backup (any playlist type). */
    fun shouldOffer(backupOutdated: Boolean, accepted: Boolean, playlistId: String): Boolean =
        backupOutdated && !accepted && playlistId !in declined

    /** Settings (Drive sync on): the same rule, once per app session. */
    fun shouldOfferInSettings(
        driveOn: Boolean,
        backupOutdated: Boolean,
        accepted: Boolean,
        playlistId: String,
    ): Boolean = driveOn && playlistId !in settingsShown && shouldOffer(backupOutdated, accepted, playlistId)

    fun markSettingsShown(playlistId: String) { settingsShown.add(playlistId) }
    fun decline(playlistId: String) { declined.add(playlistId) }
    internal fun resetForTest() { declined.clear(); settingsShown.clear() }
}
