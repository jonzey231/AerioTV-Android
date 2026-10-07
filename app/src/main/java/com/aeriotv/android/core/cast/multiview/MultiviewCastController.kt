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
        /** A web receiver further than this behind its live seekable end is
         *  seeked there on a composite focus change. */
        const val NUDGE_THRESHOLD_MS = 2_000L
        /** Where the nudge aims: just inside the live seekable end. */
        const val NUDGE_MARGIN_MS = 250L
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
        val inPile = s.tiles.any { it.id == tile.id }
        val next = if (inPile) s.tiles.filterNot { it.id == tile.id } else s.tiles + tile
        if (!inPile && next.size > MultiviewCompositeLayout.MAX_TILES) {
            Log.i(TAG, "[MV-CAST] composite add ${tile.displayName} refused: ${s.tiles.size} tiles")
            sender.notifyCastProblem(CAST_LIMIT_NOTE)
            return true
        }
        if (!MultiviewCompositeLayout.canCast(next.size)) {
            Log.i(TAG, "[MV-CAST] composite remove ${tile.displayName} refused: ${next.size} tile(s) would be left")
            return true
        }
        val focusedId = s.tiles.getOrNull(s.focused)?.id
        val focus = next.indexOfFirst { it.id == focusedId }.coerceAtLeast(0)
        Log.i(TAG, "[MV-CAST] composite ${if (inPile) "remove" else "add"} ${tile.displayName}: ${s.tiles.size} -> ${next.size} tiles")
        start(next, lastHeaders, focus)
        return true
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

    /** Composite focus change while cast (iOS 3515e92, round 6): the TV
     *  shows a focus change only when the receiver's playhead reaches the
     *  frames drawn after it, and the receiver page never moves its playhead
     *  forward on its own (iPhone log 2026-10-07 15:11:37: 13.6 s behind,
     *  about 20 s from tap to TV). Logs the receiver's position, seekable end
     *  and lag behind the composite live point; a web receiver more than
     *  [NUDGE_THRESHOLD_MS] behind its seekable end is seeked to just inside
     *  it, and a result line follows 4 s later. */
    private fun compositeFocusChanged(tileName: String) {
        if (compositor == null) return
        val snap = sender.receiverLiveSnapshot() ?: return
        val target = sender.receiverTarget.value
        val pos = snap.positionMs / 1000.0
        val end = snap.seekableEndMs?.let { it / 1000.0 }
        val behind = end?.let { it - pos }
        Log.i(
            TAG,
            "[Cast] composite focus $tileName: receiver t=${fmt3(pos)} seekable end=${end?.let { fmt3(it) } ?: "none"} " +
                "behind seekable end=${behind?.let { String.format(java.util.Locale.US, "%.1f s", it) } ?: "n/a"}; ${lagLine(pos)}",
        )
        if (target != AerioCastSender.ReceiverTarget.WEB_RECEIVER || end == null || behind == null ||
            !end.isFinite() || behind * 1000 <= NUDGE_THRESHOLD_MS
        ) return
        val aim = maxOf(pos, end - NUDGE_MARGIN_MS / 1000.0)
        Log.i(
            TAG,
            String.format(
                java.util.Locale.US,
                "[Cast] composite nudge: seek t=%.3f -> %.3f (live seekable end %.3f, %.1f s behind > %.1f s)",
                pos, aim, end, behind, NUDGE_THRESHOLD_MS / 1000.0,
            ),
        )
        sender.seekToStreamPosition((aim * 1000).toLong())
        nudgeCheck?.cancel()
        nudgeCheck = scope.launch {
            kotlinx.coroutines.delay(4_000L)
            if (compositor == null) return@launch
            val after = sender.receiverLiveSnapshot() ?: return@launch
            val p = after.positionMs / 1000.0
            Log.i(
                TAG,
                "[Cast] composite nudge result: receiver t=${fmt3(p)} playerState=${after.playerState} " +
                    "seekable end=${after.seekableEndMs?.let { fmt3(it / 1000.0) } ?: "none"} " +
                    "(moved ${String.format(java.util.Locale.US, "%+.1f", p - pos)} s in 4 s wall); ${lagLine(p)}",
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
    }

    /** Preview tile menu Remove from Multiview: the tile at position
     *  [index] leaves the running pile (the composite restarts with the
     *  rest, same cast session). Refused below two tiles. */
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
