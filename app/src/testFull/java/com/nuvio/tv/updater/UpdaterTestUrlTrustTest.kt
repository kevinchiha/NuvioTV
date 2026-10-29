package com.nuvio.tv.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTestUrlTrustTest {

    private val base = "https://tv.kevbox.dev"
    private val testUrl = "http://10.0.2.2:8765"

    private fun trusted(asset: String, testBaseUrl: String = testUrl) =
        UpdateDownloadDecision.isTrustedAssetUrl(asset, base, testBaseUrl)

    @Test
    fun `with no test URL an http asset on the base host is refused`() {
        assertFalse(trusted("http://tv.kevbox.dev/k.apk", testBaseUrl = ""))
    }

    @Test
    fun `with no test URL an http asset on the emulator host is refused`() {
        assertFalse(trusted("http://10.0.2.2:8765/k.apk", testBaseUrl = ""))
    }

    @Test
    fun `with the test URL an http asset on its exact host and port is trusted`() {
        assertTrue(trusted("http://10.0.2.2:8765/k.apk"))
    }

    @Test
    fun `with the test URL the same host on another port is refused`() {
        assertFalse(trusted("http://10.0.2.2:8766/k.apk"))
        assertFalse(trusted("http://10.0.2.2/k.apk"))
    }

    @Test
    fun `with the test URL another http host is refused`() {
        assertFalse(trusted("http://10.0.2.3:8765/k.apk"))
        assertFalse(trusted("http://tv.kevbox.dev/k.apk"))
    }

    @Test
    fun `with the test URL an https asset on its host and port is refused`() {
        assertFalse(trusted("https://10.0.2.2:8765/k.apk"))
    }

    @Test
    fun `with the test URL an https asset on the base host is still trusted`() {
        assertTrue(trusted("https://tv.kevbox.dev/k.apk"))
    }

    @Test
    fun `a test URL without a port matches the http default port`() {
        assertTrue(trusted("http://10.0.2.2:80/k.apk", testBaseUrl = "http://10.0.2.2"))
        assertTrue(trusted("http://10.0.2.2/k.apk", testBaseUrl = "http://10.0.2.2"))
        assertFalse(trusted("http://10.0.2.2:8765/k.apk", testBaseUrl = "http://10.0.2.2"))
    }

    @Test
    fun `the test host matches case-insensitively`() {
        assertTrue(trusted("http://LOCALHOST:8765/k.apk", testBaseUrl = "http://localhost:8765"))
    }
}
