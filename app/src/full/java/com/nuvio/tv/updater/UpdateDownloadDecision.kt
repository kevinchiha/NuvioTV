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

    /**
     * Names in [allNames] to prune — everything except [keepName] and its profile file
     * (null keeps nothing).
     */
    fun staleFileNames(allNames: List<String>, keepName: String?): List<String> {
        val keepProfile = keepName?.let(InstallProfile::fileNameFor)
        return allNames.filter { it != keepName && it != keepProfile }
    }

    /**
     * Only download an asset served over https from the same host as [baseUrl]. When
     * [testBaseUrl] is set (a debug build made with `-PupdaterTestBaseUrl`), an http asset on
     * exactly that host and port is trusted too.
     */
    fun isTrustedAssetUrl(assetUrl: String, baseUrl: String, testBaseUrl: String = ""): Boolean {
        val asset = runCatching { URI(assetUrl) }.getOrNull() ?: return false
        val assetHost = asset.host ?: return false
        if ("https".equals(asset.scheme, ignoreCase = true)) {
            val baseHost = runCatching { URI(baseUrl) }.getOrNull()?.host
            if (baseHost != null && assetHost.equals(baseHost, ignoreCase = true)) return true
        }
        if (testBaseUrl.isBlank() || !"http".equals(asset.scheme, ignoreCase = true)) return false
        val test = runCatching { URI(testBaseUrl.trim()) }.getOrNull() ?: return false
        val testHost = test.host ?: return false
        val testPort = effectivePort(test) ?: return false
        return assetHost.equals(testHost, ignoreCase = true) && effectivePort(asset) == testPort
    }

    /** The URI's port, with `-1` read as the scheme default (80 for http, 443 for https). */
    private fun effectivePort(uri: URI): Int? = when {
        uri.port != -1 -> uri.port
        "http".equals(uri.scheme, ignoreCase = true) -> 80
        "https".equals(uri.scheme, ignoreCase = true) -> 443
        else -> null
    }
}
