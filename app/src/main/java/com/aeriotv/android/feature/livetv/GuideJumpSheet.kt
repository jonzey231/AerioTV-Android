package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
    if (isTv) {
        // tvOS GuideJumpSheet (halved): 38 pt bold title, Day and Time pill
        // rows (MoviesPillStyle = TvPill), the target as a summary line, then
        // Go as a selected pill and Back to Now as an unselected one.
        FormFactorModal(onDismiss = onDismiss, tvWidthFraction = 0.62f, sheetMaxWidth = 600.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("Jump To", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                // Days in three groups (Logan 2026-09-10): Today, Upcoming,
                // Previous. Labels are formatted once; each group is a single
                // horizontally scrolling row like the tvOS pill rows.
                val dayLabels = remember(dayOffsets) { dayOffsets.associateWith { dayLabel(it) } }
                @Composable
                fun dayGroup(title: String, offsets: List<Int>) {
                    if (offsets.isEmpty()) return
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 3.dp),
                        ) {
                            items(offsets.size, key = { offsets[it] }) { i ->
                                val offset = offsets[i]
                                com.aeriotv.android.ui.tv.TvPill(dayLabels[offset] ?: dayLabel(offset), dayOffset == offset, onClick = { dayOffset = offset })
                            }
                        }
                    }
                }
                dayGroup("Today", listOf(0))
                dayGroup("Upcoming", dayOffsets.filter { it > 0 })
                dayGroup("Previous", dayOffsets.filter { it < 0 }.reversed())
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Time", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 3.dp),
                    ) {
                        // tvOS labels carry no hour; the summary line shows the time.
                        items(slots.size, key = { slots[it].second }) { i ->
                            val (label, hour) = slots[i]
                            com.aeriotv.android.ui.tv.TvPill(label, slotHour == hour, onClick = { slotHour = hour })
                        }
                    }
                }
                Text(
                    java.text.SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(java.util.Date(target())),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.aeriotv.android.ui.tv.TvPill("Go", selected = true, onClick = { onJump(target()); onDismiss() })
                    com.aeriotv.android.ui.tv.TvPill("Back to Now", selected = false, onClick = { onBackToNow(); onDismiss() })
                }
            }
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
