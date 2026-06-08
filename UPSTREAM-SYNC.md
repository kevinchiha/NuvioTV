# Syncing KevBox TV with upstream NuvioTV

KevBox TV is a private fork of **[NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV)**
(the old `tapframe/NuvioTV` URL redirects here — same repo, renamed). All KevBox changes live on the
**`kevbox`** branch. To get the latest NuvioTV improvements (player fixes, scrapers, etc.) we **merge
upstream into `kevbox`** — we never rebase, and we never push to upstream.

**Why this stays low-effort:** the rebrand is done with `full`-flavor resource overrides
(`app/src/full/res/…`), the Kotlin package namespace is unchanged (`com.nuvio.tv`), and almost all new
logic lives in new files — so upstream's commits rarely touch the same lines we changed. Expect a
~5-minute merge most of the time, with the occasional conflict in `app/build.gradle.kts`.

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
#    commits, and pushes kevbox to your fork):
./release.sh 0.7.6-beta "Synced latest NuvioTV + KevBox changes"
```

That's it. The in-app updater on each TV will then see the new `versionCode` at
`https://tv.kevbox.dev/version.json` and offer the update.

## What conflicts to expect — and how to resolve them

Conflicts are usually limited to **`app/build.gradle.kts`**, occasionally a few `main` Kotlin files.
General rule: **keep the KevBox identity / branding / auth / updater bits; take upstream's feature and
bug-fix code.**

| File | Keep the KevBox side | Take upstream's side |
|---|---|---|
| `app/build.gradle.kts` | `applicationId = "tv.kevbox"`, our `versionCode`/`versionName`, `UPDATE_BASE_URL`, `isUniversalApk = false`, debug id `tv.kevbox.debug` | new dependencies, SDK/AGP bumps, new `buildConfigField`s, native/player changes |
| `MainActivity.kt` | the `AuthEmailOnboardingScreen` first-run gate | everything else |
| `AddonPreferences.kt` | KevBox `getDefaultAddons()` list + `seedDefaultAddonsOrderIfFirstLaunch()` | other additions |
| `NuvioApplication.kt` | the addon-seed `launch{}` block | other startup changes |
| `app/src/full/java/.../updater/**` | the whole KevBox updater (version.json / SHA-256 / speed+ETA) | only if upstream reworked its own updater |
| `app/src/full/res/**` and new files (`EmailPasswordForm`, `CredentialCrypto`, `LastSignInDataStore`, `DefaultContent`, `Checksum`, `release.sh`) | yours — upstream has none of these | n/a |

After resolving, `git add` the files and `git commit` to complete the merge.

## Verify before shipping

```bash
./gradlew :app:compileFullDebugKotlin   # quick compile check
# or a fuller check:
./gradlew :app:assembleFullDebug
```

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

## Occasional housekeeping

- If a merge ever gets messy, you can abort and retry: `git merge --abort`.
- Prune old APKs on the VPS to save space: they live in `/var/www/kevbox-tv/` on `persovps`.
