package com.aeriotv.android.core.data.repository

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext

/**
 * One pass per key at a time: a caller that finds a pass running for its key
 * joins it and gets that pass's answer instead of starting a second one.
 *
 * A call made from INSIDE the running pass for the same key never joins it.
 * Joining would wait on the pass that is waiting on the call, forever. That
 * was the stuck Edit Playlist save of 2026-11-12: the save's capability probe
 * ran an identity repair, the repair reloaded the channels, and the reload's
 * own probe joined the probe that was running the repair, so neither ever
 * finished. Such a call gets [single]'s `reentered` answer at once.
 *
 * Only the pass's own coroutine and its children count as inside. Work the
 * pass launches into another scope is outside, and joins as usual.
 */
internal class SingleFlight<K : Any> {
    private val running = ConcurrentHashMap<K, CompletableDeferred<Boolean>>()

    private class Inside(val keys: Set<Any>) : AbstractCoroutineContextElement(Inside) {
        companion object Key : CoroutineContext.Key<Inside>
    }

    /** True while the calling coroutine is inside the pass for [key]. */
    suspend fun isInside(key: K): Boolean = coroutineContext[Inside]?.keys?.contains(key) == true

    /** True while a pass for [key] is running. */
    fun isRunning(key: K): Boolean = running.containsKey(key)

    suspend fun single(key: K, reentered: () -> Boolean, pass: suspend () -> Boolean): Boolean {
        val outer = coroutineContext[Inside]?.keys.orEmpty()
        if (key in outer) return reentered()
        val pending = CompletableDeferred<Boolean>()
        val other = running.putIfAbsent(key, pending)
        if (other != null) return other.await()
        return try {
            val result = withContext(Inside(outer + key)) { pass() }
            pending.complete(result)
            result
        } catch (t: Throwable) {
            // Includes cancellation: joiners must never hang on a dropped pass.
            pending.complete(false)
            throw t
        } finally {
            running.remove(key, pending)
        }
    }
}
