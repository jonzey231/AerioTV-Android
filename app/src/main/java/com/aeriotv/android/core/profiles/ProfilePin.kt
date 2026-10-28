package com.aeriotv.android.core.profiles

import android.content.Context
import com.aeriotv.android.core.security.CredentialCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional per-profile PIN (Profiles phase 2a, 2026-10-17). A PIN is either
 * 4 to 6 digits ("Numbers") or 4 to 6 D-pad presses ("Directions"). Only a
 * salted SHA-256 of the normalized sequence is stored, with its type; never
 * the raw PIN, never in logs.
 */
enum class PinType(val label: String) { Numbers("Numbers"), Directions("Directions") }

/** One direction press. The letter is the normalized form. */
enum class PinDirection(val letter: Char, val label: String) {
    Up('U', "Up"), Down('D', "Down"), Left('L', "Left"), Right('R', "Right"),
}

/** Reserved PIN-store id for the device Admin PIN (never a profile id, never synced). */
object DevicePin {
    const val PREFIX = "device-admin|"
    fun id(playlistId: String): String = PREFIX + playlistId
    fun isDevice(id: String): Boolean = id.startsWith(PREFIX)
}

/** Pure PIN rules, unit tested (no Android types). */
object PinRules {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6

    /** Digits for Numbers, U/D/L/R letters for Directions; null when invalid. */
    fun normalize(type: PinType, entries: List<String>): String? {
        if (entries.size !in MIN_LENGTH..MAX_LENGTH) return null
        val out = StringBuilder()
        for (e in entries) {
            val c = when (type) {
                PinType.Numbers -> e.singleOrNull()?.takeIf { it in '0'..'9' }
                PinType.Directions -> PinDirection.entries.firstOrNull {
                    it.name.equals(e, ignoreCase = true) || e.equals(it.letter.toString(), ignoreCase = true)
                }?.letter
            } ?: return null
            out.append(c)
        }
        return out.toString()
    }

