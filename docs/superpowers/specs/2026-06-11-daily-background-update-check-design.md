# Daily Background Update Check — Design Spec

**Date:** 2026-06-11
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

## Approach: plain `CoroutineWorker` + Hilt `@EntryPoint` (chosen)

The updater lives in `app/src/full/`, but `NuvioApplication` lives in shared `app/src/main/`.
Rather than make the shared Application implement `Configuration.Provider` and pull
`hilt-work` into both flavors (the `@HiltWorker` path), the `full`-only worker obtains its
dependencies via `EntryPointAccessors.fromApplication(appContext, …)`. This keeps the entire
feature contained in `full`, adds only `androidx.work:work-runtime-ktx` (scoped to the
`full` flavor via the `fullImplementation` configuration), and requires **zero** changes to
`main`/`playstore` and **no manifest edits** (the default WorkManager initializer is kept;
`full` currently has no `AndroidManifest.xml`).

## Components

All new files in `app/src/full/java/com/nuvio/tv/updater/`.

### 1. `UpdateCheckWorker : CoroutineWorker`
The daily job. `doWork()`:
1. If `BuildConfig.IS_DEBUG_BUILD` → `Result.success()` (mirrors the launch-time gate that
   suppresses auto-checks in debug).
2. Resolve deps via `UpdateWorkerEntryPoint` (`UpdateRepository`, `ApkDownloader`,
   `UpdatePreferences`).
3. `getLatestUpdate()`. On failure → `Result.retry()` (WorkManager backoff). On success,
   persist `setLastCheckAtMs(now)`.
4. Decision (extracted to a pure helper — see Testing): with the fetched `AppUpdate`, the
   current `BuildConfig.VERSION_CODE`, the persisted `ignoredVersionCode`, whether a verified
   APK for that `versionCode` is already cached, and current network metered-ness — decide
   whether to download.
5. If download is warranted:
   - Download via `ApkDownloader.download(update.assetUrl, dest, onProgress)` into the
     shared `cacheDir/updates/<safeAssetName>` directory (same dir the foreground path uses).
   - `Checksum.verify(file, update.sha256)`. On mismatch → delete file, do **not** cache a
     path, `Result.retry()`.
   - On success → persist `predownloadedVersionCode` + `predownloadedApkPath`.
6. Prune cached APK files belonging to stale `versionCode`s.
7. `Result.success()`.

### 2. `UpdateWorkScheduler`
`fun ensureScheduled(context: Context)` enqueues a 24h `PeriodicWorkRequest<UpdateCheckWorker>`
with:
- `Constraints { setRequiredNetworkType(NetworkType.CONNECTED) }` (the *check* needs only
  connectivity; metered-vs-unmetered gating for the *download* is decided inside the worker),
- exponential backoff,
- `enqueueUniquePeriodicWork("kevbox-daily-update-check", ExistingPeriodicWorkPolicy.KEEP, …)`
  — idempotent, safe to call on every app start.

### 3. `UpdateWorkerEntryPoint`
Hilt `@EntryPoint` (installed in `SingletonComponent`) exposing `UpdateRepository`,
`ApkDownloader`, and `UpdatePreferences` to the non-injected `CoroutineWorker`.

### 4. `UpdatePreferences` (+2 keys) and `AppUpdate` serialization
Make `model/AppUpdate.kt` `@Serializable` (kotlinx.serialization is already configured —
`UpdateManifest` uses it) so the full update metadata can be cached for offline surfacing.
Add an `UpdateJson` holder (`Json { ignoreUnknownKeys = true }`) shared by worker + ViewModel.
Add to the existing `update_settings` DataStore:
- `predownloadApkPath: String?` (`stringPreferencesKey("predownloaded_apk_path")`)
- `predownloadUpdateJson: String?` (`stringPreferencesKey("predownloaded_update_json")` —
  the serialized `AppUpdate`, which carries `versionCode`, `assetUrl`, `sha256`, `notes`, etc.)
Plus `setPredownload(apkPath, updateJson)` (writes both atomically) and `clearPredownload()`
(removes both). Worker writes; ViewModel reads.

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
   than installing a stale APK).
