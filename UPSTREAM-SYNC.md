# Syncing KevBox TV with upstream NuvioTV

KevBox TV is a private fork of **[NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV)**
(the old `tapframe/NuvioTV` URL redirects here — same repo, renamed). All KevBox changes live on the
**`kevbox`** branch. To get the latest NuvioTV improvements (player fixes, scrapers, etc.) we **merge
upstream into `kevbox`** — we never rebase, and we never push to upstream.

**Why this stays low-effort:** the rebrand is done with `full`-flavor resource overrides
(`app/src/full/res/…`), the Kotlin package namespace is unchanged (`com.nuvio.tv`), and almost all new
logic lives in new files — so upstream's commits rarely touch the same lines we changed. A quiet cycle
is a ~5-minute merge with just an `app/build.gradle.kts` conflict.

**But some cycles are heavier** — when upstream reworks an area we also touched (e.g. the 0.7.5-beta
sync hit the player overhaul × our telemetry hooks, plus a repo-wide "design token" theming refactor;
the 0.7.9-beta sync added a remote "sync backend switch" that deleted `SupabaseModule` and rewired
every Supabase consumer — see the two 0.7.9 callouts below; the 0.7.12-beta sync grew that surface again
with a dev-only `DebugSyncBackendSwitchCard` and a new opt-in playback-reporting endpoint — see the 0.7.12 callouts;
the **0.7.16-beta** sync (116 commits, the heaviest yet) **reverted the whole 0.7.9 dbswitch** — it
re-added `SupabaseModule` and *deleted* `SyncBackendSupabaseProvider`, so the 0.7.9 fix had to be undone
per-file; it also rebound `SUPABASE_URL` to the `NUVIO_*` props, added a Sentry crash-reporting surface,
and flipped `allowBackup` — see the 0.7.16 callouts). The **0.8.1-beta sync** (324 commits over three
releases: 0.7.20 + 0.8.0 + 0.8.1) reworked the one package we own end-to-end — the **full-flavor
updater** (upstream added a GitHub-release "update banner" that would phone home to *their* repo) —
rewrote **library sync** into a delta/event model (**server migration REQUIRED**, same class as the
0.7.16 RPC break), **deleted the whole Supabase realtime sync** (dependency included), added a Simkl
tracking provider, and renamed `SettingsCategory.TRAKT`→`TRACKING` (silently breaks our suppression
line). See the 0.8.1 callouts. The **0.8.11 sync** (304 commits) added a whole new failure class:
upstream moved the launcher entry onto six activity-aliases and shipped an **icon picker** that hands
family members five Nuvio-branded logos we don't override. Nothing was unlocked and nothing phoned
home; the app just stops looking like KevBox. See the 0.8.11 callouts.
Expect **30–45 min** then, with conflicts in the player files, `Theme.kt`, `AboutScreen.kt`,
`AuthSignInScreen.kt`, the `Account*` screens, `AndroidManifest.xml`, and `WatchedItemsSyncService.kt`.
See the expanded table below. The merge markers are
the easy part — **the real gate is the compile check**, because upstream refactors can break our code
with *no* conflict at all (see the "invisible breakage" callouts), and a remote-config change can hand
control of our fleet to upstream with no code change at all (see the "remote control plane" callout).
And note that `compileFullDebugKotlin` is **not enough on its own** — it configures only the *debug*
buildType, so a KevBox helper the merge drops from the *release* buildType (0.7.16 dropped
`resolveLocalProperty`) sails through the debug compile and only blows up inside `release.sh`. On a heavy
cycle, also configure the release build (or grep that our helpers survive) before you ship — see the
"release-only breakage" callout.

## One-time setup (already done)

```bash
git remote add upstream https://github.com/NuvioMedia/NuvioTV.git
```

Upstream's active branch is **`dev`** (that's where NuvioTV development lands).

## Recurring update flow

Run this whenever you want the latest NuvioTV with your KevBox changes on top:

```bash
# 1. Get the latest upstream code
git fetch upstream

# 2. Merge it into your kevbox branch
git switch kevbox
git merge upstream/dev

# 3. If there are conflicts, resolve them (see table below), then finish the merge:
#    git add <file> ...
#    git commit            # completes the merge

# 4. Publish a new release (bumps versionCode +1, builds signed armeabi-v7a, uploads, writes version.json,
#    commits, and pushes the CURRENT branch to your fork — so be ON kevbox when you run it, having
#    fast-forwarded it to your isolation branch first: `git switch kevbox && git merge --ff-only sync0.9.2`).
#    KevBox runs its OWN version line: since 0.7.16 we mirror upstream's patch number into ours and sit
#    one minor ahead (upstream 0.8.11-beta → KevBox 0.9.11-beta, upstream 0.9.2-beta → KevBox 0.10.2-beta,
#    upstream 1.0.0 → KevBox 1.1.0; the -beta suffix went when upstream dropped theirs)
#    so the base is legible at a glance. The updater compares versionCode only, so the name is cosmetic,
#    but keep it monotonic in semver terms (1.1.0 > 0.10.2 > 0.9.11). Bump YOUR name:
./release.sh 1.1.0 "Bug fixes and improvements"
```

> ⚠️ **Let `release.sh` do the push — don't use VS Code's "Sync Changes" button.** `kevbox` carries
> upstream's full history, so a `git pull --rebase` (which is what VS Code Sync runs) tries to **flatten
> your merge commit into dozens of cherry-picks** and dumps you into a conflict-ridden rebase. If that
> happens: `git rebase --abort` restores your merge commit untouched (it's still the branch tip + in the
> reflog). Push only with `git push origin kevbox` or `release.sh`.

That's it. The in-app updater on each TV will then see the new `versionCode` at
`https://tv.kevbox.dev/version.json` and offer the update.

## What conflicts to expect — and how to resolve them

General rule: **keep the KevBox identity / branding / auth / updater / telemetry bits; take upstream's
feature and bug-fix code.** When both sides simply *added* adjacent lines (constructor params, init
blocks, reset lines), the answer is almost always **keep both**.

