package com.nuvio.tv.updater

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.Window
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
            playing = false,
            lastKeyPressElapsedMs = null,
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

        val input = SilentInstallGuard.buildQuietInput(
            startedActivities = 0,
            store = store,
            members = members,
            nowElapsedMs = now,
            externalPlaybackRunning = false,
            installScreenWaiting = false,
            playing = false,
            lastKeyPressElapsedMs = null,
        )
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
            playing = true,
            lastKeyPressElapsedMs = now - 42L,
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
                playing = true,
                lastKeyPressElapsedMs = now - 42L,
                visibilityKnown = true,
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
    fun `an unregistered tracker counts the app as on screen`() {
        val reason = SilentInstallDecision.quiet(
            SilentInstallGuard.buildQuietInput(
                startedActivities = null,
                store = quietStore(),
                members = MemberInstallLog(),
                nowElapsedMs = now,
                externalPlaybackRunning = false,
                installScreenWaiting = false,
                playing = false,
                lastKeyPressElapsedMs = null,
            )
        )
        assertEquals(SilentReason.APP_ON_SCREEN, reason)
    }

    @Test
    fun `a running-services check that throws counts as external playback running`() {
        val context = mockk<Context>()
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } throws SecurityException("denied")

        assertTrue(SilentInstallGuard.isExternalPlaybackRunning(context))
    }

    @Test
    fun `no ActivityManager counts as external playback running`() {
        val context = mockk<Context>()
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns null

        assertTrue(SilentInstallGuard.isExternalPlaybackRunning(context))
    }

    @Test
    fun `a committed silent version is remembered until a failed result forgets it`() {
        val committed = CommittedSilentVersion()
        assertFalse(committed.isCommitted(1030))

        committed.remember(1030)
        assertTrue(committed.isCommitted(1030))
        assertFalse(committed.isCommitted(1031))

        committed.forget(1029)
        assertTrue(committed.isCommitted(1030))

        committed.forget(1030)
        assertFalse(committed.isCommitted(1030))
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
                playing = false,
                lastKeyPressElapsedMs = null,
            )
        )
        assertEquals(SilentReason.APP_ON_SCREEN, reason)
    }

    // --- idle on screen: visibility, playback and key presses ---

    private val threeHoursMs = 10_800_000L

    /** One activity started 4 hours ago, nothing playing, no key pressed since. */
    private fun idleStore() = FakeStore().apply {
        values[AppVisibilityTracker.KEY_LAST_START] = now - 4 * 3_600_000L
    }

    private fun quietOnScreen(
        startedActivities: Int? = 1,
        playing: Boolean = false,
        lastKeyPressElapsedMs: Long? = null,
    ) = SilentInstallDecision.quiet(
        SilentInstallGuard.buildQuietInput(
            startedActivities = startedActivities,
            store = idleStore(),
            members = MemberInstallLog(),
            nowElapsedMs = now,
            externalPlaybackRunning = false,
            installScreenWaiting = false,
            playing = playing,
            lastKeyPressElapsedMs = lastKeyPressElapsedMs,
        )
    )

    @Test
    fun `a started activity left idle for 3 hours with nothing playing is eligible`() {
        assertEquals(SilentReason.ELIGIBLE, quietOnScreen())
        assertEquals(SilentReason.ELIGIBLE, quietOnScreen(lastKeyPressElapsedMs = now - threeHoursMs))
    }

    @Test
    fun `a key press within 3 hours or playing audio keeps the app on screen`() {
        assertEquals(SilentReason.APP_ON_SCREEN, quietOnScreen(lastKeyPressElapsedMs = now - threeHoursMs + 1))
        assertEquals(SilentReason.APP_ON_SCREEN, quietOnScreen(playing = true))
    }

    @Test
    fun `an unregistered tracker counts the app as on screen even after 3 idle hours`() {
        val input = SilentInstallGuard.buildQuietInput(
            startedActivities = null,
            store = idleStore(),
            members = MemberInstallLog(),
            nowElapsedMs = now,
            externalPlaybackRunning = false,
            installScreenWaiting = false,
            playing = false,
            lastKeyPressElapsedMs = null,
        )
        assertFalse(input.visibilityKnown)
        assertEquals(SilentReason.APP_ON_SCREEN, SilentInstallDecision.quiet(input))
    }

    @Test
    fun `isPlaying reads the music state of the AudioManager`() {
        val audio = mockk<AudioManager>()
        val context = mockk<Context>()
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audio

        every { audio.isMusicActive } returns true
        assertTrue(SilentInstallGuard.isPlaying(context))
        every { audio.isMusicActive } returns false
        assertFalse(SilentInstallGuard.isPlaying(context))
    }

    @Test
    fun `an audio state that cannot be read counts as playing`() {
        val throwing = mockk<Context>()
        every { throwing.getSystemService(Context.AUDIO_SERVICE) } throws SecurityException("denied")
        assertTrue(SilentInstallGuard.isPlaying(throwing))

        val none = mockk<Context>()
        every { none.getSystemService(Context.AUDIO_SERVICE) } returns null
        assertTrue(SilentInstallGuard.isPlaying(none))

        val audio = mockk<AudioManager>()
        every { audio.isMusicActive } throws IllegalStateException("dead")
        val broken = mockk<Context>()
        every { broken.getSystemService(Context.AUDIO_SERVICE) } returns audio
        assertTrue(SilentInstallGuard.isPlaying(broken))
    }

    @Test
    fun `the key log keeps the latest press`() {
        val keys = KeyPressLog()
        assertNull(keys.lastPressElapsedMs())
        keys.pressed(1_000L)
        keys.pressed(2_000L)
        assertEquals(2_000L, keys.lastPressElapsedMs())
    }

    @Test
    fun `the key recorder records each key event and passes every call through unchanged`() {
        val original = mockk<Window.Callback>(relaxed = true)
        val event = mockk<KeyEvent>()
        val touch = mockk<MotionEvent>()
        every { original.dispatchKeyEvent(event) } returns true
        every { original.dispatchTouchEvent(touch) } returns false
        val keys = KeyPressLog()
        var clock = 5_000L
        val recorder = KeyRecordingCallback(original, keys) { clock }

        assertTrue(recorder.dispatchKeyEvent(event))
        assertEquals(5_000L, keys.lastPressElapsedMs())
        clock = 7_000L
        every { original.dispatchKeyEvent(event) } returns false
        assertFalse(recorder.dispatchKeyEvent(event))
        assertEquals(7_000L, keys.lastPressElapsedMs())
        verify(exactly = 2) { original.dispatchKeyEvent(event) }

        assertFalse(recorder.dispatchTouchEvent(touch))
        recorder.onWindowFocusChanged(true)
        recorder.onPointerCaptureChanged(true)
        val shortcuts = mutableListOf<KeyboardShortcutGroup>()
        val menu = mockk<Menu>()
        recorder.onProvideKeyboardShortcuts(shortcuts, menu, 3)
        verify { original.dispatchTouchEvent(touch) }
        verify { original.onWindowFocusChanged(true) }
        // Java default methods, which Kotlin's `by` delegation does not forward on its own.
        verify { original.onPointerCaptureChanged(true) }
        verify { original.onProvideKeyboardShortcuts(shortcuts, menu, 3) }
        // Only key events count as a press.
        assertEquals(7_000L, keys.lastPressElapsedMs())
    }

    @Test
    fun `the key recorder is installed at most once per window`() {
        val original = mockk<Window.Callback>(relaxed = true)
        val window = mockk<Window>(relaxed = true)
        var current: Window.Callback? = original
        every { window.callback } answers { current }
        every { window.callback = any() } answers { current = firstArg() }
        val keys = KeyPressLog()

        KeyRecordingCallback.install(window, keys) { 1L }
        val installed = current
        assertTrue(installed is KeyRecordingCallback)
        KeyRecordingCallback.install(window, keys) { 1L }
        assertTrue(current === installed)
        verify(exactly = 1) { window.callback = any() }
    }

    @Test
    fun `a window without a callback is left alone`() {
        val window = mockk<Window>(relaxed = true)
        every { window.callback } returns null

        KeyRecordingCallback.install(window, KeyPressLog()) { 1L }
        verify(exactly = 0) { window.callback = any() }
    }
}
