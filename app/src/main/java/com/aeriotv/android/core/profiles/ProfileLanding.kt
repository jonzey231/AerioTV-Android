package com.aeriotv.android.core.profiles

/**
 * Which changes of the active profile need Live TV to land again (Nothing
 * Phone 2026-11-13). The guide lands on the active profile's own group (its
 * Default Group, else All) after EVERY change of the active profile. A
 * switch, a playlist activation, and the launch land it on their own path;
 * every other change (a server address hold moving the profile to the
 * default one, and any path that may change the active profile later) lands
 * from [ProfileScope.changes].
 */
object ProfileLanding {
    private val landsOnItsOwnPath = setOf(ProfileScope.Why.SWITCH, ProfileScope.Why.PLAYLIST, ProfileScope.Why.LAUNCH)

    fun needsLanding(reason: String): Boolean = reason !in landsOnItsOwnPath
}
