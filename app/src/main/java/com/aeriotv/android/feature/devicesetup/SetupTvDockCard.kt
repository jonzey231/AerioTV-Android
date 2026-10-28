package com.aeriotv.android.feature.devicesetup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.setup.SetupCrypto
import com.aeriotv.android.core.setup.SetupTarget
import com.aeriotv.android.feature.livetv.RetainedCardControlHeight
import com.aeriotv.android.feature.livetv.RetainedCardVerticalPadding
import com.aeriotv.android.feature.main.floatingNavChrome
import com.aeriotv.android.feature.multiview.AccentTextControl
import com.aeriotv.android.ui.theme.textAccent

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

/**
 * Dock card for a TV advertising `_aeriotv-setup._tcp` (docs/DeviceSetupHandoff.md),
 * phones and tablets: the same capsule shell as the other dock cards (floating
 * nav chrome, 8 dp vertical padding, 12 dp phone / 18 dp tablet side padding,
 * 40 dp tile, 48 dp trailing control), placed in the nav bar's measured frame.
 */
@Composable
fun SetupTvDockCard(
    target: SetupTarget,
    tablet: Boolean,
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
            .padding(start = if (tablet) 18.dp else 12.dp, end = 4.dp, top = RetainedCardVerticalPadding, bottom = RetainedCardVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${target.name} is waiting for setup",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Check that the TV shows ${target.fingerprint}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.textAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.heightIn(min = RetainedCardControlHeight), contentAlignment = Alignment.Center) {
            AccentTextControl(label = "Set up", onClick = onSetUp)
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
