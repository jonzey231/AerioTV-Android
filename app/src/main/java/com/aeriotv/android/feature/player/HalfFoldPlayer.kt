package com.aeriotv.android.feature.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.ui.adaptive.FoldAxis
import com.aeriotv.android.ui.adaptive.FoldInfo
import com.aeriotv.android.ui.adaptive.rememberFoldInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Half-folded (tabletop) player, shared spec 2026-11-10 (Apple parity).
 *
 * When the fullscreen single player is up and the device is partly folded
 * with a HORIZONTAL crease (Galaxy Flip tabletop, Galaxy Fold held sideways),
 * the top half shows the video alone and the bottom half a panel: a
 * "Controls" | "Guide" segmented control over either the player's usual
 * controls (always visible, same order and wording) or a channel guide that
 * starts at the playing channel. VOD and DVR show Controls only. Open flat or
 * closed it is exactly the usual player, and switching posture never stops,
 * re-tunes, or seeks anything: only where things are drawn changes.
 */

/** The bottom panel's two views. */
enum class HalfFoldPanel { Controls, Guide }

/** Pure rule: does the half-folded layout apply? */
internal fun halfFoldApplies(fold: FoldInfo?, isTv: Boolean, inPip: Boolean): Boolean =
    !isTv && !inPip && fold != null && fold.axis == FoldAxis.HORIZONTAL && fold.halfOpened

/**
 * The fold to lay the player out around, or null for the usual layout. Not
 * on TV, never in picture in picture, only partly folded across a horizontal
 * crease.
 */
@Composable
fun rememberHalfFold(isTv: Boolean): FoldInfo? {
    val fold = rememberFoldInfo()
    val inPip by com.aeriotv.android.core.pip.PipState.inPictureInPicture
    return fold.takeIf { halfFoldApplies(it, isTv, inPip) }
}

/**
 * The Live TV list order (active group and sort) as channel ids, published by
 * the List and Guide views whenever they compute it with no search typed. The
 * half-folded Guide panel follows it, so it lists the channels the way the
 * user last saw them.
 */
object LiveChannelOrder {
    private val _ids = MutableStateFlow<List<String>>(emptyList())
    val ids: StateFlow<List<String>> = _ids.asStateFlow()

    fun publish(ids: List<String>) {
        if (_ids.value != ids) _ids.value = ids
    }
}

/**
 * Pure rule for the Guide panel's rows: the playing channel first, then the
 * channels after it in the Live TV order, wrapping around so every channel in
 * that order is reachable. A playing channel outside that order (opened from
 * search or another group) still leads, followed by the order as it stands.
 * With no published order the playlist's own order is used.
 */
internal fun halfFoldGuideRows(order: List<String>, channels: List<M3UChannel>, currentId: String?): List<M3UChannel> {
    val byId = channels.associateBy { it.id }
    val ordered = order.mapNotNull(byId::get).ifEmpty { channels }
    val idx = ordered.indexOfFirst { it.id == currentId }
    if (idx >= 0) return ordered.drop(idx) + ordered.take(idx)
    val current = currentId?.let(byId::get) ?: return ordered
    return listOf(current) + ordered.filterNot { it.id == current.id }
}

/** "42m left", "1h 5m left", "2h left", "<1m left" (Apple wording, shared spec). */
internal fun halfFoldTimeLeft(programme: EPGProgramme, nowMs: Long): String {
    val minutes = (programme.endMillis - nowMs).coerceAtLeast(0L) / 60_000L
    if (minutes <= 0L) return "<1m left"
    if (minutes < 60L) return "${minutes}m left"
    val hours = minutes / 60L
    val rest = minutes % 60L
    return if (rest == 0L) "${hours}h left" else "${hours}h ${rest}m left"
}

/** "Next: SportsCenter, 9:00 AM" (Apple wording, shared spec). */
internal fun halfFoldNextLine(title: String, startLabel: String): String = "Next: $title, $startLabel"

/** The airing program and the one after it, from a channel's guide list. */
internal fun halfFoldNowNext(programmes: List<EPGProgramme>?, nowMs: Long): Pair<EPGProgramme?, EPGProgramme?> {
    if (programmes.isNullOrEmpty()) return null to null
    val now = programmes.firstOrNull { it.startMillis <= nowMs && nowMs < it.endMillis }
    val after = now?.endMillis ?: nowMs
    val next = programmes.filter { it.startMillis >= after }.minByOrNull { it.startMillis }
    return now to next
}

/** The panel choice, remembered on this device only (never synced). */
internal object HalfFoldPanelPrefs {
    private const val PREFS = "aerio_half_fold_player"
    private const val KEY = "panel"

    fun read(context: Context): HalfFoldPanel =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { v -> HalfFoldPanel.entries.firstOrNull { it.name == v } }
            ?: HalfFoldPanel.Controls

    fun write(context: Context, panel: HalfFoldPanel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, panel.name).apply()
    }
}

/**
 * The half-folded video frame for the persistent live player window: the
 * height of the top half while the live player is laid out half folded, null
 * otherwise. Read by [phoneMiniFrameModifier], which already owns the
 * window's full-screen frame on touch devices.
 */
object HalfFoldVideo {
    val height = androidx.compose.runtime.mutableStateOf<Dp?>(null)
}

/**
 * Publishes the half-folded video frame ([HalfFoldVideo.height]) while [fold]
 * is set, and clears it when the posture or the screen goes away. Live player
 * only: the VOD player owns its own video view and sizes it directly.
 */
