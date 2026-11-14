package com.aeriotv.android.ui

import com.aeriotv.android.ui.scale.subtext
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.aeriotv.android.R

/**
 * TMDB's logo plus the wording its terms require wherever TMDB data or
 * images are shown (https://www.themoviedb.org/about/logos-attribution).
 */
@Composable
fun TmdbAttribution(
    modifier: Modifier = Modifier,
    long: Boolean = true,
    isTv: Boolean = false,
    /** Settings: caption-size lines (the Settings footnote style). */
    caption: Boolean = false,
) {
    val logoHeight = when {
        long && isTv -> 20.dp
        long -> 12.dp
        isTv -> 44.dp
        else -> 28.dp
    }
    // Apple's TMDBAttributionView on every platform: VStack(spacing: 8) with
    // the lines in textTertiary, the dim footer ink (colorScheme.tertiary,
    // as the Settings footers; in dark mode the accent at 28% on phones and
    // tablets and 45% on Android TV, as on Apple TV).
    val lineColor = MaterialTheme.colorScheme.tertiary
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(
            painter = painterResource(id = if (long) R.drawable.ic_tmdb_long else R.drawable.ic_tmdb_short),
            contentDescription = "The Movie Database",
            modifier = Modifier.height(logoHeight),
        )
        val style = when {
            caption -> com.aeriotv.android.ui.settings.settingsFootnoteStyle()
            isTv -> MaterialTheme.typography.bodyMedium
            else -> MaterialTheme.typography.bodySmall
        }
        // Their required line first, ours second.
        Text(
            text = "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            style = style.subtext(),
            color = lineColor,
        )
        Text(
            text = "TMDB data is used only after configuring a TMDB API key in Settings > Movies & TV Shows.",
            style = style.subtext(),
            color = lineColor,
        )
    }
}
