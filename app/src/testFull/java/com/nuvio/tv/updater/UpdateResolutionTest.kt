package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateResolutionTest {

    private fun update(versionCode: Int) = AppUpdate(
        versionCode = versionCode,
        tag = "v$versionCode",
        title = "Title",
        notes = "notes",
        sha256 = "abc",
        releaseUrl = null,
        assetName = "kevbox-$versionCode.apk",
        assetUrl = "https://tv.kevbox.dev/kevbox-$versionCode.apk",
        assetSizeBytes = null,
    )

    @Test
    fun `live newer with matching cached apk is installable`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1030),
            cachedUpdate = update(1030),
            cachedApkPath = "/cache/u.apk",
            cachedApkExists = true,
            currentVersionCode = 1028,
            ignoredVersionCode = null,
            force = false,
        )
        assertEquals(1030, d.update?.versionCode)
        assertTrue(d.isUpdateAvailable)
        assertTrue(d.showDialog)
        assertEquals("/cache/u.apk", d.installableApkPath)
    }

    @Test
    fun `live newer than cached apk is not installable (download on demand)`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1031),
            cachedUpdate = update(1030),
            cachedApkPath = "/cache/u.apk",
            cachedApkExists = true,
            currentVersionCode = 1028,
            ignoredVersionCode = null,
            force = false,
        )
        assertEquals(1031, d.update?.versionCode)
        assertNull(d.installableApkPath)
    }

    @Test
    fun `offline falls back to cached metadata and stays installable`() {
        val d = UpdateResolution.resolve(
            liveUpdate = null,
            cachedUpdate = update(1030),
            cachedApkPath = "/cache/u.apk",
            cachedApkExists = true,
            currentVersionCode = 1028,
            ignoredVersionCode = null,
            force = false,
        )
        assertEquals(1030, d.update?.versionCode)
        assertTrue(d.showDialog)
        assertEquals("/cache/u.apk", d.installableApkPath)
    }

    @Test
    fun `cached apk file missing is not installable`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1030),
            cachedUpdate = update(1030),
            cachedApkPath = "/cache/u.apk",
            cachedApkExists = false,
            currentVersionCode = 1028,
            ignoredVersionCode = null,
            force = false,
        )
        assertNull(d.installableApkPath)
    }

    @Test
    fun `ignored version is available but does not auto-show`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1030), cachedUpdate = null, cachedApkPath = null,
            cachedApkExists = false, currentVersionCode = 1028, ignoredVersionCode = 1030,
            force = false,
        )
        assertTrue(d.isUpdateAvailable)
        assertFalse(d.showDialog)
    }

    @Test
    fun `ignored version with a matching cached apk stays hidden but is still installable`() {
        // Decided behavior: the dialog is hidden (ignored, no force), but the cached path is
        // still resolved so a later force-check can offer instant Install.
        val d = UpdateResolution.resolve(
            liveUpdate = update(1030), cachedUpdate = update(1030), cachedApkPath = "/cache/u.apk",
            cachedApkExists = true, currentVersionCode = 1028, ignoredVersionCode = 1030,
            force = false,
        )
        assertFalse(d.showDialog)
        assertEquals("/cache/u.apk", d.installableApkPath)
    }

    @Test
    fun `force offers instant install of an ignored, pre-downloaded version`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1030), cachedUpdate = update(1030), cachedApkPath = "/cache/u.apk",
            cachedApkExists = true, currentVersionCode = 1028, ignoredVersionCode = 1030,
            force = true,
        )
        assertTrue(d.showDialog)
        assertEquals("/cache/u.apk", d.installableApkPath)
    }

    @Test
    fun `force shows the dialog even for an ignored version`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1030), cachedUpdate = null, cachedApkPath = null,
            cachedApkExists = false, currentVersionCode = 1028, ignoredVersionCode = 1030,
            force = true,
        )
        assertTrue(d.showDialog)
    }

    @Test
    fun `no update available when not newer`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1028), cachedUpdate = null, cachedApkPath = null,
            cachedApkExists = false, currentVersionCode = 1028, ignoredVersionCode = null,
            force = false,
        )
        assertFalse(d.isUpdateAvailable)
        assertFalse(d.showDialog)
        assertNull(d.installableApkPath)
    }

    @Test
    fun `nothing live and nothing cached yields empty decision`() {
        val d = UpdateResolution.resolve(
            liveUpdate = null, cachedUpdate = null, cachedApkPath = null,
            cachedApkExists = false, currentVersionCode = 1028, ignoredVersionCode = null,
            force = true,
        )
        assertNull(d.update)
        assertFalse(d.isUpdateAvailable)
        assertTrue(d.showDialog) // force surfaces the error dialog
    }
}
