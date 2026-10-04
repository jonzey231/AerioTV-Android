package com.aeriotv.android.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.pip.PipState
import com.aeriotv.android.core.pip.findActivity
import com.aeriotv.android.core.playback.AerioExoPlayerHolder
import com.aeriotv.android.feature.miniplayer.MiniPlayerSession
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Phone / tablet in-app mini player (Logan 2026-09-14): a PiP-style floating
 * video window over every tab, the touch counterpart of the TV corner mini.
 * The video is still the ONE activity-lifetime PlayerView in
 * [PersistentExoWindow]; this file only supplies the phone geometry, the
 * window's own touch controls (drag to a corner, tap to expand, X to stop) and
 * the top-strip swipe-down that minimizes the fullscreen player.
 *
 * TV never reaches any of this (PersistentExoWindow keeps its tvOS geometry).
 * Tablets (sw >= 600 dp) share these controls but dock like the iPad corner
 * mini: fixed top-trailing, 400x225 dp, 24 dp in, no drag, and stashed at the
 * trailing edge while Settings is selected.
 */
object PhoneMiniChrome {
    /** Live finger travel (px, downward) of a top-strip minimize drag on the
     *  fullscreen LIVE player; the window follows it. 0 when idle. */
    val dragPx = mutableFloatStateOf(0f)

    /** True while that drag is in progress (geometry snaps instead of springs). */
    val dragging = mutableStateOf(false)

    /** Corner the mini rests in; kept for the session so a re-minimize lands
     *  where the user last left it. Default bottom-trailing. */
    val corner = mutableStateOf(Corner.BottomEnd)

    /** Top edge of MainScaffold's bottom overlay (cast card, companion button,
     *  tab bar) in root px, 0 while the tab shell is not composed. The mini's
     *  bottom corners sit above it so they never cover the bar or the card. */
    val bottomChromeTopPx = mutableFloatStateOf(0f)

    /** Bottom edge of the tablet TOP tab bar in root px, 0 when there is none,
     *  so the top corners never cover it either. */
    val topChromeBottomPx = mutableFloatStateOf(0f)

    enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

    /** Tablet only: the user tapped the stashed sliver on Settings to bring
     *  the mini back out (iPad settingsMiniPeek, HomeView.swift). Reset by
     *  MainScaffold on every tab change. */
    val settingsPeek = mutableStateOf(false)

    /** Tablet only: trailing width (dp) the docked corner mini covers at the
     *  top of the window (inset + mini width), 0 when the mini is not docked
     *  there (hidden, fullscreen, stashed on Settings, phones). The Live TV
     *  header pads its trailing actions by this so search, sort and the
     *  List / Guide toggle slide out from under the mini, as on the iPad. */
    val tabletHeaderReserveDp = mutableFloatStateOf(0f)

    /** The spring the mini frame animates with; the header shift reuses it
     *  so both move together. */
    fun <T> frameSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}

/** iPad corner mini geometry (HomeView.swift: 400x225 pt, 24 pt from the top
 *  and trailing edges), in dp for tablets. */
private val TABLET_MINI_WIDTH = 400.dp
private val TABLET_MINI_INSET = 24.dp

@Composable
private fun isTabletMini(): Boolean =
    androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600

/** True while the tablet mini should sit stashed at the trailing edge. */
@Composable
private fun tabletMiniStashed(tablet: Boolean): Boolean {
    if (!tablet) return false
    val stashed by MiniPlayerChrome.settingsStashed.collectAsStateWithLifecycle()
    val peek by PhoneMiniChrome.settingsPeek
    return stashed && !peek
}

/** Fraction of the player's height, from the top, where a minimize swipe may
 *  start (the close-button row). */
private const val TOP_STRIP_FRACTION = 0.15f

private val MINI_EDGE = 12.dp
private val MINI_GAP = 8.dp
private val MINI_RADIUS = 10.dp

/**
 * Swipe DOWN starting in the top strip of a touch player dispatches a system
 * Back, which minimizes it to the floating mini: the live player, and the phone
 * on-demand player (movies, episodes, recordings, catch-up; PhoneVodMini.kt).
 * Once such a drag passes a (half) touch slop its moves are consumed, which
 * cancels the channel-flip swipe and the tap-to-toggle-chrome layers the
 * modifier is chained after; a drag that starts below the strip is never
 * touched. Append it LAST on the tap layer's modifier chain so it sees the
 * Main pass first.
 *
 * [drivesWindow] (live only) makes the persistent video window follow the
 * finger; on a committed release the Back handler flips the window to Mini and
 * the spring carries the frame from the dragged size into the corner.
 */
