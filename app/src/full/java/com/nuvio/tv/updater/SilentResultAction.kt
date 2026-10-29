package com.nuvio.tv.updater

import android.content.pm.PackageInstaller

/**
 * What [InstallResultReceiver] does with the result of a silent session (AC-7, AC-8). None of
 * these shows a screen. Unlike [InstallStatusAction], an aborted silent install is a failure.
 */
enum class SilentResultAction {
    /** The update installed. */
    INSTALLED,

    /** Android wanted the member to confirm: abandon the session and mark the version refused. */
    REFUSED,

    /** Anything else. The attempt was already counted before the commit; log it and stop. */
    FAILED;

    companion object {
        fun forStatus(status: Int): SilentResultAction = when (status) {
            PackageInstaller.STATUS_SUCCESS -> INSTALLED
            PackageInstaller.STATUS_PENDING_USER_ACTION -> REFUSED
            else -> FAILED
        }
    }
}

/** The AC-13 result line, logged under [SILENT_LOG_TAG]. */
internal fun silentResultLogLine(
    versionCode: Int,
    status: Int,
    action: SilentResultAction,
    message: String?,
): String {
    val line = "Silent result for versionCode $versionCode: status $status ($action)"
    return if (message != null) "$line message=$message" else line
}
