package com.aeriotv.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.aeriotv.android.ui.scale.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aeriotv.android.ui.scale.Dialog
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
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
    /**
     * Optional width cap for the TOUCH bottom sheet (plan B5).
     *
     * A ModalBottomSheet spans the full window, which on a 1280dp tablet
     * stretches a list of short group names across the whole display. Callers
     * that want the sheet to read as a panel rather than a full-bleed drawer
     * pass a cap here; the sheet then centres within the window.
     *
     * Opt-in per caller rather than a blanket default: every modal in the app
     * shares this container, and silently narrowing all of them (the player
     * sheets, Record from Now, What's New) would be a much wider visual change
     * than the plan asked for. Null keeps today's full-width behaviour.
     *
     * Ignored on TV, which uses the centered dialog and already sizes itself
     * with [tvWidthFraction].
     */
    sheetMaxWidth: Dp? = null,
    /**
     * Touch sheets only: false removes the sheet's own drag and nested
     * scroll handling, so a scrolling body that reaches its end stretches
     * (Compose overscroll) instead of handing the leftover drag to the
     * sheet, which moved the sheet and snapped it back (Record from Now,
     * Logan 2026-10-02). Cancel and the scrim still dismiss.
     */
    sheetGesturesEnabled: Boolean = true,
    /**
     * Touch sheets only (Logan 2026-09-14): open at the partial height and let
     * the user drag the sheet to full screen. The content column is then given
     * a FIXED height rather than a max, so a list inside it scrolls and the
     * expanded anchor never re-measures with the content (45483dd5: the What's
     * New sheet bounced at the end of its list for exactly that reason).
     */
    sheetExpandable: Boolean = false,
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
                color = com.aeriotv.android.ui.tv.TvChrome.dialogSurface(),
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    content = content,
                )
            }
        }
    } else {
        // Expandable sheets opt out of the sheet's own TOP content inset.
        //
        // ModalBottomSheet pads its content column with
        // `contentWindowInsets` (safeDrawing top + bottom) while the sheet
        // itself consumes `WindowInsets(top = sheetState.offset)`. The top
        // padding is therefore (statusBar - currentOffset): it is ZERO while
        // the sheet sits low and GROWS to the full status bar height as the
        // sheet is dragged to the top. Content height then depends on the
        // sheet's own offset, the Expanded anchor is recomputed from that
        // height (fullHeight - sheetSize.height), the sheet chases the moving
        // anchor, and the sheet bounces up and down -- the same class of loop
        // as 45483dd5. Keeping only the BOTTOM inset makes the measured
        // height independent of the offset, so both anchors are stable.
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !sheetExpandable),
            sheetGesturesEnabled = sheetGesturesEnabled,
            containerColor = MaterialTheme.colorScheme.background,
            // Bottom inset only for BOTH paths. With the default insets the
            // non-expandable sheet got the same offset-dependent top padding:
            // once its content was tall enough to sit near the status bar
            // (the compact Record from Now sheet, Nothing Phone recording
            // 2026-10-02 15:23) every scroll re-measured the content and the
            // sheet jumped between two heights.
            contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
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
            // Fixed, offset-independent height for the expandable sheet:
            // the window height minus the top inset we no longer let the
            // sheet pad for us, minus the drag handle block (4dp handle +
            // 22dp padding above and below) and the bottom safe-drawing
            // inset the sheet still applies. The sheet then measures exactly
            // (window - status bar) tall no matter where it sits, so the
            // Expanded anchor is a constant and the drag settles.
            val density = LocalDensity.current
            val windowHeightPx = LocalWindowInfo.current.containerSize.height
            val topInsetPx = WindowInsets.safeDrawing.getTop(density)
            val bottomInsetPx = WindowInsets.safeDrawing.getBottom(density)
            val expandedContentHeight = with(density) {
                (windowHeightPx - topInsetPx - bottomInsetPx - DragHandleBlockHeight.roundToPx())
                    .coerceAtLeast(0)
                    .toDp()
            }
            // `sheetMaxWidth` narrows and centres the column inside the
            // full-width sheet. Done here rather than on the ModalBottomSheet
            // itself so the scrim, drag handle and dismiss gesture keep
            // spanning the window - only the CONTENT is bounded, which is what
            // makes it read as a panel without breaking swipe-to-dismiss.
            Column(
                modifier = Modifier
                    .then(
                        if (sheetMaxWidth != null) {
                            Modifier
                                .fillMaxWidth()
                                .widthIn(max = sheetMaxWidth)
                                .align(Alignment.CenterHorizontally)
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (sheetExpandable) Modifier.height(expandedContentHeight)
                        else Modifier.heightIn(max = maxSheetHeight),
                    ),
                content = content,
            )
        }
    }
}

/** Height of the ModalBottomSheet drag handle block: 4dp handle + 22dp padding each side. */
private val DragHandleBlockHeight = 48.dp
