package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManifestMappingTest {

    private val oldManifest = """
        {
          "versionCode": 1055,
          "versionName": "1.2.0-beta.7",
          "url": "https://tv.kevbox.dev/kevbox-tv-1.2.0-beta.7.apk",
          "sha256": "3d69",
          "notes": "Bug fixes"
        }
    """.trimIndent()

    private val newManifest = """
        {
          "versionCode": 1056,
          "versionName": "1.2.0-beta.7.1",
          "url": "https://tv.kevbox.dev/kevbox-tv-1.2.0-beta.7.1.apk",
          "sha256": "abcd",
          "notes": "Faster after updates",
          "profiles": [
            {"minApi": 28, "maxApi": 30, "url": "https://tv.kevbox.dev/kevbox-tv-1.2.0-beta.7.1-api28.dm", "sha256": "p28"},
            {"minApi": 31, "maxApi": 2147483647, "url": "https://tv.kevbox.dev/kevbox-tv-1.2.0-beta.7.1-api31.dm", "sha256": "p31"}
          ]
        }
    """.trimIndent()

    @Test
    fun `manifest without profiles maps to an update with no profile`() {
        val update = UpdateRepository.parseManifest(oldManifest, sdkInt = 31)
        assertEquals(1055, update.versionCode)
        assertEquals("kevbox-tv-1.2.0-beta.7.apk", update.assetName)
        assertNull(update.profileUrl)
        assertNull(update.profileSha256)
    }

    @Test
    fun `manifest with profiles maps the one for this Android version`() {
        val update = UpdateRepository.parseManifest(newManifest, sdkInt = 31)
        assertEquals("https://tv.kevbox.dev/kevbox-tv-1.2.0-beta.7.1-api31.dm", update.profileUrl)
        assertEquals("p31", update.profileSha256)
    }

    @Test
    fun `manifest with profiles maps none on an Android version with no profile`() {
        val update = UpdateRepository.parseManifest(newManifest, sdkInt = 26)
        assertNull(update.profileUrl)
    }

    @Test
    fun `cached update saved by an older build still decodes`() {
        val cached = """
            {"versionCode":1055,"tag":"1.2.0-beta.7","title":"1.2.0-beta.7","notes":"",
             "sha256":"3d69","releaseUrl":null,"assetName":"kevbox-tv-1.2.0-beta.7.apk",
             "assetUrl":"https://tv.kevbox.dev/kevbox-tv-1.2.0-beta.7.apk","assetSizeBytes":null}
        """.trimIndent()
        val update = UpdateJson.json.decodeFromString<AppUpdate>(cached)
        assertEquals(1055, update.versionCode)
        assertNull(update.profileUrl)
    }
}
