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
  member-install guard and the install-screen guard still apply. When the visibility tracker is not
  registered (visibility unknown), `quiet` returns `APP_ON_SCREEN` whatever the idle time, as today.
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
- Key presses inside dialog windows (NuvioDialog, Synopsis, EpisodeOptionsOverlay and others open their
  own windows). The app's screensaver timer that sees them is a private field of upstream's
  MainActivity, and reaching it means an upstream edit. A member would have to use a dialog nonstop for
  3 hours without touching the main screen for this to matter.
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
(pure, RFC 1123); `object SilentClock { fun nowFor(serverTimeMs: Long?, boxNowMs: Long): Long }` (pure:
the server time when known, else the box clock). SilentInstall passes
`SilentClock.nowFor(live.serverTimeMs, env.nowMs())` (keep the `env.nowMs()` seam); UpdateViewModel passes
`SilentClock.nowFor(live.getOrNull()?.serverTimeMs, System.currentTimeMillis())` to `holdsPrompt`.
**Does:**
- Read `response.header("Date")` inside the existing call; set `serverTimeMs` on the mapped AppUpdate.
- SilentInstall's eligibility input and the second evaluation use the live manifest's server time.
- UpdateViewModel's `holdsPrompt(... nowMs = ...)` uses the live manifest's server time.
**Tests:** parseHttpDate cases; SilentClock.nowFor (server time wins, null falls back); through SilentInstallTest's
fake env, NOT_YET with the box clock 2 days ahead but a manifest server time before `after`.
**Verify:** `./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.*'`

### Task 2: Idle-on-screen rule, key recorder, Live's timing
**Delivers:** AC-4, AC-5, AC-6, AC-7, AC-8 (idle and timing tests)
**Files:** `updater/SilentInstallDecision.kt`, `updater/SilentInstallGuard.kt`, `updater/AppVisibilityTracker.kt`,
`updater/SilentInstallWorker.kt`, `updater/UpdateWorkScheduler.kt`, tests
**Interfaces:** `QuietInput` gains `playing: Boolean`, `lastKeyPressElapsedMs: Long?` and
`visibilityKnown: Boolean` (false when the tracker is not registered);
`SilentInstallDecision.IDLE_ON_SCREEN_MS = 10_800_000L`; `OUT_OF_SIGHT_MS = 900_000L`;
`SilentInstallWorker.DELAY_MS = 900_000L`.
**Does:**
- `quiet`: with started activities, return `APP_ON_SCREEN` unless `!playing` and
  `now - max(lastKeyPress, lastStart) >= IDLE_ON_SCREEN_MS` (values above now are dropped as a reboot).
- The tracker wraps each activity's `Window.Callback` on start (once) and records the elapsed time of
  every `dispatchKeyEvent` in memory.
- `quietInput` reads `AudioManager.isMusicActive()` behind try/catch (failure = playing).
- Worker delay, out-of-sight time and the periodic interval change as in AC-6, AC-7. The queued log
  line lives in `SilentInstallScheduler.kt` and builds "delay 900 s" from `DELAY_MS` by itself; check it.
**Tests:** idle boundary (3 h - 1 ms / 3 h); playing blocks; key press resets; unreadable audio blocks;
visibility unknown blocks even when idle; 15 min out-of-sight boundary.
**Verify:** `./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.*'`

## Build log
plan check (62f11a5a): 5 problems folded: visibility-unknown flag always blocks; env.nowMs() seam kept; SilentClock.nowFor as the tested clock choice; dialog-window key presses recorded as a non-goal (upstream-private timer); the queued log line lives in SilentInstallScheduler.kt.
baseline: TraktProgressServiceRefreshTest "change seen by a cancelled refresh is fetched by the next poll" (gate on kevbox 8501998fc, 2026-10-29 10:0x: 2446 tests, 1 failed)
Task 1+2: started at 085b73dd0
Task 1+2: landed (f5ed0c339, 519853815). Additions: SilentInstallGuard.isPlaying, KeyPressLog, KeyRecordingCallback (overrides the two Window.Callback default methods Kotlin delegation skips), AppVisibilityTracker.lastKeyPressElapsedMs. Touched SilentInstallScheduler.kt comments only. Tests pinning the old numbers updated on purpose.
review: gate green (2474 tests, only the baseline failure; release dry run ok; 39 script tests). No critical. important 1 scripts/updater_test_server.py:37,:51 run steps still wait 5 min after HOME; the job now waits 15. minor 2 stale "daily / once-a-day" wording (UpdateCheckWorker.kt:20,:39, SilentInstall.kt:68,:292, SilentPromptHold.kt:19,:40, PluginRuntimeHooks.kt:28; keep the unique work name and the "daily-check" log label). minor 3 AppVisibilityTracker.kt:85-88 pointer remotes (touch / generic motion) don't count as presses. minor 4 UpdateViewModel.kt:123 call site untested. minor 5 AppVisibilityTracker.kt:103-105 "installed once" checks only the top of the callback chain (a Sentry wrapper left on top could double-record; harmless). Live comparison: Live's leave job uses KEEP and its idle counts as out of sight at once; Live's periodic job uses KEEP (sent to the Live fix pass).
ocr: medium AppVisibilityTracker.kt:83 pointer events not counted (same as review 3); 3 low dropped.
fix list: (1) important: run steps wait 16 minutes after HOME (run 1 and run 2). (2) stale wording. (3) review 3 + ocr medium: record dispatchTouchEvent and dispatchGenericMotionEvent as presses too.
skipped: review 4 (one-line call site; SilentClock.nowFor is tested), review 5 (worst case each press recorded twice, harmless).
fix round 1: 1e84a0e0d (16-minute waits; 6-hour wording, old work name and log label kept; touch and generic motion count as presses). Re-review (orchestrator, read the diff; fixer gate: 2475 tests, only the baseline failure; release dry run ok; 39 script tests): all 3 addressed.
final review: done
emulator (kevbox_tv, build from ac489b49d, test versions 2001->2002): HOME 11:10:28, "Silent job queued (versionCode 2002, delay 900 s)", 11:25:53 "Silent check [job] versionCode=2002: ELIGIBLE", committed, 11:25:57 INSTALLED, versionCode=2002, installer screens 0. PASS (the restore step was cut off by Kevin; done after the merge).
