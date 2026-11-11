package com.aeriotv.android.core.setup

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.profiles.PinRules
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.core.profiles.effectiveRole
import com.aeriotv.android.core.sync.dsync.DispatcharrSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** A device offering setup: from NSD (key only) or from its QR code (code included). */
data class SetupTarget(
    val name: String,
    val host: String,
    val port: Int,
    val pk: ByteArray,
    /** The code itself, when it came from a QR link. */
    val code: String? = null,
    /** [SetupProtocol.KIND_PHONE], [SetupProtocol.KIND_TABLET], or [SetupProtocol.KIND_TV]. */
    val kind: String = SetupProtocol.KIND_TV,
    /** DNS-SD instance name: the identity of a discovered device (two can share a friendly name). */
    val service: String? = null,
    /** Advertised `mode` ([FixLogin.normalizeMode]): null = a normal setup receiver. */
    val mode: String? = null,
    /** Advertised `pl` with Fix Login: the receiver's playlist id, lowercase. */
    val playlistId: String? = null,
) {
    val key: String get() = "$host:$port"
    /** One discovered device: its DNS-SD instance name, else its address. */
    val identity: String get() = service ?: key
    /** The receiver asks for one playlist's sign-in (Fix Login), not a whole setup. */
    val isFixLogin: Boolean get() = mode == SetupProtocol.MODE_FIX_LOGIN
    /** Matches the 4 characters the TV shows next to its code. */
    val fingerprint: String get() = SetupCrypto.keyFingerprint(pk)
}

/**
 * Phone side NSD browse for `_aeriotv-setup._tcp`: the dock card and Settings
 * banner while the app is in the foreground (MainScaffold), and the Set up
 * another device screen. The browse, refresh, and resolve rules live in
 * [SetupDiscoveryEngine] (pure, unit tested); this adapts it to NsdManager.
 * Every engine call runs on the main thread.
 */
