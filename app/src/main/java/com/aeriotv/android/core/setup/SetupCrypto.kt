package com.aeriotv.android.core.setup

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Set up a TV from your phone (docs/DeviceSetupHandoff.md): the crypto both
 * ends run. Pure JVM (no Android types) so the unit tests exercise exactly
 * this code.
 *
 * X25519 is implemented here (RFC 7748) because the platform `XDH`
 * KeyAgreement only exists from API 33 and minSdk is 26; Apple uses CryptoKit
 * Curve25519.KeyAgreement, which speaks the same raw 32-byte keys. The ladder
 * uses BigInteger and is not constant time; the keys are single-use and live
 * for one LAN pairing window, so timing leakage has nothing durable to leak.
 */
object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASE: ByteArray = ByteArray(32).also { it[0] = 9 }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE)

    fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(32).also(random::nextBytes)

    /** RFC 7748 section 5 X25519(k, u). */
    fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        require(k.size == 32 && u.size == 32) { "X25519 keys are 32 bytes" }
        val kk = k.copyOf()
        kk[0] = (kk[0].toInt() and 248).toByte()
        kk[31] = ((kk[31].toInt() and 127) or 64).toByte()
        val scalar = decodeLe(kk)
        val uu = u.copyOf()
        uu[31] = (uu[31].toInt() and 127).toByte()
        val x1 = decodeLe(uu).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (scalar.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val s1 = da.add(cb).mod(P)
            x3 = s1.multiply(s1).mod(P)
            val s2 = da.subtract(cb).mod(P)
            z3 = x1.multiply(s2.multiply(s2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) {
            x2 = x3
            z2 = z3
        }
        val out = x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P)
        return encodeLe(out)
    }

    private fun decodeLe(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun encodeLe(n: BigInteger): ByteArray {
        val be = n.toByteArray()
        val out = ByteArray(32)
        // BigInteger.toByteArray is big-endian and may carry a sign byte.
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }
}

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }
}

object SetupCrypto {
    const val INFO_PREFIX = "aeriotv-setup-v1"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12

    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    class Sealed(val nonce: ByteArray, val ciphertext: ByteArray)

    fun newKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val priv = X25519.generatePrivateKey(random)
        return KeyPair(priv, X25519.publicKey(priv))
    }

    /** A fresh 6-digit code, zero padded. */
    fun newCode(random: SecureRandom = SecureRandom()): String =
        random.nextInt(1_000_000).toString().padStart(6, '0')

    /** 32 symbols with no 0/O or 1/I lookalikes. */
    private const val FINGERPRINT_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /**
     * 4-character key fingerprint the TV shows next to its code and the phone
     * shows in its TV list: base32 of the first 20 bits of SHA-256(pk). The
     * user compares the two to catch a LAN impostor advertising its own key.
     */
    fun keyFingerprint(pk: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(pk)
        val bits = ((d[0].toInt() and 0xff) shl 12) or ((d[1].toInt() and 0xff) shl 4) or ((d[2].toInt() and 0xff) ushr 4)
        return (0 until 4).map { FINGERPRINT_ALPHABET[(bits ushr (15 - 5 * it)) and 0x1f] }.joinToString("")
    }

    /**
     * AES-256 key both ends derive: HKDF-SHA256 over X25519(own priv, peer
     * pub), salt = the 6 ASCII digits, info = "aeriotv-setup-v1" || phonePk ||
     * tvPk. Both public keys in the info bind the key to this exact pair.
     */
    fun deriveKey(ownPrivate: ByteArray, peerPublic: ByteArray, code: String, phonePk: ByteArray, tvPk: ByteArray): ByteArray {
        val shared = X25519.scalarMult(ownPrivate, peerPublic)
        require(shared.any { it.toInt() != 0 }) { "Invalid peer key" }
        val info = INFO_PREFIX.toByteArray(Charsets.US_ASCII) + phonePk + tvPk
        return Hkdf.sha256(shared, code.toByteArray(Charsets.US_ASCII), info, 32)
    }

    /** AES-256-GCM; the ciphertext carries the 16-byte tag at its end (CryptoKit `combined` minus the nonce). */
    fun seal(key: ByteArray, plaintext: ByteArray, random: SecureRandom = SecureRandom()): Sealed {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return Sealed(nonce, c.doFinal(plaintext))
    }

    /** Null when the tag does not verify (wrong code, wrong key, or tampering). */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        c.doFinal(ciphertext)
    }.getOrNull()

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun b64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Accepts standard or URL-safe base64, padded or not. */
    fun unb64(s: String): ByteArray? = runCatching {
        val norm = s.trim().replace('-', '+').replace('_', '/')
        val padded = norm + "=".repeat((4 - norm.length % 4) % 4)
        Base64.getDecoder().decode(padded)
    }.getOrNull()

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
