package com.aeriotv.android.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM encrypt-at-rest for the handful of genuinely sensitive strings the
 * app persists: the Dispatcharr/Xtream apiKey + username + password (Room
 * `playlists` columns) and the Drive OAuth access token + TMDB key (DataStore).
 * Audit task #53 (2026-06-30 source audit, HIGH "credentials in cleartext at
 * rest" cluster).
 *
 * The symmetric key lives in the AndroidKeyStore (`aerio_credential_key_v1`),
 * generated lazily on first use, NOT bound to user authentication so it survives
 * reboots and lock-screen changes. The key is non-exportable: ciphertext can
 * only be decrypted on this device by this app. That aligns the failure modes of
 * the key and the data it protects: clearing app data or uninstalling drops both
 * the Keystore entry AND the Room DB / DataStore, so there is no NEW data-loss
 * path beyond what already wipes the credentials.
 *
 * Wire format: `"aerioenc:v1:" + base64(iv ‖ ciphertext+tag)`. The 12-byte GCM
 * IV is randomised per encryption by the Keystore (randomizedEncryptionRequired
 * default), read back from `Cipher.iv`, and prepended so [decrypt] can recover
 * it. The 128-bit GCM tag authenticates the ciphertext, so a corrupted or
 * wrong-key value fails closed (returns null) rather than yielding garbage.
 *
 * Backward / forward tolerance:
 *  - [decrypt] of an UNTAGGED value returns it unchanged. That makes the rollout
 *    a no-op for rows/prefs written by older builds (legacy plaintext) and lets
 *    the encrypting layer sit transparently in front of a not-yet-migrated row.
 *  - [encrypt] of an already-tagged value returns it unchanged (defensive: the
 *    architecture guarantees only cleartext reaches encrypt -- every consumer
 *    reads through the decrypting layer first -- but this prevents an accidental
 *    double-encryption from a future code path silently breaking auth).
 *  - On any crypto failure [encrypt] falls back to returning the plaintext (so a
 *    transient Keystore hiccup never drops a credential) and [decrypt] returns
 *    null (so a lost key degrades to "please sign in again", never a crash).
 */
@Singleton
class CredentialCipher @Inject constructor() {

    @Volatile private var cachedKey: SecretKey? = null
    private val keyLock = Any()

    /**
     * Encrypt [plaintext] for storage. null/empty pass through unchanged (the
     * read sites already treat blank as "no credential"); an already-tagged
     * value passes through to avoid double-encryption. Never returns null for a
     * non-null input.
     */
    fun encrypt(plaintext: String?): String? {
        if (plaintext.isNullOrEmpty()) return plaintext
        if (plaintext.startsWith(PREFIX)) return plaintext
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ct, 0, combined, iv.size, ct.size)
            PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (t: Throwable) {
            // Storing plaintext is strictly better than dropping the credential
            // (which would silently break the user's server). The bulk re-encrypt
            // pass + the next successful write will pick it up later.
            Log.e(TAG, "encrypt failed; persisting value unencrypted this time", t)
            plaintext
        }
    }

    /**
     * Decrypt a stored value. An untagged value (legacy plaintext, or empty)
     * passes through unchanged. A tagged value that fails authentication (key
     * lost / corruption) returns null so callers fall back to re-auth.
     */
    fun decrypt(stored: String?): String? {
        if (stored == null) return null
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val combined = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            if (combined.size <= IV_LEN) return null
            val iv = combined.copyOfRange(0, IV_LEN)
            val ct = combined.copyOfRange(IV_LEN, combined.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.e(TAG, "decrypt failed; credential unrecoverable on this device", t)
            null
        }
    }

    /** Whether [stored] is a value [encrypt] produced (tagged), as opposed to plain text. */
    fun isEncrypted(stored: String?): Boolean = stored?.startsWith(PREFIX) == true

    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(keyLock) {
            cachedKey?.let { return it }
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            val key = existing ?: generateKey()
            cachedKey = key
            return key
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "CredentialCipher"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "aerio_credential_key_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val PREFIX = "aerioenc:v1:"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}
