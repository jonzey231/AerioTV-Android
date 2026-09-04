package com.aeriotv.android.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the active source lets the user create server-side recordings: a
 * Dispatcharr admin account (user_level >= 10). Provided at the Live TV and
 * Player surfaces from the active playlist; the Record affordances (channel
 * long-press, guide cell, player More menu) read it and hide Record for
 * Standard / Streamer Dispatcharr accounts, which 403 on POST recordings.
 *
 * Defaults to true so a read outside any provider stays recording-capable
 * (back-compat). Mirrors iOS dispatcharrCanRecordToServer (commit d8aa76b).
 */
val LocalCanRecordToServer = compositionLocalOf { true }

/**
 * Whether the active playlist is a Dispatcharr Direct Connect ADMIN account
 * (user_level >= 10). POST /proxy/ts/change_stream is IsAdmin on the server, so
 * only admin accounts can switch streams; the player's Switch Stream option
 * gates on this (with the per-channel dispatcharrChannelId) so it is hidden for
 * XC / M3U sources AND for standard (non-admin) Dispatcharr sub-accounts that
 * would otherwise see an option that 403s. Admin implies Direct Connect.
 *
 * Defaults to true so a read outside any provider falls back to the per-channel
 * dispatcharrChannelId gate (Direct-Connect-only); the Player surface supplies
 * the real admin value.
 */
val LocalIsDispatcharrAdmin = compositionLocalOf { true }

/**
 * Dispatcharr 0.30 DVR access of the active playlist's account: "none" /
 * "view" / "manage". "manage" for non-Dispatcharr sources. Gates stop,
 * cancel and delete on server recordings ("view" lists and plays only).
 */
val LocalDvrAccess = compositionLocalOf { "manage" }
