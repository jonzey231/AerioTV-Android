package com.aeriotv.android.ui.settings

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aeriotv.android.ui.FormFactorModal
import com.aeriotv.android.ui.settings.ColorPickerMath as M

private fun colorOf(rgb: Int) = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

private const val SAVED_PREFS = "color_picker"
private const val SAVED_KEY = "saved_colors"

private fun loadSaved(ctx: Context): List<Int> =
    ctx.getSharedPreferences(SAVED_PREFS, Context.MODE_PRIVATE).getString(SAVED_KEY, "")!!
        .split(',').mapNotNull { M.parseHex(it) }

private fun storeSaved(ctx: Context, colors: List<Int>) {
    ctx.getSharedPreferences(SAVED_PREFS, Context.MODE_PRIVATE).edit()
        .putString(SAVED_KEY, colors.joinToString(",") { M.hex(it) }).apply()
}

/**
 * Theme Color / Accent Color sheet for phones and tablets, matching the Apple
 * color sheet (which wraps the system color picker): title with a trailing
 * Done, the Hex Color field, a Colors header, Grid / Spectrum / Sliders tabs,
 * then the current-color swatch and the quick colors row with "+" to save.
 * Every change commits live through [onColor]. Android has no system
 * eyedropper, so the Colors header carries no eyedropper button.
 */
