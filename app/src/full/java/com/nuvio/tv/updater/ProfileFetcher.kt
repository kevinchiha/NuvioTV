package com.nuvio.tv.updater

import android.util.Log
import com.nuvio.tv.updater.model.AppUpdate
import java.io.File

/**
 * Gets the install-time compile profile (`.dm`) for [AppUpdate] and stores it next to the APK.
 * Every failure returns null: the profile only makes the first launch fast, so a missing one
 * must never block an update.
 */
object ProfileFetcher {

    private const val TAG = "ProfileFetcher"

    suspend fun fetch(
        downloader: ApkDownloader,
        update: AppUpdate,
        apkFile: File,
        isTrusted: (String) -> Boolean,
    ): File? {
        val url = update.profileUrl?.takeIf { it.isNotBlank() } ?: return null
        val sha = update.profileSha256?.takeIf { it.isNotBlank() } ?: return null
        if (!isTrusted(url)) {
            Log.w(TAG, "Untrusted profile URL, skipping: $url")
            return null
        }
        val dest = File(apkFile.parentFile, InstallProfile.fileNameFor(apkFile.name))

        if (dest.exists() && verified(dest, sha)) return dest

        val file = downloader.download(url, dest) { _, _ -> }.getOrElse {
            Log.w(TAG, "Profile download failed: ${it.message}")
            return null
        }
        if (!verified(file, sha)) {
            Log.w(TAG, "Profile SHA-256 mismatch; deleting ${file.name}")
            runCatching { file.delete() }
            return null
        }
        return file
    }

    private fun verified(file: File, sha: String): Boolean =
        runCatching { Checksum.verify(file, sha) }.getOrDefault(false)
}
