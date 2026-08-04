# Sync 0.8.1 — merge resolution spec (sync-0.8.1 branch)

Context: KevBox TV is a fork of NuvioTV. Branch `sync-0.8.1` has a half-done
`git merge upstream/dev` (upstream 0.7.19→0.8.1). 17 conflicted files. General
rule: **keep KevBox identity/branding/auth/updater/telemetry/policy bits; take
upstream's features and fixes.** When both sides added adjacent lines: keep both.

## KevBox facts you must preserve

- `app/build.gradle.kts`: `applicationId = "tv.kevbox"` (debug `tv.kevbox.debug`),
  versionCode 1041 / versionName "0.8.19-beta" (release.sh bumps later — do NOT
  take upstream's 1040/0.8.1), `UPDATE_BASE_URL`, `isUniversalApk = false`,
  `SUPABASE_URL`/`SUPABASE_ANON_KEY` read our `SUPABASE_*` props (NOT upstream's
  `NUVIO_SUPABASE_*` rebinding), `SYNC_BACKEND_MANIFEST_URL` blank, `NUVIO_SUPABASE_*`
  blank, `SENTRY_*` blank, the `resolveLocalProperty(...)` helper function must exist,
  KevBox flags `FEATURE_MEMBER_ADDON_CONFIG`/`FEATURE_TELEMETRY`/`FEATURE_ACCESS_CONTROL`/
  `FEATURE_DEVICE_LIMIT`.
- KevBox updater (full flavor) is OURS: `app/src/full/java/com/nuvio/tv/updater/` —
  checks `https://tv.kevbox.dev/version.json`, SHA-256 (`Checksum.kt`),
  UpdateCheckWorker, our UpdateViewModel/UpdateRepository/UpdatePreferences/
  ui/UpdatePromptDialog. Upstream added a GitHub-release update banner that must
  NOT reach the app.
- Telemetry: `telemetryRepository`/`deviceGuardDataStore` injections and
  `heartbeatScheduler.stop()` calls in player files.
- STATE_ENDED: keep kevbox's COMMENTED-OUT `// emitCompletionScrobbleStop(...)`.
- `WatchedItemsSyncService.kt`: `unionWhenNeverSynced = true` inside
  `pullSnapshotFromRemote` (already survived — leave alone).
- Policy: addon/plugin management hidden (`SettingsScreen` CONTENT_DISCOVERY->false,
  NuvioNavHost no-op callbacks, MainActivity AddonInstall deeplink neutralized).

## File-by-file resolutions

### Conflicted

1. **`.gitignore`** — union of both sides' additions.
2. **`app/build.gradle.kts`** — keep all KevBox facts above. TAKE from upstream:
   `SIMKL_CLIENT_ID`/`SIMKL_APP_NAME` buildConfigFields, `coil.network.cache.control`
   dep, IAMF aar moved to `fullImplementation`, `FEATURE_EXTERNAL_PLAYBACK_KEEP_ALIVE_ENABLED`
   (full=true, playstore=false), androidTest junit/runner deps. ACCEPT upstream's
   REMOVAL of: `NUVIO_REALTIME_SYNC_ENABLED` property, `REALTIME_SYNC_ENABLED`
   buildConfigField, `implementation(libs.supabase.realtime)` (realtime is gone upstream).
3. **`app/src/full/java/com/nuvio/tv/updater/UpdatePreferences.kt`** — take KEVBOX side entirely.
4. **`app/src/full/java/com/nuvio/tv/updater/UpdateViewModel.kt`** — take KEVBOX side entirely.
5. **`app/src/full/java/com/nuvio/tv/updater/ui/UpdatePromptDialog.kt`** (modify/delete:
   upstream deleted, we modified) — KEEP kevbox version, `git add`.
6. **`MainActivity.kt`** — the conflict hunk is onResume: keep kevbox's
   FEATURE_ACCESS_CONTROL/FEATURE_DEVICE_LIMIT catch-up blocks AND take upstream's
   `lifecycleScope.launch { deviceSessionRegistration…; startupSyncService.requestForegroundSync() }`.
   Exactly ONE `requestForegroundSync()` call. THEN fix the auto-merged updater wiring
   (no conflict, git took upstream's): remove `import com.nuvio.tv.updater.ui.UpdateBannerHost`,
   remove the `UpdateBannerHost(...) { Box {...} }` wrapper (restore plain scaffold), and
   restore kevbox's block (from `git show kevbox:app/src/main/java/com/nuvio/tv/MainActivity.kt`,
   the `if (AppFeaturePolicy.inAppUpdatesEnabled && !BuildConfig.IS_DEBUG_BUILD)` block
   composing `UpdatePromptDialog(state, onDismiss=dismissDialog, onDownload, onInstall,
   onIgnore=ignoreThisVersion, onOpenUnknownSources)`). Verify the AddonInstall deeplink
   neutralization (KevBox FORK DIVERGENCE comments) survived.
7. **`NuvioApplication.kt`** — DROP the realtime lines (import
   `core.sync.RealtimeSyncInvalidationService`, the `@Inject lateinit var
   realtimeSyncInvalidationService`, and the `if (BuildConfig.REALTIME_SYNC_ENABLED) {
   realtimeSyncInvalidationService.start() }` block) — service/dep deleted upstream.
   KEEP kevbox's addon-seed block (`addonPreferences.seedDefaultAddonsOrderIfFirstLaunch()`);
   upstream deleted the `appScope` it launched in — re-add a small application
   CoroutineScope for it (or reuse an existing app-level scope). TAKE upstream's
   `SimklAnimeIdPreferenceHolder` injection + Coil `CacheControlCacheStrategy` image-loader
   rewrite. Keep BOTH `AddonPreferences` import and new imports.
8. **`StartupSyncService.kt`** — take upstream's rewrite (`librarySyncService.syncFromRemote(profileId)`,
   `shouldUseSupabaseWatchProgressSync()`). KEEP kevbox's `FEATURE_MEMBER_ADDON_CONFIG`
   guards (manual addon-sync skip and addonJob skip — git shows them at kevbox
   ~L176-188 and ~L543-596; verify they survived or re-apply from
   `git show kevbox:.../StartupSyncService.kt`).
