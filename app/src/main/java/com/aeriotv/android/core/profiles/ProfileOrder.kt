package com.aeriotv.android.core.profiles

/** Pure profile order math shared by [ProfileManager.move] and [ProfileManager.setOrder]. */
object ProfileOrder {

    /** One single-step move (the Move Up / Move Down / Move to Front result). */
    fun step(ids: List<String>, id: String, how: ProfileManager.Move): List<String> {
        val from = ids.indexOf(id)
        if (from < 0) return ids
        val to = when (how) {
            ProfileManager.Move.Earlier -> from - 1
            ProfileManager.Move.Later -> from + 1
            ProfileManager.Move.First -> 0
        }.coerceIn(0, ids.lastIndex)
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * A requested full order applied to the current roster: unknown ids are
     * dropped and current ids the request does not name keep their relative
     * place at the end, so a roster change (sync add or tombstone) landing
     * mid pick-up never loses or invents a profile.
     */
    fun resolve(current: List<String>, requested: List<String>): List<String> {
        val known = current.toSet()
        val head = requested.distinct().filter { it in known }
        return head + current.filterNot { it in head.toSet() }
    }
}
