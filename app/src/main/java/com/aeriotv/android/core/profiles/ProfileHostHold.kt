package com.aeriotv.android.core.profiles

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Profile sign-ins after a server address change (security fix A-APP-1,
 * shared spec with Apple, 2026-11-11). Pure rules, no Android types, so the
 * JVM tests drive them directly.
 *
 * Each non-default profile keeps its own Dispatcharr API key and password,
 * attached to the playlist id. Edit Playlist, a device setup that updates a
 * playlist in place, and a Google Drive pull can move a playlist to another
 * scheme, host, or port. The old server's keys and passwords must not follow
 * it there until the new address proves to be the same server: users/me with
 * the DEFAULT profile's sign-in answers with the same Dispatcharr user id and
 * username this device last recorded for that profile on the playlist.
 *
 * While a profile is held, its stored sign-in is never laid over requests,
 * used for a switch, a token login, a Dispatcharr Sync session, or Show User
 * QR Code, and never put in a setup handoff payload.
 */
object ProfileHostHold {

    /** "scheme://host:port", lowercased, default port filled in; "" when blank or unparseable. */
    fun routeKey(raw: String?): String {
        var v = raw?.trim().orEmpty()
        if (v.isEmpty()) return ""
        if (!v.startsWith("http://", true) && !v.startsWith("https://", true)) v = "http://$v"
        val uri = runCatching { java.net.URI(v) }.getOrNull() ?: return ""
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return ""
        val scheme = (uri.scheme ?: "http").lowercase()
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        return "$scheme://$host:$port"
    }

    /**
     * True when either route of the playlist (server URL or local URL) now
     * points at another scheme, host, or port. A path-only change is not a
     * route change.
     */
    fun routeChanged(oldBase: String?, newBase: String?, oldLan: String?, newLan: String?): Boolean =
        routeKey(oldBase) != routeKey(newBase) || routeKey(oldLan) != routeKey(newLan)

    /** The route keys a playlist uses (server URL, and the local URL when set). */
    fun routes(base: String?, lan: String?): Set<String> =
        listOf(routeKey(base), routeKey(lan)).filter { it.isNotEmpty() }.toSet()

    /** The default profile's Dispatcharr user on a playlist, as users/me answered. */
    data class Identity(val userId: Int, val username: String) {
        val known: Boolean get() = userId > 0 && username.isNotBlank()
    }

    /** What users/me with the default profile's sign-in answered at an address. */
    sealed interface Answer {
        data class Me(val identity: Identity) : Answer
        data object Rejected : Answer
        data object Unreachable : Answer
        data object NoKey : Answer
    }

    sealed interface Verdict {
        data object SameServer : Verdict
        data class NotSame(val reason: String) : Verdict
    }

    /**
     * The same-server decision. Same server only when users/me answered with
     * the same user id AND username that were recorded before the change. No
     * record, a rejected key, no key, no answer, or another user all keep the
     * hold.
     */
    fun decide(recorded: Identity?, answer: Answer): Verdict = when {
        recorded == null || !recorded.known -> Verdict.NotSame("no-record")
        answer is Answer.Me ->
            if (answer.identity.userId == recorded.userId && answer.identity.username.trim() == recorded.username.trim()) {
                Verdict.SameServer
            } else {
                Verdict.NotSame("different-user")
            }
        answer is Answer.Rejected -> Verdict.NotSame("rejected")
        answer is Answer.NoKey -> Verdict.NotSame("no-key")
        else -> Verdict.NotSame("network")
    }

    /** Routes the playlist uses now that no check has proven yet: what the check must still call. */
    fun unproven(current: Set<String>, trusted: Set<String>): Set<String> = current - trusted

    /** The profiles a route change holds: every non-default profile with a stored key or password. */
    fun toHold(profiles: List<DispatcharrProfile>): Set<String> =
        profiles.filter { !it.isDefault && (!it.apiKey.isNullOrBlank() || !it.password.isNullOrBlank()) }
            .map { it.id }.toSet()

