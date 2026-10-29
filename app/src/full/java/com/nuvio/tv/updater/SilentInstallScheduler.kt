package com.nuvio.tv.updater

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import java.util.concurrent.TimeUnit

/**
 * Queues [SilentInstallWorker] when the app leaves the screen (AC-4). The job runs 15 minutes
 * later and checks everything again, so this only filters out boxes with nothing to install.
 */
object SilentInstallScheduler {
    // Outlives any screen: the last activity is stopping when this runs.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called when the last started activity stops. Returns at once; reads the cache off the main thread. */
    fun onAppLeftScreen(context: Context) {
        val appContext = context.applicationContext ?: context
        scope.launch {
            try {
                val prefs = EntryPointAccessors
                    .fromApplication(appContext, UpdateWorkerEntryPoint::class.java)
                    .updatePreferences()
                val cached = prefs.predownloadUpdateJson.first()
                    ?.let { runCatching { UpdateJson.json.decodeFromString<AppUpdate>(it) }.getOrNull() }
                if (cached == null ||
                    !shouldQueue(cached, BuildConfig.VERSION_CODE, UpdaterBuild.enabled, Build.VERSION.SDK_INT)
                ) {
                    return@launch
                }
                val request = OneTimeWorkRequestBuilder<SilentInstallWorker>()
                    .setInitialDelay(SilentInstallWorker.DELAY_MS, TimeUnit.MILLISECONDS)
                    .build()
                // REPLACE: leaving again restarts the 15-minute clock from this leave.
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    SilentInstallWorker.UNIQUE_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
                Log.i(
                    SILENT_LOG_TAG,
                    "Silent job queued (versionCode ${cached.versionCode}, " +
                        "delay ${SilentInstallWorker.DELAY_MS / 1000} s)",
                )
            } catch (e: Exception) {
                Log.w(SILENT_LOG_TAG, "Silent job not queued: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Pure: whether leaving the screen should queue the job. */
    fun shouldQueue(cached: AppUpdate?, currentVersionCode: Int, updaterEnabled: Boolean, sdkInt: Int): Boolean {
        if (!updaterEnabled) return false
        if (sdkInt < SilentInstallDecision.MIN_SDK) return false
        if (cached == null) return false
        if (cached.versionCode <= currentVersionCode) return false
        return cached.silentBlock() != null
    }
}