4. Map the decision onto UI state: a non-null `installableApkPath` sets `downloadedApkPath`
   + `downloadProgress = 1f` → the dialog offers **Install** (skipping download); offline
   with a valid cached APK still surfaces the install dialog.
5. If no update is available, best-effort `clearPredownload()` (drops a now-installed/stale
   cached APK).

The live check's failure path is made **non-destructive** (a transient launch-time check
failure no longer nulls out a known/ cached update; it only surfaces an error dialog when
the user `force`d the check).

### 6. Scheduling seam (full-only)
Call `UpdateWorkScheduler.ensureScheduled(application)` from the **`full`** variant of
`PluginRuntimeHooks.onApplicationCreate(application)` — already invoked from
`NuvioApplication.onCreate()` and already flavor-specific. No shared-`main` edits.

### 7. Dependency
Add `androidx.work:work-runtime-ktx` to `gradle/libs.versions.toml` and reference it from
`app/build.gradle.kts` via `fullImplementation(libs.androidx.work.runtime.ktx)` so only the
`full` flavor compiles WorkManager.

## Data flow

```
            ┌──────────── daily (WorkManager, app may be closed) ────────────┐
WM trigger → getLatestUpdate() → persist result + lastCheckAtMs
            → newer & !ignored & !cached & unmetered? → download + SHA-256 verify
            → persist predownloadApkPath + predownloadUpdateJson (serialized AppUpdate)
            └────────────────────────────────────────────────────────────────┘

app open → UpdateViewModel.checkForUpdates(force=false)
         → live getLatestUpdate() (null on failure) + read cached pre-download
         → UpdateResolution.resolve(): effective = live ?: cached
         → installable = cached APK iff file exists & versionCode matches effective
         → map to UI state → dialog offers Install (cached) or Download (newer than cache)
```

## Error handling

| Condition | Behavior |
|---|---|
| Check (`getLatestUpdate`) fails | `Result.retry()` — WorkManager backoff; next daily run also retries |
| Network metered at download time | Record result only, skip download (not an error); next unmetered run (or on-demand foreground download) handles it |
| Download fails | `Result.retry()` |
| SHA-256 mismatch | Delete partial file, clear cached path, `Result.retry()` |
| Cached APK missing/invalid at app open | Clear cached keys, fall back to normal launch-time check |
| Already-downloaded current target | Skip re-download (idempotent) |

Cleanup: APK files for stale `versionCode`s are pruned from `cacheDir/updates`.

## Testing

Pure-JVM unit tests under `app/src/testFull/` (JUnit4; the existing test setup has no
Robolectric, so all logic that must be tested is extracted away from Android APIs). Run via
`./gradlew testFullDebugUnitTest`.

- **`UpdateDownloadDecision.shouldDownload(remoteVersionCode, currentVersionCode,
  ignoredVersionCode, alreadyCachedForRemote, isUnmetered): Boolean`** — full truth table
  (newer/older/equal, ignored, already-cached, metered/unmetered). This is the worker's
  download gate.
- **`UpdateResolution.resolve(...)`** — full table: live-present vs offline cached-fallback,
  newer/older/equal, ignored, installable-match vs version-mismatch, `force`.

Android-coupled pieces (the `CoroutineWorker` plumbing, `UpdatePreferences` DataStore,
`UpdateWorkScheduler`, the `PluginRuntimeHooks` wiring, and the `UpdateViewModel` state
mapping) are thin and verified by compilation + the project's emulator smoke-test flow,
matching how the telemetry feature was validated.

## Flavor safety checklist

- All new classes under `app/src/full/`.
- WorkManager dependency scoped via `fullImplementation`.
- Scheduling call only in the `full` `PluginRuntimeHooks`.
- `playstore` `AppFeaturePolicy.inAppUpdatesEnabled = false`; no worker, no scheduling, no
  WorkManager classpath there.
- Build verification: both `assembleFullDebug` (or `testFullDebugUnitTest`) and the
  `playstore` variant must compile; `full` release must stay R8-clean.
