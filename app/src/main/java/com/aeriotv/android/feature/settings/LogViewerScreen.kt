package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeriotv.android.core.debug.DebugLogger
import com.aeriotv.android.ui.settings.SettingsHeaderTextButton
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-app log viewer for Settings -> Developer -> View Log File. Mirrors iOS
 * `LogViewerView` (Aerio/Features/Settings/DeveloperSettingsView.swift line
 * 662+): tail the last ~5,000 lines of `aerio_debug_logs.txt`, render
 * monospace, refreshable. Doesn't try to live-stream; the user opens this
 * to inspect a captured artifact, not to watch a running stream; tapping
 * Refresh re-reads the file end. Cheap enough on a 10 MB cap to read fully.
 *
 * Lines are coloured by level prefix (E/W -> red/orange) so an error in
 * the middle of a long file is easy to scroll-spot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val debugLogger = remember {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            com.aeriotv.android.core.debug.DebugLoggerEntryPoint::class.java,
        )
        entry.debugLogger()
    }

    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        loading = true
        lines = withContext(Dispatchers.IO) { readTail(debugLogger, MAX_LINES) }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Log File",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                // No back arrow on Android TV -- the remote BACK pops it.
                if (!rememberIsTvDevice()) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            actions = {
                if (rememberIsTvDevice()) {
                    // Edge IconButton has no visible D-pad focus; pill insets
                    // to the 48dp overscan margin and rings under focus.
                    SettingsHeaderTextButton(label = "Refresh", onClick = { refreshTick++ })
                } else {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        when {
            loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            lines.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Log file is empty",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Enable Debug Logging on the previous screen and reproduce " +
                            "the issue you want to diagnose. Lines appear here as the app writes them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    reverseLayout = false,
                ) {
                    items(items = lines) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            color = colorFor(line),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Tail the last [maxLines] lines of the live log file. Reading the whole
 *  file then trimming is fine at the 10 MB rotation cap; a true reverse
 *  read would be premature optimisation. */
private fun readTail(debugLogger: DebugLogger, maxLines: Int): List<String> {
    val file = debugLogger.logFile()
    if (!file.exists() || file.length() == 0L) return emptyList()
    return runCatching {
        val all = file.readLines()
        if (all.size > maxLines) all.subList(all.size - maxLines, all.size) else all
    }.getOrDefault(emptyList())
}

/** Colour the line based on its level prefix (`<ts> <I|W|E>/Tag: msg`). */
private fun colorFor(line: String): Color {
    // After the timestamp and a space we expect a single-letter level then '/'.
    val idx = line.indexOf(' ').takeIf { it > 0 } ?: return Color.Unspecified
    val rest = line.substring(idx + 1)
    val levelEnd = rest.indexOf('/').takeIf { it == 1 } ?: return Color.Unspecified
    return when (rest[0]) {
        'E' -> Color(0xFFFF6B6B)
        'W' -> Color(0xFFFFB454)
        else -> Color.Unspecified
    }
}

private const val MAX_LINES = 5_000
