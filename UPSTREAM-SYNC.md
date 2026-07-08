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
and flipped `allowBackup` — see the 0.7.16 callouts).
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
#    fast-forwarded it to your isolation branch first: `git switch kevbox && git merge --ff-only sync0.7.16`).
#    KevBox runs its OWN version line: since 0.7.16 we mirror upstream's patch number into ours
#    (upstream 0.7.16-beta → KevBox 0.8.16-beta) so the base is legible at a glance. Bump YOUR name:
./release.sh 0.8.16-beta "Bug fixes and improvements"
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
| `app/build.gradle.kts` | `applicationId = "tv.kevbox"`, our `versionCode`/`versionName`, `UPDATE_BASE_URL`, `isUniversalApk = false`, debug id `tv.kevbox.debug`; **keep `SYNC_BACKEND_MANIFEST_URL` blank (`""`)** and **leave `NUVIO_SUPABASE_*` blank** (see "remote control plane" callout). **0.7.16 trap:** upstream **rebound `SUPABASE_URL`/`SUPABASE_ANON_KEY` to read the `NUVIO_SUPABASE_*` props** — do NOT take that; keep them reading our `SUPABASE_*` props (our family project). Also **keep the `resolveLocalProperty(...)` helper defined** — the merge dropped it (see "release-only breakage") | new dependencies, SDK/AGP bumps, native/player changes, AND new `buildConfigField`s that our code references — 0.7.16 needs `SENTRY_ENVIRONMENT` and `SUPABASE_FALLBACK_URL` **added to debug+release** (blank-sourced) or `SentryInitializer`/`AuthManager` won't compile |
| `MainActivity.kt` (`onResume`/`onStart`) | our `FEATURE_ACCESS_CONTROL`/`FEATURE_DEVICE_LIMIT` catch-up blocks | take upstream's `requestForegroundSync()` — call it **exactly once**. (0.7.9–0.7.12 wrapped it in a coroutine alongside `syncBackendSwitchService.refreshSelection()`; **0.7.16 removed refreshSelection entirely** — `syncBackendSwitchService` no longer exists in MainActivity — so it's now a bare single call. Either way: one `requestForegroundSync()`, never two) |
| `AccountScreen.kt` / `AccountSettingsContent.kt` | our `EmailPasswordForm` sign-in + `SHOW_SYNC_CODE_FEATURES` / `SHOW_SYNC_OVERVIEW = false` gating + the credential one-tap `LaunchedEffect` (keep it **above** any early-return so it still runs when auth flips to `FullAccount`) | take upstream's other additions, but **drop the read-only "Sync backend" `StatusCard`/`AccountInfoCard` AND the new `DebugSyncBackendSwitchCard`** (the 0.7.12 dev-only "local db switch" — never surface it to family). **0.7.16:** upstream extracted a clean `SignedInAccountSettingsContent` (StatusCard + sync note + sign-out **with a confirmation dialog**, no backend cards) and added an `initialFocusRequester` param the `SettingsScreen.kt` caller now passes — **adopt both** (the signature MUST accept `initialFocusRequester` or `SettingsScreen` won't compile), just gate its sync-overview behind `if (SHOW_SYNC_OVERVIEW)`. Note: `DebugSyncBackendSwitchCard`/`syncBackendName` copies auto-merge in with **no conflict** — grep and delete stragglers (leave the ones in the dead `AuthQrSignInScreen.kt`) |
| `MainActivity.kt` | the `AuthEmailOnboardingScreen` first-run gate | everything else |
| `AddonPreferences.kt` | KevBox `getDefaultAddons()` list + `seedDefaultAddonsOrderIfFirstLaunch()` | other additions |
| `NuvioApplication.kt` | the addon-seed `launch{}` block | other startup changes |
| `app/src/full/java/.../updater/**` | the whole KevBox updater (version.json / SHA-256 / speed+ETA) | only if upstream reworked its own updater |
| `app/src/full/res/**` and new files (`EmailPasswordForm`, `CredentialCrypto`, `LastSignInDataStore`, `DefaultContent`, `Checksum`, `release.sh`) | yours — upstream has none of these | n/a |
| `Theme.kt` | our default `LocalAppTheme = AppTheme.OCEAN` (NOT upstream's `WHITE`) | upstream's new lines, e.g. `LocalNuvioTextStyles` and design-token additions |
| `PlayerRuntimeController.kt`, `PlayerViewModel.kt`, `PlayerRuntimeControllerInitialization.kt`, `...Lifecycle.kt`, `...Mpv.kt` | **keep BOTH** — our `telemetryRepository`/`deviceGuardDataStore` injection + telemetry `launch{}`/`telemetrySessionStarted` reset + the `heartbeatScheduler.stop()` calls (in `onPlayerError`, `STATE_ENDED`, and `releasePlayer`). **EXCEPTION (0.7.12+):** in the `STATE_ENDED` branch, keep our **commented-out** `emitCompletionScrobbleStop(...)` — do NOT take upstream's active call. Trakt completion already fires exactly once from `PlayerRuntimeControllerPlaybackEvents.kt`'s `if (ended && !wasEnded)` path; uncommenting here double-scrobbles (and the fn is idempotent via `hasSentCompletionScrobbleForCurrentItem`, so a stray call is masked — verify by grep, not by testing) | **keep BOTH** — upstream's `streamBadgePresentation`, trakt-CW `launch{}`, `hasMarkedCurrentEpisodeCompleted` reset; **0.7.16:** in `onPlayerError` adopt `error.toDisplayMessage(context)` (replaces our old `buildString` block) but keep our telemetry block above it; in `...Mpv.kt`/`...Lifecycle.kt` these are pure keep-both import/line adds |
| `AboutScreen.kt` | our `if (BuildConfig.FEATURE_TELEMETRY)` §11 privacy-notice block | upstream's added imports + tokenized spacer (`NuvioTheme.spacing.xxs`) |
| `AuthSignInScreen.kt` | our `EmailPasswordForm(...)` sign-in body + `AuthEmailOnboardingScreen` + the `androidx.compose.runtime.*` and **`import androidx.hilt.navigation.compose.hiltViewModel`** imports — **discard** upstream's QR `Button`/`Text` header (we replaced that flow) | nothing here — but **0.7.16 trap:** the import-block auto-merge takes upstream's version (which doesn't use `hiltViewModel`) and **silently drops that import** while our body calls `hiltViewModel()` twice → compile break, no marker (see "release-only breakage" — same class as the `NuvioColors` one). Re-add the import; also drop the now-unused `Button`/`ButtonDefaults` imports |
| `NuvioNavHost.kt` (Settings block) | route the dormant account entry to `Screen.AuthSignIn` (QR retired); **force `onNavigateToAddons`/`onNavigateToPlugins` to no-op `{}`** (see policy-regression callout) | **keep both** — take upstream's new `onNavigateToPlugins` param and any other added route callbacks |
| `SettingsScreen.kt` | **hide the whole `CONTENT_DISCOVERY` category** (`SettingsCategory.CONTENT_DISCOVERY -> false` in the `visibleSections` filter) — it holds only the operator-forbidden Addons + Plugins rows. 0.7.16 rewrote this file heavily (+551 lines) but the one-line suppression survived — **re-confirm it after every sync** | n/a — KevBox never edits this file except to suppress that category |
| `AndroidManifest.xml` (0.7.16) | **keep `allowBackup="true"` + `dataExtractionRules="@xml/data_extraction_rules"` + `fullBackupContent="@xml/full_backup_content"`** — these are KevBox-authored rules that **exclude the access-kill-switch DataStores** (`access_control`/`device_guard`) from cloud backup + device transfer, so a locked-out member can't clone a "last-verified" grace state or duplicate a device id. They only work with backup ON | **discard** upstream's `allowBackup="false"` (it would orphan our exclusion rules) |
| `WatchedItemsSyncService.kt` (0.7.16) | our **rev-4 Option B union** — the first cloud-restore snapshot for a never-synced profile must `replaceWithRemoteItems(..., unionWhenNeverSynced = true)` so it doesn't wipe a family member's existing watch history. Upstream extracted a shared `pullSnapshotFromRemote(...)` helper — **put the `unionWhenNeverSynced = true` INSIDE that helper** so all restore paths inherit it (the flag is a no-op for already-synced profiles, so it's safe universally) | take upstream's delta-cursor resilience refactor (the `try { fetchDeltaCursor } catch { snapshot fallback }`) |

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

## Verify before shipping

```bash
./gradlew :app:compileFullDebugKotlin   # quick compile check — REQUIRED, catches most "invisible breakage"
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