fun Modifier.playerTopSwipeDown(
    enabled: Boolean,
    drivesWindow: Boolean = false,
): Modifier = if (!enabled) {
    this
} else {
    this.composed {
        val context = LocalContext.current
        pointerInput(drivesWindow) {
            val claimSlop = viewConfiguration.touchSlop / 2f
            val commitPx = maxOf(size.height * 0.12f, 48.dp.toPx())
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.position.y > size.height * TOP_STRIP_FRACTION) return@awaitEachGesture
                // Always release the window, including when the gesture is
                // cancelled mid-drag (pointerInput restart / dispose).
                try {
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var claimed = false
                    var total = 0f
                    var released = false
                    while (true) {
                        val event = awaitPointerEvent()
                        // A second finger is the Fit / Fill pinch: let it have the gesture.
                        if (event.changes.size > 1) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            released = true
                            break
                        }
                        total = change.position.y - down.position.y
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!claimed && abs(total) > claimSlop) claimed = true
                        if (claimed) {
                            change.consume()
                            if (drivesWindow) {
                                PhoneMiniChrome.dragging.value = true
                                PhoneMiniChrome.dragPx.floatValue = total.coerceAtLeast(0f)
                            }
                        }
                    }
                    val velocity = if (claimed) tracker.calculateVelocity().y else 0f
                    val commit = claimed && released && total > 0f &&
                        (total > commitPx || (velocity > 1500f && total > claimSlop * 4f))
                    if (commit) {
                        (context.findActivity() as? ComponentActivity)
                            ?.onBackPressedDispatcher?.onBackPressed()
                    }
                } finally {
                    PhoneMiniChrome.dragging.value = false
                    PhoneMiniChrome.dragPx.floatValue = 0f
                }
            }
        }
    }
}

/**
 * Phone geometry + touch controls for [PersistentExoWindow]. Returns the
 * container modifier for the current frame, or null when the plain
 * fullscreen / hidden branches apply. Must be called unconditionally on
 * phones (it owns animation state).
 */
