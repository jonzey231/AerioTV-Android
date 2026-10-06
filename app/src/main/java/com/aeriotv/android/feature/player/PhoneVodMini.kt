package com.aeriotv.android.feature.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.aeriotv.android.core.pip.findActivity
import com.aeriotv.android.core.pip.isTelevision
import com.aeriotv.android.core.playback.PlaybackTracer
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phone floating mini for ON-DEMAND playback (iPhone parity, Logan 2026-09-14):
 * movies, episodes, DVR recordings (completed and in-progress) and phone
 * catch-up, which all play in [VODPlayerScreen]'s own ExoPlayer rather than the
 * shared live holder.
 *
 * Model: instance hand-off, never a re-tune.
 *  - Minimize (Back or the top-strip swipe, both through the route's
 *    [PhoneVodMiniRoute] Back handler) grabs the screen's ExoPlayer, binds it to
 *    the activity-lifetime [PersistentExoWindow] PlayerView in Mini mode and pops
 *    the route. The screen's onRelease sees the hand-off and skips release().
 *  - Tap on the mini re-pushes the SAME route; VODPlayerScreen adopts the live
 *    instance in its factory (no setMediaItem, no resume seek) and restores the
 *    few state values the chrome needs (tuned URL, catch-up window offset, DVR
 *    migration), so the scrubber, skip buttons, menus and catch-up timeline come
 *    back at the current position.
 *  - X saves the resume position (same save as the player's exit flush), revokes
 *    a native catch-up session, then releases.
 *
 * Phone only: nothing here is reached on a TV ([PhoneVodMiniRoute] and
 * [PhoneVodMiniHost] bail on television, and every other entry point is a no-op
 * while [session] is null). The helpers VODPlayerScreen calls exist so that
 * composable, which sits at ART's verifier register limit, gains no locals.
 */
object PhoneVodMini {

    /** What the mini needs to save progress and to reopen the full player. */
    data class Info(
        /** streamUrl the route handed VODPlayerScreen; the adopt match key. */
        val key: String,
        val videoId: String?,
        val title: String,
        val posterUrl: String?,
        val meta: VodProgressMeta?,
        val isDvr: Boolean,
        /** Route that reopens this playback fullscreen (fromStart / autoResume off). */
        val reopenRoute: String,
        /** Catch-up resume identity (CatchupResumeStore key); blank when not catch-up. */
        val catchupChannelId: String = "",
        val catchupStartMillis: Long = 0L,
        val catchupDurationMs: Long = 0L,
    )

    class Session(val player: ExoPlayer, val info: Info) {
        /** Catch-up programme-relative offset of the tuned window at hand-off. */
        @Volatile var catchupOffsetMs: Long = 0L
        /** Native catch-up session URL to revoke on close; null otherwise. */
        @Volatile var revokeUrl: String? = null
        @Volatile var revoke: ((String) -> Unit)? = null
    }

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** Session being expanded back into its route; cleared on adopt. */
    @Volatile private var expanding: Session? = null

    /** Set when adopt flips the window Mini -> Hidden, so that edge does not
     *  disarm PiP under the freshly mounted fullscreen VOD screen. */
    @Volatile private var hideHandoff = false

    /** Resume-seek suppression for the video an expand re-opens. */
    @Volatile private var skipResumeVideoId: String? = null

    @Volatile private var lastAdopted: ExoPlayer? = null

    /** Installed by [PhoneVodMiniHost]; activity-scoped progress save. */
    @Volatile internal var saver: ((Session) -> Unit)? = null

    /** Installed by [PhoneVodMiniHost]. */
    @Volatile internal var windowState: ExoWindowState? = null

    private val _expandRequests = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Reopen-route events; Navigation's root collects and navigates. */
    val expandRequests: SharedFlow<String> = _expandRequests.asSharedFlow()

    val isActive: Boolean get() = _session.value != null

    val isExpanding: Boolean get() = expanding != null

    /**
     * Hand [player] (the route's current VOD player) to the floating mini.
     * Refuses a player that is not actually playing content (still loading,
     * errored, ended), so the caller falls back to a plain close.
     */
    fun minimize(player: ExoPlayer?, info: Info): Boolean {
        val p = player ?: return false
        val ws = windowState ?: return false
        if (p.playerError != null) return false
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) return false
        // A different on-demand title already in the mini is superseded.
        _session.value?.takeIf { it.player !== p }?.let { close() }
        _session.value = Session(p, info)
        ws.requestMini()
        android.util.Log.i(TAG, "minimized ${info.title} (key=${info.key.take(80)})")
        return true
    }

    /**
     * VODPlayerScreen's AndroidView onRelease. Keeps the instance alive when it
     * was handed to the mini, otherwise does the original release (including
     * the native catch-up revoke).
     */
    @OptIn(UnstableApi::class)
    fun releaseOrRetain(
        player: ExoPlayer?,
        tracer: PlaybackTracer,
        view: PlayerView,
        catchupOffsetMs: Long,
        isNativeCatchup: Boolean,
        currentPlaybackUrl: String,
        onRevokeCatchup: (String) -> Unit,
    ) {
        val s = _session.value
        tracer.tracedPlayer = null
        player?.removeAnalyticsListener(tracer.analyticsListener)
        if (player != null && s != null && s.player === player) {
            android.util.Log.i(TAG, "VOD route released; player retained by the mini")
            s.catchupOffsetMs = catchupOffsetMs
            if (isNativeCatchup) {
                s.revokeUrl = currentPlaybackUrl
                s.revoke = onRevokeCatchup
            }
            // Clears only this view's own surface; the mini's is already bound.
            view.player = null
            return
        }
        android.util.Log.i(TAG, "Releasing VOD ExoPlayer")
        // Task #149: free the native catch-up session's provider slot ahead of
        // its idle TTL. Best-effort, fire-and-forget.
        if (isNativeCatchup) onRevokeCatchup(currentPlaybackUrl)
        player?.release()
        view.player = null
    }

    /** Mini tap: reopen the route, which adopts the playing instance. */
    fun expand() {
        val s = _session.value ?: return
        saver?.invoke(s)
        expanding = s
        skipResumeVideoId = s.info.videoId
        _expandRequests.tryEmit(s.info.reopenRoute)
    }

    /** VODPlayerScreen factory: the playing instance when this mount is the expand target. */
    fun adopt(streamUrl: String): ExoPlayer? {
        val s = expanding ?: return null
        if (s.info.key != streamUrl || _session.value !== s) return null
        expanding = null
        _session.value = null
        lastAdopted = s.player
        val ws = windowState
        if (ws != null && ws.mode.value == ExoWindowState.Mode.Mini) {
            hideHandoff = true
            ws.hide()
        }
        android.util.Log.i(TAG, "expanded ${s.info.title}; route adopted the playing player")
        return s.player
    }

    fun isAdopted(player: ExoPlayer): Boolean = lastAdopted === player

    /** Keep the mini's play / pause state across an expand; fresh players start playing. */
    fun playWhenReadyFor(player: ExoPlayer): Boolean =
        if (lastAdopted === player) player.playWhenReady else true

    private fun expandTarget(streamUrl: String): Session? =
        expanding?.takeIf { it.info.key == streamUrl && _session.value === it }

    fun restoreUrl(streamUrl: String): String =
        expandTarget(streamUrl)?.player?.currentMediaItem?.localConfiguration?.uri?.toString()
            ?: streamUrl

    fun restoreCatchupOffset(streamUrl: String): Long =
        expandTarget(streamUrl)?.catchupOffsetMs ?: 0L

    fun restoreDvrActive(streamUrl: String, isDvr: Boolean): Boolean {
        if (expandTarget(streamUrl) == null) return isDvr
        return isDvr && restoreUrl(streamUrl) == streamUrl
    }

    fun restoreDvrMigrated(streamUrl: String): Boolean {
        val s = expandTarget(streamUrl) ?: return false
        return s.info.isDvr && restoreUrl(streamUrl) != streamUrl
    }

    /** Saved-progress read: an expanded title never seeks back to its saved row. */
    fun resumePosition(videoId: String?, saved: Long): Long {
        if (videoId != null && videoId == skipResumeVideoId) {
            skipResumeVideoId = null
            return -1L
        }
        return saved
    }

    /** Route: resolved URL to reuse for the expand target instead of minting a new session. */
    fun expandUrlFor(videoId: String): String? =
        expanding?.takeIf { it.info.videoId == videoId && _session.value === it }?.info?.key

    /** Mini -> Hidden edge: true when it is an expand hand-off (keep PiP armed, keep the player). */
    fun consumeHideHandoff(): Boolean {
        val handoff = hideHandoff || expanding != null
        hideHandoff = false
        return handoff
    }

    /** A new on-demand route mounting: the previous mini content is replaced. */
    fun closeForNewContent() {
        if (expanding != null) return
        close()
    }

    /** Save the position, revoke a native catch-up session, release. */
    fun close() {
        val s = _session.value ?: return
        _session.value = null
        if (expanding === s) expanding = null
        runCatching { saver?.invoke(s) }
        s.revokeUrl?.let { url -> runCatching { s.revoke?.invoke(url) } }
        android.util.Log.i(TAG, "closed ${s.info.title}")
        s.player.release()
    }

    /** Route disposed while its expand never adopted: put the mini back. */
    internal fun onRouteDisposed(key: String?) {
        val s = expanding ?: return
        if (key == null || s.info.key != key) return
        expanding = null
        windowState?.requestMini()
    }

    private const val TAG = "PhoneVodMini"
}

