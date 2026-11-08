package com.aeriotv.android.core.sync.dsync

/**
 * The server record vanished after this device already read it (owner
 * decision 2026-11-07). Apple re-created a record two minutes after it was
 * cleared on the server, from a timed sync; a device that already completed
 * its first read for the user now asks before uploading again.
 *
 * Pure: the manager passes whether this device finished its first read for
 * the user before this pull, and whether the list showed no record.
 */
object DsyncMissingRecord {
    enum class Action {
        /** Pause the cycle and ask: Upload Again or Turn Off. No writes until answered. */
        Prompt,
        /** First read for this user: seed from local as today. */
        Seed,
        /** The record is there: the normal pull. */
        Normal,
    }

    fun decide(firstReadDone: Boolean, recordMissing: Boolean): Action = when {
        !recordMissing -> Action.Normal
        firstReadDone -> Action.Prompt
        else -> Action.Seed
    }

    const val TITLE = "Dispatcharr Sync data is missing"
    const val BODY = "The sync data for this account is no longer on your Dispatcharr server. " +
        "Upload this device's data again, or leave the server alone and turn Dispatcharr Sync off on this device?"
}
