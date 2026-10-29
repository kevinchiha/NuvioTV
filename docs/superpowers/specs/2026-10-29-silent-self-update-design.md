# Silent self-update for KevBox TV

## Goal
A KevBox TV box on Android 12 or later installs a new KevBox TV release by itself, with no screen
and no button press, once the app has been out of sight for 2 minutes; when Android refuses a
silent install, or the box runs Android 11 or older, the member keeps today's "Update available"
prompt.

## Scope
- The full-flavor updater in `app/src/full/java/com/nuvio/tv/updater/`: the eligibility decision,
  a silent session install in `ApkInstaller`, silent handling in `InstallResultReceiver`, a new
  one-time silent-install job next to `UpdateCheckWorker`, its scheduling, the per-version
  silent-install state in `UpdatePreferences`, and `UpdateWorkerEntryPoint` (adds `AuthManager`).
- `app/src/full/AndroidManifest.xml`: one permission.
- The update manifest model: `UpdateManifest`, `AppUpdate`, `UpdateRepository.parseManifest`.
- Two "is the app in use" signals the decision reads: activity start/stop times (recorded
  app-wide), and whether `ExternalPlaybackKeepAliveService` is running.
- `release.sh` and its `release.env` line, so a release can say when boxes may install silently.
  The script is edited, never run, in this phase.
- A test-only build switch so a debug build on the emulator runs the updater against a server on
  the host: `app/build.gradle.kts`, the trust check, and the two `!BuildConfig.IS_DEBUG_BUILD`
  gates that hide the prompt (`MainActivity.kt`, `AboutScreen.kt`).

## Acceptance criteria
- **AC-1** `version.json` may carry `"silent": {"after": <epoch ms>, "earlyUsers": ["<uuid>", ...]}`.
  `parseManifest` maps it onto `AppUpdate` as `silentAfterMs: Long?` and
  `silentEarlyUsers: List<String>`, both with defaults, so cached `AppUpdate` JSON saved by older
  builds still decodes (with no silent block). Unit tests in `UpdateManifestMappingTest` pass for:
  no `silent` key → no silent block, rest of the manifest maps as today; `"earlyUsers"` missing →
  empty list; `"silent": "yes"`, `"silent": {}`, `"after": "soon"`, `"after": 0`, `"after": -5`, or
  `"earlyUsers": "x"` → no silent block, and the rest of the manifest still maps (no exception).
- **AC-2** A silent install proceeds only when every one of these holds, each checked in a pure
  decision function that returns a distinct reason for each failed check (one unit test per
  reason, plus one for "eligible"):
  1. the updater is on: a release build, or a debug build made with the AC-12 property;
  2. `Build.VERSION.SDK_INT >= 31`;
  3. `ApkInstaller.canRequestPackageInstalls()` is true;
  4. `version.json` fetched in this same run succeeded, and its versionCode is higher than
     `BuildConfig.VERSION_CODE`;
  5. the cached pre-download is for that exact versionCode, its file exists, and its SHA-256
     matches the `sha256` of the manifest from check 4 (computed again, right before the session);
  6. the member has not chosen "ignore this version" for that versionCode;
  7. the manifest from check 4 carries a valid silent block (AC-1);
  8. the box clock (`System.currentTimeMillis()`) is at or past `after`, or the signed-in
     account's user id equals an entry of `earlyUsers` (trimmed, case-insensitive);
  9. that versionCode is not marked "silent refused" (AC-7) and has fewer than 3 silent attempts
     (AC-8).
- **AC-3** A silent install proceeds only while the app is out of sight: no activity of the app is
  started; at least 120 000 ms have passed since the later of the last recorded activity start
  and the last recorded activity stop; `ExternalPlaybackKeepAliveService` is not running; and
  AC-10 allows it. Start and stop times are stored as `SystemClock.elapsedRealtime()` values that
  survive process death. A stored value larger than the current `elapsedRealtime()` (the box
  rebooted), or no stored value, counts as "out of sight since boot". The process checks this
  once before it copies the APK into the session and again right before `commit`; if the second
  check fails, it abandons the session, logs the reason, and counts no attempt.
- **AC-4** When the app goes from "an activity started" to "no activity started", and the cached
  pre-download is an upgrade with a silent block, the app queues one-time work named
  `kevbox-silent-install` with `ExistingWorkPolicy.REPLACE`, an initial delay of 2 minutes, and no
  network constraint. That job fetches `version.json`, evaluates AC-2 and AC-3, and installs only
  if both hold. When it finds them unmet, it logs the reason and queues nothing further; the next
  time the app leaves the screen, or the next daily check, tries again.
- **AC-5** When a `UpdateCheckWorker` run ends with a valid pre-download for the manifest it just
  fetched (downloaded now or already cached), and AC-2 and AC-3 hold, the same run starts the
  silent install.
