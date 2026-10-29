package com.nuvio.tv.core.auth.loginfile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import java.io.File

// Implements ~/projects/kevbox-setup/docs/login-file.md: KevBox Setup leaves the login file in
// the app's external files folder and waits up to 30 s for the result file.

const val LOGIN_FILE_NAME = "kevbox-login.json"
const val LOGIN_RESULT_FILE_NAME = "kevbox-login.result.json"
const val LOGIN_FILE_MAX_AGE_MS = 600_000L

internal const val LOGIN_FILE_TIMEOUT_ERROR =
    "The TV took too long to sign in. Check its internet connection and try again."
internal const val LOGIN_FILE_DELETE_ERROR = "The TV could not clear the login file. Try again."

// True when the file is gone afterwards, even if another process removed it first.
private fun deleteLoginFile(file: File): Boolean =
    try {
        file.delete() || !file.exists()
    } catch (e: SecurityException) {
        false
    }

sealed interface LoginFileOutcome {
    data object Ok : LoginFileOutcome
    data class Failed(val error: String) : LoginFileOutcome
}

/**
 * Reads, deletes and acts on one login file. Pure JVM so tests can drive it with a temp folder.
 * Nothing here may log or store the email, the password or the file's text.
 */
class LoginFileProcessor internal constructor(
    private val dir: () -> File?,
    private val nowMs: () -> Long,
    private val timeoutMs: Long,
    private val log: (String) -> Unit,
    // Tests swap this in: a folder that blocks the delete also blocks the result write.
    private val deleteFile: (File) -> Boolean,
) {
    constructor(
        dir: () -> File?,
        nowMs: () -> Long,
        timeoutMs: Long = 20_000L,
        log: (String) -> Unit = {},
    ) : this(dir, nowMs, timeoutMs, log, ::deleteLoginFile)

    private class Login(val email: String, val password: String, val requestedAt: Long)

    suspend fun process(signIn: suspend (email: String, password: String) -> LoginFileOutcome) {
        val folder = dir() ?: return
        val file = File(folder, LOGIN_FILE_NAME)
        if (!file.exists()) return

        val text = try {
            file.readText()
        } catch (e: Exception) {
            null
        }
        // Delete before anything else so the password never outlives the read.
        val deleted = deleteFile(file)
        val login = text?.let(::parse)

        if (login == null) {
            log("login file unreadable or missing a key, ${if (deleted) "deleted" else "delete failed"}")
            return
        }
        // A date in the future counts as fresh. Written this way round so it cannot overflow.
        if (login.requestedAt < nowMs() - LOGIN_FILE_MAX_AGE_MS) {
            log("login file stale, ${if (deleted) "deleted" else "delete failed"}")
            return
        }
        if (!deleted) {
            log("login file delete failed, not signing in")
            writeResult(folder, LoginFileOutcome.Failed(LOGIN_FILE_DELETE_ERROR))
            return
        }

        log("login file read and deleted, signing in")
        val outcome = try {
            withTimeoutOrNull(timeoutMs) { signIn(login.email, login.password) }
                ?: LoginFileOutcome.Failed(LOGIN_FILE_TIMEOUT_ERROR).also { log("login file sign-in timed out") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("login file sign-in threw ${e::class.java.simpleName}")
            LoginFileOutcome.Failed(loginFileErrorText(e))
        }
        writeResult(folder, outcome)
        log("login file result written, ok=${outcome is LoginFileOutcome.Ok}")
    }

    private fun parse(text: String): Login? {
        val obj = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            // The parser's message quotes the input, so it must not reach a log.
            null
        } ?: return null
        val email = (obj["email"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        val password = (obj["password"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val requestedAt = (obj["requestedAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (email.isNullOrBlank() || password == null || requestedAt == null) return null
        return Login(email, password, requestedAt)
    }

    // Write to a temporary name and rename, so KevBox Setup never reads half a file.
    private fun writeResult(folder: File, outcome: LoginFileOutcome) {
        val json = when (outcome) {
            LoginFileOutcome.Ok -> buildJsonObject { put("ok", true) }
            is LoginFileOutcome.Failed -> buildJsonObject {
                put("ok", false)
                put("error", outcome.error)
            }
        }.toString()
        val tmp = File(folder, "$LOGIN_RESULT_FILE_NAME.tmp")
        val result = File(folder, LOGIN_RESULT_FILE_NAME)
        try {
            tmp.writeText(json)
            if (!tmp.renameTo(result)) {
                result.delete()
                if (!tmp.renameTo(result)) {
                    tmp.delete()
                    log("login result rename failed")
                }
            }
        } catch (e: Exception) {
            log("login result write failed: ${e::class.java.simpleName}")
        }
    }
}
