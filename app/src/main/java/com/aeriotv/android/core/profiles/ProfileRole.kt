package com.aeriotv.android.core.profiles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-local role of a profile (Profiles phase 2b, 2026-10-17). Set by an
 * Admin profile on the device and synced in the Dispatcharr Sync `profiles`
 * key (`role`). The label follows the Dispatcharr access (owner rule
 * 2026-10-29): a server admin is Admin, any other user Standard or Child.
 */
enum class ProfileRole(val label: String, val wire: String) {
    Admin("Admin", "admin"), User("Standard", "user"), Child("Child", "child");

    companion object {
        /** Dispatcharr admin: superuser, staff, or user_level 10 (the stored level already folds staff and superuser in). */
        fun isServerAdmin(userLevel: Int): Boolean = ProfileLevel.of(userLevel) == ProfileLevel.Admin

        /**
         * Owner rule 2026-10-29: the role follows the Dispatcharr access. A
         * Dispatcharr admin is Admin; every other user is Standard (wire
         * "user") unless chosen as Child.
         */
        fun defaultFor(userLevel: Int): ProfileRole = if (isServerAdmin(userLevel)) Admin else User

        /** The roles a profile at [userLevel] may carry: Admin alone, or Standard and Child. */
        fun optionsFor(userLevel: Int): List<ProfileRole> = optionsFor(isServerAdmin(userLevel))

        /** [serverAdmin] null = level unknown: Standard and Child, never Admin. */
        fun optionsFor(serverAdmin: Boolean?): List<ProfileRole> =
            if (serverAdmin == true) listOf(Admin) else listOf(User, Child)

        /** The role to store for [userLevel] given a [picked] one (null = default). */
        fun resolve(userLevel: Int, picked: ProfileRole?): ProfileRole =
            picked?.takeIf { it in optionsFor(userLevel) } ?: defaultFor(userLevel)

        fun fromWire(s: String?): ProfileRole? = entries.firstOrNull { it.wire == s }
    }
}

fun DispatcharrProfile.effectiveRole(): ProfileRole = ProfileRole.resolve(userLevel, role)

/**
 * Pure role and PIN gating decisions, unit tested (no Android types).
 *
 * Owner rule 2026-10-29: inside AerioTV, Admin and Standard are treated
 * identically ("full access"). The labels only reflect the user's
 * Dispatcharr access (server admin vs standard user) and grant no different
 * in-app powers. Only gates that call Dispatcharr admin endpoints (Channel
 * Profiles, API keys) look at the server admin flag, never at the label.
 * Both Admin and Standard profiles have authority over Child profiles.
 *
 * Assumptions recorded with the rule:
 * A. "Require PIN or pattern when a Child exists" means every non-Child
 *    profile on the playlist must hold a PIN or Directions pattern while any
 *    Child profile exists. It is enforced when a role changes to Child (the
 *    missing PINs are set first), when an Admin or Standard profile is added
 *    while a Child exists (its PIN is set right after the add), and when a
 *    PIN would be removed (denied while a Child exists).
 * B. (superseded the same day) Wherever a PIN guards a Child, any Admin or
 *    Standard profile's PIN is accepted, and every management action is open
 *    to any non-Child profile.
 */
object RoleRules {
    /** Admin and Standard: full access in AerioTV. */
    fun isFullAccess(role: ProfileRole?): Boolean = role != null && role != ProfileRole.Child

    /** The header switcher and the Profiles list (a Child never sees other names). */
    fun canSeeSwitcher(active: ProfileRole?): Boolean = active != ProfileRole.Child

    /** Add, remove, role, and other profiles' PINs (null = no profiles: full access). */
    fun canManageProfiles(active: ProfileRole?): Boolean = active != ProfileRole.Child

    fun canChangeRoles(active: ProfileRole?): Boolean = isFullAccess(active)

    /** How Add Profile picks a user: the server's list (admin key) or a typed username. */
    enum class AddPath { ServerList, Manual }