| File | Keep the KevBox side | Take upstream's side |
|---|---|---|
| `app/build.gradle.kts` | **Resolve ONLY the conflict hunk — never `git checkout --ours` this file.** The 0.9.2 cycle did that (the hunk was just the version line) and silently threw away upstream's non-conflicting hunks in the same file (the haze 0.7.3→1.7.2 bump), so the compile failed on `hazeEffect`/`HazeInputScale` with no marker anywhere. After resolving, `git diff upstream/dev -- app/build.gradle.kts` must show only the KevBox divergences listed here. Keep: `applicationId = "tv.kevbox"`, our `versionCode`/`versionName`, `UPDATE_BASE_URL`, `isUniversalApk = false`, debug id `tv.kevbox.debug`; **keep `SYNC_BACKEND_MANIFEST_URL` blank (`""`)** and **leave `NUVIO_SUPABASE_*` blank** (see "remote control plane" callout). **0.7.16 trap:** upstream **rebound `SUPABASE_URL`/`SUPABASE_ANON_KEY` to read the `NUVIO_SUPABASE_*` props** — do NOT take that; keep them reading our `SUPABASE_*` props (our family project). Also **keep the `resolveLocalProperty(...)` helper defined** — the merge dropped it (see "release-only breakage") | new dependencies, SDK/AGP bumps, native/player changes, AND new `buildConfigField`s that our code references — 0.7.16 needs `SENTRY_ENVIRONMENT` and `SUPABASE_FALLBACK_URL` **added to debug+release** (blank-sourced) or `SentryInitializer`/`AuthManager` won't compile |
| `MainActivity.kt` (`onResume`/`onStart`) | our `FEATURE_ACCESS_CONTROL`/`FEATURE_DEVICE_LIMIT` catch-up blocks | take upstream's `requestForegroundSync()` — call it **exactly once**. (0.7.9–0.7.12 wrapped it in a coroutine alongside `syncBackendSwitchService.refreshSelection()`; **0.7.16 removed refreshSelection entirely** — `syncBackendSwitchService` no longer exists in MainActivity — so it's now a bare single call. Either way: one `requestForegroundSync()`, never two) |
| `AccountScreen.kt` / `AccountSettingsContent.kt` | our `EmailPasswordForm` sign-in + `SHOW_SYNC_CODE_FEATURES` / `SHOW_SYNC_OVERVIEW = false` gating + the credential one-tap `LaunchedEffect` (keep it **above** any early-return so it still runs when auth flips to `FullAccount`) | take upstream's other additions, but **drop the read-only "Sync backend" `StatusCard`/`AccountInfoCard` AND the new `DebugSyncBackendSwitchCard`** (the 0.7.12 dev-only "local db switch" — never surface it to family). **0.7.16:** upstream extracted a clean `SignedInAccountSettingsContent` (StatusCard + sync note + sign-out **with a confirmation dialog**, no backend cards) and added an `initialFocusRequester` param the `SettingsScreen.kt` caller now passes — **adopt both** (the signature MUST accept `initialFocusRequester` or `SettingsScreen` won't compile), just gate its sync-overview behind `if (SHOW_SYNC_OVERVIEW)`. Note: `DebugSyncBackendSwitchCard`/`syncBackendName` copies auto-merge in with **no conflict** — grep and delete stragglers (leave the ones in the dead `AuthQrSignInScreen.kt`) |
| `MainActivity.kt` | the `AuthEmailOnboardingScreen` first-run gate, **and the access/device-limit lock gate** (the `// KevBox FORK DIVERGENCE` block: LockedOutScreen early-return + `LockRetryStatus` retry-feedback wiring, 2026-08-11). Watch the import block — `com.nuvio.tv.core.access.*` / `ui.screens.account.Lock*` are kevbox-only imports an auto-merge can silently drop | everything else |
| `AddonPreferences.kt` | KevBox `getDefaultAddons()` list + `seedDefaultAddonsOrderIfFirstLaunch()` | other additions |
| `NuvioApplication.kt` | the addon-seed `launch{}` block | other startup changes |
| `app/src/full/java/.../updater/**` | the whole KevBox updater (version.json / SHA-256 / speed+ETA) — **0.8.1: upstream reworked THIS package (update banner) — see the "upstream's own updater" callout; it's now a guaranteed conflict zone every cycle** | nothing from upstream's updater |
| `app/src/full/res/**` and new files (`EmailPasswordForm`, `CredentialCrypto`, `LastSignInDataStore`, `DefaultContent`, `Checksum`, `release.sh`) | yours — upstream has none of these | n/a |
| `Theme.kt` | our default `LocalAppTheme = AppTheme.OCEAN` (NOT upstream's `WHITE`) | upstream's new lines, e.g. `LocalNuvioTextStyles` and design-token additions |
| `PlayerRuntimeController.kt`, `PlayerViewModel.kt`, `PlayerRuntimeControllerInitialization.kt`, `...Lifecycle.kt`, `...Mpv.kt` | **keep BOTH** — our `telemetryRepository`/`deviceGuardDataStore` injection + telemetry `launch{}`/`telemetrySessionStarted` reset + the `heartbeatScheduler.stop()` calls (in `onPlayerError`, `STATE_ENDED`, and `releasePlayer`). **RETIRED (0.9.2):** the old `STATE_ENDED` scrobble exception is gone — upstream itself removed the `emitCompletionScrobbleStop(99.5f)` call there (completion fires once from `PlayerRuntimeControllerPlaybackEvents.kt`), so our commented-out copy was dropped. If a future sync re-adds an active call in `STATE_ENDED`, that is the double-scrobble again: refuse it | **keep BOTH** — upstream's `streamBadgePresentation`, trakt-CW `launch{}`, `hasMarkedCurrentEpisodeCompleted` reset; **0.7.16:** in `onPlayerError` adopt `error.toDisplayMessage(context)` (replaces our old `buildString` block) but keep our telemetry block above it; in `...Mpv.kt`/`...Lifecycle.kt` these are pure keep-both import/line adds |
| `AboutScreen.kt` | our `if (BuildConfig.FEATURE_TELEMETRY)` §11 privacy-notice block | upstream's added imports + tokenized spacer (`NuvioTheme.spacing.xxs`) |
| `AuthSignInScreen.kt` | our `EmailPasswordForm(...)` sign-in body + `AuthEmailOnboardingScreen` + the `androidx.compose.runtime.*` and **`import androidx.hilt.navigation.compose.hiltViewModel`** imports — **discard** upstream's QR `Button`/`Text` header (we replaced that flow) | nothing here — but **0.7.16 trap:** the import-block auto-merge takes upstream's version (which doesn't use `hiltViewModel`) and **silently drops that import** while our body calls `hiltViewModel()` twice → compile break, no marker (see "release-only breakage" — same class as the `NuvioColors` one). Re-add the import; also drop the now-unused `Button`/`ButtonDefaults` imports |
| `NuvioNavHost.kt` (Settings block) | route the dormant account entry to `Screen.AuthSignIn` (QR retired); **force `onNavigateToAddons`/`onNavigateToPlugins` to no-op `{}`** (see policy-regression callout) | **keep both** — take upstream's new `onNavigateToPlugins` param and any other added route callbacks |
| `SettingsScreen.kt` (**rewritten for 1.1.0-beta.4**, read this first) | upstream moved category visibility into `SettingsCatalog.visibleSettingsCategories()` (`else -> true`), so our old `visibleSections` filter is gone. Keep **`KEVBOX_HIDDEN_SETTINGS_CATEGORIES`** (PROFILES, CONTENT_DISCOVERY, TRACKING) and the `.filterNot { it in KEVBOX_HIDDEN_SETTINGS_CATEGORIES }` on the result inside the `visibleCategories` `remember`. Filter `visibleCategories`, not `visibleSections`: the rail places its group dividers with `visibleCategories.startsNewGroup(index)`. Never edit `SettingsCatalog.kt` itself; upstream's `SettingsCatalogTest` pins it. Taking upstream's side of this hunk alone compiles and shows Addons, Plugins, Tracking and Profiles again | take upstream's restructure. The next row describes the pre-beta.4 shape and is kept for history |
| `SettingsScreen.kt` (before 1.1.0-beta.4) | **hide the whole `CONTENT_DISCOVERY` category** (`SettingsCategory.CONTENT_DISCOVERY -> false` in the `visibleSections` filter) — it holds only the operator-forbidden Addons + Plugins rows. 0.7.16 rewrote this file heavily (+551 lines) but the one-line suppression survived — **re-confirm it after every sync**. **0.8.1:** upstream renamed the enum `TRAKT`→`TRACKING`; our kevbox-added `SettingsCategory.TRAKT -> false` line merges cleanly but **fails to compile** — rename it to `TRACKING -> false` (this also hides the new combined Trakt/Simkl `TrackingSettingsScreen`, which is what policy wants) | n/a — KevBox never edits this file except to suppress those categories |
| `AndroidManifest.xml` (0.7.16) | **keep `allowBackup="true"` + `dataExtractionRules="@xml/data_extraction_rules"` + `fullBackupContent="@xml/full_backup_content"`** — these are KevBox-authored rules that **exclude the access-kill-switch DataStores** (`access_control`/`device_guard`) from cloud backup + device transfer, so a locked-out member can't clone a "last-verified" grace state or duplicate a device id. They only work with backup ON | **discard** upstream's `allowBackup="false"` (it would orphan our exclusion rules) |
| `WatchedItemsSyncService.kt` (0.7.16) | our **rev-4 Option B union** — the first cloud-restore snapshot for a never-synced profile must `replaceWithRemoteItems(..., unionWhenNeverSynced = true)` so it doesn't wipe a family member's existing watch history. Upstream extracted a shared `pullSnapshotFromRemote(...)` helper — **put the `unionWhenNeverSynced = true` INSIDE that helper** so all restore paths inherit it (the flag is a no-op for already-synced profiles, so it's safe universally) | take upstream's delta-cursor resilience refactor (the `try { fetchDeltaCursor } catch { snapshot fallback }`) |
| `StreamScreen.kt` (external-stream tap) | **replace upstream's `openExternalInBrowser(playbackInfo)` with our `consumeExternalStreamClick(playbackInfo)` = `return playbackInfo.isExternal`** — launch NO intent. External-URL entries in the stream list are AIOStreams info cards ("Removal Reasons"/"Statistics", externalUrl set + url == null); upstream's `Intent.ACTION_VIEW`/`CATEGORY_BROWSABLE` gets hijacked by the sideloaded Downloader app and traps the user (force-close required). Same contract (true == consumed), so the two callers (`routePlayback`/`routeAutoPlay`) only need the rename. **Also re-remove the imports it drags back:** `android.content.Intent`, `android.net.Uri`, `com.nuvio.tv.core.player.ExternalPlayerLauncher`. Full rationale is in the `// KevBox FORK DIVERGENCE` block on the function | take upstream's other stream-list changes; this is the only line that matters |
| `MainActivity.kt` (deeplinks, 0.7.18) | in **both** deeplink `LaunchedEffect`s, **neutralize the `AppDeepLink.AddonInstall` branch** — no `deepLinkHandler.installAddon(...)`, no `navController.navigate(Screen.AddonManager.route)`; just `pendingDeepLinkUrl.value = null`. This is a **policy regression** guard: a `stremio://…/manifest`/`nuvio://…addon` link (browser/QR/other app) would otherwise install an arbitrary addon AND drop the user into the (suppressed) Addon Manager — addons are operator-managed (`member_addon`). `deepLinkHandler` stays `@Inject`ed (unused) purely to keep the branch mergeable. See the `// KevBox FORK DIVERGENCE` blocks | **keep** the `AppDeepLink.Meta` branch (opens a Detail screen — harmless, lets a `tv.kevbox.dev` title link deep-link in) and everything else upstream added (card-depth, `pendingDeepLinkUrl`, `onNewIntent`) |
| `StreamRepositoryImpl.kt` (no-stream-source wording, 2026-08-30) | **keep the `authManager` constructor param and the `when (authManager.authState.value)` block** in `buildAggregateFailureMessage`'s `attemptedAddonNames.isEmpty()` branch. Upstream returns the single `error_stream_no_supported_addon` string there; taking theirs re-hides the most common member-facing failure behind addon jargon (see the "member can't tell they're signed out" callout). Also keep the two kevbox-only strings `error_stream_signed_out` / `error_stream_no_source_configured` in `values/strings.xml` — a strings-file merge can drop them **independently** of the Kotlin change, which compiles fine and only breaks at runtime | take upstream's other changes to this file — the addon fan-out, plugin/debrid merging and `buildAddonFailure` wording are all upstream's and we track them |
| `AddonRepositoryImpl.kt` (2026-08-30) | **keep the `@Singleton` on the class** and **keep `awaitResolvedInstalledAddons()` + its `hasUnresolvedEnabledAddon()` helper**. Upstream has neither. Losing the annotation re-creates the duplicate-instance manifest storm that gets members HTTP 429'd off their own addon host (see the callout); losing the method silently shortens every cold-cache stream search. Both are marked with `KevBox FORK DIVERGENCE` blocks and guarded by `AddonRepositorySingletonScopeTest` / `AddonRepositoryResolvedAddonsTest` | take upstream's other changes to the manifest cache, fetch and flow |
| `AddonRepository.kt` (interface, 2026-08-30) | **keep the `awaitResolvedInstalledAddons(timeoutMs)` declaration and its default body.** The default (`getInstalledAddons().first()`) exists so upstream/test fakes keep compiling — do not "simplify" it away, and do not make it abstract | take upstream's new interface members |
| `MemberConfigService.kt` (kevbox-only file, 2026-08-30) | **`start()` must feed EVERY auth state to `MemberConfigApplyGate`.** Do not reintroduce `filterIsInstance<AuthState.FullAccount>()` + `distinctUntilChangedBy { it.userId }` — the gate has to see `SignedOut` to know the addon store was wiped, otherwise the same member signing back in looks like a duplicate and never gets their addons back until the app restarts. Guarded by `MemberConfigApplyGateTest` | n/a — upstream has neither this file nor `MemberConfigApplyGate.kt` |
| `AndroidManifest.xml` (deeplinks, 0.7.18) | **drop the `<data android:scheme="stremio" />` intent-filter** — that scheme resolves ONLY to addon-install deeplinks (`DeepLinkParser`), so registering it makes the TV advertise as a Stremio-addon handler we then refuse. A `// KevBox FORK DIVERGENCE` comment marks where it was removed | **keep** the `nuvio://` filter (serves harmless Meta/title deeplinks) and `launchMode="singleTop"` |
| `PlaybackAvailability.kt` + `ui/components/PlaybackAvailabilityProvider.kt` (0.9.2) | **keep the `allowUnverifiedPlayback` field + its early return in `canStream()`, and the `.copy(allowUnverifiedPlayback = true)` in the provider composable.** Upstream's new Play gate reads the live addon list; an addon whose manifest has not resolved carries no resources, so on the first Play after a sign-in it toasts "Playback isn't available… with your current setup" and hides the member's own source (the 2026-08-30 symptom, back through a new door), and a signed-out member gets that toast instead of the "sign in" wording from `StreamRepositoryImpl`. The flag lives on the data class but is set only in the provider, so upstream's `PlaybackAvailabilityTest` / `PlaybackAvailabilityViewModelTest` keep their expectations untouched | take everything else (the gate's call sites in `NuvioNavHost`, `MetaDetailsScreen`, `ContinueWatchingSection` are fine — they become no-ops) |
| `app/src/main/java/com/nuvio/tv/updater/**` + `ui/screens/settings/UpdateChannelSettings.kt` (0.9.2) | **delete on merge**: upstream moved `VersionUtils`/`ReleaseSelector`/`UpdateChannel` into the SHARED source set (plus `ReleaseSelectorTest`/`VersionUtilsTest`) and added an `UpdateChannelSettings` composable that calls upstream's `UpdateViewModel` API (`updateChannel`, `setUpdateBannerEnabled`, `dismissBanner`). Against our `UpdateViewModel` that file does not compile, and the three shared files are dead code for KevBox. Post-sync: `ls app/src/main/java/com/nuvio/tv/updater/` must not exist and `grep -rn UpdateChannelSettings app/src --include=*.kt` must hit comments only | nothing — the `update_channel_*` / `about_update_channel_*` strings may stay (translations reference them; unused strings are harmless) |
| `AboutScreen.kt` (0.9.2) | keep our rows (telemetry notice, single "Check for updates" row on our `UpdateViewModel` via `hiltViewModel(context as ComponentActivity)`, hidden privacy + licenses rows) **inside upstream's new `BringIntoViewSpec` scroll wrapper**, and give our first row `firstRowModifier` so upstream's scroll-to-top fix still works. **Re-add the imports the auto-merge drops** (`androidx.activity.ComponentActivity`, `com.nuvio.tv.updater.UpdateViewModel`, keep `hiltViewModel`) | the wrapper + `MemberBrandWordmark` (draws our wordmark; the gradient is supporter-only) — but NOT the `UpdateChannelSettings(...)` call |
| `WatchedItemsPreferences.kt` / `WatchedItemsSyncService.kt` (0.9.2) | upstream replaced the last-push timestamp heuristic with a **pending-mutation store** and its `pullSnapshotFromRemote` no longer passes `lastSuccessfulPushMs` (null → only queued local changes survive a snapshot). The store is new and nothing seeds pre-existing local marks into it, so a never-synced profile would lose them on the first restore. **Keep the KevBox `unionWhenNeverSynced: Boolean = false` parameter on `replaceWithRemoteItems` + its `preserveNeverSynced` clause, and pass `unionWhenNeverSynced = watchedItemsPreferences.getLastSuccessfulPushMs(profileId) <= 0L` from `pullSnapshotFromRemote`.** Default false keeps upstream's `WatchedItemsPreferencesSyncTest` ("empty snapshot clears non-pending") and `WatchedItemsPullPreservationTest` on upstream semantics; `SyncMergeLogicTest` still pins the pure helper in `SyncMergeLogic.kt` | take the pending-upsert/delete logic, the delta-cursor fallbacks and `WatchStateMutationStore` wholesale |
| `AddonRepositoryImpl.kt` (0.9.2) | keep `awaitResolvedInstalledAddons` + `hasUnresolvedEnabledAddon` (unchanged). **Upstream converged on our `@Singleton`** (cf7ee078d, same fix, same day) so the annotation now comes from both sides — take upstream's primary constructor with the injectable `dispatcher`/`clock` and the `@Inject` secondary (our tests use the 5-arg form and keep compiling). Upstream also added placeholder addons on fetch failure; they carry no resources, which is exactly why the Play gate above had to be neutralised | everything else |
| `app/src/full/res/drawable/app_logo_wordmark_{gold,jade,rose_gold,arctic_blue,graphite}.xml` (0.9.2) | ours — five `<bitmap>` aliases to `@drawable/app_logo_wordmark`. `ThemeBranding.kt` maps the premium themes to Nuvio-branded wordmark PNGs in `main/res`, and both the profile screen and the 0.9.1 startup splash draw that resource directly. Without the aliases a member on the Gold theme sees a Nuvio play-triangle logo on every launch. Keep the folder; it never conflicts | n/a |
| `ThemeAccessTest.kt` / `ThemeSettingsViewModel.kt` (0.9.2) | our OCEAN fallback now has a THIRD assertion to flip: upstream's new `customThemesAreAvailableWithoutMembership` asserts `resolveAppTheme(null, None) == WHITE` → make it OCEAN. In `ThemeSettingsViewModel` keep `selectedTheme = AppTheme.OCEAN` and take upstream's new `customThemeColors` / `customThemeGradientEnabled` fields beside it | the custom-theme editor (`AppTheme.CUSTOM`) is free for everyone and maps to our default wordmark — nothing to hide |
| `MainActivity.kt` (0.9.2 splash + profile switch) | as before (email onboarding, access gate, `UpdatePromptDialog`, no `UpdateBannerHost`), but the tail of `onCreate` is now upstream's shape: take `startupDestination = StartupDestination.Setup` before our `AuthEmailOnboardingScreen(`, the `} else {` that replaced the essential-addon-setup `return@Surface`, upstream's `handleSwitchProfile` (resets the splash state; both scaffolds take it), the `Box { scaffolds + autoNextOverlay }`, and the `StartupSplashScreen` block after the scaffolds. Our `UpdatePromptDialog` block goes where the `UpdateBannerHost` lambda used to close. The auto-merge left BOTH copies of the scaffolds in the file (ours bare + upstream's inside the banner host) — rebuild that region from upstream's text rather than patching the interleaving; the brace balance must match upstream's file | everything else |
| `AddonRepositoryResolvedAddonsTest.kt` (0.9.2, corrected 1.0.0) | our test. The 30 s / 60 s budgets stay, but the 0.9.2 note blaming "IO starvation" was wrong: the flake was the race described in the 1.0.0 callout (the method answered `[]` in 3 ms, it never waited). The third test pins that race with a shared `StandardTestDispatcher` and a pre-filled disk cache; keep it, it is the only deterministic guard | n/a |
| `AddonRepositoryImpl.kt` `awaitResolvedInstalledAddons` (1.0.0) | the predicate judges the **published** list (`installedAddonsFlow.first { published -> !hasUnresolvedEnabledAddon(published) }`): an enabled URL counts as resolved only when it appears with a real manifest, where a placeholder is `version.isEmpty() && resources.isEmpty()`. Do not "simplify" it back to a `manifestCache` check; the cache fills before the flow publishes, and a check in that gap returns the empty initial list | n/a, upstream has no such method |
| `ui/navigation/DetailChildHost.kt` (1.1.0-beta.2) | the nested-detail `MetaDetailsScreen`'s `onPlayClick` is a lambda that calls `navigateToDetailStream(..., manualSelection = true)`. Upstream passes the bare reference `parentNavController::navigateToDetailStream`, whose default is `manualSelection = false`, so a title opened from cast / similar / studio rows auto-plays past our forced stream picker. No conflict marks it: the file is new, and our six `manualSelection = true` lines in `NuvioNavHost` all survive | everything else in the host (child back stack, nested-depth cap, focus restore) |
| `LibraryScreen.kt` header (1.1.0-beta.2) | our `app_logo_wordmark` `Image`. Upstream's side of the hunk is the source-label `Text` with a new `MDBLIST` line, and it prints `"NUVIO"` for a signed-in account | n/a for this hunk |
| `app/src/full/.../updater/ui/UpdateBanner.kt` (1.1.0-beta.2) | stays **deleted** (modify/delete conflict: upstream touched one text style). `git rm` it | nothing |
| `NuvioNavHost.kt` settings block (1.1.0-beta.4) | `onNavigateToTracking = { }` and `onNavigateToManageProfiles = { }`, beside the Addons/Plugins no-ops. Second layer behind `KEVBOX_HIDDEN_SETTINGS_CATEGORIES`; upstream wires both to live routes | other callbacks |
| `PlaybackSettingsSections.kt`, `EssentialPlaybackSettingsContent.kt`, `core/torrent/TorrentService.kt` (1.1.0-beta.4) | **no P2P on family TVs.** Keep `.filterNot { it == PlaybackSection.P2P }` on `visiblePlaybackSections(...)` (filter at the caller; upstream's `SettingsStructureTest` pins the function), `SHOW_ESSENTIAL_P2P_TOGGLE = false`, and the `check(settings.p2pEnabled)` at the top of `ensureEngine()` with its `kevbox_p2p_disabled` string. Upstream's new "Clear torrent cache" row starts the Nuvio Engine (DHT, UPnP, LAN broadcast) even with P2P off, and in-player source/episode switches never check consent. `ensureEngine()` is the only place `NuvioEngine.create()` runs, so one check covers every caller | the engine, its settings model and its tests |
| `PlayerRuntimeControllerStreams.kt` `openExternalStreamInBrowser` (1.1.0-beta.4, gap predates it) | no `Intent`/`startActivity`: close the panel and `return true`. Same Downloader hijack as the `StreamScreen.kt` row, reached from the player's Sources and Episodes panels. Drop `import android.content.Intent`, keep `android.net.Uri` (used further down) | the rest of the file |
| "Start from beginning" + TV home-screen launches (1.1.0-beta.4, gap predates it) | `manualSelection = true` next to `startFromBeginning = true` in `NuvioNavHost` (Continue Watching and Detail) and `DetailChildHost`, and in **both** `MainActivity` Stream launches (channel row `launchMode == "stream"` and the `pendingLaunch` Watch Next intent). `StreamScreenViewModel` treats the two flags separately, so without it a member who turns on "Auto-play first source" or "Reuse last link" skips the picker. The external-player `autoPlayNext` route in `MainActivity` stays as is: that is binge | n/a |
| `core/sync/StartupSyncService.kt` (2026-10-06, startup lag) | the two `KevBox FORK DIVERGENCE` markers: (1) in `pullRemoteData`, `canUseWarmSync = canUseKevboxWarmStartupSync(..., ttlMs = KEVBOX_FULL_STARTUP_PULL_TTL_MS)` (24 h, upstream 6 h) instead of upstream's expression, which also required the in-memory `lastPulledKey`/`lastPulledAtMs`; those reset on every process death, so each cold start re-downloaded the member's whole watched/progress history (13 s of CPU for a 3,000-item history on a TCL TV); (2) **no `startupSyncPreferences.markFullPull(...)` at the end of `pullWarmRemoteData`**, so warm syncs don't push the daily full pull back forever. Logic and merge notes live in the kevbox-only `KevboxWarmStartupSync.kt`, pinned by `KevboxWarmStartupSyncTest`. Post-merge check: `grep -n 'canUseKevboxWarmStartupSync\|markFullPull' app/src/main/java/com/nuvio/tv/core/sync/StartupSyncService.kt` must show the call in `pullRemoteData` and exactly one `markFullPull` (the full path) | take upstream's other startup-sync changes. If upstream itself drops the in-memory check, keep marker 1 anyway for the KevBox 24 h window, and keep marker 2 only if they still stamp from the warm path |
| `data/local/WatchedItemsPreferences.kt` `observeAllItems` + `getAllItems` (2026-10-06, startup lag) | the two `KevBox FORK DIVERGENCE` blocks: the `kevboxParsedItems` cache plus `.map { raw set }.distinctUntilChanged().map { kevboxParsedItems.parseAll(it) }` in `observeAllItems`, `kevboxParsedItems.parseAll(...)` as the body of `getAllItems` (the delta sync calls it twice per run only to log a count), and the `distinctUntilChanged` import. Upstream re-parses every JSON string on every store emission, per reader, which was the top app-level hotspot in simpleperf. Logic in the kevbox-only `KevboxParsedJsonCache.kt`, pinned by `KevboxParsedJsonCacheTest` and `KevboxWatchedItemsParseCacheTest`. A merge that takes upstream's body compiles and passes every upstream test, so only that test catches the regression | take upstream's other changes. If upstream moves watched items off the JSON string set (Room, proto), drop the cache and the test instead of porting them |

After resolving, `git add` the files and `git commit` to complete the merge.

### ⚠️ Invisible breakage — upstream refactors that DON'T show as conflicts

The 0.7.5-beta "design token" refactor **removed `import com.nuvio.tv.ui.theme.NuvioColors`** from
`MainActivity.kt`, `AuthSignInScreen.kt`, and `AboutScreen.kt` (it migrated those files to
`NuvioTheme.colors`). Git auto-merged the import *removal* silently, but our kept-KevBox code still
uses the static `NuvioColors` palette — so the build failed with `Unresolved reference 'NuvioColors'`
and **zero conflict markers**. The `NuvioColors` object still exists, so the fix is just to **re-add
the import** to each affected file.

Lesson: after resolving markers, a clean `git status` does **not** mean you're done. Always run the
compile check below — it's the only thing that catches this class of breakage.

**0.7.16 gave two more of exactly this class** (auto-merge takes upstream's version of a shared region
and drops something our kept code still needs, zero markers): (1) `AuthSignInScreen.kt` lost
`import androidx.hilt.navigation.compose.hiltViewModel` while our body still calls `hiltViewModel()`
twice; (2) `NuvioApplication.kt` needed BOTH its old `AddonPreferences` import and upstream's new
`SentrySettingsDataStore` import. Same fix: re-add the import. Same detector: the compile check.

### 🛑 Release-only breakage — a break the *debug* compile can't see (0.7.16)

`./gradlew :app:compileFullDebugKotlin` configures only the **debug** buildType. If the merge damages
something used **only in the `release` buildType**, the debug compile is green and the break stays hidden
until `release.sh` runs `assembleFullRelease` — i.e. after it has already bumped the versionCode.

0.7.16 did this: the auto-merge took upstream's top-of-`build.gradle.kts` helper block and **dropped our
KevBox-only `resolveLocalProperty(...)` function**, but our resolution kept its 5 call sites in the
`release` buildType (`SUPABASE_URL`, `NUVIO_SUPABASE_*`, etc). Debug uses `resolveProperty` instead, so
`compileFullDebugKotlin` passed clean; the release build would have died at Gradle *configuration* with
`Unresolved reference: resolveLocalProperty`. Fix: re-add the one-line helper (it lives just under
`resolveProperty`).

**Standing check on every heavy sync — verify the release side too, before `release.sh`:**
```bash
# does the release build even configure? (cheap; no full build)
./gradlew :app:assembleFullRelease --dry-run
# or just confirm our release-only helpers survived the merge:
grep -q "fun resolveLocalProperty" app/build.gradle.kts && echo OK || echo "MISSING resolveLocalProperty"
```
`release.sh` itself runs the real `assembleFullRelease` (R8 + signing), so it *is* the ultimate gate —
but you don't want to discover a break there, because it fails *after* the versionCode bump (re-running
then double-bumps; resume by hand instead — see the note at the bottom of this doc).

### ⚠️ Sign-out wipes the addon store, so member config MUST re-apply on the next sign-in (2026-08-30)

`AuthManager.signOut()` and `handleUnexpectedSignedOut()` both call
`AccountLocalDataResetService.clearAfterSignOut()` → `ProfileDataStoreFactory.clearProfileScopedData()`,
which clears **every** profile-scoped DataStore. `addon_preferences` is not in
`retainedStandaloneDataStoreNames`, so **signing out deletes the member's addons** and drops them to
the four baked-in defaults, none of which serves streams.

`MemberConfigService` originally watched auth as
`filterIsInstance<FullAccount>().distinctUntilChangedBy { it.userId }`. Because `SignedOut` was
filtered out, a sign-out / sign-in round trip inside one app session looked like
`FullAccount(X), FullAccount(X)` — a duplicate — so the apply was skipped and **the addons never came
back until the app was restarted.** That is what the account screen's "Restart this device after
signing in" has been papering over.

Now every auth state goes through `MemberConfigApplyGate` (kevbox-only), which treats `SignedOut` as
a reset and ignores `Loading` so token refreshes still don't cause repeat applies.

If a future sync tempts you to "clean up" that collect back into a filter + dedupe: don't. Verify
with `./gradlew :app:testFullDebugUnitTest --tests "*MemberConfigApplyGateTest"`, and on-device by
signing out and back in **without** restarting, then confirming a second
`MemberConfigService: Applied N member_addon row(s)` line.

### 🛑 `@Binds @Singleton` scopes the INTERFACE, not the class — duplicate repositories DDoS our own addon host (2026-08-30)

The nastiest bug of this cycle, and the one most likely to come back, because the code that causes
it looks completely normal.

`RepositoryModule` does `@Binds @Singleton bindAddonRepository(impl: AddonRepositoryImpl)`. That
scopes the **`AddonRepository` interface binding**. It does NOT scope `AddonRepositoryImpl`. Three
classes inject the **concrete** type — `StartupSyncService`, `SubtitleRepositoryImpl` and
`AccountViewModel` — and each of those got a **fresh instance**. Every instance:

- runs `loadManifestCacheFromDisk()` in its `init`,
- keeps its own private `manifestCache`,
- starts `installedAddonsFlow` with `SharingStarted.Eagerly`, which **fetches every enabled addon's
  manifest**.

So one sign-in fired the same manifest request four-plus times within milliseconds. AIOStreams
answered the first and returned **HTTP 429 Too Many Requests** to the rest. The instance backing the
stream search was one of the losers, cached nothing, and **the member's only stream source vanished
from the search** — no error dialog, no red text, just a suspiciously short stream list. Fixed by
putting `@Singleton` on the class itself.

Why this matters beyond one bug: we point ~300 boxes at our own AIOStreams host. Any accidental
instance multiplication is a self-inflicted load multiplier against a host that rate-limits, and the
symptom is "streams are worse today", which nobody reports as a bug.

**The tell in the logs** (`adb logcat | grep AddonRepository`): the same manifest URL appearing
several times within a second, or `Loaded N cached manifests from disk` printing more than once per
launch — that line runs in `init`, so more than one means more than one instance.

```bash
# want: exactly 1 line per URL (the pre-fix run showed 6 for the member's own addon)
adb -s emulator-5554 logcat -d | grep -E "(Updated addon manifest cache|Failed to fetch addon manifest for) url=" \
  | sed 's/.*url=\(https:\/\/[^/]*\).*/\1/' | sort | uniq -c | sort -rn

# want: never MORE than 1. Clear logcat BEFORE launching or this reads 0 — the line runs in init,
# so it only appears in a buffer that covers app startup.
adb -s emulator-5554 logcat -d | grep -c "Loaded .* cached manifests from disk"

# want: 0
adb -s emulator-5554 logcat -d | grep "Failed to fetch addon manifest" | grep -c 429
```

**Generalise it.** After a sync, for any repository that owns a cache, a connection or an eager
flow, check that the CLASS is scoped and not just its `@Binds`. `grep -rn ": <Name>Impl" app/src`
finds the concrete injections; if there are any, the class needs its own `@Singleton`.

### ⚠️ Member-facing wording is a fork feature — "no stream source" must not revert to addon jargon (2026-08-30)

Upstream writes error copy for people who install their own addons. We ship to family members who have
never heard the word "addon", and our whole stream supply is remote and invisible to them. So a couple of
upstream strings are **load-bearing support tooling** for us, and reverting one is a silent regression:
compile-green, test-green if you also drop the tests, and it costs a phone call per member.

The live case: `StreamRepositoryImpl.buildAggregateFailureMessage`, `attemptedAddonNames.isEmpty()`
branch. Reaching it means **zero addons were eligible to be asked at all**, and on KevBox that has one
dominant cause — **the member is signed out**:

- `AddonPreferences.getDefaultAddons()` ships Cinemeta + two OpenSubtitles + a catalog addon. **None
  declares a `stream` resource.** They cannot play anything, by design — the real source is the member's
  per-member AIOStreams row in Supabase `member_addon`.
- `MemberConfigService` applies that row only once auth reaches `FullAccount`.
- Signing out **wipes the addon store**: `AuthManager.signOut()` / `handleUnexpectedSignedOut()` call
  `AccountLocalDataResetService.clearAfterSignOut()` → `ProfileDataStoreFactory.clearProfileScopedData()`,
  and `addon_preferences` is **not** in `retainedStandaloneDataStoreNames`.

So a signed-out box falls back to four streamless addons and shows upstream's
`No installed addon supports streams for "movie".` on every Play. Diagnosed 2026-08-30 after a member
sat on that screen for days; his session had ended and nothing in the app said so. (The one existing
signal, the `MainActivity` Toast at line ~374, fires once at startup, is consumed immediately, and is
unreadable from a sofa.)

We now branch the message on auth state — signed out / signed in with no source / still loading. See the
`KevBox FORK DIVERGENCE` block on that function and the conflict-table row.

**After every sync, verify:**

```bash
# 1. The branch survived (want: the three-way when, not a bare getString)
grep -n "AuthState.SignedOut ->" app/src/main/java/com/nuvio/tv/data/repository/StreamRepositoryImpl.kt

# 2. The strings survived — a strings.xml merge can drop these INDEPENDENTLY of the Kotlin,
#    which still compiles and only breaks at runtime (want: both present)
grep -c "error_stream_signed_out\|error_stream_no_source_configured" app/src/main/res/values/strings.xml

# 3. The behaviour survived (want: 3 passing)
./gradlew :app:testFullDebugUnitTest --tests "*StreamRepositoryNoStreamAddonMessageTest"
```

Generalise it: **whenever upstream adds or rewords a failure message on a path a family member can
reach, read it as if you had never seen the codebase.** If it names an internal concept (addon,
manifest, scraper, resource, debrid) it is wrong for our audience, and the fix belongs on the fork.

### ⚠️ Invisible *policy* regression — upstream re-exposing a feature we deliberately hid

Worse than a compile break: a change that compiles **and** runs but quietly undoes a KevBox policy, with
**zero conflict**. The 0.7.8-beta sync did exactly this — "Move addons into content discovery settings"
(+ a new Plugins screen) **moved on-device addon management** from the sidebar (which KevBox had removed)
**into Settings → Content Discovery**, wired to live routes. Because KevBox had never edited
`SettingsScreen.kt`, the whole rework auto-merged with no marker — and family members silently regained
the ability to view/edit addons + plugins, which the operator manages remotely (kevbox-admin /
`member_addon`). Fixed in two layers: hide the `CONTENT_DISCOVERY` category in `SettingsScreen.kt`
(`-> false`) **and** no-op `onNavigateToAddons`/`onNavigateToPlugins` in `NuvioNavHost.kt`.

Lesson: compile-green does not prove policy-safe. **After each sync, grep for new navigation entry
points to screens KevBox suppressed** — e.g. `grep -rn "navigate(Screen.AddonManager\|navigate(Screen.Plugins"`
and review any new sidebar / Settings rows. Upstream can re-surface a hidden feature through a brand-new
code path that never touches your files.

**0.7.18 did it again — through deeplinks.** "adding deeplinks for addons and detailscreen" registered
`nuvio://` + `stremio://` VIEW/BROWSABLE intent-filters (`AndroidManifest.xml`) and wired
`AppDeepLink.AddonInstall` in `MainActivity` to `deepLinkHandler.installAddon(...)` **and**
`navController.navigate(Screen.AddonManager.route)`. That's a brand-new entry point straight to the
addon-install path that bypasses every door we'd already shut (sidebar, `CONTENT_DISCOVERY` category,
`onNavigateToAddons` no-op). It compiles and runs. Fixed by neutralizing the `AddonInstall` branch in
both `LaunchedEffect`s + dropping the `stremio://` filter (see the two 0.7.18 table rows above) — the
`Meta`/title deeplink is kept. So the post-sync policy grep must also cover **manifest intent-filters and
deeplink handlers**, not just `navigate(...)` call sites.

**0.8.11 did it a third way — through branding, not access.** The new launcher icon picker let a family
member swap the home-row tile to one of five *Nuvio-branded* icons we don't override (see the 0.8.11
callout). Nothing was unlocked and nothing phoned home; the app just stops looking like KevBox. So the
policy sweep isn't only "can they reach a screen we hid" — it's also **"can they reach a setting that
undoes the rebrand"**. New Settings rows touching icons, banners, themes, or app name deserve the same
look as new navigation routes.

**Standing post-sync policy greps (run all of these):**
```bash
grep -rn "navigate(Screen.AddonManager\|navigate(Screen.Plugins" app/src --include=*.kt   # want: empty
grep -n "stremio" app/src/main/AndroidManifest.xml                                        # want: comment only
grep -A4 "KEVBOX_HIDDEN_SETTINGS_CATEGORIES = setOf" app/src/.../SettingsScreen.kt       # want: PROFILES, CONTENT_DISCOVERY, TRACKING
grep -c "in KEVBOX_HIDDEN_SETTINGS_CATEGORIES" app/src/.../SettingsScreen.kt             # want: 1 (the filter is applied)
# (the old "CONTENT_DISCOVERY ->" grep now also matches upstream's own lines and passes even when nothing is hidden)
grep -n "onNavigateToTracking = \|onNavigateToManageProfiles = " app/src/.../NuvioNavHost.kt # want: both "{ }"
grep -n "PlaybackSection.P2P }" app/src/.../settings/PlaybackSettingsSections.kt           # want: 1 (P2P section filtered)
grep -n "check(settings.p2pEnabled)" app/src/.../core/torrent/TorrentService.kt           # want: 1
grep -n "startActivity" app/src/.../player/PlayerRuntimeControllerStreams.kt              # want: empty
grep -c "manualSelection = true" app/src/.../MainActivity.kt                              # want: 2 (TV home-screen launches)
grep -rn "SHOW_LAUNCHER_ARTWORK_PICKER" app/src --include=*.kt                            # want: still false
grep -rn "supportNuvioEnabled" app/src/full/.../AppFeaturePolicy.kt                       # want: false
grep -rn "UpdateBannerHost\|AbiSelector\|VersionUtils" app/src --include=*.kt             # want: empty
grep -rn "manualSelection = true" app/src/.../NuvioNavHost.kt                             # want: 8 (6 + 2 start-from-beginning, 1.1.0-beta.4)
grep -c "manualSelection = true" app/src/.../ui/navigation/DetailChildHost.kt             # want: 3 (nested details incl. start-from-beginning)
grep -rn "Screen.Stream.createRoute\|::navigateToDetailStream" app/src/main --include=*.kt # review any NEW call site
grep -rn "allowUnverifiedPlayback = true" app/src --include=*.kt                        # want: 1 (PlaybackAvailabilityProvider)
ls app/src/full/res/drawable/app_logo_wordmark_*.xml | wc -l                              # want: 5 (themed wordmark aliases)
ls app/src/main/java/com/nuvio/tv/updater/ 2>/dev/null                                    # want: no such directory
grep -rn "UpdateChannelSettings(" app/src --include=*.kt                                  # want: empty
grep -c "unionWhenNeverSynced" app/src/.../core/sync/WatchedItemsSyncService.kt          # want: >= 1
git diff upstream/dev -- app/build.gradle.kts | grep -c haze                              # want: 0 (dep bumps taken)
```

### 🛑 Remote control plane — upstream can switch our backend / force-logout the fleet (0.7.9-beta)

The 0.7.9-beta sync added a **remote "sync backend switch"** (the `dbswitch` branch). On every
`onCreate`/`onResume`/`onStart`, `MainActivity` calls `syncBackendSwitchService.refreshSelection()`,
which fetches a JSON manifest from `BuildConfig.SYNC_BACKEND_MANIFEST_URL` and **obeys it**: the
manifest can change the active sync backend and **force-logout every install** (`forceLogoutOnChange`
defaults `true`). Upstream's default URL is `https://switch.nuvioapp.space/config.json` — a
**NuvioMedia-controlled** endpoint we have no access to. Shipped as-is, that hands tapframe a remote
off-switch over every family TV, plus a phone-home on every app foreground.

**The fix is one line, and it must be re-applied on every future sync:** in `app/build.gradle.kts`,
keep the `SYNC_BACKEND_MANIFEST_URL` `buildConfigField` default **blank** for *both* flavors:

```kotlin
buildConfigField("String", "SYNC_BACKEND_MANIFEST_URL", "\"${resolveProperty(devProperties, localProperties, "SYNC_BACKEND_MANIFEST_URL", "")}\"")
```

A blank URL makes `SyncBackendRepository.refreshFromManifest()` return `NotConfigured` immediately — no
network call, no external switch. Second safety layer: leave `NUVIO_SUPABASE_URL` / `NUVIO_SUPABASE_ANON_KEY`
**blank** so the alternate "nuvio" backend fails `isUsableClientConfig()` and can't be selected even if a
manifest somehow slipped through. The default backend is `hosted`, which reads our existing
`SUPABASE_URL`/`SUPABASE_ANON_KEY` — i.e. *our* Supabase — so blanking the manifest changes nothing about
normal operation. **Post-sync check:** `grep -n "switch.nuvioapp.space\|SYNC_BACKEND_MANIFEST_URL" app/build.gradle.kts`
— the only `nuvioapp.space` hit should be inside a comment, never a live `buildConfigField` default.

> **0.7.16 status:** upstream deleted the whole reader (`SyncBackendRepository`/`SyncBackendSwitchService`),
> so `SYNC_BACKEND_MANIFEST_URL` is now **dead config nothing reads**, and `MainActivity` no longer calls
> `refreshSelection()`. We still keep the field blank (harmless, and cheap insurance if upstream revives the
> switch a third time). The `NUVIO_SUPABASE_*`-blank safety layer still matters — see Invisible-breakage #2.

### 🛑 New data-sending endpoint each heavy cycle — verify it stays blank (0.7.12-beta)

The remote-control-plane is not a one-off; **upstream keeps adding `*_URL`/`*_BASE_URL` `buildConfigField`s
that send data out.** 0.7.12-beta added **opt-in playback-issue diagnostics reporting**:
`PlaybackIssueReportRepository.submit()` POSTs `api/playback-reports` to `BuildConfig.PLAYBACK_REPORTS_BASE_URL`.
It's **inert for KevBox by default** (the opt-in `playbackIssueReportsEnabled` defaults `false`, *and* `submit()`
no-ops when the base URL is blank, *and* upstream itself defaults that URL blank — read from `local.properties`).
So nothing to change **as long as `PLAYBACK_REPORTS_BASE_URL` is never set in `local.properties`**.

**0.7.16 added several more** — all inert for us by the same rule (blank-sourced URL, `""` fallback):
`AuthDiagnosticReportRepository` (POSTs auth diagnostics), `StreamSpeedTester`, plus the `SUPABASE_FALLBACK_URL`,
`DONATIONS_*`, `AVATAR_PUBLIC_BASE_URL`, `UNIQUE_CONTRIBUTIONS_BASE_URL`, `PARENTAL_GUIDE_API_URL`,
`INTRODB_API_URL`, `TRAILER_API_URL`, `IMDB_*` fields. Keep every one blank in `local.properties`.
Two carry a hardcoded non-blank default but are **pre-existing + dormant**: `TRAKT_API_URL` (public Trakt API,
fine) and `TV_LOGIN_WEB_BASE_URL` (`nuvio.tv/tv-login` — only used to render a QR for the *phone*; the TV talks
to our Supabase directly; we replaced QR sign-in with email/password anyway).

**Standing post-sync check** — list every data-sending field and confirm none default to a NuvioMedia/tapframe host:
`grep -nE 'buildConfigField.*(_URL|_BASE_URL)' app/build.gradle.kts` — each should resolve from `local.properties`
(or `devProperties`) with a `""` fallback, never a hardcoded remote default (bar the two dormant exceptions above).
Treat any new one like the manifest URL.

### 🛑 New crash-reporting surface — Sentry (0.7.16)

0.7.16 wired in **Sentry** (crash/error reporting): `SentryInitializer`, `SentrySettingsDialog`,
`SentrySettingsDataStore`, a `SentryNetworkBreadcrumbInterceptor`, and the `sentry.android.gradle` plugin
in the `plugins {}` block. **Inert for KevBox by default** — `SentryInitializer.start()` early-returns when
`BuildConfig.SENTRY_DSN` is blank, and we never set `SENTRY_DSN`. (Note: `SentrySettingsDataStore.enabled`
defaults `true`, so it's the blank *DSN* — not the toggle — that keeps it off; don't rely on the toggle.)
The Gradle plugin only uploads debug-symbol mappings when `SENTRY_AUTH_TOKEN`/`SENTRY_ORG`/`SENTRY_PROJECT`
are all set (blank for us → `sentryMappingUploadEnabled = false`, no upload during `assembleFullRelease`).
**Standing check:** keep `SENTRY_DSN` **and** `SENTRY_AUTH_TOKEN`/`SENTRY_ORG`/`SENTRY_PROJECT` blank/unset.

### ⚠️ Invisible breakage #2 — the Supabase DI wiring FLIP-FLOPS between syncs (0.7.9 ↔ 0.7.16)

This one is the cautionary tale of the whole doc: **the same architectural decision reversed itself two
releases later, and the old "standing check" became a false alarm.** Check which state you're in *first*,
then decide — never assume last cycle's fix still applies.

- **0.7.9 (the `dbswitch`):** upstream **deleted** `core/di/SupabaseModule.kt` (the Hilt `@Module` that
  `@Provides Postgrest`/`Auth`/`SupabaseClient`) and replaced it with an `@Inject`-able
  `SyncBackendSupabaseProvider`. Four KevBox-only files still injected `Postgrest` directly, so the merge
  removed the binding they relied on → Hilt/compile break, no markers. We fixed it by routing those four
  through the provider (`private val postgrest get() = supabaseProvider.postgrest`).
- **0.7.16 (the reversal):** upstream **reverted the whole thing** — `SupabaseModule.kt` is **back**
  (`@Provides Postgrest`/`Auth`/`SupabaseClient` again) and **`SyncBackendSupabaseProvider` + all the
  `SyncBackend*` files are DELETED**. Now our four files break the *opposite* way: they import/inject a
  provider that no longer exists → unresolved reference, no markers. So the 0.7.9 fix had to be **undone**
  per-file — migrate them **back to direct `Postgrest`** (which `SupabaseModule` now provides, exactly like
  every upstream sync service already does):

```kotlin
// import com.nuvio.tv.core.network.SyncBackendSupabaseProvider   ->
import io.github.jan.supabase.postgrest.Postgrest
// private val supabaseProvider: SyncBackendSupabaseProvider,     ->
private val postgrest: Postgrest,
// and DELETE the body line:  private val postgrest get() = supabaseProvider.postgrest
```

The four files (unchanged across both cycles): `MemberConfigService.kt`, `AccessControlService.kt`,
`DeviceGuardService.kt`, `TelemetryRepository.kt`.

**Presence-aware post-sync check (do this every sync — the right answer depends on which files exist):**
```bash
ls app/src/main/java/com/nuvio/tv/core/di/SupabaseModule.kt 2>/dev/null && echo "MODULE PRESENT → inject Postgrest directly" \
  || echo "MODULE GONE → route the 4 files via a provider"
grep -rn "SyncBackendSupabaseProvider" app/src --include=*.kt   # after a 0.7.16-style sync: only comments, no live import/param
```
- If `SupabaseModule` **exists** (0.7.16+ state): direct `Postgrest` injection is CORRECT — the old
  "grep should return nothing" check is **obsolete**, direct injection is now expected everywhere.
- If `SupabaseModule` is **gone** again (a future re-delete): re-introduce the provider indirection.
- Also confirm there's exactly **one** binding per type — if both `SupabaseModule` *and* a provider ever
  bind `Postgrest`, Hilt fails with "bound multiple times". (0.7.16 is single-provider; verified clean.)

> Note: upstream's 0.7.9 baseline profile (`baseline-prof.txt`/`startup-prof.txt`) still lists the deleted
> `SupabaseModule` class — that's an upstream staleness, not ours. R8/ART silently drop unresolvable
> profile rules, so it's benign and ships in upstream's own 0.7.9. Don't hand-edit those files.

### 🛑 Invisible breakage #3 — client adds an RPC arg the Supabase server doesn't have → ALL sync pushes silently 404 (0.7.16)

**The worst kind of break in this doc: it compiles, it passes a single-device smoke test, and it silently
stops the *entire* cloud-sync fleet for days.** 0.7.16 introduced `SyncClientIdentity.putSyncOriginClientId()`
(`core/sync/SyncClientIdentity.kt`, `ORIGIN_CLIENT_ID_PARAM = "p_origin_client_id"`) and wired it into **every**
`sync_push_*` / `sync_delete_*` RPC body the app sends — watch progress, watched items, library, collections,
profiles, profile-settings, home-catalog-settings (for a new realtime self-echo-suppression feature). But the
**Supabase functions were never migrated to accept the new arg.** PostgREST resolves an RPC by matching the JSON
body keys to a function's named parameters, so a 3-key body `{p_entries, p_profile_id, p_origin_client_id}`
matched **no** function → HTTP 404 `PGRST202` ("could not find the function … in the schema cache") on every push.

Why it's invisible: the throw is **swallowed** (`WatchProgressSyncService` `catch { Log.e(…); Result.failure }`,
then `WatchProgressRepo W "Failed single progress push; falling back to full sync next cycle"`). Nothing crashes.
The build is clean. A lone test device still shows its own **local** history, so a one-device smoke test looks
fine. It only surfaces as **cross-device desync** and a **stale admin dashboard** days later. Symptom in prod:
member `watch_progress` / `watched_items` writes stop dead on the exact day 0.8.16-beta rolled out (2026-07-07);
heartbeats keep working because `claim_device` is a *different*, unchanged RPC.

