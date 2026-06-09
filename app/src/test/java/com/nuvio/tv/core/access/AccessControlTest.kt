package com.nuvio.tv.core.access

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessControlTest {

    private val grace = AccessControl.GRACE_MS

    @Test
    fun `constants match the access control contract`() {
        assertTrue(AccessControl.GRACE_MS == 5 * 60 * 1000L)
        assertTrue(AccessControl.CHECK_INTERVAL_MS == 2 * 60 * 1000L)
    }

    @Test
    fun `never checked both clocks zero is in grace`() {
        assertFalse(
            AccessControl.graceExpired(
                lastOkWallMs = 0L,
                lastOkElapsedMs = 0L,
                nowWallMs = 9_999_999_999L,
                nowElapsedMs = 9_999_999_999L
            )
        )
    }

    @Test
    fun `fresh success within grace is not expired`() {
        // 1 minute since the last good check, well under the 5-minute grace.
        val lastOkElapsed = 100_000L
        val lastOkWall = 1_000_000L
        assertFalse(
            AccessControl.graceExpired(
                lastOkWallMs = lastOkWall,
                lastOkElapsedMs = lastOkElapsed,
                nowWallMs = lastOkWall + 60_000L,
                nowElapsedMs = lastOkElapsed + 60_000L
            )
        )
    }

    @Test
    fun `elapsed greater than grace is expired`() {
        val lastOkElapsed = 100_000L
        val lastOkWall = 1_000_000L
        assertTrue(
            AccessControl.graceExpired(
                lastOkWallMs = lastOkWall,
                lastOkElapsedMs = lastOkElapsed,
                nowWallMs = lastOkWall + grace + 1_000L,
                nowElapsedMs = lastOkElapsed + grace + 1_000L
            )
        )
    }

    @Test
    fun `wall clock rollback with no reboot still expires via elapsed clock`() {
        // Attacker rolls the wall clock BACK so nowWall < lastOkWall (would read as "negative"
        // elapsed on the wall clock and never expire). But the device has NOT rebooted, so
        // elapsedRealtime is monotonic and well past grace -> must still expire.
        val lastOkElapsed = 100_000L
        val lastOkWall = 10_000_000L
        assertTrue(
            AccessControl.graceExpired(
                lastOkWallMs = lastOkWall,
                lastOkElapsedMs = lastOkElapsed,
                nowWallMs = lastOkWall - 1_000_000L, // rolled BACK
                nowElapsedMs = lastOkElapsed + grace + 1_000L // monotonic, past grace
            )
        )
    }

    @Test
    fun `wall clock rollback with no reboot stays in grace when elapsed is within grace`() {
        // Same rollback, but the monotonic clock shows we're still inside the grace window.
        // The rolled-back wall clock must NOT be used to lock early; elapsed clock wins.
        val lastOkElapsed = 100_000L
        val lastOkWall = 10_000_000L
        assertFalse(
            AccessControl.graceExpired(
                lastOkWallMs = lastOkWall,
                lastOkElapsedMs = lastOkElapsed,
                nowWallMs = lastOkWall - 1_000_000L, // rolled BACK
                nowElapsedMs = lastOkElapsed + 60_000L // 1 min, within grace
            )
        )
    }

    @Test
    fun `reboot falls back to wall clock and expires when wall elapsed exceeds grace`() {
        // After a reboot, elapsedRealtime resets to a small value -> nowElapsed < lastOkElapsed.
        // Fall back to the wall clock; it shows more than grace has passed -> expired.
        val lastOkElapsed = 5_000_000L
        val lastOkWall = 1_000_000L
        assertTrue(
            AccessControl.graceExpired(
                lastOkWallMs = lastOkWall,
                lastOkElapsedMs = lastOkElapsed,
                nowWallMs = lastOkWall + grace + 1_000L, // wall clock past grace
                nowElapsedMs = 2_000L // tiny: device just rebooted
            )
        )
    }

    @Test
    fun `reboot falls back to wall clock and stays in grace when wall elapsed within grace`() {
        // Reboot (nowElapsed < lastOkElapsed) but the wall clock shows we're still within grace.
        val lastOkElapsed = 5_000_000L
        val lastOkWall = 1_000_000L
        assertFalse(
            AccessControl.graceExpired(
                lastOkWallMs = lastOkWall,
                lastOkElapsedMs = lastOkElapsed,
                nowWallMs = lastOkWall + 60_000L, // 1 min on the wall clock, within grace
                nowElapsedMs = 2_000L // tiny: device just rebooted
            )
        )
    }

    @Test
    fun `boundary exactly at grace is not expired`() {
        // Contract uses strict `>`; exactly GRACE_MS elapsed is still in-grace.
        val lastOkElapsed = 100_000L
        val lastOkWall = 1_000_000L
        assertFalse(
            AccessControl.graceExpired(
                lastOkWallMs = lastOkWall,
                lastOkElapsedMs = lastOkElapsed,
                nowWallMs = lastOkWall + grace,
                nowElapsedMs = lastOkElapsed + grace
            )
        )
    }

    @Test
    fun `custom grace argument is honored`() {
        val lastOkElapsed = 100_000L
        val lastOkWall = 1_000_000L
        val customGrace = 1_000L
        assertTrue(
            AccessControl.graceExpired(
                lastOkWallMs = lastOkWall,
                lastOkElapsedMs = lastOkElapsed,
                nowWallMs = lastOkWall + 2_000L,
                nowElapsedMs = lastOkElapsed + 2_000L,
                graceMs = customGrace
            )
        )
    }
}
