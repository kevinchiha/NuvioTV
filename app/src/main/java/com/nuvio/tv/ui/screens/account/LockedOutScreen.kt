@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors

/**
 * Which guard tripped the lockout. The caller decides which message to show; when both the
 * access kill-switch and the device limit are locked at once it passes [ACCESS] (the access
 * message wins) — see MEMBER-ACCESS-PLAN.md.
 */
enum class LockReason {
    ACCESS,
    DEVICE
}

/**
 * Full-screen, navigation-blocking lockout gate. Rendered as an early-return guard in
 * MainActivity's Surface body when the server has disabled the member ([LockReason.ACCESS]) or
 * the device is over the per-member limit ([LockReason.DEVICE]).
 *
 * It mirrors the styling of [AuthSignInScreen]: a centered elevated card on the app background,
 * TV-remote focusable, with a single focused Retry button that re-runs the access/device check
 * via [onRetry]. A no-op [BackHandler] swallows Back so the member can't navigate out of the lock.
 */
@Composable
fun LockedOutScreen(
    reason: LockReason,
    onRetry: () -> Unit
) {
    // Back must not escape the lock screen.
    BackHandler { /* swallow back — the member is locked out */ }

    val retryFocusRequester = remember { FocusRequester() }

    // Pull focus onto the Retry button so a TV remote has something to act on immediately.
    LaunchedEffect(Unit) {
        runCatching { retryFocusRequester.requestFocus() }
    }

    val message = when (reason) {
        LockReason.ACCESS -> "Access disabled — contact the administrator"
        LockReason.DEVICE -> "This device isn't authorized — contact the administrator"
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
                .fillMaxWidth(0.5f)
                .background(
                    color = NuvioColors.BackgroundElevated,
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(horizontal = 40.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Access locked",
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioColors.TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    focusedContainerColor = NuvioColors.FocusBackground,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContentColor = NuvioColors.TextPrimary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(retryFocusRequester)
            ) {
                Text(
                    text = "Retry",
                    modifier = Modifier.padding(vertical = 4.dp),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
