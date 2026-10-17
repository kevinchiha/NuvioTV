package com.nuvio.tv.updater

import android.util.Log
import com.nuvio.tv.updater.model.AppUpdate
import java.io.File
import java.util.zip.ZipFile

/**
 * Gets the install-time compile profile (`.dm`) for [AppUpdate] and stores it next to the APK.
 * Every failure returns null: the profile only makes the first launch fast, so a missing one
 * must never block an update.
 */
object ProfileFetcher {

    private const val TAG = "ProfileFetcher"

    /** Long enough for ~45 KB on a slow link, short enough that an Install press never hangs. */
    const val DEFAULT_TIMEOUT_MS = 15_000L

    /**
     * The only entries AGP puts in a `.dm`. Android would also accept a `primary.vdex`, which
     * the APK signature does not cover, so anything else is refused.
     */
    private val ALLOWED_ENTRIES = setOf("primary.prof", "primary.profm")

    suspend fun fetch(
        downloader: ApkDownloader,
        update: AppUpdate,
        apkFile: File,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        isTrusted: (String) -> Boolean,
    ): File? {
        val url = update.profileUrl?.takeIf { it.isNotBlank() } ?: return null
        val sha = update.profileSha256?.takeIf { it.isNotBlank() } ?: return null
        if (!isTrusted(url)) {
            Log.w(TAG, "Untrusted profile URL, skipping: $url")
            return null
        }
        val dest = File(apkFile.parentFile, InstallProfile.fileNameFor(apkFile.name))

        if (dest.exists() && acceptable(dest, sha)) return dest

        val file = downloader.download(url, dest, timeoutMs) { _, _ -> }.getOrElse {
            Log.w(TAG, "Profile download failed: ${it.message}")
            return null
        }
        if (!acceptable(file, sha)) {
            Log.w(TAG, "Profile failed its checksum or content check; deleting ${file.name}")
            runCatching { file.delete() }
            return null
        }
        return file
    }

    private fun acceptable(file: File, sha: String): Boolean =
        runCatching { Checksum.verify(file, sha) && onlyProfileEntries(file) }.getOrDefault(false)

    private fun onlyProfileEntries(file: File): Boolean =
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            names.isNotEmpty() && names.all { it in ALLOWED_ENTRIES }
        }
}
