package com.nuvio.tv.updater

import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.updater.model.AppUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class SilentInstallTest {

    private val live = 1030
    private val nowMs = 5_000_000L
    private val userId = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
    private val email = "member@example.com"

    private fun update(afterMs: Long = nowMs - 1, earlyUsers: List<String> = emptyList()) = AppUpdate(
        versionCode = live,
        tag = "v$live",
        title = "Title",
        notes = "notes",
        sha256 = "abc",
        releaseUrl = null,
        assetName = "kevbox-$live.apk",
        assetUrl = "https://tv.kevbox.dev/kevbox-$live.apk",
        assetSizeBytes = null,
        silentAfterMs = afterMs,
        silentEarlyUsers = earlyUsers,
    )

    private val outOfSight = QuietInput(
        startedActivities = 0,
        lastStartElapsedMs = null,
        lastStopElapsedMs = null,
        nowElapsedMs = 10_000_000L,
        externalPlaybackRunning = false,
        memberInstallRunning = false,
        installScreenWaiting = false,
        lastMemberCommitElapsedMs = null,
    )
    private val onScreen = outOfSight.copy(startedActivities = 1)

    /** Every check passes unless a test changes a field. Records each call in [calls]. */
    private class FakeEnv(
        var quiet: MutableList<QuietInput>,
        val auth: MutableStateFlow<AuthState> = MutableStateFlow(AuthState.SignedOut),
    ) : SilentInstallEnv {
        val calls = mutableListOf<String>()
        val lines = mutableListOf<String>()
        var authReads = 0
        var hashes = 0
        var attempts = 0
        var openThrows: Exception? = null
        var commitThrows: Exception? = null
        var recordAttemptHook: suspend () -> Unit = {}

        override val updaterEnabled = true
        override val sdkInt = 36
        override val currentVersionCode = 1028
        override fun canRequestInstalls() = true
        var path: String? = "/cache/updates/kevbox-1030.apk"
        var exists = true
        var shaOk = true
        val committed = mutableSetOf<Int>()
        val discarded = mutableListOf<String>()

        var boxNowMs = 5_000_000L
        override fun nowMs() = boxNowMs
        override suspend fun predownloadPath(): String? = path
        override suspend fun predownloadVersionCode(): Int? = 1030
        override fun fileExists(path: String) = exists
        override fun sha256Matches(path: String, sha256: String): Boolean {
            hashes++
            return shaOk
        }
        override suspend fun ignoredVersionCode(): Int? = null
        override suspend fun silentState(): SilentInstallState? = null
        override fun authState(): Flow<AuthState> {
            authReads++
            return auth
        }
        override fun quietInput(): QuietInput {
            calls += "quiet"
            return if (quiet.size > 1) quiet.removeAt(0) else quiet.first()
        }
        override suspend fun fetchProfile(live: AppUpdate, apkFile: File): File? {
            calls += "profile"
            return File("/cache/updates/kevbox-1030.dm")
        }
        override fun openSession(apkFile: File, profileFile: File?): Int {
            calls += "open"
            openThrows?.let { throw it }
            return 42
        }
        override fun commitSession(sessionId: Int, versionCode: Int, profileName: String?) {
            calls += "commit:$sessionId:$versionCode:$profileName"
            commitThrows?.let { throw it }
        }
        override fun abandonSession(sessionId: Int) {
            calls += "abandon:$sessionId"
        }
        override suspend fun recordAttempt(versionCode: Int) {
            recordAttemptHook()
            calls += "record:$versionCode"
            attempts++
        }
        override fun alreadyCommitted(versionCode: Int) = versionCode in committed
        override fun rememberCommitted(versionCode: Int) {
            committed += versionCode
        }
        override suspend fun discardPredownload(path: String) {
            calls += "discard:$path"
            discarded += path
        }
        override fun log(message: String) {
            lines += message
        }
        override fun warn(message: String) {
            lines += message
        }
    }

    private fun env(vararg quiet: QuietInput) = FakeEnv(quiet.toMutableList())

    private fun sessionCalls(env: FakeEnv) =
        env.calls.filter { it.startsWith("open") || it.startsWith("commit") || it.startsWith("abandon") }

    @Test
    fun `an eligible attempt logs one check line and commits with the profile`() = runTest {
        val env = env(outOfSight)
        SilentInstall.run(env, Result.success(update()), SilentTrigger.JOB, Mutex())

        assertEquals("Silent check [job] versionCode=1030: ELIGIBLE early=false", env.lines.single())
        assertEquals(listOf("open", "commit:42:1030:kevbox-1030.dm"), sessionCalls(env))
        assertEquals(1, env.attempts)
    }

    @Test
    fun `a failed second quiet check abandons the session and counts no attempt`() = runTest {
        val env = env(outOfSight, onScreen)
        SilentInstall.run(env, Result.success(update()), SilentTrigger.JOB, Mutex())

        assertEquals(listOf("open", "abandon:42"), sessionCalls(env))
        assertEquals(0, env.attempts)
        assertTrue(env.lines.contains("Silent session 42 abandoned before commit: APP_ON_SCREEN"))
    }

    @Test
    fun `a session write that throws counts one attempt and commits nothing`() = runTest {
        val env = env(outOfSight).apply { openThrows = IOException("disk full at /data/app/secret") }
        SilentInstall.run(env, Result.success(update()), SilentTrigger.DAILY_CHECK, Mutex())

        assertEquals(listOf("open"), sessionCalls(env))
        assertEquals(1, env.attempts)
        assertEquals(
            "Silent session for versionCode 1030 could not be written (attempt counted): IOException",
            env.lines.last(),
        )
        assertFalse(env.lines.any { it.contains("secret") })
    }

    @Test
    fun `the attempt is recorded before commit`() = runTest {
        val env = env(outOfSight)
        SilentInstall.run(env, Result.success(update()), SilentTrigger.JOB, Mutex())

        assertEquals(
            listOf("quiet", "profile", "open", "quiet", "record:1030", "commit:42:1030:kevbox-1030.dm"),
            env.calls,
        )
    }

    @Test
    fun `a refused commit abandons the session and the attempt stays counted`() = runTest {
        val env = env(outOfSight).apply { commitThrows = SecurityException("token=xyz") }
        SilentInstall.run(env, Result.success(update()), SilentTrigger.JOB, Mutex())

        assertEquals(listOf("open", "commit:42:1030:kevbox-1030.dm", "abandon:42"), sessionCalls(env))
        assertEquals(1, env.attempts)
        assertEquals("Silent commit failed for session 42: SecurityException", env.lines.last())
        assertFalse(env.lines.any { it.contains("xyz") })
    }

    @Test
    fun `the account is read only on NOT_YET with earlyUsers, and an early user proceeds`() = runTest {
        // after has passed: no account read.
        val passed = env(outOfSight)
        passed.auth.value = AuthState.FullAccount(userId, email)
        SilentInstall.run(passed, Result.success(update(earlyUsers = listOf(userId))), SilentTrigger.JOB, Mutex())
        assertEquals(0, passed.authReads)

        // after not passed, no earlyUsers: no account read.
        val noEarly = env(outOfSight)
        noEarly.auth.value = AuthState.FullAccount(userId, email)
        SilentInstall.run(noEarly, Result.success(update(afterMs = nowMs + 1)), SilentTrigger.JOB, Mutex())
        assertEquals(0, noEarly.authReads)
        assertEquals("Silent check [job] versionCode=1030: NOT_YET early=false", noEarly.lines.single())

        // after not passed, this account is early: read once, proceeds.
        val early = env(outOfSight)
        early.auth.value = AuthState.FullAccount(userId, email)
        val earlyUpdate = update(afterMs = nowMs + 1, earlyUsers = listOf(" ${userId.uppercase()} "))
        SilentInstall.run(early, Result.success(earlyUpdate), SilentTrigger.JOB, Mutex())
        assertEquals(1, early.authReads)
        assertEquals("Silent check [job] versionCode=1030: ELIGIBLE early=true", early.lines.first())
        assertEquals(listOf("open", "commit:42:1030:kevbox-1030.dm"), sessionCalls(early))

        for (env in listOf(passed, noEarly, early)) {
            assertFalse(env.lines.any { it.contains(userId, ignoreCase = true) || it.contains(email) })
        }
    }

    private val twoDaysMs = 2 * 24 * 60 * 60 * 1000L

    @Test
    fun `a box clock 2 days ahead still waits when the server time is before after`() = runTest {
        val env = env(outOfSight).apply { boxNowMs = nowMs + twoDaysMs }
        val update = update(afterMs = nowMs + 1).copy(serverTimeMs = nowMs)
        SilentInstall.run(env, Result.success(update), SilentTrigger.JOB, Mutex())

        assertEquals("Silent check [job] versionCode=1030: NOT_YET early=false", env.lines.single())
        assertEquals(emptyList<String>(), sessionCalls(env))
    }

    @Test
    fun `a box clock 2 days behind installs once the server time is past after`() = runTest {
        val env = env(outOfSight).apply { boxNowMs = nowMs - twoDaysMs }
        val update = update(afterMs = nowMs - 1).copy(serverTimeMs = nowMs)
        SilentInstall.run(env, Result.success(update), SilentTrigger.JOB, Mutex())

        assertEquals("Silent check [job] versionCode=1030: ELIGIBLE early=false", env.lines.single())
        assertEquals(listOf("open", "commit:42:1030:kevbox-1030.dm"), sessionCalls(env))
    }

    @Test
    fun `the early-user evaluation uses the server time too`() = runTest {
        // Box clock past after, server time before it: only an early user proceeds.
        val env = env(outOfSight).apply { boxNowMs = nowMs + twoDaysMs }
        env.auth.value = AuthState.FullAccount("someone-else", email)
        val update = update(afterMs = nowMs + 1, earlyUsers = listOf(userId)).copy(serverTimeMs = nowMs)
        SilentInstall.run(env, Result.success(update), SilentTrigger.JOB, Mutex())

        assertEquals(1, env.authReads)
        assertEquals("Silent check [job] versionCode=1030: NOT_YET early=false", env.lines.single())
    }

    @Test
    fun `an account still loading after 15 s counts as not early`() = runTest {
        val env = env(outOfSight)
        env.auth.value = AuthState.Loading
        val update = update(afterMs = nowMs + 1, earlyUsers = listOf(userId))
        SilentInstall.run(env, Result.success(update), SilentTrigger.JOB, Mutex())

        assertEquals(SilentInstall.ACCOUNT_WAIT_MS, testScheduler.currentTime)
        assertEquals(1, env.authReads)
        assertEquals("Silent check [job] versionCode=1030: NOT_YET early=false", env.lines.single())
        assertEquals(emptyList<String>(), sessionCalls(env))
    }

    @Test
    fun `a missing pre-download file gives NO_PREDOWNLOAD and touches no session`() = runTest {
        val env = env(outOfSight).apply { exists = false }
        SilentInstall.run(env, Result.success(update()), SilentTrigger.JOB, Mutex())

        assertEquals("Silent check [job] versionCode=1030: NO_PREDOWNLOAD early=false", env.lines.single())
        assertEquals(0, env.hashes)
        assertEquals(emptyList<String>(), sessionCalls(env))
        assertEquals(emptyList<String>(), env.discarded)
    }

    @Test
    fun `no stored pre-download path gives NO_PREDOWNLOAD and touches no session`() = runTest {
        val env = env(outOfSight).apply { path = null }
        SilentInstall.run(env, Result.success(update()), SilentTrigger.JOB, Mutex())

        assertEquals("Silent check [job] versionCode=1030: NO_PREDOWNLOAD early=false", env.lines.single())
        assertEquals(0, env.hashes)
        assertEquals(emptyList<String>(), sessionCalls(env))
    }

    @Test
    fun `a checksum mismatch touches no session and discards the pre-download`() = runTest {
        val env = env(outOfSight).apply { shaOk = false }
        SilentInstall.run(env, Result.success(update()), SilentTrigger.DAILY_CHECK, Mutex())

        assertEquals(
            "Silent check [daily-check] versionCode=1030: PREDOWNLOAD_CHECKSUM_MISMATCH early=false",
            env.lines.first(),
        )
        assertEquals(1, env.hashes)
        assertEquals(emptyList<String>(), sessionCalls(env))
        assertEquals(0, env.attempts)
        assertEquals(listOf("/cache/updates/kevbox-1030.apk"), env.discarded)
    }

    @Test
    fun `a second attempt for a version this process committed touches no session`() = runTest {
        val env = env(outOfSight)
        SilentInstall.run(env, Result.success(update()), SilentTrigger.JOB, Mutex())
        SilentInstall.run(env, Result.success(update()), SilentTrigger.DAILY_CHECK, Mutex())

        assertEquals(
            "Silent check [daily-check] versionCode=1030: ALREADY_COMMITTED early=false",
            env.lines.last(),
        )
        assertEquals(listOf("open", "commit:42:1030:kevbox-1030.dm"), sessionCalls(env))
        assertEquals(1, env.attempts)
    }

    @Test
    fun `a refused commit is not remembered, so a later attempt may run`() = runTest {
        val env = env(outOfSight).apply { commitThrows = SecurityException("refused") }
        SilentInstall.run(env, Result.success(update()), SilentTrigger.JOB, Mutex())

        assertTrue(env.committed.isEmpty())
    }

    @Test
    fun `a held lock logs ANOTHER_SILENT_ATTEMPT and touches no session`() = runTest {
        val env = env(outOfSight)
        val lock = Mutex()
        lock.lock()
        SilentInstall.run(env, Result.success(update()), SilentTrigger.JOB, lock)

        assertEquals("Silent check [job] versionCode=1030: ANOTHER_SILENT_ATTEMPT early=false", env.lines.single())
        assertEquals(emptyList<String>(), env.calls)
        assertEquals(0, env.attempts)
        assertTrue(lock.isLocked)
    }

    @Test
    fun `the lock is free again after an attempt`() = runTest {
        val lock = Mutex()
        SilentInstall.run(env(outOfSight), Result.success(update()), SilentTrigger.JOB, lock)
        assertFalse(lock.isLocked)
    }

    @Test
    fun `an app on screen logs its reason and never hashes the APK`() = runTest {
        val env = env(onScreen)
        SilentInstall.run(env, Result.success(update()), SilentTrigger.DAILY_CHECK, Mutex())

        assertEquals("Silent check [daily-check] versionCode=1030: APP_ON_SCREEN early=false", env.lines.single())
        assertEquals(0, env.hashes)
        assertEquals(listOf("quiet"), env.calls)
    }

    @Test
    fun `a failed manifest fetch logs versionCode none`() = runTest {
        val env = env(outOfSight)
        SilentInstall.run(env, Result.failure(IOException("offline")), SilentTrigger.JOB, Mutex())

        assertEquals("Silent check [job] versionCode=none: MANIFEST_FETCH_FAILED early=false", env.lines.single())
        assertEquals(emptyList<String>(), sessionCalls(env))
    }

    @Test
    fun `an unexpected failure is logged by class name and never thrown`() = runTest {
        val env = env(outOfSight)
        env.recordAttemptHook = { throw IllegalStateException("user $userId") }
        val lock = Mutex()
        SilentInstall.run(env, Result.success(update()), SilentTrigger.JOB, lock)

        assertEquals("Silent attempt failed: IllegalStateException", env.lines.last())
        assertTrue(env.calls.contains("abandon:42"))
        assertFalse(env.lines.any { it.contains(userId) })
        assertFalse(lock.isLocked)
    }

    @Test
    fun `a cancelled attempt after the session is written abandons it and frees the lock`() = runTest {
        val env = env(outOfSight)
        val recording = CompletableDeferred<Unit>()
        env.recordAttemptHook = {
            recording.complete(Unit)
            awaitCancellation()
        }
        val lock = Mutex()
        val job = launch { SilentInstall.run(env, Result.success(update()), SilentTrigger.JOB, lock) }
        recording.await()
        job.cancel()
        job.join()

        assertEquals(listOf("open", "abandon:42"), sessionCalls(env))
        assertTrue(job.isCancelled)
        assertFalse(lock.isLocked)
    }
}
