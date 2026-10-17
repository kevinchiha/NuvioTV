package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProfileFetcherTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val downloader = ApkDownloader(OkHttpClient())
    private val body = zipOf("primary.prof", "primary.profm")
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

    private suspend fun fetch(u: AppUpdate, trusted: Boolean = true, timeoutMs: Long = 15_000) =
        ProfileFetcher.fetch(downloader, u, apk, timeoutMs) { trusted }

    private fun respond(bytes: ByteArray) = MockResponse().setBody(Buffer().write(bytes))

    @Test
    fun `downloads and verifies the profile next to the apk`() = runTest {
        server.enqueue(respond(body))
        val file = fetch(update())
        assertEquals(File(apk.parentFile, "kevbox-tv-1.dm"), file)
        assertArrayEquals(body, file!!.readBytes())
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
        server.enqueue(respond("tampered".toByteArray()))
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
        File(apk.parentFile, "kevbox-tv-1.dm").writeBytes(body)
        assertEquals(File(apk.parentFile, "kevbox-tv-1.dm"), fetch(update()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `downloads again when the stored profile has the wrong checksum`() = runTest {
        File(apk.parentFile, "kevbox-tv-1.dm").writeText("stale")
        server.enqueue(respond(body))
        val file = fetch(update())
        assertArrayEquals(body, file!!.readBytes())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `gives up on a stalled download within the time limit`() = runTest {
        server.enqueue(respond(body).throttleBody(1, 1, TimeUnit.SECONDS))
        val started = System.nanoTime()
        assertNull(fetch(update(), timeoutMs = 500))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000)
    }

    @Test
    fun `rejects a profile that carries anything besides the two profile entries`() = runTest {
        val crafted = zipOf("primary.prof", "primary.profm", "primary.vdex")
        server.enqueue(respond(crafted))
        assertNull(fetch(update(sha = sha256(crafted))))
        assertFalse(File(apk.parentFile, "kevbox-tv-1.dm").exists())
    }

    @Test
    fun `rejects a profile that is not a zip`() = runTest {
        val junk = "not a zip".toByteArray()
        server.enqueue(respond(junk))
        assertNull(fetch(update(sha = sha256(junk))))
    }

    private fun zipOf(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name)); zip.write(name.toByteArray()); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
