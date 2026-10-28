package com.aeriotv.android.core.qr

/**
 * Hand-off from the Dispatcharr connect form to the step after it. In memory
 * only: the key never touches disk here.
 */
object QrImportBus {
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
