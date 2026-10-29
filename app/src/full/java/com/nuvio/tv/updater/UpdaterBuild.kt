package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig

/**
 * Build-time facts about the updater, read in one place. A release build always runs the
 * updater; a debug build runs it only when made with `-PupdaterTestBaseUrl`, which also lets the
 * trust check accept plain http on that test server.
 */
object UpdaterBuild {
    /** AC-2 check 1: a release build, or a debug build made with -PupdaterTestBaseUrl. */
    val enabled: Boolean
        get() = BuildConfig.UPDATER_ENABLED

    /** isTrustedAssetUrl with this build's UPDATE_BASE_URL and UPDATER_TEST_BASE_URL. */
    fun isTrusted(url: String): Boolean =
        UpdateDownloadDecision.isTrustedAssetUrl(
            url,
            BuildConfig.UPDATE_BASE_URL,
            BuildConfig.UPDATER_TEST_BASE_URL,
        )
}
