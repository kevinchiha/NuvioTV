package com.nuvio.tv.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
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
     * can't start, it queues the plain install screen as before. Screens go through
     * [PendingInstallScreen], never startActivity here: this runs after a background download,
     * when the member may have left the app. Blocking file I/O: call it off the main thread.
     */
    fun launchInstall(context: Context, apkFile: File, profileFile: File? = null) {
        if (profileFile != null && InstallProfile.useSession(hasProfile = true, sdkInt = Build.VERSION.SDK_INT)) {
            val started = runCatching { installWithSession(context, apkFile, profileFile) }
                .onFailure { Log.w(TAG, "Session install could not start; using the plain installer", it) }
                .isSuccess
            if (started) return
        }
        PendingInstallScreen.post(plainInstall(context, apkFile))
    }

    /** The plain install screen with only the APK; nothing further to fall back to. */
    fun plainInstall(context: Context, apkFile: File): PendingInstall =
        PendingInstall(buildViewInstallIntent(context, apkFile), fallbackApkPath = null)

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
                SilentInstallGuard.memberSessionCommitted(SystemClock.elapsedRealtime())
            }
            Log.i(TAG, "Committed session $sessionId with ${profileFile.name}")
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    /**
     * Creates a silent session and writes base.apk (+ base.dm). Abandons it and rethrows on
     * failure. Blocking I/O. With UPDATE_PACKAGES_WITHOUT_USER_ACTION, Android installs an update
     * of this app without the confirm screen. A missing [profileFile] only drops base.dm: the
     * plain installer always shows a screen, so a silent install never routes there.
     */
    @RequiresApi(31)
    fun openSilentSession(context: Context, apkFile: File, profileFile: File?): Int {
        val installer = context.packageManager.packageInstaller
        // Same clean-up as installWithSession. The caller checks SilentInstallGuard first: no
        // silent attempt runs while the member's Install job copies its session, while an install
        // screen waits to open, or for 10 minutes after a member commit or an install screen opened.
        // A member who leaves Android's confirm screen open longer than that loses that session.
        installer.mySessions.forEach { old -> runCatching { installer.abandonSession(old.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(context.packageName)
                setSize(apkFile.length() + (profileFile?.length() ?: 0L))
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.writeFile("base.apk", apkFile)
                if (profileFile != null) session.writeFile("base.dm", profileFile)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
        return sessionId
    }

    /**
     * Commits a session opened by [openSilentSession]. Throws if Android refuses the commit.
     * The result goes to [InstallResultReceiver] flagged as silent, so it never shows a screen.
     */
    @RequiresApi(31)
    fun commitSilentSession(context: Context, sessionId: Int, versionCode: Int, profileName: String?) {
        val installer = context.packageManager.packageInstaller
        installer.openSession(sessionId).use { session ->
            val result = Intent(context, InstallResultReceiver::class.java)
                .setPackage(context.packageName)
                .putExtra(InstallResultReceiver.EXTRA_SILENT, true)
                .putExtra(InstallResultReceiver.EXTRA_VERSION_CODE, versionCode)
            // MUTABLE, as in installWithSession: PackageInstaller adds the status extras to it.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, result, flags)
            session.commit(pending.intentSender)
        }
        Log.i(
            SILENT_LOG_TAG,
            "Committed silent session $sessionId (versionCode $versionCode, profile=${profileName ?: "none"})"
        )
    }

    /** Abandons [sessionId]; never throws. */
    fun abandonSession(context: Context, sessionId: Int) {
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
    }

    private fun PackageInstaller.Session.writeFile(name: String, file: File) {
        openWrite(name, 0, file.length()).use { out ->
            file.inputStream().use { it.copyTo(out) }
            fsync(out)
        }
    }
}
