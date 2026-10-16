package com.nuvio.tv.updater

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallStatusActionTest {

    @Test
    fun `asks the user to confirm when Android needs it`() {
        assertEquals(InstallStatusAction.CONFIRM, InstallStatusAction.forStatus(PackageInstaller.STATUS_PENDING_USER_ACTION))
    }

    @Test
    fun `does nothing on success`() {
        assertEquals(InstallStatusAction.NOTHING, InstallStatusAction.forStatus(PackageInstaller.STATUS_SUCCESS))
    }

    @Test
    fun `does nothing when the user cancels`() {
        assertEquals(InstallStatusAction.NOTHING, InstallStatusAction.forStatus(PackageInstaller.STATUS_FAILURE_ABORTED))
    }

    @Test
    fun `falls back to the plain install screen on a generic failure`() {
        assertEquals(InstallStatusAction.FALLBACK, InstallStatusAction.forStatus(PackageInstaller.STATUS_FAILURE))
    }

    @Test
    fun `falls back when Android rejects the files`() {
        assertEquals(InstallStatusAction.FALLBACK, InstallStatusAction.forStatus(PackageInstaller.STATUS_FAILURE_INVALID))
    }

    @Test
    fun `falls back on an unknown status`() {
        assertEquals(InstallStatusAction.FALLBACK, InstallStatusAction.forStatus(42))
    }
}
