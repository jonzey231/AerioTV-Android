package com.aeriotv.android.core.cast.multiview

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import com.aeriotv.android.core.cast.AerioCastReceiverController
import com.aeriotv.android.core.cast.AerioCastSender
import com.aeriotv.android.core.cast.hlsproxy.CastHlsProxySession
import com.aeriotv.android.feature.multiview.MultiviewLayoutMode
import com.aeriotv.android.feature.multiview.MultiviewTile
import com.aeriotv.android.feature.multiview.TileKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Play on <device>" for a staged Multiview when the Cast receiver is the WEB
 * receiver (Logan 2026-10-06): owns the [MultiviewCompositor], its local TS
 * pipe into the cast proxy, the sender load, audio focus, the phone preview,
 * and the resource rules (720p30, 4 tiles, stop when the phone cannot keep
 * up or runs SEVERE hot). The Cast Connect path to the AerioTV Android TV
 * app (sendMultiviewOpen) is separate and unchanged.
 */
@Singleton
class MultiviewCastController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sender: AerioCastSender,
    private val hlsProxy: CastHlsProxySession,
    private val timeshift: com.aeriotv.android.core.timeshift.TimeshiftController,
    private val prefs: com.aeriotv.android.core.preferences.AppPreferences,
) {
    companion object {
        private const val TAG = "AerioCast"
        /** Cast content id of the composite; the cast card keys on it. */
        const val MEDIA_ID = "aerio-multiview-composite"
        const val TITLE = "Multiview"
        const val CANNOT_KEEP_UP = "Multiview casting stopped: the phone could not keep up"
        const val CAST_LIMIT_NOTE = "Up to 4 channels can be cast"
        /** A web receiver further than this behind its live seek end is
         *  seeked there. Apple a4bd790 round 8: the receiver sat 3.7 to 4 s
         *  behind and the former 6 s threshold never fired, so it is 2 s. */
        const val NUDGE_THRESHOLD_MS = 2_000L
        /** The composite playlists' HOLD-BACK: three targets of the composite
         *  profile's TARGETDURATION of 1. */
        const val COMPOSITE_HOLD_BACK_S = 3.0
        /** Where the nudge result is read, after the nudge's re-buffer. */
        const val NUDGE_RESULT_DELAY_MS = 6_000L
        /** Where the nudge aims: just inside the live seek end. */
        const val NUDGE_MARGIN_MS = 500L
    }

    /** The composite on the receiver right now; null when none. */
    data class Session(
        val tiles: List<MultiviewTile>,
        val focused: Int,
        /** The composite's grid layout (the cast sheet's Layout row); kept
         *  across a tile add or remove, seeded from Settings > Player >
         *  Multiview at a fresh start. */
        val layoutMode: MultiviewLayoutMode = MultiviewLayoutMode.Auto,
    ) {
        /** The one channel a session dropped to a single tile shows full
         *  frame (round 9): the card and sheet read it like a normal
         *  single-channel cast while the stream stays the composite. Null
         *  with 2 or more tiles. */
        val singleChannelName: String? get() = tiles.singleOrNull()?.displayName
        /** Upstream host of the tiles (the Dispatcharr host) for Stream Info. */
        val sourceHost: String? get() = MultiviewCompositeLayout.sourceHost(tiles.map { it.resolvedUrl })
    }

    /** The user's Multiview look (Settings > Player > Multiview), applied
     *  live to the composite and read by the preview's hit testing. */
    val style: StateFlow<CompositeStyle> = combine(
        combine(prefs.multiviewAudioFocusStyle, prefs.multiviewTilePadding, prefs.multiviewTileCornersRounded) { f, p, r -> Triple(f, p, r) },
        combine(prefs.multiviewShowLogos, prefs.multiviewLogoPosition, prefs.multiviewLogoSize) { s, pos, size -> Triple(s, pos, size) },
    ) { a, b ->
        CompositeStyle(
            focusStyle = a.first, padding = a.second, rounded = a.third,
            showLogos = b.first, logoPosition = b.second, logoSizePercent = b.third,
        )
    }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, CompositeStyle())

    /** Settings > Player > Multiview layout, the initial composite layout. */
    private val prefLayoutMode: StateFlow<String> = prefs.multiviewLayoutMode
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, "auto")

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var compositor: MultiviewCompositor? = null
    private var pipe: LocalTsPipe? = null
    private var watchJob: Job? = null
    private var styleJob: Job? = null
    private var thermalListener: Any? = null
    /** Position -> compositor tile index; swapped from the preview. */
    private var order: List<Int> = emptyList()
    /** Accent ARGB for the focused tile's border, set by the UI. */
    @Volatile var focusArgb: Int = 0xFFFFFFFF.toInt()
        set(value) { field = value; compositor?.focusArgb = value }
    private var keepaliveOn = false
    /** Headers of the running composite, for a restart that adds or drops a tile. */
    private var lastHeaders: Map<String, String> = emptyMap()
    private val lifecycleObserver = object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
            compositor?.backgrounded = true
            setKeepalive(true)
        }
        override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
            compositor?.backgrounded = false
            setKeepalive(false)
        }
    }

    /** Live tiles that may be composited (the first [MultiviewCompositeLayout.MAX_TILES]). */
    fun castableTiles(tiles: List<MultiviewTile>): List<MultiviewTile> = tiles.filter { it.kind == TileKind.Live }

    /** Main thread. Starts the composite and the cast load. */
    fun start(tiles: List<MultiviewTile>, headers: Map<String, String>, focus: Int): Boolean {
        val live = castableTiles(tiles)
        if (!MultiviewCompositeLayout.canCast(live.size)) {
            Log.w(TAG, "[MV-CAST] composite refused: ${live.size} live tiles")
            return false
        }
        val keptLayout = _session.value?.layoutMode
        stop("restart")
        lastHeaders = headers
        val layout = MultiviewCompositeLayout.effectiveMode(
            keptLayout ?: MultiviewLayoutMode.from(prefLayoutMode.value).let {
                if (it == MultiviewLayoutMode.Spotlight) MultiviewLayoutMode.Auto else it
            },
            live.size,
        )
        val focused = live.indexOfFirst { it.id == tiles.getOrNull(focus)?.id }.coerceAtLeast(0)
        // A channel kept live in the background holds a server slot the tile
        // needs (same rule as the local Multiview tile).
        live.forEach { runCatching { timeshift.stopRetainedChannel(it.id) } }
        val p = LocalTsPipe()
        val c = MultiviewCompositor(
            context, live, headers, focused,
            output = p::write,
            onFatal = { reason -> onFatal(reason) },
        )
        c.style = style.value
        c.layoutMode = layout
        if (!c.start()) {
            p.close()
            _session.value = null
            sender.notifyCastProblem("Can't cast Multiview right now")
            return false
        }
        pipe = p
        compositor = c
        c.focusArgb = focusArgb
        order = live.indices.toList()
        _session.value = Session(live, focused, layout)
        styleJob = scope.launch {
            style.collect { st ->
                if (compositor === c) {
                    c.style = st
                    Log.i(TAG, "[MV-CAST] composite style $st")
                }
            }
        }
        startThermalWatch()
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        val names = live.joinToString(", ") { it.displayName }
        sender.castComposite(
            base = AerioCastSender.Content(
                mediaId = MEDIA_ID,
                kind = AerioCastReceiverController.Kind.LIVE,
                title = TITLE,
                subtitle = names,
                artUri = null,
            ),
            startProxy = { hlsProxy.startLocalChannel(p, "multiview") },
            failureMessage = "Can't cast Multiview right now",
            onFailed = { stop("proxy failed") },
        )
        // Round 7 (Apple f33f645): the first PLAYING tick after this load
        // runs a one-time catch-up (the receiver's startup buffering is
        // where it fell about 9 s behind and never moved forward again).
        startupCatchUpPending = true
        sender.receiverTickListener = { j -> noteReceiverTick(j) }
        // The composite ends with the cast content: Stop Casting, a channel
        // cast over it, a session end or a disconnect.
        watchJob = scope.launch {
            var seen = false
            combine(sender.content, sender.state) { content, state -> content to state }.collect { (content, state) ->
                val ours = content?.mediaId == MEDIA_ID
                if (ours) seen = true
                if ((seen && !ours) || state !is AerioCastSender.State.Connected) {
                    stop(
                        when {
                            state !is AerioCastSender.State.Connected -> "session ended"
                            content == null -> "stopped"
                            else -> "replaced by ${content.title}"
                        },
                    )
                }
            }
        }
        return true
    }

    /**
     * Live TV "Add to Multiview" while the composite runs (Apple e1dcbd2
     * parity): the channel joins the running pile, or leaves it when it is
     * already a tile, instead of a fresh staged pile replacing it. The
     * composite restarts with the new tile set (same cast session, audio
     * focus kept on its tile). Returns false when no composite is running.
     */
    fun toggleTile(tile: MultiviewTile): Boolean {
        val s = _session.value ?: return false
        val isTile = s.tiles.any { it.id == tile.id }
        val next = if (isTile) s.tiles.filterNot { it.id == tile.id } else s.tiles + tile
        if (!isTile && next.size > MultiviewCompositeLayout.MAX_TILES) {
            Log.i(TAG, "[MV-CAST] composite add ${tile.displayName} refused: ${s.tiles.size} tiles")
            sender.notifyCastProblem(CAST_LIMIT_NOTE)
            return true
        }
        if (isTile && next.size == 1) {
            dropToSingleTile(s, next[0])
            return true
        }
        if (next.isEmpty()) {
            Log.i(TAG, "[MV-CAST] composite: no tiles left, stopping")
            stop("no tiles left")
            if (sender.content.value?.mediaId == MEDIA_ID) sender.stopPlayback()
            return true
        }
        if (!MultiviewCompositeLayout.canCast(next.size)) {
            Log.i(TAG, "[MV-CAST] composite remove ${tile.displayName} refused: ${next.size} tile(s) would be left")
            return true
        }
        val focusedId = s.tiles.getOrNull(s.focused)?.id
        val focus = next.indexOfFirst { it.id == focusedId }.coerceAtLeast(0)
        Log.i(TAG, "[MV-CAST] composite ${if (isTile) "remove" else "add"} ${tile.displayName}: ${s.tiles.size} -> ${next.size} tiles")
        start(next, lastHeaders, focus)
        return true
    }

    /**
     * One tile left (Apple 6136daf round 9, Logan: removing down to one tile
     * must be seamless). Round 7's hand-off to a normal single-channel cast
     * reloaded the receiver and left the TV dark. The composite now keeps
     * running (same encoder, pipe, proxy and receiver load) and draws the
     * remaining tile full frame at 1280x720, so the receiver never reloads.
     * The card and sheet read it as that channel ([Session.singleChannelName]).
     * Add to Multiview returns to the grid; removing the last tile stops.
     */
    private fun dropToSingleTile(s: Session, tile: MultiviewTile) {
        val c = compositor ?: return
        val pos = s.tiles.indexOfFirst { it.id == tile.id }
        val tileIndex = order.getOrElse(pos) { pos }
        Log.i(TAG, "[MV-CAST] composite remove: ${s.tiles.size} -> 1 tile (${tile.displayName} full frame, same stream, no receiver reload)")
        c.dropToSingle(tileIndex)
        order = listOf(tileIndex)
        _session.value = s.copy(tiles = listOf(tile), focused = 0)
    }

    /** Tap on a tile in the phone preview (a position): audio and
     *  highlight follow. */
    fun setFocus(index: Int, tapNanos: Long = System.nanoTime()) {
        val s = _session.value ?: return
        if (index !in s.tiles.indices || index == s.focused) return
        compositor?.setFocus(order.getOrElse(index) { index }, tapNanos)
        _session.value = s.copy(focused = index)
        compositeFocusChanged(s.tiles[index].displayName)
    }

    private fun compositeFocusChanged(tileName: String) = compositeCatchUp("focus $tileName")

    /** Set at each composite load; the first PLAYING tick clears it and runs
     *  the startup catch-up. */
    private var startupCatchUpPending = false

    /** Receiver status tick (debug namespace, main thread). */
    private fun noteReceiverTick(j: org.json.JSONObject) {
        if (!startupCatchUpPending || compositor == null) return
        if (sender.content.value?.mediaId != MEDIA_ID) return
        if (j.optString("state") != "PLAYING") return
        if (j.optDouble("rate", 0.0) != 1.0) return
        if (j.optInt("elPaused", 0) != 0) return
        startupCatchUpPending = false
        scope.launch {
            // Let the SDK's media status catch up with the play.
            kotlinx.coroutines.delay(1_000L)
            compositeCatchUp("startup")
        }
    }

    /**
     * Composite catch-up (Apple f33f645 round 7): a focus or layout change is
     * on the composite within a frame, but the TV shows it only when the
     * receiver's playhead reaches it, and the receiver page never moves
     * forward on its own. The lag is judged from the proxy itself: the
     * newest published composite segment's end minus the composite
     * HOLD-BACK, the point the receiver's player clamps to. The Cast SDK's
     * cached live seekable range was stale on Apple (seekable end 7.363 at
     * t=30.4 and t=58.0), so it is logged only. A web receiver more than
     * [NUDGE_THRESHOLD_MS] behind is seeked to just inside the seek end, and
     * a result line follows [NUDGE_RESULT_DELAY_MS] later.
     */
    private fun compositeCatchUp(reason: String) {
        if (compositor == null) return
        val snap = sender.receiverLiveSnapshot() ?: return
        val target = sender.receiverTarget.value
        val pos = snap.positionMs / 1000.0
        val cachedEnd = snap.seekableEndMs?.let { it / 1000.0 }
        // Only stated when the receiver plays the proxy's current generation.
        val seekEnd = hlsProxy.liveEdgeEstimate()?.let { e ->
            if (e.first == hlsProxy.loadedGeneration) e.second - e.third - COMPOSITE_HOLD_BACK_S else null
        }
        val behind = seekEnd?.let { it - pos }
        Log.i(
            TAG,
            "[Cast] composite $reason: receiver t=${fmt3(pos)} live seek end=${seekEnd?.let { fmt3(it) } ?: "n/a"} " +
                "(newest segment end - HOLD-BACK $COMPOSITE_HOLD_BACK_S s; SDK cached ${cachedEnd?.let { fmt3(it) } ?: "none"}) " +
                "behind=${behind?.let { String.format(java.util.Locale.US, "%.1f s", it) } ?: "n/a"}; ${lagLine(pos)}",
        )
        if (target != AerioCastSender.ReceiverTarget.WEB_RECEIVER || seekEnd == null || behind == null ||
            !seekEnd.isFinite() || behind * 1000 <= NUDGE_THRESHOLD_MS
        ) return
        val aim = maxOf(pos, seekEnd - NUDGE_MARGIN_MS / 1000.0)
        Log.i(
            TAG,
            String.format(
                java.util.Locale.US,
                "[Cast] composite nudge (%s): seek t=%.3f -> %.3f (live seek end %.3f, %.1f s behind > %.1f s)",
                reason, pos, aim, seekEnd, behind, NUDGE_THRESHOLD_MS / 1000.0,
            ),
        )
        sender.seekToStreamPosition((aim * 1000).toLong())
        nudgeCheck?.cancel()
        nudgeCheck = scope.launch {
            kotlinx.coroutines.delay(NUDGE_RESULT_DELAY_MS)
            if (compositor == null) return@launch
            val after = sender.receiverLiveSnapshot() ?: return@launch
            val p = after.positionMs / 1000.0
            Log.i(
                TAG,
                "[Cast] composite nudge result ($reason): receiver t=${fmt3(p)} playerState=${after.playerState} " +
                    "(moved ${String.format(java.util.Locale.US, "%+.1f", p - pos)} s in ${NUDGE_RESULT_DELAY_MS / 1000} s wall); ${lagLine(p)}",
            )
        }
    }

    private var nudgeCheck: Job? = null

    private fun fmt3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)

    /** "lag behind the composite live point" for a receiver position, or
     *  why it cannot be stated (the receiver's t is on the media timeline of
     *  the generation it was loaded on). */
    private fun lagLine(positionS: Double): String {
        val edge = hlsProxy.liveEdgeEstimate() ?: return "composite live point unknown"
        val loaded = hlsProxy.loadedGeneration
        if (edge.first != loaded) {
            return "composite live point t=${fmt3(edge.second)} on gen ${edge.first}, receiver loaded gen $loaded: lag n/a"
        }
        return String.format(
            java.util.Locale.US,
            "composite live point t=%.3f (last cut %.1f s ago): receiver lag %.1f s",
            edge.second, edge.third, edge.second - positionS,
        )
    }

    /** Cast sheet Layout row: the composite re-lays out on its next frame
     *  (encoder, players and the cast session keep running) and the preview
     *  follows. Kept for this composite session only. */
    fun setLayoutMode(mode: MultiviewLayoutMode) {
        val s = _session.value ?: return
        val m = MultiviewCompositeLayout.effectiveMode(mode, s.tiles.size)
        if (m == s.layoutMode) return
        compositor?.layoutMode = m
        Log.i(TAG, "[MV-CAST] composite layout ${s.layoutMode.displayName} -> ${m.displayName}")
        _session.value = s.copy(layoutMode = m)
        // The new layout reaches the TV only when the receiver's playhead
        // does, so a receiver far behind is caught up here too.
        compositeCatchUp("layout ${m.displayName}")
    }

    /** Preview tile menu Remove from Multiview: the tile at position
     *  [index] leaves the running pile (the composite restarts with the
     *  rest, same cast session). With one tile left the composite keeps
     *  running with that tile full frame (no receiver reload). */
    fun removeAt(index: Int): Boolean {
        val tile = _session.value?.tiles?.getOrNull(index) ?: return false
        return toggleTile(tile)
    }

    /** Long-press-drag in the preview: the tiles at positions [a] and [b]
     *  trade cells. The compositor re-lays out on its next frame; the
     *  encoder, the players and the cast session keep running. */
    fun swap(a: Int, b: Int) {
        val s = _session.value ?: return
        if (a == b || a !in s.tiles.indices || b !in s.tiles.indices) return
        order = MultiviewCompositeLayout.swapOrder(order, a, b)
        compositor?.setSlots(MultiviewCompositeLayout.slotsFor(order))
        val tiles = s.tiles.toMutableList().also { it[a] = s.tiles[b]; it[b] = s.tiles[a] }
        val focused = when (s.focused) { a -> b; b -> a; else -> s.focused }
        Log.i(TAG, "[MV-CAST] composite swap ${s.tiles[a].displayName} <-> ${s.tiles[b].displayName}")
        _session.value = s.copy(tiles = tiles, focused = focused)
    }

    /** App to the background (or back) during a composite: the cast proxy's
     *  foreground service (partial wake lock + Wi-Fi lock) keeps the process,
     *  the encoder thread, the tile players and the proxy running. Started
     *  again here in case it is not up yet. Thermal and encoder-behind stops
     *  still apply. */
    private fun setKeepalive(on: Boolean) {
        if (compositor == null || on == keepaliveOn) return
        keepaliveOn = on
        if (on && !com.aeriotv.android.core.cast.hlsproxy.CastHlsProxyService.running) {
            com.aeriotv.android.core.cast.hlsproxy.CastHlsProxyService.start(context)
        }
        Log.i(
            TAG,
            "[MV-BG] background keepalive ${if (on) "on" else "off"} " +
                "fgs=${if (com.aeriotv.android.core.cast.hlsproxy.CastHlsProxyService.running) "running" else "starting"}",
        )
    }

    fun attachPreview(surface: Surface) { compositor?.attachPreview(surface) }
    fun detachPreview(surface: Surface) { compositor?.detachPreview(surface) }

    /** Main thread. Idempotent. */
    fun stop(reason: String) {
        val c = compositor ?: return
        compositor = null
        watchJob?.cancel()
        watchJob = null
        styleJob?.cancel()
        styleJob = null
        stopThermalWatch()
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        if (keepaliveOn) {
            keepaliveOn = false
            Log.i(TAG, "[MV-CAST] background keepalive off reason=$reason")
        }
        order = emptyList()
        startupCatchUpPending = false
        nudgeCheck?.cancel()
        nudgeCheck = null
        sender.receiverTickListener = null
        c.release(reason)
        pipe?.close()
        pipe = null
        // A restart keeps the session so observers (the Live TV pile mirror)
        // never see the composite end; start() sets the new one.
        if (reason != "restart") _session.value = null
    }

    private fun onFatal(reason: String) {
        if (compositor == null) return
        stop(reason)
        sender.notifyCastProblem(CANNOT_KEEP_UP)
        // Back to the idle card, session kept, exactly as Stop Casting.
        if (sender.content.value?.mediaId == MEDIA_ID) sender.stopPlayback()
    }

    private fun startThermalWatch() {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        Log.i(TAG, "[MV-CAST] composite thermal at start=${pm.currentThermalStatus}")
        if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            scope.launch { onFatal("thermal") }
            return
        }
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            Log.i(TAG, "[MV-CAST] composite thermal status=$status")
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) onFatal("thermal")
        }
        pm.addThermalStatusListener(context.mainExecutor, l)
        thermalListener = l
    }

    private fun stopThermalWatch() {
        if (Build.VERSION.SDK_INT < 29) return
        val l = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
        thermalListener = null
        runCatching { context.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(l) }
    }
}
