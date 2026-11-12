package com.aeriotv.android.core.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets a held key ([DispatcharrRequestGate]) recover on its own, slowly
 * (owner 2026-11-09: "We don't want to just block access but we don't want
 * constant retries that force a server-side block from repeated attempts
 * either."):
 *
 *  - one probe 1, 5, and 15 minutes after the rejection, then every 30
 *    minutes; also one when the app comes to the foreground and one on a
 *    user refresh, at most one probe per key per 60 s across all of them;
 *  - never inside a 429 pause (the probe waits for it to pass);
 *  - a key-only playlist's probe is ONE /api/core/version/ request with the
 *    key; a password playlist's probe is one re-login through
 *    [DispatcharrLoginGate] (its own 3-per-minute throttle and parks apply);
 *  - accepted: the hold and its prompt clear and [recovered] tells the
 *    playlist and On Demand screens to reload, the same reload a save runs;
 *    rejected or unknown: stays held, no new prompt, schedule continues.
 *
 * The timer runs only while the app is in the foreground (process lifecycle
 * STARTED), on phones, tablets, and TV alike, so it never keeps a
 * backgrounded or dying process busy. The schedule itself is pure logic in
 * [DispatcharrRequestGate] (fake-clock tests).
 */
@Singleton
class DispatcharrKeyRecheck @Inject constructor(
    private val client: DispatcharrClient,
    private val broker: DispatcharrAuthBroker,
    private val dao: PlaylistDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loop: Job? = null

    private val _recovered = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Playlist ids whose held key just proved good again: reload them. */
    val recovered: SharedFlow<String> = _recovered

    init {
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    startLoop()
                    probeAllOnDemand("foreground")
                }

                override fun onStop(owner: LifecycleOwner) {
                    loop?.cancel()
                    loop = null
                }
            })
        }
    }

    /** A user refresh (pull to refresh, Refresh Playlist, Test Connection, Retry). */
    fun userRefresh() = probeAllOnDemand("user refresh")

    private fun probeAllOnDemand(reason: String) {
        DispatcharrRequestGate.rechecks().forEach { r ->
            if (DispatcharrRequestGate.claimOnDemand(r, loginBlockedUntil(r))) {
                scope.launch { runProbe(r, reason) }
            }
        }
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                val next = DispatcharrRequestGate.earliestScheduledAt(::loginBlockedUntil)
                val now = DispatcharrRequestGate.clock()
                // Nothing held: look again in a minute (cheap, memory only).
                val wait = if (next == null) IDLE_POLL_MS else (next - now).coerceIn(1_000, IDLE_POLL_MS)
                delay(wait)
                DispatcharrRequestGate.rechecks().forEach { r ->
                    if (DispatcharrRequestGate.claimScheduled(r, loginBlockedUntil(r))) runProbe(r, "schedule #${r.scheduledDone}")
                }
            }
        }
    }

    /** Password re-check: also wait out a login throttle park. */
    private fun loginBlockedUntil(r: DispatcharrRequestGate.Recheck): Long {
        if (!r.passwordToo) return 0
        val secs = DispatcharrLoginGate.serverParkedSeconds(r.baseUrl)
        return if (secs > 0) DispatcharrRequestGate.clock() + secs * 1000 else 0
    }

    private suspend fun runProbe(r: DispatcharrRequestGate.Recheck, reason: String) {
        var accepted = false
        try {
            val outcome: String
            // The re-login is the held key's own identity only: with another
            // profile active now, its success would say nothing about this
            // key, so the key itself is probed instead (2026-11-12, security).
            val row = if (r.passwordToo) dao.byId(r.playlistId) else null
            if (row != null && row.apiKey?.trim() == r.key.trim()) {
                val fresh = broker.silentRebootstrapApiKey(row)?.trim()?.takeIf { it.isNotEmpty() }
                // Signing in proves the password, not the key it hands back:
                // that key is checked once (it may be the refused one), and a
                // refused new key is held too, so lifting the hold never sets
                // every caller off with a dead key (2026-11-12).
                val keyProbe = fresh?.let { client.probeApiKeyOnce(r.baseUrl, it) }
                accepted = keyProbe is DispatcharrClient.KeyProbe.Accepted
                if (fresh != null && fresh != r.key.trim() && keyProbe == DispatcharrClient.KeyProbe.Rejected) broker.holdRefusedKey(row, fresh)
                outcome = when {
                    accepted -> "accepted (re-login issued a key the server accepts)"
                    fresh != null && keyProbe == DispatcharrClient.KeyProbe.Rejected -> "rejected (signed in, but its key is refused)"
                    DispatcharrLoginGate.isRejected(broker.loginSlot(row)) -> "rejected (login refused)"
                    else -> "unknown"
                }
            } else {
                val probe = client.probeApiKeyOnce(r.baseUrl, r.key)
                accepted = probe is DispatcharrClient.KeyProbe.Accepted
                outcome = when (probe) {
                    is DispatcharrClient.KeyProbe.Accepted -> "accepted"
                    DispatcharrClient.KeyProbe.Rejected -> "rejected"
                    DispatcharrClient.KeyProbe.Unknown -> "unknown"
                }
            }
            Log.i(TAG, "[AUTH] held key re-check for ${r.playlistId.take(8)} ($reason): $outcome")
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "[AUTH] held key re-check for ${r.playlistId.take(8)} ($reason) failed: ${t.javaClass.simpleName}")
        } finally {
            DispatcharrRequestGate.finishProbe(r, accepted)
        }
        if (accepted) {
            broker.invalidateRow(r.playlistId)
            Log.i(TAG, "[AUTH] key for ${r.playlistId.take(8)} accepted again; hold cleared, reloading")
            _recovered.tryEmit(r.playlistId)
        }
    }

    private companion object {
        const val TAG = "DispatcharrAuthBroker"
        const val IDLE_POLL_MS = 60_000L
    }
}
