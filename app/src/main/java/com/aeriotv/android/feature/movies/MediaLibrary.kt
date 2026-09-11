package com.aeriotv.android.feature.movies

import com.aeriotv.android.core.network.DispatcharrVODMovie
import com.aeriotv.android.core.network.DispatcharrVODSeries
import java.text.Normalizer

/** Which library a media tab shows. Apple parity: MoviesView(kind:). */
enum class MediaKind(val title: String, val libraryTitle: String, val emptyTitle: String) {
    Movies("Movies", "All Movies", "No Movies"),
    TVShows("TV Shows", "All TV Shows", "No TV Shows"),
}

/** Library sort (Apple parity: MoviesSortOrder). Recently Added is deliberately absent. */
enum class MediaSortOrder(val wire: String, val label: String) {
    TitleAZ("titleAZ", "Title · A to Z"),
    TitleZA("titleZA", "Title · Z to A"),
    YearNewest("yearNewest", "Year · Newest"),
    YearOldest("yearOldest", "Year · Oldest"),
    RatingHighest("ratingHighest", "Rating · Highest");

    companion object {
        fun fromWire(s: String?): MediaSortOrder = entries.firstOrNull { it.wire == s } ?: TitleAZ
    }
}

/** One poster in either library; the adapter the grid, sort and rail work on. */
data class MediaItem(
    val key: String,
    val title: String,
    val year: Int?,
    val rating: String?,
    val posterUrl: String?,
    val category: String?,
    val movieUuid: String? = null,
    val seriesId: Int? = null,
) {
    /** Sort/bucket key: quality prefix stripped, case and diacritics folded. */
    val sortKey: String by lazy { foldTitle(stripQualityPrefix(title)) }
    val bucket: Char by lazy { bucketFor(sortKey) }
    /** Persistent TMDB art key for this title (see [tmdbArtKey]). */
    val artKey: String by lazy { tmdbArtKey(title, key.startsWith("m:")) }
    val ratingValue: Double? by lazy { rating?.trim()?.toDoubleOrNull() }
}

/**
 * Rating for a badge or meta line, one decimal like iOS ("5.851" -> "5.9").
 * Empty when the raw string is not a number or is zero (nothing to show).
 */
fun formatRating(raw: String?): String {
    val v = raw?.trim()?.toDoubleOrNull() ?: return ""
    if (v <= 0.0) return ""
    return String.format(java.util.Locale.US, "%.1f", v)
}

/**
 * Provider titles carry markers the poster grid should not: a leading
 * language prefix ("EN - ", "FR: "), trailing quality tags ("[1080p]",
 * "[4K]") and a trailing "(2015)" that duplicates the year line. Also the
 * title used for TMDB lookups.
 */
// "(2019)" or " - 2019" at the end: both duplicate the year line.
private val trailingYear = Regex("""\s*(?:\((\d{4})\)|[-\u2013]\s*(\d{4}))\s*$""")
private val trailingTag = Regex("""\s*\[[^\]]*\]\s*$""")
private val leadingLang = Regex("""^[A-Za-z]{2,3}\s*[-:|]\s+""")
fun displayTitle(raw: String, year: Int?): String {
    var t = raw.trim()
    t = leadingLang.replace(t, "")
    while (true) { val n = trailingTag.replace(t, "").trim(); if (n == t) break; t = n }
    val m = trailingYear.find(t) ?: return t
    val found = m.groupValues[1].ifEmpty { m.groupValues[2] }
    return if (year == null || found == year.toString()) t.substring(0, m.range.first).trim() else t
}

/**
 * The title reduced to its identity for the persistent TMDB art cache, an
 * exact mirror of Apple's LibraryMatcher.cleanTitle (VODModels.swift:1194):
 * leading quality tags stripped repeatedly, trailing "(YYYY)" years stripped,
 * case and diacritics folded, "&" spelled out, every other non-alphanumeric
 * folded to a single space. Two copies of the same film in different groups
 * therefore share one cached lookup.
 */
fun cleanArtTitle(raw: String): String {
    var s = stripQualityPrefix(raw.trim())
    while (true) {
        val m = artTrailingYear.find(s) ?: break
        val head = s.substring(0, m.range.first).trim()
        if (head.isEmpty()) break
        s = head
    }
    s = foldTitle(s).replace("&", " and ")
    val spaced = s.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
    return spaced.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}

/** Cache key: the kind prefix plus [cleanArtTitle] (Apple TMDBArtCache.key). */
fun tmdbArtKey(title: String, isMovie: Boolean): String =
    (if (isMovie) "m:" else "t:") + cleanArtTitle(title)

private val artTrailingYear = Regex("""\s*\((?:19|20)\d{2}\)\s*$""")

/** Title for a TMDB search: display cleanup plus any remaining trailing year. */
fun searchTitle(raw: String): String = trailingYear.replace(displayTitle(raw, null), "").trim()

fun DispatcharrVODMovie.toMediaItem() = MediaItem(
    key = "m:$uuid", title = displayTitle(title.ifBlank { name ?: "" }, year), year = year, rating = rating,
    posterUrl = posterUrl, category = categoryName, movieUuid = uuid,
)

fun DispatcharrVODSeries.toMediaItem() = MediaItem(
    key = "s:$id", title = displayTitle(name.ifBlank { title ?: "" }, year), year = year, rating = rating,
    posterUrl = posterUrl, category = categoryName, seriesId = id,
)

private val qualityPrefix = Regex("""^\s*[\[(]?(?:UHD|FHD|4K|HD|SD)[\])]?\s*(?:[:\-|]\s*)?""", RegexOption.IGNORE_CASE)

/** Drops leading UHD/FHD/4K/HD/SD markers (optionally bracketed, optionally followed by : - |) repeatedly. */
fun stripQualityPrefix(title: String): String {
    var t = title
    while (true) {
        val m = qualityPrefix.find(t) ?: break
        if (m.value.isBlank() || m.range.last + 1 >= t.length) break
        t = t.substring(m.range.last + 1)
    }
    return t.trim()
}

private val combiningMarks = Regex("\\p{Mn}+")
private fun foldTitle(t: String): String =
    Normalizer.normalize(t, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()

private fun bucketFor(sortKey: String): Char {
    val c = sortKey.firstOrNull { !it.isWhitespace() } ?: return '#'
    val u = c.uppercaseChar()
    return if (u in 'A'..'Z') u else '#'
}

fun List<MediaItem>.sortedBy(order: MediaSortOrder): List<MediaItem> = when (order) {
    MediaSortOrder.TitleAZ -> sortedWith(compareBy({ it.sortKey }, { it.title }))
    MediaSortOrder.TitleZA -> sortedWith(compareByDescending<MediaItem> { it.sortKey }.thenBy { it.title })
    MediaSortOrder.YearNewest -> sortedWith(compareByDescending<MediaItem> { it.year ?: Int.MIN_VALUE }.thenBy { it.sortKey })
    MediaSortOrder.YearOldest -> sortedWith(compareBy<MediaItem> { it.year ?: Int.MAX_VALUE }.thenBy { it.sortKey })
    MediaSortOrder.RatingHighest -> sortedWith(compareByDescending<MediaItem> { it.ratingValue ?: -1.0 }.thenBy { it.sortKey })
}

/** The rail letters: "#" then A to Z. */
val railLetters: List<Char> = listOf('#') + ('A'..'Z')
