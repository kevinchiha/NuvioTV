# Silent update: match KevBox Live (TV side)

## Goal
KevBox TV's silent update times the 24 h wait and the 72 h prompt net by the update server's clock,
installs on a box left open but idle for 3 hours, and checks on Live's schedule, so the two apps
behave the same (Kevin's ruling 2026-10-29: "best of each, change both").

## Scope
`app/src/full/java/com/nuvio/tv/updater/` only (UpdateRepository, AppUpdate model, SilentInstall,
SilentInstallDecision, SilentInstallGuard, AppVisibilityTracker, SilentPromptHold call site in
UpdateViewModel, SilentInstallWorker, UpdateWorkScheduler) and their tests. No `app/src/main` edits.

**Gate:** run each with `set -o pipefail`, full flavor only: `./gradlew :app:compileFullDebugKotlin`,
`./gradlew :app:testFullDebugUnitTest --continue` (green when the only failure is
TraktProgressServiceRefreshTest "change seen by a cancelled refresh is fetched by the next poll"),
`./gradlew :app:assembleFullRelease --dry-run`, `PYTHONPATH=scripts python3 -m unittest discover -s scripts/tests`.

## Acceptance criteria
- **AC-1** `UpdateRepository.getLatestUpdate` reads the HTTP `Date` header of the `version.json`
  response and stores it as `AppUpdate.serverTimeMs: Long? = null` (epoch ms). A missing or
  unparseable header gives null. Cached `AppUpdate` JSON from older builds still decodes.
- **AC-2** Silent eligibility's `NOT_YET` check compares `after` with the live manifest's
  `serverTimeMs`, or the box clock when that is null.
- **AC-3** The AC-15 prompt hold's 72 h net (`SilentPromptHold`) uses the same clock as AC-2.
- **AC-4** `quiet` no longer returns `APP_ON_SCREEN` for a started activity when nothing is playing
  and no remote key was pressed for 10 800 000 ms (3 h), counted on `elapsedRealtime` from the later
  of the last key press and the last activity start. "Playing" means `AudioManager.isMusicActive()`
  is true; if that cannot be read, it counts as playing (fail closed). External playback, the
  member-install guard and the install-screen guard still apply.
- **AC-5** Remote key presses are recorded app-wide by a `Window.Callback` wrapper the visibility
  tracker installs on each activity (no upstream file changes). The wrapper passes every call to the
  original callback unchanged and is installed at most once per window.
- **AC-6** A box counts as out of sight only after 900 000 ms (15 min) with no started activity
  (was 120 000). The leave-the-app job waits 900 000 ms (was 120 000) and logs
  `Silent job queued (versionCode <n>, delay 900 s)`.
- **AC-7** `UpdateWorkScheduler` runs the periodic update check every 6 hours (was 1 day), with
  `ExistingPeriodicWorkPolicy.UPDATE` so installed boxes pick up the new period.
- **AC-8** Unit tests cover: Date header parsed / missing / garbage; NOT_YET and the 72 h net on a
  box clock 2 days wrong but a correct server time; idle-on-screen at 3 h minus 1 ms (on screen)
  and 3 h (eligible); playing blocks it; an unreadable audio state blocks it; a key press resets it;
  the 15 min boundary.

## Non-goals
- Live's 7-day prompt fallback: TV's prompt rules (AC-15 of the silent-self-update spec) are the
  ones both apps keep.
- A shorter idle time for emulator tests. Unit tests pin the 3 h rule; the emulator re-runs the
  out-of-sight path only.

## Decisions
- Server `Date` header over the box clock: some boxes have a wrong date (Live's rule).
- `AudioManager.isMusicActive()` as the "playing" signal over hooking the upstream players: no
  upstream edit, and it also covers trailers and external audio. A trailer with sound playing on the
  home screen therefore blocks the idle path; the out-of-sight path and the 72 h prompt still work.
- 15 min out of sight and every 6 h checks (Live's timing): a member stepping away for a few
  minutes no longer comes back to an app that is installing.

## Tasks

### Task 1: Server clock for the wait and the prompt net
**Delivers:** AC-1, AC-2, AC-3, AC-8 (clock tests)
**Files:** `updater/UpdateRepository.kt`, `updater/model/AppUpdate.kt`, `updater/SilentInstall.kt`,
`updater/UpdateViewModel.kt`, tests in `app/src/testFull/java/com/nuvio/tv/updater/`
**Interfaces:** `AppUpdate.serverTimeMs: Long? = null`; `UpdateRepository.parseHttpDate(header: String?): Long?`
(pure, RFC 1123); SilentInstall and UpdateViewModel pass `live.serverTimeMs ?: System.currentTimeMillis()`
as `nowMs`.
**Does:**
- Read `response.header("Date")` inside the existing call; set `serverTimeMs` on the mapped AppUpdate.
- SilentInstall's eligibility input and the second evaluation use the live manifest's server time.
- UpdateViewModel's `holdsPrompt(... nowMs = ...)` uses the live manifest's server time.
**Tests:** parseHttpDate cases; eligibility NOT_YET with box clock 2 days ahead but server time before `after`;
holdsPrompt with a wrong box clock.
**Verify:** `./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.*'`

### Task 2: Idle-on-screen rule, key recorder, Live's timing
**Delivers:** AC-4, AC-5, AC-6, AC-7, AC-8 (idle and timing tests)
**Files:** `updater/SilentInstallDecision.kt`, `updater/SilentInstallGuard.kt`, `updater/AppVisibilityTracker.kt`,
`updater/SilentInstallWorker.kt`, `updater/UpdateWorkScheduler.kt`, tests
**Interfaces:** `QuietInput` gains `playing: Boolean` and `lastKeyPressElapsedMs: Long?`;
`SilentInstallDecision.IDLE_ON_SCREEN_MS = 10_800_000L`; `OUT_OF_SIGHT_MS = 900_000L`;
`SilentInstallWorker.DELAY_MS = 900_000L`.
**Does:**
- `quiet`: with started activities, return `APP_ON_SCREEN` unless `!playing` and
  `now - max(lastKeyPress, lastStart) >= IDLE_ON_SCREEN_MS` (values above now are dropped as a reboot).
- The tracker wraps each activity's `Window.Callback` on start (once) and records the elapsed time of
  every `dispatchKeyEvent` in memory.
- `quietInput` reads `AudioManager.isMusicActive()` behind try/catch (failure = playing).
- Worker delay, out-of-sight time and the periodic interval change as in AC-6, AC-7; update the
  queued log line.
**Tests:** idle boundary (3 h - 1 ms / 3 h); playing blocks; key press resets; unreadable audio blocks;
15 min out-of-sight boundary.
**Verify:** `./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.*'`

## Build log
