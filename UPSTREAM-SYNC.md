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
sync hit the player overhaul × our telemetry hooks, plus a repo-wide "design token" theming refactor).
Expect **15–25 min** then, with conflicts in the player files, `Theme.kt`, `AboutScreen.kt`, and
`AuthSignInScreen.kt`. See the expanded table below. The merge markers are the easy part — **the real
gate is the compile check**, because upstream refactors can break our code with *no* conflict at all
(see the "invisible breakage" callout).

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

# 4. Publish a new release (bumps version, builds signed armeabi-v7a, uploads, writes version.json,
#    commits, and pushes kevbox to your fork). KevBox runs its OWN version line, ahead of upstream's
#    (e.g. upstream 0.7.5-beta → KevBox was already 0.8.0-beta). Bump YOUR next number, not upstream's:
./release.sh 0.8.1-beta "Synced latest NuvioTV (0.7.5-beta) + KevBox changes"
```

That's it. The in-app updater on each TV will then see the new `versionCode` at
`https://tv.kevbox.dev/version.json` and offer the update.

## What conflicts to expect — and how to resolve them

General rule: **keep the KevBox identity / branding / auth / updater / telemetry bits; take upstream's
feature and bug-fix code.** When both sides simply *added* adjacent lines (constructor params, init
blocks, reset lines), the answer is almost always **keep both**.

| File | Keep the KevBox side | Take upstream's side |
|---|---|---|
| `app/build.gradle.kts` | `applicationId = "tv.kevbox"`, our `versionCode`/`versionName`, `UPDATE_BASE_URL`, `isUniversalApk = false`, debug id `tv.kevbox.debug` | new dependencies, SDK/AGP bumps, new `buildConfigField`s, native/player changes |
| `MainActivity.kt` | the `AuthEmailOnboardingScreen` first-run gate | everything else |
| `AddonPreferences.kt` | KevBox `getDefaultAddons()` list + `seedDefaultAddonsOrderIfFirstLaunch()` | other additions |
| `NuvioApplication.kt` | the addon-seed `launch{}` block | other startup changes |
| `app/src/full/java/.../updater/**` | the whole KevBox updater (version.json / SHA-256 / speed+ETA) | only if upstream reworked its own updater |
| `app/src/full/res/**` and new files (`EmailPasswordForm`, `CredentialCrypto`, `LastSignInDataStore`, `DefaultContent`, `Checksum`, `release.sh`) | yours — upstream has none of these | n/a |
| `Theme.kt` | our default `LocalAppTheme = AppTheme.OCEAN` (NOT upstream's `WHITE`) | upstream's new lines, e.g. `LocalNuvioTextStyles` and design-token additions |
| `PlayerRuntimeController.kt`, `PlayerViewModel.kt`, `PlayerRuntimeControllerInitialization.kt` | **keep BOTH** — our `telemetryRepository`/`deviceGuardDataStore` injection + telemetry `launch{}`/`telemetrySessionStarted` reset | **keep BOTH** — upstream's `streamBadgePresentation`, trakt-CW `launch{}`, `hasMarkedCurrentEpisodeCompleted` reset |
| `AboutScreen.kt` | our `if (BuildConfig.FEATURE_TELEMETRY)` §11 privacy-notice block | upstream's added imports + tokenized spacer (`NuvioTheme.spacing.xxs`) |
| `AuthSignInScreen.kt` | our `EmailPasswordForm(...)` sign-in body — **discard** upstream's QR/`Text` header (we replaced that flow) | nothing here |

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

## Verify before shipping

```bash
./gradlew :app:compileFullDebugKotlin   # quick compile check — REQUIRED, catches "invisible breakage"
# then build + smoke-test the actual app:
./gradlew :app:assembleFullDebug
adb install -r app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk   # emulator ABI = x86_64
```

Smoke test on an emulator/TV before publishing: confirm the app **launches**, the home screen renders
with the **OCEAN** theme, and — most important when upstream touched the player — **actually play a
stream**. Compile-green does NOT prove playback; the player is the area upstream changes most, so a
real playback test is the one check worth doing by hand before pushing to family TVs.

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
