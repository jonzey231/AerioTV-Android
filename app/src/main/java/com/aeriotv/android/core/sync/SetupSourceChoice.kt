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
        (a.categories.keys + b.categories.keys).all { name -> categoryEqual(a.categories[name], b.categories[name]) }

    /** The Sync Categories item that gates a shared category ("settings" is gated per name instead). */
    fun itemId(name: String): String? = when (name) {
        "progress" -> "watchProgress"
        "settings" -> null
        else -> name
    }

    /**
     * Only the categories this device syncs (Sync Categories, item 6): an
     * item that is Off here is neither compared, converged, nor replaced.
     * Both sides go through this, so they compare the same category set.
     */
    fun forDevice(s: Summary, itemOff: (String) -> Boolean): Summary =
        Summary(s.categories.filterKeys { n -> itemId(n)?.let { !itemOff(it) } ?: true }, s.lastWrite)

    /** Per category: both empty, or the same row count and the same non-null fingerprint. */
    fun categoryEqual(x: Category?, y: Category?): Boolean {
        val xr = x?.rows ?: 0
        val yr = y?.rows ?: 0
        return (xr <= 0 && yr <= 0) || (xr == yr && x?.fingerprint != null && x.fingerprint == y?.fingerprint)
    }

    /** Item Off in this device's Sync Categories ([scopes] is AppPreferences.syncScopesOnce). */
    fun itemOff(scopes: Map<String, SyncScope>, id: String): Boolean = scopes[id] == SyncScope.Off

    /** A settings name whose item is Off on this device. Names no item covers stay in. */
    fun nameOff(scopes: Map<String, SyncScope>, name: String): Boolean =
        SyncItemCatalog.itemForName(name)?.let { itemOff(scopes, it.id) } ?: false

    /** What a converge push remembers per category: the Dispatcharr state it pushed for. */
    fun convergeMark(c: Category?): String = "${c?.rows ?: 0}|${c?.fingerprint ?: "-"}"

    /**
     * Rule 5 (convergence): the shared categories whose Drive copy differs
     * from the Dispatcharr copy, minus [skip] (a local change still on its way
     * to the server) and minus those already pushed for this exact Dispatcharr
     * state ([lastPushed] holds [convergeMark] per category). The last guard is
     * what stops a ping-pong: a category that still differs after one push
     * (a row this device could not translate) is never pushed again until the
     * Dispatcharr copy itself changes.
     */
    fun convergePlan(drive: Summary, dsync: Summary, skip: Set<String>, lastPushed: Map<String, String>): List<String> =
        SHARED.filter { it in drive.categories || it in dsync.categories }
            .filter { it !in skip }
            .filter { !categoryEqual(drive.categories[it], dsync.categories[it]) }
            .filter { lastPushed[it] != convergeMark(dsync.categories[it]) }

    /**
     * The differing categories converge does not push, each with its reason:
     * the [skip] reason (armed, deferred, dirty, unread), or "untranslatable"
     * when this exact Dispatcharr state was already pushed once and Drive
     * still differs. Converge reports "equal" only when this and the plan are
     * both empty, which is exactly when the setup probe ([decide]) would not ask.
     */
    fun convergeSkips(drive: Summary, dsync: Summary, skip: Map<String, String>, lastPushed: Map<String, String>): List<Pair<String, String>> =
        SHARED.filter { it in drive.categories || it in dsync.categories }
            .filter { !categoryEqual(drive.categories[it], dsync.categories[it]) }
            .mapNotNull { n ->
                skip[n]?.let { n to it }
                    ?: if (lastPushed[n] == convergeMark(dsync.categories[n])) n to "untranslatable" else null
            }

    /**
     * The "equal" converge line. skipped counts only the categories that got
     * a skip line ([skipped], from [convergeSkips]): a skip reason on a
     * category that is equal anyway, or one whose Sync Categories item is
     * Off (not compared at all), is not counted (field run 2026-11-01 logged
     * skipped=1 with no skip line).
     */
    fun convergeEqualLine(categories: Int, skipped: List<Pair<String, String>>) =
        "[SYNC] converge equal categories=$categories skipped=${skipped.size}"

    /** The counts-only skip line. */
    fun convergeSkipLine(name: String, reason: String, drive: Category?, dsync: Category?) =
        "[SYNC] converge skip $name reason=$reason drive=${drive?.rows ?: 0} dsync=${dsync?.rows ?: 0}"

    /** The Drive file that carries a shared category. */
    fun driveFile(name: String): SyncCategory? = when (name) {
        "favorites" -> SyncCategory.Favorites
        "progress" -> SyncCategory.WatchProgress
        "reminders" -> SyncCategory.Reminders
        "watchlist", "hiddenTitles" -> SyncCategory.Watchlist
        "collections", "recents", "hiddenGroups", "groupOrder", "settings" -> SyncCategory.Preferences
        else -> null
    }

    /** The shared categories one Drive file carries, in [SHARED] order. */
    fun namesInFile(file: SyncCategory): List<String> = SHARED.filter { driveFile(it) == file }

    /**
     * Fresh-device order (Rule 4, Dispatcharr chosen; section 10a): the first
     * Drive push of [names] (one Drive file, only the names this device
     * syncs) waits until the Dispatcharr pull of each of them has completed
     * on this device, so a fresh device never overwrites a populated backup
     * before it has the Dispatcharr copy. [read] is the set of shared keys
     * whose pull completed this session (null: no session or no complete
     * pull yet). Once a name's pull completed it is released whatever the
     * row counts say: after a completed pull, local IS the Dispatcharr copy
     * (field run 2026-11-01: a count-based "empty here" held a set-up phone
     * forever because the local count missed settings and a progress row).
     * Null means push now; otherwise the reason it waits.
     */
    fun freshDeviceHold(names: List<String>, read: Set<String>?): String? {
        if (names.isEmpty()) return null
        if (read == null) return "Dispatcharr pull not done"
        names.firstOrNull { it !in read }?.let { return "$it not pulled yet" }
        return null
    }

    /**
     * Rule 4 with an unknown side (section 10a): a Drive probe that failed
     * ([drive] null) is unknown, never empty, so nothing is decided (no
     * UseDispatcharr with replace, no forced replace). Null: do not decide.
     */
    fun decideKnown(drive: Summary?, dsync: Summary?): Decision? =
        if (drive == null || dsync == null) null else decide(drive, dsync)

    /** The setup probe failure line: the reason class only. */
    fun driveProbeFailedLine(t: Throwable): String = "[DRIVE] setup probe failed: ${probeReason(t)} (treated as unknown, not empty)"

    fun probeReason(t: Throwable): String = when (t) {
        is DriveAuthException -> "Unauthorized"
        is DriveNeedsSignInException -> "NeedsSignIn"
        is DriveHttpException -> "Http${t.code}"
        else -> t.javaClass.simpleName.ifEmpty { "Error" }
    }

    /** The per-category Drive push line (counts only): "(converge)", "(replace)", "(follow recents local, recents pull)". */
    fun drivePushLine(file: String, rows: Int, shared: Map<String, Int>, label: String?, follows: List<String>, ok: Boolean): String {
        val why = listOfNotNull(label, follows.takeIf { it.isNotEmpty() }?.let { "follow " + it.joinToString(", ") }).joinToString("; ")
        val detail = if (shared.size > 1) " " + shared.entries.joinToString(" ") { "${it.key}=${it.value}" } else ""
        return "[DRIVE] push $file rows=$rows$detail ($why)${if (ok) "" else " failed"}"
    }

    /** The counts-only converge line. */
    fun convergeLine(name: String, drive: Category?, dsync: Category?) =
        "[SYNC] converge $name drive=${drive?.fingerprint ?: "-"}/${drive?.rows ?: 0} dsync=${dsync?.fingerprint ?: "-"}/${dsync?.rows ?: 0} -> push drive"

    /** Plain name for logs (class names are renamed in release builds). */
    fun decisionName(d: Decision): String = when (d) {
        Decision.UseDrive -> "UseDrive"
        Decision.UseDispatcharr -> "UseDispatcharr"
        is Decision.Ask -> "Ask"
    }

    /**
     * The card selected when the prompt opens: always Dispatcharr (Logan,
     * 2026-11-01). The prompt only appears when Dispatcharr Sync is on for
     * the active playlist (both providers hold data), so there is no case
     * without Dispatcharr and no newer-side rule.
     */
    @Suppress("UNUSED_PARAMETER")
    fun defaultSelection(drive: Summary, dsync: Summary): Source = Source.Dispatcharr

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