**Fix (server-side, no app release needed — the shipped devices self-heal on next push):** add the arg as
**optional** to each function, and DROP the old 2-arg overload (keeping both makes a 2-key call ambiguous →
`PGRST203`). Lives in the **kevbox repo**, not here: `supabase/migrations/0011_sync_push_accept_origin_client_id.sql`
(`p_origin_client_id text default null`, arg accepted-and-ignored — realtime echo-suppression left unwired,
harmless). Applied to prod 2026-07-14.

**Standing post-sync check — every arg the client sends to a sync RPC MUST exist on the server function.**
This is the general rule; `p_origin_client_id` was just the first instance. After any sync that touches
`core/sync/`, enumerate the params the client sends and confirm each has a server counterpart:
```bash
# 1. What params does the app now send to sync_push_*/sync_delete_* RPCs? (watch for NEW ones)
grep -rnE 'rpc\("sync_(push|delete)_|put\(|putSyncOriginClientId' app/src/main/java/com/nuvio/tv/core/sync/
# 2. In the kevbox repo, confirm every sync_push_/sync_delete_ function accepts them (want: empty):
#    supabase db query --linked "select p.proname, pg_get_function_arguments(p.oid)
#      from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public'
#      and (p.proname like 'sync_push_%' or p.proname like 'sync_delete_%') and p.proname not like '%\_for'
#      and pg_get_function_arguments(p.oid) not ilike '%origin_client_id%';"
```
If the app sends a key with no matching server param, PostgREST 404s the push and the error is swallowed — there
is **no** compile or runtime signal. Treat any new key in a sync RPC body as a required server migration.