/** Set by the route helper while its composition handed its player to the mini,
 *  so the orphaned screen's own onClose (still reachable from its old player
 *  listener) can never pop whatever route is on top by then. */
class PhoneVodMiniRouteToken {
    @Volatile var minimized: Boolean = false
}

/**
 * Route-level phone wiring for one on-demand player route. Call it right
 * before VODPlayerScreen; wrap that screen's onClose with [token]. No-op on TV.
 *
 * [info] is null while the route has nothing playable yet (VOD URL resolving).
 */
@Composable
fun PhoneVodMiniRoute(
    info: PhoneVodMini.Info?,
    token: PhoneVodMiniRouteToken,
    onPop: () -> Unit,
) {
    val context = LocalContext.current
    val isPhone = remember(context) { !context.isTelevision() }
    if (!isPhone) return
    val companionHost = remember(context) {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            PlayerScreenEntryPoint::class.java,
        ).companionHost()
    }
    val latestInfo by rememberUpdatedState(info)
    val latestPop by rememberUpdatedState(onPop)

    // Picking new on-demand content while a mini (live or on-demand) is up plays
    // it in the mini (live parity, d4f5eace). Sampled once at mount, before the
    // route's live teardown hides the window.
    val startInMini = remember {
        val ws = PhoneVodMini.windowState
        ws != null && ws.mode.value == ExoWindowState.Mode.Mini && !PhoneVodMini.isExpanding
    }

    // Back (and the top-strip swipe, which dispatches Back) minimizes.
    BackHandler(enabled = info != null) {
        val i = latestInfo
        // VODPlayerScreen registers its current ExoPlayer here while mounted.
        val player = companionHost.externalPlayerProvider?.invoke()
        if (i != null && PhoneVodMini.minimize(player, i)) token.minimized = true
        latestPop()
    }

    LaunchedEffect(startInMini, info?.key) {
        val i = latestInfo ?: return@LaunchedEffect
        if (!startInMini || token.minimized) return@LaunchedEffect
        // Wait for the first READY so the screen's resume seek has its duration,
        // then give that seek a beat to land before handing off.
        var waited = 0L
        while (waited < START_IN_MINI_TIMEOUT_MS) {
            val p = companionHost.externalPlayerProvider?.invoke()
            if (p != null && p.playerError != null) return@LaunchedEffect
            if (p != null && p.playbackState == Player.STATE_READY) {
                delay(700L)
                val ready = companionHost.externalPlayerProvider?.invoke()
                if (PhoneVodMini.minimize(ready, latestInfo ?: i)) {
                    token.minimized = true
                    latestPop()
                }
                return@LaunchedEffect
            }
            delay(150L)
            waited += 150L
        }
    }

    val disposeKey = info?.key
    DisposableEffect(disposeKey) {
        onDispose { PhoneVodMini.onRouteDisposed(disposeKey) }
    }
}

