@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.LastSignInDataStore
import com.nuvio.tv.domain.model.AuthState
import kotlinx.coroutines.launch

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface LastSignInEntryPoint {
    fun lastSignInDataStore(): LastSignInDataStore
}

/**
 * In-settings email/password sign-in. Replaces the old QR-only entry. The QR flow is dead
 * (its backing RPCs are gone) so it is intentionally not surfaced here anymore; the parameter
 * is kept for callers but no longer used.
 */
@Composable
fun AuthSignInScreen(
    onBackPress: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onNavigateToQrSignIn: () -> Unit = {},
    onSuccess: () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lastSignInDataStore = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            LastSignInEntryPoint::class.java
        ).lastSignInDataStore()
    }
    val lastEmail by lastSignInDataStore.lastEmail.collectAsState(initial = null)
    val hasSavedCredential by lastSignInDataStore.hasSavedCredential.collectAsState(initial = false)
    val uiState by viewModel.uiState.collectAsState()
    val isSignedIn = uiState.authState is AuthState.FullAccount
    var pendingPassword by remember { mutableStateOf("") }

    BackHandler { onBackPress() }

    LaunchedEffect(isSignedIn) {
        if (isSignedIn) {
            (uiState.authState as? AuthState.FullAccount)?.let {
                if (pendingPassword.isNotBlank()) {
                    lastSignInDataStore.saveCredential(it.email, pendingPassword)
                } else {
                    lastSignInDataStore.setLastEmail(it.email)
                }
            }
            onSuccess()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .background(
                    color = NuvioTheme.colors.BackgroundElevated,
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(NuvioTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            EmailPasswordForm(
                onSubmit = { email, password ->
                    pendingPassword = password
                    viewModel.clearError()
                    viewModel.signIn(email, password)
                },
                isLoading = uiState.isLoading,
                error = uiState.error,
                prefillEmail = lastEmail,
                title = stringResource(R.string.auth_signin_title),
                oneTapEmail = if (hasSavedCredential) lastEmail else null,
                onOneTap = {
                    val em = lastEmail
                    if (!em.isNullOrBlank()) {
                        scope.launch {
                            val pw = lastSignInDataStore.decryptedPassword()
                            if (!pw.isNullOrBlank()) {
                                pendingPassword = pw
                                viewModel.clearError()
                                viewModel.signIn(em, pw)
                            }
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.account_sign_in_description),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Full-screen first-run onboarding gate built around [EmailPasswordForm]. Replaces the old QR
 * onboarding screen. Sign-in is optional — "Skip for now" continues into the app unauthenticated
 * so the family can use it immediately. On success the last email is remembered and [onContinue]
 * is invoked.
 */
@Composable
fun AuthEmailOnboardingScreen(
    onContinue: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lastSignInDataStore = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            LastSignInEntryPoint::class.java
        ).lastSignInDataStore()
    }
    val lastEmail by lastSignInDataStore.lastEmail.collectAsState(initial = null)
    val hasSavedCredential by lastSignInDataStore.hasSavedCredential.collectAsState(initial = false)
    val uiState by viewModel.uiState.collectAsState()
    val isSignedIn = uiState.authState is AuthState.FullAccount
    var pendingPassword by remember { mutableStateOf("") }

    BackHandler { /* first screen — swallow back to avoid leaving the app */ }

    LaunchedEffect(isSignedIn) {
        if (isSignedIn) {
            (uiState.authState as? AuthState.FullAccount)?.let {
                if (pendingPassword.isNotBlank()) {
                    lastSignInDataStore.saveCredential(it.email, pendingPassword)
                } else {
                    lastSignInDataStore.setLastEmail(it.email)
                }
            }
            onContinue()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.46f)
                .background(
                    color = NuvioColors.BackgroundElevated,
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(horizontal = 40.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.account_sign_in_description),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))

            EmailPasswordForm(
                onSubmit = { email, password ->
                    pendingPassword = password
                    viewModel.clearError()
                    viewModel.signIn(email, password)
                },
                isLoading = uiState.isLoading,
                error = uiState.error,
                prefillEmail = lastEmail,
                title = stringResource(R.string.auth_signin_title),
                oneTapEmail = if (hasSavedCredential) lastEmail else null,
                onOneTap = {
                    val em = lastEmail
                    if (!em.isNullOrBlank()) {
                        scope.launch {
                            val pw = lastSignInDataStore.decryptedPassword()
                            if (!pw.isNullOrBlank()) {
                                pendingPassword = pw
                                viewModel.clearError()
                                viewModel.signIn(em, pw)
                            }
                        }
                    }
                },
                secondaryButton = {
                    Button(
                        onClick = {
                            viewModel.clearError()
                            onContinue()
                        },
                        enabled = !uiState.isLoading,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            contentColor = NuvioColors.TextPrimary,
                            focusedContentColor = NuvioColors.TextPrimary
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.auth_qr_continue_without_account),
                            modifier = Modifier.padding(vertical = 4.dp),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            )
        }
    }
}
