package com.aeriotv.android.core.setup

import android.content.Context

/**
 * Memory of the last `aeriotv://setup` link this phone handled, so the same
 * link is never opened twice by accident (2026-11-10).
 *
 * Reported on the Nothing Phone: after the process was killed (an app update),
 * opening AerioTV from the launcher rebuilt the activity from the task's
 * original launch intent, which was an old camera link, and the Set up sheet
 * reopened on a device that had long finished its setup session.
 *
 * Rules (the Apple app behaves the same way):
 * - While the link's sheet is open, or after it was sent, the same link is ignored.
 * - After Cancel, scanning the same code again opens it (a fresh intent).
 * - A cold launch carrying the link that was last handled is the task's old
 *   intent being replayed, not a new scan, so it is ignored even after Cancel.
 *
 * Only a short hash of the link's code and device key is stored.
 */
object SetupLinkMarker {
    enum class State { Open, Sent, Cancelled }

    data class Mark(val id: String, val state: State)

    /** Identity of a setup link: its one-time code and the device's session key, hashed. Null when it does not parse. */
    fun linkId(link: String): String? = SetupProtocol.parseLink(link)?.let { id(it.code, it.pk) }

    fun id(code: String, pk: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
            .digest((code + ":" + SetupCrypto.b64Url(pk)).toByteArray(Charsets.UTF_8))
        return d.take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * Pure rule: should a link with [id] be ignored, given the stored [marks]
     * (most recent first)? [coldLaunch] = it arrived with the activity's
     * launch intent (onCreate) rather than as a new intent to the running app.
     */
    fun shouldIgnore(id: String, marks: List<Mark>, coldLaunch: Boolean): Boolean {
        val mark = marks.firstOrNull { it.id == id } ?: return false
        return when (mark.state) {
            State.Open, State.Sent -> true
            State.Cancelled -> coldLaunch
        }
    }

    /** [marks] with [id] set to [state], most recent first, capped at [MAX_MARKS]. */
    fun updated(marks: List<Mark>, id: String, state: State): List<Mark> =
        (listOf(Mark(id, state)) + marks.filterNot { it.id == id }).take(MAX_MARKS)

    /** Ids remembered: a few, so a link replaced by a second scan is still known as handled. */
    const val MAX_MARKS = 8

    private const val PREFS = "aerio_setup_link_marker"
    private const val KEY_MARKS = "marks"

    fun read(context: Context): List<Mark> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MARKS, null)
            ?.split(',')
            ?.mapNotNull { e ->
                val i = e.indexOf(':')
                if (i <= 0) return@mapNotNull null
                val state = State.entries.firstOrNull { it.name == e.substring(i + 1) } ?: return@mapNotNull null
                Mark(e.substring(0, i), state)
            }
            .orEmpty()

    private fun write(context: Context, id: String, state: State) {
        val next = updated(read(context), id, state)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MARKS, next.joinToString(",") { "${it.id}:${it.state.name}" }).apply()
    }

    /** True when [link] should not open now (see the rules above). */
    fun ignore(context: Context, link: String, coldLaunch: Boolean): Boolean {
        val id = linkId(link) ?: return false
        return shouldIgnore(id, read(context), coldLaunch)
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