class SetupDiscovery(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val nsd: NsdManager? by lazy { context.getSystemService(Context.NSD_SERVICE) as? NsdManager }
    private var lock: WifiManager.MulticastLock? = null
    private val engine = SetupDiscoveryEngine(
        platform = NsdPlatform(),
        timers = { ms, block ->
            val r = Runnable(block)
            main.postDelayed(r, ms)
            ({ main.removeCallbacks(r) })
        },
        isOwnPk = SetupReceiver::isOwnPk,
        log = { Log.i(TAG, it) },
    )

    val tvs: StateFlow<List<SetupTarget>> = engine.tvs

    fun start() = onMain {
        if (engine.isRunning || nsd == null) return@onMain
        lock = runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("aeriotv-setup-discover")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        engine.start()
    }

    fun stop() = onMain {
        engine.stop()
        lock?.let { runCatching { if (it.isHeld) it.release() } }
        lock = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** NsdManager behind the engine. Callbacks hop to the main thread before reaching it. */
    private inner class NsdPlatform : SetupDiscoveryEngine.Platform {
        private val listeners = HashMap<Int, NsdManager.DiscoveryListener>()
        /** Browse id -> instance name -> the advert as that browse last reported it. */
        private val adverts = HashMap<Int, HashMap<String, NsdServiceInfo>>()
        private val resolvers = HashMap<Int, NsdManager.ResolveListener>()

        override fun startBrowse(id: Int) {
            val mgr = nsd ?: return
            val seen = HashMap<String, NsdServiceInfo>()
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    main.post { listeners.remove(id); adverts.remove(id); engine.browseFailed(id, errorCode) }
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    val name = info.serviceName ?: return
                    main.post { if (listeners.containsKey(id)) { seen[name] = info; engine.found(id, name) } }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    val name = info.serviceName ?: return
                    main.post { if (listeners.containsKey(id)) { seen.remove(name); engine.lost(id, name) } }
                }
            }
            listeners[id] = l
            adverts[id] = seen
            runCatching { mgr.discoverServices(SetupProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
                .onFailure { listeners.remove(id); adverts.remove(id); main.post { engine.browseFailed(id, -1) } }
        }

        override fun stopBrowse(id: Int) {
            adverts.remove(id)
            // A new listener per browse: NsdManager refuses a listener that is already in use.
            listeners.remove(id)?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        }

        override fun resolve(serial: Int, browse: Int, service: String) {
            val mgr = nsd
            val info = adverts[browse]?.get(service)
            if (mgr == null || info == null) {
                main.post { engine.resolveFailed(serial, "advert gone") }
                return
            }
            @Suppress("DEPRECATION")
            val rl = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post { resolvers.remove(serial); engine.resolveFailed(serial, "error $errorCode") }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = info.host?.hostAddress
                    val txt = runCatching { info.attributes }.getOrNull().orEmpty()
                        .mapValues { (_, v) -> v?.let { String(it, Charsets.UTF_8) } }
                    val t = SetupDiscoveryRules.receiverTarget(service, host, info.port, txt, SetupReceiver::isOwnPk)
                    main.post { resolvers.remove(serial); engine.resolved(serial, t) }
                }
            }
            resolvers[serial] = rl
            @Suppress("DEPRECATION")
            runCatching { mgr.resolveService(info, rl) }
                .onFailure { e -> resolvers.remove(serial); main.post { engine.resolveFailed(serial, e.javaClass.simpleName) } }
        }

        override fun cancelResolve(serial: Int) {
            val rl = resolvers.remove(serial) ?: return
            // Android 14 can cancel a resolve; before that a timed-out one is only ignored,
            // and the retry may meet "already active" until the system gives up on it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { nsd?.stopServiceResolution(rl) }
            }
        }
    }

    companion object {
        private const val TAG = "AerioSetup"

        /**
         * Resolves a discovered device again right before a send, so a device that
         * started a new session (new port and key) is reached at its current
         * advert. Waits up to [timeoutMs] for the same name; keeps the old target
         * when the device is not seen (a QR target is returned as is).
         */
        suspend fun fresh(context: Context, t: SetupTarget, timeoutMs: Long = 3_000): SetupTarget {
            if (t.code != null) return t
            val d = SetupDiscovery(context.applicationContext)
            d.start()
            return try {
                val live = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                    val same: (SetupTarget) -> Boolean = { if (t.service != null) it.service == t.service else it.name == t.name }
                    d.tvs.first { list -> list.any(same) }.first(same)
                }
                when {
                    live == null -> { Log.i(TAG, "[SETUP] send target not re-resolved, using last advert key=${t.fingerprint}"); t }
                    live.pk.contentEquals(t.pk) && live.port == t.port -> { Log.i(TAG, "[SETUP] send target confirmed key=${live.fingerprint}"); live }
                    else -> { Log.i(TAG, "[SETUP] send target refreshed old=${t.fingerprint} new=${live.fingerprint} port ${t.port} -> ${live.port}"); live }
                }
            } finally { d.stop() }
        }
    }
}

