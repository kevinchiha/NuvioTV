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

### 4. `UpdatePreferences` (+2 keys)
Add to the existing `update_settings` DataStore:
- `predownloadedVersionCode: Int?` (`intPreferencesKey("predownloaded_version_code")`)
- `predownloadedApkPath: String?` (`stringPreferencesKey("predownloaded_apk_path")`)
Plus a setter that writes both atomically and a clearer that removes both. Worker writes;
ViewModel reads.

### 5. `UpdateViewModel.init` integration (instant-install from cache)
Before/alongside the existing live check:
1. Read `predownloadedVersionCode` + `predownloadedApkPath`. If the path points to an
   existing file, the version is `> BuildConfig.VERSION_CODE`, and it is not the
   `ignoredVersionCode`, immediately populate UI state with the matching `AppUpdate`
   metadata and `downloadedApkPath`, and `showDialog = true` — so the dialog offers
   **Install** (skipping the download step).
2. Still run the existing `getLatestUpdate()` live check, so a release newer than the
   pre-downloaded one is caught (download-on-demand fallback via the existing
   `downloadUpdate()` path).
If the cached file is missing or fails verification, clear the cached keys and fall back to
the normal flow.

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
            → persist predownloadedVersionCode + predownloadedApkPath
            └────────────────────────────────────────────────────────────────┘

app open → UpdateViewModel.init → read predownloaded cache
         → verified APK for newer, non-ignored version? → show dialog w/ instant Install
         → also run live getLatestUpdate() refresh (catches anything newer)
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

## Testing (full-variant unit tests)

Following the existing `HeartbeatSchedulerTest` style:
- Extract the "should download?" decision into a **pure function** taking
  `(remoteVersionCode, currentVersionCode, ignoredVersionCode, alreadyCachedForVersion,
  isUnmetered)` → `Boolean`, and unit-test its truth table (newer/older/equal,
  ignored, already-cached, metered/unmetered).
- `UpdatePreferences` round-trip for the two new keys (set → read → clear).
- `UpdateViewModel` surfaces an instant-install dialog from a cached verified pre-download
  **without** invoking the downloader, and falls back/clears when the cached file is absent.

## Flavor safety checklist

- All new classes under `app/src/full/`.
- WorkManager dependency scoped via `fullImplementation`.
- Scheduling call only in the `full` `PluginRuntimeHooks`.
- `playstore` `AppFeaturePolicy.inAppUpdatesEnabled = false`; no worker, no scheduling, no
  WorkManager classpath there.
- Build verification: both `assembleFullDebug` (or `testFullDebugUnitTest`) and the
  `playstore` variant must compile; `full` release must stay R8-clean.
