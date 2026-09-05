package com.aeriotv.android.ui

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
fun TmdbAttribution(modifier: Modifier = Modifier, long: Boolean = true, isTv: Boolean = false) {
    val logoHeight = when {
        long && isTv -> 20.dp
        long -> 12.dp
        isTv -> 44.dp
        else -> 28.dp
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Image(
            painter = painterResource(id = if (long) R.drawable.ic_tmdb_long else R.drawable.ic_tmdb_short),
            contentDescription = "The Movie Database",
            modifier = Modifier.height(logoHeight),
        )
        val style = if (isTv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall
        // Their required line first, ours second.
        Text(
            text = "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "TMDB data is used only after configuring a TMDB API key in Settings > App Behaviors.",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
