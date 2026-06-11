package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate

/**
 * Pure resolve logic for [UpdateViewModel]: merges the live check result with the cached
 * background pre-download. Live wins; cached metadata is the offline fallback. A cached APK
 * is only "installable" when its file exists and its versionCode matches the effective
 * update — the same [UpdateDownloadDecision.isCachedFor] predicate the worker uses. The
 * installable path is computed independent of the ignore flag; the dialog is gated by
 * [Decision.showDialog], so an ignored-but-pre-downloaded version stays hidden until a force
 * check (then it correctly offers instant Install).
 */
object UpdateResolution {

    data class Decision(
        val update: AppUpdate?,
        val isUpdateAvailable: Boolean,
        val showDialog: Boolean,
        val installableApkPath: String?,
    )

    fun resolve(
        liveUpdate: AppUpdate?,
        cachedUpdate: AppUpdate?,
        cachedApkPath: String?,
        cachedApkExists: Boolean,
        currentVersionCode: Int,
        ignoredVersionCode: Int?,
        force: Boolean,
    ): Decision {
        val effective = liveUpdate ?: cachedUpdate
            ?: return Decision(update = null, isUpdateAvailable = false, showDialog = force, installableApkPath = null)

        val remoteNewer = effective.versionCode > currentVersionCode
        val notIgnored = ignoredVersionCode == null || ignoredVersionCode != effective.versionCode
        val installable = cachedApkPath?.takeIf {
            UpdateDownloadDecision.isCachedFor(cachedUpdate?.versionCode, effective.versionCode, cachedApkExists)
        }

        return Decision(
            update = effective,
            isUpdateAvailable = remoteNewer,
            showDialog = (remoteNewer && notIgnored) || force,
            installableApkPath = installable,
        )
    }
}
