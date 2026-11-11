package com.aeriotv.android.core.setup

import android.content.Context

/**
 * Memory of the last `aeriotv://setup` links this phone handled, so an old
 * link is never opened again by accident (2026-11-10).
 *
 * Reported on the Nothing Phone: after the process was killed (an app update),
 * opening AerioTV from the launcher rebuilt the activity from the task's
 * original launch intent, which was an old camera link, and the Set up sheet
 * reopened on a device that had long finished its setup session.
 *
 * Rules (2026-11-11, after the Z Fold5 report: once the app was swiped away,
 * every rescan of the same code cold-started the app and was ignored):
 * - A link already sent is ignored.
 * - A link first seen more than [SetupProtocol.WINDOW_MS] ago (a TV setup
 *   session lasts 10 minutes) is ignored.
 * - Otherwise a scan opens the sheet, cold start or not. Rescanning the link
 *   whose sheet is already open leaves that sheet as it is (same link).
 * Links the system rebuilt from Recents or restored state never reach this
 * check (MainActivity).
 *
 * Only a short hash of the link's code and device key is stored, with the
 * time it was first seen.
 */
object SetupLinkMarker {
    enum class State { Open, Sent, Cancelled }

    /** [firstSeenMs] is wall-clock time; 0 for a mark stored before it was recorded (counts as old). */
    data class Mark(val id: String, val state: State, val firstSeenMs: Long = 0L)

    /** Identity of a setup link: its one-time code and the device's session key, hashed. Null when it does not parse. */
    fun linkId(link: String): String? = SetupProtocol.parseLink(link)?.let { id(it.code, it.pk) }

    fun id(code: String, pk: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
            .digest((code + ":" + SetupCrypto.b64Url(pk)).toByteArray(Charsets.UTF_8))
        return d.take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * Pure rule: why a link with [id] should not open now, or null when it
     * should, given the stored [marks] (most recent first) at [nowMs].
     */
    fun ignoreReason(id: String, marks: List<Mark>, nowMs: Long): String? {
        val mark = marks.firstOrNull { it.id == id } ?: return null
        return when {
            mark.state == State.Sent -> "already sent"
            nowMs - mark.firstSeenMs > SetupProtocol.WINDOW_MS -> "first seen over 10 minutes ago"
            else -> null
        }
    }

    fun shouldIgnore(id: String, marks: List<Mark>, nowMs: Long): Boolean = ignoreReason(id, marks, nowMs) != null

    /** [marks] with [id] set to [state], most recent first, capped at [MAX_MARKS]. A known id keeps its first-seen time. */
    fun updated(marks: List<Mark>, id: String, state: State, nowMs: Long): List<Mark> {
        val firstSeen = marks.firstOrNull { it.id == id }?.firstSeenMs ?: nowMs
        return (listOf(Mark(id, state, firstSeen)) + marks.filterNot { it.id == id }).take(MAX_MARKS)
    }

    /** Ids remembered: a few, so a link replaced by a second scan is still known as handled. */
    const val MAX_MARKS = 8

    private const val PREFS = "aerio_setup_link_marker"
    private const val KEY_MARKS = "marks"

    fun read(context: Context): List<Mark> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MARKS, null)
            ?.split(',')
            ?.mapNotNull(::parseMark)
            .orEmpty()

    /** "id:State:firstSeenMs"; marks stored before 2026-11-11 have no time ("id:State"). */
    fun parseMark(e: String): Mark? {
        val parts = e.split(':')
        if (parts.size < 2 || parts[0].isEmpty()) return null
        val state = State.entries.firstOrNull { it.name == parts[1] } ?: return null
        return Mark(parts[0], state, parts.getOrNull(2)?.toLongOrNull() ?: 0L)
    }

    fun encode(marks: List<Mark>): String = marks.joinToString(",") { "${it.id}:${it.state.name}:${it.firstSeenMs}" }

    private fun write(context: Context, id: String, state: State) {
        val next = updated(read(context), id, state, System.currentTimeMillis())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MARKS, encode(next)).apply()
    }

    /** Why [link] should not open now (see the rules above), or null when it should. */
    fun ignoreReason(context: Context, link: String): String? {
        val id = linkId(link) ?: return null
        return ignoreReason(id, read(context), System.currentTimeMillis())
    }

    /** The sheet for [link] opened. */
    fun markOpen(context: Context, link: String) {
        linkId(link)?.let { write(context, it, State.Open) }
    }

    /** The playlist was sent to the device behind [code] and [pk]. */
    fun markSent(context: Context, code: String, pk: ByteArray) = write(context, id(code, pk), State.Sent)

    /** The sheet for [link] closed: Cancelled unless it was sent. */
    fun markClosed(context: Context, link: String) {
        val id = linkId(link) ?: return
        if (read(context).firstOrNull { it.id == id }?.state == State.Sent) return
        write(context, id, State.Cancelled)
    }
}
