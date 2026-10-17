package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.ProfileAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallProfileTest {

    private val api28 = ProfileAsset(minApi = 28, maxApi = 30, url = "https://tv.kevbox.dev/k-api28.dm", sha256 = "aa")
    private val api31 = ProfileAsset(minApi = 31, maxApi = Int.MAX_VALUE, url = "https://tv.kevbox.dev/k-api31.dm", sha256 = "bb")
    private val both = listOf(api28, api31)

    @Test
    fun `picks the Android 12+ profile on API 31`() {
        assertEquals(api31, InstallProfile.pick(both, sdkInt = 31))
    }

    @Test
    fun `picks the Android 12+ profile on API 34`() {
        assertEquals(api31, InstallProfile.pick(both, sdkInt = 34))
    }

    @Test
    fun `picks the Android 9-11 profile on API 30`() {
        assertEquals(api28, InstallProfile.pick(both, sdkInt = 30))
    }

    @Test
    fun `picks nothing below every range`() {
        assertNull(InstallProfile.pick(both, sdkInt = 26))
    }

    @Test
    fun `picks nothing from an empty list`() {
        assertNull(InstallProfile.pick(emptyList(), sdkInt = 31))
    }

    @Test
    fun `skips an entry with a blank url or checksum`() {
        val broken = listOf(api31.copy(url = " "), api31.copy(sha256 = ""))
        assertNull(InstallProfile.pick(broken, sdkInt = 31))
    }

    @Test
    fun `profile file sits next to the apk with a dm extension`() {
        assertEquals("kevbox-tv-1.2.0.dm", InstallProfile.fileNameFor("kevbox-tv-1.2.0.apk"))
    }

    @Test
    fun `profile file name for a name without apk extension appends dm`() {
        assertEquals("kevbox-tv.dm", InstallProfile.fileNameFor("kevbox-tv"))
    }

    @Test
    fun `uses the session route with a profile on Android 9`() {
        assertTrue(InstallProfile.useSession(hasProfile = true, sdkInt = 28))
    }

    @Test
    fun `uses the session route with a profile on Android 16`() {
        assertTrue(InstallProfile.useSession(hasProfile = true, sdkInt = 36))
    }

    @Test
    fun `uses the plain screen without a profile`() {
        assertFalse(InstallProfile.useSession(hasProfile = false, sdkInt = 31))
    }

    @Test
    fun `uses the plain screen below Android 9 even with a profile`() {
        assertFalse(InstallProfile.useSession(hasProfile = true, sdkInt = 27))
    }
}