9. **`LibraryPreferences.kt`** — both sides rewrote. Take UPSTREAM's version wholesale
   (it implements `LibrarySyncLocalStore` for the new delta sync). KevBox's old
   `mergeRemoteItems(preserveLocal=true)` is superseded — BUT apply the local-preservation
   patch described below (todo item), which may land here or in LibrarySyncReducer.
10. **`NuvioNavHost.kt`** — keep kevbox's `onNavigateToAddons = { }` /
    `onNavigateToPlugins = { }` no-ops + AuthSignIn reroute (and their comment block);
    TAKE upstream's `onNavigateToTrakt`→`onNavigateToTracking` rename,
    `TraktScreen`→`TrackingSettingsScreen`, and `onReturnFocusConsumed` param.
11. **`AccountViewModel.kt`** — take upstream's API: `librarySyncService.syncFromRemote(profileId)`
    / `pushToRemote(profileId)`.
12. **`LibraryScreen.kt`** — take upstream's side unless a kevbox-specific block exists
    (check `git show kevbox:.../LibraryScreen.kt` vs merge-base for kevbox mods first).
13. **`PlayerRuntimeController.kt`** — hunk1 (imports): keep both (kevbox
    HeartbeatScheduler/TelemetryRepository + upstream TrackingMediaReference/
    TrackingScrobbleCoordinator). Hunk2 (init block): keep kevbox's telemetry device-id
    `scope.launch` but DROP the `scope.launch { isTraktCwActive = … }` line — upstream
    deleted that mechanism; the field no longer exists. Afterwards
    `grep -rn isTraktCwActive app/src` must be EMPTY.
14. **`PlayerRuntimeControllerInitialization.kt`** — imports: keep both (kevbox BuildConfig
    + upstream AspectRatioFrameLayout/PlayerView). STATE_ENDED hunk: take kevbox's side
    wholesale (telemetry `heartbeatScheduler.stop()` + commented-out
    `// emitCompletionScrobbleStop(progressPercent = 99.5f)`).
15. **`PlayerViewModel.kt`** — imports: keep both (TelemetryRepository +
    TrackingScrobbleCoordinator).
16. **`AboutScreen.kt`** — keep kevbox's §11 `if (BuildConfig.FEATURE_TELEMETRY)`
    privacy-notice block; keep the privacy-policy row COMMENTED OUT (do not take
    upstream's live `nuvio.tv/privacy-policy` row). Update section: keep only kevbox's
    "Check for Updates" `SettingsActionRow` inside the
    `inAppUpdatesEnabled && !IS_DEBUG_BUILD` gate; DELETE upstream's `SettingsToggleRow`
    (`about_update_banner_title`/`setUpdateBannerEnabled` — those don't exist on kevbox's VM).
17. **`app/src/main/res/values/strings.xml`** — keep both: kevbox's `about_telemetry_notice_*`
    + "Check for Updates" AND upstream's new strings (Simkl/tracking etc. — the hidden
    TrackingSettingsScreen still references them). Unused `about_update_banner_*` strings
    are acceptable; do NOT remove other upstream additions.

