package com.nuvio.tv.updater

import android.content.Context
import android.os.Build
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import java.io.File

/** What started a silent attempt; shown in the evaluation log line. */
enum class SilentTrigger(val label: String) { JOB("job"), DAILY_CHECK("daily-check") }

/**
 * Every Android, clock, file and DataStore call a silent attempt makes, so the sequence in
 * [SilentInstall] runs in unit tests against a fake.
 */
internal interface SilentInstallEnv {
    val updaterEnabled: Boolean
    val sdkInt: Int
    val currentVersionCode: Int
    fun canRequestInstalls(): Boolean
    fun nowMs(): Long
    suspend fun predownloadPath(): String?
    /** versionCode of the cached pre-download's AppUpdate JSON, or null. */
    suspend fun predownloadVersionCode(): Int?
    fun fileExists(path: String): Boolean
    /** Hashes the whole file. */
    fun sha256Matches(path: String, sha256: String): Boolean
    suspend fun ignoredVersionCode(): Int?
    suspend fun silentState(): SilentInstallState?
    /** The account state. Called only when the account has to be read. */
    fun authState(): Flow<AuthState>
    fun quietInput(): QuietInput
    suspend fun fetchProfile(live: AppUpdate, apkFile: File): File?
    /** Creates the silent session and writes the files. Throws when that fails. */
    fun openSession(apkFile: File, profileFile: File?): Int
    /** Throws when Android refuses the commit; the session is then still open. */
    fun commitSession(sessionId: Int, versionCode: Int, profileName: String?)
    /** Never throws. */
    fun abandonSession(sessionId: Int)
    suspend fun recordAttempt(versionCode: Int)
    /** Whether this process already committed a silent session for [versionCode]. */
    fun alreadyCommitted(versionCode: Int): Boolean
    /** Remembers, for this process, that [versionCode] was committed. */
    fun rememberCommitted(versionCode: Int)
    /**
     * Deletes the cached APK at [path] and its compile profile, and clears the pre-download
     * pointer while it still names [path], so the next daily check downloads the APK again.
     */
    suspend fun discardPredownload(path: String)
    fun log(message: String)
    fun warn(message: String)
}

object SilentInstall {
    /** How long to wait for a cold process's AuthManager to leave Loading. */
    internal const val ACCOUNT_WAIT_MS = 15_000L

    /** One silent attempt for [live] (this run's version.json result). Never throws. */
    suspend fun attempt(context: Context, live: Result<AppUpdate>, trigger: SilentTrigger) {
        withContext(Dispatchers.IO) {
            run(AndroidSilentInstallEnv(context.applicationContext), live, trigger)
        }
    }

    /** [attempt] against [env]; [lock] is the process-wide one outside tests. */
    internal suspend fun run(
        env: SilentInstallEnv,
        live: Result<AppUpdate>,
        trigger: SilentTrigger,
        lock: Mutex = SilentInstallGuard.attemptLock,
    ) {
        val update = live.getOrNull()
        if (!lock.tryLock()) {
            env.log(checkLine(trigger, update, SilentReason.ANOTHER_SILENT_ATTEMPT, early = false))
            return
        }
        try {
            sequence(env, update, trigger)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            env.warn("Silent attempt failed: ${t.javaClass.simpleName}")
        } finally {
            lock.unlock()
        }
    }

