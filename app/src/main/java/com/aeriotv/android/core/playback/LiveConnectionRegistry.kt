package com.aeriotv.android.core.playback

import android.os.SystemClock
import android.util.Log
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Single-connection invariant for live ingests (Apple parity 708f5a1,
 * LiveConnectionRegistry.waitForRelease).
 *
 * Within one player the holder already closes a superseded source before the
 * next opens ([AerioExoPlayerHolder] LiveCallTracker gate). Across players it
 * did not: on Apple 2026-11-03 18:03:20 and 18:03:22 a Multiview rebuild
 * mounted fresh tiles that each opened the channel while the previous tile's
 * ingest was still open, so the app held two connections to one channel (a
 * per-user stream limit counts both).
 *
 * Every live open through a client carrying [interceptor] and [eventListener]
 * now waits, on its own loader thread and for at most [WAIT_MS], until no
 * other connection to the same channel is registered, and logs how long it
 * waited and what was still registered at the timeout. Never logs a URL: the
 * channel is named by a short hash of its path.
 */
object LiveConnectionRegistry {
    private const val TAG = "AerioTrace"

    /** Upper bound on the wait for a previous connection to go. */
    const val WAIT_MS = 3_000L

    private val lock = Object()
    private val open = HashMap<Call, String>()

    private fun keyFor(url: HttpUrl): String = url.host + url.encodedPath

    private fun label(key: String): String = "ch#" + Integer.toHexString(key.hashCode())

    /** Playlists and fragmented segments are not long-lived ingests. */
    private fun isIngest(url: HttpUrl): Boolean {
        val path = url.encodedPath.lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".m4s") || path.endsWith(".mp4") ||
            path.endsWith(".aac") || path.endsWith(".vtt")
        ) return false
        return !ForceHls.isForcedHlsUrl(url.toString())
    }

    /** Blocks the calling (loader) thread until no other connection to [key]
     *  is registered, up to [WAIT_MS]. Returns true when the channel is free. */
    private fun waitForRelease(key: String, self: Call): Boolean {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + WAIT_MS
        var waited = false
        val still: List<String>
        synchronized(lock) {
            while (open.any { it.key !== self && it.value == key && !it.key.isCanceled() }) {
                waited = true
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0L) break
                try {
                    lock.wait(left)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            still = open.filter { it.key !== self && it.value == key && !it.key.isCanceled() }
                .map { "open" }
        }
        if (waited) {
            val ms = SystemClock.elapsedRealtime() - started
            Log.i(
                TAG,
                "[SINGLE-CONN] waited $ms ms for the previous connection to ${label(key)} to go" +
                    if (still.isEmpty()) "" else " (timed out, still registered: ${still.size} open)",
            )
        }
        return still.isEmpty()
    }

    private fun register(call: Call, key: String) {
        synchronized(lock) { open[call] = key }
    }

    private fun unregister(call: Call) {
        synchronized(lock) {
            if (open.remove(call) != null) lock.notifyAll()
        }
    }

    /** Application interceptor: wait for the channel to be free, then register. */
    val interceptor = Interceptor { chain ->
        val url = chain.request().url
        if (!isIngest(url)) return@Interceptor chain.proceed(chain.request())
        val key = keyFor(url)
        val call = chain.call()
        waitForRelease(key, call)
        register(call, key)
        val response: Response = try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            unregister(call)
            throw e
        } catch (e: RuntimeException) {
            unregister(call)
            throw e
        }
        response
    }

    /** Unregisters a call when it ends (body consumed or closed), fails, or
     *  is cancelled. */
    val eventListener = object : EventListener() {
        override fun callEnd(call: Call) = unregister(call)
        override fun callFailed(call: Call, ioe: IOException) = unregister(call)
        override fun canceled(call: Call) = unregister(call)
        override fun responseBodyEnd(call: Call, byteCount: Long) = unregister(call)
    }
}
