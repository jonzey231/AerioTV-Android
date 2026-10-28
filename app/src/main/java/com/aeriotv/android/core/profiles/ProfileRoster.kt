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
}
