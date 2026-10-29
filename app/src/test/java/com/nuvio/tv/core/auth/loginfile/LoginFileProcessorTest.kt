package com.nuvio.tv.core.auth.loginfile

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LoginFileProcessorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_761_746_592_000L
    private val password = "s3cret-Pa55"
    private val email = "customer@example.com"

    private fun folder(): File = tmp.root
    private fun loginFile() = File(folder(), LOGIN_FILE_NAME)
    private fun resultFile() = File(folder(), LOGIN_RESULT_FILE_NAME)

    private fun writeLogin(requestedAt: Long = now, emailValue: String = email) {
        loginFile().writeText("""{"email": "$emailValue", "password": "$password", "requestedAt": $requestedAt}""")
    }

    private fun processor(dir: File? = folder(), logs: MutableList<String>? = null, timeoutMs: Long = 20_000L) =
        LoginFileProcessor(dir = { dir }, nowMs = { now }, timeoutMs = timeoutMs, log = { logs?.add(it) })

    private class Recorder(private val outcome: suspend () -> LoginFileOutcome) {
        val calls = mutableListOf<Pair<String, String>>()
        suspend fun signIn(e: String, p: String): LoginFileOutcome {
            calls += e to p
            return outcome()
        }
    }

    private fun assertNoSecrets(text: String) {
        assertFalse("contains password: $text", text.contains(password))
        assertFalse("contains email: $text", text.contains(email))
    }

    @Test
    fun `no folder does nothing`() = runTest {
        val recorder = Recorder { LoginFileOutcome.Ok }
        processor(dir = null).process(recorder::signIn)
        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun `no login file does nothing`() = runTest {
        val recorder = Recorder { LoginFileOutcome.Ok }
        processor().process(recorder::signIn)
        assertTrue(recorder.calls.isEmpty())
        assertFalse(resultFile().exists())
    }

    @Test
    fun `fresh file signs in once with the file already gone and writes ok`() = runTest {
        writeLogin()
        val logs = mutableListOf<String>()
        val recorder = Recorder {
            assertFalse("login file still on disk during sign-in", loginFile().exists())
            LoginFileOutcome.Ok
        }
        processor(logs = logs).process(recorder::signIn)

        assertEquals(listOf(email to password), recorder.calls)
        assertFalse(loginFile().exists())
        assertEquals("""{"ok":true}""", resultFile().readText())
        assertFalse(File(folder(), "$LOGIN_RESULT_FILE_NAME.tmp").exists())
        logs.forEach(::assertNoSecrets)
    }

    @Test
    fun `email is trimmed`() = runTest {
        writeLogin(emailValue = "  $email ")
        val recorder = Recorder { LoginFileOutcome.Ok }
        processor().process(recorder::signIn)
        assertEquals(listOf(email to password), recorder.calls)
    }

    @Test
    fun `file dated in the future counts as fresh`() = runTest {
        writeLogin(requestedAt = now + 3_600_000L)
        val recorder = Recorder { LoginFileOutcome.Ok }
        processor().process(recorder::signIn)
        assertEquals(1, recorder.calls.size)
        assertEquals("""{"ok":true}""", resultFile().readText())
    }

    @Test
    fun `file exactly ten minutes old is still fresh`() = runTest {
        writeLogin(requestedAt = now - LOGIN_FILE_MAX_AGE_MS)
        val recorder = Recorder { LoginFileOutcome.Ok }
        processor().process(recorder::signIn)
        assertEquals(1, recorder.calls.size)
    }

    @Test
    fun `stale file is deleted with no sign-in and no result`() = runTest {
        writeLogin(requestedAt = now - LOGIN_FILE_MAX_AGE_MS - 1)
        val logs = mutableListOf<String>()
        val recorder = Recorder { LoginFileOutcome.Ok }
        processor(logs = logs).process(recorder::signIn)

        assertTrue(recorder.calls.isEmpty())
        assertFalse(loginFile().exists())
        assertFalse(resultFile().exists())
        logs.forEach(::assertNoSecrets)
    }

    @Test
    fun `invalid files are deleted with no sign-in and no result`() = runTest {
        val bad = listOf(
            "not json at all $password",
            """["$email", "$password"]""",
            """{"password": "$password", "requestedAt": $now}""",
            """{"email": "$email", "requestedAt": $now}""",
            """{"email": "$email", "password": "$password"}""",
            """{"email": "   ", "password": "$password", "requestedAt": $now}""",
            """{"email": "$email", "password": "$password", "requestedAt": "$now"}""",
            """{"email": "$email", "password": "$password", "requestedAt": 1761746592000.5}""",
            """{"email": "$email", "password": "$password", "requestedAt": null}""",
            """{"email": 42, "password": "$password", "requestedAt": $now}""",
        )
        for (text in bad) {
            loginFile().writeText(text)
            val logs = mutableListOf<String>()
            val recorder = Recorder { LoginFileOutcome.Ok }
            processor(logs = logs).process(recorder::signIn)

            assertTrue("signed in for: $text", recorder.calls.isEmpty())
            assertFalse("file left for: $text", loginFile().exists())
            assertFalse("result written for: $text", resultFile().exists())
            logs.forEach(::assertNoSecrets)
        }
    }

    @Test
    fun `failed sign-in writes the error text`() = runTest {
        writeLogin()
        val recorder = Recorder { LoginFileOutcome.Failed("Wrong email or password") }
        processor().process(recorder::signIn)
        assertEquals("""{"ok":false,"error":"Wrong email or password"}""", resultFile().readText())
    }

    @Test
    fun `wrong-password exception gives fixed text without the server reply`() = runTest {
        writeLogin()
        val serverReply = """Auth request failed endpoint=/auth/v1/token?grant_type=password status=400 """ +
            """body={"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials","p":"$password"}"""
        val logs = mutableListOf<String>()
        val recorder = Recorder { throw IllegalStateException(serverReply) }
        processor(logs = logs).process(recorder::signIn)

        val result = resultFile().readText()
        assertEquals("""{"ok":false,"error":"Wrong email or password"}""", result)
        assertNoSecrets(result)
        assertFalse(result.contains("invalid_credentials"))
        logs.forEach(::assertNoSecrets)
        logs.forEach { assertFalse(it.contains("invalid_credentials")) }
    }

    @Test
    fun `sign-in that does not finish in time writes the timeout text`() = runTest {
        writeLogin()
        val recorder = Recorder { awaitCancellation() }
        processor(timeoutMs = 20_000L).process(recorder::signIn)

        val result = resultFile().readText()
        assertEquals(
            """{"ok":false,"error":"The TV took too long to sign in. Check its internet connection and try again."}""",
            result,
        )
        assertNoSecrets(result)
    }

    @Test
    fun `fresh file in a read-only folder is not used for sign-in`() = runTest {
        writeLogin()
        val recorder = Recorder { LoginFileOutcome.Ok }
        withReadOnlyFolder {
            processor().process(recorder::signIn)
        }
        assertTrue(recorder.calls.isEmpty())
        assertTrue("delete should have failed", loginFile().exists())
    }

    // A read-only folder blocks the result write too, so this case swaps in a failing delete.
    @Test
    fun `fresh file whose delete fails writes the clear-file result and does not sign in`() = runTest {
        writeLogin()
        val recorder = Recorder { LoginFileOutcome.Ok }
        failingDeleteProcessor().process(recorder::signIn)

        assertTrue(recorder.calls.isEmpty())
        val result = resultFile().readText()
        assertEquals("""{"ok":false,"error":"The TV could not clear the login file. Try again."}""", result)
        assertNoSecrets(result)
    }

    @Test
    fun `stale file whose delete fails gets no result`() = runTest {
        writeLogin(requestedAt = now - LOGIN_FILE_MAX_AGE_MS - 1)
        val recorder = Recorder { LoginFileOutcome.Ok }
        failingDeleteProcessor().process(recorder::signIn)
        assertTrue(recorder.calls.isEmpty())
        assertFalse(resultFile().exists())
    }

    @Test
    fun `stale file in a read-only folder gets no result`() = runTest {
        writeLogin(requestedAt = now - LOGIN_FILE_MAX_AGE_MS - 1)
        val recorder = Recorder { LoginFileOutcome.Ok }
        withReadOnlyFolder {
            processor().process(recorder::signIn)
        }
        assertTrue(recorder.calls.isEmpty())
        assertFalse(resultFile().exists())
    }

    @Test
    fun `login path that is a non-empty directory gets no sign-in and no result`() = runTest {
        loginFile().mkdir()
        File(loginFile(), "inside").writeText("x")
        val recorder = Recorder { LoginFileOutcome.Ok }
        processor().process(recorder::signIn)
        assertTrue(recorder.calls.isEmpty())
        assertFalse(resultFile().exists())
        assertTrue(loginFile().isDirectory)
    }

    @Test
    fun `error text follows the cause chain and never says sync`() {
        val cases = mapOf(
            RuntimeException("wrapper", IllegalStateException("""status=400 body={"msg":"Invalid login credentials"}""")) to
                "Wrong email or password",
            RuntimeException("""{"error_code":"invalid_credentials","msg":"invalid code"}""") to "Wrong email or password",
            RuntimeException("Email not confirmed") to "This account's email is not confirmed yet",
            RuntimeException("Auth request failed endpoint=/x status=429 body={}") to
                "Too many sign-in tries. Wait a minute and try again",
            RuntimeException("wrapper", java.net.UnknownHostException()) to
                "The TV could not reach the internet. Check its connection and try again",
            java.net.SocketTimeoutException() to
                "The TV could not reach the internet. Check its connection and try again",
            java.net.ConnectException() to
                "The TV could not reach the internet. Check its connection and try again",
            RuntimeException("Request timeout has expired") to
                "The TV could not reach the internet. Check its connection and try again",
            RuntimeException("invalid sync code") to "Sign-in did not work. Try again, or sign in on the TV",
            RuntimeException() to "Sign-in did not work. Try again, or sign in on the TV",
        )
        for ((error, expected) in cases) {
            val text = loginFileErrorText(error)
            assertEquals(expected, text)
            assertFalse(text.lowercase().contains("sync"))
        }
    }

    private fun failingDeleteProcessor() = LoginFileProcessor(
        dir = { folder() },
        nowMs = { now },
        timeoutMs = 20_000L,
        log = {},
        deleteFile = { false },
    )

    private inline fun withReadOnlyFolder(block: () -> Unit) {
        val dir = folder()
        assertTrue(dir.setWritable(false))
        try {
            // Root ignores folder permissions; skip rather than pass for the wrong reason.
            val probe = File(dir, "probe")
            val writable = runCatching { probe.createNewFile() }.getOrDefault(false)
            if (writable) probe.delete()
            assumeFalse("folder still writable, running as root?", writable)
            block()
        } finally {
            dir.setWritable(true)
        }
    }
}
