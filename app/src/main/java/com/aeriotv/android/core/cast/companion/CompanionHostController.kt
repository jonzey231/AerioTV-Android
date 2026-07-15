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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.ServerSocket
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
    }

    /** Player commands executed on the single ExoPlayer thread (Main), like the Cast receiver. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Ktor server + NSD live off Main. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var started = false
    private var advertising = false
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

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private fun isTv(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature("android.software.leanback")

    /** Idempotent; called once from Application.onCreate. No-op on non-TV. */
    fun start() {
        if (started || !isTv()) return
        started = true
        // Advertise only while the TV app is foregrounded; tear down when it leaves.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = startAdvertising()
            override fun onStop(owner: LifecycleOwner) = stopAdvertising()
        })
        startAdvertising()
    }

    private fun startAdvertising() {
        if (advertising) return
        advertising = true
        ioScope.launch {
            runCatching {
                boundPort = freePort()
                startServer(boundPort)
                acquireMulticastLock()
                registerNsd(boundPort)
                Log.i(TAG, "companion host advertising on :$boundPort")
            }.onFailure { Log.w(TAG, "startAdvertising failed", it) }
        }
    }

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

    private fun startServer(port: Int) {
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets)
            routing {
                webSocket(WS_PATH) { serveConnection() }
            }
        }.also { it.start(wait = false) }
    }

    private suspend fun DefaultWebSocketSession.serveConnection() {
        var authed = false
        runCatching { send(Frame.Text(CompanionProtocol.hello(deviceName(), needsPairing = true, nowPlaying = nowPlayingTitle()))) }
        try {
            for (frame in incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                val type = CompanionProtocol.typeOf(json)
                if (!authed) {
                    if (type == CompanionProtocol.T_AUTH) {
                        val issued = tryAuth(json.optString(CompanionProtocol.KEY_TOKEN), json.optString(CompanionProtocol.KEY_CODE))
                        if (issued != null) {
                            authed = true
                            _pairingCode.value = null
                            runCatching { send(Frame.Text(CompanionProtocol.authOk(issued))) }
                            registerSession(this)
                        } else {
                            // No valid token/code yet -> show a code on the TV and ask for it.
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
        if (synchronized(sessions) { sessions.isEmpty() }) { tickerJob?.cancel(); tickerJob = null }
    }

    // ---- Command executor (reuses the Cast receiver's rules; drives the shared player) ----

    private fun handleControl(json: JSONObject, session: DefaultWebSocketSession) {
        scope.launch {
            when (json.optString(CastControl.KEY_CMD)) {
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
                CastControl.CMD_PLAY -> runCatching { holder.player?.play() }
                CastControl.CMD_PAUSE -> runCatching { holder.player?.pause() }
                CastControl.CMD_TOGGLE -> runCatching {
                    holder.player?.let { if (it.isPlaying) it.pause() else it.play() }
                }
                CastControl.CMD_GO_LIVE -> runCatching { holder.goLive() }
                CastControl.CMD_SEEK_BY -> runCatching {
                    val delta = json.optLong(CastControl.KEY_DELTA_MS)
                    val base = holder.currentRewindWallMs() ?: holder.rewindWindow()?.get(1)
                    if (base != null) commitRewindSeek(base + delta)
                }
                CastControl.CMD_SEEK_WALL -> runCatching {
                    commitRewindSeek(json.optLong(CastControl.KEY_TARGET_WALL_MS))
                }
                CastControl.CMD_SET_AUDIO_ONLY -> runCatching {
                    holder.setVideoTrackEnabled(!json.optBoolean(CastControl.KEY_AUDIO_ONLY))
                }
                // Options parity with the Cast receiver (GH #33): the phone's full
                // CastRemoteOverlay drives the companion path too, so mirror the
                // receiver's audio/subtitle/speed/aspect handlers verbatim.
                CastControl.CMD_SET_AUDIO -> runCatching {
                    holder.player?.selectAudioTrack(
                        json.optString(CastControl.KEY_TRACK_ID).toIntOrNull(),
                    )
                }
                CastControl.CMD_SET_TEXT -> runCatching {
                    holder.player?.selectSubtitleTrack(
                        json.optString(CastControl.KEY_TRACK_ID).toIntOrNull(),
                    )
                }
                CastControl.CMD_SET_SPEED -> runCatching {
                    holder.player?.applySpeed(json.optDouble(CastControl.KEY_SPEED, 1.0).toFloat())
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
                session.send(Frame.Text(castReceiver.buildRemoteStateMessage()))
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
            val win = runCatching { holder.rewindWindow() }.getOrNull()
            val pos = runCatching { holder.currentRewindWallMs() }.getOrNull() ?: (win?.get(1) ?: 0L)
            CastControl.positionMessage(
                canSeek = win != null,
                isLive = runCatching { holder.isAtLiveEdge() }.getOrDefault(true),
                positionWallMs = pos,
                windowStartMs = win?.get(0) ?: 0L,
                windowEndMs = win?.get(1) ?: 0L,
                isPlaying = runCatching { holder.player?.isPlaying }.getOrNull() ?: true,
            )
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
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
