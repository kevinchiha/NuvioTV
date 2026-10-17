package com.nuvio.tv.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Receives the result of [ApkInstaller]'s session install. Android first answers with
 * STATUS_PENDING_USER_ACTION and the confirm screen to show; after that, a final status.
 * Screens go through [PendingInstallScreen] rather than startActivity here: Android 10+ drops
 * activity starts from a receiver while the app is not visible, without an error.
 * Not exported: only the PendingIntent this app hands to PackageInstaller can reach it.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        when (InstallStatusAction.forStatus(status)) {
            InstallStatusAction.CONFIRM -> {
                val confirm = confirmIntent(intent)
                if (confirm != null) {
                    PendingInstallScreen.post(
                        PendingInstall(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), fallbackApkPath = apkPath)
                    )
                } else {
                    Log.w(TAG, "Session needs confirmation but sent no screen; using the plain installer")
                    fallback(context, apkPath)
                }
            }
            InstallStatusAction.NOTHING -> Log.i(TAG, "Session install finished with status $status")
            InstallStatusAction.FALLBACK -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Session install failed (status $status: $message); using the plain installer")
                fallback(context, apkPath)
            }
        }
    }

    private fun fallback(context: Context, apkPath: String?) {
        val apk = apkPath?.let(::File)?.takeIf { it.exists() }
        if (apk == null) {
            Log.w(TAG, "No APK to fall back to (path=$apkPath); the next Install press starts over")
            return
        }
        runCatching { PendingInstallScreen.post(ApkInstaller.plainInstall(context, apk)) }
            .onFailure { Log.w(TAG, "Could not prepare the plain installer", it) }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        const val EXTRA_APK_PATH = "tv.kevbox.updater.APK_PATH"
        private const val TAG = "InstallResultReceiver"
    }
}
