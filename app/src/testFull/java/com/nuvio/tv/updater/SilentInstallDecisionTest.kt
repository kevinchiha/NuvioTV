package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentInstallDecisionTest {

    private val live = 1030
    private val afterMs = 1_000_000L
    private val earlyId = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"

    private fun update(
        versionCode: Int = live,
        silentAfterMs: Long? = afterMs,
        earlyUsers: List<String> = listOf(earlyId),
    ) = AppUpdate(
        versionCode = versionCode,
        tag = "v$versionCode",
        title = "Title",
        notes = "notes",
        sha256 = "abc",
        releaseUrl = null,
        assetName = "kevbox-$versionCode.apk",
        assetUrl = "https://tv.kevbox.dev/kevbox-$versionCode.apk",
        assetSizeBytes = null,
        silentAfterMs = silentAfterMs,
        silentEarlyUsers = earlyUsers,
    )

    /** Every AC-2 check passes. Each test breaks one field. */
    private fun allPass() = SilentEligibilityInput(
        updaterEnabled = true,
        sdkInt = 36,
        canRequestInstalls = true,
        liveUpdate = update(),
        currentVersionCode = 1028,
        cachedVersionCode = live,
        cachedFileExists = true,
        cachedSha256Matches = { true },
        ignoredVersionCode = null,
        nowMs = afterMs + 1,
        signedInUserId = null,
        silentState = null,
    )

    private fun eligibility(input: SilentEligibilityInput) = SilentInstallDecision.eligibility(input)

    // --- eligibility: one test per AC-2 reason ---

    @Test
    fun `eligibility is ELIGIBLE when every check passes`() {
        assertEquals(SilentReason.ELIGIBLE, eligibility(allPass()))
    }

    @Test
    fun `eligibility returns UPDATER_OFF when only that check fails`() {
        assertEquals(SilentReason.UPDATER_OFF, eligibility(allPass().copy(updaterEnabled = false)))
    }

    @Test
    fun `eligibility returns SDK_TOO_OLD when only that check fails`() {
        assertEquals(SilentReason.SDK_TOO_OLD, eligibility(allPass().copy(sdkInt = 30)))
        assertEquals(SilentReason.ELIGIBLE, eligibility(allPass().copy(sdkInt = 31)))
    }

    @Test
    fun `eligibility returns NO_INSTALL_PERMISSION when only that check fails`() {
        assertEquals(SilentReason.NO_INSTALL_PERMISSION, eligibility(allPass().copy(canRequestInstalls = false)))
    }

    @Test
    fun `eligibility returns MANIFEST_FETCH_FAILED when only that check fails`() {
        assertEquals(SilentReason.MANIFEST_FETCH_FAILED, eligibility(allPass().copy(liveUpdate = null)))
    }

    @Test
    fun `eligibility returns NOT_AN_UPGRADE when only that check fails`() {
        assertEquals(SilentReason.NOT_AN_UPGRADE, eligibility(allPass().copy(currentVersionCode = live)))
        assertEquals(SilentReason.NOT_AN_UPGRADE, eligibility(allPass().copy(currentVersionCode = live + 1)))
    }

    @Test
    fun `eligibility returns NO_PREDOWNLOAD when only that check fails`() {
        assertEquals(SilentReason.NO_PREDOWNLOAD, eligibility(allPass().copy(cachedVersionCode = live - 1)))
        assertEquals(SilentReason.NO_PREDOWNLOAD, eligibility(allPass().copy(cachedVersionCode = null)))
        assertEquals(SilentReason.NO_PREDOWNLOAD, eligibility(allPass().copy(cachedFileExists = false)))
    }

    @Test
    fun `eligibility returns PREDOWNLOAD_CHECKSUM_MISMATCH when only that check fails`() {
        assertEquals(
            SilentReason.PREDOWNLOAD_CHECKSUM_MISMATCH,
            eligibility(allPass().copy(cachedSha256Matches = { false })),
        )
    }

    @Test
    fun `eligibility returns VERSION_IGNORED when only that check fails`() {
        assertEquals(SilentReason.VERSION_IGNORED, eligibility(allPass().copy(ignoredVersionCode = live)))
        assertEquals(SilentReason.ELIGIBLE, eligibility(allPass().copy(ignoredVersionCode = live - 1)))
    }

    @Test
    fun `eligibility returns NO_SILENT_BLOCK when only that check fails`() {
        assertEquals(
            SilentReason.NO_SILENT_BLOCK,
            eligibility(allPass().copy(liveUpdate = update(silentAfterMs = null))),
        )
    }

    @Test
    fun `eligibility returns NOT_YET when only that check fails`() {
        assertEquals(
            SilentReason.NOT_YET,
            eligibility(allPass().copy(nowMs = afterMs - 1, signedInUserId = "someone-else")),
        )
        assertEquals(SilentReason.NOT_YET, eligibility(allPass().copy(nowMs = afterMs - 1)))
        assertEquals(SilentReason.ELIGIBLE, eligibility(allPass().copy(nowMs = afterMs)))
    }

    @Test
    fun `eligibility returns SILENT_REFUSED when only that check fails`() {
        assertEquals(
            SilentReason.SILENT_REFUSED,
            eligibility(allPass().copy(silentState = SilentInstallState(live, 0, refused = true))),
        )
    }

    @Test
    fun `eligibility returns TOO_MANY_ATTEMPTS when only that check fails`() {
        assertEquals(
            SilentReason.TOO_MANY_ATTEMPTS,
            eligibility(allPass().copy(silentState = SilentInstallState(live, 3, refused = false))),
        )
        assertEquals(
            SilentReason.ELIGIBLE,
            eligibility(allPass().copy(silentState = SilentInstallState(live, 2, refused = false))),
        )
    }

    @Test
    fun `an early user passes before after`() {
        val input = allPass().copy(nowMs = afterMs - 1, signedInUserId = "  ${earlyId.uppercase()} ")
        assertEquals(SilentReason.ELIGIBLE, eligibility(input))
    }

    @Test
    fun `the checksum is not computed when an earlier check fails`() {
        var calls = 0
        val counting = { calls++; true }
        eligibility(allPass().copy(cachedSha256Matches = counting, nowMs = afterMs - 1))
        eligibility(allPass().copy(cachedSha256Matches = counting, ignoredVersionCode = live))
        eligibility(allPass().copy(cachedSha256Matches = counting, silentState = SilentInstallState(live, 3, false)))
        assertEquals(0, calls)

        assertEquals(SilentReason.ELIGIBLE, eligibility(allPass().copy(cachedSha256Matches = counting)))
        assertEquals(1, calls)
    }

    // --- per-version state (AC-8) ---

    @Test
    fun `per-version silent state resets on a new versionCode`() {
        val old = SilentInstallState(live - 1, 3, refused = true)
        assertEquals(SilentReason.ELIGIBLE, eligibility(allPass().copy(silentState = old)))

        assertEquals(SilentInstallState(live, 1, false), nextSilentState(old, live, SilentStateEvent.ATTEMPT))
        assertEquals(SilentInstallState(live, 0, true), nextSilentState(old, live, SilentStateEvent.REFUSED))
        assertEquals(
            SilentInstallState(live, 2, false),
            nextSilentState(SilentInstallState(live, 1, false), live, SilentStateEvent.ATTEMPT),
        )
        assertEquals(
            SilentInstallState(live, 2, true),
            nextSilentState(SilentInstallState(live, 2, false), live, SilentStateEvent.REFUSED),
        )
        assertEquals(SilentInstallState(live, 1, false), nextSilentState(null, live, SilentStateEvent.ATTEMPT))
        assertEquals(SilentInstallState(live, 0, true), nextSilentState(null, live, SilentStateEvent.REFUSED))
    }

    // --- isEarlyUser ---

    @Test
    fun `isEarlyUser matches trimmed ids ignoring case`() {
        assertTrue(SilentInstallDecision.isEarlyUser(earlyId, listOf(" ${earlyId.uppercase()} ")))
        assertTrue(SilentInstallDecision.isEarlyUser(" $earlyId ", listOf("other", earlyId)))
        assertFalse(SilentInstallDecision.isEarlyUser("other-id", listOf(earlyId)))
        assertFalse(SilentInstallDecision.isEarlyUser(earlyId, emptyList()))
    }

    @Test
    fun `isEarlyUser is false for a null or blank id`() {
        assertFalse(SilentInstallDecision.isEarlyUser(null, listOf(earlyId)))
        assertFalse(SilentInstallDecision.isEarlyUser("", listOf("")))
        assertFalse(SilentInstallDecision.isEarlyUser("   ", listOf("  ")))
    }

    // --- quiet (AC-3, AC-10) ---

    private val now = 10_000_000L

    /** Out of sight for long enough, nothing else running. */
    private fun quietInput() = QuietInput(
        startedActivities = 0,
        lastStartElapsedMs = now - 900_000,
        lastStopElapsedMs = now - 800_000,
        nowElapsedMs = now,
        externalPlaybackRunning = false,
        memberInstallRunning = false,
        installScreenWaiting = false,
        lastMemberCommitElapsedMs = null,
    )

    private fun quiet(input: QuietInput) = SilentInstallDecision.quiet(input)

    @Test
    fun `quiet is ELIGIBLE when the app is out of sight and nothing else runs`() {
        assertEquals(SilentReason.ELIGIBLE, quiet(quietInput()))
    }

    @Test
    fun `quiet returns APP_ON_SCREEN while an activity is started`() {
        assertEquals(SilentReason.APP_ON_SCREEN, quiet(quietInput().copy(startedActivities = 1)))
    }

    @Test
    fun `quiet returns APP_RECENTLY_ON_SCREEN within 120 000 ms of the later stored time`() {
        assertEquals(
            SilentReason.APP_RECENTLY_ON_SCREEN,
            quiet(quietInput().copy(lastStopElapsedMs = now - 119_999)),
        )
        assertEquals(SilentReason.ELIGIBLE, quiet(quietInput().copy(lastStopElapsedMs = now - 120_000)))
        // The later of the two counts, whichever it is.
        assertEquals(
            SilentReason.APP_RECENTLY_ON_SCREEN,
            quiet(quietInput().copy(lastStartElapsedMs = now - 119_999, lastStopElapsedMs = now - 500_000)),
        )
        assertEquals(
            SilentReason.ELIGIBLE,
            quiet(quietInput().copy(lastStartElapsedMs = now - 120_000, lastStopElapsedMs = now - 500_000)),
        )
    }

    @Test
    fun `quiet treats a stored time above now as out of sight since boot`() {
        val afterReboot = quietInput().copy(lastStartElapsedMs = null, lastStopElapsedMs = 5_000_000, nowElapsedMs = 200_000)
        assertEquals(SilentReason.ELIGIBLE, quiet(afterReboot))

        val justBooted = quietInput().copy(lastStartElapsedMs = null, lastStopElapsedMs = null, nowElapsedMs = 60_000)
        assertEquals(SilentReason.APP_RECENTLY_ON_SCREEN, quiet(justBooted))
    }

    @Test
    fun `quiet returns EXTERNAL_PLAYBACK while the external player runs`() {
        assertEquals(SilentReason.EXTERNAL_PLAYBACK, quiet(quietInput().copy(externalPlaybackRunning = true)))
    }

    @Test
    fun `quiet returns MEMBER_INSTALL_RUNNING while the member's install copies`() {
        assertEquals(SilentReason.MEMBER_INSTALL_RUNNING, quiet(quietInput().copy(memberInstallRunning = true)))
    }

    @Test
    fun `quiet returns INSTALL_SCREEN_WAITING while an install screen is pending`() {
        assertEquals(SilentReason.INSTALL_SCREEN_WAITING, quiet(quietInput().copy(installScreenWaiting = true)))
    }

    @Test
    fun `quiet returns MEMBER_INSTALL_RECENT within 600 000 ms of a member commit`() {
        assertEquals(
            SilentReason.MEMBER_INSTALL_RECENT,
            quiet(quietInput().copy(lastMemberCommitElapsedMs = now - 599_999)),
        )
        assertEquals(SilentReason.ELIGIBLE, quiet(quietInput().copy(lastMemberCommitElapsedMs = now - 600_000)))
        // A commit time above now is from before a reboot.
        assertEquals(SilentReason.ELIGIBLE, quiet(quietInput().copy(lastMemberCommitElapsedMs = now + 1)))
    }

    @Test
    fun `quiet checks in AC-3 then AC-10 order`() {
        val everything = quietInput().copy(
            startedActivities = 1,
            lastStopElapsedMs = now - 1,
            externalPlaybackRunning = true,
            memberInstallRunning = true,
            installScreenWaiting = true,
            lastMemberCommitElapsedMs = now - 1,
        )
        assertEquals(SilentReason.APP_ON_SCREEN, quiet(everything))
        assertEquals(SilentReason.APP_RECENTLY_ON_SCREEN, quiet(everything.copy(startedActivities = 0)))
        val outOfSight = everything.copy(startedActivities = 0, lastStopElapsedMs = now - 800_000)
        assertEquals(SilentReason.EXTERNAL_PLAYBACK, quiet(outOfSight))
        assertEquals(SilentReason.MEMBER_INSTALL_RUNNING, quiet(outOfSight.copy(externalPlaybackRunning = false)))
        assertEquals(
            SilentReason.INSTALL_SCREEN_WAITING,
            quiet(outOfSight.copy(externalPlaybackRunning = false, memberInstallRunning = false)),
        )
        assertEquals(
            SilentReason.MEMBER_INSTALL_RECENT,
            quiet(outOfSight.copy(externalPlaybackRunning = false, memberInstallRunning = false, installScreenWaiting = false)),
        )
    }
}
