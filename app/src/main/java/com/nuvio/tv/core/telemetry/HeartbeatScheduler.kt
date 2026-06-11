package com.nuvio.tv.core.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fires a playback heartbeat every [intervalMs] while a session is active.
 * Each beat is independently fail-soft (TelemetryRepository swallows errors), so a throw never
 * stops the ticker. Inject a real CoroutineScope in prod; a TestScope in unit tests.
 *
 * Session-start dedup: [start] emits exactly ONE `session_start` per call. The player must pass
 * `emitSessionStart = false` when *resuming the same playback* after a pause, so that pause→resume
 * (and rebuffer-driven restarts) do not inflate the `sessions` metric (spec §5.1). A brand-new
 * playback passes `emitSessionStart = true` (the default).
 */
class HeartbeatScheduler(
    private val repo: TelemetryRepository,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 60_000L,
) {
    private var job: Job? = null

    fun start(deviceId: String, emitSessionStart: Boolean = true) {
        if (job?.isActive == true) {
            // Reuse the running ticker for a same-playback resume/rebuffer (no duplicate job, no
            // re-emit). But if a fresh playback explicitly requests session_start while a stale
            // ticker is still active (e.g. leaked after a playback error), cancel the stale job and
            // start clean so the requested session_start is never silently dropped.
            if (!emitSessionStart) return
            job?.cancel()
        }
        job = scope.launch {
            if (emitSessionStart) runCatching { repo.heartbeat(deviceId, "session_start") }
            while (isActive) {
                delay(intervalMs)
                runCatching { repo.heartbeat(deviceId, "playback") }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
