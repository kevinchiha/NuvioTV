package com.nuvio.tv.updater

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
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
 * The versionCode this process committed a silent session for (AC-8). Android installs and kills
 * the process soon after; until then another trigger must not commit the same version again.
 * A result other than success forgets it, so a failed install can be tried again.
 */
internal class CommittedSilentVersion {
    private var versionCode: Int? = null

    @Synchronized
    fun remember(versionCode: Int) {
        this.versionCode = versionCode
    }

    @Synchronized
    fun isCommitted(versionCode: Int): Boolean = this.versionCode == versionCode

    /** Forgets [versionCode] only; a later commit of another version stays. */
    @Synchronized
    fun forget(versionCode: Int) {
        if (this.versionCode == versionCode) this.versionCode = null
    }
}

/**
 * Live facts that keep a silent install away while the app is in use (AC-3, AC-10), and the
 * lock that lets only one silent attempt run per process at a time.
 */
object SilentInstallGuard {
    private const val KEEP_ALIVE_SERVICE = "com.nuvio.tv.core.player.ExternalPlaybackKeepAliveService"

    /** AC-10: one silent attempt per process at a time. */
    val attemptLock: Mutex = Mutex()

    /** AC-8: the version this process already committed silently. */
    internal val committedSilentVersion = CommittedSilentVersion()

    private val members = MemberInstallLog()

    fun memberInstallStarted() = members.started()

    fun memberInstallFinished() = members.finished()

    val memberInstallRunning: Boolean get() = members.isRunning

    /**
     * Called right after the member's session commit, and when a member install screen opens.
     * Starts the 10-minute wait (AC-10). Never throws, so the install goes on.
     */
    fun memberSessionCommitted(nowElapsedMs: Long) {
        try {
            members.committed(nowElapsedMs, AppVisibilityTracker.store)
        } catch (t: Throwable) {
            Log.w(SILENT_LOG_TAG, "Could not store the member commit time: ${t.javaClass.simpleName}")
        }
    }

    /**
     * True while the external-player keep-alive service runs. `getRunningServices` is deprecated
     * but still lists the caller's own services, so the upstream service needs no change. A check
     * that cannot run counts as playback running: the silent install waits for the next trigger.
     */
    fun isExternalPlaybackRunning(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (manager == null) {
                Log.w(SILENT_LOG_TAG, "No ActivityManager; counting external playback as running")
                return true
            }
            @Suppress("DEPRECATION")
            manager.getRunningServices(Int.MAX_VALUE).orEmpty().any {
                it.service.packageName == context.packageName && it.service.className == KEEP_ALIVE_SERVICE
            }
        } catch (t: Throwable) {
            Log.w(SILENT_LOG_TAG, "Could not read running services: ${t.javaClass.simpleName}")
            true
        }
    }

    /**
     * True while any audio plays (`AudioManager.isMusicActive`): the app's players, a trailer,
     * or another app. A state that cannot be read counts as playing, so the idle-on-screen path
     * waits for the next trigger.
     */
    fun isPlaying(context: Context): Boolean {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audio == null) {
                Log.w(SILENT_LOG_TAG, "No AudioManager; counting audio as playing")
                return true
            }
            audio.isMusicActive
        } catch (t: Throwable) {
            Log.w(SILENT_LOG_TAG, "Could not read the audio state: ${t.javaClass.simpleName}")
            true
        }
    }

    fun quietInput(context: Context): QuietInput = buildQuietInput(
        startedActivities = if (AppVisibilityTracker.isRegistered) AppVisibilityTracker.startedActivities else null,
        store = AppVisibilityTracker.storeFor(context),
        members = members,
        nowElapsedMs = SystemClock.elapsedRealtime(),
        externalPlaybackRunning = isExternalPlaybackRunning(context),
        installScreenWaiting = PendingInstallScreen.pending.value != null,
        playing = isPlaying(context),
        lastKeyPressElapsedMs = AppVisibilityTracker.lastKeyPressElapsedMs,
    )

    /**
     * [quietInput] with every Android fact passed in, for tests. A null [startedActivities] means
     * the tracker is not registered: visibility is unknown, so it counts as one started activity
     * that never goes idle.
     */
    internal fun buildQuietInput(
        startedActivities: Int?,
        store: VisibilityStore?,
        members: MemberInstallLog,
        nowElapsedMs: Long,
        externalPlaybackRunning: Boolean,
        installScreenWaiting: Boolean,
        playing: Boolean,
        lastKeyPressElapsedMs: Long?,
    ): QuietInput = QuietInput(
        startedActivities = startedActivities ?: 1,
        lastStartElapsedMs = store?.getLong(AppVisibilityTracker.KEY_LAST_START),
        lastStopElapsedMs = store?.getLong(AppVisibilityTracker.KEY_LAST_STOP),
        nowElapsedMs = nowElapsedMs,
        externalPlaybackRunning = externalPlaybackRunning,
        memberInstallRunning = members.isRunning,
        installScreenWaiting = installScreenWaiting,
        lastMemberCommitElapsedMs = members.lastCommitElapsedMs(store, nowElapsedMs),
        playing = playing,
        lastKeyPressElapsedMs = lastKeyPressElapsedMs,
        visibilityKnown = startedActivities != null,
    )
}
