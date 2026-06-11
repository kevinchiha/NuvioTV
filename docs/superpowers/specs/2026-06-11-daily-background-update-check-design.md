# Daily Background Update Check — Design Spec

**Date:** 2026-06-11 (revised after adversarial gap analysis)
**Branch:** kevbox
**Flavor scope:** `full` only (`playstore` and shared `main` untouched)
**Status:** Approved design, pending implementation plan

## Problem

KevBox TV (the `full` flavor) already checks `tv.kevbox.dev` for a newer build on
**every app launch** (`UpdateViewModel.init` → `UpdateRepository.getLatestUpdate()`) and
shows `UpdatePromptDialog` when one is available. There is **no** periodic or background
check: a TV that stays on with the app foregrounded for days never notices a new release
until the app is relaunched, and a launch-time "Update" still requires a full foreground
download before install.

## Goal

Once per day — independent of whether the app is open — check for a newer build. If one
exists, **pre-download and SHA-256-verify the APK on an unmetered network** so that the
next time the user opens the app, the existing dialog offers an **instant Install** (no
foreground download wait).

## Non-goals (YAGNI)

- **No system notifications** and no `POST_NOTIFICATIONS` permission. Android TV
  notification visibility is unreliable across launchers; surfacing happens through the
  existing in-app dialog on next open/resume.
- **No charging constraint** (TV devices are mains-powered).
- **No check→download worker chain.** A single worker does both; metered-network deferral
  is handled inline (see Error Handling).
- No changes to `playstore` or to the shared `main` `NuvioApplication`.
- **No hardened/cert-pinned updater HTTP client (deferred).** The app's shared
  `OkHttpClient` is intentionally trust-all (it disables TLS cert + hostname verification —
  see Security). Re-routing the updater onto a validating client would touch shared `main`
  DI and is out of scope here. Instead the worker validates the asset URL's **scheme + host**
  before downloading, and authenticity ultimately rests on the **install-time signature
  check** (Security).

## Approach: plain `CoroutineWorker` + Hilt `@EntryPoint` (chosen)

The updater lives in `app/src/full/`, but `NuvioApplication` lives in shared `app/src/main/`.
Rather than make the shared Application implement `Configuration.Provider` and pull
`hilt-work` into both flavors (the `@HiltWorker` path), the `full`-only worker obtains its
dependencies via `EntryPointAccessors.fromApplication(appContext, …)`. This keeps the entire
feature contained in `full`, adds only `androidx.work:work-runtime-ktx` (scoped to the
`full` flavor via the `fullImplementation` configuration), and requires **zero** changes to
`main`/`playstore` and **no manifest edits** (the default WorkManager initializer is kept;
`full` currently has no `AndroidManifest.xml`).

The plain `CoroutineWorker` is instantiated by WorkManager's default `WorkerFactory`
(reflection over its `(Context, WorkerParameters)` constructor) — no custom factory and no
`@HiltWorker` are needed. The app is `@HiltAndroidApp`, the default
`androidx.startup.InitializationProvider` is intact in the merged manifest (no
`tools:node="remove"`, no custom `Configuration.Provider`), and `ACCESS_NETWORK_STATE`,
`INTERNET`, and `REQUEST_INSTALL_PACKAGES` are already declared in `main` — so scheduling,
the connectivity probe, and install all work without manifest changes.

## Components

All new files in `app/src/full/java/com/nuvio/tv/updater/`.

### 1. `UpdateCheckWorker : CoroutineWorker`
The daily job. `doWork()`:
1. If `BuildConfig.IS_DEBUG_BUILD` → `Result.success()` (mirrors the launch-time gate that
   suppresses auto-checks in debug/benchmark builds; **the worker only does real work in a
   `release` build** — see Testing for how that shapes the smoke test).
2. Resolve deps via `UpdateWorkerEntryPoint` (`UpdateRepository`, `ApkDownloader`,
   `UpdatePreferences`).
3. `getLatestUpdate()`. On failure → `Result.retry()` (WorkManager backoff). On success,
   persist `setLastCheckAtMs(now)`.
