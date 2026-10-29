package com.nuvio.tv.core.auth.loginfile

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.domain.model.AuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.auth.Auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LoginFileSignIn"
private const val AUTH_SETTLE_TIMEOUT_MS = 5_000L

internal enum class LoginFileAccountAction { SignInOnly, SignOutThenSignIn }

/** What the box has saved on disk for the auth library, read without the network. */
internal sealed interface StoredSession {
    /** Nothing saved: the box has never been signed in, or was signed out. */
    data object Absent : StoredSession

    data class WithEmail(val email: String) : StoredSession

    /** A session is saved but carries no user or no email, or reading it failed. */
    data object Unknown : StoredSession
}

internal fun storedSession(found: Boolean, email: String?): StoredSession = when {
    !found -> StoredSession.Absent
    email.isNullOrBlank() -> StoredSession.Unknown
    else -> StoredSession.WithEmail(email)
}

private fun normaliseEmail(email: String) = email.filterNot { it.isWhitespace() }.lowercase()

internal fun sameEmail(a: String, b: String) = normaliseEmail(a) == normaliseEmail(b)

/**
 * Signed out: sign in. Signed in as the same email (case and spaces ignored): sign in again on
 * top, so nothing gets wiped. Signed in as another email: sign out first, so the old account's
 * profiles and history do not stay on the box. Still Loading: judge by [stored], the session
 * saved on the box. Same email signs in only, another email signs out first, a saved session
 * with no email signs out first (unknown account), nothing saved signs in only.
 */
internal fun loginFileAccountAction(
    state: AuthState,
    fileEmail: String,
    stored: StoredSession = StoredSession.Absent,
): LoginFileAccountAction = when (state) {
    AuthState.SignedOut -> LoginFileAccountAction.SignInOnly
    is AuthState.FullAccount ->
        if (sameEmail(state.email, fileEmail)) LoginFileAccountAction.SignInOnly
        else LoginFileAccountAction.SignOutThenSignIn
    AuthState.Loading -> when (stored) {
        StoredSession.Absent -> LoginFileAccountAction.SignInOnly
        is StoredSession.WithEmail ->
            if (sameEmail(stored.email, fileEmail)) LoginFileAccountAction.SignInOnly
            else LoginFileAccountAction.SignOutThenSignIn
        StoredSession.Unknown -> LoginFileAccountAction.SignOutThenSignIn
    }
}

/** Log text for the Loading case. Never contains the email. */
internal fun storedSessionLogLabel(stored: StoredSession, fileEmail: String): String = when (stored) {
    StoredSession.Absent -> "absent"
    is StoredSession.WithEmail -> if (sameEmail(stored.email, fileEmail)) "same" else "other"
    StoredSession.Unknown -> "unknown"
}

/**
 * Signs the app in from the file KevBox Setup leaves in the app folder
 * (contract: ~/projects/kevbox-setup/docs/login-file.md). The app-wide scope keeps the
 * sign-in going when the screen goes away, so the result file still gets written.
 */
@Singleton
class LoginFileSignIn @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authManager: AuthManager,
    private val auth: Auth,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Stays set until the account work ends, even after a timeout has answered the phone,
    // so a later check cannot start a second sign-in next to a stalled one.
    private val running = AtomicBoolean(false)

    // MainActivity checks once a second; look the folder up once, not on every check.
    @Volatile private var cachedFolder: File? = null

    private fun folder(): File? =
        cachedFolder ?: context.getExternalFilesDir(null)?.also { cachedFolder = it }

    fun checkNow() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            var accountWork: Job? = null
            try {
                LoginFileProcessor(
                    dir = ::folder,
                    nowMs = System::currentTimeMillis,
                    log = { Log.i(TAG, it) },
                ).process { email, password ->
                    // Never cancelled by the processor's timer: AuthManager's blocking HTTP call
                    // and its catch-all handlers would not stop cleanly if it were.
                    scope.async { signIn(email, password) }.also { accountWork = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Class name only: messages from this path could carry the file's text.
                Log.w(TAG, "login file check failed: ${e::class.java.simpleName}")
            } finally {
                val work = accountWork
                if (work == null) {
                    running.set(false)
                } else {
                    // Runs at once if the work has already ended.
                    work.invokeOnCompletion { running.set(false) }
                }
            }
        }
    }

    private suspend fun signIn(email: String, password: String): LoginFileOutcome {
        val state = withTimeoutOrNull(AUTH_SETTLE_TIMEOUT_MS) {
            authManager.authState.first { it !is AuthState.Loading }
        } ?: authManager.authState.value
        val stored = if (state is AuthState.Loading) readStoredSession() else StoredSession.Absent
        val action = loginFileAccountAction(state, email, stored)
        if (state is AuthState.Loading) {
            val next = if (action == LoginFileAccountAction.SignInOnly) "signing in" else "signing out first"
            Log.i(TAG, "auth still loading, saved session ${storedSessionLogLabel(stored, email)}, $next")
        } else if (action == LoginFileAccountAction.SignOutThenSignIn) {
            Log.i(TAG, "signed in to another account, signing out first")
        }
        if (action == LoginFileAccountAction.SignOutThenSignIn) {
            authManager.signOut()
        }
        return authManager.signInWithEmail(email, password).fold(
            onSuccess = { LoginFileOutcome.Ok },
            onFailure = { LoginFileOutcome.Failed(loginFileErrorText(it)) },
        )
    }

    // currentSessionOrNull() returns null unless the status is Authenticated, so it hides the saved
    // session exactly when auth is stuck Loading (Initializing, or RefreshFailure after a network
    // error). loadSession() reads the saved JSON from the library's settings storage: no network,
    // no write, no status change, and null when nothing is saved or the JSON does not parse.
    private suspend fun readStoredSession(): StoredSession {
        val result = runCatching { auth.sessionManager.loadSession() }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result.fold(
            onSuccess = { session -> storedSession(found = session != null, email = session?.user?.email) },
            // A read that throws leaves the account unknown; treat it like a session with no email.
            onFailure = { StoredSession.Unknown },
        )
    }
}
