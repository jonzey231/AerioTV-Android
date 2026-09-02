package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.EPGProgramme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Duplicate detection and field merging for two programmes on one channel.
 * Mirrors Apple GuideStore.mergeProgramInto (docs/guide-semantics.md, section 3):
 *
 *  - duplicate when the titles match and the starts are within 60 s, OR the
 *    overlap exceeds 80 % of the INCOMING programme's duration (asymmetric on
 *    purpose: a short incoming programme inside a long existing one is a
 *    duplicate; a long incoming one over a short existing one is not);
 *  - on a duplicate the EXISTING programme keeps identity, title and times;
 *    the longer description wins; an existing non-empty category wins;
 *    ids and season/episode fill only if missing; boolean flags OR together.
 */
object GuideMerge {
    fun isDuplicate(existing: EPGProgramme, incoming: EPGProgramme): Boolean {
        val titleEq = existing.title.isNotBlank() && existing.title.trim() == incoming.title.trim()
        if (titleEq && abs(existing.startMillis - incoming.startMillis) < 60_000L) return true
        val incomingDuration = incoming.endMillis - incoming.startMillis
        if (incomingDuration <= 0L) return false
        val overlap = min(existing.endMillis, incoming.endMillis) - max(existing.startMillis, incoming.startMillis)
        return overlap > 0L && overlap.toDouble() / incomingDuration.toDouble() > 0.8
    }

    fun merge(existing: EPGProgramme, incoming: EPGProgramme): EPGProgramme = existing.copy(
        description = if (incoming.description.length > existing.description.length) incoming.description else existing.description,
        category = if (existing.category.isNotBlank()) existing.category else incoming.category,
        dispatcharrProgramId = existing.dispatcharrProgramId ?: incoming.dispatcharrProgramId,
        subTitle = existing.subTitle ?: incoming.subTitle,
        season = existing.season ?: incoming.season,
        episode = existing.episode ?: incoming.episode,
        isNew = existing.isNew || incoming.isNew,
        isLiveBroadcast = existing.isLiveBroadcast || incoming.isLiveBroadcast,
        isPremiere = existing.isPremiere || incoming.isPremiere,
        isFinale = existing.isFinale || incoming.isFinale,
        isRepeat = existing.isRepeat || incoming.isRepeat,
    )

    /** Collapse duplicates in a start-sorted list, keeping the earlier row. */
    fun dedup(sorted: List<EPGProgramme>): List<EPGProgramme> {
        if (sorted.size <= 1) return sorted
        var any = false
        for (i in 0 until sorted.size - 1) if (isDuplicate(sorted[i], sorted[i + 1])) { any = true; break }
        if (!any) return sorted
        val out = ArrayList<EPGProgramme>(sorted.size)
        for (next in sorted) {
            val prev = out.lastOrNull()
            if (prev != null && isDuplicate(prev, next)) out[out.lastIndex] = merge(prev, next) else out.add(next)
        }
        return out
    }
}
