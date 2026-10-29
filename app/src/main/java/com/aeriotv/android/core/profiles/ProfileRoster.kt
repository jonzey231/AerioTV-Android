package com.aeriotv.android.core.profiles

import com.aeriotv.android.core.sync.dsync.ProfileRow

/**
 * The synced `profiles` list is a catalog of available profiles, not a
 * mandate (owner decision 2026-10-27): a pull merges metadata into profiles
 * this device already has and never creates one. Rows for users with no
 * local profile are offered as suggestions ("From your other devices") and
 * added only by an explicit choice. Pure logic, unit tested.
 */
object ProfileRoster {
    data class Plan(
        /** Rows whose user has a profile on this device: metadata merges. */
        val merge: List<ProfileRow>,
        /** Rows with no local profile: offered, never added by a pull. */
        val available: List<ProfileRow>,
    )

    fun plan(rows: List<ProfileRow>, localUsernames: Collection<String>): Plan {
        val have = localUsernames.map { it.lowercase() }.toSet()
        val (merge, available) = rows.partition { it.username.lowercase() in have }
        return Plan(merge, available)
    }

    /** Suggestions in roster order (row order, then name), never a local user. */
    fun suggestions(rows: List<ProfileRow>, localUsernames: Collection<String>): List<ProfileRow> =
        plan(rows, localUsernames).available
            .distinctBy { it.username.lowercase() }
            .sortedWith(compareBy<ProfileRow> { if (it.order >= 0) it.order else Int.MAX_VALUE }.thenBy { it.username.lowercase() })

    /**
     * The row's display name, role, order, and avatar on [p]. Absent fields
     * (older writer: blank name, "" role, -1 order, null avatar) keep the
     * local value. The PIN is applied separately (PinStore).
     */
    fun applyRow(p: DispatcharrProfile, r: ProfileRow): DispatcharrProfile {
        var out = p
        if (r.displayName.isNotBlank()) out = out.copy(displayName = r.displayName)
        ProfileRole.fromWire(r.role)?.let { out = out.copy(role = it) }
        if (r.order >= 0) out = out.copy(order = r.order)
        if (r.avatar != null) out = out.copy(avatar = AvatarCatalog.normalize(r.avatar))
        if (out.userId == 0 && r.userId > 0) out = out.copy(userId = r.userId)
        return out
    }

    /**
     * A row with no roster fields of its own: name blank or equal to the
     * username, and no avatar (null or the default initial with no color).
     * This is what an Add pill pushed before the roster fields applied.
     */
    fun isStripped(displayName: String, username: String, avatar: AvatarSpec?): Boolean =
        (displayName.isBlank() || displayName.equals(username, ignoreCase = true)) &&
            (avatar == null || avatar == AvatarSpec())

    fun isStripped(r: ProfileRow): Boolean = isStripped(r.displayName, r.username, r.avatar)

    /**
     * Push guard for a profile added from the roster in the last 60 s: a
     * stripped outgoing row never replaces a server row that has a name or
     * avatar; the server's name, avatar, and order are kept. Null = unchanged.
     */
    fun guardFreshRosterAdd(out: ProfileRow, prev: ProfileRow?, freshRosterAdd: Boolean): ProfileRow? {
        if (!freshRosterAdd || prev == null || !isStripped(out) || isStripped(prev)) return null
        return out.copy(
            displayName = prev.displayName.ifBlank { out.displayName },
            avatar = prev.avatar ?: out.avatar,
            order = if (prev.order >= 0) prev.order else out.order,
        )
    }

    /**
     * Push guard for a profile this device never renamed or gave an avatar:
     * it carries no claim on those fields, so a server row that has them
     * keeps its name and avatar. Null = unchanged.
     */
    fun guardUncustomized(out: ProfileRow, prev: ProfileRow?, customized: Boolean): ProfileRow? {
        if (customized || prev == null || isStripped(prev)) return null
        val next = out.copy(
            displayName = prev.displayName.ifBlank { out.displayName },
            avatar = prev.avatar ?: out.avatar,
        )
        return next.takeIf { it != out }
    }

    /**
     * Pull side of the same rule: the server name and avatar to adopt on a
     * profile with no local claim, whatever the stamps say. Null = nothing.
     */
    fun adoptUncustomized(localName: String, localAvatar: AvatarSpec?, customized: Boolean, r: ProfileRow): Pair<String, AvatarSpec?>? {
        if (customized || isStripped(r)) return null
        val name = r.displayName.ifBlank { localName }
        val avatar = r.avatar?.let(AvatarCatalog::normalize) ?: localAvatar
        if (name == localName && avatar == localAvatar) return null
        return name to avatar
    }

    enum class Repair { None, ResendLocal, ApplyServer }

    /** How long after the local stamp a stripped server row counts as a duplicate of it. */
    const val STRIPPED_WINDOW_S = 600L

    /**
     * Pull-side repair for rows clobbered by a half-built roster add. Narrow:
     * a stripped server row up to 10 minutes newer than a local row that has
     * a name or avatar is a stripped duplicate (re-send local, stamped now);
     * a local stripped row with a server row that has them takes the server.
     */
    fun repair(localName: String, localAvatar: AvatarSpec?, localAt: Long, r: ProfileRow): Repair {
        val serverStripped = isStripped(r)
        val localStripped = isStripped(localName, r.username, localAvatar)
        return when {
            serverStripped && !localStripped && localAt > 0 && r.updatedAt > localAt &&
                r.updatedAt - localAt <= STRIPPED_WINDOW_S -> Repair.ResendLocal
            localStripped && !serverStripped && localAt >= r.updatedAt -> Repair.ApplyServer
            else -> Repair.None
        }
    }
}