**0.7.19 note — the surface can also *shrink*.** 0.7.19 deleted `TraktCredentialSyncService` (its
`sync_push_provider_credentials` / `sync_pull_provider_credentials` calls are gone; Trakt tokens no longer
sync between devices — each device authenticates Trakt locally). Only `sync_delete_provider_credentials`
survives, in the new `TraktCredentialCleanupService`, with the **same** params as before and a soft-fail
`Result` wrapper. No new wire keys → no server migration. Otherwise 0.7.19 was the lightest cycle yet:
2 trivial conflicts (version numbers; a NuvioApplication import pair), manifest untouched, no policy
regression, no new `buildConfigField` surfaces, scrobble-stop comment untouched.

### 🛑 Upstream reworked ITS OWN updater — the "update banner" phones home to tapframe/NuvioTV (0.8.1)

0.8.0 added `feat(updater): add update banner` — a rework of the **full-flavor updater package, the exact
directory KevBox owns end-to-end** (`app/src/full/java/com/nuvio/tv/updater/`). Upstream's new
`UpdateRepository` checks `api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases/latest`, and those
BuildConfig fields still read `"tapframe"`/`"NuvioTV"` **even on our branch** (leftover fields our updater
ignores). Their `UpdateViewModel.init` auto-checks on every cold start (banner default **enabled**), and
`MainActivity` wraps the whole scaffold in `UpdateBannerHost` with **no** `IS_DEBUG_BUILD` gate. Only our
higher version number (`0.8.19` > `0.8.1`) stops the banner from showing today — the day upstream tags
`0.8.20`, family TVs would get a banner offering an upstream-signed APK. **This merges in with almost no
conflicts** (new files + auto-merged wiring), so it's invisible unless you grep.

