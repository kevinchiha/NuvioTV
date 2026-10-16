package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ProfileFetcherTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val downloader = ApkDownloader(OkHttpClient())
    private val body = "profile-bytes"
    private val bodySha = sha256(body)
    private lateinit var apk: File

    @Before
    fun setUp() {
        server.start()
        apk = tmp.newFile("kevbox-tv-1.apk")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun update(url: String? = server.url("/k.dm").toString(), sha: String? = bodySha) = AppUpdate(
        versionCode = 1056, tag = "t", title = "t", notes = "", sha256 = "x", releaseUrl = null,
        assetName = apk.name, assetUrl = "https://tv.kevbox.dev/${apk.name}", assetSizeBytes = null,
        profileUrl = url, profileSha256 = sha,
    )

    private suspend fun fetch(u: AppUpdate, trusted: Boolean = true) =
        ProfileFetcher.fetch(downloader, u, apk) { trusted }

    @Test
    fun `downloads and verifies the profile next to the apk`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        val file = fetch(update())
        assertEquals(File(apk.parentFile, "kevbox-tv-1.dm"), file)
        assertEquals(body, file!!.readText())
    }

    @Test
    fun `returns null when the update has no profile`() = runTest {
        assertNull(fetch(update(url = null, sha = null)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `returns null for an untrusted url without requesting it`() = runTest {
        assertNull(fetch(update(), trusted = false))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `returns null and deletes the file on a checksum mismatch`() = runTest {
        server.enqueue(MockResponse().setBody("tampered"))
        assertNull(fetch(update()))
        assertFalse(File(apk.parentFile, "kevbox-tv-1.dm").exists())
    }

    @Test
    fun `returns null when the server fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(fetch(update()))
    }

    @Test
    fun `reuses an already verified profile without a request`() = runTest {
        File(apk.parentFile, "kevbox-tv-1.dm").writeText(body)
        assertEquals(File(apk.parentFile, "kevbox-tv-1.dm"), fetch(update()))
        assertEquals(0, server.requestCount)
    }

    private fun sha256(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
