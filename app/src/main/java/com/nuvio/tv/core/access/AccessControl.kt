package com.nuvio.tv.core.access

/**
 * Pure (Android-free) policy logic for the KevBox TV access kill-switch and device limit.
 *
 * Both [AccessControlService] and [DeviceGuardService] share this logic so the tamper-resistant
 * grace decision lives in one unit-testable place. Keep this file free of Android imports.
 */
object AccessControl {

    /**
     * How long the app keeps working after the last *successful* server check while it can't
     * reach the server (offline / errored). Once exceeded, the app fails closed (locks).
     */
    const val GRACE_MS: Long = 5 * 60 * 1000L

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
