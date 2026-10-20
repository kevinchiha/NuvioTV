package com.nuvio.tv.updater.ui

import com.nuvio.tv.updater.UpdateUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android locks the app for about a minute while it compiles an update, and nothing of ours can
 * be on screen then. The warning has to show with the Install button, before the member presses it.
 */
class InstallWarningTest {

    @Test
    fun `shows next to the Install button`() {
        assertTrue(showInstallWarning(UpdateUiState(showDialog = true, downloadedApkPath = "/x/kevbox.apk")))
    }

    @Test
    fun `hidden before the update is downloaded`() {
        assertFalse(showInstallWarning(UpdateUiState(showDialog = true)))
    }

    @Test
    fun `hidden while asking for unknown sources, where the button is Open Settings`() {
        assertFalse(
            showInstallWarning(
                UpdateUiState(showDialog = true, downloadedApkPath = "/x/kevbox.apk", showUnknownSourcesDialog = true)
            )
        )
    }
}
