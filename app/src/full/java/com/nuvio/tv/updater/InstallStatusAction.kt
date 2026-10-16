package com.nuvio.tv.updater

import android.content.pm.PackageInstaller

/** What [InstallResultReceiver] does with a status from a [PackageInstaller] session commit. */
enum class InstallStatusAction {
    /** Show Android's "Install this update?" screen. */
    CONFIRM,

    /** Installed, or the member said no: leave it there. */
    NOTHING,

    /** The session route failed: retry through the plain install screen, without the profile. */
    FALLBACK;

    companion object {
        fun forStatus(status: Int): InstallStatusAction = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> CONFIRM
            PackageInstaller.STATUS_SUCCESS,
            PackageInstaller.STATUS_FAILURE_ABORTED -> NOTHING
            else -> FALLBACK
        }
    }
}
