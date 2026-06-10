package com.aeriotv.android.core.update

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Contract for the in-app updater. The implementation is selected by product
 * flavor: the `github` flavor binds GithubUpdateManager (checks GitHub
 * Releases, downloads + verifies + installs the release APK); the `play`
 * flavor binds NoOpUpdateManager (Play policy forbids self-update of
 * Play-distributed builds, and that flavor's manifest carries no
 * REQUEST_INSTALL_PACKAGES). UI in the main source set talks only to this
 * interface, so zero updater surface is reachable in the Play build.
 */
interface UpdateManager {

    /** False when this build/install can't use the updater (play flavor, a
     *  Play-installed copy, or a build not signed with the release key).
     *  Every UI entry point hides itself when false. */
    val isEnabled: Boolean

    val state: StateFlow<UpdateState>

    /**
     * Check GitHub for a newer release. Automatic checks (app foregrounded)
     * are throttled to once per 12h and stay silent on failure; [manual]
     * checks (Settings button) bypass the throttle, ignore a skipped
     * version, and surface errors.
     */
    suspend fun check(manual: Boolean)

    /** Begin (or restart) downloading the available update. */
    fun startDownload()

    /**
     * Install the staged, verified APK. Routes through the one-time
     * "Install unknown apps" Settings grant when needed, then commits a
     * PackageInstaller session; the system confirm dialog takes over. On
     * success Android replaces the app in place (data intact) and kills the
     * process; the user relaunches from the home screen.
     */
    fun install()

    /** "Later": snooze the currently-available version for launch prompts.
     *  Manual checks in Settings still offer it. */
    fun skipAvailableVersion()

    /** Clear an error state back to idle. */
    fun dismissError()

    /**
     * Launch-time bookkeeping: resume a staged update that survived a
     * process death (the unknown-sources grant and the install commit both
     * kill the process), or clean up after a completed one.
     */
    suspend fun resumePending()
}

/** A newer GitHub release the app can update to. */
data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
)

sealed interface UpdateState {
    /** Nothing known (not yet checked, or updater disabled). */
    data object Idle : UpdateState

    /** Last check found no newer release. */
    data class UpToDate(val checkedAtMs: Long) : UpdateState

    data class Available(val info: UpdateInfo) : UpdateState

    data class Downloading(val info: UpdateInfo, val progressPercent: Int) : UpdateState

    data class Verifying(val info: UpdateInfo) : UpdateState

    /** Downloaded + verified; waiting for the user to tap Install. */
    data class ReadyToInstall(val info: UpdateInfo) : UpdateState

    /** Bounced to the "Install unknown apps" Settings toggle; the grant can
     *  kill this process, so a persisted PendingUpdate backs this state. */
    data class AwaitingInstallPermission(val info: UpdateInfo) : UpdateState

    /** Session committed; the system installer dialog owns the screen. */
    data class Installing(val info: UpdateInfo) : UpdateState

    data class Error(val message: String, val info: UpdateInfo?) : UpdateState
}

/**
 * Persisted across the two process-killing steps of the install flow
 * (unknown-sources grant, session commit) so the next launch can resume.
 * Stored as JSON in AppPreferences.updatePendingJson.
 */
@Serializable
data class PendingUpdate(
    val versionName: String,
    val versionCode: Long,
    val apkPath: String,
    val notes: String = "",
    val apkSizeBytes: Long = 0L,
)