private const val START_IN_MINI_TIMEOUT_MS = 20_000L

/**
 * Phone-only mini bookkeeping, mounted by [PersistentExoWindow]: installs the
 * activity-scoped progress saver, keeps saving every 5 s while an on-demand
 * mini plays, and stops it when the window goes away underneath it (PiP X,
 * cast teardown) or a live player takes the window fullscreen.
 */
@Composable
fun PhoneVodMiniHost(state: ExoWindowState, mode: ExoWindowState.Mode) {
    val context = LocalContext.current
    val watchVm: WatchProgressViewModel = hiltViewModel()
    val vodSession by PhoneVodMini.session.collectAsState()
    DisposableEffect(watchVm, state) {
        PhoneVodMini.windowState = state
        PhoneVodMini.saver = { s -> saveMiniProgress(watchVm, s, context) }
        onDispose {
            PhoneVodMini.saver = null
            // Activity finishing (Exit): never leave a retained decoder behind.
            if (context.findActivity()?.isFinishing == true) PhoneVodMini.close()
        }
    }
    LaunchedEffect(vodSession) {
        val s = vodSession ?: return@LaunchedEffect
        while (true) {
            delay(5_000L)
            if (PhoneVodMini.session.value !== s) return@LaunchedEffect
            val pos = s.player.contentPosition
            val dur = s.player.contentDuration
            val id = s.info.videoId
            if (id.isNullOrBlank() || pos <= 0L || dur <= 0L) continue
            if (s.player.playbackState == Player.STATE_ENDED) continue
            saveVodProgress(watchVm, id, s.info.title, s.info.posterUrl, pos, dur, s.info.meta)
        }
    }
    // A live player went fullscreen (channel tap, reminder, deep link) while the
    // on-demand mini was up: that content is replaced.
    LaunchedEffect(mode, vodSession) {
        if (mode == ExoWindowState.Mode.Fullscreen && vodSession != null) PhoneVodMini.close()
    }
}