- **AC-6** The silent session is a `PackageInstaller` session with `MODE_FULL_INSTALL`,
  `setAppPackageName(packageName)` and `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`. It writes
  `base.apk`, plus `base.dm` only when `ProfileFetcher.fetch` returned a profile; a missing profile
  never moves a silent install to the plain installer. Its result intent carries a silent flag and
  the versionCode. `app/src/full/AndroidManifest.xml` declares
  `android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION`. Logcat shows
  `Committed silent session <id> (versionCode <n>, profile=<file name|none>)`.
- **AC-7** A silent result of `STATUS_PENDING_USER_ACTION` posts nothing to `PendingInstallScreen`,
  starts no activity, abandons that session (`EXTRA_SESSION_ID`), and marks that versionCode
  "silent refused", so no further silent attempt runs for it. On the next app open the prompt
  still offers Install for that version.
- **AC-8** A silent result with any other non-success status (including `STATUS_FAILURE_ABORTED`)
  starts no activity, posts nothing to `PendingInstallScreen`, and never falls back to the plain
  installer. Each committed silent session, and each silent attempt that throws while creating or
  writing its session, counts one attempt for its versionCode, recorded before `commit`. After 3,
  no further silent attempt runs for that versionCode, and the prompt handles it. Silent state
  stored for any other versionCode is ignored.
- **AC-9** The member-started path behaves as today: `UpdateViewModel.installUpdateOrRequestPermission`
  → `ApkInstaller.launchInstall`, with the session plus `base.dm` when a profile exists, the plain
  install screen otherwise, Android's confirm screen on `STATUS_PENDING_USER_ACTION`, and the
  plain-installer fallback on failure. Existing tests (`InstallStatusActionTest`,
  `PendingInstallScreenTest`, `UpdateResolutionTest`, `UpdateDownloadDecisionTest`,
  `ProfileFetcherTest`, `InstallProfileTest`) pass unchanged.
- **AC-10** Every silent attempt, from the job in AC-4 or the worker in AC-5, runs under one
  process-wide lock, so two never overlap. A silent attempt does not start while the member's
  Install job is copying its session, while `PendingInstallScreen.pending` holds a screen, or
  within 10 minutes after a member-started session was committed in this process.
- **AC-11** `release.sh` writes `"silent": {"after": <T + 86 400 000>, "earlyUsers": [...]}` into
  `version.json`, where T is the epoch ms at which the script writes the manifest, and
  `earlyUsers` comes from `SILENT_EARLY_USERS` in `release.env` (comma-separated; blank or
  missing gives `[]`). Before it bumps or builds anything, the script exits non-zero with a
  message naming the bad position (not its value) when any entry is not a UUID
  (`8-4-4-4-12` hex). `./release.sh --no-silent <version> [notes...]` writes no `silent` key. The
  step that builds the silent block can run alone with no ssh and no Gradle, and does so in this
  phase for these cases: two valid ids; blank; one email (rejected); `--no-silent`. Each output
  passes `python3 -m json.tool`. `bash -n release.sh` passes. The script itself is not run.
- **AC-12** `./gradlew :app:assembleFullDebug -PupdaterTestBaseUrl=http://10.0.2.2:<port>
  -PupdaterTestVersionCode=<n>` builds a debug APK in which the updater is on: the worker runs,
  the prompt is composed, the silent path works, `UPDATE_BASE_URL` is that URL, `versionCode` is
  `<n>`, and the trust check accepts http on exactly that host and port. Unit tests on the trust
  check: with no test URL, an http asset URL is refused; with the test URL, the same host on
  another port and another http host are both refused. Without the property, debug builds keep
  the updater off as today. `:app:generateFullReleaseBuildConfig` with both properties set leaves
  the release `BuildConfig.java` with an empty test URL, `IS_DEBUG_BUILD = false`, and the
  versionCode from `defaultConfig`.
- **AC-13** Every silent evaluation logs one line under one tag with the reason from AC-2/AC-3/AC-10
  (or "eligible"), and logs mark job queued, session committed, and result status. The early-user
  match is logged as `early=true|false`. No updater log line contains the user id, an email, or a
  token: the AC-14 logcat, filtered to the updater's own tags, contains neither the emulator
  account's id nor its email when an account is signed in. (Upstream AuthManager logs the raw
  user id under its own tag today; that is outside this phase.)
