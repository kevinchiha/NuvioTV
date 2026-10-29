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
        update: AppUpdate = update(),
        silentState: SilentInstallState? = null,
        nowMs: Long = afterMs + 1,
    ) = SilentPromptHold.holdsPrompt(sdkInt, canRequestInstalls, update, silentState, nowMs)

    @Test
    fun `holdsPrompt is true while silent owns the version and false for each condition that ends it`() {
        assertTrue("all hold", holds())
        assertTrue("API 31", holds(sdkInt = 31))
        assertTrue("before after", holds(nowMs = afterMs - 1))
        assertTrue("2 attempts", holds(silentState = SilentInstallState(live, attempts = 2, refused = false)))

        assertFalse("below API 31", holds(sdkInt = 30))
        assertFalse("no install permission", holds(canRequestInstalls = false))
        assertFalse("no silent block", holds(update = update(silentAfterMs = null)))
        assertFalse("refused", holds(silentState = SilentInstallState(live, attempts = 0, refused = true)))
        assertFalse("3 attempts", holds(silentState = SilentInstallState(live, attempts = 3, refused = false)))

        // Safety net: 72 h after `after` the prompt comes back.
        assertFalse("at after + 72 h", holds(nowMs = afterMs + SilentPromptHold.SAFETY_NET_MS))
        assertTrue("1 ms before after + 72 h", holds(nowMs = afterMs + SilentPromptHold.SAFETY_NET_MS - 1))

        // State saved for another versionCode counts as 0 attempts, not refused.
        assertTrue("other version refused", holds(silentState = SilentInstallState(live - 1, attempts = 0, refused = true)))
        assertTrue("other version 3 attempts", holds(silentState = SilentInstallState(live - 1, attempts = 3, refused = false)))
    }
}
