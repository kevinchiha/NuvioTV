@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.data.local.LastSignInDataStore
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.AuthState
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import kotlinx.coroutines.launch

// Cross-device sync overview is backed by the get_sync_overview RPC, which no longer exists on
// the self-owned auth-only backend. Keep the UI code in place but gate it off so the family
// never sees a permanently-empty/broken sync panel.
private const val SHOW_SYNC_OVERVIEW = false

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface AccountSettingsLastSignInEntryPoint {
    fun lastSignInDataStore(): LastSignInDataStore
}

@Composable
fun AccountSettingsContent(
    uiState: AccountUiState,
    viewModel: AccountViewModel,
    @Suppress("UNUSED_PARAMETER") onNavigateToAuthQrSignIn: () -> Unit = {},
    initialFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val lastSignInDataStore = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            AccountSettingsLastSignInEntryPoint::class.java
        ).lastSignInDataStore()
    }
    val lastEmail by lastSignInDataStore.lastEmail.collectAsState(initial = null)
    val hasSavedCredential by lastSignInDataStore.hasSavedCredential.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var pendingPassword by remember { mutableStateOf("") }

    val signedInEmail = (uiState.authState as? AuthState.FullAccount)?.email
    androidx.compose.runtime.LaunchedEffect(signedInEmail) {
        if (!signedInEmail.isNullOrBlank()) {
            if (pendingPassword.isNotBlank()) {
                lastSignInDataStore.saveCredential(signedInEmail, pendingPassword)
            } else {
                lastSignInDataStore.setLastEmail(signedInEmail)
            }
        }
    }

    // KevBox: adopt upstream's signed-in extraction (StatusCard + sync note + sign-out-with-confirmation,
    // and NO sync-backend/debug cards). The credential-save LaunchedEffect above runs first, so one-tap
    // still persists after a fresh sign-in even though we early-return here for FullAccount.
    if (uiState.authState is AuthState.FullAccount) {
        SignedInAccountSettingsContent(
            uiState = uiState,
            viewModel = viewModel,
            initialFocusRequester = initialFocusRequester
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (val authState = uiState.authState) {
            is AuthState.Loading -> {
                item(key = "account_loading") {
                    Text(
                        text = stringResource(R.string.account_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }

            is AuthState.SignedOut -> {
                item(key = "account_signed_out_info") {
                    Text(
                        text = stringResource(R.string.account_sync_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
                item(key = "account_sync_note_signed_out") {
                    AccountInlineNote(text = stringResource(R.string.account_sync_restart_note))
                }
                // KevBox: keep our email/password sign-in; discard upstream's QR sign-in button (the QR
                // flow's RPCs were removed) AND its "Sync backend" StatusCard + DebugSyncBackendSwitchCard
                // (internal labels, not for family). Account creation happens in the Supabase dashboard.
                item(key = "account_sign_in_email") {
                    EmailPasswordForm(
                        onSubmit = { email, password ->
                            pendingPassword = password
                            viewModel.clearError()
                            viewModel.signIn(email, password)
                        },
                        isLoading = uiState.isLoading,
                        error = uiState.error,
                        prefillEmail = lastEmail,
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
                }
            }

            // KevBox: signed-in is handled by the early return to SignedInAccountSettingsContent above.
            is AuthState.FullAccount -> Unit

        }
    }
}

@Composable
private fun SignedInAccountSettingsContent(
    uiState: AccountUiState,
    viewModel: AccountViewModel,
    initialFocusRequester: FocusRequester?
) {
    val listState = rememberLazyListState()
    var showSignOutConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val authState = uiState.authState as AuthState.FullAccount
            item(key = "account_status") {
                StatusCard(label = stringResource(R.string.account_signed_in_label), value = authState.email)
            }
            item(key = "account_sync_note_signed_in") {
                AccountInlineNote(text = stringResource(R.string.account_sync_restart_note))
            }

            // KevBox: SHOW_SYNC_OVERVIEW=false hides the per-profile sync counts from family members.
            if (SHOW_SYNC_OVERVIEW) {
                val overview = uiState.syncOverview
                if (overview != null) {
                    item(key = "account_sync_overview") { SyncOverviewCard(overview) }
                } else if (uiState.isSyncOverviewLoading) {
                    item(key = "account_sync_overview_loading") { SyncOverviewLoadingCard() }
                }
            }
        }

        SignOutSettingsButton(
            onClick = { showSignOutConfirmation = true },
            modifier = if (initialFocusRequester != null) {
                Modifier.focusRequester(initialFocusRequester)
            } else {
                Modifier
            }
        )
    }

    if (showSignOutConfirmation) {
        AccountSignOutConfirmationDialog(
            onConfirm = {
                viewModel.signOut()
                showSignOutConfirmation = false
            },
            onDismiss = { showSignOutConfirmation = false }
        )
    }
}

@Composable
private fun AccountInlineNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.md)
    )
}

@Composable
private fun SyncOverviewCard(overview: SyncOverview) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioTheme.colors.BackgroundCard,
                shape = RoundedCornerShape(NuvioTheme.radii.sm)
            )
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Totals row — layout matches ProfileSyncRow columns
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = NuvioTheme.colors.BackgroundElevated,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = NuvioTheme.spacing.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.account_total_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Secondary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(100.dp)
                )
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ProfileStatValue(overview.totalAddons, stringResource(R.string.account_stat_addons))
                    ProfileStatValue(overview.totalPlugins, stringResource(R.string.account_stat_plugins))
                    ProfileStatValue(overview.totalLibrary, stringResource(R.string.account_stat_library))
                    ProfileStatValue(overview.totalWatchProgress, stringResource(R.string.account_stat_progress))
                    ProfileStatValue(overview.totalWatchedItems, stringResource(R.string.account_stat_watched))
                }
            }

            // Per-profile breakdown
            if (overview.perProfile.isNotEmpty()) {
                overview.perProfile.forEach { profile ->
                    ProfileSyncRow(profile)
                }
            }
        }
    }
}