- **AC-14** On the `kevbox_tv` emulator (Android 16, API 36, x86_64), with debug APKs from AC-12
  (application id `tv.kevbox.debug`, both signed with the release key), "install unknown apps"
  allowed for `tv.kevbox.debug`, and a host server at `http://10.0.2.2:<port>` serving
  `version.json` with no `profiles` entry:
  - Run 1: install versionCode N, serve N+1 with `after` 1 hour in the past, open the app until
    the pre-download log line appears, then press HOME. Within 5 minutes of HOME, with no input,
    `dumpsys package tv.kevbox.debug` shows `versionCode=N+1`, no package-installer screen
    appeared, and logcat shows the committed silent session and a success status.
  - Run 2: with N+1 installed after run 1 (never uninstall: the emulator's debug app holds
    Kevin's sign-in), serve N+2 with `after` 24 hours ahead and
    `earlyUsers: ["00000000-0000-0000-0000-000000000000"]`. 5 minutes after HOME, the package is
    still at N+1 and logcat shows the not-yet reason. Opening the app shows the prompt with Install.

## Non-goals
- Installing on a real TV box. Those are 32-bit armeabi-v7a; the TCL test needs Kevin's yes and
  comes after this phase.
- KevBox Live's updater: separate repo, its own phase.
- A "What's new" screen after a silent update: it needs UI work this phase does not touch.
- Rollout control on the server beyond the manifest fields (no per-member table): the manifest is
  enough for a family of boxes.
- Silent install on Android 11 and older: `setRequireUserAction` exists from API 31 only, so those
  boxes keep the prompt.
- A box that never leaves the app (left on the in-app ambient screen, which keeps the activity
  started) never updates silently; it gets the prompt when someone uses it.
- Cancelling a silent install once committed: Android gives no way back after `commit`.
- Notifications or toasts about a silent install: the point is that nobody notices.

## Decisions
- Grill: skipped (unattended run on Kevin's word, 2026-10-29)
- Install only while the app is out of sight for 2 minutes, over a fixed overnight window, because
  boxes have wrong clocks and time zones, and "not on screen" is the condition that matters: the
  install kills the app and freezes it for about 55 s.
- Staged rollout through `after` + `earlyUsers` in `version.json`, over a server table, because the
  manifest is already the one place a release is described and an old manifest without the block
  means no silent install (fails safe).
- A refused silent attempt drops quietly to the prompt, over showing Android's confirm screen,
  because the app is in the background and a screen popping up later would surprise the member.
- auto-resolved: `earlyUsers` holds Supabase auth user ids, not emails, because `version.json` is
  public; `release.sh` refuses any entry that is not a UUID, so an email pasted by mistake never
  gets published.
- auto-resolved: a box with no signed-in account is never early, and still installs once `after`
  has passed. The account is read only when `after` has not passed. In a cold background process
  `AuthManager` already exists (`NuvioApplication` injects `StartupSyncService`, which injects it)
  and starts in `Loading`; the check waits up to 15 s for it to leave `Loading`, and anything
  other than `FullAccount` counts as not early. The box's own id (`currentUserId`) is used, not
  the sync-owner id.
- auto-resolved: keep the 2-minute delay. Nothing in the code argues for longer: the in-app
  ambient screen keeps the activity started, so the app only leaves the screen when the member
  leaves the app, the system screensaver starts after the member has already been idle for
  minutes, or the TV switches input. The one real "in use but out of sight" case, playback in an
  external player, is covered by the `ExternalPlaybackKeepAliveService` check (AC-3), over a
  longer delay that would still miss a film.
- Every silent attempt fetches `version.json` again and needs the live manifest to allow it, over
  trusting the silent block cached at download time, so removing the block on the server stops
  installs that have not started yet; offline means no install.
- Compare `after` with the box clock, over the `Date` header of the response: the https fetch
  that every attempt needs already fails on a box whose clock is far off.
- Count attempts when the session is committed, over counting failure results, because the result
  broadcast may never arrive once Android kills the process.
- Guard member-started installs (AC-10), because `installWithSession` abandons every earlier
  session of the app before it starts; without the guard a silent attempt would cancel a confirm
  screen the member opened.
- The silent session writes `base.apk` alone when there is no profile, over today's rule that
  sends a profile-less install to the plain installer, because the plain installer always shows a
  screen.
- Verify the release build through `:app:generateFullReleaseBuildConfig` and the generated file,
  over `assembleFullRelease --dry-run`, because a dry run evaluates no BuildConfig values.
- The emulator test switch also sets `versionCode`, because debug and release share
  `versionCode = 1061` in `defaultConfig`, and the N to N+1 test needs two codes without editing
  the file.
- Android rules behind AC-2 (checked against the `setRequireUserAction` reference): user action is
  skipped only when the session sets `USER_ACTION_NOT_REQUIRED`, the installer holds
  `REQUEST_INSTALL_PACKAGES` with "install unknown apps" allowed, the app updates itself, the app
  declares `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, and the installed APK targets at least API 29 on
  Android 12, 30 on 13, 31 on 14, 33 on 15, 34 on 16, and 35 on 17. KevBox targets 36, so it
  clears all of these. Android still answers `STATUS_PENDING_USER_ACTION` when any rule fails,
  hence AC-7. No script in this repo allows "install unknown apps"; it is a manual setup step, so
  AC-2 checks it at run time.

## Open questions