    data class SignIn(val apiKey: String?, val username: String?, val password: String?)

    /** The stored sign-in a switch, a token login, or a re-check may send for [p]: none while held. */
    fun usable(p: DispatcharrProfile, held: Boolean): SignIn =
        if (held && !p.isDefault) SignIn(null, p.username, null) else SignIn(p.apiKey, p.username, p.password)

    /** [p] as the setup payload, Show User QR Code, and "saved sign-in" checks read it: no stored sign-in while held. */
    fun withoutHeldSignIn(p: DispatcharrProfile, held: Boolean): DispatcharrProfile =
        if (held && !p.isDefault) p.copy(apiKey = null, password = null) else p

    /**
     * A save that moves the playlist sends the playlist's own sign-in only.
     * Edit Playlist shows the ACTIVE profile's sign-in when a non-default
     * profile is active, so a request carrying a held profile's key,
     * password, or username is that profile's sign-in: the playlist's own one
     * ([own]) replaces it whole. Anything else (the playlist's own sign-in, or
     * one typed fresh) goes as it is.
     */
    fun scrub(request: SignIn, own: SignIn, held: List<SignIn>): SignIn {
        fun key(a: String?, b: String?) = !a.isNullOrBlank() && !b.isNullOrBlank() && a.trim() == b.trim()
        fun user(a: String?, b: String?) = !a.isNullOrBlank() && !b.isNullOrBlank() && a.trim().equals(b.trim(), ignoreCase = true)
        fun pass(a: String?, b: String?) = !a.isNullOrEmpty() && !b.isNullOrEmpty() && a == b
        val carriesHeld = held.any { h ->
            key(request.apiKey, h.apiKey) || pass(request.password, h.password) ||
                (user(request.username, h.username) && !user(request.username, own.username))
        }
        return if (carriesHeld) own else request
    }
}

/** A switch to a held profile: nothing was sent, and the "Sign in to Dispatcharr" form opens instead. */
class ProfileSignInHeld : IllegalStateException("Sign in again to use this profile.")

/** One playlist's hold: the held profiles, the routes trusted for them, and the identity to compare against. */
@Serializable
data class HostHoldRecord(
    val profileIds: Set<String> = emptySet(),
    /** Route keys the held sign-ins were used at before the change, plus every route a check has proven since. */
    val trusted: Set<String> = emptySet(),
    /** The default profile's identity recorded before the change; 0 and "" = none was recorded. */
    val expectedUserId: Int = 0,
    val expectedUsername: String = "",
) {
    fun expected(): ProfileHostHold.Identity? =
        ProfileHostHold.Identity(expectedUserId, expectedUsername).takeIf { it.known }
}

@Serializable
data class RecordedIdentity(
    val userId: Int,
    val username: String,
    /** The route key that answered ("" when not known). */
    val route: String = "",
) {
    fun identity(): ProfileHostHold.Identity = ProfileHostHold.Identity(userId, username)
}

@Serializable
data class HostHoldState(
    /** Playlist id -> its hold. */
    val holds: Map<String, HostHoldRecord> = emptyMap(),
    /** Playlist id -> the default profile's identity from the last successful users/me with no hold in place. */
    val identities: Map<String, RecordedIdentity> = emptyMap(),
    /**
     * Non-default profile id -> the default profile's identity that users/me
     * answered at the address where that profile last signed in or was
     * verified: which server its key belongs to. A check lifts a profile
     * only when its tag equals the identity recorded before the change.
     */
    val tags: Map<String, RecordedIdentity> = emptyMap(),
)

/**
 * The persisted holds and recorded identities. Every change is written
 * through [Backing] before it is visible, so a relaunch reads exactly what was
 * in force. Holds carry no keys or passwords: profile ids, route keys, and the
 * Dispatcharr user id and username only.
 */
class HostHoldBook(private val backing: Backing) {

    interface Backing {
        fun read(): String?
        fun write(value: String)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile var state: HostHoldState = load()
        private set