4. Read the cached pre-download state (`ignoredVersionCode`, `predownloadApkPath`, and the
   decoded `predownloadUpdateJson`'s `versionCode`) and compute `alreadyCached` via the pure
   `UpdateDownloadDecision.isCachedFor(...)` (cached versionCode matches the remote
   `versionCode` **and** the cached file still exists on disk).
5. **URL trust gate:** compute `UpdateDownloadDecision.isTrustedAssetUrl(update.assetUrl,
   BuildConfig.UPDATE_BASE_URL)` — the asset URL must be `https://` and on the same host as
   the configured update server. An untrusted URL (forged/`http://`/off-host manifest) means
   **do not download** (treated as "nothing to do", not a transient error).
6. Download decision (pure `UpdateDownloadDecision.shouldDownload(...)`): with the fetched
   `AppUpdate`, the current `BuildConfig.VERSION_CODE`, the persisted `ignoredVersionCode`,
   `alreadyCached`, and current network metered-ness — decide whether to download. The actual
   download proceeds only when **trusted AND shouldDownload**.
7. If download proceeds:
   - Download via `ApkDownloader.download(update.assetUrl, dest, onProgress)` into the
     shared `cacheDir/updates/<safeAssetName>` directory (same dir the foreground path uses).
     `ApkDownloader` is hardened to stream into a `<name>.part` temp and **atomically rename**
     on success, so a partial or concurrent write is never visible at the final path — this
     removes the worker↔foreground race on the shared filename (both paths benefit).
   - `Checksum.verify(file, update.sha256)`. On mismatch → delete file, prune, `Result.retry()`.
   - On success → persist `predownloadedVersionCode` + `predownloadedApkPath`; the freshly
     downloaded file becomes the one to keep.
8. **Prune (unconditional, every run):** delete every APK in `cacheDir/updates` except the
   one we still intend to install — i.e. the file just downloaded, or an already-cached file
   for a version that is still an upgrade over the current build; otherwise prune everything.
   This runs on **all** non-early paths (download, skip-download, untrusted), so stale APKs do
   not survive a day on which no download happens. Pruning **skips `.part` files** so it cannot
   delete a foreground download that is still in flight.
9. `Result.success()`.

The worker logs (`Log.i`/`Log.w`, tag `UpdateCheckWorker`) at the decision points
(checked / metered-skip / untrusted-skip / downloaded+verified / verify-failed) so the
release smoke test can observe it via logcat without a debuggable build.

### 2. `UpdateWorkScheduler`
`fun ensureScheduled(context: Context)` enqueues two requests against the same worker:

- A 24h `PeriodicWorkRequest<UpdateCheckWorker>` with
  `Constraints { setRequiredNetworkType(NetworkType.CONNECTED) }` (the *check* needs only
  connectivity; metered-vs-unmetered gating for the *download* is decided inside the worker)
  and exponential backoff, via
  `enqueueUniquePeriodicWork("kevbox-daily-update-check", ExistingPeriodicWorkPolicy.UPDATE, …)`.
  **`UPDATE` (not `KEEP`)** so a future change to the period/constraints/backoff propagates to
  already-installed TVs instead of being frozen at the first-ever enqueue; when the request is
  unchanged it is a no-op (no reschedule). Idempotent, safe to call on every app start.
- A one-time `OneTimeWorkRequest<UpdateCheckWorker>` with the same constraints, via
  `enqueueUniqueWork("kevbox-update-check-now", ExistingWorkPolicy.KEEP, …)`. A
  `PeriodicWorkRequest`'s first run is deferred up to the interval (~24h, OS-batched), so on a
  freshly set-up TV the pre-download would otherwise be a day away. The one-time kick runs the
  same worker promptly (as soon as the network constraint is met) so day-1 pre-download works;
  `KEEP` means at most one is in flight. (Day-1 *detection* is independently covered by the
  existing launch-time `checkForUpdates`, which still falls back to foreground
  download-on-demand.)

> **Timing caveat:** WorkManager periodic timing is best-effort under Doze / app-standby. On a
> powered-but-idle Android TV the effective cadence can stretch beyond 24h and is bursty. This
> is acceptable: the pre-download is an optimization, not a guarantee, and launch-time
> detection always covers the case where the user actually opens the app.

### 3. `UpdateWorkerEntryPoint`
Hilt `@EntryPoint` (installed in `SingletonComponent`) exposing `UpdateRepository`,
`ApkDownloader`, and `UpdatePreferences` to the non-injected `CoroutineWorker`.

### 4. `UpdatePreferences` (+2 keys) and `AppUpdate` serialization
Make `model/AppUpdate.kt` `@Serializable` (kotlinx.serialization is already configured —
`UpdateManifest` in the same file uses it, and the `import kotlinx.serialization.Serializable`
is already present) so the full update metadata can be cached for offline surfacing. Add an
`UpdateJson` holder (`Json { ignoreUnknownKeys = true }`) shared by worker + ViewModel.
Add to the existing `update_settings` DataStore:
- `predownloadApkPath: String?` (`stringPreferencesKey("predownloaded_apk_path")`)
- `predownloadUpdateJson: String?` (`stringPreferencesKey("predownloaded_update_json")` —
  the serialized `AppUpdate`, which carries `versionCode`, `assetUrl`, `sha256`, `notes`, etc.)
Plus `setPredownload(apkPath, updateJson)` (writes both atomically) and `clearPredownload()`
(removes both prefs keys). Worker writes; ViewModel reads. (Deleting the on-disk APK is the
caller's responsibility — see Component 5 — because `UpdatePreferences` holds no file handle.)

### 5. `UpdateViewModel` integration (merged resolve — instant install from cache)
Refactor `checkForUpdates(force, showNoUpdateFeedback)` (the existing public entry, used by
`init` and the About screen) into a single coherent resolve, with the decision logic
extracted to a **pure, unit-testable** `UpdateResolution.resolve(...)`:
1. Read `ignoredVersionCode`, `predownloadApkPath`, and the decoded `predownloadUpdateJson`.
2. Run the live `getLatestUpdate()` (best-effort; `null` on failure) and persist
   `lastCheckAtMs`.
3. `UpdateResolution.resolve(liveUpdate, cachedUpdate, cachedApkPath, cachedApkExists,
   currentVersionCode, ignoredVersionCode, force)` returns the effective update
   (**live wins; cached metadata is the offline fallback**), whether an update is available,
   whether to show the dialog, and an `installableApkPath` — the cached path **only when the
   cached file exists and its versionCode matches the effective update** (so a newer live
   release than what was pre-downloaded correctly falls back to download-on-demand rather
   than installing a stale APK). The cached-match test reuses the same pure
   `UpdateDownloadDecision.isCachedFor(...)` the worker uses, so the two sites cannot drift.
4. Map the decision onto UI state: a non-null `installableApkPath` sets `downloadedApkPath`
   + `downloadProgress = 1f` → the dialog offers **Install** (skipping download); offline
   with a valid cached APK still surfaces the install dialog.
5. If no update is available, best-effort delete the cached APK **file** and
   `clearPredownload()` (drops a now-installed/stale cached pre-download, file and pointer).

The live check's failure path is made **non-destructive** (a transient launch-time check
failure no longer nulls out a known/cached update; it only surfaces an error dialog when
the user `force`d the check).

> **Ignored-but-cached behavior (decided):** `installableApkPath` is computed independent of
> the ignore flag, but the dialog is only shown when `(remoteNewer && notIgnored) || force`.
> So a pre-downloaded version the user has ignored stays silent until the user *actively*
> `force`-checks (About screen), at which point offering an instant Install is the desired
> behavior. This cell of the truth table is covered by tests (see Testing).

### 6. Scheduling seam (full-only)
Call `UpdateWorkScheduler.ensureScheduled(application)` from the **`full`** variant of
`PluginRuntimeHooks.onApplicationCreate(application)` — already invoked from
`NuvioApplication.onCreate()` and already flavor-specific. Wrapped in try/catch so a
WorkManager hiccup never blocks startup. No shared-`main` edits.

### 7. Dependency
Add `androidx.work:work-runtime-ktx` to `gradle/libs.versions.toml` and reference it from
`app/build.gradle.kts` via `fullImplementation(libs.androidx.work.runtime)` so only the
`full` flavor compiles WorkManager. (compileSdk 36 / minSdk 24 / AGP 8.13 satisfy
WorkManager 2.10's requirements.)

## Security

**Authenticity rests on the install-time signature check, not on SHA-256.** The shared
`@Singleton OkHttpClient` injected into `UpdateRepository` and `ApkDownloader` is
intentionally trust-all (`NetworkModule.provideOkHttpClient` installs a no-op
`X509TrustManager` and `hostnameVerifier { _, _ -> true }`). Because both `version.json`
(the SHA-256 source) and the `.apk` travel over that same client, the SHA-256 check provides
**integrity against accidental corruption, not transit authenticity** — an on-path attacker
who can forge/redirect the manifest controls both the APK and its expected hash.

The real barrier is Android's package installer: an update APK whose signing certificate does
**not** match the installed app's cert (the KevBox release keystore) is rejected, and install
always goes through the user-facing system installer on next app open — there is no silent
background install. The background worker therefore cannot cause a malicious APK to be
installed; the worst an attacker on the LAN can do is waste a download.

Hardening in scope here (cheap, `full`-only, no `main` DI changes):
- The worker validates `update.assetUrl` is `https://` **and** on the same host as
  `BuildConfig.UPDATE_BASE_URL` before downloading; otherwise it skips (Component 1, step 5).
- The misleading `Checksum.kt` doc comment ("a compromised/misconfigured host cannot push an
  APK that doesn't match the manifest hash") is corrected to state that SHA-256 is an
  integrity check and authenticity comes from the signing-cert match.

Out of scope (documented for the operator): the app globally permits cleartext
(`usesCleartextTraffic="true"`) and uses the trust-all client; a future hardened/pinned
updater client would close the transit-authenticity gap but requires shared-`main` changes.

## Data flow

```
            ┌──────────── daily (WorkManager, app may be closed) ────────────┐
WM trigger → getLatestUpdate() → persist result + lastCheckAtMs
            → read cached pre-download → alreadyCached = isCachedFor(...)
            → trusted (https + UPDATE_BASE_URL host)? & newer & !ignored & !cached & unmetered?
              → download + SHA-256 verify → persist predownloadApkPath + predownloadUpdateJson
            → prune cacheDir/updates to the one version still worth keeping (every run)
            └────────────────────────────────────────────────────────────────┘

app open → UpdateViewModel.checkForUpdates(force=false)
         → live getLatestUpdate() (null on failure) + read cached pre-download
         → UpdateResolution.resolve(): effective = live ?: cached
         → installable = cached APK iff isCachedFor(cached.versionCode, effective.versionCode, exists)
         → map to UI state → dialog offers Install (cached) or Download (newer than cache)
         → if no update available: delete cached APK file + clearPredownload()
```

## Error handling

| Condition | Behavior |
|---|---|
| Check (`getLatestUpdate`) fails | `Result.retry()` — WorkManager backoff; next daily run also retries. (A permanently-failing host re-checks a cheap `version.json` GET daily; no APK is downloaded until a valid, trusted, newer manifest appears.) |
| `assetUrl` not `https://` or not on the `UPDATE_BASE_URL` host | Skip download, prune, `Result.success()` (not a transient error) |
| Network metered at download time | Record result only, skip download (not an error); next unmetered run (or on-demand foreground download) handles it |
| Download fails | Prune, `Result.retry()` |
| SHA-256 mismatch | Delete partial file, prune, `Result.retry()` |
| Cached APK missing/invalid at app open | `isCachedFor` returns false → fall back to normal launch-time check/download |
| Already-downloaded current target | Skip re-download (idempotent); keep the cached APK |
| No update available at app open | Delete cached APK file + `clearPredownload()` |

Cleanup: pruning runs on **every** worker invocation (not only after a download), keeping at
most the single APK for the version still worth installing; the ViewModel deletes the cached
APK file when it clears a now-stale pre-download. `cacheDir/updates` therefore holds ≤1 APK.

## Testing

Pure-JVM unit tests under `app/src/testFull/` (JUnit4; the existing test setup has no
Robolectric, so all logic that must be tested is extracted away from Android APIs). Run via
`./gradlew testFullDebugUnitTest`.

> **Source-set note:** `app/src/testFull/` is a *new, flavor-scoped* unit-test source set (AGP
> recognizes it implicitly). These tests must **not** go in the shared `app/src/test/`, because
> `UpdateResolution`/`UpdateDownloadDecision`/`AppUpdate` exist only in the `full` flavor —
> placing them in `app/src/test/` would break `testPlaystoreDebugUnitTest`.

- **`UpdateDownloadDecision.shouldDownload(remoteVersionCode, currentVersionCode,
  ignoredVersionCode, alreadyCachedForRemote, isUnmetered): Boolean`** — full truth table
  (newer/older/equal, ignored, already-cached, metered/unmetered). The worker's download gate.
- **`UpdateDownloadDecision.isCachedFor(cachedVersionCode, targetVersionCode,
  cachedFileExists): Boolean`** — the shared cache-match predicate (used by the worker's
  `alreadyCached` and by `UpdateResolution`'s `installableApkPath`).
- **`UpdateDownloadDecision.staleFileNames(allNames, keepName): List<String>`** — the prune
  selection (everything except the one to keep).
- **`UpdateDownloadDecision.isTrustedAssetUrl(assetUrl, baseUrl): Boolean`** — scheme/host
  gate (https + same host; rejects http, off-host, malformed).
- **`UpdateResolution.resolve(...)`** — full table: live-present vs offline cached-fallback,
  newer/older/equal, ignored (including ignored **with** a matching cached APK, force and
  non-force), installable-match vs version-mismatch, `force`.

Android-coupled pieces that remain thin (the `CoroutineWorker` deps plumbing,
`UpdatePreferences` DataStore, `UpdateWorkScheduler`, the `PluginRuntimeHooks` wiring, and the
`UpdateViewModel` state mapping) are verified by compilation + the emulator smoke test. With
the decision logic above extracted, what is left untested is genuinely glue.

**Smoke test must use a `release` build.** Because the worker returns `Result.success()`
immediately when `BuildConfig.IS_DEBUG_BUILD` is true (which it is for both `debug` and
`benchmark`), only a `fullRelease` build exercises the real path. The smoke test installs the
release build, lets the one-time kick run, and verifies behaviorally (logcat + the dialog
offering instant Install on next open) — see plan Task 11.

## Flavor safety checklist

- All new classes under `app/src/full/`.
- WorkManager dependency scoped via `fullImplementation`.
- Scheduling call only in the `full` `PluginRuntimeHooks`.
- `playstore` `AppFeaturePolicy.inAppUpdatesEnabled = false`; no worker, no scheduling, no
  WorkManager classpath there.
- R8: the newly-`@Serializable` `AppUpdate` is kept by the **existing**
  `-keep class com.nuvio.tv.updater.model.** { *; }` + `**$$serializer` rules in
  `proguard-rules.pro` (the same rules that already protect `UpdateManifest`) — not by `@Keep`
  alone. `UpdateCheckWorker` (package `com.nuvio.tv.updater`, outside `.model`) is kept by
  `work-runtime`'s bundled consumer rules (`* extends androidx.work.ListenableWorker` + its
  constructor). `assembleFullRelease` is the check that nothing strips either.
- Build verification: `testFullDebugUnitTest`, `assembleFullDebug`, **and** the `playstore`
  variant must compile; `assembleFullRelease` must stay R8-clean.
