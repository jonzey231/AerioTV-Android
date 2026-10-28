package com.aeriotv.android.core.qr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A Dispatcharr user code that arrived from outside a form (a QR image shared
 * to AerioTV). MainActivity offers it; the Dispatcharr connect form takes it
 * once and fills itself. In memory only: the key never touches disk here.
 */
object QrImportBus {
    private val _pending = MutableStateFlow<ScannedCode.Result.UserKey?>(null)
    val pending: StateFlow<ScannedCode.Result.UserKey?> = _pending.asStateFlow()

    fun offer(key: ScannedCode.Result.UserKey) { _pending.value = key }

    fun take(): ScannedCode.Result.UserKey? = _pending.value.also { _pending.value = null }

    /**
     * The last Dispatcharr sign-in made from a user QR, so the step after a
     * successful connect can ask the server whether this user already has
     * AerioTV sync data (restore offer). Taken once; only a match on the key
     * the playlist actually saved counts, so a failed scan followed by a typed
     * key never probes with the scanned one.
     */
    class SignIn(val apiKey: String, val baseUrl: String, val username: String?) {
        override fun toString(): String = "SignIn(baseUrl=$baseUrl, username=$username, apiKey=<redacted>)"
    }

    @Volatile private var lastSignIn: SignIn? = null

    fun noteSignIn(s: SignIn) { lastSignIn = s }

    fun takeSignIn(savedApiKey: String?): SignIn? {
        val s = lastSignIn
        lastSignIn = null
        return s?.takeIf { !savedApiKey.isNullOrBlank() && it.apiKey == savedApiKey.trim() }
    }
}