    /**
     * The users list needs a server admin key; a non-admin base (or a 403
     * from GET /api/accounts/users/) gets the manual username form instead.
     */
    fun addPath(baseIsServerAdmin: Boolean, usersListForbidden: Boolean): AddPath =
        if (baseIsServerAdmin && !usersListForbidden) AddPath.ServerList else AddPath.Manual

    /** Channel Profiles are assigned on the server: server admin rights, never a Child. */
    fun canAssignChannelProfiles(baseIsServerAdmin: Boolean, activeIsServerAdmin: Boolean, active: ProfileRole?): Boolean =
        baseIsServerAdmin && activeIsServerAdmin && isFullAccess(active)

    /** Dispatcharr Sync `profiles` key: written by any Admin or Standard profile. */
    fun canWriteProfilesKey(active: ProfileRole?): Boolean = isFullAccess(active)

    /** Row menu "Avatar": Admin or Standard for any profile, a Child for its own. */
    fun canEditAvatar(actor: ProfileRole?, isOwn: Boolean): Boolean =
        actor != ProfileRole.Child || isOwn

    /** Server-editing actions (Mark/Unmark as Radio Channel, Switch Stream, Manage Groups). */
    fun canEditServer(active: ProfileRole?): Boolean = active != ProfileRole.Child

    /** Settings sections a Child sees (plus Manage Profiles). Null = every section. */
    val CHILD_SECTIONS: Set<String> = setOf("LiveTV", "Player", "Appearance", "About")

    fun sectionVisible(active: ProfileRole?, sectionName: String): Boolean =
        active != ProfileRole.Child || sectionName in CHILD_SECTIONS

    /** Playlists page and row (Child: hidden). */
    fun playlistsVisible(active: ProfileRole?): Boolean = active != ProfileRole.Child

    /** Settings deep link pages a Child may open; anything else lands on the root. */
    val CHILD_DEEP_LINK_PAGES: Set<String> = setOf("root", "livetv", "player", "appearance", "about", "manage-profiles")

    fun deepLinkAllowed(active: ProfileRole?, page: String): Boolean =
        active != ProfileRole.Child || page.trim().lowercase() in CHILD_DEEP_LINK_PAGES

    /** One profile's PIN state for [pinsMissingForChild]. */
    data class PinHolder(val id: String, val role: ProfileRole, val hasPin: Boolean, val isActor: Boolean = false)

    /**
     * Assumption A: ids of the Admin and Standard profiles that still need a
     * PIN before (or while) a Child exists, the acting profile first.
     * [becomingChild] is the profile about to turn Child (not counted).
     */
    fun pinsMissingForChild(profiles: List<PinHolder>, becomingChild: String? = null): List<String> =
        profiles.filter { it.id != becomingChild && isFullAccess(it.role) && !it.hasPin }
            .sortedBy { if (it.isActor) 0 else 1 }
            .map { it.id }

    /**
     * Role change from [from] to [to]. [missingPins] counts the Admin and
     * Standard profiles (other than the target) without a PIN;
     * [anyPinExists] says whether any full-access PIN (or the Device Admin
     * PIN) exists; [targetIsLastFullAccess] says the target is the only
     * Admin or Standard profile.
     */
    sealed interface RoleChange {
        data object Allowed : RoleChange
        /** Some Admin or Standard profile has no PIN: set the missing PINs first. */
        data object NeedsPinsCreated : RoleChange
        /** Leaving Child: a PIN from an Admin or Standard profile must be entered. */
        data object NeedsPinEntry : RoleChange
        data class Denied(val reason: String) : RoleChange
    }

    const val ADMIN_IS_SERVER_SET = "Admin is set by the Dispatcharr server."
    const val SERVER_ADMIN_STAYS = "Dispatcharr admins are always Admin in AerioTV."

    /**
     * The role to store after the server reports [newLevel] for a profile
     * whose stored role is [stored] (owner rule 2026-10-29): promotion to
     * Dispatcharr admin makes it Admin (a Child too), demotion turns Admin
     * into Standard, a Standard or Child user keeps its choice. Null = no change.
     */
    fun serverRoleChange(stored: ProfileRole?, newLevel: Int): ProfileRole? =
        ProfileRole.resolve(newLevel, stored).takeIf { it != stored }

