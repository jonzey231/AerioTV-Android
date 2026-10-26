package com.aeriotv.android.core.profiles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-local role of a profile (Profiles phase 2b, 2026-10-17). Set by an
 * Admin profile on the device and synced in the Dispatcharr Sync `profiles`
 * key (`role`). Independent of the Dispatcharr user level, which the server
 * still enforces; the role only shapes what the app shows.
 */
enum class ProfileRole(val label: String, val wire: String) {
    Admin("Admin", "admin"), User("User", "user"), Child("Child", "child");

    companion object {
        /**
         * Default role. The BASE profile (the login that added the playlist)
         * is always Admin, whatever its Dispatcharr level, so two Standard
         * users can share a device without a server admin. Other profiles:
         * a Dispatcharr Admin level is Admin, every other level User.
         */
        fun defaultFor(userLevel: Int, isBase: Boolean = false): ProfileRole =
            if (isBase || ProfileLevel.of(userLevel) == ProfileLevel.Admin) Admin else User

        /**
         * One-time promotion of an existing base profile: its stored role
         * moves to Admin unless it is already Admin or deliberately Child.
         * Returns the role to store, or null when nothing changes.
         */
        fun basePromotion(stored: ProfileRole?): ProfileRole? = when (stored) {
            Admin, Child -> null
            null, User -> Admin
        }

        fun fromWire(s: String?): ProfileRole? = entries.firstOrNull { it.wire == s }
    }
}

fun DispatcharrProfile.effectiveRole(): ProfileRole = role ?: ProfileRole.defaultFor(userLevel, isBase = isDefault)

/** Pure role and PIN gating decisions, unit tested (no Android types). */
object RoleRules {
    /** The header switcher and the Profiles list (a Child never sees other names). */
    fun canSeeSwitcher(active: ProfileRole?): Boolean = active != ProfileRole.Child

    /** Add, remove, role, and other profiles' PINs. */
    fun canManageProfiles(active: ProfileRole?): Boolean = active == null || active == ProfileRole.Admin

    fun canChangeRoles(active: ProfileRole?): Boolean = active == ProfileRole.Admin

    /** How Add Profile picks a user: the server's list (admin key) or a typed username. */
    enum class AddPath { ServerList, Manual }

    /**
     * The users list needs a server admin key; a non-admin base (or a 403
     * from GET /api/accounts/users/) gets the manual username form instead.
     */
    fun addPath(baseIsServerAdmin: Boolean, usersListForbidden: Boolean): AddPath =
        if (baseIsServerAdmin && !usersListForbidden) AddPath.ServerList else AddPath.Manual

    /** Channel Profiles are assigned on the server: server admin only. */
    fun canAssignChannelProfiles(baseIsServerAdmin: Boolean, activeIsServerAdmin: Boolean, active: ProfileRole?): Boolean =
        baseIsServerAdmin && activeIsServerAdmin && active == ProfileRole.Admin

    /** Dispatcharr Sync `profiles` key: written by the local Admin, server admin or not. */
    fun canWriteProfilesKey(active: ProfileRole?): Boolean = active == ProfileRole.Admin

    /** Row menu "Avatar": an Admin for any profile, a User or Child for its own. */
    fun canEditAvatar(actor: ProfileRole?, isOwn: Boolean): Boolean =
        actor == null || actor == ProfileRole.Admin || isOwn

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

    /**
     * Role change from [from] to [to] when [childExists] (after the change is
     * NOT counted) and [adminHasPin] says whether any Admin profile has a PIN.
     */
    sealed interface RoleChange {
        data object Allowed : RoleChange
        /** No Admin has a PIN yet: the acting Admin must create one first. */
        data object NeedsAdminPinCreated : RoleChange
        /** Leaving Child: an Admin PIN must be entered. */
        data object NeedsAdminPinEntry : RoleChange
        data class Denied(val reason: String) : RoleChange
    }

    fun roleChange(
        actor: ProfileRole?,
        from: ProfileRole,
        to: ProfileRole,
        adminHasPin: Boolean,
        targetIsLastAdmin: Boolean,
    ): RoleChange = when {
        actor != ProfileRole.Admin -> RoleChange.Denied("Only an Admin profile can change roles.")
        from == to -> RoleChange.Allowed
        from == ProfileRole.Admin && targetIsLastAdmin ->
            RoleChange.Denied("At least one profile must stay Admin.")
        to == ProfileRole.Child && !adminHasPin -> RoleChange.NeedsAdminPinCreated
        from == ProfileRole.Child -> RoleChange.NeedsAdminPinEntry
        else -> RoleChange.Allowed
    }

    /** Removing a Child profile needs an Admin PIN. */
    fun removeNeedsAdminPin(target: ProfileRole): Boolean = target == ProfileRole.Child

    /** While any Child exists, the last Admin PIN stays. */
    fun canRemoveAdminPin(targetRole: ProfileRole, childExists: Boolean, otherAdminPins: Int): Boolean =
        targetRole != ProfileRole.Admin || !childExists || otherAdminPins > 0

    /**
     * Admin reset of another profile's (forgotten) PIN: the Admin enters its
     * own PIN; with no Admin PIN it is a confirm only, unless a Child exists
     * (then an Admin PIN is mandatory and already exists by the rules above).
     */
    sealed interface ResetGate {
        data object EnterOwnPin : ResetGate
        data object ConfirmOnly : ResetGate
        data class Denied(val reason: String) : ResetGate
    }

    fun resetGate(actor: ProfileRole?, actorHasPin: Boolean, childExists: Boolean): ResetGate = when {
        actor != ProfileRole.Admin -> ResetGate.Denied("Only an Admin profile can reset a PIN.")
        actorHasPin -> ResetGate.EnterOwnPin
        !childExists -> ResetGate.ConfirmOnly
        else -> ResetGate.Denied("Set a PIN on this Admin profile first.")
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