@Composable
fun BoxScope.phoneMiniFrameModifier(
    mode: ExoWindowState.Mode,
    inPip: Boolean,
): Modifier? {
    val density = LocalDensity.current
    val container = LocalWindowInfo.current.containerSize
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val fullW = container.width.toFloat()
    val fullH = container.height.toFloat()

    val insets = WindowInsets.statusBars.union(WindowInsets.navigationBars)
        .union(WindowInsets.displayCutout)
    val insetTop = insets.getTop(density).toFloat()
    val insetBottom = insets.getBottom(density).toFloat()
    val insetLeft = insets.getLeft(density, LayoutDirection.Ltr).toFloat()
    val insetRight = insets.getRight(density, LayoutDirection.Ltr).toFloat()
    val edge = with(density) { MINI_EDGE.toPx() }
    val gap = with(density) { MINI_GAP.toPx() }

    // Tablets dock like the iPad corner mini; phones use iPhone PiP
    // proportions (a bit under half the short side). Both 16:9.
    val tablet = isTabletMini()
    val stashed = tabletMiniStashed(tablet)
    val shortSide = with(density) { minOf(fullW, fullH).toDp() }
    val miniWdp = if (tablet) {
        TABLET_MINI_WIDTH.coerceAtMost(shortSide * 0.6f)
    } else {
        (shortSide * 0.46f).coerceIn(160.dp, 320.dp)
    }
    val miniW = with(density) { miniWdp.toPx() }
    val miniH = miniW * 9f / 16f

    val bottomChromeTop by PhoneMiniChrome.bottomChromeTopPx
    val topChromeBottom by PhoneMiniChrome.topChromeBottomPx
    val minY = maxOf(insetTop, topChromeBottom) + gap
    val maxY = (if (bottomChromeTop > 0f) bottomChromeTop else fullH - insetBottom) - gap - miniH
    val minX = insetLeft + edge
    val maxX = fullW - insetRight - edge - miniW

    val storedCorner by PhoneMiniChrome.corner
    val corner = if (tablet) PhoneMiniChrome.Corner.TopEnd else storedCorner
    val dragging by PhoneMiniChrome.dragging
    val dragPx by PhoneMiniChrome.dragPx
    val startSide = corner == PhoneMiniChrome.Corner.TopStart || corner == PhoneMiniChrome.Corner.BottomStart
    val leftSide = startSide != rtl
    val topSide = corner == PhoneMiniChrome.Corner.TopStart || corner == PhoneMiniChrome.Corner.TopEnd
    val tabletInset = with(density) { TABLET_MINI_INSET.toPx() }
    val sliver = with(density) { MiniPlayerChrome.stashSliver.toPx() }
    val cornerX = when {
        // Settings stash (iPad MiniPlayerSettingsStash): slide toward the
        // trailing edge until only the sliver of video stays on screen.
        tablet && stashed -> if (rtl) sliver - miniW else fullW - sliver
        tablet -> if (rtl) insetLeft + tabletInset else fullW - insetRight - tabletInset - miniW
        leftSide -> minX
        else -> maxX
    }
    val cornerY = when {
        // 24 dp below the status bar rather than the physical top, so the
        // window never covers the system status icons.
        tablet -> insetTop + tabletInset
        topSide -> minY
        else -> maxY.coerceAtLeast(minY)
    }

    val miniTarget = mode == ExoWindowState.Mode.Mini && !inPip
    val liveDrag = mode == ExoWindowState.Mode.Fullscreen && dragging
    // Fullscreen drag: shrink toward half size and slide down with the finger.
    val k = if (liveDrag && fullH > 0f) (dragPx / fullH).coerceIn(0f, 1f) else 0f
    val dragW = fullW * (1f - 0.5f * k)
    val dragH = fullH * (1f - 0.5f * k)
    val tx = when { miniTarget -> cornerX; liveDrag -> (fullW - dragW) / 2f; else -> 0f }
    val ty = when { miniTarget -> cornerY; liveDrag -> dragPx; else -> 0f }
    val tw = when { miniTarget -> miniW; liveDrag -> dragW; else -> fullW }
    val th = when { miniTarget -> miniH; liveDrag -> dragH; else -> fullH }

    val ax = remember { Animatable(0f) }
    val ay = remember { Animatable(0f) }
    val aw = remember { Animatable(fullW) }
    val ah = remember { Animatable(fullH) }
    var windowDragging by remember { mutableStateOf(false) }
    var settleSerial by remember { mutableIntStateOf(0) }
    val snapNow = liveDrag || inPip || mode == ExoWindowState.Mode.Hidden
    // A rotation / fold changes the full-size target itself: snap rather
    // than spring so the fullscreen frame never shows rounded corners.
    var lastContainer by remember { mutableStateOf(container) }
    LaunchedEffect(tx, ty, tw, th, snapNow, settleSerial) {
        if (windowDragging) return@LaunchedEffect
        val resized = container != lastContainer
        lastContainer = container
        val spec = PhoneMiniChrome.frameSpring<Float>()
        if (snapNow || resized) {
            ax.snapTo(tx); ay.snapTo(ty); aw.snapTo(tw); ah.snapTo(th)
        } else {
            launch { ax.animateTo(tx, spec) }
            launch { ay.animateTo(ty, spec) }
            launch { aw.animateTo(tw, spec) }
            launch { ah.animateTo(th, spec) }
        }
    }

    // Keep system PiP armed while only the mini is playing: the fullscreen
    // player clears videoPlaybackActive when its route disposes, which happens
    // AFTER the flip to Mini, so re-assert it for as long as the mini is up.
    // Cleared only on the Mini -> Hidden edge, never on a plain Hidden, so a
    // VOD screen's own flag is left alone.
    val miniUp = mode == ExoWindowState.Mode.Mini
    LaunchedEffect(miniUp) {
        if (miniUp) {
            PipState.videoPlaybackActive.collect { if (!it) PipState.videoPlaybackActive.value = true }
        }
    }
    var lastMode by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        if (lastMode == ExoWindowState.Mode.Mini && mode == ExoWindowState.Mode.Hidden) {
            // An on-demand expand hides the window under the fullscreen VOD
            // screen, which owns the flag now; leave it armed.
            if (!PhoneVodMini.consumeHideHandoff()) {
                PipState.videoPlaybackActive.value = false
                // The window went away under an on-demand mini (PiP X, a
                // teardown): stop it, saving the position.
                PhoneVodMini.close()
            }
        }
        lastMode = mode
    }

    // Tablet: tell the Live TV header how much trailing width the docked
    // mini covers so its action circles shift left of it.
    val headerReserve = if (tablet && miniTarget && !stashed) {
        (TABLET_MINI_INSET + miniWdp).value
    } else 0f
    androidx.compose.runtime.SideEffect {
        if (PhoneMiniChrome.tabletHeaderReserveDp.floatValue != headerReserve) {
            PhoneMiniChrome.tabletHeaderReserveDp.floatValue = headerReserve
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { PhoneMiniChrome.tabletHeaderReserveDp.floatValue = 0f }
    }

    if (mode == ExoWindowState.Mode.Hidden) return null
    // In PiP the activity window IS the picture: fill it, above every route.
    if (inPip) {
        return if (miniUp) {
            Modifier.zIndex(2f).fillMaxSize().background(Color.Black)
        } else null
    }
    val animating = abs(aw.value - fullW) > 0.5f || abs(ay.value) > 0.5f || abs(ax.value) > 0.5f
    if (!miniUp && !liveDrag && !animating) return null
    val radius = if (miniUp || animating) MINI_RADIUS else 0.dp
    return Modifier
        .zIndex(1f)
        .align(Alignment.TopStart)
        .offset { IntOffset(ax.value.toInt(), ay.value.toInt()) }
        .size(
            width = with(density) { aw.value.toDp() },
            height = with(density) { ah.value.toDp() },
        )
        .shadow(elevation = 10.dp, shape = RoundedCornerShape(radius))
        .clip(RoundedCornerShape(radius))
        .background(Color.Black)
        .then(
            // Tablets stay docked top-trailing like the iPad: no drag.
            if (miniUp && !tablet) {
                Modifier.phoneMiniDrag(
                    ax = ax,
                    ay = ay,
                    onStart = { windowDragging = true },
                    onEnd = {
                        val cx = ax.value + miniW / 2f
                        val cy = ay.value + miniH / 2f
                        val left = cx < fullW / 2f
                        val top = cy < fullH / 2f
                        val start = left != rtl
                        PhoneMiniChrome.corner.value = when {
                            top && start -> PhoneMiniChrome.Corner.TopStart
                            top -> PhoneMiniChrome.Corner.TopEnd
                            start -> PhoneMiniChrome.Corner.BottomStart
                            else -> PhoneMiniChrome.Corner.BottomEnd
                        }
                        windowDragging = false
                        settleSerial++
                    },
                )
            } else Modifier,
        )
}

