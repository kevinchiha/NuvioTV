// KevBox upstream-sync note: KevBox-only test (Sentry KEVBOX-TV-5). Covers the two settings stores
// in `full`; the three in `main` are in test's KevboxCorruptStoreResetTest.
package com.nuvio.tv.updater

import android.content.Context
import com.nuvio.tv.core.memberconfig.MemberConfigPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A settings file full of zero bytes (seen on a member's TV, KEVBOX-TV-5) used to throw
 * CorruptionException on every read. The update check reads update_settings as the app opens,
 * so the app crashed on every open until the member cleared app data. Each store must now reset
 * the file to empty and return its defaults.
 *
 * One test per store on purpose: `preferencesDataStore` caches its DataStore for the whole JVM,
 * so the first context a store sees is the one it keeps.
 */
class KevboxCorruptFullStoreResetTest {

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
    fun `zeroed update settings file reads as nothing ignored and nothing downloaded`() = runTest {
        val prefs = UpdatePreferences(contextWithZeroedStore("update_settings"))
        assertNull(prefs.ignoredVersionCode.first())
        assertNull(prefs.predownloadApkPath.first())
    }

    @Test
    fun `zeroed member config file reads as no member applied yet`() = runTest {
        val prefs = MemberConfigPreferences(contextWithZeroedStore("member_config"))
        assertNull(prefs.lastAppliedUserId())
    }
}