@Composable
fun HexColorPickerSheet(
    title: String,
    initialHex: String,
    onColor: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var current by remember { mutableIntStateOf(M.parseHex(initialHex) ?: 0x2DD4BF) }
    var draft by remember { mutableStateOf(M.hex(current)) }
    var tab by remember { mutableIntStateOf(1) }
    var saved by remember { mutableStateOf(loadSaved(ctx)) }
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    fun pick(rgb: Int) {
        val c = rgb and 0xFFFFFF
        if (c == current) return
        current = c
        draft = M.hex(c)
        onColor(M.hex(c))
    }

    FormFactorModal(onDismiss = onDismiss, sheetMaxWidth = 560.dp, sheetGesturesEnabled = false, traceName = "color") {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(48.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.Center),
                )
                Text(
                    "Done",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(50))
                        .background(muted.copy(alpha = 0.22f))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            SettingsTextField(
                label = "Hex Color",
                value = draft,
                onValueChange = { raw ->
                    val cleaned = M.cleanHexInput(raw)
                    draft = cleaned
                    M.parseHex(cleaned)?.let { c ->
                        if (c != current) { current = c; onColor(cleaned) }
                    }
                },
                placeholder = "2DD4BF",
                helper = "Six hex digits, for example 2DD4BF.",
                keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Colors",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(12.dp))
            Segmented(listOf("Grid", "Spectrum", "Sliders"), tab) { tab = it }
            Spacer(Modifier.height(12.dp))
            when (tab) {
                0 -> ColorGrid(current, ::pick)
                1 -> SpectrumPanel(current, ::pick)
                else -> RgbSliders(current, ::pick)
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(colorOf(current)),
                )
                Spacer(Modifier.width(20.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    (M.QUICK_COLORS + saved).distinct().takeLast(7).forEach { c ->
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(colorOf(c))
                                .border(if (c == current) 2.dp else 0.dp, if (c == current) Color.White else Color.Transparent, CircleShape)
                                .clickable { pick(c) },
                        )
                    }
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(muted.copy(alpha = 0.35f))
                            .clickable {
                                saved = (saved.filter { it != current } + current).takeLast(12)
                                storeSaved(ctx, saved)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Save color", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)).padding(2.dp),
    ) {
        labels.forEachIndexed { i, label ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                    .background(if (i == selected) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
private fun ColorGrid(current: Int, onPick: (Int) -> Unit) {
    val grid = remember { M.gridColors() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = M.GRID_COLUMNS; val rows = M.GRID_ROWS
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(cols.toFloat() / rows).clip(RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    fun at(o: Offset) {
                        val c = (o.x / size.width * cols).toInt().coerceIn(0, cols - 1)
                        val r = (o.y / size.height * rows).toInt().coerceIn(0, rows - 1)
                        onPick(grid[r][c])
                    }
                    detectTapGestures { at(it) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val o = change.position
                        val c = (o.x / size.width * cols).toInt().coerceIn(0, cols - 1)
                        val r = (o.y / size.height * rows).toInt().coerceIn(0, rows - 1)
                        onPick(grid[r][c])
                    }
                },
        ) {
            val w = size.width / cols; val h = size.height / rows
            grid.forEachIndexed { r, row ->
                row.forEachIndexed { c, rgb ->
                    drawRect(colorOf(rgb), topLeft = Offset(c * w, r * h), size = androidx.compose.ui.geometry.Size(w + 0.5f, h + 0.5f))
                }
            }
            grid.forEachIndexed { r, row ->
                row.forEachIndexed { c, rgb ->
                    if (rgb == current) drawRect(
                        Color.White, topLeft = Offset(c * w, r * h),
                        size = androidx.compose.ui.geometry.Size(w, h), style = Stroke(3.dp.toPx()),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpectrumPanel(current: Int, onPick: (Int) -> Unit) {
    val hues = remember { (0..12).map { colorOf(M.hsl(it * 30f, 1f, 0.5f)) } }
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectTapGestures { o -> onPick(M.spectrumColor(o.x / size.width, o.y / size.height)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val o = change.position
                    onPick(M.spectrumColor(o.x / size.width, o.y / size.height))
                }
            },
    ) {
        drawRect(Brush.verticalGradient(hues))
        drawRect(Brush.horizontalGradient(0f to Color.White, 0.5f to Color.White.copy(alpha = 0f), 1f to Color.White.copy(alpha = 0f)))
        drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0f), 0.5f to Color.Black.copy(alpha = 0f), 1f to Color.Black))
        val (x, y) = M.spectrumPosition(current)
        val center = Offset(x * size.width, y * size.height)
        drawCircle(colorOf(current), radius = 11.dp.toPx(), center = center)
        drawCircle(Color.White, radius = 12.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = 13.5f.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun RgbSliders(current: Int, onPick: (Int) -> Unit) {
    val r = M.red(current); val g = M.green(current); val b = M.blue(current)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChannelSlider("RED", r, M.rgb(0, g, b), M.rgb(255, g, b)) { onPick(M.rgb(it, g, b)) }
        ChannelSlider("GREEN", g, M.rgb(r, 0, b), M.rgb(r, 255, b)) { onPick(M.rgb(r, it, b)) }
        ChannelSlider("BLUE", b, M.rgb(r, g, 0), M.rgb(r, g, 255)) { onPick(M.rgb(r, g, it)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text("sRGB Hex Color #", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            ValuePill(M.hex(current), Modifier.widthIn(min = 76.dp))
        }
    }
}

@Composable
private fun ValuePill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun ChannelSlider(label: String, value: Int, from: Int, to: Int, onValue: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(
                Modifier.weight(1f).height(32.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { o -> onValue(((o.x - size.height / 2f) / (size.width - size.height) * 255f).toInt().coerceIn(0, 255)) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            onValue(((change.position.x - size.height / 2f) / (size.width - size.height) * 255f).toInt().coerceIn(0, 255))
                        }
                    },
            ) {
                val rad = size.height / 2f
                drawRoundRect(
                    Brush.horizontalGradient(listOf(colorOf(from), colorOf(to))),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(rad, rad),
                )
                val cx = rad + (size.width - 2 * rad) * value / 255f
                drawCircle(Color.White, radius = rad - 3.dp.toPx(), center = Offset(cx, rad), style = Stroke(3.dp.toPx()))
            }
            Spacer(Modifier.width(12.dp))
            ValuePill(value.toString(), Modifier.width(56.dp))
        }
    }
}
