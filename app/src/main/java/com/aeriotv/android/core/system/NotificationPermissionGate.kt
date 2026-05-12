package com.aeriotv.android.core.system

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * On Android 13+ apps must request POST_NOTIFICATIONS at runtime; without it,
 * the DVR ongoing notification, programme reminders, and the background
 * playback notification all silently no-op. The permission is declared in the
 * manifest but the runtime request was missing — this gate fires it once per
 * install on the first cold start where the permission isn't already granted.
 *
 * Pre-API-33 (Android 12 and below) the permission is implicit, so this gate
 * is a no-op there.
 */
@Composable
fun NotificationPermissionGate() {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val alreadyGranted = remember {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
    if (alreadyGranted) return

    // Guard against requesting twice in the same composition lifecycle (e.g.
    // recomposition during the in-flight system dialog).
    var requested by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* result is best-effort; user-denied is acceptable, we just silently lose notifications */ }

    LaunchedEffect(Unit) {
        if (!requested) {
            requested = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
