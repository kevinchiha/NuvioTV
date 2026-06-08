@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors

/**
 * One reusable, D-pad-friendly email + password sign-in form used both by the first-run
 * onboarding gate and the in-settings sign-in. It reuses [InputField] (the existing TV-focusable
 * BasicTextField) for text entry, adds a password-visibility toggle, an inline error row, and a
 * submit button. It does not know about the backend — the caller wires [onSubmit] to
 * AccountViewModel.signIn().
 *
 * Account creation is handled in the Supabase dashboard, not here — this form only ever signs in.
 */
@Composable
fun EmailPasswordForm(
    onSubmit: (email: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    error: String? = null,
    prefillEmail: String? = null,
    title: String = stringResource(R.string.auth_signin_title),
    submitLabel: String = stringResource(R.string.debug_sign_in),
    oneTapEmail: String? = null,
    onOneTap: (() -> Unit)? = null,
    secondaryButton: (@Composable () -> Unit)? = null
) {
    val showOneTap = !oneTapEmail.isNullOrBlank() && onOneTap != null
    val oneTapFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    // Land focus somewhere useful on open: the one-tap button if we have a saved credential,
    // otherwise the email field — so the family isn't left hunting with the D-pad.
    LaunchedEffect(showOneTap) {
        try {
            if (showOneTap) oneTapFocus.requestFocus() else emailFocus.requestFocus()
        } catch (_: Exception) { }
    }
    var email by remember(prefillEmail) { mutableStateOf(prefillEmail.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val canSubmit = !isLoading && email.isNotBlank() && password.isNotBlank()
    val submit: () -> Unit = {
        if (!isLoading && email.isNotBlank() && password.isNotBlank()) {
            onSubmit(email.trim(), password)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioColors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )

        // One-tap re-login: a single focused button that decrypts the saved credential and signs
        // in. The manual form stays below for switching accounts.
        if (showOneTap) {
            Button(
                onClick = { if (!isLoading) onOneTap?.invoke() },
                enabled = !isLoading,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.Secondary,
                    focusedContainerColor = NuvioColors.SecondaryVariant,
                    contentColor = NuvioColors.OnSecondary,
                    focusedContentColor = NuvioColors.OnSecondaryVariant,
                    disabledContainerColor = NuvioColors.Secondary.copy(alpha = 0.4f)
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(oneTapFocus)
            ) {
                Text(
                    text = if (isLoading) stringResource(R.string.debug_signing_in) else "Sign in as $oneTapEmail",
                    modifier = Modifier.padding(vertical = 4.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "or sign in with a different account",
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary,
                textAlign = TextAlign.Center
            )
        } else if (!prefillEmail.isNullOrBlank()) {
            // "Sign in as <email>" prompt when we remember the last user (manual path only).
            Text(
                text = "${stringResource(R.string.account_signed_in_as)} $prefillEmail",
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary,
                textAlign = TextAlign.Center
            )
        }

        InputField(
            value = email,
            onValueChange = { email = it },
            placeholder = stringResource(R.string.debug_email_placeholder),
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            onImeAction = { try { passwordFocus.requestFocus() } catch (_: Exception) { } },
            modifier = Modifier.focusRequester(emailFocus)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                InputField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = stringResource(R.string.debug_password_placeholder),
                    isPassword = !passwordVisible,
                    imeAction = ImeAction.Done,
                    onImeAction = submit,
                    modifier = Modifier.focusRequester(passwordFocus)
                )
            }
            Button(
                onClick = { passwordVisible = !passwordVisible },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    focusedContainerColor = NuvioColors.FocusBackground,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContentColor = NuvioColors.TextPrimary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
            ) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (!error.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = NuvioColors.Error.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.Error,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Button(
            onClick = submit,
            enabled = canSubmit,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.Secondary,
                focusedContainerColor = NuvioColors.SecondaryVariant,
                contentColor = NuvioColors.OnSecondary,
                focusedContentColor = NuvioColors.OnSecondaryVariant,
                disabledContainerColor = NuvioColors.Secondary.copy(alpha = 0.4f)
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(50)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (isLoading) stringResource(R.string.debug_signing_in) else submitLabel,
                modifier = Modifier.padding(vertical = 4.dp),
                fontWeight = FontWeight.Medium
            )
        }

        if (secondaryButton != null) {
            secondaryButton()
        }
    }
}
