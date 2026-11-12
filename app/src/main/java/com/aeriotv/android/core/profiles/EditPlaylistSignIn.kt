package com.aeriotv.android.core.profiles

/**
 * Item 13 of the profile follow-ups (Apple parity, security, 2026-11-11):
 * Edit Playlist shows and edits the playlist's OWN sign-in, the default
 * profile's, whatever profile is active. It never pre-fills, diverts to, or
 * writes a non-default profile's sign-in; that one changes only through the
 * profile's own sign-in form. Pure rules, so the unit tests check exactly
 * what the screen and its save do.
 *
 * Before this, the form showed the ACTIVE profile's sign-in and the save
 * went through the DAO's per-profile diversion: a username and password
 * typed while a non-default profile was active were saved nowhere, and a
 * server-address change saved in API Key mode could write a key typed for
 * that profile onto the playlist's own row.
 */
object EditPlaylistSignIn {

    /** The sign-in the form shows: the playlist's own row, never the active profile's. */
    fun shown(own: ProfileHostHold.SignIn?): ProfileHostHold.SignIn =
        own ?: ProfileHostHold.SignIn(null, null, null)

    /**
     * The save's channel fetch and row writes run as the active non-default
     * profile ([profileInstalled]: installed on this playlist and not held)
     * when the playlist is the active one and its address stays; the
     * playlist's own sign-in is then checked first and written to its own row
     * after. Otherwise (the default profile is active, the playlist is not the
     * active one, or the address moves and every non-default profile is held)
     * the whole save runs on the playlist's own row.
     */
    fun savesAsProfile(isActivePlaylist: Boolean, profileInstalled: Boolean, routeChanged: Boolean): Boolean =
        isActivePlaylist && profileInstalled && !routeChanged

    /**
     * The typed sign-in differs from the playlist's own. Only a SUPPLIED field
     * counts (the form sends only the fields of the mode on screen), the same
     * rule as the full save.
     */
    fun ownChanged(typed: ProfileHostHold.SignIn, own: ProfileHostHold.SignIn): Boolean {
        fun s(v: String?) = v?.trim()?.takeIf { it.isNotBlank() }
        val pass = typed.password?.takeIf { it.isNotBlank() }
        return (s(typed.apiKey) != null && s(typed.apiKey) != s(own.apiKey)) ||
            (s(typed.username) != null && s(typed.username) != s(own.username)) ||
            (pass != null && pass != own.password?.takeIf { it.isNotBlank() })
    }

    /**
     * The sign-in the rest of the save carries: the active profile's current
     * one, unchanged, when it saves as that profile ([profile]); else what was
     * typed, which is the playlist's own.
     */
    fun forSave(typed: ProfileHostHold.SignIn, profile: ProfileHostHold.SignIn?, asProfile: Boolean): ProfileHostHold.SignIn =
        if (asProfile && profile != null) profile else typed

    /**
     * What lands on the playlist's own row: the typed fields over the stored
     * ones (a field not supplied keeps its value), with [resolvedKey] (the key
     * users/me returned for a typed username and password) in place of a key.
     */
    fun ownAfter(typed: ProfileHostHold.SignIn, resolvedKey: String?, own: ProfileHostHold.SignIn): ProfileHostHold.SignIn =
        ProfileHostHold.SignIn(
            apiKey = resolvedKey?.trim()?.takeIf { it.isNotBlank() } ?: own.apiKey,
            username = typed.username?.trim()?.takeIf { it.isNotBlank() } ?: own.username,
            password = typed.password?.takeIf { it.isNotBlank() } ?: own.password,
        )
}
