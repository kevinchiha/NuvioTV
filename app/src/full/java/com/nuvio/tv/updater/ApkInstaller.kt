package com.nuvio.tv.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.nuvio.tv.BuildConfig
import java.io.File

object ApkInstaller {

    private const val TAG = "ApkInstaller"

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun buildUnknownSourcesSettingsIntent(context: Context): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }
    }

    /**
     * Installs [apkFile]. With a [profileFile] (Android 9+), it goes through a PackageInstaller
     * session as `base.apk` + `base.dm`, so Android compiles the app during the install instead
     * of leaving it in the interpreter until idle maintenance. Without one, or if the session
     * can't start, it opens the plain install screen as before. Blocking file I/O: call it off
     * the main thread.
     */
    fun launchInstall(context: Context, apkFile: File, profileFile: File? = null) {
        if (profileFile != null && InstallProfile.useSession(hasProfile = true, sdkInt = Build.VERSION.SDK_INT)) {
            val started = runCatching { installWithSession(context, apkFile, profileFile) }
                .onFailure { Log.w(TAG, "Session install could not start; using the plain installer", it) }
                .isSuccess
            if (started) return
        }
        launchViewInstall(context, apkFile)
    }

    /** The plain install screen with only the APK (no compile profile). */
    fun launchViewInstall(context: Context, apkFile: File) {
        context.startActivity(buildViewInstallIntent(context, apkFile))
    }

    fun buildViewInstallIntent(context: Context, apkFile: File): Intent {
        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)

        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun installWithSession(context: Context, apkFile: File, profileFile: File) {
        val installer = context.packageManager.packageInstaller
        // An earlier attempt that never got a final answer (confirmation left open, app killed)
        // still holds a full copy of the APK in /data/app. Clear those before copying again.
        installer.mySessions.forEach { old -> runCatching { installer.abandonSession(old.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(context.packageName)
                setSize(apkFile.length() + profileFile.length())
            }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.writeFile("base.apk", apkFile)
                session.writeFile("base.dm", profileFile)

                val result = Intent(context, InstallResultReceiver::class.java)
                    .setPackage(context.packageName)
                    .putExtra(InstallResultReceiver.EXTRA_APK_PATH, apkFile.absolutePath)
                // Android 12+ needs MUTABLE: PackageInstaller adds the status extras to it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, result, flags)
                session.commit(pending.intentSender)
            }
            Log.i(TAG, "Committed session $sessionId with ${profileFile.name}")
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private fun PackageInstaller.Session.writeFile(name: String, file: File) {
        openWrite(name, 0, file.length()).use { out ->
            file.inputStream().use { it.copyTo(out) }
            fsync(out)
        }
    }
}
