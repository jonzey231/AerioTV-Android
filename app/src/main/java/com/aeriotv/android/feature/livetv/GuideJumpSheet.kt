package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeriotv.android.ui.FormFactorModal
import java.util.Calendar
import java.util.Locale

/**
 * Guide "Jump To" (Roman via Discord 2026-09-06; Apple parity with
 * GuideJumpSheet.swift): pick a day (Yesterday, Today, Tomorrow, then
 * weekday names up to [daysAhead]) and a time of day. Picking a day alone
 * keeps the current clock time on that day. Go returns the target instant;
 * Back to Now clears the jump.
 */
@Composable
fun GuideJumpSheet(
    daysBack: Int,
    daysAhead: Int,
    onJump: (Long) -> Unit,
    onBackToNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    val today = remember(now) {
        Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    }
    // One pill per day of loaded EPG, past and future (the caller measures it).
    val dayOffsets = remember(daysBack, daysAhead) { (-daysBack.coerceIn(0, 14)..daysAhead.coerceIn(1, 14)).toList() }
    val slots = remember {
        listOf(
            "Same Time" to -1, "Morning" to 7, "Afternoon" to 13,
            "Evening" to 18, "Prime Time" to 20, "Late" to 23,
        )
    }
    var dayOffset by remember { mutableStateOf(0) }
    var slotHour by remember { mutableStateOf(-1) }

    // "Today, Sep 10" / "Tomorrow, Sep 11" / "Sat, Sep 12" (Logan 2026-09-10:
    // every day pill carries its date; tvOS GuideJumpSheet.dayLabel).
    fun dayLabel(offset: Int): String {
        val c = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
        val name = when (offset) {
            -1 -> "Yesterday"
            0 -> "Today"
            1 -> "Tomorrow"
            else -> java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(c.time)
        }
        return name + ", " + java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(c.time)
    }
    fun target(): Long {
        val c = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        if (slotHour >= 0) {
            c.set(Calendar.HOUR_OF_DAY, slotHour); c.set(Calendar.MINUTE, 0)
        } else {
            val nowCal = Calendar.getInstance().apply { timeInMillis = now }
            c.set(Calendar.HOUR_OF_DAY, nowCal.get(Calendar.HOUR_OF_DAY))
            c.set(Calendar.MINUTE, nowCal.get(Calendar.MINUTE) / 30 * 30)
        }
        return c.timeInMillis
    }

    val isTv = com.aeriotv.android.ui.settings.rememberIsTvDevice()
    val clockMode = com.aeriotv.android.core.ui.rememberClockMode()
    if (isTv) {
        // Phone-less path kept for callers that still want the modal on TV.
        FormFactorModal(onDismiss = onDismiss, tvWidthFraction = 0.62f, sheetMaxWidth = 600.dp) {
            GuideJumpTvContent(daysBack, daysAhead, onJump, onBackToNow, onDismiss)
        }
        return
    }
    FormFactorModal(onDismiss = onDismiss, tvWidthFraction = 0.6f, sheetMaxWidth = 560.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Jump To", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Text("Day", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dayOffsets.forEach { offset ->
                    FilterChip(
                        selected = dayOffset == offset,
                        onClick = { dayOffset = offset },
                        label = { Text(dayLabel(offset)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                slots.forEach { (label, hour) ->
                    val text = when (hour) {
                        -1 -> label
                        else -> "$label " + java.text.SimpleDateFormat("h a", Locale.getDefault())
                            .format((today.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, hour) }.time)
                    }
                    FilterChip(
                        selected = slotHour == hour,
                        onClick = { slotHour = hour },
                        label = { Text(text) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { onBackToNow(); onDismiss() }) { Text("Back to Now") }
                Spacer(Modifier.width(10.dp))
                Button(onClick = { onJump(target()); onDismiss() }) { Text("Go") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}


/**
 * TV Jump To body (tvOS GuideJumpSheet, halved): title, Today / Upcoming /
 * Previous pill rows, the time row, the target summary, Go and Back to Now.
 */
@Composable
internal fun GuideJumpTvContent(
    daysBack: Int,
    daysAhead: Int,
    onJump: (Long) -> Unit,
    onBackToNow: () -> Unit,
    onDismiss: () -> Unit,
    firstPillFocus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val now = remember { System.currentTimeMillis() }
    val today = remember(now) {
        Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    }
    val dayOffsets = remember(daysBack, daysAhead) { (-daysBack.coerceIn(0, 14)..daysAhead.coerceIn(1, 14)).toList() }
    val slots = remember {
        listOf("Same Time" to -1, "Morning" to 7, "Afternoon" to 13, "Evening" to 18, "Prime Time" to 20, "Late" to 23)
    }
    var dayOffset by remember { mutableStateOf(0) }
    var slotHour by remember { mutableStateOf(-1) }
    val clockMode = com.aeriotv.android.core.ui.rememberClockMode()
    fun dayLabel(offset: Int): String {
        val c = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
        val name = when (offset) {
            -1 -> "Yesterday"; 0 -> "Today"; 1 -> "Tomorrow"
            else -> java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(c.time)
        }
        return name + ", " + java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(c.time)
    }
    fun target(): Long {
        val c = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        if (slotHour >= 0) { c.set(Calendar.HOUR_OF_DAY, slotHour); c.set(Calendar.MINUTE, 0) } else {
            val nowCal = Calendar.getInstance().apply { timeInMillis = now }
            c.set(Calendar.HOUR_OF_DAY, nowCal.get(Calendar.HOUR_OF_DAY)); c.set(Calendar.MINUTE, nowCal.get(Calendar.MINUTE) / 30 * 30)
        }
        return c.timeInMillis
    }
    val dayLabels = remember(dayOffsets) { dayOffsets.associateWith { dayLabel(it) } }
    val timeLabels = remember(clockMode) {
        val f = com.aeriotv.android.core.ui.ClockFormat.short(clockMode)
        slots.filter { it.second >= 0 }.associate { (_, h) -> h to f.format((today.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, 0) }.time) }
    }
    @Composable
    fun pillRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp, vertical = 5.dp),
            modifier = Modifier.fillMaxWidth().offset(x = (-5).dp),
            content = content,
        )
    }
    @Composable
    fun dayGroup(title: String, offsets: List<Int>) {
        if (offsets.isEmpty()) return
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            pillRow {
                items(offsets.size, key = { offsets[it] }) { i ->
                    val offset = offsets[i]
                    com.aeriotv.android.ui.tv.TvPill(
                        dayLabels[offset] ?: dayLabel(offset), dayOffset == offset, onClick = { dayOffset = offset },
                        modifier = if (offset == 0 && firstPillFocus != null) Modifier.focusRequester(firstPillFocus) else Modifier,
                    )
                }
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Jump To", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        dayGroup("Today", listOf(0))
        dayGroup("Upcoming", dayOffsets.filter { it > 0 })
        dayGroup("Previous", dayOffsets.filter { it < 0 }.reversed())
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Time", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            pillRow {
                items(slots.size, key = { slots[it].second }) { i ->
                    val (label, hour) = slots[i]
                    com.aeriotv.android.ui.tv.TvPill(if (hour == -1) label else timeLabels[hour] ?: label, slotHour == hour, onClick = { slotHour = hour })
                }
            }
        }
        Text(
            java.text.SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(java.util.Date(target())),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            com.aeriotv.android.ui.tv.TvPill("Go", selected = true, onClick = { onJump(target()); onDismiss() })
            com.aeriotv.android.ui.tv.TvPill("Back to Now", selected = false, onClick = { onBackToNow(); onDismiss() })
        }
    }
}

/**
 * In-place TV host: a scrim and a centered card inside the guide's own
 * Box, no Dialog window, so the sheet is on screen the same frame the hold
 * fires (Logan 2026-09-10). Focus is trapped inside; Back closes it.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun GuideJumpTvOverlay(
    daysBack: Int,
    daysAhead: Int,
    onJump: (Long) -> Unit,
    onBackToNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstPill = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) {
        repeat(10) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { firstPill.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
            .focusProperties { exit = { androidx.compose.ui.focus.FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = com.aeriotv.android.ui.tv.TvChrome.dialogSurface(),
            modifier = Modifier.width(600.dp).heightIn(max = 470.dp),
        ) {
            GuideJumpTvContent(daysBack, daysAhead, onJump, onBackToNow, onDismiss, firstPillFocus = firstPill)
        }
    }
}