/** Builds the payload from this device's playlist and profiles, and posts it. */
class SetupSender(
    private val profiles: ProfileManager,
    private val prefs: AppPreferences,
) {
    data class ProfileChoice(
        val profileId: String,
        val displayName: String,
        val username: String,
        val isDefault: Boolean,
        val isCurrent: Boolean,
        /** "API key saved", "Password saved", or null when none is stored here. */
        val credentialLabel: String?,
    )

    data class Source(
        val playlist: PlaylistEntity,
        val isDispatcharr: Boolean,
        val choices: List<ProfileChoice>,
        val syncOnHere: Boolean,
    )

    suspend fun source(playlistId: String): Source? {
        val row = profiles.defaultPlaylistRow(playlistId) ?: return null
        val isD = row.sourceType.startsWith("Dispatcharr")
        val currentId = profiles.activeProfile(playlistId)?.id
        val choices = if (!isD) emptyList() else profiles.setupSources(playlistId).map { s ->
            val p = s.profile
            val key = if (p.isDefault) row.apiKey else p.apiKey
            val pw = if (p.isDefault) row.password else p.password
            ProfileChoice(
                profileId = p.id,
                displayName = p.displayName,
                username = p.username,
                isDefault = p.isDefault,
                isCurrent = p.id == currentId,
                credentialLabel = when {
                    !key.isNullOrBlank() -> "API key saved"
                    !pw.isNullOrBlank() -> "Password saved"
                    else -> null
                },
            )
        }
        val syncOn = runCatching { prefs.dispatcharrSyncEnabledOnce() }.getOrDefault(false)
        return Source(row, isD, choices, syncOn)
    }

    /**
     * [userOnly] = "Import this user only": the playlist signs in as the
     * active profile, which arrives as the receiver's one (default) profile.
     * Same wire format, one profile.
     */
    suspend fun buildPayload(playlistId: String, profileIds: Set<String>, turnOnSync: Boolean, userOnly: Boolean = false): SetupProtocol.Payload? {
        val row = profiles.defaultPlaylistRow(playlistId) ?: return null
        if (userOnly) return buildUserOnly(playlistId, row, turnOnSync)
        val type = when {
            row.sourceType.startsWith("Dispatcharr") -> "dispatcharr"
            row.sourceType == "XtreamCodes" -> "xtream"
            else -> "m3u"
        }
        val offset = runCatching { prefs.catchupTimeOffsetMinutes(playlistId).first() }.getOrDefault(0)
        val playlist = SetupProtocol.PlaylistPart(
            id = row.id,
            type = type,
            name = row.name,
            url = row.urlString,
            xmltvUrl = row.epgUrl?.takeIf { it.isNotBlank() },
            localUrl = row.lanUrlString?.takeIf { it.isNotBlank() },
            userAgent = row.customUserAgent.takeIf { it.isNotBlank() },
            guideDays = row.epgRetentionDays,
            catchupOffsetMinutes = offset,
            vodEnabled = row.vodEnabled,
            channelProfileIds = listOfNotNull(row.dispatcharrProfileId),
            credential = SetupProtocol.Credential(
                apiKey = row.apiKey?.takeIf { it.isNotBlank() },
                username = row.username?.takeIf { it.isNotBlank() },
                password = row.password?.takeIf { it.isNotBlank() },
            ).takeIf { it.apiKey != null || it.username != null || it.password != null },
        )
        if (type != "dispatcharr") return SetupProtocol.Payload(playlist = playlist)
        val parts = profiles.setupSources(playlistId).withIndex()
            .filter { (_, s) -> s.profile.isDefault || s.profile.id in profileIds }
            .map { (index, s) ->
                val p = s.profile
                val pin = s.pin
                SetupProtocol.ProfilePart(
                    id = p.id,
                    username = p.username,
                    displayName = p.displayName,
                    role = p.effectiveRole().wire,
                    avatar = p.avatar?.let { SetupProtocol.Avatar(it.icon, it.color) },
                    order = index,
                    isDefault = p.isDefault,
                    credential = if (p.isDefault) null else SetupProtocol.Credential(
                        apiKey = p.apiKey?.takeIf { it.isNotBlank() },
                        password = p.password?.takeIf { it.isNotBlank() },
                    ),
                    pinType = if (pin != null) PinRules.wireType(pin.type) else "none",
                    pinHash = pin?.hash.orEmpty(),
                    pinSalt = pin?.salt.orEmpty(),
                )
            }
        return SetupProtocol.Payload(
            playlist = playlist,
            profiles = parts,
            sync = if (turnOnSync) SetupProtocol.SyncPart() else null,
        )
    }

    /**
     * Fix Login: only [playlistId]'s own sign-in (the default profile's, as
     * in a full setup), marked [SetupProtocol.MODE_FIX_LOGIN], with
     * `playlist.id` set to the TV's advertised [receiverPlaylistId] so the TV
     * updates the playlist it asked about. No profiles, no sync choice. Null
     * when the playlist cannot be read or has no sign-in to send.
     */
    suspend fun buildFixLoginPayload(playlistId: String, receiverPlaylistId: String): SetupProtocol.Payload? {
        val full = buildPayload(playlistId, emptySet(), turnOnSync = false) ?: return null
        if (full.playlist.type != "dispatcharr") return null
        return fixLoginPayload(full.playlist, receiverPlaylistId)
    }

    private suspend fun buildUserOnly(playlistId: String, row: PlaylistEntity, turnOnSync: Boolean): SetupProtocol.Payload? {
        val full = buildPayload(playlistId, emptySet(), turnOnSync = false) ?: return null
        if (full.playlist.type != "dispatcharr") return full
        val activeId = profiles.activeProfile(playlistId)?.id
        val src = profiles.setupSources(playlistId).firstOrNull { it.profile.id == activeId }
            ?: profiles.setupSources(playlistId).firstOrNull { it.profile.isDefault } ?: return full
        val p = src.profile
        val cred = if (p.isDefault) full.playlist.credential else userOnlyCredential(p.apiKey, p.username, p.password) ?: return null
        val pin = src.pin
        return full.copy(
            // Another user's sign-in is another account, so another playlist:
            // no ids then (the receiver adds a playlist of its own).
            playlist = full.playlist.copy(credential = cred, id = full.playlist.id.takeIf { p.isDefault }),
            profiles = listOf(
                SetupProtocol.ProfilePart(
                    id = p.id.takeIf { p.isDefault },
                    username = p.username,
                    displayName = p.displayName,
                    role = p.effectiveRole().wire,
                    avatar = p.avatar?.let { SetupProtocol.Avatar(it.icon, it.color) },
                    order = 0,
                    isDefault = true,
                    credential = null,
                    pinType = if (pin != null) PinRules.wireType(pin.type) else "none",
                    pinHash = pin?.hash.orEmpty(),
                    pinSalt = pin?.salt.orEmpty(),
                ),
            ),
            sync = if (turnOnSync) SetupProtocol.SyncPart() else null,
        )
    }

    sealed interface SendResult {
        data object Ok : SendResult
        data object WrongCode : SendResult
        data object Refused : SendResult
        data class Failed(val message: String) : SendResult
    }

    suspend fun send(target: SetupTarget, code: String, payload: SetupProtocol.Payload): SendResult = withContext(Dispatchers.IO) {
        val env = SetupProtocol.sealForTv(payload, target.pk, code)
        val body = SetupProtocol.json.encodeToString(SetupProtocol.Envelope.serializer(), env).toByteArray(Charsets.UTF_8)
        runCatching {
            val conn = URL("http://${target.host}:${target.port}${SetupProtocol.PATH}").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val text = runCatching { (if (status < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() }.getOrNull().orEmpty()
            conn.disconnect()
            val reply = runCatching { SetupProtocol.json.decodeFromString(SetupProtocol.Reply.serializer(), text) }.getOrNull()
            Log.i(TAG, "[SETUP] send -> HTTP $status ${reply?.error.orEmpty()}")
            when {
                status == 200 && reply?.ok == true -> SendResult.Ok
                status == 403 && reply?.error == "wrong_code" -> SendResult.WrongCode
                status == 403 -> SendResult.Refused
                else -> SendResult.Failed("The device answered HTTP $status.")
            }
        }.getOrElse {
            Log.w(TAG, "[SETUP] send failed: ${it.javaClass.simpleName}")
            SendResult.Failed("The device could not be reached. Check that both devices are on the same network.")
        }
    }

    companion object {
        private const val TAG = "AerioSetup"

        /**
         * "Turn on Dispatcharr Sync on the other device" starts ticked only
         * when Dispatcharr Sync is on for this device, for either scope
         * (Apple SetUpTvView parity: sync = source.syncOnHere).
         */
        fun syncPreTicked(isDispatcharr: Boolean, syncOnHere: Boolean): Boolean = isDispatcharr && syncOnHere
    }
}

/** What the sender does after checking the key it is about to send. */
sealed interface SetupKeyVerdict {
    /** The server accepted the key, or no verdict after the retries: send. */
    data object Send : SetupKeyVerdict
    /** 401/403: do not send; offer Edit Playlist. */
    data object Rejected : SetupKeyVerdict
}

/**
 * Sender pre-flight (2026-11-09): the sender once handed over a key it
 * already knew the server rejected, and the other device failed with HTTP
 * 429 and then 401. [check] is one authenticated request with the exact key
 * in the payload. 401/403 = [SetupKeyVerdict.Rejected]. A 429 counts down
 * its Retry-After through [onCountdown] and tries again; a network error
 * waits [NETWORK_RETRY_S] seconds and tries again; up to
 * [com.aeriotv.android.core.network.RateLimitRetry.MAX_AUTO_RETRIES]
 * retries, then the send goes ahead (the receiver's own retry covers it).
 */
suspend fun preflightSetupKey(
    check: suspend () -> com.aeriotv.android.core.network.DispatcharrClient.KeyCheck,
    onCountdown: (secondsLeft: Long, rateLimited: Boolean) -> Unit,
    tick: suspend () -> Unit = { kotlinx.coroutines.delay(1_000) },
): SetupKeyVerdict {
    var retries = 0
    while (true) {
        when (val r = check()) {
            com.aeriotv.android.core.network.DispatcharrClient.KeyCheck.Accepted -> return SetupKeyVerdict.Send
            com.aeriotv.android.core.network.DispatcharrClient.KeyCheck.Rejected -> return SetupKeyVerdict.Rejected
            is com.aeriotv.android.core.network.DispatcharrClient.KeyCheck.Unknown -> {
                if (retries >= com.aeriotv.android.core.network.RateLimitRetry.MAX_AUTO_RETRIES) return SetupKeyVerdict.Send
                retries++
                val limited = r.retryAfterSeconds != null
                var left = (r.retryAfterSeconds ?: NETWORK_RETRY_S)
                    .coerceIn(1, com.aeriotv.android.core.network.DispatcharrRequestGate.MAX_RETRY_AFTER_S)
                while (left > 0) { onCountdown(left, limited); tick(); left-- }
            }
        }
    }
}

/** Wait before re-checking the key after a network error. */
const val NETWORK_RETRY_S = 3L

/** Sender copy when the key it would send is refused. */
const val SETUP_KEY_REJECTED_MESSAGE =
    "Dispatcharr no longer accepts this playlist's API key, so it was not sent. Enter the current key in Edit Playlist, then try again."

/**
 * The Fix Login payload from a full payload's playlist part: that playlist
 * and its credential only, addressed to [receiverPlaylistId]. Null without a
 * credential (nothing to fix with). Pure, for the tests.
 */
internal fun fixLoginPayload(playlist: SetupProtocol.PlaylistPart, receiverPlaylistId: String): SetupProtocol.Payload? {
    val cred = playlist.credential?.takeIf { it.apiKey != null || it.password != null } ?: return null
    val id = receiverPlaylistId.trim().takeIf { it.isNotEmpty() } ?: return null
    return SetupProtocol.Payload(
        playlist = playlist.copy(id = id, credential = cred),
        profiles = emptyList(),
        sync = null,
        mode = SetupProtocol.MODE_FIX_LOGIN,
    )
}

/**
 * The sign-in for "Import this user only": `username` always rides beside the
 * API key or password, like Apple. Null when there is neither.
 */
internal fun userOnlyCredential(apiKey: String?, username: String?, password: String?): SetupProtocol.Credential? =
    SetupProtocol.Credential(
        apiKey = apiKey?.takeIf { it.isNotBlank() },
        username = username?.takeIf { it.isNotBlank() },
        password = password?.takeIf { it.isNotBlank() },
    ).takeIf { it.apiKey != null || it.password != null }
