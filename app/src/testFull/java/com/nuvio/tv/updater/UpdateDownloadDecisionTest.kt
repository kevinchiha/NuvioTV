package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadDecisionTest {

    private fun decide(
        remote: Int = 1030,
        current: Int = 1028,
        ignored: Int? = null,
        alreadyCached: Boolean = false,
        unmetered: Boolean = true,
    ) = UpdateDownloadDecision.shouldDownload(remote, current, ignored, alreadyCached, unmetered)

    // --- shouldDownload ---

    @Test
    fun `downloads when newer, not ignored, not cached, on unmetered`() {
        assertTrue(decide())
    }

    @Test
    fun `skips when remote equals current`() {
        assertFalse(decide(remote = 1028, current = 1028))
    }

    @Test
    fun `skips when remote is older than current`() {
        assertFalse(decide(remote = 1027, current = 1028))
    }

    @Test
    fun `skips when this version is ignored`() {
        assertFalse(decide(remote = 1030, ignored = 1030))
    }

    @Test
    fun `downloads when a different version is ignored`() {
        assertTrue(decide(remote = 1030, ignored = 1029))
    }

    @Test
    fun `skips when already cached for this version`() {
        assertFalse(decide(alreadyCached = true))
    }

    @Test
    fun `skips when network is metered`() {
        assertFalse(decide(unmetered = false))
    }

    // --- isCachedFor ---

    @Test
    fun `isCachedFor true when version matches and file exists`() {
        assertTrue(UpdateDownloadDecision.isCachedFor(1030, 1030, cachedFileExists = true))
    }

    @Test
    fun `isCachedFor false when version differs`() {
        assertFalse(UpdateDownloadDecision.isCachedFor(1029, 1030, cachedFileExists = true))
    }

    @Test
    fun `isCachedFor false when file missing`() {
        assertFalse(UpdateDownloadDecision.isCachedFor(1030, 1030, cachedFileExists = false))
    }

    @Test
    fun `isCachedFor false when no cached version`() {
        assertFalse(UpdateDownloadDecision.isCachedFor(null, 1030, cachedFileExists = true))
    }

    // --- staleFileNames ---

    @Test
    fun `staleFileNames returns everything except the kept name`() {
        assertEquals(
            listOf("old-1.apk", "old-2.apk"),
            UpdateDownloadDecision.staleFileNames(listOf("old-1.apk", "keep.apk", "old-2.apk"), "keep.apk"),
        )
    }

    @Test
    fun `staleFileNames with null keep returns everything`() {
        assertEquals(
            listOf("a.apk", "b.apk"),
            UpdateDownloadDecision.staleFileNames(listOf("a.apk", "b.apk"), null),
        )
    }

    // --- isTrustedAssetUrl ---

    @Test
    fun `isTrustedAssetUrl trusts https on the configured host`() {
        assertTrue(UpdateDownloadDecision.isTrustedAssetUrl("https://tv.kevbox.dev/k.apk", "https://tv.kevbox.dev"))
    }

    @Test
    fun `isTrustedAssetUrl rejects http on the configured host`() {
        assertFalse(UpdateDownloadDecision.isTrustedAssetUrl("http://tv.kevbox.dev/k.apk", "https://tv.kevbox.dev"))
    }

    @Test
    fun `isTrustedAssetUrl rejects https on a different host`() {
        assertFalse(UpdateDownloadDecision.isTrustedAssetUrl("https://evil.example/k.apk", "https://tv.kevbox.dev"))
    }

    @Test
    fun `isTrustedAssetUrl rejects a malformed url`() {
        assertFalse(UpdateDownloadDecision.isTrustedAssetUrl("not a url", "https://tv.kevbox.dev"))
    }
}
