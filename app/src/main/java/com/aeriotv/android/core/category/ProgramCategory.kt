package com.aeriotv.android.core.category

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Diversity1
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Mirrors iOS ProgramCategory (Aerio/Shared/CategoryColor.swift). 11 buckets
 * resolved from XMLTV <category> tags via alias substring matching. The first
 * four (Sports/Movies/Kids/News) ship enabled by default; the remaining seven
 * are gated behind the "Add more categories" sub-screen.
 *
 * Priority order (kids first, movies last) is enforced in [CategoryPalette]
 * when multiple aliases match a single token, so "Kids Sports" colours as Kids
 * and "Film Documentary" colours as Documentary.
 */
enum class ProgramCategory(
    val storageSuffix: String,
    val displayName: String,
    val defaultHex: String,
    val icon: ImageVector,
    val aliases: List<String>,
) {
    Sports(
        storageSuffix = "sports",
        displayName = "Sports",
        defaultHex = "3949AB",
        icon = Icons.Filled.SportsBasketball,
        aliases = listOf(
            "sport", "football", "soccer", "basketball", "baseball",
            "hockey", "boxing", "rugby", "tennis", "golf",
            "swimming", "cricket", "nfl", "nba", "mlb", "nhl",
            "fútbol", "fussball", "fußball",
        ),
    ),
    Movies(
        storageSuffix = "movie",
        displayName = "Movies",
        defaultHex = "5E35B1",
        icon = Icons.Filled.Theaters,
        aliases = listOf(
            "movie", "film", "cine", "feature film", "short film", "cortometraje",
        ),
    ),
    Kids(
        storageSuffix = "kids",
        displayName = "Kids",
        defaultHex = "039BE5",
        icon = Icons.Filled.Diversity1,
        aliases = listOf(
            "kids", "children", "child", "animated", "animation",
            "cartoon", "family", "jeunesse", "infantil", "niños",
            "zeichentrick", "dzieci",
        ),
    ),
    News(
        storageSuffix = "news",
        displayName = "News",
        defaultHex = "43A047",
        icon = Icons.Filled.Newspaper,
        aliases = listOf(
            "news", "newsmagazine", "current affairs", "noticias",
            "nachrichten", "journal", "informativo", "weather",
            "politics", "political",
        ),
    ),
    Documentary(
        storageSuffix = "documentary",
        displayName = "Documentary",
        defaultHex = "6D4C41",
        icon = Icons.Filled.Article,
        aliases = listOf(
            "documentary", "docudrama", "nature", "biography",
            "history", "historical", "science",
        ),
    ),
    Drama(
        storageSuffix = "drama",
        displayName = "Drama",
        defaultHex = "C62828",
        icon = Icons.Filled.TheaterComedy,
        aliases = listOf(
            "drama", "crime drama", "crime", "mystery", "thriller",
            "romance", "suspense",
        ),
    ),
    Comedy(
        storageSuffix = "comedy",
        displayName = "Comedy",
        defaultHex = "F9A825",
        icon = Icons.Outlined.Mood,
        aliases = listOf("comedy", "sitcom", "stand-up", "stand up"),
    ),
    Reality(
        storageSuffix = "reality",
        displayName = "Reality",
        defaultHex = "EC407A",
        icon = Icons.Filled.Tv,
        aliases = listOf(
            "reality", "game show", "game-show", "competition reality",
            "dating", "talk show", "talk", "shopping",
        ),
    ),
    Educational(
        storageSuffix = "educational",
        displayName = "Educational",
        defaultHex = "00897B",
        icon = Icons.Filled.School,
        aliases = listOf(
            "educational", "educacional", "tutorial", "how-to", "instructional",
        ),
    ),
    SciFi(
        storageSuffix = "scifi",
        displayName = "Sci-Fi / Fantasy",
        defaultHex = "00838F",
        icon = Icons.Filled.AutoAwesome,
        aliases = listOf(
            "science fiction", "sci-fi", "scifi", "fantasy",
            "supernatural", "horror",
        ),
    ),
    Music(
        storageSuffix = "music",
        displayName = "Music",
        defaultHex = "D81B60",
        icon = Icons.Filled.MusicNote,
        aliases = listOf("music", "concert", "musical", "música"),
    );

    val hexStorageKey: String get() = "categoryColor.$storageSuffix"
    val enabledStorageKey: String get() = "categoryBucketEnabled.$storageSuffix"

    companion object {
        /** The four buckets visible in the main Palette section. iOS parity. */
        val defaultBuckets: List<ProgramCategory> = listOf(Sports, Movies, Kids, News)

        /** The 7 buckets gated behind "Add more categories". */
        val additionalBuckets: List<ProgramCategory> = listOf(
            Documentary, Drama, Comedy, Reality, Educational, SciFi, Music,
        )

        /**
         * Resolution order. Kids first so "Kids Sports" colours as Kids,
         * Movies last so "Film Documentary" colours as Documentary. Matches
         * iOS `priorityOrder` in `CategoryColor.bucket(for:)`.
         */
        val priorityOrder: List<ProgramCategory> = listOf(
            Kids, Sports, News,
            Documentary, Educational, Reality, Music, SciFi,
            Drama, Comedy,
            Movies,
        )

        fun fromSuffix(suffix: String): ProgramCategory? =
            entries.firstOrNull { it.storageSuffix == suffix }
    }
}

/**
 * User-defined category mapping (Add More Categories > Custom section).
 * Custom entries match before built-in buckets so a user can colour a
 * genre that isn't a built-in (e.g. "Horror"). Persisted as JSON array
 * under [CategoryPalette.CUSTOM_KEY].
 */
@kotlinx.serialization.Serializable
data class CustomCategoryEntry(
    val id: String,
    val match: String,
    val hex: String,
)
