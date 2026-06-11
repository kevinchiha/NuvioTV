package com.nuvio.tv.updater

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * Schedules the once-a-day [UpdateCheckWorker] plus a prompt one-time kick. Idempotent — safe to
 * call on every app start. The CONNECTED constraint gates only the cheap version check; the
 * worker itself enforces unmetered-only for the actual APK download.
 */
object UpdateWorkScheduler {

    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val wm = WorkManager.getInstance(context)

        val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

        // UPDATE (not KEEP): a future change to the period/constraints/backoff propagates to
        // already-installed TVs instead of being frozen at the first-ever enqueue. An unchanged
        // request is a no-op (no reschedule), so this stays safe to call on every app start.
        wm.enqueueUniquePeriodicWork(
            UpdateCheckWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )

        // PeriodicWork's first run is deferred up to the interval (~24h). Kick a one-time run so a
        // freshly set-up TV pre-downloads promptly. KEEP = at most one in flight; the worker's own
        // debug/trust/metered/already-cached gates still apply.
        val initial = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(constraints)
            .build()
        wm.enqueueUniqueWork(
            UpdateCheckWorker.UNIQUE_NAME_ONESHOT,
            ExistingWorkPolicy.KEEP,
            initial,
        )
    }
}
