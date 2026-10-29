package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentInstallSchedulerTest {

    private val current = 1028

    private fun cached(versionCode: Int = 1030, silentAfterMs: Long? = 1_000_000L) = AppUpdate(
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
    )

    private fun shouldQueue(
        cached: AppUpdate? = cached(),
        updaterEnabled: Boolean = true,
        sdkInt: Int = 36,
    ) = SilentInstallScheduler.shouldQueue(cached, current, updaterEnabled, sdkInt)

    @Test
    fun `the job runs 15 minutes after the app leaves the screen`() {
        assertEquals(900_000L, SilentInstallWorker.DELAY_MS)
    }

    @Test
    fun `queues for a cached upgrade with a silent block`() {
        assertTrue(shouldQueue())
        assertTrue(shouldQueue(sdkInt = 31))
    }

    @Test
    fun `does not queue without a silent block, for a non-upgrade, below API 31, or with the updater off`() {
        assertFalse("no silent block", shouldQueue(cached = cached(silentAfterMs = null)))
        assertFalse("nothing cached", shouldQueue(cached = null))
        assertFalse("same version", shouldQueue(cached = cached(versionCode = current)))
        assertFalse("older version", shouldQueue(cached = cached(versionCode = current - 1)))
        assertFalse("API 30", shouldQueue(sdkInt = 30))
        assertFalse("updater off", shouldQueue(updaterEnabled = false))
    }
}
