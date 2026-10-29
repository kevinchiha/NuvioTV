package com.nuvio.tv.updater

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Test

class SilentResultActionTest {

    @Test
    fun `pending user action maps to REFUSED`() {
        assertEquals(SilentResultAction.REFUSED, SilentResultAction.forStatus(PackageInstaller.STATUS_PENDING_USER_ACTION))
    }

    @Test
    fun `success maps to INSTALLED`() {
        assertEquals(SilentResultAction.INSTALLED, SilentResultAction.forStatus(PackageInstaller.STATUS_SUCCESS))
    }

    @Test
    fun `aborted and failure statuses map to FAILED`() {
        listOf(
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
        ).forEach { status ->
            assertEquals("status $status", SilentResultAction.FAILED, SilentResultAction.forStatus(status))
        }
    }

    @Test
    fun `an unknown status maps to FAILED`() {
        assertEquals(SilentResultAction.FAILED, SilentResultAction.forStatus(42))
    }

    @Test
    fun `result line names the version, status and action`() {
        assertEquals(
            "Silent result for versionCode 123: status -1 (REFUSED)",
            silentResultLogLine(123, PackageInstaller.STATUS_PENDING_USER_ACTION, SilentResultAction.REFUSED, null),
        )
    }

    @Test
    fun `result line appends the status message when present`() {
        assertEquals(
            "Silent result for versionCode 123: status 3 (FAILED) message=aborted",
            silentResultLogLine(123, PackageInstaller.STATUS_FAILURE_ABORTED, SilentResultAction.FAILED, "aborted"),
        )
    }
}
