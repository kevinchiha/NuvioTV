package com.nuvio.tv.updater

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** Long values kept across process death. A small seam so the logic runs in unit tests. */
internal interface VisibilityStore {
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
}

/** [VisibilityStore] over the `kevbox_app_visibility` preferences file, written with `apply()`. */
internal class PrefsVisibilityStore(context: Context) : VisibilityStore {
    private val prefs = context.getSharedPreferences(AppVisibilityTracker.PREFS_NAME, Context.MODE_PRIVATE)

    override fun getLong(key: String): Long? =
        if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }
}

/**
 * Counts started activities and stores when one last started or stopped (AC-3). The count lives
 * in memory only: a cold process has no started activity, so it starts at 0.
 */
internal class VisibilityLog(private val store: VisibilityStore) {
    private val started = AtomicInteger(0)

    val startedActivities: Int get() = started.get()

    fun activityStarted(nowElapsedMs: Long) {
        started.incrementAndGet()
        store.putLong(AppVisibilityTracker.KEY_LAST_START, nowElapsedMs)
    }

    fun activityStopped(nowElapsedMs: Long) {
        started.updateAndGet { (it - 1).coerceAtLeast(0) }
        store.putLong(AppVisibilityTracker.KEY_LAST_STOP, nowElapsedMs)
    }

    fun lastStartElapsedMs(): Long? = store.getLong(AppVisibilityTracker.KEY_LAST_START)

    fun lastStopElapsedMs(): Long? = store.getLong(AppVisibilityTracker.KEY_LAST_STOP)
}

/**
 * Tells whether any activity of the app is on screen, and since when it is not. Registered once
 * from `PluginRuntimeHooks.onApplicationCreate`. Times are `SystemClock.elapsedRealtime()` values.
 * It does not cancel a queued silent job when an activity starts: that job checks again and stops.
 */
object AppVisibilityTracker {
    internal const val PREFS_NAME = "kevbox_app_visibility"
    internal const val KEY_LAST_START = "last_start_elapsed_ms"
    internal const val KEY_LAST_STOP = "last_stop_elapsed_ms"
    internal const val KEY_LAST_MEMBER_COMMIT = "last_member_commit_elapsed_ms"

    /** The store opened by [register]; null before it ran. */
    @Volatile
    internal var store: VisibilityStore? = null
        private set

    @Volatile
    private var log: VisibilityLog? = null

    fun register(application: Application) {
        synchronized(this) {
            if (log != null) return
            val opened = PrefsVisibilityStore(application)
            val visibility = VisibilityLog(opened)
            application.registerActivityLifecycleCallbacks(Callbacks(visibility))
            store = opened
            log = visibility
        }
    }

    val startedActivities: Int get() = log?.startedActivities ?: 0

    fun lastStartElapsedMs(context: Context): Long? = storeFor(context).getLong(KEY_LAST_START)

    fun lastStopElapsedMs(context: Context): Long? = storeFor(context).getLong(KEY_LAST_STOP)

    internal fun storeFor(context: Context): VisibilityStore =
        store ?: PrefsVisibilityStore(context.applicationContext ?: context)

    private class Callbacks(private val visibility: VisibilityLog) : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            visibility.activityStarted(SystemClock.elapsedRealtime())
        }

        override fun onActivityStopped(activity: Activity) {
            visibility.activityStopped(SystemClock.elapsedRealtime())
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