**Resolution (done 2026-08-04, re-apply if upstream touches the updater again):**
- Conflicts `UpdatePreferences.kt`/`UpdateViewModel.kt` → take **ours** entirely; `ui/UpdatePromptDialog.kt`
  (modify/delete) → **keep ours**.
- DELETE upstream's additions: `full/.../updater/AbiSelector.kt`, `VersionUtils.kt`,
  `ui/UpdateBanner.kt`, `ui/UpdateBannerHost.kt`, `ui/UpdateDialogs.kt`,
  `main/.../updater/UpdateBannerPolicy.kt`, `playstore/.../updater/ui/UpdateBannerHost.kt`, and
  `app/src/test/.../UpdateBannerPolicyTest.kt` (tests the deleted class). (`AbiSelector`/`VersionUtils`
  may not even enter the merge if a prior cycle already deleted them — check.)
- RESTORE our playstore stubs the merge silently overwrites/deletes:
  `git checkout kevbox -- app/src/playstore/java/com/nuvio/tv/updater/UpdateViewModel.kt app/src/playstore/java/com/nuvio/tv/updater/ui/UpdatePromptDialog.kt`
- `MainActivity.kt`: remove the auto-merged `UpdateBannerHost` import + wrapper; restore our
  `UpdatePromptDialog` block gated on `AppFeaturePolicy.inAppUpdatesEnabled && !BuildConfig.IS_DEBUG_BUILD`.
  (Auto-merge also dropped the `AppFeaturePolicy`/`UpdatePromptDialog` imports — re-add.)
- `AboutScreen.kt`: delete upstream's banner `SettingsToggleRow` (`setUpdateBannerEnabled` doesn't exist on
  our VM); keep only our "Check for Updates" `SettingsActionRow` inside the same gate; keep the
  privacy-policy row commented (don't take upstream's live `nuvio.tv/privacy-policy` row).
- **Post-sync grep (add to the standing list):**
  `grep -rn "UpdateBannerHost\|dismissBanner\|setUpdateBannerEnabled\|updateBannerEnabled\|UpdateBannerPolicy\|consumeFeedbackMessage\|dismissUnknownSourcesDialog\|AbiSelector\|VersionUtils" app/src` → EMPTY.
  Watch `app/src/main/baseline-prof.txt`/`startup-prof.txt` — upstream's regenerated profiles reference
  `AbiSelector`/`VersionUtils`; either sed out those stale lines (done 0.8.1) or regenerate profiles via
  the `baselineprofile` module. **0.8.2:** upstream now also COMMITS the generated copies at
  `app/src/main/generated/baselineProfiles/{baseline,startup}-prof.txt` (new files, ~78k lines each) —
  same stale refs, same sed treatment (done 0.8.2); the grep above will hit these `.txt` files, which is
  fine — only `.kt` hits matter.

### 🛑 Library sync rewritten to delta/events — SERVER MIGRATION REQUIRED (0.8.1)

0.8.0 replaced library sync with the watched-items-style event model. `sync_push_library` is **renamed**
`sync_push_library_items`, and the app newly calls `sync_delete_library_items`,
`sync_get_library_delta_cursor`, `sync_pull_library_delta`, plus `register_current_device` (new
`DeviceSessionRegistration`, fires on every auth + onResume; soft-fails if missing). All take
`p_origin_client_id`. There is **no client fallback** — missing functions = library sync 404s and dies
(`Result.failure`, log-only). Migration **applied to prod 2026-08-04**: `sql/sync/library_delta_setup.sql`
(new `library_events` log + `registered_devices` table + the 5 functions, mirroring
`watched_items_setup.sql`; teardown in the `_teardown.sql` pair). **Fleet-compat rule:** the old fleet
still calls 2-key `sync_push_library` — keep it working by making it 3-arg-with-default (drop the 2-arg
overload, PGRST203) and have its body also append events, so old-fleet pushes are visible to new-fleet
delta pulls. Follow-ups: `prune_sync_events` doesn't prune `library_events` yet (log grows unbounded);
`.supabase_db.env`'s `SYNC_TEST_DB_URL` is stale (tenant gone) — the working prod URL is `SUPABASE_DB_URL`
in `local.properties`.

Client side: `LibraryPreferences.kt` was fully rewritten upstream (implements `LibrarySyncLocalStore`) —
take theirs wholesale. **But** upstream's `LibrarySyncReducer.applySnapshot` only preserves local items on
first sync when the remote is EMPTY (`migrateLegacyLocal`) — a never-synced device restoring against a
non-empty remote library would drop local-only items (the library analog of the watched-items Option-B
gap). Patched in `LibrarySyncReducer.kt` (`preserveLocalOnFirstSnapshot` path: union local-only items +
queue them as pending upserts; marked `KevBox FORK DIVERGENCE (ponytail)`, test in
`LibrarySyncReducerTest.kt`). **Re-check this patch survives future syncs that touch the reducer.**
`AccountViewModel.kt`/`StartupSyncService.kt`: take upstream's `syncFromRemote(profileId)` /
`pushToRemote(profileId)` API; keep our `FEATURE_MEMBER_ADDON_CONFIG` guards.

### 0.8.1 misc — realtime deleted, enum rename, player notes

- **Realtime sync is gone upstream (take the removal):** `RealtimeSyncInvalidationService.kt`, the
  `supabase-realtime` dep + toml entry, `install(Realtime)` in `SupabaseModule`, and the
  `REALTIME_SYNC_ENABLED` flag all deleted. Drop our three `NuvioApplication.kt` lines (import/inject/
  start-block). Only NuvioApplication ever referenced it; our member-config/access services don't use it.
  Consequence: cross-device changes propagate on foreground/delta sync, not near-instant push.
- **`SettingsCategory.TRAKT`→`TRACKING`** — see the SettingsScreen table row. `Screen.Trakt`→
  `Screen.Tracking` (route string `"trakt"` unchanged) + `TraktScreen.kt` deleted → `TrackingSettingsScreen.kt`;
  take the rename in `NuvioNavHost.kt` while keeping our no-op addon/plugins callbacks.
- **Simkl is inert for us:** blank `SIMKL_CLIENT_ID`, and the only entry point is the Tracking settings
  screen, hidden by `TRACKING -> false`. No NuvioMedia hosts in the Simkl code (api.simkl.com only).
- **Player:** conflicts only in the 3 hooked files (imports keep-both; STATE_ENDED keep ours). One trap:
  upstream deleted the `isTraktCwActive` mechanism — dropping our `scope.launch { isTraktCwActive = … }`
  line is **required** or the build breaks (`grep -rn isTraktCwActive app/src` → EMPTY). Upstream now
  fires Trakt completion exactly once by construction (`handleNaturalPlaybackEnded()` + stricter
  natural-end gate + the existing idempotency flag), so our commented-out `emitCompletionScrobbleStop`
  is now belt-and-suspenders — keep it commented.
- `NuvioApplication.kt`: our `appScope` survived the auto-merge (0.8.1) — the addon-seed block needs no
  new scope; take upstream's Coil `CacheControlCacheStrategy` + `SimklAnimeIdPreferenceHolder`.
- `.gitignore` conflicted for the first time (union both sides).
- Resolved on branch `sync-0.8.1` (merge `45204625d`), spec archived at `plans/sync-0.8.1-resolution.md`.

### 0.8.2 — the light cycle (2026-08-06)

33 commits (dev tip `86e0510e0`→`320c64dc7`; the `0.8.2-beta` tag sits 12 commits behind tip). Content:
continue-watching card styles + inline previews in Layout Settings, subtitle-addon fetch fixes
(idPrefixes fallback, path encoding, DTO nullability), a `StreamRepositoryImpl` plugin-isolation
refactor (TMDB lookup only when a compatible scraper is enabled), upstream reverting its own
seek-forward/416 fix (1 line in `PlayerRuntimeControllerInitialization.kt` — auto-merged over our
telemetry hooks fine), D-pad focus fix for skip-intro + next-episode coexistence, i18n. **One conflict:
the version block.** Nothing touched the updater, `core/sync` (no server migration), MainActivity,
manifest, SettingsScreen, or `buildConfigField`s. Only new trap: the committed generated baseline
profiles (see the updater callout). Released **0.9.2-beta (1043)** same day.


### 0.8.3 — provider-credential sync = server migration; a fork divergence retired (2026-08-10)

72 commits (0.8.2-beta→0.8.3-beta), resolved on branch `sync0.8.3`. Mostly a big subtitle "sidecar"
rework (new `PlayerSidecarSubtitles.kt`/`PlayerSubtitleRtlFix.kt`, `PlayerRuntimeControllerInitialization.kt`
gutted −395 lines) + player/perf fixes. Manifest, MainActivity, updater, SettingsScreen, NuvioNavHost,
WatchedItemsSyncService, LibrarySyncReducer all untouched — every policy guard and fork patch survived
with no action. Four conflicts: version block (keep ours), 2 generated baseline profiles (take theirs +
the standing sed — NOTE the sed pattern must be **case-insensitive-ish**: upstream's profiles also carry
`getUpdateBannerEnabled`/`UpdateBannerEnabledKey` method rules that a lowercase `updateBannerEnabled`
pattern misses), and `PlayerSettingsDataStore.kt` — which **retired a fork divergence**:

- **`AddonSubtitleStartupMode` is deleted upstream** (enum, keys, parse, settings UI). Our PREFERRED_ONLY
  default flip died with it — **take upstream wholesale, do NOT try to preserve it.** The new sidecar
  system does "fast-startup preferred-language auto-select" natively (plus several race-condition fixes),
  which is what our flip existed to force. The **forced-subs OFF flip survives** (upstream never touched
  `subtitleUseForcedSubtitlesKey ?: false`) — that's now the fork's only PlayerSettingsDataStore divergence.
  Verify on-device that preferred-language subs still auto-enable.

- **🛑 SERVER MIGRATION (same class as 0.7.16/0.8.1): new `ProviderCredentialSyncService`** syncs
  debrid/MDBList/AnimeSkip API keys via THREE new RPCs — `sync_seed_provider_credentials` /
  `sync_push_provider_credentials` (`p_profile_id`, `p_credentials` = array of
  `{provider, credential_json}`, `p_origin_client_id`) and `sync_pull_provider_credentials`
  (`p_profile_id`). None existed on prod (verified: 37 sync fns, zero provider ones — the 0.7.19-era
  `sync_delete_provider_credentials` client call had been soft-failing against a nonexistent fn all
  along; that caller `TraktCredentialCleanupService` is now deleted). Startup/foreground failures are
  swallowed, **but `AccountViewModel`'s manual pull-from-cloud path calls it with `.getOrElse { throw it }`
  — missing RPCs hard-abort the whole cloud restore**, the path our Stremio-import backfills use.
  Fixed by **kevbox repo `supabase/migrations/0012_provider_credentials.sql`** (applied to prod +
  probed 2026-08-10). Server rule that matters: **store only non-empty credentials** (blank → skipped on
  seed, row-delete on push) — the client pushes its full provider list including blanks and pull-overwrites
  local values, so stored-blank rows would let an empty device wipe a configured device's keys.
  Also note: these keys are now **excluded from the profile-settings blob** (`credentialProfileSettingsKeys`)
  — without the new table, credentials would silently stop syncing across devices entirely.

### ⚠️ Invisible breakage #4 — unit-test fakes lag upstream interface growth (0.8.3, found 2026-08-11)

0.8.3 grew the `AddonRepository` interface (`applyRemoteAddonConfig`, `resetPrimaryAddonsToDefaults`).
`SearchViewModelConcurrencyTest`'s hand-rolled `GatedAddonRepository` fake didn't implement them, so the
**entire unit-test source set failed to compile** — zero conflict markers, and `compileFullDebugKotlin`
can't see it (tests are a separate source set). It sat broken for a full day until the next unit-test run.
Fix: add `error("unused")` overrides to the fake. Detector: the `testFullDebugUnitTest` line in the verify
block below — treat a test-compile failure after a sync as this class of breakage and check every
hand-rolled fake against the interfaces upstream touched.

**Known-failure baseline (as of 0.9.3-beta / 2026-08-11): 13 pre-existing failures + 1 skipped** in
unrelated areas (DolbyVisionBaseLayerPolicy, Matroska/AFR probes, TmdbMetadataService release ranges,
Simkl reconciliation, CollectionsDataStore migration, ContinueWatchingAiringRules, ExoPlayer perf tiers,
LocalhostZeroCopy 404). These predate the 0.8.3 merge fix. After a sync, compare against this list —
only NEW failures implicate the merge. (Shrinking this baseline is separate housekeeping, not sync work.)

### 0.8.4 + 0.8.5 — SELF_HOSTED retired, sign-in route trap, upstream's own tests broken (2026-08-18)

96 commits (`aa327f0e9`→`131fc2d8d`, dev tip a few past the 0.8.5-beta tag). Branch `sync0.8.5`,
merge `49c1c4564` + build/test repair `d1cfab795`. 5 conflicts (version block, MainActivity sidebar +
updater wiring, AccountSettingsContent sign-in block, both player files = constructor-param unions).
**No server migration** — zero `.rpc()`/DDL changes in range; the `strip_hdr10plus_sei` settings-sync
exclusion is client-side only. Generated baseline profiles untouched this cycle (no re-sed needed).

