// KevBox upstream-sync note: KevBox-only test (Sentry KEVBOX-TV-5). Covers the three KevBox-only
// settings stores in `main`; the two in `full` are in testFull's KevboxCorruptFullStoreResetTest.
package com.nuvio.tv.data.local

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A settings file full of zero bytes (seen on a member's TV, KEVBOX-TV-5) used to throw
 * CorruptionException on every read, which crashed the app on every open until the member
 * cleared app data. Each store must now reset the file to empty and return its defaults.
 *
 * One test per store on purpose: `preferencesDataStore` caches its DataStore for the whole JVM,
 * so the first context a store sees is the one it keeps.
 */
class KevboxCorruptStoreResetTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun contextWithZeroedStore(name: String): Context {
        val dir = Files.createTempDirectory("kevbox_corrupt_$name").toFile().also(tempDirs::add)
        File(dir, "datastore").mkdirs()
        File(dir, "datastore/$name.preferences_pb").writeBytes(ByteArray(64))
        return mockk(relaxed = true) {
            every { applicationContext } returns this
            every { filesDir } returns dir
        }
    }

    @Test
    fun `zeroed last sign-in file reads as no saved email`() = runTest {
        val store = LastSignInDataStore(contextWithZeroedStore("last_sign_in"))
        assertNull(store.lastEmail.first())
    }

    @Test
    fun `zeroed access control file reads as never checked and not locked`() = runTest {
        val snapshot = AccessControlDataStore(contextWithZeroedStore("access_control")).snapshot()
        assertEquals(0L, snapshot.lastOkWallMs)
        assertFalse(snapshot.lockedOut)
    }

    @Test
    fun `zeroed device guard file reads as never checked and not locked`() = runTest {
        val snapshot = DeviceGuardDataStore(contextWithZeroedStore("device_guard")).snapshot()
        assertEquals(0L, snapshot.lastOkWallMs)
        assertFalse(snapshot.deviceLockedOut)
    }
}
