package com.aeriotv.android.feature.ondemand

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.aeriotv.android.core.network.TmdbPerson
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
            modifier = Modifier.fillMaxWidth(0.62f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // w342 headshot: the dialog photo renders ~2x the strip
                    // card, so the w185 thumb would upscale soft on a TV.
                    val photo = profileUrl(bio?.profilePath ?: person.profilePath, "w342")
                    Box(
                        modifier = Modifier
                            .width(160.dp)
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
                            else -> Column(
                                modifier = Modifier
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    text = biography,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
