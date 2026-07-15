package com.aeriotv.android.feature.cast.companion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.cast.CastControl
import com.aeriotv.android.core.cast.companion.CompanionDiscovery
import com.aeriotv.android.core.cast.companion.CompanionRemoteController
import kotlin.math.abs

/**
 * Phone companion-remote screen (GH #33 second-screen, v1 minimal slice): discover
 * open AerioTV TVs, connect + pair with the 6-digit code shown on the TV, then drive
 * the TV's NATIVE player. Controls here are the transport + rewind headline (the part
 * basic cast can't do); the full audio/subtitle/speed parity comes from binding
 * CastRemoteOverlay to the same controller in the polish pass.
 */
@Composable
fun CompanionRemoteScreen(
    discovery: CompanionDiscovery,
    controller: CompanionRemoteController,
    onClose: () -> Unit,
) {
    // Discover only while this screen is on-screen.
    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.stop() }
    }

    val tvs by discovery.devices.collectAsStateWithLifecycle()
    val conn by controller.connection.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Companion Remote", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Control an AerioTV TV on your network",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        when (val c = conn) {
            is CompanionRemoteController.Conn.Idle -> TvPicker(tvs, onPick = { controller.connect(it) })
            is CompanionRemoteController.Conn.Connecting -> Centered { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Connecting to ${c.name ?: "TV"}…") }
            is CompanionRemoteController.Conn.NeedsPairing -> PairingEntry(c.name, onSubmit = { controller.submitPairingCode(it) }, onCancel = { controller.disconnect() })
            is CompanionRemoteController.Conn.Connected -> Controls(c.name, controller, onDisconnect = { controller.disconnect() })
            is CompanionRemoteController.Conn.Failed -> Centered {
                Text("Couldn't connect: ${c.reason}", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { controller.disconnect() }) { Text("Back") }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Close") }
    }
}

@Composable
private fun TvPicker(tvs: List<CompanionDiscovery.Tv>, onPick: (CompanionDiscovery.Tv) -> Unit) {
    if (tvs.isEmpty()) {
        Centered { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Searching for TVs…") }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(tvs, key = { it.deviceId }) { tv ->
            Card(Modifier.fillMaxWidth().clickable { onPick(tv) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(tv.name, style = MaterialTheme.typography.titleMedium)
                    Text("${tv.host}:${tv.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PairingEntry(name: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Enter the code shown on ${name ?: "the TV"}", textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 6 && it.all { ch -> ch.isDigit() }) code = it },
            label = { Text("6-digit code") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onSubmit(code) }, enabled = code.length == 6) { Text("Pair") }
        }
    }
}

@Composable
private fun Controls(name: String?, controller: CompanionRemoteController, onDisconnect: () -> Unit) {
    val position by controller.position.collectAsStateWithLifecycle()
    val isPlaying by controller.isPlaying.collectAsStateWithLifecycle()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Controlling ${name ?: "TV"}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(behindLiveLabel(position), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { controller.seekBy(-30_000) }) { Text("-30s") }
            Button(onClick = { controller.togglePlayPause() }) { Text(if (isPlaying) "Pause" else "Play") }
            OutlinedButton(onClick = { controller.seekBy(30_000) }) { Text("+30s") }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { controller.goLiveRemote() }, enabled = !position.isLive) { Text("Go Live") }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
    }
}

private fun behindLiveLabel(p: CastControl.PositionSnapshot): String {
    if (p.isLive || !p.canSeek) return "● LIVE"
    val behindMs = (p.windowEndMs - p.positionWallMs).coerceAtLeast(0)
    val totalSec = abs(behindMs) / 1000
    return "-%d:%02d behind live".format(totalSec / 60, totalSec % 60)
}

@Composable
private fun Centered(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
