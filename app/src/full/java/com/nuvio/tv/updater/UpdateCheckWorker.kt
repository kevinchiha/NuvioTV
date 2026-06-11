package com.nuvio.tv.updater

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Once-a-day background check. If a newer, non-ignored build exists on a trusted (https +
 * configured-host) URL and the device is on an unmetered network, downloads + SHA-256-verifies
 * the APK and caches its path + metadata so [UpdateViewModel] can offer an instant Install on
 * next app open. No notifications. Pruning runs on every invocation, keeping at most the one
 * APK still worth installing. Logs at decision points so a release smoke test can follow it.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Mirror the launch-time gate: never auto-act in debug/benchmark builds. The worker only
        // does real work in a release build (BuildConfig.IS_DEBUG_BUILD == false).
        if (BuildConfig.IS_DEBUG_BUILD) return Result.success()

        // The daily periodic job and the one-time startup kick can fire concurrently in the same
        // process; serialize so two runs never race on the shared cacheDir/updates files (the
        // loser then sees alreadyCached and cleanly no-ops). withLock releases on any return/throw.
        return runMutex.withLock { runCheck() }
    }

    private suspend fun runCheck(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            UpdateWorkerEntryPoint::class.java,
        )
        val repo = entry.updateRepository()
        val downloader = entry.apkDownloader()
        val prefs = entry.updatePreferences()

        val update = repo.getLatestUpdate().getOrElse {
            Log.w(TAG, "Update check failed; will retry: ${it.message}")
            return Result.retry()
        }
        prefs.setLastCheckAtMs(System.currentTimeMillis())

        val dir = File(applicationContext.cacheDir, "updates")
        val ignored = prefs.ignoredVersionCode.first()
        val cachedPath = prefs.predownloadApkPath.first()
        val cachedVersion = prefs.predownloadUpdateJson.first()
            ?.let { runCatching { UpdateJson.json.decodeFromString<AppUpdate>(it) }.getOrNull()?.versionCode }
        val cachedFileExists = cachedPath != null && File(cachedPath).exists()
        val alreadyCached = UpdateDownloadDecision.isCachedFor(cachedVersion, update.versionCode, cachedFileExists)

        // Keep an already-valid pre-download only while it is still an upgrade over the installed
        // build; otherwise nothing is worth keeping (prune clears everything).
        val targetIsUpgrade = update.versionCode > BuildConfig.VERSION_CODE
        var keepName: String? =
            if (targetIsUpgrade && alreadyCached && cachedPath != null) File(cachedPath).name else null

        val trusted = UpdateDownloadDecision.isTrustedAssetUrl(update.assetUrl, BuildConfig.UPDATE_BASE_URL)
        if (!trusted) Log.w(TAG, "Untrusted asset URL, skipping download: ${update.assetUrl}")

        val shouldDownload = trusted && UpdateDownloadDecision.shouldDownload(
            remoteVersionCode = update.versionCode,
            currentVersionCode = BuildConfig.VERSION_CODE,
            ignoredVersionCode = ignored,
            alreadyCachedForRemote = alreadyCached,
            isUnmetered = isUnmetered(applicationContext),
        )

        if (shouldDownload) {
            val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dest = File(dir, safeName)

            val file = downloader.download(update.assetUrl, dest) { _, _ -> }.getOrElse {
                Log.w(TAG, "Download failed; will retry: ${it.message}")
                pruneStale(dir, keepName)
                return Result.retry()
            }

            val verified = runCatching { Checksum.verify(file, update.sha256) }.getOrDefault(false)
            if (!verified) {
                Log.w(TAG, "SHA-256 mismatch for ${file.name}; deleting and retrying")
                runCatching { file.delete() }
                pruneStale(dir, keepName)
                return Result.retry()
            }

            prefs.setPredownload(file.absolutePath, UpdateJson.json.encodeToString(update))
            keepName = file.name
            Log.i(TAG, "Pre-downloaded + verified ${file.name} (versionCode ${update.versionCode})")
        } else {
            Log.i(
                TAG,
                "No download this run (trusted=$trusted, alreadyCached=$alreadyCached, " +
                    "remote=${update.versionCode}, current=${BuildConfig.VERSION_CODE})",
            )
        }

        pruneStale(dir, keepName)
        return Result.success()
    }

    private fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Keep only [keepName]; delete other finalized APKs. Skips in-progress `.part` files so a
     * concurrent foreground download (ApkDownloader streams to `<name>.part` then renames) is
     * never deleted mid-write.
     */
    private fun pruneStale(dir: File, keepName: String?) {
        val names = dir.listFiles()?.filterNot { it.name.endsWith(".part") }?.map { it.name } ?: return
        UpdateDownloadDecision.staleFileNames(names, keepName).forEach { name ->
            runCatching { File(dir, name).delete() }
        }
    }

    companion object {
        const val UNIQUE_NAME = "kevbox-daily-update-check"
        const val UNIQUE_NAME_ONESHOT = "kevbox-update-check-now"
        private const val TAG = "UpdateCheckWorker"

        // Process-wide lock so the periodic job and the one-time kick can't run the check
        // concurrently and race on the shared cache files.
        private val runMutex = Mutex()
    }
}
