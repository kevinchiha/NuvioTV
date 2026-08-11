package com.nuvio.tv.core.access

/**
 * Pure (Android-free) policy logic for the KevBox TV access kill-switch and device limit.
 *
 * Both [AccessControlService] and [DeviceGuardService] share this logic so the tamper-resistant
 * grace decision lives in one unit-testable place. Keep this file free of Android imports.
 */
/**
 * Result of a single access/device refresh attempt. Lets the lock-screen Retry button tell the
 * member what actually happened instead of silently doing nothing (the lock itself is monotonic:
 * an UNREACHABLE attempt can never clear it, so without this the button feels dead while offline).
 */
enum class RefreshOutcome {
    /** Server said yes — the lock (if any) was cleared. */
    AUTHORIZED,
    /** Server explicitly denied (kill-switch LOCKED / device claim refused). */
    DENIED,
    /** Couldn't get a verdict (offline / auth error) — grace rules were applied instead. */
    UNREACHABLE
}

object AccessControl {

    /**
     * How long the app keeps working after the last *successful* server check while it can't
     * reach the server (offline / errored). Once exceeded, the app fails closed (locks).
     *
     * 24 h, not minutes: an UNREACHABLE verdict is almost always the member's wifi/DNS flapping
     * or the TV waking from standby before the network is up — the original 5-minute window
     * hard-locked devices mid-playback on routine blips. Enforcement doesn't need a short
     * window: while the app can reach the server a real LOCKED/DENIED verdict still locks
     * within one [CHECK_INTERVAL_MS] poll, and a device that can't reach the server can't
     * stream anyway. The grace window only bounds how long a deliberately server-blocked
     * device keeps its menus before failing closed.
     */
    const val GRACE_MS: Long = 24 * 60 * 60 * 1000L

    /** How often the foreground poller re-checks the server verdict. */
    const val CHECK_INTERVAL_MS: Long = 2 * 60 * 1000L

    /**
     * Decide whether the offline grace window has expired since the last successful check.
     *
     * Uses two clocks so a user rolling the device wall clock back can't extend grace forever:
     *  - [lastOkElapsedMs]/[nowElapsedMs] is [android.os.SystemClock.elapsedRealtime] — monotonic
     *    while the device stays up, immune to wall-clock changes, but reset to 0 on reboot.
     *  - [lastOkWallMs]/[nowWallMs] is [System.currentTimeMillis] — survives reboot but is
     *    user-settable.
     *
     * Rules:
     *  - Both clocks `0L` (never had a successful check) → not expired (treated as in-grace), so a
     *    first launch while offline isn't a false lockout.
     *  - No reboot ([nowElapsedMs] >= [lastOkElapsedMs]) → measure elapsed via the monotonic clock
     *    (rollback-proof).
     *  - Reboot ([nowElapsedMs] < [lastOkElapsedMs], i.e. elapsedRealtime was reset) → fall back to
     *    the wall clock.
     *  - Expired when the measured elapsed exceeds [graceMs].
     */
    fun graceExpired(
        lastOkWallMs: Long,
        lastOkElapsedMs: Long,
        nowWallMs: Long,
        nowElapsedMs: Long,
        graceMs: Long = GRACE_MS
    ): Boolean {
        // Never checked yet → in-grace.
        if (lastOkWallMs == 0L && lastOkElapsedMs == 0L) return false

        val elapsed = if (nowElapsedMs >= lastOkElapsedMs) {
            // No reboot: monotonic elapsed-realtime clock (rollback-proof).
            nowElapsedMs - lastOkElapsedMs
        } else {
            // Rebooted: elapsedRealtime reset, fall back to the wall clock.
            nowWallMs - lastOkWallMs
        }

        return elapsed > graceMs
    }
}