    fun roleChange(
        actor: ProfileRole?,
        from: ProfileRole,
        to: ProfileRole,
        missingPins: Int,
        anyPinExists: Boolean,
        targetIsLastFullAccess: Boolean,
        targetIsServerAdmin: Boolean = false,
    ): RoleChange = when {
        !isFullAccess(actor) -> RoleChange.Denied("Only an Admin or Standard profile can change roles.")
        targetIsServerAdmin && to != ProfileRole.Admin -> RoleChange.Denied(SERVER_ADMIN_STAYS)
        !targetIsServerAdmin && to == ProfileRole.Admin -> RoleChange.Denied(ADMIN_IS_SERVER_SET)
        from == to -> RoleChange.Allowed
        to == ProfileRole.Child && targetIsLastFullAccess ->
            RoleChange.Denied("At least one profile must stay Admin or Standard.")
        to == ProfileRole.Child && (missingPins > 0 || !anyPinExists) -> RoleChange.NeedsPinsCreated
        from == ProfileRole.Child -> RoleChange.NeedsPinEntry
        else -> RoleChange.Allowed
    }

    /** Removing a Child profile needs a PIN from an Admin or Standard profile. */
    fun removeNeedsPin(target: ProfileRole): Boolean = target == ProfileRole.Child

    /**
     * Assumption A: while any Child exists, Admin and Standard profiles keep
     * a PIN (this includes the earlier "last Admin PIN stays" rule).
     */
    fun canRemovePin(targetRole: ProfileRole, childExists: Boolean): Boolean =
        targetRole == ProfileRole.Child || !childExists

    /** The Device Admin PIN stays while a Child exists and no other full-access PIN does. */
    fun canRemoveDevicePin(childExists: Boolean, otherPins: Int): Boolean = !childExists || otherPins > 0

    const val KEEP_PIN_REASON = "While a Child profile exists, Admin and Standard profiles keep a PIN."

    /**
     * Reset of another profile's (forgotten) PIN by an Admin or Standard
     * profile: the actor enters its own PIN; with no PIN it is a confirm
     * only, unless a Child exists (then the actor's PIN is mandatory and
     * already exists by the rules above). While a Child exists a full-access
     * target gets a temporary PIN instead of none.
     */
    sealed interface ResetGate {
        data object EnterOwnPin : ResetGate
        data object ConfirmOnly : ResetGate
        data class Denied(val reason: String) : ResetGate
    }

    fun resetGate(actor: ProfileRole?, actorHasPin: Boolean, childExists: Boolean): ResetGate = when {
        !isFullAccess(actor) -> ResetGate.Denied("Only an Admin or Standard profile can reset a PIN.")
        actorHasPin -> ResetGate.EnterOwnPin
        !childExists -> ResetGate.ConfirmOnly
        else -> ResetGate.Denied("Set a PIN on this profile first.")
    }

    /**
     * TV launch picker: shown when the active playlist is Dispatcharr, the
     * toggle is on, 2+ profiles are visible, and the last active profile is
     * not a Child (a Child goes straight in).
     */
    fun showLaunchPicker(isTv: Boolean, isDispatcharr: Boolean, toggleOn: Boolean, profileCount: Int, lastRole: ProfileRole?): Boolean =
        isTv && isDispatcharr && toggleOn && profileCount >= 2 && lastRole != ProfileRole.Child
}

/**
 * The ACTIVE profile's role, process wide (like ProfileScope) so Compose
 * surfaces and non-DI callers (PlaylistEntity.canSwitchStream) can read it.
 * Null = the active playlist has no profiles (Xtream Codes, M3U): full access.
 */
object ProfileRoles {
    private val _active = MutableStateFlow<ProfileRole?>(null)
    val active: StateFlow<ProfileRole?> = _active.asStateFlow()

    fun isChild(): Boolean = _active.value == ProfileRole.Child

    fun set(role: ProfileRole?) { _active.value = role }
}
