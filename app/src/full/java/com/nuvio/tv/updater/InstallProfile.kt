package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.ProfileAsset

/** Pure helpers for the install-time compile profile (`.dm`) that ships next to the APK. */
object InstallProfile {

    /** The profile whose API range covers [sdkInt], ignoring entries with no url or checksum. */
    fun pick(profiles: List<ProfileAsset>, sdkInt: Int): ProfileAsset? =
        profiles.firstOrNull {
            sdkInt in it.minApi..it.maxApi && it.url.isNotBlank() && it.sha256.isNotBlank()
        }

    /**
     * Name of the profile file stored next to [apkFileName]. Android pairs a `.dm` with the APK
     * of the same base name, so the installer writes them as `base.apk` + `base.dm`.
     */
    fun fileNameFor(apkFileName: String): String =
        apkFileName.removeSuffix(".apk") + ".dm"

    /**
     * Whether to install through a PackageInstaller session (APK + profile) instead of the
     * plain install screen. Android only reads `.dm` files from Android 9 (API 28).
     */
    fun useSession(hasProfile: Boolean, sdkInt: Int): Boolean =
        hasProfile && sdkInt >= 28
}