    private fun load(): HostHoldState =
        backing.read()?.let { raw -> runCatching { json.decodeFromString(HostHoldState.serializer(), raw) }.getOrNull() }
            ?: HostHoldState()

    @Synchronized
    private fun commit(next: HostHoldState) {
        if (next == state) return
        backing.write(json.encodeToString(HostHoldState.serializer(), next))
        state = next
    }

    fun heldProfileIds(): Set<String> = state.holds.values.flatMapTo(HashSet()) { it.profileIds }

    fun isHeld(profileId: String): Boolean = state.holds.values.any { profileId in it.profileIds }

    fun hold(playlistId: String): HostHoldRecord? = state.holds[playlistId]

    fun recorded(playlistId: String): ProfileHostHold.Identity? =
        state.identities[playlistId]?.identity()

    /**
     * The record as a live answer for [playlistId] now: only with no hold
     * freezing it, and only when it came from a route the playlist still uses.
     */
    fun liveRecord(playlistId: String, currentRoutes: Set<String>): ProfileHostHold.Identity? =
        if (playlistId in state.holds) null
        else state.identities[playlistId]?.takeIf { it.route in currentRoutes }?.identity()?.takeIf { it.known }

    fun tagOf(profileId: String): ProfileHostHold.Identity? = state.tags[profileId]?.identity()?.takeIf { it.known }

    /** Tag [profileId] with the default identity live at the address it just signed in at or was verified at; null clears it. */
    @Synchronized
    fun tag(profileId: String, identity: ProfileHostHold.Identity?) {
        val next = if (identity == null || !identity.known) state.tags - profileId
        else state.tags + (profileId to RecordedIdentity(identity.userId, identity.username.trim()))
        commit(state.copy(tags = next))
    }

    /** Profiles from before tags existed get the current record the first time one is recorded. */
    @Synchronized
    fun tagUntagged(profileIds: Collection<String>, identity: ProfileHostHold.Identity) {
        if (!identity.known) return
        val add = profileIds.filter { it !in state.tags }
        if (add.isEmpty()) return
        val t = RecordedIdentity(identity.userId, identity.username.trim())
        commit(state.copy(tags = state.tags + add.associateWith { t }))
    }

    /**
     * Hold [profileIds] on [playlistId]. A new hold trusts [priorRoutes] (the
     * routes before this change) and freezes the identity recorded so far,
     * the snapshot every check compares against; a hold already in place
     * keeps both and only gains profiles. Null when there is nothing to hold.
     * Callers place it before the change sends anything, so no users/me made
     * by the change itself can reach the record first.
     */
    @Synchronized
    fun hold(playlistId: String, profileIds: Set<String>, priorRoutes: Set<String>): HostHoldRecord? {
        val existing = state.holds[playlistId]
        val ids = existing?.profileIds.orEmpty() + profileIds
        if (ids.isEmpty()) return null
        val rec = existing?.copy(profileIds = ids) ?: run {
            val id = state.identities[playlistId]
            HostHoldRecord(ids, priorRoutes, id?.userId ?: 0, id?.username.orEmpty())
        }
        commit(state.copy(holds = state.holds + (playlistId to rec)))
        return rec
    }

    /**
     * Record the default profile's identity. Frozen while ANY profile on the
     * playlist is held: every check compares against the identity recorded
     * before the route change, never against an answer from the new address
     * (otherwise another server's user would overwrite it and the next check
     * would compare it with itself). Recording resumes once nothing on the
     * playlist is held.
     */
    @Synchronized
    fun record(playlistId: String, identity: ProfileHostHold.Identity, route: String = ""): Boolean {
        if (!identity.known || playlistId in state.holds) return false
        commit(state.copy(identities = state.identities + (playlistId to RecordedIdentity(identity.userId, identity.username.trim(), route))))
        return true
    }

