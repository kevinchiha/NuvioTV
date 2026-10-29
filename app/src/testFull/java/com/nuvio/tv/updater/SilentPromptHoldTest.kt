package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentPromptHoldTest {

    private val live = 1030
    private val afterMs = 1_000_000L

    private fun update(silentAfterMs: Long? = afterMs) = AppUpdate(
        versionCode = live,
        tag = "v$live",
        title = "Title",
        notes = "notes",
        sha256 = "abc",
        releaseUrl = null,
        assetName = "kevbox-$live.apk",
        assetUrl = "https://tv.kevbox.dev/kevbox-$live.apk",
        assetSizeBytes = null,
        silentAfterMs = silentAfterMs,
    )

    /** Every condition holds by default; each case breaks one. */
    private fun holds(
        sdkInt: Int = 36,
        canRequestInstalls: Boolean = true,
        liveUpdate: AppUpdate? = update(),
        silentState: SilentInstallState? = null,
        nowMs: Long = afterMs + 1,
        predownloadReady: Boolean = true,
        unmeteredNetwork: Boolean = true,
    ) = SilentPromptHold.holdsPrompt(
        sdkInt = sdkInt,
        canRequestInstalls = canRequestInstalls,
        liveUpdate = liveUpdate,
        silentState = silentState,
        nowMs = nowMs,
        predownloadReady = predownloadReady,
        unmeteredNetwork = unmeteredNetwork,
    )

    @Test
    fun `holdsPrompt is true while silent owns the version`() {
        assertTrue(holds())
        assertTrue(holds(sdkInt = 31))
        assertTrue(holds(nowMs = afterMs - 1))
        assertTrue(holds(silentState = SilentInstallState(live, attempts = 2, refused = false)))
    }

    @Test
    fun `below API 31 the prompt shows`() {
        assertFalse(holds(sdkInt = 30))
    }

    @Test
    fun `without the install permission the prompt shows`() {
        assertFalse(holds(canRequestInstalls = false))
    }

    @Test
    fun `live fetch failed, prompt shows`() {
        assertFalse(holds(liveUpdate = null))
    }

    @Test
    fun `without a silent block the prompt shows`() {
        assertFalse(holds(liveUpdate = update(silentAfterMs = null)))
    }

    @Test
    fun `a version marked silent refused shows the prompt`() {
        assertFalse(holds(silentState = SilentInstallState(live, attempts = 0, refused = true)))
    }

    @Test
    fun `a version with 3 silent attempts shows the prompt`() {
        assertFalse(holds(silentState = SilentInstallState(live, attempts = 3, refused = false)))
    }

    @Test
    fun `72 h after after the prompt shows, and one ms before it stays held`() {
        assertFalse(holds(nowMs = afterMs + SilentPromptHold.SAFETY_NET_MS))
        assertTrue(holds(nowMs = afterMs + SilentPromptHold.SAFETY_NET_MS - 1))
    }

    @Test
    fun `an after near Long MAX does not overflow into showing the prompt`() {
        assertTrue(holds(liveUpdate = update(silentAfterMs = Long.MAX_VALUE)))
    }

    @Test
    fun `silent state stored for another versionCode is ignored`() {
        assertTrue(holds(silentState = SilentInstallState(live - 1, attempts = 0, refused = true)))
        assertTrue(holds(silentState = SilentInstallState(live - 1, attempts = 3, refused = false)))
    }

    @Test
    fun `no pre-download on a metered network shows the prompt`() {
        assertFalse(holds(predownloadReady = false, unmeteredNetwork = false))
    }

    @Test
    fun `no pre-download yet on an unmetered network stays held, the daily check fetches it`() {
        assertTrue(holds(predownloadReady = false, unmeteredNetwork = true))
    }

    @Test
    fun `a ready pre-download stays held on a metered network`() {
        assertTrue(holds(predownloadReady = true, unmeteredNetwork = false))
    }
}
