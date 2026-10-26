package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeriotv.android.core.profiles.AvatarCatalog
import com.aeriotv.android.core.profiles.AvatarSpec
import com.aeriotv.android.core.profiles.DispatcharrProfile
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import com.aeriotv.android.ui.tv.TvChrome
import com.aeriotv.android.ui.tv.tvFocusScale

/**
 * Profile avatars (Profiles 2c, 2026-10-26): the shared avatar drawing and
 * the row menu "Avatar" picker. Wire ids live in [AvatarCatalog]; this file
 * maps them to vectors and the theme palette colors.
 */

/** The 8 avatar colors, taken from the theme palette (AppTheme accents). */
internal fun avatarColor(id: String): Color = when (id) {
    "teal" -> Color(0xFF1AC4D8) // AerioTV accent
    "blue" -> Color(0xFF3B82F6) // Midnight secondary
    "orange" -> Color(0xFFFB923C) // Sunset accent
    "ember" -> Color(0xFFC2410C) // Sunset light secondary
    "green" -> Color(0xFF22C55E) // Forest secondary
    "purple" -> Color(0xFF8B5CF6) // Lavender secondary
    "slate" -> Color(0xFF94A3B8) // Monochrome secondary
    "sea" -> Color(0xFF2B7A86) // Light secondary
    else -> Color(0xFF1AC4D8)
}

internal fun avatarColorName(id: String): String = id.replaceFirstChar { it.uppercase() }

/** Glyph color that reads on [bg]. */
internal fun avatarInk(bg: Color): Color = if (bg.luminance() > 0.45f) Color(0xFF0B1220) else Color.White

internal fun avatarIcon(id: String): ImageVector? = when (id) {
    "person" -> Icons.Filled.Person
    "face" -> Icons.Filled.Face
    "child" -> Icons.Filled.ChildCare
    "paw" -> Icons.Filled.Pets
    "cat" -> CatIcon
    "fish" -> FishIcon
    "star" -> Icons.Filled.Star
    "rocket" -> Icons.Filled.RocketLaunch
    "music" -> Icons.Filled.MusicNote
    "soccer" -> Icons.Filled.SportsSoccer
    "gamepad" -> Icons.Filled.SportsEsports
    "movie" -> Icons.Filled.Movie
    else -> null
}

internal fun avatarIconName(id: String): String = when (id) {
    AvatarCatalog.INITIAL -> "Initial"
    "gamepad" -> "Game controller"
    "paw" -> "Paw"
    else -> id.replaceFirstChar { it.uppercase() }
}

/** Generic cat face (no brand mark): round head, two ears, eye cutouts. */
private val CatIcon: ImageVector by lazy {
    ImageVector.Builder("AvatarCat", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4.6f, 10.5f); lineTo(5.2f, 3f); lineTo(10.2f, 6.8f); close()
            moveTo(19.4f, 10.5f); lineTo(18.8f, 3f); lineTo(13.8f, 6.8f); close()
        }
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 6f)
            arcToRelative(7.5f, 7.5f, 0f, true, true, 0f, 15f)
            arcToRelative(7.5f, 7.5f, 0f, true, true, 0f, -15f)
            close()
            moveTo(9f, 11.8f)
            arcToRelative(1.3f, 1.3f, 0f, true, false, 0f, 2.6f)
            arcToRelative(1.3f, 1.3f, 0f, true, false, 0f, -2.6f)
            close()
            moveTo(15f, 11.8f)
            arcToRelative(1.3f, 1.3f, 0f, true, false, 0f, 2.6f)
            arcToRelative(1.3f, 1.3f, 0f, true, false, 0f, -2.6f)
            close()
        }
    }.build()
}

/** Generic fish: body, forked tail, eye cutout. */
private val FishIcon: ImageVector by lazy {
    ImageVector.Builder("AvatarFish", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(2.5f, 12f)
            curveTo(4.5f, 7f, 11f, 5.8f, 15.2f, 9f)
            lineTo(21f, 5.5f)
            lineTo(19.4f, 12f)
            lineTo(21f, 18.5f)
            lineTo(15.2f, 15f)
            curveTo(11f, 18.2f, 4.5f, 17f, 2.5f, 12f)
            close()
            moveTo(7.2f, 10.2f)
            arcToRelative(1.2f, 1.2f, 0f, true, false, 0f, 2.4f)
            arcToRelative(1.2f, 1.2f, 0f, true, false, 0f, -2.4f)
            close()
        }
    }.build()
}