@Composable
internal fun HalfFoldVideoFrame(fold: FoldInfo?) {
    val height: Dp? = fold?.start
    LaunchedEffect(height) { HalfFoldVideo.height.value = height }
    DisposableEffect(Unit) {
        onDispose { HalfFoldVideo.height.value = null }
    }
}

/**
 * Lays out the player's chrome for the posture. With no [fold] it is exactly
 * the usual full-screen chrome: [controls] gets `forceVisible = false`. Half
 * folded, the top half is left clear for the video (and its tap gestures) and
 * the bottom half holds the panel; [controls] gets `forceVisible = true` (no
 * auto-hide). [guide] null = no Guide option (VOD, DVR, catch-up): the
 * segmented control is hidden and the panel is Controls only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HalfFoldChromeHost(
    fold: FoldInfo?,
    guide: (@Composable () -> Unit)?,
    controls: @Composable (forceVisible: Boolean) -> Unit,
) {
    if (fold == null) {
        controls(false)
        return
    }
    val context = LocalContext.current
    var panel by remember { mutableStateOf(HalfFoldPanelPrefs.read(context)) }
    val showGuide = guide != null && panel == HalfFoldPanel.Guide
    Column(Modifier.fillMaxSize()) {
        // Top half and the crease: nothing drawn, so the video and its tap
        // layer (channel flip, minimize swipe, pinch) work as usual.
        Spacer(Modifier.fillMaxWidth().height(fold.end))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                // The panel is not at the top of the window: its content must
                // not pad for the status bar again.
                .consumeWindowInsets(WindowInsets.statusBars)
                // Taps on the panel's blank areas stay in the panel.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            if (guide != null) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                ) {
                    HalfFoldPanel.entries.forEachIndexed { i, option ->
                        SegmentedButton(
                            selected = panel == option,
                            onClick = {
                                panel = option
                                HalfFoldPanelPrefs.write(context, option)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = HalfFoldPanel.entries.size),
                        ) { Text(if (option == HalfFoldPanel.Controls) "Controls" else "Guide") }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (showGuide) guide?.invoke() else controls(true)
            }
        }
    }
}

/**
 * The half-folded Guide panel (live TV only): the playing channel first and
 * highlighted, then the channels after it in the Live TV order. A tap tunes
 * that channel through the player's usual channel switch.
 */
@Composable
internal fun HalfFoldGuidePanel(
    channels: List<M3UChannel>,
    currentChannelId: String?,
    epgByChannel: Map<String, List<EPGProgramme>>,
    onSelect: (M3UChannel) -> Unit,
) {
    val order by LiveChannelOrder.ids.collectAsStateWithLifecycle()
    val rows = remember(order, channels, currentChannelId) {
        halfFoldGuideRows(order, channels, currentChannelId)
    }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    val listState = rememberLazyListState()
    // The highlighted row follows the playing channel: it leads the list, so
    // a channel change scrolls back to the top.
    LaunchedEffect(currentChannelId) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        items(rows, key = { it.id }) { ch ->
            val (now, next) = halfFoldNowNext(epgByChannel[ch.guideMatchKey], nowMs)
            HalfFoldGuideRow(
                channel = ch,
                now = now,
                next = next,
                nowMs = nowMs,
                current = ch.id == currentChannelId,
                onClick = { onSelect(ch) },
            )
        }
    }
}

@Composable
private fun HalfFoldGuideRow(
    channel: M3UChannel,
    now: EPGProgramme?,
    next: EPGProgramme?,
    nowMs: Long,
    current: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (current) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 56.dp, height = 36.dp), contentAlignment = Alignment.Center) {
            if (channel.tvgLogo.isNotBlank()) {
                AsyncImage(
                    model = channel.tvgLogo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val number = channel.channelNumber?.takeIf { it.isNotBlank() }
            Text(
                text = if (number != null) "$number  ${channel.name}" else channel.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (current) accent else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (now == null) {
                Text(
                    text = "No guide data",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            } else {
                // Time left sits at the END of the title line (Apple parity).
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = now.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = halfFoldTimeLeft(now, nowMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = accent,
                        maxLines = 1,
                    )
                }
                val span = (now.endMillis - now.startMillis).coerceAtLeast(1L)
                LinearProgressIndicator(
                    progress = { ((nowMs - now.startMillis).toFloat() / span).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = accent,
                    trackColor = Color.White.copy(alpha = 0.15f),
                    drawStopIndicator = {},
                )
                if (next != null) {
                    val at = com.aeriotv.android.core.ui.ClockFormat.short().format(java.util.Date(next.startMillis))
                    Text(
                        text = halfFoldNextLine(next.title, at),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * VOD player video view: the top half when half folded, else the full
 * screen. Computed here rather than in VODPlayerScreen, which sits at the ART
 * verifier's register limit.
 */
@Composable
internal fun halfFoldVideoModifier(isTv: Boolean): Modifier {
    val fold = rememberHalfFold(isTv)
    return if (fold != null) Modifier.fillMaxWidth().height(fold.start) else Modifier.fillMaxSize()
}

/** VOD, DVR, and catch-up chrome host: Controls only, never a Guide. */
@Composable
internal fun HalfFoldVodChrome(isTv: Boolean, controls: @Composable (forceVisible: Boolean) -> Unit) {
    HalfFoldChromeHost(fold = rememberHalfFold(isTv), guide = null, controls = controls)
}