@Composable
private fun Modifier.phoneMiniDrag(
    ax: Animatable<Float, AnimationVector1D>,
    ay: Animatable<Float, AnimationVector1D>,
    onStart: () -> Unit,
    onEnd: () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    return this.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { onStart() },
            onDragEnd = { onEnd() },
            onDragCancel = { onEnd() },
            onDrag = { change, delta ->
                change.consume()
                scope.launch {
                    ax.snapTo(ax.value + delta.x)
                    ay.snapTo(ay.value + delta.y)
                }
            },
        )
    }
}

/**
 * Casting / companion control (GH #33 rule, as the old audio chip): the phone
 * is a remote then, so a local mini must never keep playing next to the cast
 * card. The moment a session connects while the mini is up, stop local
 * playback with the X teardown; the cast card is the one control surface.
 */
@Composable
fun PhoneMiniCastGuard(
    holder: AerioExoPlayerHolder,
    state: ExoWindowState,
    session: MiniPlayerSession,
) {
    val context = LocalContext.current
    val entry = remember(context) {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            com.aeriotv.android.feature.main.MainScaffoldEntryPoint::class.java,
        )
    }
    val castState by entry.castSender().state
        .collectAsStateWithLifecycle()
    val companionConn by entry.companionRemote().connection
        .collectAsStateWithLifecycle()
    val mode by state.mode.collectAsStateWithLifecycle()
    val remoteActive = castState is com.aeriotv.android.core.cast.AerioCastSender.State.Connected ||
        companionConn is com.aeriotv.android.core.cast.companion.CompanionRemoteController.Conn.Connected
    LaunchedEffect(remoteActive, mode) {
        if (remoteActive && mode == ExoWindowState.Mode.Mini) {
            PhoneVodMini.close()
            session.dismiss()
            state.hide()
            holder.stop()
            com.aeriotv.android.core.playback.AerioMediaPlaybackService.stop(context)
        }
    }
}

/**
 * Tap-to-expand and the stop X, drawn inside the mini frame over the video.
 * Only composed while the phone mini is up and not in system PiP.
 */
@Composable
fun BoxScope.PhoneMiniControls(
    holder: AerioExoPlayerHolder,
    state: ExoWindowState,
    session: MiniPlayerSession,
) {
    val context = LocalContext.current
    val stashed = tabletMiniStashed(isTabletMini())
    val currentStashed by androidx.compose.runtime.rememberUpdatedState(stashed)
    Box(
        Modifier
            .matchParentSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    if (currentStashed) {
                        // iPad parity: a tap on the stashed sliver slides the
                        // mini back out and stays on Settings; the next tap
                        // expands it.
                        PhoneMiniChrome.settingsPeek.value = true
                    } else if (PhoneVodMini.isActive) {
                        // On-demand: Navigation's expandRequests collector
                        // re-pushes the player route, which adopts the instance.
                        PhoneVodMini.expand()
                    } else {
                        // Navigation's resumeRequests collector re-pushes PLAYER for
                        // the session channel; its mount flips the window Fullscreen,
                        // so the frame springs back out of the corner.
                        session.requestResume()
                    }
                })
            },
    )
    // The X stays off while stashed so a tap on the sliver can only peek.
    if (stashed) return
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    // On-demand: save the resume position and release first,
                    // exactly what the player's own close does.
                    PhoneVodMini.close()
                    // Same teardown as the fullscreen chrome's X.
                    session.dismiss()
                    state.hide()
                    holder.stop()
                    com.aeriotv.android.core.playback.AerioMediaPlaybackService.stop(context)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Close",
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}
