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

/**
 * Signed out: sign in. Signed in as the same email (case and spaces ignored): sign in again on
 * top, so nothing gets wiped. Signed in as another email: sign out first, so the old account's
 * profiles and history do not stay on the box. Still Loading: judge by [storedSessionEmail], the
 * email of the session saved on the box (no network); none saved means sign in only.
 */
internal fun loginFileAccountAction(
    state: AuthState,
    fileEmail: String,
    storedSessionEmail: String? = null,
): LoginFileAccountAction {
    fun normalise(email: String) = email.filterNot { it.isWhitespace() }.lowercase()
    fun sameAs(email: String) = normalise(email) == normalise(fileEmail)
    return when (state) {
        AuthState.SignedOut -> LoginFileAccountAction.SignInOnly
        is AuthState.FullAccount ->
            if (sameAs(state.email)) LoginFileAccountAction.SignInOnly else LoginFileAccountAction.SignOutThenSignIn
        AuthState.Loading ->
            if (storedSessionEmail.isNullOrBlank() || sameAs(storedSessionEmail)) {
                LoginFileAccountAction.SignInOnly
            } else {
                LoginFileAccountAction.SignOutThenSignIn
            }
    }
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
        val storedEmail = if (state is AuthState.Loading) storedSessionEmail() else null
        val action = loginFileAccountAction(state, email, storedEmail)
        if (state is AuthState.Loading) {
            Log.i(
                TAG,
                when {
                    storedEmail.isNullOrBlank() -> "auth still loading, no saved session, signing in"
                    action == LoginFileAccountAction.SignInOnly ->
                        "auth still loading, saved session is the same account, signing in"
                    else -> "auth still loading, saved session is another account, signing out first"
                },
            )
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

    // Reads the session the auth library keeps in memory; makes no network call.
    private fun storedSessionEmail(): String? =
        try {
            auth.currentUserOrNull()?.email ?: auth.currentSessionOrNull()?.user?.email
        } catch (e: Exception) {
            null
        }
}
