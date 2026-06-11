@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.updater.UpdateViewModel

@Composable
fun AboutScreen(
    onNavigateToSupportersContributors: () -> Unit = {},
    onNavigateToLicensesAttributions: () -> Unit = {},
    onBackPress: () -> Unit = {}
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.about_title),
        subtitle = stringResource(R.string.about_subtitle)
    ) {
        AboutSettingsContent(
            onNavigateToSupportersContributors = onNavigateToSupportersContributors,
            onNavigateToLicensesAttributions = onNavigateToLicensesAttributions
        )
    }
}

@Composable
fun AboutSettingsContent(
    onNavigateToSupportersContributors: () -> Unit = {},
    onNavigateToLicensesAttributions: () -> Unit = {},
    initialFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.about_title),
            subtitle = stringResource(R.string.about_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            title = null
        ) {
            val aboutScrollState = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(aboutScrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))

                Image(
                    painter = painterResource(id = R.drawable.app_logo_wordmark),
                    contentDescription = stringResource(R.string.cd_nuvio_logo),
                    modifier = Modifier
                        .width(180.dp)
                        .height(40.dp),
                    contentScale = ContentScale.Fit
                )

                Text(
                    text = stringResource(R.string.about_made_with_love),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                // KevBox §11 telemetry notice — durations-only activity/diagnostics. Shown ONLY on
                // builds that actually report (full flavor: FEATURE_TELEMETRY=true); never on playstore.
                if (BuildConfig.FEATURE_TELEMETRY) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_telemetry_notice_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.about_telemetry_notice),
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))

                // Gate the manual check the SAME way as the auto-check + dialog host in MainActivity
                // (`!IS_DEBUG_BUILD`): the UpdatePromptDialog is only composed in non-debug builds, so
                // showing this row in a debug build let it run but render nothing. Hide it in debug.
                if (AppFeaturePolicy.inAppUpdatesEnabled && !BuildConfig.IS_DEBUG_BUILD) {
                    val updateViewModel: UpdateViewModel = hiltViewModel(context as ComponentActivity)
                    SettingsActionRow(
                        title = stringResource(R.string.about_check_updates),
                        subtitle = stringResource(R.string.about_check_updates_subtitle),
                        trailingIcon = Icons.Default.OpenInNew,
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        },
                        onClick = {
                            updateViewModel.checkForUpdates(force = true, showNoUpdateFeedback = true)
                        }
                    )
                }

                // KevBox: upstream "Privacy Policy" row pointed to Nuvio's own policy
                // (tapframe.github.io/NuvioStreaming) — wrong target for the family build. Hidden
                // (hide, don't delete). KevBox's durations-only notice is the block above, gated on
                // FEATURE_TELEMETRY. The initial-focus fallback (when in-app updates are off, e.g.
                // playstore) now lives on the Licenses row below.
                // SettingsActionRow(
                //     title = stringResource(R.string.about_privacy_policy),
                //     subtitle = stringResource(R.string.about_privacy_policy_subtitle),
                //     trailingIcon = Icons.Default.OpenInNew,
                //     onClick = { /* opened Nuvio's privacy policy URL */ }
                // )

                // KevBox: upstream "Supporters & Contributors" (donations / Ko-fi / sponsors)
                // hidden for the private family build. Screen + route left intact (hide, don't delete).
                // SettingsActionRow(
                //     title = stringResource(R.string.about_supporters_contributors),
                //     subtitle = stringResource(R.string.about_supporters_contributors_subtitle),
                //     trailingIcon = Icons.Default.ChevronRight,
                //     onClick = onNavigateToSupportersContributors
                // )

                // Temporarily hidden while validating the About screen layout.
                // The licenses screen and navigation route remain intact.
            }
            SettingsVerticalScrollIndicators(state = aboutScrollState)
            }
        }
    }
}
