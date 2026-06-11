package com.nuvio.tv.core.telemetry

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatSchedulerTest {

    private fun scheduler(scope: TestScope, repo: TelemetryRepository) =
        HeartbeatScheduler(repo, scope, intervalMs = 1000L)

    // NOTE: the ticker is an infinite `while (isActive) { delay() }` loop, so NEVER call
    // advanceUntilIdle() while it is live (it never goes idle → hang). Use advanceTimeBy()+runCurrent()
    // to fire due beats, and always s.stop() before the test ends so the coroutine doesn't leak.

    @Test
    fun `start emits a session_start then a playback beat each interval`() = runTest {
        val repo = mockk<TelemetryRepository>(relaxed = true)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val s = scheduler(scope, repo)

        s.start("dev-1")
        advanceTimeBy(3500) // 3 full intervals elapsed (interval = 1000ms → beats at 1000/2000/3000)
        runCurrent()
        s.stop()

        coVerify(exactly = 1) { repo.heartbeat("dev-1", "session_start") }
        coVerify(atLeast = 3) { repo.heartbeat("dev-1", "playback") }
    }

    @Test
    fun `stop halts further beats`() = runTest {
        val repo = mockk<TelemetryRepository>(relaxed = true)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val s = scheduler(scope, repo)

        s.start("dev-1")
        advanceTimeBy(1500) // exactly one playback beat (at t=1000)
        runCurrent()
        s.stop()
        advanceTimeBy(5000) // would be 5 more beats if the ticker were still alive
        runCurrent()

        coVerify(exactly = 1) { repo.heartbeat("dev-1", "playback") } // no beats after stop()
    }

    @Test
    fun `restart with emitSessionStart=false does not re-emit session_start (pause then resume)`() = runTest {
        val repo = mockk<TelemetryRepository>(relaxed = true)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val s = scheduler(scope, repo)

        s.start("dev-1"); advanceTimeBy(1200); runCurrent(); s.stop()                       // play
        s.start("dev-1", emitSessionStart = false); advanceTimeBy(1200); runCurrent(); s.stop() // resume same playback

        coVerify(exactly = 1) { repo.heartbeat("dev-1", "session_start") } // session_start NOT re-emitted on resume
    }

    @Test
    fun `repo failure does not stop the ticker (fail-soft)`() = runTest {
        val repo = mockk<TelemetryRepository>(relaxed = true)
        coEvery { repo.heartbeat(any(), "playback") } throws RuntimeException("network")
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val s = scheduler(scope, repo)

        s.start("dev-1")
        advanceTimeBy(3500)
        runCurrent()
        s.stop()
        coVerify(atLeast = 3) { repo.heartbeat("dev-1", "playback") } // kept ticking despite throws
    }
}
