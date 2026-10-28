package com.aeriotv.android.core.setup

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.profiles.PinRules
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.core.profiles.effectiveRole
import com.aeriotv.android.core.sync.dsync.DispatcharrSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

/** A TV offering setup: from NSD (code hash only) or from its QR code (code included). */
data class SetupTarget(
    val name: String,
    val host: String,
    val port: Int,
    val pk: ByteArray,
    /** TXT `code` (first 8 hex of SHA-256 of the code); null when it came from a QR link. */
    val codeHash: String?,
    /** The code itself, when it came from a QR link. */
    val code: String? = null,
) {
    val key: String get() = "$host:$port"
}

/**
 * Phone side NSD browse for `_aeriotv-setup._tcp`. Only runs while the Set up
 * a TV screen is open; resolves one service at a time (NsdManager limit).
 */
class SetupDiscovery(private val context: Context) {
    private val _tvs = MutableStateFlow<List<SetupTarget>>(emptyList())
    val tvs: StateFlow<List<SetupTarget>> = _tvs.asStateFlow()

    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (listener != null) return
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = mgr
        lock = runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("aeriotv-setup-discover")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "[SETUP] discovery failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) { enqueue(info) }
            override fun onServiceLost(info: NsdServiceInfo) {
                _tvs.value = _tvs.value.filterNot { it.name == info.serviceName }
            }
        }
        listener = l
        runCatching { mgr.discoverServices(SetupProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    @Synchronized
    fun stop() {
        listener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        listener = null
        queue.clear()
        resolving = false
        lock?.let { runCatching { if (it.isHeld) it.release() } }
        lock = null
    }

    @Synchronized
    private fun enqueue(info: NsdServiceInfo) { queue.add(info); pump() }

    @Synchronized
    private fun pump() {
        if (resolving) return
        val next = queue.poll() ?: return
        val mgr = nsd ?: return
        resolving = true
        @Suppress("DEPRECATION")
        val rl = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { done() }
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = info.host?.hostAddress
                val attrs = runCatching { info.attributes }.getOrNull().orEmpty()
                fun attr(k: String) = attrs[k]?.let { String(it, Charsets.UTF_8) }
                val pk = attr(SetupProtocol.TXT_PK)?.let(SetupCrypto::unb64)?.takeIf { it.size == 32 }
                if (host != null && pk != null && attr(SetupProtocol.TXT_VERSION) == SetupProtocol.VERSION.toString()) {
                    val t = SetupTarget(
                        name = attr(SetupProtocol.TXT_NAME)?.takeIf { it.isNotBlank() } ?: info.serviceName ?: "TV",
                        host = host,
                        port = info.port,
                        pk = pk,
                        codeHash = attr(SetupProtocol.TXT_CODE),
                    )
                    _tvs.value = (_tvs.value.filterNot { it.name == t.name || it.key == t.key } + t).sortedBy { it.name.lowercase() }
                }
                done()
            }
        }
        @Suppress("DEPRECATION")
        runCatching { mgr.resolveService(next, rl) }.onFailure { done() }
    }

    @Synchronized
    private fun done() { resolving = false; pump() }

    private companion object { const val TAG = "AerioSetup" }
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
        val syncOn = runCatching { prefs.syncProviderOnce() == DispatcharrSyncManager.PROVIDER }.getOrDefault(false)
        return Source(row, isD, choices, syncOn)
    }

    suspend fun buildPayload(playlistId: String, profileIds: Set<String>, turnOnSync: Boolean): SetupProtocol.Payload? {
        val row = profiles.defaultPlaylistRow(playlistId) ?: return null
        val type = when {
            row.sourceType.startsWith("Dispatcharr") -> "dispatcharr"
            row.sourceType == "XtreamCodes" -> "xtream"
            else -> "m3u"
        }
        val offset = runCatching { prefs.catchupTimeOffsetMinutes(playlistId).first() }.getOrDefault(0)
        val playlist = SetupProtocol.PlaylistPart(
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
                else -> SendResult.Failed("The TV answered HTTP $status.")
            }
        }.getOrElse {
            Log.w(TAG, "[SETUP] send failed: ${it.javaClass.simpleName}")
            SendResult.Failed("Could not reach the TV. Check that both devices are on the same network.")
        }
    }

    private companion object { const val TAG = "AerioSetup" }
}
