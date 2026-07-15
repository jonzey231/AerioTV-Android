package com.aeriotv.android.feature.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouter
import com.aeriotv.android.core.cast.AerioCastSender

/**
 * Phone Cast button (GH #33) for the player chrome. Hidden when no cast route is
 * available; tapping opens a lightweight route chooser. Rendered via the
 * PlayerChromeOverlay.castSlot so it only appears on the phone/tablet sender.
 * Deliberately built without AppCompat (the app uses a Material3 Compose theme,
 * and the stock MediaRouteButton / MediaRouteChooserDialog require Theme.AppCompat).
 */
@Composable
fun CastIconButton(
    sender: AerioCastSender,
    modifier: Modifier = Modifier,
) {
    val state by sender.state.collectAsState()
    // Discovery is driven app-wide by AerioCastSender (tied to the process
    // foreground lifecycle), so CastState is already current here and the button
    // shows the moment a registered device is found.
    //
    // showChooser + the chooser dialog MUST live ABOVE any early-return on
    // availability: opening the chooser starts an active MediaRouter scan, which
    // can make sender.state emit a transient Unavailable. If the whole composable
    // early-returned on that emission, the dialog (and this remember) would
    // unmount and the picker would "flash" shut on the FIRST tap, only sticking
    // on the second once the scan settled (GH #33 field report). Gate only the
    // button on availability; keep the dialog resilient to a transient flicker.
    var showChooser by remember { mutableStateOf(false) }
    val connected = state is AerioCastSender.State.Connected

    if (state !is AerioCastSender.State.Unavailable) {
        Box(
            modifier = modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (connected) Color.White.copy(alpha = 0.28f)
                    else Color.Black.copy(alpha = 0.55f),
                )
                .then(
                    if (connected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier,
                )
                .clickable { showChooser = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (connected) Icons.Filled.CastConnected else Icons.Filled.Cast,
                contentDescription = if (connected) "Casting" else "Cast",
                tint = Color.White,
            )
        }
    }

    if (showChooser) {
        CastRouteChooserDialog(sender = sender, onDismiss = { showChooser = false })
    }
}

/**
 * A minimal Cast route chooser: lists the cast devices matching AerioTV's
 * receiver selector (active-scan while open) and, when connected, offers a stop
 * action. Selecting a route hands off to the Cast framework's SessionManager,
 * which starts the session; [AerioCastSender] then loads the pending content.
 */
@Composable
private fun CastRouteChooserDialog(
    sender: AerioCastSender,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by sender.state.collectAsState()
    val connected = state is AerioCastSender.State.Connected

    val selector = remember { sender.routeSelector() }
    val router = remember { runCatching { MediaRouter.getInstance(context) }.getOrNull() }
    var routes by remember { mutableStateOf<List<MediaRouter.RouteInfo>>(emptyList()) }

    DisposableEffect(selector, router) {
        if (selector == null || router == null) {
            onDispose { }
        } else {
            fun refresh() {
                routes = router.routes.filter {
                    !it.isDefault && it.isEnabled && it.matchesSelector(selector)
                }
            }
            val callback = object : MediaRouter.Callback() {
                override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            }
            router.addCallback(
                selector,
                callback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or
                    MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
            )
            refresh()
            onDispose { router.removeCallback(callback) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (connected) "Casting" else "Cast to") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (routes.isEmpty()) {
                    Text("Searching for devices...")
                }
                routes.forEach { route ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching { router?.selectRoute(route) }
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                    ) {
                        Icon(
                            imageVector = if (route.isSelected) Icons.Filled.CastConnected
                            else Icons.Filled.Cast,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Text(route.name)
                    }
                }
            }
        },
        confirmButton = {
            if (connected) {
                TextButton(onClick = {
                    sender.stopCasting()
                    onDismiss()
                }) { Text("Stop casting") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = if (connected) {
            { TextButton(onClick = onDismiss) { Text("Close") } }
        } else {
            null
        },
    )
}