@Composable
private fun SyncStatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.Secondary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            fontSize = 9.sp,
            color = NuvioTheme.colors.TextTertiary
        )
    }
}

@Composable
private fun ProfileSyncRow(profile: ProfileSyncStats) {
    val color = runCatching { Color(android.graphics.Color.parseColor(profile.avatarColorHex)) }
        .getOrDefault(Color(0xFF1E88E5))
    val rowShape = RoundedCornerShape(6.dp)
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .background(
                color = if (isFocused) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundElevated,
                shape = rowShape
            )
            .border(
                width = NuvioTheme.spacing.xxs,
                color = if (isFocused) NuvioTheme.colors.FocusRing else Color.Transparent,
                shape = rowShape
            )
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = profile.profileName.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))

        Text(
            text = profile.profileName,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(70.dp)
        )

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ProfileStatValue(profile.addons, stringResource(R.string.account_stat_addons))
            ProfileStatValue(profile.plugins, stringResource(R.string.account_stat_plugins))
            ProfileStatValue(profile.library, stringResource(R.string.account_stat_library))
            ProfileStatValue(profile.watchProgress, stringResource(R.string.account_stat_progress))
            ProfileStatValue(profile.watchedItems, stringResource(R.string.account_stat_watched))
        }
    }
}

@Composable
private fun ProfileStatValue(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            fontSize = 12.sp,
            color = if (count > 0) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = label,
            fontSize = 8.sp,
            color = NuvioTheme.colors.TextTertiary
        )
    }
}

@Composable
private fun SyncOverviewLoadingCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioTheme.colors.BackgroundCard,
                shape = RoundedCornerShape(NuvioTheme.radii.sm)
            )
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.account_loading_sync),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun SettingsActionButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(NuvioTheme.radii.sm)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (isFocused) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun StatusCard(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioTheme.colors.Secondary.copy(alpha = 0.1f),
                shape = RoundedCornerShape(NuvioTheme.radii.sm)
            )
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(NuvioTheme.spacing.lg),
            tint = NuvioTheme.colors.Secondary
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
        Text(
            text = "$label  ",
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextTertiary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SignOutSettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = Color(0xFFC62828).copy(alpha = 0.12f),
            focusedContainerColor = Color(0xFFC62828).copy(alpha = 0.25f)
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, Color(0xFFF44336).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(NuvioTheme.radii.sm)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color(0xFFF44336)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.account_sign_out),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFF44336),
                fontWeight = FontWeight.Medium
            )
        }
    }
}
