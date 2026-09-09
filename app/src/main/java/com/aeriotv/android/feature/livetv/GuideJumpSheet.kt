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
    val dayOffsets = remember(daysBack, daysAhead) { (-daysBack.coerceIn(0, 7)..daysAhead.coerceIn(1, 7)).toList() }
    val slots = remember {
        listOf(
            "Same Time" to -1, "Morning" to 7, "Afternoon" to 13,
            "Evening" to 18, "Prime Time" to 20, "Late" to 23,
        )
    }
    var dayOffset by remember { mutableStateOf(0) }
    var slotHour by remember { mutableStateOf(-1) }

    fun dayLabel(offset: Int): String = when (offset) {
        -1 -> "Yesterday"
        0 -> "Today"
        1 -> "Tomorrow"
        else -> {
            val c = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            java.text.SimpleDateFormat("EEEE", Locale.getDefault()).format(c.time)
        }
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
