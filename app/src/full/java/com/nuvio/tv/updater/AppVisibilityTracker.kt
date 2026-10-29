package com.nuvio.tv.updater

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.Window
import androidx.annotation.RequiresApi
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

    /** Returns how many activities are still started. */
    fun activityStopped(nowElapsedMs: Long): Int {
        val left = started.updateAndGet { (it - 1).coerceAtLeast(0) }
        store.putLong(AppVisibilityTracker.KEY_LAST_STOP, nowElapsedMs)
        return left
    }

    fun lastStartElapsedMs(): Long? = store.getLong(AppVisibilityTracker.KEY_LAST_START)

    fun lastStopElapsedMs(): Long? = store.getLong(AppVisibilityTracker.KEY_LAST_STOP)
}

/** When a remote key was last pressed in an activity window. In memory only, like the count. */
internal class KeyPressLog {
    @Volatile
    private var lastElapsedMs: Long? = null

    fun pressed(nowElapsedMs: Long) {
        lastElapsedMs = nowElapsedMs
    }

    /** Null until a key is pressed in this process. */
    fun lastPressElapsedMs(): Long? = lastElapsedMs
}

/**
 * Wraps an activity window's callback to record each key event in [keys], so the idle-on-screen
 * rule sees remote presses without an upstream change. Every call goes to [original] unchanged.
 */
internal class KeyRecordingCallback(
    private val original: Window.Callback,
    private val keys: KeyPressLog,
    private val clock: () -> Long,
) : Window.Callback by original {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        keys.pressed(clock())
        return original.dispatchKeyEvent(event)
    }

    // `by original` skips Java default methods; these two would otherwise never reach it.
    // Android calls each only on the API level that added it.
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) {
        original.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        original.onPointerCaptureChanged(hasCapture)
    }

    companion object {
        /** Wraps [window]'s callback, unless it is wrapped already or there is none. */
        fun install(window: Window, keys: KeyPressLog, clock: () -> Long) {
            val current = window.callback ?: return
            if (current is KeyRecordingCallback) return
            window.callback = KeyRecordingCallback(current, keys, clock)
        }
    }
}

/**
 * Tells whether any activity of the app is on screen, since when it is not, and when a remote key
 * was last pressed in one. Registered once from `PluginRuntimeHooks.onApplicationCreate`. Times
 * are `SystemClock.elapsedRealtime()` values. When the last started activity stops, it asks
 * [SilentInstallScheduler] to queue the silent job. It does not cancel a queued silent job when an
 * activity starts: that job checks again and stops.
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

    private val keys = KeyPressLog()

    /** The last key press in an activity window of this process, or null. */
    val lastKeyPressElapsedMs: Long? get() = keys.lastPressElapsedMs()

    fun register(application: Application) {
        synchronized(this) {
            if (log != null) return
            val opened = PrefsVisibilityStore(application)
            val visibility = VisibilityLog(opened)
            application.registerActivityLifecycleCallbacks(Callbacks(visibility, keys))
            store = opened
            log = visibility
        }
    }

    /**
     * False when [register] never ran or failed in this process: nothing counts activities, so
     * visibility is unknown and a silent install treats the app as on screen.
     */
    val isRegistered: Boolean get() = log != null

    /** Started activities; 0 when not registered, so read [isRegistered] first. */
    val startedActivities: Int get() = log?.startedActivities ?: 0

    internal fun storeFor(context: Context): VisibilityStore =
        store ?: PrefsVisibilityStore(context.applicationContext ?: context)

    private class Callbacks(
        private val visibility: VisibilityLog,
        private val keys: KeyPressLog,
    ) : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            visibility.activityStarted(SystemClock.elapsedRealtime())
            // A recorder that cannot be installed only costs the idle-on-screen path; the app goes on.
            try {
                KeyRecordingCallback.install(activity.window, keys, SystemClock::elapsedRealtime)
            } catch (t: Throwable) {
                Log.w(SILENT_LOG_TAG, "Could not record key presses: ${t.javaClass.simpleName}")
            }
        }

        override fun onActivityStopped(activity: Activity) {
            // The stop time is stored first: the queued job measures its 15 minutes from it.
            val left = visibility.activityStopped(SystemClock.elapsedRealtime())
            // A rotation or other config change stops and restarts the activity at once.
            if (left == 0 && !activity.isChangingConfigurations) {
                SilentInstallScheduler.onAppLeftScreen(activity.applicationContext)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
