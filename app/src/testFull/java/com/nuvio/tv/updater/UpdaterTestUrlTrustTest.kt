package com.nuvio.tv.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTestUrlTrustTest {

    /** A release build: the real server, no test URL. */
    private val releaseBase = "https://tv.kevbox.dev"

    /** A debug build made with -PupdaterTestBaseUrl: UPDATE_BASE_URL is the test URL itself. */
    private val testUrl = "http://10.0.2.2:8765"

    private fun trustedInRelease(asset: String) =
        UpdateDownloadDecision.isTrustedAssetUrl(asset, releaseBase, "")

    private fun trustedInTestBuild(asset: String, testBaseUrl: String = testUrl) =
        UpdateDownloadDecision.isTrustedAssetUrl(asset, testBaseUrl, testBaseUrl)

    @Test
    fun `with no test URL an http asset on the base host is refused`() {
        assertFalse(trustedInRelease("http://tv.kevbox.dev/k.apk"))
    }

    @Test
    fun `with no test URL an http asset on the emulator host is refused`() {
        assertFalse(trustedInRelease("http://10.0.2.2:8765/k.apk"))
    }

    @Test
    fun `with no test URL an https asset on the base host is trusted`() {
        assertTrue(trustedInRelease("https://tv.kevbox.dev/k.apk"))
    }

    @Test
    fun `with the test URL an http asset on its exact host and port is trusted`() {
        assertTrue(trustedInTestBuild("http://10.0.2.2:8765/k.apk"))
    }

    @Test
    fun `with the test URL the same host on another port is refused`() {
        assertFalse(trustedInTestBuild("http://10.0.2.2:8766/k.apk"))
        assertFalse(trustedInTestBuild("http://10.0.2.2/k.apk"))
    }

    @Test
    fun `with the test URL another http host is refused`() {
        assertFalse(trustedInTestBuild("http://10.0.2.3:8765/k.apk"))
        assertFalse(trustedInTestBuild("http://tv.kevbox.dev/k.apk"))
    }

    @Test
    fun `with an http test URL an https asset on its host and port is refused`() {
        assertFalse(trustedInTestBuild("https://10.0.2.2:8765/k.apk"))
    }

    @Test
    fun `with the test URL the release host is not trusted`() {
        assertFalse(trustedInTestBuild("https://tv.kevbox.dev/k.apk"))
    }

    @Test
    fun `an https test URL does not make plain http on its host and port trusted`() {
        val https = "https://10.0.2.2:8765"
        assertFalse(trustedInTestBuild("http://10.0.2.2:8765/k.apk", testBaseUrl = https))
        assertTrue(trustedInTestBuild("https://10.0.2.2:8765/k.apk", testBaseUrl = https))
        assertFalse(trustedInTestBuild("https://10.0.2.2:8766/k.apk", testBaseUrl = https))
    }

    @Test
    fun `a test URL without a port matches the http default port`() {
        val noPort = "http://10.0.2.2"
        assertTrue(trustedInTestBuild("http://10.0.2.2:80/k.apk", testBaseUrl = noPort))
        assertTrue(trustedInTestBuild("http://10.0.2.2/k.apk", testBaseUrl = noPort))
        assertFalse(trustedInTestBuild("http://10.0.2.2:8765/k.apk", testBaseUrl = noPort))
    }

    @Test
    fun `the test host matches case-insensitively`() {
        assertTrue(trustedInTestBuild("http://LOCALHOST:8765/k.apk", testBaseUrl = "http://localhost:8765"))
    }
}
