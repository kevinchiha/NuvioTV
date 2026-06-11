package com.nuvio.tv.updater

import java.net.URI

/**
 * Pure decisions for the background [UpdateCheckWorker], extracted so they can be unit-tested
 * without Android APIs. [isCachedFor] is also reused by [UpdateResolution] so the worker's
 * "already cached?" and the ViewModel's "installable?" never drift.
 */
object UpdateDownloadDecision {

    /** Whether the worker should pre-download an APK for [remoteVersionCode]. */
    fun shouldDownload(
        remoteVersionCode: Int,
        currentVersionCode: Int,
        ignoredVersionCode: Int?,
        alreadyCachedForRemote: Boolean,
        isUnmetered: Boolean,
    ): Boolean =
        remoteVersionCode > currentVersionCode &&
            ignoredVersionCode != remoteVersionCode &&
            !alreadyCachedForRemote &&
            isUnmetered

    /** True when a previously cached pre-download still satisfies [targetVersionCode]. */
    fun isCachedFor(
        cachedVersionCode: Int?,
        targetVersionCode: Int,
        cachedFileExists: Boolean,
    ): Boolean =
        cachedVersionCode != null && cachedVersionCode == targetVersionCode && cachedFileExists

    /** Names in [allNames] to prune — everything except [keepName] (null keeps nothing). */
    fun staleFileNames(allNames: List<String>, keepName: String?): List<String> =
        allNames.filter { it != keepName }

    /** Only download an asset served over https from the same host as [baseUrl]. */
    fun isTrustedAssetUrl(assetUrl: String, baseUrl: String): Boolean {
        val asset = runCatching { URI(assetUrl) }.getOrNull() ?: return false
        if (!"https".equals(asset.scheme, ignoreCase = true)) return false
        val assetHost = asset.host ?: return false
        val baseHost = runCatching { URI(baseUrl) }.getOrNull()?.host ?: return false
        return assetHost.equals(baseHost, ignoreCase = true)
    }
}
