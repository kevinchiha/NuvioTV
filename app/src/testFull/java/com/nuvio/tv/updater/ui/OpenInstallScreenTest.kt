package com.nuvio.tv.updater.ui

import android.content.Context
import android.content.Intent
import com.nuvio.tv.updater.PendingInstall
import com.nuvio.tv.updater.SilentInstallGuard
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Opening an install screen empties the PendingInstallScreen slot, so INSTALL_SCREEN_WAITING no
 * longer keeps a silent install away. The opener records the member-install time instead (AC-10).
 */
class OpenInstallScreenTest {

    @Before
    fun setUp() {
        mockkObject(SilentInstallGuard)
        every { SilentInstallGuard.memberSessionCommitted(any()) } just runs
    }

    @After
    fun tearDown() {
        unmockkObject(SilentInstallGuard)
    }

    @Test
    fun `opening the plain install screen records the member-install time first`() {
        val context = mockk<Context>(relaxed = true)
        val screen = mockk<Intent>()

        openInstallScreen(context, PendingInstall(screen, fallbackApkPath = null), nowElapsedMs = 1_234L)

        verifyOrder {
            SilentInstallGuard.memberSessionCommitted(1_234L)
            context.startActivity(screen)
        }
    }

    @Test
    fun `a screen that fails to open still counts as a member install`() {
        val context = mockk<Context>(relaxed = true)
        val screen = mockk<Intent>()
        every { context.startActivity(screen) } throws IllegalStateException("no activity")

        openInstallScreen(context, PendingInstall(screen, fallbackApkPath = null), nowElapsedMs = 5_000L)

        verifyOrder {
            SilentInstallGuard.memberSessionCommitted(5_000L)
            context.startActivity(screen)
        }
    }
}
