package com.aeriotv.android.ui

import androidx.compose.runtime.compositionLocalOf
import com.aeriotv.android.core.data.capability.Capability
import com.aeriotv.android.core.data.capability.CapabilitySet

/**
 * The active playlist's per-user capabilities, supplied at the surfaces that
 * gate on them (Live TV, Guide, Player, DVR).
 *
 * Replaces the old hard "Admin vs Standard" split: what the user sees now
 * follows what Dispatcharr says THIS account can do
 * (custom_properties.dvr_access, vod_movies_enabled, catchup_enabled, ...),
 * probed from /api/accounts/users/me/ and re-derived on read.
 *
 * Defaults to [CapabilitySet.UNKNOWN], which reports every capability as
 * allowed: a read outside any provider, or before the snapshot lands, must
 * NEVER hide an affordance. The server is the final authority, and a 403
 * self-corrects the capability (see CapabilityCorrections).
 */
val LocalCapabilities = compositionLocalOf { CapabilitySet.UNKNOWN }

/**
 * Whether the active source lets the user create server-side recordings.
 *
 * This is [Capability.CanManageDvr]: Dispatcharr's recording writes (POST /
 * PATCH / DELETE / stop / extend on /api/channels/recordings/, and every
 * recurring-rule action) are IsAdminOrDVRManager, so a non-admin account with
 * `custom_properties.dvr_access = "manage"` CAN record and must see the Record
 * affordances. The old gate demanded user_level >= 10 and hid Record from those
 * users.
 *
 * Defaults to true so a read outside any provider stays recording-capable.
 */
val LocalCanRecordToServer = compositionLocalOf { true }

/**
 * Whether the player's Switch Stream option should be offered
 * ([Capability.CanSwitchStream]).
 *
 * POST /proxy/ts/change_stream and the other /proxy control endpoints are still
 * IsAdmin on the server, so this resolves to admin today. It is routed through
 * the capability layer anyway: if Dispatcharr later moves stream switching to a
 * per-user permission, the change is one line in `deriveCapabilities` and every
 * call site follows.
 *
 * Defaults to FALSE (fail closed): a read outside any provider, or with no
 * measured user level, hides admin-only affordances rather than offering
 * something the server will refuse with 403.
 */
val LocalIsDispatcharrAdmin = compositionLocalOf { false }

/**
 * Effective DVR access of the active playlist's account: "none" / "view" /
 * "manage", mirroring apps/channels/dvr_access.py. "manage" for
 * non-Dispatcharr sources and for an unprobed account (nothing is hidden
 * before the account has been measured). Gates stop, cancel, edit and delete on
 * server recordings; "view" lists and plays only.
 */
val LocalDvrAccess = compositionLocalOf { "manage" }

/** Convenience: is this capability allowed (Allowed OR Unknown) right here? */
fun CapabilitySet.canRecordToServer(): Boolean = allows(Capability.CanManageDvr)
