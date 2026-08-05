package com.aeriotv.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aeriotv.android.ui.settings.rememberIsTvDevice

/**
 * The app's standard modal container, split by form factor:
 *
 *  - Phone / tablet: a Material3 ModalBottomSheet (drag handle, swipe to
 *    dismiss) -- the natural touch idiom.
 *  - Android TV: a centered Dialog panel. A bottom sheet is wrong on a
 *    remote: its drag handle is a dead control that even GRABS D-pad focus
 *    (showing a "Drag handle" tooltip), and the sheet hugs the screen
 *    bottom. The dialog has no gesture chrome; BACK dismisses.
 *
 * Every modal in the app should use this (or hand-roll the same split, as
 * AddToMultiviewSheet / UpdatePromptSheet / WhatsNewSheet do). The content
 * lambda is identical across both containers; callers keep their own inner
 * padding and scrolling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormFactorModal(
    onDismiss: () -> Unit,
    tvWidthFraction: Float = 0.55f,
    tvMaxHeight: Dp = 480.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isTv = rememberIsTvDevice()
    if (isTv) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(tvWidthFraction)
                    .heightIn(max = tvMaxHeight),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    content = content,
                )
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            // Bound the content column to the window height.
            //
            // Without a max, this Column wraps its content, and a caller whose
            // content scrolls (RecordProgramSheet wraps everything in a
            // verticalScroll Column) gets measured with an unbounded height:
            // the scroll viewport becomes exactly as tall as its content, so
            // there is nothing left to scroll, and the sheet grows past the
            // bottom of the screen. Anything below the fold -- Record from
            // Now's Destination toggle and Remove Commercials rows -- was then
            // unreachable, and dragging the sheet up did nothing because the
            // sheet was already fully expanded (skipPartiallyExpanded).
            //
            // Capping at 88% of the window leaves the scrim tap-target at the
            // top and gives the inner scroll a real viewport, so long sheets
            // scroll to their last row. Short sheets are unaffected (they
            // measure below the cap and still wrap).
            val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f
            Column(
                modifier = Modifier.heightIn(max = maxSheetHeight),
                content = content,
            )
        }
    }
}
