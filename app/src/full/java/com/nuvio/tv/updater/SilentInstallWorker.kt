package com.nuvio.tv.updater

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors

/**
 * The job [SilentInstallScheduler] queues 15 minutes after the app leaves the screen (AC-4).
 * Fetches version.json and makes one silent attempt, which checks every condition again. It has
 * no network constraint: offline, the fetch fails and nothing installs. The attempt checks
 * "out of sight" first, so it logs MANIFEST_FETCH_FAILED only when the app is quiet; otherwise it
 * logs the on-screen or in-use reason.
 * Always succeeds: a retry would keep trying a box that is in use, and the next leave is the retry.
 */
class SilentInstallWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!UpdaterBuild.enabled) return Result.success()

        val repo = EntryPointAccessors
            .fromApplication(applicationContext, UpdateWorkerEntryPoint::class.java)
            .updateRepository()
        // A failed fetch is passed on as is: the attempt logs it and installs nothing.
        val live = repo.getLatestUpdate()
        SilentInstall.attempt(applicationContext, live, SilentTrigger.JOB)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "kevbox-silent-install"
        const val DELAY_MS = 900_000L
    }
}
