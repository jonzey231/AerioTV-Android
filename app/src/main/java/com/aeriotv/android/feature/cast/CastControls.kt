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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.KeyboardType
import androidx.mediarouter.media.MediaRouter
import com.aeriotv.android.core.cast.AerioCastSender
import com.aeriotv.android.core.cast.companion.CompanionDiscovery
import com.aeriotv.android.core.cast.companion.CompanionRemoteController

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
    // GH #33 companion remote: the SAME picker also lists AerioTV TVs on the LAN
    // (with inline pairing), so casting and companion-controlling share one flow.
    companionRemote: CompanionRemoteController? = null,
    companionDiscovery: CompanionDiscovery? = null,
    /** Reports the chooser dialog's open state so the host can pin the player
     *  chrome: this button lives INSIDE the auto-hiding chrome, and without the
     *  pin the 4s auto-hide unmounts the button (and the dialog with it) before
     *  the user can read the device list (GH #33 field report). */
    onChooserOpenChange: (Boolean) -> Unit = {},
) {
    val state by sender.state.collectAsState()
    val companionConn = companionRemote?.connection?.collectAsState()?.value
    val companionConnected = companionConn is CompanionRemoteController.Conn.Connected
    // AerioTV TVs discovered over mDNS make the button appear even with NO
    // Google Cast route (sideloaded TV install, or a phone without Play
    // services). Discovery itself is owned by PlayerScreen (running the whole
    // time the player is open) -- NOT this button, which lives inside the
    // auto-hiding chrome: owning it here restarted the mDNS browse on every
    // chrome show, leaving the button invisible for the first seconds each
    // time (device test 2026-07-15).
    val companionTvsExist =
        (companionDiscovery?.devices?.collectAsState()?.value?.isNotEmpty() == true)
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
    LaunchedEffect(showChooser) { onChooserOpenChange(showChooser) }
    DisposableEffect(Unit) { onDispose { onChooserOpenChange(false) } }
    val connected = state is AerioCastSender.State.Connected || companionConnected

    if (state !is AerioCastSender.State.Unavailable || companionConnected || companionTvsExist) {
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
        CastRouteChooserDialog(
            sender = sender,
            companionRemote = companionRemote,
            companionDiscovery = companionDiscovery,
            onDismiss = { showChooser = false },
        )
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
    companionRemote: CompanionRemoteController?,
    companionDiscovery: CompanionDiscovery?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by sender.state.collectAsState()
    val connected = state is AerioCastSender.State.Connected

    // GH #33 companion remote: browse for open AerioTV TVs while the chooser is
    // up. A Google TV device typically appears BOTH as a Cast route (basic cast,
    // web receiver) and as an AerioTV TV (full native remote) -- the user picks
    // the transport. Pairing happens inline: pick a TV -> the TV shows a 6-digit
    // code -> enter it here (remembered per-TV afterwards).
    DisposableEffect(companionDiscovery) {
        companionDiscovery?.start()
        onDispose { companionDiscovery?.stop() }
    }
    val tvs = companionDiscovery?.devices?.collectAsState()?.value.orEmpty()
    val companionConn = companionRemote?.connection?.collectAsState()?.value
    var pairCode by remember { mutableStateOf("") }
    // Freshly paired -> the chooser's job is done; the remote overlay takes over.
    // TRANSITION-only: opening the chooser while ALREADY connected must NOT
    // insta-dismiss it (the user may want to read the list / disconnect).
    var connectedAtOpen by remember {
        mutableStateOf(companionConn is CompanionRemoteController.Conn.Connected)
    }
    LaunchedEffect(companionConn) {
        when {
            companionConn !is CompanionRemoteController.Conn.Connected -> connectedAtOpen = false
            !connectedAtOpen -> onDismiss()
        }
    }

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
                if (routes.isEmpty() && tvs.isEmpty() &&
                    companionConn !is CompanionRemoteController.Conn.NeedsPairing
                ) {
                    Text("Searching for devices...")
                }
                routes.forEach { route ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Mutual exclusion: one remote target at a time.
                                companionRemote?.disconnect()
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
                // AerioTV TVs (GH #33 companion remote). A pending pairing takes
                // over the section with the code entry.
                when (companionConn) {
                    is CompanionRemoteController.Conn.NeedsPairing -> {
                        Text("Enter the code shown on ${companionConn.name ?: "the TV"}")
                        OutlinedTextField(
                            value = pairCode,
                            onValueChange = { v ->
                                if (v.length <= 6 && v.all { it.isDigit() }) pairCode = v
                            },
                            label = { Text("6-digit code") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { companionRemote?.disconnect() }) { Text("Cancel") }
                            TextButton(
                                onClick = { companionRemote?.submitPairingCode(pairCode) },
                                enabled = pairCode.length == 6,
                            ) { Text("Pair") }
                        }
                    }
                    is CompanionRemoteController.Conn.Connecting ->
                        Text("Connecting to ${companionConn.name ?: "TV"}...")
                    else -> tvs.forEach { tv ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Mutual exclusion with a live cast session.
                                    sender.stopCasting()
                                    companionRemote?.connect(tv)
                                }
                                .padding(vertical = 12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Tv,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                            Column {
                                Text(tv.name)
                                Text(
                                    "AerioTV Remote",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                connected -> TextButton(onClick = {
                    sender.stopCasting()
                    onDismiss()
                }) { Text("Stop casting") }
                companionConn is CompanionRemoteController.Conn.Connected -> TextButton(onClick = {
                    companionRemote?.disconnect()
                    onDismiss()
                }) { Text("Disconnect TV") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = if (connected || companionConn is CompanionRemoteController.Conn.Connected) {
            { TextButton(onClick = onDismiss) { Text("Close") } }
        } else {
            null
        },
    )
}
