package com.aeriotv.android.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.aeriotv.android.feature.multiview.AddToMultiviewSheet
import com.aeriotv.android.feature.multiview.MultiviewTile
import com.aeriotv.android.feature.multiview.rememberMultiviewStoreHandle

/**
 * GH #76 (Apple parity): the Multiview button on a fullscreen DVR recording.
 * Same picker and same seeding rule as the live player (PlayerScreen
 * seedCurrent): the playing recording becomes Tile 1 with audio focus, any
 * already staged tiles stay behind it, Cancel restores the exact pre-open set.
 */
@Composable
fun RecordingMultiviewPicker(
    seedTile: MultiviewTile,
    onLaunch: () -> Unit,
    onClose: () -> Unit,
) {
    val store = rememberMultiviewStoreHandle()
    val snapshot = remember { store.selected.value }
    val snapshotFocus = remember { store.audioFocusedIndex.value }
    LaunchedEffect(Unit) {
        val without = store.selected.value.filterNot { it.id == seedTile.id }
        store.restore((listOf(seedTile) + without).take(store.maxTiles), 0)
    }
    AddToMultiviewSheet(
        currentChannel = null,
        multiviewStore = store,
        onLaunch = onLaunch,
        onCancel = {
            store.restore(snapshot, snapshotFocus)
            onClose()
        },
        onDismiss = onClose,
    )
}

/** The tile for a recording, matching the picker's own Recordings rows
 *  (id "dvr-<recording id>", headers only on http(s) URLs). An in-progress
 *  recording plays as a DVR tile. */
fun recordingMultiviewTile(
    recordingKey: String,
    title: String,
    playbackUrl: String,
    headers: Map<String, String>,
    inProgress: Boolean,
): MultiviewTile {
    val remote = playbackUrl.startsWith("http://", ignoreCase = true) ||
        playbackUrl.startsWith("https://", ignoreCase = true)
    return MultiviewTile(
        id = "dvr-$recordingKey",
        kind = if (inProgress) com.aeriotv.android.feature.multiview.TileKind.Dvr
        else com.aeriotv.android.feature.multiview.TileKind.Vod,
        displayName = title,
        resolvedUrl = playbackUrl,
        httpHeaders = if (remote) headers else emptyMap(),
        vodId = null,
    )
}