    sealed interface Outcome {
        /** No hold on the playlist: the identity was recorded (or was already the record). */
        data object Recorded : Outcome
        /** Nothing to decide: no usable answer with no hold, or a route that is stale or already trusted. */
        data object Ignored : Outcome
        /** [route] answered as the recorded user: it is the same server. */
        data object Proven : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * One users/me answer with the default profile's sign-in, at [route],
     * for a playlist whose routes are now [currentRoutes]. With no hold it is
     * recorded; with a hold it is checked against the frozen identity and
     * never recorded.
     */
    @Synchronized
    fun onAnswer(playlistId: String, route: String, currentRoutes: Set<String>, answer: ProfileHostHold.Answer): Outcome {
        val rec = state.holds[playlistId]
        if (rec == null) {
            val me = (answer as? ProfileHostHold.Answer.Me)?.identity ?: return Outcome.Ignored
            // An answer from an address the playlist no longer uses is not this playlist's server now.
            if (!me.known || route !in currentRoutes) return Outcome.Ignored
            record(playlistId, me, route)
            return Outcome.Recorded
        }
        if (route !in currentRoutes || route in rec.trusted) return Outcome.Ignored
        return when (val v = ProfileHostHold.decide(rec.expected(), answer)) {
            ProfileHostHold.Verdict.SameServer -> { trust(playlistId, route); Outcome.Proven }
            is ProfileHostHold.Verdict.NotSame -> Outcome.Failed(v.reason)
        }
    }

    /** True when [playlistId] is held and every route it uses now is trusted: the hold can lift. */
    fun allProven(playlistId: String, currentRoutes: Set<String>): Boolean =
        state.holds[playlistId]?.let { ProfileHostHold.unproven(currentRoutes, it.trusted).isEmpty() } ?: false

    /**
     * The playlist was deleted: its recorded identity goes, and so does its
     * hold, except for held profiles in [movedProfiles] (a sync merge moved
     * them to another playlist), which stay held until they sign in again.
     */
    @Synchronized
    fun forgetPlaylist(playlistId: String, movedProfiles: Set<String>) {
        val kept = state.holds[playlistId]?.let { it.copy(profileIds = it.profileIds intersect movedProfiles) }
        commit(
            state.copy(
                identities = state.identities - playlistId,
                holds = if (kept == null || kept.profileIds.isEmpty()) state.holds - playlistId else state.holds + (playlistId to kept),
            ),
        )
    }

    /** A check proved [route] is the same server. */
    @Synchronized
    fun trust(playlistId: String, route: String) {
        val rec = state.holds[playlistId] ?: return
        commit(state.copy(holds = state.holds + (playlistId to rec.copy(trusted = rec.trusted + route))))
    }

    /**
     * The check passed for [playlistId] (every route in [currentRoutes] is
     * trusted): lift every held profile whose tag equals the snapshot (its key
     * belongs to this server). A profile tagged with another server's
     * identity, or with none, stays held and must sign in again. Nothing lifts
     * without a snapshot or before every route is proven. Returns the ids lifted.
     */
    @Synchronized
    fun liftMatching(playlistId: String, currentRoutes: Set<String>): Set<String> {
        val rec = state.holds[playlistId] ?: return emptySet()
        val snapshot = rec.expected() ?: return emptySet()
        if (ProfileHostHold.unproven(currentRoutes, rec.trusted).isNotEmpty()) return emptySet()
        val lift = rec.profileIds.filterTo(HashSet()) { tagOf(it) == snapshot }
        if (lift.isEmpty()) return emptySet()
        val left = rec.profileIds - lift
        commit(state.copy(holds = if (left.isEmpty()) state.holds - playlistId else state.holds + (playlistId to rec.copy(profileIds = left))))
        return lift
    }

    /** Lift [profileId] only (it signed in again, or it is gone); returns its playlist id when it was held. */
    @Synchronized
    fun liftProfile(profileId: String): String? {
        val (pid, rec) = state.holds.entries.firstOrNull { profileId in it.value.profileIds }?.toPair() ?: return null
        val left = rec.profileIds - profileId
        commit(state.copy(holds = if (left.isEmpty()) state.holds - pid else state.holds + (pid to rec.copy(profileIds = left))))
        return pid
    }
}
