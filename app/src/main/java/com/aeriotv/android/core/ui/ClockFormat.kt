package com.aeriotv.android.core.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Settings > Appearance > Time Format. One process-wide mode (seeded from
 * [com.aeriotv.android.core.preferences.AppPreferences.timeFormat] by the
 * Application) so every clock in the app agrees: System follows the device's
 * 24-hour setting, the other two force 12-hour or 24-hour regardless.
 */
object ClockFormat {
    enum class Mode { SYSTEM, H12, H24 }

    val mode = MutableStateFlow(Mode.SYSTEM)

    @Volatile private var system24 = false

    fun init(context: Context) {
        system24 = android.text.format.DateFormat.is24HourFormat(context)
    }

    fun fromPref(value: String): Mode = when (value) {
        "12" -> Mode.H12
        "24" -> Mode.H24
        else -> Mode.SYSTEM
    }

    fun use24(m: Mode = mode.value): Boolean = when (m) {
        Mode.H12 -> false
        Mode.H24 -> true
        Mode.SYSTEM -> system24
    }

    /** "7:30 PM" / "19:30"; System keeps the locale's own short style. */
    fun short(m: Mode = mode.value): DateFormat = when (m) {
        Mode.SYSTEM -> DateFormat.getTimeInstance(DateFormat.SHORT)
        Mode.H12 -> SimpleDateFormat("h:mm a", Locale.getDefault())
        Mode.H24 -> SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    /** Guide header labels: "7:30pm" / "19:30". */
    fun guideLabel(m: Mode = mode.value): DateFormat =
        SimpleDateFormat(if (use24(m)) "HH:mm" else "h:mma", Locale.getDefault())

    /** Guide cell ranges: "7:30" / "19:30". */
    fun guideShort(m: Mode = mode.value): DateFormat =
        SimpleDateFormat(if (use24(m)) "HH:mm" else "h:mm", Locale.getDefault())
}

/** The live mode, so composables re-render when the setting changes. */
@Composable
fun rememberClockMode(): ClockFormat.Mode = ClockFormat.mode.collectAsState().value
