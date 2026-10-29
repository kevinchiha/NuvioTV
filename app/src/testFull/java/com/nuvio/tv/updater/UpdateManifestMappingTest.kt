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

    // --- silent install block (AC-1) ---

    private fun manifestWithSilent(silentJson: String) = """
        {
          "versionCode": 1060,
          "versionName": "1.3.0",
          "url": "https://tv.kevbox.dev/kevbox-tv-1.3.0.apk",
          "sha256": "beef",
          "notes": "Silent",
          "silent": $silentJson
        }
    """.trimIndent()

    private fun assertNoSilentBlockAndRestMaps(silentJson: String) {
        val update = UpdateRepository.parseManifest(manifestWithSilent(silentJson), sdkInt = 31)
        assertEquals(1060, update.versionCode)
        assertEquals("https://tv.kevbox.dev/kevbox-tv-1.3.0.apk", update.assetUrl)
        assertEquals("beef", update.sha256)
        assertNull(update.silentAfterMs)
        assertEquals(emptyList<String>(), update.silentEarlyUsers)
        assertNull(update.silentBlock())
    }

    @Test
    fun `manifest without a silent key maps as today with no silent block`() {
        val update = UpdateRepository.parseManifest(oldManifest, sdkInt = 31)
        assertEquals(1055, update.versionCode)
        assertEquals("1.2.0-beta.7", update.tag)
        assertEquals("1.2.0-beta.7", update.title)
        assertEquals("Bug fixes", update.notes)
        assertEquals("3d69", update.sha256)
        assertEquals("https://tv.kevbox.dev/kevbox-tv-1.2.0-beta.7.apk", update.assetUrl)
        assertEquals("kevbox-tv-1.2.0-beta.7.apk", update.assetName)
        assertNull(update.silentAfterMs)
        assertEquals(emptyList<String>(), update.silentEarlyUsers)
        assertNull(update.silentBlock())
    }

    @Test
    fun `a valid block maps after and earlyUsers, and a missing earlyUsers gives an empty list`() {
        val withUsers = UpdateRepository.parseManifest(
            manifestWithSilent("""{"after": 1793000000000, "earlyUsers": ["abc-123", " DEF-456 "]}"""),
            sdkInt = 31
        )
        assertEquals(1060, withUsers.versionCode)
        assertEquals(1793000000000L, withUsers.silentAfterMs)
        assertEquals(listOf("abc-123", " DEF-456 "), withUsers.silentEarlyUsers)
        assertEquals(
            SilentBlock(1793000000000L, listOf("abc-123", " DEF-456 ")),
            withUsers.silentBlock()
        )

        val withoutUsers = UpdateRepository.parseManifest(
            manifestWithSilent("""{"after": 1793000000000}"""),
            sdkInt = 31
        )
        assertEquals(1793000000000L, withoutUsers.silentAfterMs)
        assertEquals(emptyList<String>(), withoutUsers.silentEarlyUsers)
        assertEquals(SilentBlock(1793000000000L, emptyList()), withoutUsers.silentBlock())
    }

    @Test
    fun `a null earlyUsers gives an empty list`() {
        val update = UpdateRepository.parseManifest(
            manifestWithSilent("""{"after": 5, "earlyUsers": null}"""),
            sdkInt = 31
        )
        assertEquals(SilentBlock(5L, emptyList()), update.silentBlock())
    }

    @Test
    fun `malformed block - silent is a string`() = assertNoSilentBlockAndRestMaps("\"yes\"")

    @Test
    fun `malformed block - silent is an empty object`() = assertNoSilentBlockAndRestMaps("{}")

    @Test
    fun `malformed block - after is a word`() =
        assertNoSilentBlockAndRestMaps("""{"after": "soon"}""")

    @Test
    fun `malformed block - after is a quoted number`() =
        assertNoSilentBlockAndRestMaps("""{"after": "1793000000000"}""")

    @Test
    fun `malformed block - after is zero`() = assertNoSilentBlockAndRestMaps("""{"after": 0}""")

    @Test
    fun `malformed block - after is negative`() =
        assertNoSilentBlockAndRestMaps("""{"after": -5}""")

    @Test
    fun `malformed block - after is a fraction`() =
        assertNoSilentBlockAndRestMaps("""{"after": 1.5}""")

    @Test
    fun `malformed block - after is a nested object`() =
        assertNoSilentBlockAndRestMaps("""{"after": {"ms": 5}}""")

    @Test
    fun `malformed block - after is null`() =
        assertNoSilentBlockAndRestMaps("""{"after": null}""")

    @Test
    fun `malformed block - earlyUsers is a string`() =
        assertNoSilentBlockAndRestMaps("""{"after": 5, "earlyUsers": "x"}""")

    @Test
    fun `malformed block - earlyUsers is a number`() =
        assertNoSilentBlockAndRestMaps("""{"after": 5, "earlyUsers": 7}""")

    @Test
    fun `malformed block - earlyUsers holds a non-string`() =
        assertNoSilentBlockAndRestMaps("""{"after": 5, "earlyUsers": ["a", 7]}""")

    @Test
    fun `malformed block - earlyUsers holds a null`() =
        assertNoSilentBlockAndRestMaps("""{"after": 5, "earlyUsers": ["a", null]}""")

    @Test
    fun `AppUpdate JSON cached by an older build decodes with no silent block`() {
        val cached = """
            {"versionCode":1056,"tag":"1.2.0-beta.7.1","title":"1.2.0-beta.7.1","notes":"",
             "sha256":"abcd","releaseUrl":null,"assetName":"kevbox-tv-1.2.0-beta.7.1.apk",
             "assetUrl":"https://tv.kevbox.dev/kevbox-tv-1.2.0-beta.7.1.apk","assetSizeBytes":null,
             "profileUrl":"https://tv.kevbox.dev/kevbox-tv-1.2.0-beta.7.1-api31.dm",
             "profileSha256":"p31"}
        """.trimIndent()
        val update = UpdateJson.json.decodeFromString<AppUpdate>(cached)
        assertEquals(1056, update.versionCode)
        assertEquals("p31", update.profileSha256)
        assertNull(update.silentAfterMs)
        assertEquals(emptyList<String>(), update.silentEarlyUsers)
        assertNull(update.silentBlock())
    }

    @Test
    fun `AppUpdate with a silent block survives the cache round trip`() {
        val update = UpdateRepository.parseManifest(
            manifestWithSilent("""{"after": 1793000000000, "earlyUsers": ["abc"]}"""),
            sdkInt = 31
        )
        val restored = UpdateJson.json.decodeFromString<AppUpdate>(
            UpdateJson.json.encodeToString(AppUpdate.serializer(), update)
        )
        assertEquals(update, restored)
        assertEquals(SilentBlock(1793000000000L, listOf("abc")), restored.silentBlock())
    }

    @Test
    fun `silentBlock is null when silentAfterMs is not positive`() {
        val update = UpdateRepository.parseManifest(oldManifest, sdkInt = 31)
        assertNull(update.copy(silentAfterMs = 0L, silentEarlyUsers = listOf("a")).silentBlock())
        assertNull(update.copy(silentAfterMs = -1L).silentBlock())
    }
}
