package com.aeriotv.android.feature.ondemand

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.aeriotv.android.core.network.TmdbPerson
import com.aeriotv.android.core.tv.qrCodeBitmap
import com.aeriotv.android.core.network.TmdbPersonBio
import com.aeriotv.android.feature.settings.SettingsDialogTextButton
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Cast-member biography dialog for the VOD detail screens. Opens from a
 * Cast & Crew headshot card; the `/person/{id}` payload is fetched lazily
 * so browsing the strip costs nothing until a card is actually picked.
 * VM-agnostic on purpose: callers hand in [fetchBio] and [profileUrl]
 * (the detail screens pass OnDemandViewModel::resolveTmdbPersonBio and
 * ::tmdbProfileImageUrl), so the dialog never needs a ViewModel reference
 * of its own. BACK and the scrim dismiss via the Dialog defaults; Close is
 * the only focus stop, so D-pad users land on it immediately.
 */
@Composable
fun PersonBioDialog(
    person: TmdbPerson,
    fetchBio: suspend (String) -> TmdbPersonBio?,
    profileUrl: (String?, String) -> String?,
    onDismiss: () -> Unit,
) {
    var bio by remember(person.id) { mutableStateOf<TmdbPersonBio?>(null) }
    var loaded by remember(person.id) { mutableStateOf(false) }
    LaunchedEffect(person.id) {
        bio = fetchBio(person.id)
        loaded = true
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            // Bound to the viewport (a 1080p TV is only ~540dp tall): the body
            // scrolls if a long bio plus the Known For strip overflow, while
            // the Close footer below stays pinned and reachable.
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .heightIn(max = 504.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        // w342 headshot: the dialog photo renders ~2x the strip
                        // card, so the w185 thumb would upscale soft on a TV.
                        val photo = profileUrl(bio?.profilePath ?: person.profilePath, "w342")
                        Box(
                            modifier = Modifier
                                .width(140.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!photo.isNullOrBlank()) {
                                AsyncImage(
                                    model = photo,
                                    contentDescription = person.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(48.dp),
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bio?.name ?: person.name,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Bold,
                            )
                            bio?.birthday?.let { LifeDetailLine("Born", formatBioDate(it)) }
                            bio?.deathday?.let { LifeDetailLine("Died", formatBioDate(it)) }
                            bio?.placeOfBirth?.let { LifeDetailLine("Birthplace", it) }
                            Spacer(Modifier.height(10.dp))
                            val biography = bio?.biography
                            when {
                                !loaded -> CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                                biography.isNullOrBlank() -> Text(
                                    text = "No biography available.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                else -> Text(
                                    text = biography,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // QR to the person's TMDB page: no browser on a TV, so
                        // the user scans it to open the full filmography on a
                        // phone. A real layout child (not an overlay) so it
                        // never sits on top of the bio text.
                        TmdbPersonQr(personId = person.id)
                    }

                    val knownFor = bio?.knownFor.orEmpty()
                    if (knownFor.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = "Known For",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(knownFor, key = { it.id }) { item ->
                                KnownForCard(
                                    title = item.title,
                                    posterUrl = profileUrl(item.posterPath, "w185"),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    SettingsDialogTextButton(label = "Close", onClick = onDismiss)
                }
            }
        }
    }
}

/**
 * Small QR in the bio sheet's top-right corner that encodes the person's
 * TMDB page (themoviedb.org/person/{id}). Android TV has no browser, so the
 * user scans it with a phone to open the actor's full filmography. Renders
 * nothing when the QR cannot be generated. Not focusable, matching the rest
 * of this read-only sheet.
 */
@Composable
private fun TmdbPersonQr(personId: String) {
    val url = "https://www.themoviedb.org/person/$personId"
    val qr = remember(url) { qrCodeBitmap(url, sizePx = 256) } ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(6.dp),
        ) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "TMDB page QR code",
                modifier = Modifier.size(72.dp),
                filterQuality = FilterQuality.None,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "View on TMDB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One poster + title tile in the bio sheet's "Known For" strip. Not
 * focusable: the strip is informational (the dialog's only focus stop is
 * Close), matching the read-only "Known For" row on TMDB's person page.
 */
@Composable
private fun KnownForCard(title: String, posterUrl: String?) {
    Column(modifier = Modifier.width(96.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LifeDetailLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * TMDB sends life dates as POSIX `yyyy-MM-dd`. Same locale conversion the
 * episode air-date uses; falls back to the raw string on anything odd.
 */
private fun formatBioDate(raw: String): String {
    val trimmed = raw.takeIf { it.length >= 10 } ?: return raw
    return runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = parser.parse(trimmed.substring(0, 10)) ?: return raw
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
    }.getOrDefault(raw)
}
