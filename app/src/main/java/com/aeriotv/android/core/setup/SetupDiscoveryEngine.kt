package com.aeriotv.android.core.setup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When the sender's `_aeriotv-setup._tcp` browse is refreshed and how a
 * resolve is retried (Apple SetupDiscoveryRules, f108c4a; same timings).
 * Pure so the unit tests can check it.
 *
 * Why: a device that started advertising while the app stayed open was
 * missed until a relaunch. An NSD browse can stop delivering new adverts
 * after its first sweep (a Fold browse never reported a TV that registered
 * later; "no client mapping" in the system log), and a resolve had no
 * timeout, so one resolve that never called back blocked every later one.
 */
object SetupDiscoveryRules {
    /**
     * Gap before refresh number `done + 1` since discovery started: 5, 10,
     * and 20 s, then every 30 s while discovery runs. The first comes fast
     * for a browse that is deaf from the start; the steady pace costs one
     * browse query burst every 30 s.
     */
    fun refreshDelayMs(done: Int): Long = when {
        done < 1 -> 5_000L
        done == 1 -> 10_000L
        done == 2 -> 20_000L
        else -> 30_000L
    }

    enum class RefreshStep {
        /** Nothing is listed, so nothing can flicker: stop the browse and start a new one. */
        RECREATE,
        /** Devices are listed: start a second browse beside the current one. It takes
         *  over on its first result, so the offer never blinks during a refresh. */
        START_PROBE,
        /** The second browse stayed silent for a whole interval: replace it. */
        REPLACE_PROBE,
        /** The second browse stayed silent again while the current one still lists
         *  devices: the current one missed their goodbye, so the silent browse
         *  takes over and the listed devices count as gone. */
        PROBE_TAKES_OVER,
    }

    /** Silent second browses in a row before the listed devices count as gone. */
    const val SILENT_PROBE_LIMIT = 2

    /**
     * @param currentLists the current browse lists at least one advert.
     * @param probeRunning a second browse from the previous refresh is still silent.
     * @param silentProbes silent second browses already replaced since the last takeover.
     */
    fun refreshStep(currentLists: Boolean, probeRunning: Boolean, silentProbes: Int): RefreshStep = when {
        !currentLists -> RefreshStep.RECREATE
        !probeRunning -> RefreshStep.START_PROBE
        silentProbes + 1 >= SILENT_PROBE_LIMIT -> RefreshStep.PROBE_TAKES_OVER
        else -> RefreshStep.REPLACE_PROBE
    }

    /** A browse that just took over reports its adverts one by one: removals wait
     *  this long, then use what it has found. */
    const val TAKEOVER_REMOVAL_HOLD_MS = 2_000L

    /** One resolve attempt gives up after this (a resolve normally answers well under 1 s). */
    const val RESOLVE_TIMEOUT_MS = 4_000L

    /** Attempts per advert per browse; the next refresh's browse tries again after the last. */
    const val RESOLVE_ATTEMPTS = 3

    /** Wait before attempt `attempt + 1`: 1 s, then 2 s. */
    fun resolveRetryDelayMs(afterAttempt: Int): Long = if (afterAttempt <= 1) 1_000L else 2_000L

    /**
     * The receiver an advert describes, or null when it is not one: TXT v must
     * be the protocol version, pk a 32-byte key, role not `source` (a device
     * showing a user QR), and the key not this device's own receiver.
     */
    fun receiverTarget(
        service: String,
        host: String?,
        port: Int,
        txt: Map<String, String?>,
        isOwnPk: (ByteArray) -> Boolean,
    ): SetupTarget? {
        if (host == null) return null
        if (txt[SetupProtocol.TXT_ROLE] == SetupProtocol.ROLE_SOURCE) return null
        if (txt[SetupProtocol.TXT_VERSION] != SetupProtocol.VERSION.toString()) return null
        val pk = txt[SetupProtocol.TXT_PK]?.let(SetupCrypto::unb64)?.takeIf { it.size == 32 } ?: return null
        if (isOwnPk(pk)) return null
        return SetupTarget(
            name = txt[SetupProtocol.TXT_NAME]?.takeIf { it.isNotBlank() } ?: service,
            host = host,
            port = port,
            pk = pk,
            kind = SetupProtocol.normalizeKind(txt[SetupProtocol.TXT_KIND]),
            service = service,
            mode = FixLogin.normalizeMode(txt[SetupProtocol.TXT_MODE]),
            playlistId = FixLogin.normalizePlaylistId(txt[SetupProtocol.TXT_PLAYLIST]),
        )
    }
}

