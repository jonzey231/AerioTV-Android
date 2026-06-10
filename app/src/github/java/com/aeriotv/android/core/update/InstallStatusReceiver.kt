package com.aeriotv.android.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import dagger.hilt.android.EntryPointAccessors

/**
 * Status sink for the updater's PackageInstaller session. The platform
 * delivers session progress here via the IntentSender the commit registered.
 *
 * STATUS_PENDING_USER_ACTION wraps the system installer's confirm dialog;
 * launching it hands the screen to the OS (D-pad navigable on TV). On a
 * successful SELF-update the process is killed before STATUS_SUCCESS could be
 * observed, so success handling lives in PackageReplacedReceiver instead;
 * failures and user-aborts are forwarded to the manager to update the UI.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_INTENT, Intent::class.java,
                ) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Log.w(TAG, "couldn't launch install confirm", it) }
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.i(TAG, "install session status=$status message=$message")
                EntryPointAccessors
                    .fromApplication(context.applicationContext, UpdaterEntryPoint::class.java)
                    .githubUpdateManager()
                    .onInstallStatus(status, message)
            }
        }
    }

    private companion object { const val TAG = "InstallStatus" }
}