    private suspend fun sequence(env: SilentInstallEnv, update: AppUpdate?, trigger: SilentTrigger) {
        // A trigger that took the lock after another one committed this version: Android is
        // installing it, so a second commit would only count a second attempt.
        if (update != null && env.alreadyCommitted(update.versionCode)) {
            env.log(checkLine(trigger, update, SilentReason.ALREADY_COMMITTED, early = false))
            return
        }

        var early = false
        var apkPath: String? = null

        // Quiet first: a box whose member is back on screen never hashes the APK.
        val quiet = SilentInstallDecision.quiet(env.quietInput())
        val reason = if (quiet != SilentReason.ELIGIBLE) {
            quiet
        } else {
            val path = env.predownloadPath()
            apkPath = path
            val shaMatches by lazy {
                path != null && update != null && env.sha256Matches(path, update.sha256)
            }
            val input = SilentEligibilityInput(
                updaterEnabled = env.updaterEnabled,
                sdkInt = env.sdkInt,
                canRequestInstalls = env.canRequestInstalls(),
                liveUpdate = update,
                currentVersionCode = env.currentVersionCode,
                cachedVersionCode = env.predownloadVersionCode(),
                cachedFileExists = path != null && env.fileExists(path),
                cachedSha256Matches = { shaMatches },
                ignoredVersionCode = env.ignoredVersionCode(),
                nowMs = env.nowMs(),
                signedInUserId = null,
                silentState = env.silentState(),
            )
            val first = SilentInstallDecision.eligibility(input)
            val earlyUsers = update?.silentBlock()?.earlyUsers.orEmpty()
            if (first == SilentReason.NOT_YET && earlyUsers.isNotEmpty()) {
                val userId = signedInUserId(env)
                early = SilentInstallDecision.isEarlyUser(userId, earlyUsers)
                SilentInstallDecision.eligibility(input.copy(signedInUserId = userId))
            } else {
                first
            }
        }

        env.log(checkLine(trigger, update, reason, early))
        if (reason == SilentReason.PREDOWNLOAD_CHECKSUM_MISMATCH && apkPath != null) {
            // The worker never re-hashes a cached APK, so without this it would keep the bad file
            // and never download the version again.
            env.discardPredownload(apkPath)
            env.warn("Pre-download for versionCode ${update?.versionCode} failed its checksum; deleted")
        }
        if (reason != SilentReason.ELIGIBLE) return
        // ELIGIBLE implies both, through the pre-download and manifest checks.
        val live = update ?: return
        val apkFile = File(apkPath ?: return)
        val versionCode = live.versionCode

        val profile = env.fetchProfile(live, apkFile)

        val sessionId = try {
            env.openSession(apkFile, profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            env.recordAttempt(versionCode)
            env.warn(
                "Silent session for versionCode $versionCode could not be written (attempt counted): " +
                    e.javaClass.simpleName
            )
            return
        }

        // From here the open session holds a full copy of the APK. Every path below either
        // commits it or abandons it; `settled` lets the finally abandon it on cancellation or
        // any other exception in between.
        var settled = false
        try {
            val again = SilentInstallDecision.quiet(env.quietInput())
            if (again != SilentReason.ELIGIBLE) {
                env.abandonSession(sessionId)
                settled = true
                env.log("Silent session $sessionId abandoned before commit: $again")
                return
            }

            // Recorded and awaited before commit: Android may kill this process for the install
            // before the result broadcast arrives.
            env.recordAttempt(versionCode)
            settled = true
            try {
                env.commitSession(sessionId, versionCode, profile?.name)
                env.rememberCommitted(versionCode)
            } catch (e: Exception) {
                env.abandonSession(sessionId)
                env.warn("Silent commit failed for session $sessionId: ${e.javaClass.simpleName}")
            }
        } finally {
            if (!settled) withContext(NonCancellable) { env.abandonSession(sessionId) }
        }
    }

    /** Waits for the account to leave Loading; the user id of a FullAccount, else null. */
    private suspend fun signedInUserId(env: SilentInstallEnv): String? {
        val state = withTimeoutOrNull(ACCOUNT_WAIT_MS) {
            env.authState().first { it !is AuthState.Loading }
        }
        return (state as? AuthState.FullAccount)?.userId
    }

    private fun checkLine(trigger: SilentTrigger, update: AppUpdate?, reason: SilentReason, early: Boolean) =
        "Silent check [${trigger.label}] versionCode=${update?.versionCode ?: "none"}: $reason early=$early"
}

/** [SilentInstallEnv] on a real box. */
private class AndroidSilentInstallEnv(private val context: Context) : SilentInstallEnv {
    private val entry by lazy {
        EntryPointAccessors.fromApplication(context, UpdateWorkerEntryPoint::class.java)
    }
    private val prefs by lazy { entry.updatePreferences() }

    override val updaterEnabled: Boolean get() = UpdaterBuild.enabled
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    override fun canRequestInstalls(): Boolean = ApkInstaller.canRequestPackageInstalls(context)

    override fun nowMs(): Long = System.currentTimeMillis()

    override suspend fun predownloadPath(): String? = prefs.predownloadApkPath.first()

    override suspend fun predownloadVersionCode(): Int? = prefs.predownloadUpdateJson.first()
        ?.let { runCatching { UpdateJson.json.decodeFromString<AppUpdate>(it) }.getOrNull()?.versionCode }

    override fun fileExists(path: String): Boolean = File(path).exists()

    override fun sha256Matches(path: String, sha256: String): Boolean =
        runCatching { Checksum.verify(File(path), sha256) }.getOrDefault(false)

    override suspend fun ignoredVersionCode(): Int? = prefs.ignoredVersionCode.first()

    override suspend fun silentState(): SilentInstallState? = prefs.silentInstallState.first()

    override fun authState(): Flow<AuthState> = entry.authManager().authState

    override fun quietInput(): QuietInput = SilentInstallGuard.quietInput(context)

    override suspend fun fetchProfile(live: AppUpdate, apkFile: File): File? =
        ProfileFetcher.fetch(entry.apkDownloader(), live, apkFile) { UpdaterBuild.isTrusted(it) }

    // The SDK_TOO_OLD check has passed before any session call; the guards below make that
    // visible to lint and never trip in practice.
    override fun openSession(apkFile: File, profileFile: File?): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ApkInstaller.openSilentSession(context, apkFile, profileFile)
        }
        throw IllegalStateException("Silent sessions need Android 12")
    }

    override fun commitSession(sessionId: Int, versionCode: Int, profileName: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ApkInstaller.commitSilentSession(context, sessionId, versionCode, profileName)
            return
        }
        throw IllegalStateException("Silent sessions need Android 12")
    }

    override fun abandonSession(sessionId: Int) = ApkInstaller.abandonSession(context, sessionId)

    override suspend fun recordAttempt(versionCode: Int) = prefs.recordSilentAttempt(versionCode)

    override fun alreadyCommitted(versionCode: Int): Boolean =
        SilentInstallGuard.committedSilentVersion.isCommitted(versionCode)

    override fun rememberCommitted(versionCode: Int) =
        SilentInstallGuard.committedSilentVersion.remember(versionCode)

    override suspend fun discardPredownload(path: String) {
        val apk = File(path)
        runCatching { apk.delete() }
        runCatching { File(apk.parentFile, InstallProfile.fileNameFor(apk.name)).delete() }
        // A daily check may have stored a newer pre-download meanwhile; leave that one alone.
        if (prefs.predownloadApkPath.first() == path) prefs.clearPredownload()
    }

    override fun log(message: String) {
        Log.i(SILENT_LOG_TAG, message)
    }

    override fun warn(message: String) {
        Log.w(SILENT_LOG_TAG, message)
    }
}
