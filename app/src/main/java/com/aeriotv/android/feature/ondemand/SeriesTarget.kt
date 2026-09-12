package com.aeriotv.android.feature.ondemand

import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import com.aeriotv.android.core.network.DispatcharrVODEpisode

/**
 * The episode a series' primary Play button launches, plus the label that
 * button carries. Apple's `tvSeriesTarget` (VODDetailView.swift:731-751) is
 * the reference; the same rule drives the series detail hero AND the library
 * / watchlist hero on Android TV so "Play" never just opens Details.
 */
data class SeriesTarget(
    val episode: DispatcharrVODEpisode,
    val resuming: Boolean,
    val label: String,
)

/**
 * Apple's rule, verbatim:
 *  1. flatten the seasons ordered by season then episode number,
 *  2. take this series' progress rows newest first,
 *  3. if the newest row maps into that list: unfinished -> that episode
 *     ("Resume S{n} E{n}"), finished with a next episode -> the next one
 *     ("Play S{n} E{n}"),
 *  4. otherwise the first episode ("Play S{n} E{n}").
 *
 * [progress] must already be scoped to this series (the keyed
 * `observeSeriesEpisodes` flow), never a recency window.
 */
fun seriesTarget(
    episodes: List<DispatcharrVODEpisode>,
    progress: List<WatchProgressEntity>,
): SeriesTarget? {
    val ordered = episodes.sortedWith(
        compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: Int.MAX_VALUE }),
    )
    if (ordered.isEmpty()) return null
    val newest = progress.maxByOrNull { it.updatedAt }
    val index = newest?.let { row -> ordered.indexOfFirst { it.uuid == row.videoId } } ?: -1
    if (newest != null && index >= 0) {
        if (!newest.isFinished) {
            return SeriesTarget(ordered[index], resuming = true, label = playLabel(ordered[index], "Resume"))
        }
        ordered.getOrNull(index + 1)?.let { next ->
            return SeriesTarget(next, resuming = false, label = playLabel(next, "Play"))
        }
    }
    val first = ordered.first()
    return SeriesTarget(first, resuming = false, label = playLabel(first, "Play"))
}

private fun playLabel(episode: DispatcharrVODEpisode, verb: String): String =
    "$verb S${episode.seasonNumber ?: 1} E${episode.episodeNumber ?: 1}"