- **🛑 Upstream retired `SELF_HOSTED`** (build flag → runtime `ServerConfigurationStore` + server
  discovery). All its deletions auto-merge with **zero conflicts**, and the `NuvioNavHost` hunk flips
  the `AuthSignIn` route from our `AuthSignInScreen` (email/password) to their `AuthQrSignInScreen` —
  dead end for family (QR RPCs are removed server-side). Fix re-applied on the branch with a don't-take
  comment; **re-check that route hunk every cycle now**. Our `BuildConfig.SELF_HOSTED` refs in
  `NuvioNavHost`/`AuthQrSignInScreen` were upstream-rewritten away — do NOT re-add the flag when
  resolving `build.gradle.kts` (take their deletion, keep only our version/identity lines).
  Hardening applied: `FEATURE_CUSTOM_SERVER_CONNECTIONS_ENABLED=false` on the full flavor (their
  default is `true`; the switch-server UI is unreachable in our build, this is belt-and-braces).
  Default path is safe regardless: `ServerConfigurationStore.loadActive()` falls back to our
  compiled-in `SUPABASE_URL`/anon key when nothing custom is stored.
- **Upstream's playstore flavor is broken at their tip**: new subtitle-download code does
  `response.body.bytes()` bare — full flavor resolves OkHttp 5.3.2 (non-null body), playstore stays on
  4.12.0 (`ResponseBody?`). Fixed with `?.bytes() ?: ByteArray(0)`. We keep playstore compiling; they
  don't build it.