/**
 * The sender's discovery state machine, free of Android so the unit tests
 * drive it with a fake NSD and a virtual clock. [SetupDiscovery] adapts it
 * to NsdManager. Every call (public methods and the [Platform] callbacks)
 * comes on one thread.
 *
 *  - The browse refreshes at 5, 10, and 20 s, then every 30 s, for as long as
 *    discovery runs ([SetupDiscoveryRules.refreshDelayMs]).
 *  - Nothing listed: the browse is recreated. Devices listed: a second browse
 *    starts beside it and takes over on its first result, so the card never
 *    blinks; a goodbye the current browse missed clears the card after two
 *    silent second browses.
 *  - Resolves run one at a time (older NsdManager allows one per app); each
 *    gives up after 4 s and retries twice (1 s, then 2 s). After the last
 *    attempt the advert waits for the next refresh's browse.
 */
class SetupDiscoveryEngine(
    private val platform: Platform,
    private val timers: Timers,
    private val isOwnPk: (ByteArray) -> Boolean = { false },
    private val log: (String) -> Unit = {},
) {
    interface Platform {
        /** Starts browse [id]; adverts come back through [found], [lost], and [browseFailed]. */
        fun startBrowse(id: Int)
        fun stopBrowse(id: Int)
        /** Resolves [service] as browse [browse] reported it; answers [resolved] or [resolveFailed] with [serial]. */
        fun resolve(serial: Int, browse: Int, service: String)
        /** Gives up on attempt [serial]; any late answer is ignored. */
        fun cancelResolve(serial: Int)
    }

    fun interface Timers {
        /** Runs [block] after [ms] on the engine's thread; the result cancels it. */
        fun after(ms: Long, block: () -> Unit): () -> Unit
    }

    private val _tvs = MutableStateFlow<List<SetupTarget>>(emptyList())
    val tvs: StateFlow<List<SetupTarget>> = _tvs.asStateFlow()

    private var running = false
    private var nextBrowseId = 0
    /** The browse whose adverts count. */
    private var browse: Int? = null
    /** A second browse a refresh started while devices are listed. */
    private var probe: Int? = null
    /** Instance names the current browse has found and not lost. */
    private val found = LinkedHashSet<String>()
    private var silentProbes = 0
    private var refreshes = 0
    private var cancelRefresh: (() -> Unit)? = null
    /** After a takeover, removals of adverts the new browse has not found yet wait. */
    private var cancelHold: (() -> Unit)? = null
    private val holding get() = cancelHold != null

    /** Instance name -> resolved receiver. */
    private val rows = LinkedHashMap<String, SetupTarget>()

    private class Attempt(val service: String, val attempt: Int)
    private class Active(val service: String, val attempt: Int, val serial: Int, val cancelTimeout: () -> Unit)
    private val queue = ArrayDeque<Attempt>()
    private var active: Active? = null
    private var serial = 0
    /** Adverts waiting before their next attempt. */
    private val waits = HashMap<String, () -> Unit>()
    /** Adverts whose attempts ran out under the current browse. */
    private val gaveUp = HashSet<String>()

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        refreshes = 0
        browse = newBrowse()
        log("[SETUP] discovery started")
        scheduleRefresh()
    }

    fun stop() {
        if (!running) return
        running = false
        cancelRefresh?.invoke(); cancelRefresh = null
        cancelHold?.invoke(); cancelHold = null
        browse?.let(platform::stopBrowse); browse = null
        probe?.let(platform::stopBrowse); probe = null
        active?.let { it.cancelTimeout(); platform.cancelResolve(it.serial) }; active = null
        waits.values.forEach { it() }; waits.clear()
        queue.clear(); gaveUp.clear(); found.clear(); rows.clear()
        silentProbes = 0
        publish()
        log("[SETUP] discovery stopped")
    }

    // ---- Browse ----

    private fun newBrowse(): Int {
        val id = ++nextBrowseId
        platform.startBrowse(id)
        return id
    }

    private fun scheduleRefresh() {
        cancelRefresh = timers.after(SetupDiscoveryRules.refreshDelayMs(refreshes)) {
            if (!running) return@after
            refreshes++
            refresh(refreshes)
            scheduleRefresh()
        }
    }

    private fun refresh(n: Int) {
        val step = SetupDiscoveryRules.refreshStep(found.isNotEmpty(), probe != null, silentProbes)
        if (n <= 3 || n % 10 == 0 || step == SetupDiscoveryRules.RefreshStep.PROBE_TAKES_OVER) {
            log("[SETUP] discovery refresh n=$n $step listed=${found.size}")
        }
        when (step) {
            SetupDiscoveryRules.RefreshStep.RECREATE -> {
                probe?.let(platform::stopBrowse); probe = null
                silentProbes = 0
                browse?.let(platform::stopBrowse)
                browse = newBrowse()
                found.clear(); gaveUp.clear()
                dropAbsent()
            }
            SetupDiscoveryRules.RefreshStep.START_PROBE -> probe = newBrowse()
            SetupDiscoveryRules.RefreshStep.REPLACE_PROBE -> {
                silentProbes++
                probe?.let(platform::stopBrowse)
                probe = newBrowse()
            }
            SetupDiscoveryRules.RefreshStep.PROBE_TAKES_OVER -> {
                // Fresh browses heard nothing twice while this one still lists devices: it
                // missed their goodbye. The silent browse takes over with nothing found.
                browse?.let(platform::stopBrowse)
                browse = probe ?: newBrowse()
                probe = null
                silentProbes = 0
                found.clear(); gaveUp.clear()
                cancelHold?.invoke(); cancelHold = null
                dropAbsent()
            }
        }
    }

    /** Browse [id] found [service]. */
    fun found(id: Int, service: String) {
        if (!running) return
        if (id == probe) {
            // The refresh browse heard the network: it replaces the current one. Its adverts
            // arrive one by one, so removals wait briefly and then use what it found.
            browse?.let(platform::stopBrowse)
            browse = id
            probe = null
            silentProbes = 0
            found.clear(); gaveUp.clear()
            cancelHold?.invoke()
            cancelHold = timers.after(SetupDiscoveryRules.TAKEOVER_REMOVAL_HOLD_MS) {
                cancelHold = null
                if (running) dropAbsent()
            }
            log("[SETUP] discovery refreshed: new browse took over")
        }
        if (id != browse) return
        if (!found.add(service) && (rows.containsKey(service) || isBusy(service))) return
        if (service in gaveUp || isBusy(service)) return
        queue.addLast(Attempt(service, 1))
        pump()
    }

    /** Browse [id] lost [service] (its goodbye arrived). */
    fun lost(id: Int, service: String) {
        if (!running || id != browse) return
        found.remove(service)
        gaveUp.remove(service)
        cancelWork(service)
        rows.remove(service)?.let { log("[SETUP] tv gone key=${it.fingerprint}") }
        publish()
    }

    /** Browse [id] could not start. A failed probe is dropped; a failed current browse
     *  has found nothing, so the next refresh recreates it. */
    fun browseFailed(id: Int, code: Int) {
        if (!running) return
        when (id) {
            probe -> { log("[SETUP] discovery probe stopped $code"); platform.stopBrowse(id); probe = null }
            browse -> log("[SETUP] discovery failed $code")
        }
    }

    /** Rows and work for adverts the current browse no longer lists (not while a takeover holds removals). */
    private fun dropAbsent() {
        if (holding) return
        for (s in rows.keys.filter { it !in found }) {
            rows.remove(s)?.let { log("[SETUP] tv gone key=${it.fingerprint}") }
        }
        for (s in (waits.keys + queue.map { it.service } + listOfNotNull(active?.service)).filter { it !in found }) {
            cancelWork(s)
        }
        publish()
    }

    // ---- Resolve ----

    private fun isBusy(service: String) =
        active?.service == service || service in waits || queue.any { it.service == service }

    private fun cancelWork(service: String) {
        queue.removeAll { it.service == service }
        waits.remove(service)?.invoke()
        val a = active
        if (a != null && a.service == service) {
            a.cancelTimeout()
            platform.cancelResolve(a.serial)
            active = null
            pump()
        }
    }

    private fun pump() {
        if (!running || active != null) return
        val b = browse ?: return
        while (true) {
            val next = queue.removeFirstOrNull() ?: return
            if (next.service !in found) continue
            val s = ++serial
            val cancel = timers.after(SetupDiscoveryRules.RESOLVE_TIMEOUT_MS) { resolveFailed(s, "timeout") }
            active = Active(next.service, next.attempt, s, cancel)
            platform.resolve(s, b, next.service)
            return
        }
    }

    /** Attempt [serial] answered: [target] is the receiver, or null when the advert is not one. */
    fun resolved(serial: Int, target: SetupTarget?) {
        val a = active ?: return
        if (a.serial != serial) return
        a.cancelTimeout()
        active = null
        val service = a.service
        val old = rows[service]
        when {
            target == null -> if (rows.remove(service) != null) log("[SETUP] tv dropped (not a receiver) service=$service")
            old != null && same(old, target) -> {}
            else -> {
                if (old != null && !old.pk.contentEquals(target.pk)) {
                    log("[SETUP] tv key changed old=${old.fingerprint} new=${target.fingerprint}")
                }
                // One row per address: a device that came back under a new instance name replaces its old row.
                rows.entries.removeAll { it.key != service && it.value.key == target.key }
                rows[service] = target
                log("[SETUP] tv found key=${target.fingerprint}" + if (a.attempt > 1) " attempt=${a.attempt}" else "")
            }
        }
        publish()
        pump()
    }

    /** Attempt [serial] failed or timed out. */
    fun resolveFailed(serial: Int, reason: String) {
        val a = active ?: return
        if (a.serial != serial) return
        a.cancelTimeout()
        platform.cancelResolve(serial)
        active = null
        val limit = SetupDiscoveryRules.RESOLVE_ATTEMPTS
        if (a.attempt >= limit) {
            gaveUp.add(a.service)
            log("[SETUP] resolve failed ${a.service}: $reason, attempt ${a.attempt} of $limit, next refresh tries again")
        } else {
            val delay = SetupDiscoveryRules.resolveRetryDelayMs(a.attempt)
            log("[SETUP] resolve failed ${a.service}: $reason, attempt ${a.attempt} of $limit, retry in ${delay / 1000} s")
            val service = a.service
            val next = a.attempt + 1
            waits[service] = timers.after(delay) {
                waits.remove(service)
                if (running && service in found) {
                    queue.addLast(Attempt(service, next))
                    pump()
                }
            }
        }
        pump()
    }

    private fun same(a: SetupTarget, b: SetupTarget) =
        a.name == b.name && a.host == b.host && a.port == b.port && a.kind == b.kind &&
            a.service == b.service && a.pk.contentEquals(b.pk) && a.mode == b.mode && a.playlistId == b.playlistId

    private fun publish() {
        val next = rows.values.sortedBy { it.name.lowercase() }
        if (next != _tvs.value) _tvs.value = next
    }
}
