package com.aeriotv.android.core.sync.dsync

/**
 * One-time Dispatcharr Sync offer after an app update (0.5.14, Apple
 * parity). An upgraded Dispatcharr user keeps Dispatcharr Sync off
 * (SyncSwitchMigration), and the providers offer runs only from
 * onboarding, so without this an upgraded device is never asked.
 *
 * Pure: the host reads the inputs, probes the server record only when
 * [gate] returns null, then calls [decide].
 */
object DsyncUpgradeOffer {
    enum class Record(val wire: String) { Found("found"), Missing("missing"), Error("error"), Unread("unread") }

    enum class Decision(val wire: String) {
        /** Another device already syncs this account: opt-in dialog, no comparison prompt. */
        ShowOptIn("shown-optin"),
        /** First device on this account: the providers offer and today's migration flow. */
        ShowMigrate("shown-migrate"),
        DeclinedBefore("declined-before"),
        NotDispatcharr("not-dispatcharr"),
        AlreadyOn("already-on"),
        /** This versionCode was already evaluated; nothing to log. */
        AlreadyEvaluated("already-evaluated"),
        /** Probe failed: do nothing, retry on the next launch. */
        Retry("retry"),
    }

    /** Null = probe the server record, then [decide]. */
    fun gate(
        versionCode: Int,
        lastEvaluatedVersion: Int,
        isDispatcharr: Boolean,
        dispatcharrOn: Boolean,
        declined: Boolean,
    ): Decision? = when {
        lastEvaluatedVersion >= versionCode -> Decision.AlreadyEvaluated
        !isDispatcharr -> Decision.NotDispatcharr
        dispatcharrOn -> Decision.AlreadyOn
        declined -> Decision.DeclinedBefore
        else -> null
    }

    fun decide(
        versionCode: Int,
        lastEvaluatedVersion: Int,
        isDispatcharr: Boolean,
        dispatcharrOn: Boolean,
        declined: Boolean,
        record: Record,
    ): Decision = gate(versionCode, lastEvaluatedVersion, isDispatcharr, dispatcharrOn, declined) ?: when (record) {
        Record.Found -> Decision.ShowOptIn
        Record.Missing -> Decision.ShowMigrate
        Record.Error, Record.Unread -> Decision.Retry
    }

    /** Store versionCode as evaluated (no offer again until the next update). */
    fun stamps(d: Decision): Boolean = d != Decision.Retry && d != Decision.AlreadyEvaluated

    /** Log line only for a fresh evaluation (no keys or URLs). */
    fun logLine(record: Record, d: Decision): String? =
        if (d == Decision.AlreadyEvaluated) null else "[DSYNC] upgrade offer: record=${record.wire} decision=${d.wire}"

    const val OPT_IN_TITLE = "Dispatcharr Sync is on for this account"
    fun optInBody(driveOn: Boolean): String =
        "Another device already syncs this account through Dispatcharr. " +
            if (driveOn) "Turn it on here too, alongside Google Drive?" else "Turn it on here too?"

    /** First device on the account: the onboarding sync step's copy. */
    const val MIGRATE_TITLE = "Turn on Dispatcharr Sync?"
    const val MIGRATE_BODY = "Your favorites, watch progress, reminders, and preferences are stored on your " +
        "Dispatcharr server under each profile and follow you to every device. " +
        "Admins on the server can view this data."

    /** Once per process: at most one offer per launch, never in a launch that ran onboarding. */
    @Volatile var evaluatedThisLaunch = false
    @Volatile var onboardedThisLaunch = false
}
