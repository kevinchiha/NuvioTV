package com.nuvio.tv.updater

import android.content.Intent
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PendingInstallScreenTest {

    @After
    fun tearDown() {
        PendingInstallScreen.take()
    }

    @Test
    fun `a posted screen is taken once`() {
        val pending = PendingInstall(screen = mockk<Intent>(), fallbackApkPath = "/cache/updates/k.apk")
        PendingInstallScreen.post(pending)
        assertSame(pending, PendingInstallScreen.take())
        assertNull(PendingInstallScreen.take())
    }

    @Test
    fun `a newer post replaces a waiting one`() {
        val first = PendingInstall(screen = mockk<Intent>(), fallbackApkPath = null)
        val second = PendingInstall(screen = mockk<Intent>(), fallbackApkPath = null)
        PendingInstallScreen.post(first)
        PendingInstallScreen.post(second)
        assertSame(second, PendingInstallScreen.pending.value)
        assertSame(second, PendingInstallScreen.take())
    }
}
