package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentInstallGuardTest {

    private class FakeStore : VisibilityStore {
        val values = mutableMapOf<String, Long>()
        override fun getLong(key: String): Long? = values[key]
        override fun putLong(key: String, value: Long) {
            values[key] = value
        }
    }

    private val now = 10_000_000L

    /** Out of sight for a long time, nothing else in the way. */
    private fun quietStore() = FakeStore().apply {
        values[AppVisibilityTracker.KEY_LAST_START] = now - 3_600_000L
        values[AppVisibilityTracker.KEY_LAST_STOP] = now - 3_500_000L
    }

    private fun quiet(store: VisibilityStore?, members: MemberInstallLog) = SilentInstallDecision.quiet(
        SilentInstallGuard.buildQuietInput(
            startedActivities = 0,
            store = store,
            members = members,
            nowElapsedMs = now,
            externalPlaybackRunning = false,
            installScreenWaiting = false,
        )
    )

    @Test
    fun `member install running while started without finish, and not after finish`() {
        assertFalse(SilentInstallGuard.memberInstallRunning)
        SilentInstallGuard.memberInstallStarted()
        try {
            assertTrue(SilentInstallGuard.memberInstallRunning)
        } finally {
            SilentInstallGuard.memberInstallFinished()
        }
        assertFalse(SilentInstallGuard.memberInstallRunning)
    }

    @Test
    fun `two overlapping member installs stay running until both finish, and extra finishes do not go below zero`() {
        val members = MemberInstallLog()
        members.started()
        members.started()
        members.finished()
        assertTrue(members.isRunning)
        members.finished()
        assertFalse(members.isRunning)
        members.finished()
        members.started()
        assertTrue(members.isRunning)
    }

    @Test
    fun `quietInput carries the last member commit time`() {
        val store = quietStore()
        val members = MemberInstallLog()
        assertEquals(SilentReason.ELIGIBLE, quiet(store, members))

        members.committed(now - 60_000L, store)

        val input = SilentInstallGuard.buildQuietInput(0, store, members, now, false, false)
        assertEquals(now - 60_000L, input.lastMemberCommitElapsedMs)
        assertEquals(SilentReason.MEMBER_INSTALL_RECENT, quiet(store, members))
    }

    @Test
    fun `a fresh process reads the stored member commit time`() {
        val store = quietStore()
        MemberInstallLog().committed(now - 60_000L, store)

        assertEquals(SilentReason.MEMBER_INSTALL_RECENT, quiet(store, MemberInstallLog()))
    }

    @Test
    fun `a stored member commit time above now means a reboot and is dropped`() {
        val store = quietStore()
        store.values[AppVisibilityTracker.KEY_LAST_MEMBER_COMMIT] = now + 5_000L

        assertNull(MemberInstallLog().lastCommitElapsedMs(store, now))
        assertEquals(SilentReason.ELIGIBLE, quiet(store, MemberInstallLog()))
    }

    @Test
    fun `quietInput carries the member flags, the screen slot and the stored visibility times`() {
        val store = quietStore()
        val members = MemberInstallLog().apply { started() }

        val input = SilentInstallGuard.buildQuietInput(
            startedActivities = 2,
            store = store,
            members = members,
            nowElapsedMs = now,
            externalPlaybackRunning = true,
            installScreenWaiting = true,
        )

        assertEquals(
            QuietInput(
                startedActivities = 2,
                lastStartElapsedMs = now - 3_600_000L,
                lastStopElapsedMs = now - 3_500_000L,
                nowElapsedMs = now,
                externalPlaybackRunning = true,
                memberInstallRunning = true,
                installScreenWaiting = true,
                lastMemberCommitElapsedMs = null,
            ),
            input,
        )
    }

    @Test
    fun `a running member install keeps a silent install away`() {
        val members = MemberInstallLog().apply { started() }
        assertEquals(SilentReason.MEMBER_INSTALL_RUNNING, quiet(quietStore(), members))
    }

    @Test
    fun `the counter never goes below zero and a stop records the stop time`() {
        val store = FakeStore()
        val visibility = VisibilityLog(store)

        visibility.activityStopped(nowElapsedMs = 500L)
        assertEquals(0, visibility.startedActivities)
        assertEquals(500L, visibility.lastStopElapsedMs())
        assertNull(visibility.lastStartElapsedMs())

        visibility.activityStarted(nowElapsedMs = 1_000L)
        assertEquals(1, visibility.startedActivities)
        assertEquals(1_000L, visibility.lastStartElapsedMs())

        visibility.activityStopped(nowElapsedMs = 2_000L)
        visibility.activityStopped(nowElapsedMs = 3_000L)
        assertEquals(0, visibility.startedActivities)
        assertEquals(3_000L, visibility.lastStopElapsedMs())
        assertEquals(3_000L, store.values[AppVisibilityTracker.KEY_LAST_STOP])
        assertEquals(1_000L, store.values[AppVisibilityTracker.KEY_LAST_START])
    }

    @Test
    fun `a new activity start after the app left the screen counts as on screen`() {
        val store = FakeStore()
        val visibility = VisibilityLog(store)
        visibility.activityStarted(now - 3_600_000L)
        visibility.activityStopped(now - 3_500_000L)
        visibility.activityStarted(now - 1_000L)

        val reason = SilentInstallDecision.quiet(
            SilentInstallGuard.buildQuietInput(
                startedActivities = visibility.startedActivities,
                store = store,
                members = MemberInstallLog(),
                nowElapsedMs = now,
                externalPlaybackRunning = false,
                installScreenWaiting = false,
            )
        )
        assertEquals(SilentReason.APP_ON_SCREEN, reason)
    }
}