/** A profile's avatar: its icon (or initial) on its color. */
@Composable
fun ProfileAvatar(profile: DispatcharrProfile, size: Int, modifier: Modifier = Modifier) {
    ProfileAvatar(name = profile.displayName, size = size, modifier = modifier, username = profile.username, spec = profile.avatar)
}

/**
 * [name] gives the initial, [username] the default color (stable per user,
 * schema 4.12), [spec] the chosen icon and color.
 */
@Composable
fun ProfileAvatar(
    name: String,
    size: Int,
    modifier: Modifier = Modifier,
    username: String = name,
    spec: AvatarSpec? = null,
) {
    val bg = avatarColor(AvatarCatalog.colorFor(spec, username))
    val ink = avatarInk(bg)
    val icon = avatarIcon(AvatarCatalog.iconFor(spec))
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size((size * 0.58f).dp))
        } else {
            Text(
                text = name.trim().take(1).uppercase().ifEmpty { "?" },
                color = ink,
                fontWeight = FontWeight.Bold,
                fontSize = (size * 0.42f).sp,
            )
        }
    }
}

/**
 * One picker tile: a circle with the standard TV ring. Focused = 2 dp ring
 * (white when it is the current choice, accent otherwise) and the 1.08 grow;
 * the current choice keeps a quiet ring at rest so it reads without focus.
 */
@Composable
private fun AvatarTile(
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .tvFocusScale(focused, focusedScale = 1.08f)
            .size(46.dp)
            .border(
                width = TvChrome.ringWidth,
                color = when {
                    focused && selected -> Color.White
                    focused -> colors.primary
                    selected -> colors.onSurface.copy(alpha = 0.55f)
                    else -> Color.Transparent
                },
                shape = CircleShape,
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics { this.contentDescription = contentDescription; this.selected = selected }
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Row menu "Avatar": a live preview, the 8 colors, and the icon grid
 * (the initial plus 12 icons). Save writes the profile (and its synced row);
 * Cancel leaves it as it was. D-pad: Up / Down between rows, Left / Right
 * within a row, OK picks.
 */
@Composable
fun AvatarPickerDialog(
    profile: DispatcharrProfile,
    onDismiss: () -> Unit,
    onSave: (AvatarSpec) -> Unit,
) {
    var color by remember(profile.id) { mutableStateOf(AvatarCatalog.colorFor(profile.avatar, profile.username)) }
    var icon by remember(profile.id) { mutableStateOf(AvatarCatalog.iconFor(profile.avatar)) }
    val firstFocus = remember { FocusRequester() }
    val isTv = com.aeriotv.android.ui.settings.rememberIsTvDevice()
    val perRow = if (isTv) 7 else 5
    com.aeriotv.android.ui.FormFactorModal(onDismiss = onDismiss, tvWidthFraction = 0.6f, tvMaxHeight = 520.dp, traceName = "avatar") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Avatar", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            ProfileAvatar(name = profile.displayName, size = 64, username = profile.username, spec = AvatarSpec(icon, color))
            Spacer(Modifier.height(6.dp))
            Text(profile.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("Color", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            AvatarCatalog.COLORS.chunked(if (isTv) 8 else 4).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { id ->
                        AvatarTile(
                            selected = id == color,
                            contentDescription = avatarColorName(id),
                            onClick = { color = id },
                            modifier = if (id == color) Modifier.focusRequester(firstFocus) else Modifier,
                        ) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(avatarColor(id)))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Icon", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            AvatarCatalog.PICKER_ICONS.chunked(perRow).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { id ->
                        AvatarTile(selected = id == icon, contentDescription = avatarIconName(id), onClick = { icon = id }) {
                            ProfileAvatar(
                                name = profile.displayName,
                                size = 34,
                                username = profile.username,
                                spec = AvatarSpec(id, color),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SettingsDialogTextButton(label = "Cancel", onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                SettingsDialogTextButton(label = "Save", onClick = { onSave(AvatarSpec(icon, color)) })
            }
        }
    }
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(32L)
        }
    }
}