private fun saveMiniProgress(
    watchVm: WatchProgressViewModel,
    s: PhoneVodMini.Session,
    context: android.content.Context,
) {
    // Catch-up: the mini's own save (expand, X close) keeps the resume entry
    // current while the full player is not mounted.
    if (s.info.catchupChannelId.isNotBlank() && s.info.catchupDurationMs > 0L &&
        s.player.playbackState != Player.STATE_ENDED
    ) {
        val pos = s.catchupOffsetMs + s.player.contentPosition.coerceAtLeast(0L)
        com.aeriotv.android.core.playback.CatchupResumeStore.save(
            context, s.info.catchupChannelId, s.info.catchupStartMillis, pos, s.info.catchupDurationMs,
        )
    }
    val id = s.info.videoId
    if (id.isNullOrBlank()) return
    val pos = s.player.contentPosition
    val dur = s.player.contentDuration
    if (pos > 0L && dur > 0L && s.player.playbackState != Player.STATE_ENDED) {
        saveVodProgress(watchVm, id, s.info.title, s.info.posterUrl, pos, dur, s.info.meta)
    }
    com.aeriotv.android.core.sync.DriveSyncWorker.enqueueOneShotPush(context.applicationContext)
}

/**
 * VODPlayerScreen's tap layer gestures: the brightness / volume edge slides,
 * the Fit / Fill pinch, and the top-strip swipe-down that minimizes (phone
 * only; [enabled] is already false on TV and in PiP). One call so that
 * composable's modifier chain stays the same size.
 *
 * Order matches the live player: the edge slides claim only inside a narrow
 * band at one bezel, the pinch wants two fingers, and playerTopSwipeDown must
 * stay LAST so it sees the Main pass after the others.
 */
fun Modifier.vodTapLayerGestures(
    settingsVm: com.aeriotv.android.feature.settings.SettingsViewModel,
    enabled: Boolean,
): Modifier = this
    .playerEdgeSlideGesturesFromSettings(settingsVm, enabled = enabled)
    .videoScalePinch(settingsVm, enabled = enabled)
    .playerTopSwipeDown(enabled = enabled)
