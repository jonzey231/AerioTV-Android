package com.aeriotv.android.core.cast.companion

import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aeriotv.android.core.cast.AerioCastReceiverController
import com.aeriotv.android.core.cast.CastControl
import com.aeriotv.android.core.playback.AerioExoPlayerHolder
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.feature.player.applySpeed
import com.aeriotv.android.feature.player.selectAudioTrack
import com.aeriotv.android.feature.player.selectSubtitleTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * TV-side host for the LAN companion remote (GH #33 second-screen). When AerioTV is
 * open on an Android TV device it advertises itself over mDNS/NSD (`_aeriotv._tcp`)
 * and runs an embedded Ktor WebSocket server. A phone discovers it, pairs with a
 * 6-digit code shown on the TV (or a remembered token), and then drives the TV's OWN
 * native ExoPlayer -- so the phone gets full live-rewind / HEVC / AC-3, with no Google
 * Cast and no Play-install gate.
 *
 * The player-control wire format is reused verbatim from [CastControl] (the same
 * commands the Cast receiver handles); channel changes reuse the receiver's proven
 * in-place switch ([AerioCastReceiverController.requestCastChannel]); the session /
 * pairing handshake is [CompanionProtocol]. Every control command is refused until
 * the socket authenticates.
 *
 * This runs ONLY on TV (FEATURE_LEANBACK); on phones it stays inert.
 */
@Singleton
class CompanionHostController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: AerioExoPlayerHolder,
    private val castReceiver: AerioCastReceiverController,
    private val appPrefs: AppPreferences,
) {
    private companion object {
        const val TAG = "CompanionHost"
        const val WS_PATH = "/remote"
        const val PREFS = "aeriotv_companion"
        const val KEY_TOKENS = "tokens" // StringSet of issued pairing tokens
        const val KEY_DEVICE_ID = "deviceId"
        const val TICK_MS = 1000L
        /** Wrong-code guesses tolerated on one socket before it is closed. */
        const val MAX_CODE_ATTEMPTS = 5
    }

    /** Player commands executed on the single ExoPlayer thread (Main), like the Cast receiver. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Ktor server + NSD live off Main. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var started = false
    @Volatile private var advertising = false
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private var boundPort = 0
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var tickerJob: Job? = null

    private val sessions: MutableSet<DefaultWebSocketSession> =
        Collections.synchronizedSet(mutableSetOf())

    /** Pairing code currently shown on the TV (null when no pairing is pending). The
     *  TV UI observes this to render the "enter this code on your phone" overlay. */
    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    /** GH #33 companion VOD/DVR: a phone asked this TV to play a movie/episode or
     *  a recording. One-shot Channel (same rationale as the cast loadRequests) --
     *  MainActivity collects it and routes into the deep-link navigation. */
    sealed interface PlayRequest {
        data class Vod(val videoId: String, val isEpisode: Boolean) : PlayRequest
        data class Recording(val url: String, val title: String) : PlayRequest
    }
    private val _playRequests = Channel<PlayRequest>(Channel.BUFFERED)
    val playRequests: Flow<PlayRequest> = _playRequests.receiveAsFlow()

    /**
     * GH #33 companion VOD/DVR transport: the TV's VOD/recording playback uses a
     * PER-SCREEN ExoPlayer (VODPlayerScreen), NOT the shared live holder -- so that
     * screen registers its player here while mounted and companion transport /
     * seek / position drive it instead of the (stopped) live player.
     */
    @Volatile var externalPlayerProvider: (() -> androidx.media3.exoplayer.ExoPlayer?)? = null

    private fun controlPlayer(): androidx.media3.exoplayer.ExoPlayer? =
        externalPlayerProvider?.invoke() ?: holder.player

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private fun isTv(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature("android.software.leanback")

    private var watchdogJob: Job? = null

    /**
     * On-demand foreground truth. The Google TV Streamer delivers STALE lifecycle
     * events ~1s after every (re)launch ("Activity pause timeout" in the system
     * log): a late onStop lands after the new onStart, so the ProcessLifecycleOwner
     * observer tears the advert down and leaves this host dark while the app is
     * visibly foreground (2026-07-16 field trace: register -> Removing service
     * 130ms later on every launch). Activity resume/pause callbacks are delivered
     * equally out of order, so no event bookkeeping is trustworthy here -- the
     * recheck and watchdog ask ActivityManager for the process importance instead.
     */
    private fun isForeground(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return false
        val myPid = android.os.Process.myPid()
        return am.runningAppProcesses?.any {
            it.pid == myPid &&
                it.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    }

    /** Idempotent; called once from Application.onCreate. No-op on non-TV. */
    fun start() {
        if (started || !isTv()) return
        started = true
        // Advertise only while the TV app is foregrounded; tear down when it leaves.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = startAdvertising()
            override fun onStop(owner: LifecycleOwner) {
                stopAdvertising()
                // Stale-stop guard: if the process is still foreground shortly
                // after, this stop was out-of-order -- bring the host straight back.
                ioScope.launch {
                    delay(2_000)
                    if (!advertising && isForeground()) {
                        Log.i(TAG, "stale lifecycle stop while foreground -> re-advertising")
                        startAdvertising()
                    }
                }
            }
        })
        startAdvertising()
        // Safety net for any teardown path the recheck misses: while the app is
        // visibly foreground the host must be advertising.
        watchdogJob = ioScope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(15_000)
                if (!advertising && isForeground()) {
                    Log.i(TAG, "watchdog: foreground but not advertising -> restart")
                    startAdvertising()
                }
            }
        }
    }

    @Synchronized
    private fun startAdvertising() {
        if (advertising) return
        advertising = true
        ioScope.launch {
            runCatching {
                // Bind the server to an EPHEMERAL port and read back what it
                // actually bound (review 2026-07-15): the old freePort()->probe,
                // then startServer(port)->bind sequence was a TOCTOU (another
                // process could grab the port in between) AND start(wait=false)'s
                // async bind failure escaped the runCatching. resolvedConnectors()
                // suspends until the real bind succeeds (or throws), so we only
                // ever advertise a port we truly own.
                boundPort = startServerAndResolvePort()
                acquireMulticastLock()
                registerNsd(boundPort)
                Log.i(TAG, "companion host advertising on :$boundPort")
                // stopAdvertising may have run while we were binding (stale
                // lifecycle events interleave on some devices, and its regListener/
                // server fields were still null then) -- tear down what we just
                // built so a dead advert doesn't linger on the network.
                if (!advertising) {
                    Log.i(TAG, "advertising cancelled mid-setup -> tearing down")
                    runCatching { regListener?.let { nsd?.unregisterService(it) } }
                    regListener = null
                    runCatching { server?.stop(200L, 500L) }
                    server = null
                    multicastLock?.let { l -> runCatching { if (l.isHeld) l.release() } }
                    multicastLock = null
                }
            }.onFailure {
                Log.w(TAG, "startAdvertising failed", it)
                // Leave a clean slate so the next foreground can re-attempt
                // (otherwise advertising stays true and NSD is never retried).
                runCatching { server?.stop(200L, 500L) }
                server = null
                advertising = false
            }
        }
    }

    @Synchronized
    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        runCatching { regListener?.let { nsd?.unregisterService(it) } }
        regListener = null
        runCatching { server?.stop(500L, 1000L) }
        server = null
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
        synchronized(sessions) { sessions.clear() }
        tickerJob?.cancel(); tickerJob = null
        _pairingCode.value = null
    }

    // ---- NSD advertise ----

    private fun registerNsd(port: Int) {
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = mgr
        val info = NsdServiceInfo().apply {
            serviceName = deviceName()
            serviceType = CompanionProtocol.SERVICE_TYPE
            setPort(port)
            runCatching { setAttribute(CompanionProtocol.TXT_VERSION, CompanionProtocol.VERSION.toString()) }
            runCatching { setAttribute(CompanionProtocol.TXT_DEVICE_ID, deviceId()) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "NSD registered ${s.serviceName}") }
            override fun onRegistrationFailed(s: NsdServiceInfo, err: Int) { Log.w(TAG, "NSD reg failed $err") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, err: Int) {}
        }
        regListener = listener
        runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.createMulticastLock("aeriotv-companion-host")?.apply {
                setReferenceCounted(false); acquire()
            }
        }.getOrNull()
    }

    // ---- WebSocket server ----

    /** Live pairing sockets: the code overlay clears when the LAST one drops. */
    private val pairingWaiters = java.util.concurrent.atomic.AtomicInteger(0)

    private suspend fun startServerAndResolvePort(): Int {
        val srv = embeddedServer(CIO, port = 0, host = "0.0.0.0") {
            install(WebSockets)
            routing {
                webSocket(WS_PATH) {
                    // Drive-by-web hardening (adversarial review 2026-07-15): browser
                    // WebSockets are NOT blocked by same-origin policy, so a hostile
                    // page on any LAN device could script this socket. Browsers always
                    // send an Origin header on WS upgrades; our native clients never
                    // do -- reject any upgrade that carries one.
                    if (call.request.headers["Origin"] != null) {
                        close()
                        return@webSocket
                    }
                    serveConnection()
                }
            }
        }
        server = srv
        srv.start(wait = false)
        // Suspends until the ephemeral port is actually bound; throws on bind
        // failure (surfaced to startAdvertising's runCatching).
        return srv.engine.resolvedConnectors().first().port
    }

    private suspend fun DefaultWebSocketSession.serveConnection() {
        var authed = false
        var wasPairing = false
        var codeAttempts = 0
        runCatching { send(Frame.Text(CompanionProtocol.hello(deviceName(), needsPairing = true, nowPlaying = nowPlayingTitle()))) }
        try {
            for (frame in incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                val type = CompanionProtocol.typeOf(json)
                if (!authed) {
                    if (type == CompanionProtocol.T_AUTH) {
                        val code = json.optString(CompanionProtocol.KEY_CODE)
                        val issued = tryAuth(json.optString(CompanionProtocol.KEY_TOKEN), code)
                        if (issued != null) {
                            authed = true
                            _pairingCode.value = null
                            runCatching { send(Frame.Text(CompanionProtocol.authOk(issued))) }
                            registerSession(this)
                            // On-TV confirmation that a remote took control.
                            scope.launch {
                                runCatching {
                                    android.widget.Toast.makeText(
                                        context, "Phone connected", android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        } else {
                            // Brute-force hardening (adversarial review 2026-07-15):
                            // a WRONG code both counts against a small per-connection
                            // budget AND rotates the displayed code, so the 6-digit
                            // space can't be swept -- every guess invalidates itself.
                            // Token-only failures (remembered device gone stale) don't
                            // rotate: the phone follows up with a code entry.
                            if (code.isNotBlank()) {
                                codeAttempts++
                                _pairingCode.value = null // rotate on every miss
                            }
                            if (codeAttempts >= MAX_CODE_ATTEMPTS) {
                                runCatching { send(Frame.Text(CompanionProtocol.authFail(CompanionProtocol.REASON_BAD_CODE))) }
                                close()
                                break
                            }
                            // No valid token/code yet -> show a code on the TV and ask for it.
                            if (!wasPairing) {
                                wasPairing = true
                                pairingWaiters.incrementAndGet()
                            }
                            ensurePairingCode()
                            val reason = if (json.optString(CompanionProtocol.KEY_TOKEN).isNotBlank())
                                CompanionProtocol.REASON_BAD_TOKEN else CompanionProtocol.REASON_BAD_CODE
                            runCatching { send(Frame.Text(CompanionProtocol.authFail(reason))) }
                        }
                    }
                    continue // ignore control commands until authenticated
                }
                if (type == null) handleControl(json, this) // CastControl "cmd" frame
            }
        } finally {
            unregisterSession(this)
            // This socket was the one a code was minted for: when the LAST pairing
            // socket drops without success, take the overlay down and kill the code
            // (review finding: it used to linger on screen indefinitely).
            if (wasPairing && pairingWaiters.decrementAndGet() <= 0 && !authed) {
                _pairingCode.value = null
            }
        }
    }

    private fun tryAuth(token: String, code: String): String? {
        val tokens = prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty()
        if (token.isNotBlank() && token in tokens) return token // remembered device
        val pending = _pairingCode.value
        if (code.isNotBlank() && pending != null && code == pending) {
            val fresh = UUID.randomUUID().toString()
            prefs.edit().putStringSet(KEY_TOKENS, tokens + fresh).apply()
            return fresh
        }
        return null
    }

    private fun ensurePairingCode() {
        if (_pairingCode.value == null) {
            _pairingCode.value = "%06d".format(Random.nextInt(0, 1_000_000))
        }
    }

    private fun registerSession(session: DefaultWebSocketSession) {
        synchronized(sessions) { sessions.add(session) }
        ensureTicker()
        scope.launch { pushPosition() } // immediate first paint
    }

    private fun unregisterSession(session: DefaultWebSocketSession) {
        synchronized(sessions) { sessions.remove(session) }
        if (synchronized(sessions) { sessions.isEmpty() }) {
            tickerJob?.cancel(); tickerJob = null
            // No phone left to turn it back off: don't leave the TV stuck on a
            // dark screen after the controlling phone goes away.
            if (holder.remoteAudioOnly) {
                Log.i(TAG, "last phone disconnected while audioOnly -> restoring video")
                holder.remoteAudioOnly = false
                scope.launch { runCatching { holder.setVideoTrackEnabled(true) } }
            }
        }
    }

    // ---- Command executor (reuses the Cast receiver's rules; drives the shared player) ----

    private fun handleControl(json: JSONObject, session: DefaultWebSocketSession) {
        scope.launch {
            val cmd = json.optString(CastControl.KEY_CMD)
            // GH #33 diagnostics: one breadcrumb per inbound phone command (skip
            // the noisy getState poll) so a user's captured log shows exactly
            // what the phone asked the TV to do.
            if (cmd.isNotBlank() && cmd != CastControl.CMD_GET_STATE) {
                Log.i(TAG, "companion cmd: $cmd")
            }
            when (cmd) {
                CastControl.CMD_SET_CHANNEL -> json.optString(CastControl.KEY_CHANNEL_ID)
                    .takeIf { it.isNotBlank() }?.let { id ->
                        // Ride the SAME validated load-request -> deep-link path a
                        // cast LOAD uses: MainActivity collects it from ANY app
                        // state (guide, menus, idle, playing) and the Navigation
                        // layer decides navigate-vs-in-place-re-tune. Direct
                        // requestCastChannel would only work while the live
                        // player is mounted (its collector lives in PlayerScreen).
                        if (!castReceiver.requestOpenChannel(id)) {
                            Log.w(TAG, "companion setChannel: channel not playable: $id")
                        }
                    }
                // GH #33 companion VOD/DVR: route to the TV's VOD player via the
                // deep-link navigation (MainActivity collects playRequests).
                CastControl.CMD_PLAY_VOD -> json.optString(CastControl.KEY_VIDEO_ID)
                    .takeIf { it.isNotBlank() }?.let { id ->
                        _playRequests.trySend(
                            PlayRequest.Vod(id, json.optBoolean(CastControl.KEY_IS_EPISODE)),
                        )
                    }
                CastControl.CMD_PLAY_RECORDING -> json.optString(CastControl.KEY_URL)
                    .takeIf { it.isNotBlank() }?.let { url ->
                        _playRequests.trySend(
                            PlayRequest.Recording(url, json.optString(CastControl.KEY_TITLE)),
                        )
                    }
                // Transport prefers the registered VOD/recording player when one
                // is on screen; otherwise the shared live player.
                CastControl.CMD_PLAY -> runCatching { controlPlayer()?.play() }
                CastControl.CMD_PAUSE -> runCatching { controlPlayer()?.pause() }
                CastControl.CMD_TOGGLE -> runCatching {
                    controlPlayer()?.let { if (it.isPlaying) it.pause() else it.play() }
                }
                CastControl.CMD_GO_LIVE -> runCatching { holder.goLive() }
                CastControl.CMD_SEEK_BY -> runCatching {
                    val delta = json.optLong(CastControl.KEY_DELTA_MS)
                    val ext = externalPlayerProvider?.invoke()
                    if (ext != null) {
                        ext.seekTo((ext.currentPosition + delta).coerceAtLeast(0L))
                    } else {
                        val base = holder.currentRewindWallMs() ?: holder.rewindWindow()?.get(1)
                        if (base != null) commitRewindSeek(base + delta)
                    }
                }
                CastControl.CMD_SEEK_WALL -> runCatching {
                    val target = json.optLong(CastControl.KEY_TARGET_WALL_MS)
                    val ext = externalPlayerProvider?.invoke()
                    // For a VOD/recording player the "wall" axis IS media position
                    // (the position tick maps window [0, duration] onto it).
                    if (ext != null) ext.seekTo(target.coerceAtLeast(0L)) else commitRewindSeek(target)
                }
                CastControl.CMD_SET_AUDIO_ONLY -> runCatching {
                    val on = json.optBoolean(CastControl.KEY_AUDIO_ONLY)
                    // Sticky: re-primes must not silently restore video.
                    holder.remoteAudioOnly = on
                    holder.setVideoTrackEnabled(!on)
                    Log.i(TAG, "companion audioOnly=$on (video ${if (on) "disabled" else "enabled"})")
                }
                // Options parity with the Cast receiver (GH #33): the phone's full
                // CastRemoteOverlay drives the companion path too, so mirror the
                // receiver's audio/subtitle/speed/aspect handlers verbatim.
                CastControl.CMD_SET_AUDIO -> runCatching {
                    controlPlayer()?.selectAudioTrack(
                        json.optString(CastControl.KEY_TRACK_ID).toIntOrNull(),
                    )
                }
                CastControl.CMD_SET_TEXT -> runCatching {
                    controlPlayer()?.selectSubtitleTrack(
                        json.optString(CastControl.KEY_TRACK_ID).toIntOrNull(),
                    )
                }
                CastControl.CMD_SET_SPEED -> runCatching {
                    controlPlayer()?.applySpeed(json.optDouble(CastControl.KEY_SPEED, 1.0).toFloat())
                }
                CastControl.CMD_SET_ASPECT -> runCatching {
                    appPrefs.setPlayerAspectMode(
                        CastControl.AspectMode.fromKey(json.optString(CastControl.KEY_ASPECT)).key,
                    )
                }
                // CMD_GET_STATE: no action; the state reply below answers it.
            }
            // Mirror the Cast receiver's replyState: after EVERY command, push the
            // full snapshot (tracks/speed/aspect/streamInfo/rewind) built by the
            // SAME AerioCastReceiverController code path, so both remotes render
            // identical pickers. Plus the lightweight position tick.
            runCatching {
                // Pass the registered VOD/recording player (if any) so the snapshot
                // reflects the player the commands actually drive, not the stopped
                // live holder (review 2026-07-15).
                session.send(Frame.Text(castReceiver.buildRemoteStateMessage(externalPlayerProvider?.invoke())))
            }
            pushPosition()
        }
    }

    /** Clamp + apply a rewind seek (port of AerioCastReceiverController.commitRewindSeek). */
    private fun commitRewindSeek(target: Long) {
        val w = holder.rewindWindow() ?: return
        val tail = w[0]
        val head = w[1]
        if (target >= head - 5_000) holder.goLive()
        else holder.playTimeshift(target.coerceAtLeast(tail))
    }

    // ---- State push (~1Hz position tick; carries isPlaying for the phone transport) ----

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = ioScope.launch {
            while (isActive) {
                pushPosition()
                delay(TICK_MS)
            }
        }
    }

    private suspend fun pushPosition() {
        val msg = withContext(Dispatchers.Main.immediate) {
            val ext = externalPlayerProvider?.invoke()
            if (ext != null) {
                // VOD / recording player: map the seek axis onto media position --
                // window [0, duration], playhead = currentPosition. The phone's
                // scrubber + seek commands then work unchanged.
                val duration = runCatching { ext.duration }.getOrNull()?.takeIf { it > 0 } ?: 0L
                CastControl.positionMessage(
                    canSeek = duration > 0,
                    isLive = false,
                    positionWallMs = runCatching { ext.currentPosition }.getOrDefault(0L),
                    windowStartMs = 0L,
                    windowEndMs = duration,
                    isPlaying = runCatching { ext.isPlaying }.getOrDefault(false),
                )
            } else {
                val win = runCatching { holder.rewindWindow() }.getOrNull()
                val pos = runCatching { holder.currentRewindWallMs() }.getOrNull() ?: (win?.get(1) ?: 0L)
                CastControl.positionMessage(
                    canSeek = win != null,
                    isLive = runCatching { holder.isAtLiveEdge() }.getOrDefault(true),
                    positionWallMs = pos,
                    windowStartMs = win?.get(0) ?: 0L,
                    windowEndMs = win?.get(1) ?: 0L,
                    // Default false when there's no live player: reporting "playing"
                    // while idle made the phone transport show a play state for
                    // nothing (review 2026-07-15).
                    isPlaying = runCatching { holder.player?.isPlaying }.getOrNull() ?: false,
                    // Anchor rides the tick: the post-setChannel state push races
                    // the async re-prime and would otherwise leave clients stale.
                    channelId = holder.currentChannelId ?: castReceiver.castChannelRequest.value,
                )
            }
        }
        broadcast(msg)
    }

    private suspend fun broadcast(text: String) {
        val snapshot = synchronized(sessions) { sessions.toList() }
        snapshot.forEach { s ->
            runCatching { s.send(Frame.Text(text)) }.onFailure {
                runCatching { s.close() }
                synchronized(sessions) { sessions.remove(s) }
            }
        }
    }

    // ---- helpers ----

    private fun nowPlayingTitle(): String =
        runCatching { castReceiver.castChannelRequest.value }.getOrNull().orEmpty()

    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, "device_name") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android TV"

    private fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().take(8)
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    /** Allocate a free TCP port for the WS server (NSD advertises the resolved port). */
}
