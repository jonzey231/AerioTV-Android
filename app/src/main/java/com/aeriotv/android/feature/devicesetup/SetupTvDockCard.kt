package com.aeriotv.android.feature.devicesetup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.setup.SetupCrypto
import com.aeriotv.android.core.setup.SetupTarget
import com.aeriotv.android.feature.livetv.RetainedCardControlHeight
import com.aeriotv.android.feature.main.dockCardHeight
import com.aeriotv.android.feature.main.floatingNavChrome
import com.aeriotv.android.feature.multiview.AccentTextControl
import com.aeriotv.android.ui.settings.SettingsCardMetrics
import com.aeriotv.android.ui.settings.settingsCardFill

/**
 * TVs the user dismissed from the dock, by name and current key, until the
 * app restarts. A TV that regenerates its key (new session) shows again.
 */
object SetupDockDismissals {
    private val keys = mutableStateListOf<String>()
    fun idOf(t: SetupTarget) = t.name + "|" + SetupCrypto.b64(t.pk)
    fun isDismissed(t: SetupTarget) = idOf(t) in keys
    fun dismiss(t: SetupTarget) { if (!isDismissed(t)) keys.add(idOf(t)) }
}

/** Device-type icon for a receiver's advertised `kind` (absent = TV). */
fun setupKindIcon(kind: String): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    com.aeriotv.android.core.setup.SetupProtocol.KIND_PHONE -> Icons.Filled.PhoneAndroid
    com.aeriotv.android.core.setup.SetupProtocol.KIND_TABLET -> Icons.Filled.Tablet
    else -> Icons.Filled.Tv
}

/**
 * The TV the dock card is offering right now (null when none, when the
 * profile cannot set up a TV, or while its setup sheet is open). MainScaffold
 * computes it once from its one SetupDiscovery and provides it, so the dock
 * card and the Settings root banner always show the same TV.
 */
class SetupTvOffer(
    val target: SetupTarget,
    val onSetUp: () -> Unit,
    val onDismiss: () -> Unit,
    /**
     * Fix Login: this phone's playlist whose sign-in the device asks for
     * (the advert's `mode=fixlogin`); null for a normal setup offer.
     */
    val fixLoginPlaylistId: String? = null,
)

val LocalSetupTvOffer = staticCompositionLocalOf<SetupTvOffer?> { null }

/**
 * Settings root banner for the device in [LocalSetupTvOffer]: its icon, its
 * name on one line, Set up, and dismiss, in a settings card at the very top
 * of the list. The key check lives on the next screen ("Check that the
 * device shows XXXX"), so the banner no longer carries a "Waiting for setup,
 * key XXXX" line (Logan 2026-11-11, Apple 87d06a8).
 */
@Composable
fun SetupTvSettingsBanner(offer: SetupTvOffer, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(SettingsCardMetrics.cardCorner)
    Row(
        modifier = modifier
            .clip(shape)
            .background(settingsCardFill())
            .clickable(onClick = offer.onSetUp)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(setupKindIcon(offer.target.kind), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = offer.target.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.heightIn(min = RetainedCardControlHeight), contentAlignment = Alignment.Center) {
            AccentTextControl(label = com.aeriotv.android.core.setup.FixLogin.buttonLabel(offer.target.mode), onClick = offer.onSetUp)
        }
        IconButton(onClick = offer.onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Dock card for a device advertising `_aeriotv-setup._tcp`
 * (docs/DeviceSetupHandoff.md), phones and tablets: one row (Logan
 * 2026-11-11, Apple 87d06a8) with the device name on one line (truncating),
 * Set up, and dismiss, in the same capsule shell as the other dock cards
 * (floating nav chrome, the nav bar's measured height and frame, 48 dp
 * trailing controls). No device tile and no key line; the key check is on
 * the next screen. The name starts 18 dp in on every device (Apple's
 * leading padding), clear of the capsule's rounded end now that no tile
 * fills it.
 */
@Composable
fun SetupTvDockCard(
    target: SetupTarget,
    onSetUp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val capsule = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .floatingNavChrome(capsule)
            .clip(capsule)
            .clickable(onClick = onSetUp)
            .dockCardHeight()
            .padding(start = 18.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = target.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.heightIn(min = RetainedCardControlHeight), contentAlignment = Alignment.Center) {
            // "Fix Login" when the device asks for one playlist's sign-in (Fix Login).
            AccentTextControl(label = com.aeriotv.android.core.setup.FixLogin.buttonLabel(target.mode), onClick = onSetUp)
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