    fun hash(salt: ByteArray, profileId: String, type: PinType, normalized: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update("|$profileId|${type.name}|$normalized".toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** v2 (synced): SHA-256 of salt bytes || UTF-8 PIN, lowercase hex. */
    fun hashV2(saltHex: String, normalized: String): String =
        com.aeriotv.android.core.sync.dsync.DsyncPinHash.hash(saltHex, normalized)

    /** Wire names in the Dispatcharr Sync `profiles` key. */
    fun wireType(type: PinType?): String = when (type) {
        PinType.Numbers -> "numbers"
        PinType.Directions -> "directions"
        null -> "none"
    }

    fun typeFromWire(s: String): PinType? = when (s) {
        "numbers" -> PinType.Numbers
        "directions" -> PinType.Directions
        else -> null
    }

    /** Constant-time compare of two hex digests. */
    fun matches(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

/**
 * Wrong-PIN backoff, per profile and per device: 5 wrong entries, then a
 * 30 s wait that doubles each round. Time based only, so no profile (the
 * Admin one included) is ever locked permanently.
 */
data class PinBackoff(val fails: Int = 0, val round: Int = 0, val lockedUntil: Long = 0L) {

    fun remainingSeconds(now: Long): Int =
        if (now >= lockedUntil) 0 else ((lockedUntil - now + 999) / 1000).toInt()

    fun onWrong(now: Long): PinBackoff {
        val f = fails + 1
        if (f < ATTEMPTS) return copy(fails = f)
        val r = round + 1
        return PinBackoff(fails = 0, round = r, lockedUntil = now + lockMs(r))
    }

    fun onOk(): PinBackoff = PinBackoff()

    fun encode(): String = "$fails,$round,$lockedUntil"

    companion object {
        const val ATTEMPTS = 5
        const val BASE_LOCK_MS = 30_000L
        private const val MAX_DOUBLINGS = 10

        fun lockMs(round: Int): Long = BASE_LOCK_MS shl (round - 1).coerceIn(0, MAX_DOUBLINGS)

        fun decode(raw: String?): PinBackoff {
            val p = raw?.split(',') ?: return PinBackoff()
            if (p.size != 3) return PinBackoff()
            return PinBackoff(p[0].toIntOrNull() ?: 0, p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0L)
        }
    }
}

/**
 * Stored PIN records. The record goes through CredentialCipher.
 *
 * v2 (Dispatcharr Sync, docs/DispatcharrSyncSchema.md 4.12): "TYPE:hash:salt"
 * with hash = SHA-256(salt bytes || UTF-8 PIN) and a fresh random 16-byte
 * salt per user, regenerated on every set or change, so the same record can
 * sync and verify on every device.
 * v1 (phase 2a): "TYPE:hash" with the per-device salt. A v1 PIN still
 * verifies, and the first correct entry re-hashes it to v2.
 */
@Singleton
class ProfilePinStore @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: CredentialCipher,
) {
    private val prefs = context.getSharedPreferences("aerio_profiles", Context.MODE_PRIVATE)

    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    /** Bumps on every set, remove, migration, or synced import. */
    val changes: kotlinx.coroutines.flow.StateFlow<Long> = _changes

    @Synchronized
    private fun deviceSalt(): ByteArray {
        cipher.decrypt(prefs.getString(KEY_SALT, null))?.let { hex ->
            if (hex.length == SALT_BYTES * 2) return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        val fresh = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_SALT, cipher.encrypt(fresh.joinToString("") { "%02x".format(it) })).apply()
        return fresh
    }

    /** [salt] null = legacy v1 record. */
    data class Record(val type: PinType, val hash: String, val salt: String?)

    private fun record(profileId: String): Record? {
        val raw = cipher.decrypt(prefs.getString(KEY_PIN + profileId, null)) ?: return null
        val type = PinType.entries.firstOrNull { raw.startsWith(it.name + ":") } ?: return null
        val parts = raw.substringAfter(':').split(':')
        return Record(type, parts[0], parts.getOrNull(1)?.takeIf { it.length == SALT_BYTES * 2 })
    }

    private fun write(profileId: String, type: PinType, hash: String, salt: String) {
        prefs.edit().putString(KEY_PIN + profileId, cipher.encrypt("${type.name}:$hash:$salt")).apply()
    }

    private fun freshSalt(): String =
        ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun hasPin(profileId: String): Boolean = record(profileId) != null
    fun type(profileId: String): PinType? = record(profileId)?.type

    /** The synced form of [profileId]'s PIN, or null (no PIN, or a v1 record not yet re-hashed). */
    fun syncRecord(profileId: String): Record? = record(profileId)?.takeIf { it.salt != null }

    /** True when the PIN is a v1 record that cannot sync until the next correct entry. */
    fun isLegacy(profileId: String): Boolean = record(profileId)?.let { it.salt == null } == true

    /** Adopt a synced record (another device set or changed it). */
    fun importSynced(profileId: String, type: PinType, hash: String, salt: String) {
        val cur = record(profileId)
        if (cur != null && cur.type == type && cur.hash == hash && cur.salt == salt) return
        write(profileId, type, hash, salt)
        // A synced PIN's length is unknown here until the next correct entry.
        prefs.edit().remove(KEY_LEN + profileId).apply()
        _changes.value++
    }

    /** Stored PIN length (dots to show on entry); null = unknown (older record). */
    fun length(profileId: String): Int? {
        if (record(profileId) == null) return null
        return prefs.getInt(KEY_LEN + profileId, 0).takeIf { it in PinRules.MIN_LENGTH..PinRules.MAX_LENGTH }
    }

    private fun saveLength(profileId: String, n: Int) {
        if (prefs.getInt(KEY_LEN + profileId, 0) != n) prefs.edit().putInt(KEY_LEN + profileId, n).apply()
    }

    /** False when [entries] is not a valid PIN of [type]. */
    fun set(profileId: String, type: PinType, entries: List<String>): Boolean {
        val n = PinRules.normalize(type, entries) ?: return false
        val salt = freshSalt()
        write(profileId, type, PinRules.hashV2(salt, n), salt)
        prefs.edit().remove(KEY_BACKOFF + profileId).putInt(KEY_LEN + profileId, n.length).apply()
        _changes.value++
        return true
    }

    fun remove(profileId: String) {
        if (record(profileId) == null && prefs.getString(KEY_BACKOFF + profileId, null) == null) return
        prefs.edit().remove(KEY_PIN + profileId).remove(KEY_BACKOFF + profileId).remove(KEY_LEN + profileId).apply()
        _changes.value++
    }

    fun check(profileId: String, entries: List<String>): Boolean {
        val r = record(profileId) ?: return true
        val n = PinRules.normalize(r.type, entries) ?: return false
        if (r.salt != null) {
            val ok = PinRules.matches(PinRules.hashV2(r.salt, n), r.hash)
            if (ok) saveLength(profileId, n.length)
            return ok
        }
        val ok = PinRules.matches(PinRules.hash(deviceSalt(), profileId, r.type, n), r.hash)
        if (ok) {
            // One-time migration to the synced v2 form.
            val salt = freshSalt()
            write(profileId, r.type, PinRules.hashV2(salt, n), salt)
            saveLength(profileId, n.length)
            _changes.value++
        }
        return ok
    }

    fun backoff(profileId: String): PinBackoff = PinBackoff.decode(prefs.getString(KEY_BACKOFF + profileId, null))
    fun saveBackoff(profileId: String, b: PinBackoff) {
        prefs.edit().putString(KEY_BACKOFF + profileId, b.encode()).apply()
    }

    private companion object {
        const val SALT_BYTES = 16
        const val KEY_SALT = "pin.salt"
        const val KEY_PIN = "pin.rec."
        const val KEY_BACKOFF = "pin.backoff."
        const val KEY_LEN = "pin.len."
    }
}
