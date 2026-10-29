package com.nuvio.tv.core.auth.loginfile

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.domain.model.AuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LoginFileSignIn"
private const val AUTH_SETTLE_TIMEOUT_MS = 5_000L

internal enum class LoginFileAccountAction { SignInOnly, SignOutThenSignIn }

/**
 * Same account (case and spaces ignored): sign in again on top, so nothing gets wiped.
 * Anything else that is not signed out, Loading included: sign out first, so the old
 * account's profiles and history do not stay on the box.
 */
internal fun loginFileAccountAction(state: AuthState, fileEmail: String): LoginFileAccountAction {
    fun normalise(email: String) = email.filterNot { it.isWhitespace() }.lowercase()
    return when {
        state is AuthState.SignedOut -> LoginFileAccountAction.SignInOnly
        state is AuthState.FullAccount && normalise(state.email) == normalise(fileEmail) ->
            LoginFileAccountAction.SignInOnly
        else -> LoginFileAccountAction.SignOutThenSignIn
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    fun checkNow() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                LoginFileProcessor(
                    dir = { context.getExternalFilesDir(null) },
                    nowMs = System::currentTimeMillis,
                    log = { Log.i(TAG, it) },
                ).process(::signIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Class name only: messages from this path could carry the file's text.
                Log.w(TAG, "login file check failed: ${e::class.java.simpleName}")
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun signIn(email: String, password: String): LoginFileOutcome {
        val state = withTimeoutOrNull(AUTH_SETTLE_TIMEOUT_MS) {
            authManager.authState.first { it !is AuthState.Loading }
        } ?: authManager.authState.value
        if (loginFileAccountAction(state, email) == LoginFileAccountAction.SignOutThenSignIn) {
            Log.i(TAG, "login file is for another account, signing out first")
            authManager.signOut()
        }
        return authManager.signInWithEmail(email, password).fold(
            onSuccess = { LoginFileOutcome.Ok },
            onFailure = { LoginFileOutcome.Failed(loginFileErrorText(it)) },
        )
    }
}