### Upstream additions to DELETE (they merged in cleanly)

- `app/src/full/java/com/nuvio/tv/updater/AbiSelector.kt`
- `app/src/full/java/com/nuvio/tv/updater/VersionUtils.kt`
- `app/src/full/java/com/nuvio/tv/updater/ui/UpdateBanner.kt`
- `app/src/full/java/com/nuvio/tv/updater/ui/UpdateBannerHost.kt`
- `app/src/full/java/com/nuvio/tv/updater/ui/UpdateDialogs.kt`
- `app/src/main/java/com/nuvio/tv/updater/UpdateBannerPolicy.kt`
- `app/src/playstore/java/com/nuvio/tv/updater/ui/UpdateBannerHost.kt`

### KevBox playstore stubs to RESTORE (merge silently overwrote/deleted)

- `git checkout kevbox -- app/src/playstore/java/com/nuvio/tv/updater/UpdateViewModel.kt`
- `git checkout kevbox -- app/src/playstore/java/com/nuvio/tv/updater/ui/UpdatePromptDialog.kt`

### Auto-merged files needing a one-line fix

- **`SettingsScreen.kt`**: kevbox's line `SettingsCategory.TRAKT -> false` no longer
  compiles (enum renamed) → change to `SettingsCategory.TRACKING -> false` (this also
  hides the new combined Trakt/Simkl screen — intended). Verify
  `SettingsCategory.CONTENT_DISCOVERY -> false` survived.
- **`gradle/libs.versions.toml`** — verify the supabase-realtime entry is gone and the
  supabase bump merged sanely.

## Library local-preservation patch (after conflicts resolve)

Upstream's new library sync drops local-only library items when a never-synced device
restores against a NON-empty remote library (the watched-items Option-B analog).
Read `app/src/main/java/com/nuvio/tv/domain/model/LibrarySyncReducer.kt` (esp.
`applySnapshot` + `migrateLegacyLocal`) and `core/sync/library/LibrarySyncLocalStore.kt`.
Minimal fix: when a profile has never completed a sync (no prior delta cursor / first
snapshot), local-only items must be queued as pending upserts (or otherwise preserved)
instead of dropped — mirror the semantics of `unionWatchedSnapshot` in
`data/local/SyncMergeLogic.kt`. Keep it small; add a `// KevBox FORK DIVERGENCE` /
`ponytail:` comment. If `LibrarySyncReducer` has unit tests upstream, add one small
test mirroring existing style; otherwise skip tests.

## Post-resolution verification gates (must all pass)

1. `grep -rn "UpdateBannerHost\|dismissBanner\|setUpdateBannerEnabled\|updateBannerEnabled\|UpdateBannerPolicy\|consumeFeedbackMessage\|dismissUnknownSourcesDialog\|AbiSelector\|VersionUtils" app/src` → EMPTY.
2. `grep -rn "isTraktCwActive\|RealtimeSyncInvalidation\|REALTIME_SYNC" app/src app/build.gradle.kts` → EMPTY.
3. `grep -n "TRACKING -> false\|CONTENT_DISCOVERY -> false" app/src/main/java/com/nuvio/tv/ui/screens/settings/SettingsScreen.kt` → both present.
4. `grep -n "onNavigateToAddons = { }\|onNavigateToPlugins = { }" app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt` → both present.
5. `grep -n "resolveLocalProperty" app/build.gradle.kts` → present.
6. `grep -c "emitCompletionScrobbleStop" app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt` → only the commented one (no active call in STATE_ENDED).
7. `./gradlew :app:compileFullDebugKotlin` → BUILD SUCCESSFUL (this also runs Hilt/KSP).
8. `./gradlew :app:assembleFullRelease --dry-run` → SUCCESS.
9. `grep -n "unionWhenNeverSynced = true" app/src/main/java/com/nuvio/tv/core/sync/WatchedItemsSyncService.kt` → present.
10. `git diff kevbox...HEAD` sanity on build.gradle.kts: applicationId still tv.kevbox,
    versionCode 1041, UPDATE_BASE_URL present.

Do NOT `git commit`. `git add` resolved files and leave the merge in progress.
Report: per-file resolution notes, any deviations from this spec, gate results.
