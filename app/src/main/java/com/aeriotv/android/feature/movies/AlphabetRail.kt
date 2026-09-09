package com.aeriotv.android.feature.movies

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phone alphabet rail (Apple parity, MoviesView AlphabetRail): "#" then
 * A to Z in 21 dp cells, 12 sp, one touch surface for the whole column so a
 * finger anywhere on the lane picks the letter; dragging changes it. Letters
 * with no titles are dimmed but still hittable and resolve to the nearest
 * letter that has some. Total height 27 x 21 = 567 dp.
 */
@Composable
fun AlphabetRail(
    available: Set<Char>,
    onLetter: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cell = 21.dp
    val density = LocalDensity.current
    val cellPx = with(density) { cell.toPx() }
    val latest by rememberUpdatedState(onLetter)
    val availableNow by rememberUpdatedState(available)
    var lastChosen by remember { mutableStateOf<Char?>(null) }
    fun choose(y: Float) {
        val i = (y / cellPx).toInt().coerceIn(0, railLetters.lastIndex)
        val letter = nearestAvailable(railLetters[i], availableNow) ?: return
        if (letter != lastChosen) { lastChosen = letter; latest(letter) }
    }
    Column(
        modifier = modifier
            .width(34.dp)
            .height(cell * railLetters.size)
            .pointerInput(Unit) {
                detectTapGestures(onPress = { lastChosen = null; choose(it.y) })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { lastChosen = null; choose(it.y) },
                    onDrag = { change, _ -> choose(change.position.y) },
                    onDragEnd = { lastChosen = null },
                )
            },
        horizontalAlignment = Alignment.End,
    ) {
        railLetters.forEach { letter ->
            val enabled = letter in available
            Box(modifier = Modifier.fillMaxWidth().height(cell), contentAlignment = Alignment.Center) {
                Text(
                    text = letter.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                )
            }
        }
    }
}

/** A dead letter resolves to the next letter with titles, else the previous one. */
fun nearestAvailable(letter: Char, available: Set<Char>): Char? {
    if (letter in available) return letter
    val i = railLetters.indexOf(letter)
    for (j in i + 1..railLetters.lastIndex) if (railLetters[j] in available) return railLetters[j]
    for (j in i - 1 downTo 0) if (railLetters[j] in available) return railLetters[j]
    return null
}
