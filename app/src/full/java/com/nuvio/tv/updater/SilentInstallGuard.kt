package com.nuvio.tv.updater

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the member's own Install flow is doing (AC-10): how many install jobs are copying a
 * session now, and when the last member session was committed. The commit time is also stored,
 * so a fresh process still waits after a member commit.
 */
internal class MemberInstallLog {
    private val running = AtomicInteger(0)

    @Volatile
    private var lastCommitElapsedMs: Long? = null

    val isRunning: Boolean get() = running.get() > 0

    fun started() {
        running.incrementAndGet()
    }

    fun finished() {
        running.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun committed(nowElapsedMs: Long, store: VisibilityStore?) {
        lastCommitElapsedMs = nowElapsedMs
        store?.putLong(AppVisibilityTracker.KEY_LAST_MEMBER_COMMIT, nowElapsedMs)
    }

    /**
     * The commit time seen by this process, else the stored one. A stored time above now means
     * the box rebooted since, so it is dropped.
     */
    fun lastCommitElapsedMs(store: VisibilityStore?, nowElapsedMs: Long): Long? =
        lastCommitElapsedMs
            ?: store?.getLong(AppVisibilityTracker.KEY_LAST_MEMBER_COMMIT)?.takeIf { it <= nowElapsedMs }
}

/**
 * Live facts that keep a silent install away while the app is in use (AC-3, AC-10), and the
 * lock that lets only one silent attempt run per process at a time.
 */
object SilentInstallGuard {
    private const val KEEP_ALIVE_SERVICE = "com.nuvio.tv.core.player.ExternalPlaybackKeepAliveService"

    /** AC-10: one silent attempt per process at a time. */
    val attemptLock: Mutex = Mutex()

    private val members = MemberInstallLog()

    fun memberInstallStarted() = members.started()

    fun memberInstallFinished() = members.finished()

    val memberInstallRunning: Boolean get() = members.isRunning

    /** Called right after the member's session commit. Never throws, so the install goes on. */
    fun memberSessionCommitted(nowElapsedMs: Long) {
        try {
            members.committed(nowElapsedMs, AppVisibilityTracker.store)
        } catch (t: Throwable) {
            Log.w(SILENT_LOG_TAG, "Could not store the member commit time: ${t.javaClass.simpleName}")
        }
    }

    val lastMemberCommitElapsedMs: Long?
        get() = members.lastCommitElapsedMs(AppVisibilityTracker.store, SystemClock.elapsedRealtime())

    /**
     * True while the external-player keep-alive service runs. `getRunningServices` is deprecated
     * but still lists the caller's own services, so the upstream service needs no change.
     */
    fun isExternalPlaybackRunning(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            @Suppress("DEPRECATION")
            manager.getRunningServices(Int.MAX_VALUE).orEmpty().any {
                it.service.packageName == context.packageName && it.service.className == KEEP_ALIVE_SERVICE
            }
        } catch (t: Throwable) {
            false
        }
    }

    fun quietInput(context: Context): QuietInput = buildQuietInput(
        startedActivities = AppVisibilityTracker.startedActivities,
        store = AppVisibilityTracker.storeFor(context),
        members = members,
        nowElapsedMs = SystemClock.elapsedRealtime(),
        externalPlaybackRunning = isExternalPlaybackRunning(context),
        installScreenWaiting = PendingInstallScreen.pending.value != null,
    )

    /** [quietInput] with every Android fact passed in, for tests. */
    internal fun buildQuietInput(
        startedActivities: Int,
        store: VisibilityStore?,
        members: MemberInstallLog,
        nowElapsedMs: Long,
        externalPlaybackRunning: Boolean,
        installScreenWaiting: Boolean,
    ): QuietInput = QuietInput(
        startedActivities = startedActivities,
        lastStartElapsedMs = store?.getLong(AppVisibilityTracker.KEY_LAST_START),
        lastStopElapsedMs = store?.getLong(AppVisibilityTracker.KEY_LAST_STOP),
        nowElapsedMs = nowElapsedMs,
        externalPlaybackRunning = externalPlaybackRunning,
        memberInstallRunning = members.isRunning,
        installScreenWaiting = installScreenWaiting,
        lastMemberCommitElapsedMs = members.lastCommitElapsedMs(store, nowElapsedMs),
    )
}