- **Upstream's own unit tests don't compile at their tip** (they don't run them): 3 tests stub
  `TmdbSettingsDataStore.settings` with `flowOf` where it's now `StateFlow`, and
  `SearchViewModelConcurrencyTest` wasn't given the new `metaRepository` param. Repaired on the branch
  (stubs → `MutableStateFlow`, pass a relaxed mock) — same "invisible breakage #4" class.
- **Known-failure baseline updated**: pre-merge baseline was 14 (incl. TmdbMetadata ×2 + TraktAuth,
  which the sync fixed). Post-merge 12 = the 11 host-environment stragglers + **1 new:
  `DefaultAllocatorTest.testLateReleasedAllocationsMemoryLeak`** (expects 0 retained, gets 196608).
  Caused by upstream's own `b9172d13f` "Update ExoPlayer AAR" — a vendored media3 test vs their new
  player binary, not merge logic. Verify playback on-device; leave the test red until upstream
  re-baselines it (or fix the expectation when we next understand the new allocator's pooling).

### 0.8.6 + 0.8.7 — supporter perks; theme default moved; no server migration (2026-08-21)

50 commits (`131fc2d8d`→`0cbfb551a`, dev tip at the 0.8.7-beta tag). Branch `sync0.8.7`, merge
`d535f72f3`. 6 conflicts (version block, MainActivity imports+MainUiPrefs+onResume, ThemeDataStore,
ThemeSettingsViewModel, SubtitleTiming, AboutScreen). Player telemetry-hook files auto-merged clean
despite the Bluetooth-audio-route rework. **No server migration** — see below.

- **Supporter perks v1 is the headline.** New `MemberAccessRepository` (Supabase RPC
  `get_my_member_access`, retries 1s/2s/4s then settles on `MemberAccess.None`), supporter themes
  (`ThemeAccess.kt`, `SupporterThemeColors`), profile backgrounds (`get_member_profile_background_catalog`),
  member avatars (`get_member_profile_avatar_catalog`). **All soft-fail against our Supabase** (missing
  RPC → caught → None/empty), so the whole perk surface self-disables — no migration needed. Cost: ~4
  silent 404s per foreground (`refreshIfStale`, 15-min staleness). Optional cleanup: 3 stub RPCs in the
  kevbox repo returning empty would silence the spam. `DebugMemberTierCard` only surfaces via
  `SettingsCategory.DEBUG` (`IS_DEBUG_BUILD`-gated) — no strip needed.
- **🛑 New hardcoded data-sending defaults blanked:** `DONATIONS_*` was replaced by
  `SUPPORTERS_API_BASE_URL` (default `https://nuvio.tv/`) and `SUPPORT_URL` (`https://nuvio.tv/support`)
  in all 3 buildConfig blocks — blanked per the standing URL rule. Plus we set
  **`AppFeaturePolicy.supportNuvioEnabled = false`** on the full flavor (upstream gates the Supporters
  screen route + About row behind it; false = `SupportersApi` never fires, row dead — and our old
  commented-out About-row hack was retired in favor of upstream's own gate).
- **Theme default moved:** upstream made the theme preference nullable (`ThemeDataStore.selectedTheme`
  → `Flow<AppTheme?>`) and moved fallback resolution into the new `resolveAppTheme()` in
  `ThemeAccess.kt`. Our OCEAN flip moved there (both fallbacks; `ThemeAccessTest` updated to expect
  OCEAN). `ThemeSettingsViewModel` keeps `selectedTheme = AppTheme.OCEAN` initial state. **Re-check
  both spots after future syncs that touch theming.**
- **`ProfileSyncService` push now includes `profile_background_id/url`** — inside the entries JSON
  (NOT named RPC args), DTO defaults `null` both directions → our prod functions tolerate it;
  backgrounds just don't persist server-side. Add columns in the kevbox repo only if we ever want
  cross-device background sync.
- **SubtitleTiming conflict was a wash:** upstream made the same nullable-body fix we shipped in
  0.8.4/0.8.5 (`?.bytes()`), theirs with a better error path — took theirs, divergence retired.
- **Test-source dedupe (invisible breakage #4 variant):** upstream fixed the same two broken tests we
  patched last cycle (`86b67be76`) → auto-merge produced a duplicate `MutableStateFlow` import
  (`TmdbCollectionSourceResolverTest`) and a duplicate `metaRepository` ctor arg
  (`SearchViewModelConcurrencyTest`). Both deduped. **When both sides fix the same upstream test break,
  expect silent duplicates, not conflicts.**
- Untouched this cycle: updater package, manifest, `SettingsScreen.kt` (suppressions survive as-is),
  `WatchedItemsSyncService`/`LibrarySyncReducer` patches, generated baseline profiles (no sed),
  `sync_push_*`/`sync_delete_*` signatures. New dep: `supabase-storage` (+`install(Storage)` in
  SupabaseModule — auto-merged). Baseline still 12 known failures (921 tests).
- Emulator-verified: home renders (OCEAN), CW sync live, manual stream picker intact, ExoPlayer
  playback + subtitles confirmed.

### 0.8.8 → 0.8.11 — launcher-icon branding leak; forced-subs divergence retired; no migration (2026-08-30)

304 commits (`0cbfb551a`→`fa8e7266c`, dev tip a few past the `0.8.11-beta` tag `eb30fbf91`). Branch
`sync0.8.11`, merge `e52026f7f`, released as **0.9.11-beta**. 295 files, +22.9k lines, but roughly a
quarter of that is Albanian and Vietnamese translations, so the real code change is moderate. Nine
conflicts: `.gitignore`, `build.gradle.kts` (3 hunks), `MainActivity`, `WatchedItemsSyncService`,
`PlayerSettingsDataStore`, `WatchedItemsPreferences`, both player files, `StreamScreen`.
The player took ~30 commits (parallel range data source, chunked playback for non-faststart MP4s,
Bluetooth audio routing, a subtitle charset rewrite, a post-play recommendations overlay).

- **🛑 Launcher-icon branding leak (the real find this cycle).** Upstream moved the launcher entry off
  `MainActivity` onto six new activities under `com.nuvio.tv.launcher` and added an icon picker in
  Settings → Appearance. Our `full` flavor only overrides `ic_launcher` + `banner`, so the five
  alternates (arctic blue, emerald, rose gold, copper, graphite) are **Nuvio artwork with no KevBox
  override** and would put a Nuvio logo on a family TV's home row. Fixed by hiding the picker row:
  `SHOW_LAUNCHER_ARTWORK_PICKER = false` in `ThemeSettingsScreen.kt`, same pattern as
  `SHOW_SYNC_OVERVIEW`. **Add to the standing post-sync grep list.** Reassuring detail found while
  checking: the five alternates ship `android:enabled="false"`, so only the KevBox tile ever appears;
  the picker was the *only* leak. Nothing else sets `showAppIconDialog`, the choice is not in the
  synced profile-settings blob, and no startup path applies an alternate. Note `MainActivity` lost its
  `MAIN`/`LEANBACK_LAUNCHER` filter (it now lives on `.launcher.AppIconDefault`) — Android TV home
  tiles can duplicate or vanish when the launcher component changes across an update, so **verify the
  tile still works after installing over an existing build**.
- **`PlayerSettingsDataStore`: one divergence retired, another nearly lost silently.** Upstream
  independently adopted `?: false` for `subtitleUseForcedSubtitlesKey`, so that flip is **retired** —
  take theirs. But the same conflict hunk also carried our `secondaryPreferredLanguage ?: "fr"`
  (French secondary subtitle default, from `2d5a321f6`), which upstream's side does **not** have.
  Taking their block wholesale would have silently reset it for every new install. Re-applied with a
  `KevBox FORK DIVERGENCE` comment. The file now differs from upstream by exactly the three install
  defaults from that commit: `autoSwitchInternalPlayerOnError ?: true`,
  `secondaryPreferredAudioLanguage ?: "fr"`, `secondaryPreferredLanguage ?: "fr"`.
  **Lesson: "take theirs wholesale" is only safe once you've diffed the whole hunk, not just the line
  that motivated it.** Also note the preferred-language default moved from a hardcoded `"en"` to the
  device locale via the new `SubtitleLanguageOption.DEVICE`.
- **`WatchedItemsPreferences`: upstream converged on our rev-4 Option B, so the default flipped.**
  Upstream dropped its own `if (lastSuccessfulPushMs > 0L)` gate — a device that never pushed cannot
  read remote absence as a deletion — and added `WatchedItemsPullPreservationTest` to pin it. That
  test fails against our opt-in flag. Resolution: keep our tested `unionWatchedSnapshot` helper and the
  `unionWhenNeverSynced` parameter, but **flip its default `false` → `true`**. One word, and it keeps
  both test suites green: upstream's new test passes, `SyncMergeLogicTest` still passes because it sets
  the flag explicitly on both sides, and the restore path is unchanged. Only two callers exist:
  `pullSnapshotFromRemote` (explicit `true`) and `TrackingSourceController.repopulateWatchedItemsFromNuvioSync`
  (now stops discarding local marks when a member switches their watch source back to cloud sync).
  Also take upstream's `setLastSuccessfulPushMs` → `advanceLastSuccessfulPushMs` rename (monotonic
  `maxOf`, so out-of-order pushes can't lower the point) and per-profile `syncPointFor(profileId)`.
- **🛑 New hardcoded URL default blanked: `DEVICE_LOGIN_WEB_BASE_URL`** (upstream ships
  `https://nuvio.tv/link`), in **all three** buildConfig blocks. The field must *exist* because
  `ServerConfigurationStore.kt:70` reads it, but only the QR device-login flow consumes it and we use
  email/password. Same standing rule that caught `SUPPORTERS_API_BASE_URL` last cycle: the merge
  auto-adds the `defaultConfig` copy with no conflict, so **blank all three, not just the two in the
  conflict**.
- **No server migration.** The complete `rpc(...)` inventory across `app/src` gained exactly two names
  versus upstream's base: `get_access_verdict` (ours, already live) and `get_my_membership_overview`
  (supporter perks, soft-fails like `get_my_member_access`). One new named arg, `p_device_type`, goes
  to `start_device_login_session` — the QR path we don't use, and it already handles a missing function.
  No new `sync_push_*`/`sync_delete_*` wire keys. The new `BackendRateLimit` OkHttp plugin only reacts
  to HTTP 429 and 503, so our supporter-RPC 404s never trip it.
- **Invisible breakage #4, third occurrence.** Upstream's new `SearchViewModelSuggestionsTest` has an
  `AddonRepository` fake missing our two KevBox-only members (`applyRemoteAddonConfig`,
  `resetPrimaryAddonsToDefaults`), which broke the **whole test source set** while
  `compileFullDebugKotlin` stayed green. Fixed with `error("unused")` overrides. **Standing check that
  catches it cheaply:** `grep -rln ": AddonRepository" app/src/test | while read f; do grep -q applyRemoteAddonConfig "$f" || echo "MISSING: $f"; done`
- **⚠️ Check gradle's output, not the shell exit code.** `./gradlew … 2>&1 | tail` reports `tail`'s
  exit status, so a failed build looks like success. The verify block below is written as piped
  commands; run them with `set -o pipefail` or grep the output for `BUILD FAILED`.
- **Known-failure baseline is now 13** (1103 tests, 1 skipped): the 12 from 0.8.5, plus
  **`TrackSelectionInvestigationTest.testBuildStreamInfoDataWithActiveVideoFormat`**. That one is
  upstream's own test against upstream's own new stats-overlay code — both files are byte-identical to
  their tip, and it's a mockk gap (an unstubbed `playbackTimeline` on a relaxed mock hits
  `PlayerBitrateEstimator.fileBitrateBps(..., playbackTimeline.value.duration)` and throws
  `ClassCastException`). Not merge logic, not a playback bug. Same class as the 0.8.4/0.8.5 finding
  that upstream doesn't run its own tests.
- **Divergences confirmed surviving, verified by diffing each file against `fa8e7266c`** rather than by
  assumption: every buildConfigField upstream added is present in all three blocks (none dropped);
  `MainActivity` passes byte-identical argument sets to both sidebar scaffolds and an identical
  `handleExitApp`; `StreamScreen` differs only by the external-stream swap; the sync service by one
  line. Also re-checked: the **forced stream picker** (`manualSelection = true` ×2 in `NuvioNavHost`,
  from `fd33dc3f9`) survived, and upstream's new `StreamAutoPlayPolicy` cannot override it because
  `manualSelection` short-circuits before the settings path runs.
- **Take from upstream in `MainActivity`:** the confirm-exit toast (`handleExitApp` +
  `confirmExitEnabled` + `backPressedOnce`) and the new `longPressBackHeld` param, which both scaffolds
  now require. **Drop** upstream's `UpdateBannerHost` wrapper and its `Box` — that's the 0.8.1 updater
  callout, still live — and keep our `UpdatePromptDialog` gate below it.
- Untouched this cycle: the **updater package** (upstream didn't touch it, no re-fight), generated
  baseline profiles (no sed needed), `SettingsScreen.kt` suppressions, `LibrarySyncReducer` patch,
  `AndroidManifest` beyond the launcher move. The only new exported components are the six launcher
  activities. `gradle.properties` heap went 4096m → 6144m + 1024m metaspace (upstream's change).
- **⚠️ `.gitignore` now ignores `docs/`** (taken from upstream) while the repo tracks eleven files
  under it, including the superpowers plans. Tracked files keep working, but a **new** file written to
  `docs/` is silently skipped by `git add`. Add `!docs/superpowers/` if that bites.
- Not emulator-verified this cycle: build + install + launch succeeded on `kevbox_tv`, but real
  playback was **not** exercised. Given ~30 upstream player commits, **play a stream on a real TV
  before trusting this build**.

### 0.8.12 → 0.9.2 — Play gate, updater channels, never-synced union, splash wordmark (2026-09-12)

301 commits (205 non-merge, `fa8e7266c`→`e54a74904`, dev tip = the `0.9.2-beta` tag, cut the same day).
Branch `sync0.9.2`, merge `719a1fa19`, docs `06588e6df`, released as **0.10.2-beta / versionCode 1050**
(bump `d39e9e479`, sha256 `bf9d0811…`, 70 MB; the big push was pre-staged through a temp ref, no 408). 254
files, +22.6k/-4.8k. Sixteen conflicts: build.gradle (version
line only), updater ×4 (`UpdateBanner.kt` modify/delete), `MainActivity` (4 hunks), `NuvioNavHost`,
`PlayerRuntimeControllerInitialization`, `WatchedItemsSyncService` + `WatchedItemsPreferences`,
`AddonRepositoryImpl`, `AboutScreen`, `ThemeSettingsViewModel`, `StreamScreen`, `strings.xml`, playstore
`UpdateViewModel`. **No server migration**: the whole-app `rpc()` inventory gained one name,
`sync_copy_profile_setup` (copy settings into a newly created profile, wrapped in `Result.failure` →
the profile still gets created); the `p_keys` argument only moved into `deleteKeysFromRemoteLocked`.
Manifest, `.gitignore`, Gradle toolchain and generated baseline profiles untouched; the one dependency
bump is haze 0.7.3 → 1.7.2. Player took 43 files / +1.7k lines (Hi10P software fallback, VC-1 error
surfacing + "switch to MPV", in-memory moov cache for non-faststart MP4, sidecar subtitles, HLS 404
rendition fallback, ASS styling under libass, cross-domain subtitle headers).

- **🛑 New Play gate re-created the 2026-08-30 bug through a new door.** `PlaybackAvailability.canStream`
  (called from `NuvioNavHost` ×3, `MetaDetailsScreen` ×4, `ContinueWatchingSection`) reads
  `addonRepository.getInstalledAddons()` and refuses Play with a toast when no enabled addon lists a
  stream resource. Upstream's own new placeholder addons (emitted when a manifest cannot be fetched or
  has not been fetched yet) carry no resources, so the first Play after a sign-in toasts "Playback isn't
  available for this title with your current setup" instead of reaching the stream screen and our
  `awaitResolvedInstalledAddons` wait. A signed-out member gets the same toast instead of the "sign in"
  wording. Both are member-facing regressions with zero conflict markers (the call sites auto-merge).
  Fix = `allowUnverifiedPlayback` on the data class, set only by the provider composable (see table);
  upstream's two gate tests keep passing because they construct the class directly.
- **🛑 Upstream's updater moved into shared code.** Stable/beta channels: three new files under
  `app/src/main/java/com/nuvio/tv/updater/` and an `UpdateChannelSettings` composable in Settings that
  calls upstream's `UpdateViewModel` API. Deleted all of them plus their two tests; `AboutScreen` keeps
  our single check-for-updates row. Standing rule unchanged: nothing from upstream's updater.
- **⚠️ Watched-history restore lost its never-synced protection.** Upstream's pending-mutation store is
  the right model, but it starts empty and `pullSnapshotFromRemote` dropped the timestamp argument, so
  local marks that were never pushed (or were marked on the old build before upgrading) would vanish on
  the first snapshot. Kept the rev-4 union as an opt-in flag the sync service sets only when the
  profile's last successful push is 0 (see table). `WatchProgressSyncService` has the same shape but no
  KevBox divergence — continue-watching entries are always pushed promptly, so left as upstream.
- **⚠️ Branding: the splash draws the theme's wordmark.** `ThemeBranding.kt` already mapped
  GOLD/JADE/ROSE_GOLD/ARCTIC_BLUE/GRAPHITE to Nuvio wordmark PNGs with no full-flavor override (checked
  the gold one: Nuvio's play triangle), which the profile screen has shown all along and the new
  startup splash now shows on every launch. Fixed with five `<bitmap>` alias drawables in
  `app/src/full/res/drawable/` — zero-divergence, survives merges. Same lesson as the icon picker: the
  policy sweep includes "which drawable does a new screen reach for".
- **🛑 `git checkout --ours app/build.gradle.kts` is a trap** (new to this cycle's notes, see the table
  row): the conflict was one hunk, but "ours" is whole-file and dropped the haze bump → `Unresolved
  reference 'hazeEffect'` across `MainActivity`, `StreamScreen`, `ModernSidebarBlurPanel`. Caught by the
  compile gate, fixed by re-applying the two upstream lines; `git diff upstream/dev -- app/build.gradle.kts`
  is now the check. The updater files are the ONLY ones where whole-file "ours" is correct by design.
- **Retired / converged:** upstream added the same `@Singleton` to `AddonRepositoryImpl` (cf7ee078d);
  upstream already removed the `STATE_ENDED` scrobble call our comment guarded (it was gone at the
  0.8.11 base — the comment was stale); forced-subs / libass / strip-SDH flipped to `true` only in the
  `PlayerSettings` data-class defaults while every stored-preference fallback stays `?: false`, so no
  behaviour change for installs and our three "fr"/auto-switch defaults are intact.
- **ℹ️ TLS is now validated on first-party traffic.** `NetworkModule`'s default `OkHttpClient` lost the
  trust-all socket factory; addon/Retrofit traffic moved to a `@Named("addonPermissive")` client, and
  the HTTP cache dir became `http_cache_v2`. Our updater (`ApkDownloader`, `UpdateRepository`) injects
  the default client, so tv.kevbox.dev must keep a valid chain (Let's Encrypt, checked) — **verify an
  update download on a real TV after this release.** `Checksum.kt`'s "trust-all" comment is now wrong
  in spirit but harmless.
- **ℹ️ Simkl replaced the ARM anime-ID lookup** for skip-intro (`SimklIdResolver`, existing
  `SIMKL_CLIENT_ID`); no new URL surface. `DEVICE_LOGIN_WEB_BASE_URL` and friends unchanged upstream.
- **Invisible breakage #4, fourth occurrence:** new `SearchViewModelPaginationTest` fake lacked the two
  KevBox `AddonRepository` members. `StreamRepositoryPluginIsolationTest` uses a mockk mock, not a fake,
  and kept its `awaitResolvedInstalledAddons` stub + `authManager` ctor arg.
- **Known-failure baseline is now 12** (1268 tests, 1 skipped): ten of the previous thirteen still fail
  (one ExoPlayer-tier straggler now passes), plus **two new upstream-own failures**
  `PostPlayRecommendationStateTest.{loaded recommendation holds natural completion until overlay evaluation,
  post play returns to player only while its window is available}` — test and source byte-identical to
  upstream's tip, same class as `TrackSelectionInvestigationTest`. `ThemeAccessTest` needed the OCEAN
  flip; `AddonRepositoryResolvedAddonsTest` flaked once under suite load and got wider budgets.
- **Verified:** `compileFullDebugKotlin` green (after the haze fix), `testFullDebugUnitTest` at the
  12-failure baseline, `assembleFullRelease --dry-run` green, all standing greps pass, every anchored
  divergence diffed against `upstream/dev` (PlayerSettingsDataStore, StreamRepositoryImpl, ThemeAccess,
  NuvioApplication, ThemeSettingsScreen, SettingsScreen suppressions, manifest identical to kevbox).
  Emulator: full-debug installs and launches to the KevBox email gate, no crash, updater worker runs.
  There is no test account on this box, so the automated run stops at the gate; Kevin signed in and
  played a stream on the emulator by hand before giving the go for the release. Keep doing that: the
  player churn alone warrants it every cycle.

### 0.9.3 → 1.0.0 — the light cycle; a fork race surfaced through the test suite (2026-09-20)

108 commits (67 non-merge, `e54a74904`→`8f5e9a963`, dev tip two past the `1.0.0` tag; 0.9.3/0.9.4/0.9.5-beta
and 1.0.0 were cut in one week and 1.0.0 is a version bump, not a rework). Branch `sync1.0.0`, merge
`ef6695c08`, fork fix `bb00bf53b`, released as **1.1.0 / versionCode 1051**. 95 files, +3.2k/-0.4k.
**One conflict** (the version line; resolved the hunk only, `git diff upstream/dev -- app/build.gradle.kts`
shows only the known KevBox lines). Seven auto-merges over KevBox-edited files, all additive one-liners
or new enum values nowhere near our lines. **No server migration** (zero `rpc()` changes, `core/sync/`
untouched), no new URL field, manifest / updater / `SettingsScreen` / `NuvioNavHost` / DI / launcher /
`.gitignore` / generated profiles untouched; every standing grep passed unchanged. Upstream content: RTL
layout fixes (many), movie post-credits skip (inert, `INTRODB_API_URL` blank), subtitle credential scoping
(`PlayerSubtitleDataSource`: stream headers no longer follow a subtitle fetch to another host), nested-MKV
seek + truncated-tail fixes, ffmpeg downmix fix as a new prebuilt aar (still carries `armeabi-v7a`,
checked), stream list paginated 100 rows at a time, transparent player window so HDR letterbox bars stay
black, Rotten Tomatoes icons (needs an MDBList key), TVDB option in Simkl settings (hidden by `TRACKING`),
upstream's own CI workflow files (inert for us).

- **⚠️ The suite went 12 → 13, and the 13th was ours.** `AddonRepositoryResolvedAddonsTest` failed on
  every full-suite run after the merge, passed alone, passed paired with each new upstream test class and
  in every package partition, and still failed with all eight new upstream test files moved aside. So
  upstream's tests were innocent, and upstream's code never touched `AddonRepositoryImpl` either; the merge
  only shifted thread timing. The tell was the duration: **3 ms**, returning `[]`. A timeout would have
  taken 30 s. Root cause: `awaitResolvedInstalledAddons` judged "resolved" by reading `manifestCache`,
  which `fetchAddon` and the disk load both fill *before* `installedAddonsFlow` publishes the matching list.
  Evaluated in that gap, the predicate is true on the initial empty StateFlow value, so the caller gets
  `[]` at once, which is the exact partial-list bug the method was written to prevent (a microsecond window
  in production, deterministic under suite load). The 0.9.2 note that widened this test's budgets for "IO
  starvation" had misread the same race. Fixed test-first (`bb00bf53b`): the predicate now judges the
  published list, and a new test pins the ordering with a shared `StandardTestDispatcher` plus a pre-filled
  disk cache so `init` fills the cache before the flow's collectors run; it failed RED with the suite's
  exact message. **Lesson: a new full-suite failure in a kevbox-only test is still ours to root-cause even
  when the merge did not touch its subject. Run it alone and read the duration in the XML report: a fast
  wrong answer is a race, a slow one is a timeout.**
- **Version rule for the 1.x line:** upstream 1.0.0 → KevBox 1.1.0 (still one minor ahead; `-beta` dropped
  with upstream's). Upstream's versionCode is 1062 and ours 1051; unrelated, the updater only reads ours.
- **Emulator:** full-debug launched to the signed-in home (Continue Watching populated, OCEAN), one
  `Loaded N cached manifests from disk` line (single repository instance), zero 429s, `MemberConfigService`
  applied 7 rows. Manual picker enforced; Kevbox returned 29 streams. ExoPlayer refused the 10-bit HEVC
  file on the emulator's software decoder (`NO_EXCEEDS_CAPABILITIES`, an emulator limit), the
  `autoSwitchInternalPlayerOnError` default handed it to MPV and video played; letterbox bars rendered
  true black. 131 addon subtitles fetched, the internal English track auto-selected. **Not exercised:**
  ExoPlayer's new subtitle data source (the credential-scoping change), because HEVC forced MPV; check
  subtitles on a real TV with an H.264 stream. Two log lines seen and judged harmless, both in files the
  merge did not touch: `AccessControlService: applyUnknown grace evaluation failed:
  LeftCompositionCancellationException` at startup (a cancellation caught by the fail-open branch; the
  check reruns on resume) and `AUTO_SUB stop: user explicitly selected current subtitle` every 500 ms
  under MPV (log noise).
- **Known-failure baseline stays at 12** (1320 tests, 1 skipped), the same list as 0.9.2.

### 1.0.0 → 1.1.0-beta.2 — first sync onto a pre-release; a new play path skipped the picker (2026-09-25)

216 commits (152 non-merge, `8f5e9a963`→`d8c500175`, exactly the `1.1.0-beta.2` tag). GitHub marks both
1.1.0 betas as pre-releases, the first time we merged one; Kevin chose to take it after an emulator test.
Branch `sync1.1.0-beta.2`, merge `0372ac1d4`, **merged into kevbox but not released** (version still
1.1.0 / 1051; `release.sh` bumps it). 295 files, +22k/-2.7k, of which about 4.4k are translations and 7k
tests. **Three conflicts**, all in the table: the version block (upstream added
`testInstrumentationRunner`, take that line), the `LibraryScreen` header, and `UpdateBanner.kt`
(modify/delete). **No server migration**: zero `rpc()` changes; `ProfileSettingsSyncService` only adds a
`plugin_settings` key inside the existing settings blob. No manifest change, no new hard-coded URL field.

- **🛑 Policy leak: nested details auto-play.** Upstream moved Cast / Studio / similar-title navigation
  into a new child host (`DetailChildHost.kt`) that draws its own `MetaDetailsScreen` and wires Play to
  `navigateToDetailStream` with `manualSelection = false`. A member who opens a film from a cast list and
  presses Play would skip the stream picker. Zero conflict, compiles, and every old standing grep stayed
  green, because the old six `manualSelection = true` lines are all still there. Fixed in the merge commit
  (table row above). **Lesson: grep for every `Screen.Stream.createRoute` / `navigateToDetailStream`
  call site after a sync, not just count the known ones.**
- **Inert new surfaces.** MDBList becomes a full tracker (OAuth device login, library, scrobble). It needs
  `MDBLIST_CLIENT_ID`, which we leave unset (blank = "unavailable in this build"), and its sign-in lives in
  `TrackingSettingsScreen`, hidden by `TRACKING -> false`. Every MDBList service checks
  `isAuthenticated` before a network call. Simkl "More like this" sits under the same hidden screen.
  **Custom Poster Source** is a new row under Settings → Layout, which we show: blank by default, set from
  a phone through a local config page on port 8092 (same pattern as the stream-badge server). Harmless;
  hide it if a member ever gets confused by it.
- **Player memory retune** (about 30 commits, three reverts in the same week): target buffer default
  150 → 50 MB, back buffer 15 s → 0, lower native-memory tiers, plus one-time migrations that rewrite stored
  buffer prefs on existing installs. Upstream's own `NuvioExoPlayerPerformanceHelperTest` 1 GB / 2 GB tier
  tests fail against the new tiers. Watch real 32-bit TVs after release for rebuffering.
- **Tests:** 1663 run, 17 failures. That is **exactly** the set upstream's own tip fails when run alone
  (1606 tests, 17 failures, compared name by name), so none are ours. Against our old baseline of 12: two
  old failures now pass, and seven new ones are all upstream-own: `CatalogRepositoryTypeTest` (mockk stub
  missing `getCustomPosterEnabledScreens`), `CustomPosterUrlResolverTest`, `NuvioExoPlayerPerformanceHelperTest`
  ×3 (tier values), `PluginBinaryFetchTest` ×2 (`NoSuchMethodException`, reflection on a changed
  signature). **New baseline: 17.**
- **Emulator:** existing signed-in install upgraded in place, home rendered (OCEAN, Continue Watching
  populated), one `Loaded N cached manifests` line, `MemberConfigService` applied 7 rows, zero 429s. Main
  Detail Play showed the picker; an H.264 WEB-DL (Torrentio via Premiumize) played on stock ExoPlayer for
  90 s+ with the buffer steady at 50 s ahead; addon English subtitles loaded (933 cues) and Kevin confirmed
  them on screen. No telemetry failure logged (the repository logs only failures). Pre-existing noise:
  `PluginSyncService` "Could not find the table public.plugins" (we never created it; file untouched).
  Not exercised on a device: the nested-detail Play fix (covered by code review), MPV fallback, real
  32-bit TV memory.

### 1.1.0-beta.2 → 1.1.0-beta.4 — settings rebuilt, TorrServer replaced, P2P blocked (2026-10-05)

151 non-merge commits: the `1.1.0-beta.4` tag plus two engine fixes that landed an hour later
(`e9e3e40cd` engine 0.1.4, `19ecc9bf9` cache path), so `upstream/dev` = `19ecc9bf9`. Still a GitHub
pre-release. Branch `sync1.1.0-beta.4` off the unreleased beta.2 merge; merge `519ad4b42`, fork fixes
`023b08372` and `b0ec37672`. **Released as KevBox `1.2.0-beta.4` / versionCode 1052** (bump `9a750be18`,
sha256 `89d008c3…`, 52.9 MB armeabi-v7a), shipping the beta.2 and beta.4 merges together. The 441-commit
push went through cleanly after staging upstream's commits on a temporary branch first.

Subtitle AutoSync (new in this range) stays **off by default** on purpose (Kevin, 2026-10-05). It can
only fix timing by comparing against subtitles built into the video, shows a "Sync failed" message
whenever there are none (the South Park test file had zero), and it is two weeks old upstream. If it is
ever turned on, flip the `false` defaults in `AutoSyncPreferences.kt` and hide its failure messages on
KevBox in the same change.

**Three conflicts** (version hunk, `AboutScreen` Licenses row = keep ours,
`SettingsScreen`). **No server migration**: zero `rpc()` changes, `core/sync` untouched, no new
`buildConfigField`, manifest unchanged.

- **🛑 Settings rebuilt, and taking upstream's side brings hidden categories back.** Upstream moved
  category visibility into `SettingsCatalog.visibleSettingsCategories()` and grouped the rail. Our side of
  the hunk no longer compiles; upstream's side compiles and shows Addons, Plugins, Tracking and Profiles.
  Resolved with `KEVBOX_HIDDEN_SETTINGS_CATEGORIES` (table row). The old standing grep for
  `CONTENT_DISCOVERY ->` would have passed on the broken resolution, because upstream's own spec lines
  match it; replaced.
- **🛑 TorrServer replaced by the Nuvio Engine** (`app/libs/lib-nuvio-engine-android-0.1.4.aar`,
  libtorrent, GPL-3). Has armeabi-v7a (7.5 MB `.so`), loads its library only when first created, adds no
  permissions or services. It joins the public DHT, asks the router for a port (UPnP/NAT-PMP), broadcasts
  on the LAN and adds 20 hardcoded public trackers, but only while it runs. Upstream's new "Clear torrent
  cache" row starts it with P2P off. Family streams are Torrentio + Premiumize with `nodownloadlinks`, so
  KevBox now blocks P2P outright (table row). Side effect worth having: the 32-bit release APK dropped from
  ~73 MB to **52.9 MB** (`libtorrserver.so` was 24.7 MB compressed).
- **Two older gaps closed in the same branch** (both predate this sync, separate commit `b0ec37672`): the
  player's Sources/Episodes panels opened AIOStreams info cards with a browser intent (Downloader
  hijack), and "Start from beginning" plus the TV home-screen launches skipped the picker when a member
  had turned on "Auto-play first source" or "Reuse last link". Table rows above.
- **Inert or off by default:** episode shuffle (every entry point goes through the forced picker),
  preload next-episode sources (off; one extra cached search per episode when on, the only new stream
  request call site), background trailers (off), subtitle AutoSync (off, on-device), YouTube-id streams
  (same extractor as trailers), MDBList ratings on the hero (need a key). IMDb episode ratings now run on
  Continue Watching cards with no setting, but `IMDB_TAPFRAME_API_BASE_URL`/`IMDB_RATINGS_API_BASE_URL`
  are unset, so they hit `http://localhost/` on the TV and fail quietly. **If those two URLs are ever
  set, every member's watched-show IMDb ids go to that host.**
- **Tests: 1899 run, 2 failures**, both long-known (`LocalhostZeroCopyDataSourceTest.testHttpError404`,
  `DefaultAllocatorTest.testLateReleasedAllocationsMemoryLeak`), files byte-identical to upstream. Upstream
  fixed its own stale tests (`2f44fa38f`, `c974b5a39`, `4bdd2abba`), so **the baseline drops from 17 to 2**.
  Any third failure after the next sync is worth a look.
- **Gates:** `compileFullDebugKotlin`, `assembleFullRelease --dry-run`, and a full signed R8
  `assembleFullRelease` (cert SHA-256 `9A:E0:71:8C…1D:6F`, matches) all green. All standing greps pass; 40
  `KevBox FORK DIVERGENCE` markers (27 before).
- **Emulator:** upgraded in place over the signed-in beta.2 build, home in OCEAN with Continue Watching
  filled. Settings rail = Account, Appearance, Layout, Playback, Integrations, Advanced, About; Playback
  ends at Buffer & Network (no P2P). A Continue Watching card opened the picker; an x264 WEB-DL via
  Torrentio/Premiumize resumed at 14:13 and played 80 s+ on ExoPlayer with the buffer ~50 s ahead and
  subtitles on screen. Not exercised on a device: the start-from-beginning and Watch Next picker fixes
  (needs a member setting changed, which would sync to Kevin's real TVs), the player-panel info-card fix,
  MPV fallback, real 32-bit TV memory.

### 1.1.0-beta.4 → 1.1.0-beta.5 — the light cycle; a picker bypass that upstream closed itself (2026-10-08)

38 non-merge commits (61 with merges) from `19ecc9bf9` to the `1.1.0-beta.5` tag (`6adf0251b`, still a
GitHub pre-release). The two engine commits in the beta.5 notes (engine 0.1.4, cache path) were already in
`1.2.0-beta.4`. Branch `sync1.1.0-beta.5` off `kevbox` at `1.2.0-beta.4.1`; merge `0fe0da071`.
74 files, +1961/-823.

**One conflict** (version hunk, hunk only). **No server migration**: zero `rpc()` changes, `core/sync`
untouched, no new `buildConfigField`, manifest unchanged. `MainActivity` and `ThemeSettingsScreen` changed
only to add screen-reader labels. Both 2026-10-06 startup-lag divergences survived the auto-merge.

- **⚠️ Next-episode binge match nearly skipped the picker.** `d293973bd` added an "early binge group
  match" in `StreamScreenViewModel` that picks a stream on every emission, guarded only by
  `!resolvedAutoPlayTarget`. With `manualSelection = true` we set `autoPlayHandledForSession = true`, and
  that commit never read it, so a member with "Reuse binge group" on would have had the picker skipped.
  Upstream's own follow-up `2c5554ecf` (same day) added `!autoPlayHandledForSession` to the guard, which
  closes it. Both are in beta.5, so nothing to change. **If a later sync touches `earlyBingeGroupMatch`,
  confirm the guard still reads `autoPlayHandledForSession`.** The same commit also makes binge-group
  reuse ignore the auto-play source/addon filters (it searches all streams); harmless for us.
- **Tests: 1940 run, 3 failures.** The two known ones plus
  `StreamAutoPlaySelectorTest > bingeGroup-first respects source and addon plugin filters`. Upstream's own:
  `d293973bd` changed the selector to ignore source filters on purpose and left the test expecting the
  filtered result. Selector, test and `AppFeaturePolicy.pluginsEnabled` (true) are identical to upstream,
  so their tree fails it too. **Baseline is now 3.**
- **Player:** new `lib-exoplayer-release.aar` (calloc buffer allocation, fixes SIGSEGV), `isLive` moved to
  `PlayerUiState`, Watch Next updates deferred until playback stops, AutoSync parallelism capped and the
  "AutoSync really off" fix (`0a20eb1fe`, we keep AutoSync off), HTTP/2 one connection per parallel
  connection, Coil timeout 8 s.
- **Inert for us:** MDBList list sorting / external lists in the library (no MDBList key), accessibility
  labels, translations (`values-he` renamed to `values-iw`).
- **Gates:** `compileFullDebugKotlin`, `assembleFullRelease --dry-run` green. All standing greps pass; 46
  `KevBox FORK DIVERGENCE` lines. The hardcoded `TV_LOGIN_WEB_BASE_URL` (`nuvio.tv/tv-login`) defaults
  predate this sync and only feed the dead QR sign-in.
- **Emulator:** debug build installed over the existing signed-in one, home in OCEAN with Continue
  Watching filled. Detail → Play opened the picker; an x264 HDRip via Torrentio/Premiumize played on
  ExoPlayer from 0 to 84 s with subtitles on screen, no crash, no MPV fallback.

## Verify before shipping

> **Run these with `set -o pipefail`.** Piping gradle into `tail`/`grep` reports the *pipe's* exit
> status, so a failed build reads as success (bit me in 0.8.11). Either use `pipefail` or grep the
> output for `BUILD FAILED` before believing it.

```bash
./gradlew :app:compileFullDebugKotlin   # quick compile check — REQUIRED, catches most "invisible breakage"
./gradlew :app:testFullDebugUnitTest    # unit tests — REQUIRED, catches test-source-set breaks the app
                                        # compile can't see (see "invisible breakage #4" + its
                                        # known-failure baseline; only NEW failures implicate the merge)
                                        # (also runs Hilt/KSP → catches missing/duplicate DI bindings)
./gradlew :app:assembleFullRelease --dry-run   # ALSO configure the RELEASE side — debug compile misses
                                               # release-only breaks (see "release-only breakage" callout)
# then build + smoke-test the actual app:
./gradlew :app:assembleFullDebug
adb install -r app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk   # emulator ABI = x86_64
# (or just run the emulator skill: bash ~/.claude/skills/run-emulator/scripts/run.sh — handles the AMD-GPU traps)
```

Smoke test on an emulator/TV before publishing: confirm the app **launches**, the home screen renders
with the **OCEAN** theme, and — most important when upstream touched the player — **actually play a
stream**. Compile-green does NOT prove playback; the player is the area upstream changes most, so a
real playback test is the one check worth doing by hand before pushing to family TVs.

**On a heavy cycle, verify in parallel before committing.** The 0.7.16 sync compiled green after the
markers were resolved, yet still hid **three** compile/DI breaks (dropped `resolveLocalProperty`, dropped
`hiltViewModel` import, and the whole `SyncBackendSupabaseProvider`→`Postgrest` reversal). Fanning out a
handful of focused read-only review agents — one per risk area (build.gradle+DI, account screens,
watched-items union, player files, policy/manifest) — caught all three *before* the compile even ran. For
a 100+-commit cycle that touches the player, DI, and settings, that pre-compile review pass is worth it.

> Heads-up (0.7.16): upstream accidentally committed a stray `Player` **submodule gitlink** (mode 160000)
> at the repo root. It's inert for the Gradle build (not a source dir) — leave it; don't try to `git rm`
> or `submodule init` it unless a later sync cleans it up upstream.

Then `./release.sh …`. Confirm `tv.kevbox.dev/version.json` shows the new versionCode and
`tv.kevbox.dev/download` serves the new APK.

## Hard rules

- **Never push to upstream.** Push only to your fork: `origin` = `github.com/kevinchiha/NuvioTV`.
  (`git push` / `release.sh` already target `origin`; there is no push path to `NuvioMedia/NuvioTV`.)
- **Never change the signing key.** Every release must be signed with `~/kevbox-keys/kevboxtv.jks`,
  or installed apps fail to update with "signatures don't match." Never debug-sign a family release.
- Keep `version.json.versionCode` in lockstep with the gradle `versionCode` — `release.sh` does this
  automatically.
- `/download` always serves **armeabi-v7a** (the family's 32-bit TV hardware); `release.sh` defaults
  to that ABI.

## Getting notified when upstream releases

`nuvio-release-watch.sh` pings an [ntfy](https://ntfy.sh) topic when **NuvioMedia/NuvioTV**
publishes a new GitHub Release — your cue to run the sync flow above. It's stateful (saves the
last-seen tag to `~/.cache/nuvio-release-last.txt`), so a missed run only *delays* the alert and
never re-notifies for a release you've already seen. Install it as a `systemd` user timer:

```bash
# 1. Private ntfy topic (kept OUT of git). Subscribe to this same topic in the ntfy phone app.
echo "NTFY_TOPIC=kevbox-nuvio-$(openssl rand -hex 4)" > ~/.config/nuvio-release-watch.env

# 2. Install the units (copies → clean daemon-reload). Repo is assumed at ~/projects/NuvioTV.
install -Dm644 nuvio-release-watch.service ~/.config/systemd/user/nuvio-release-watch.service
install -Dm644 nuvio-release-watch.timer   ~/.config/systemd/user/nuvio-release-watch.timer

# 3. Enable (linger lets it fire even when not logged in graphically).
loginctl enable-linger "$USER"
systemctl --user daemon-reload
systemctl --user enable --now nuvio-release-watch.timer

# 4. Record the current tag as baseline now (silent — no notification for today's version):
systemctl --user start nuvio-release-watch.service
```

Inspect: `systemctl --user list-timers nuvio-release-watch.timer`, `cat ~/.cache/nuvio-release-last.txt`.
Test the push path: `source ~/.config/nuvio-release-watch.env && curl -d "test" ntfy.sh/$NTFY_TOPIC`.
For an always-on server instead, skip systemd and cron it: `0 8,20 * * * NTFY_TOPIC=… /path/to/nuvio-release-watch.sh`.

## Occasional housekeeping

- If a merge ever gets messy, you can abort and retry: `git merge --abort`.
- Prune old APKs on the VPS to save space: they live in `/var/www/kevbox-tv/` on `persovps`.
