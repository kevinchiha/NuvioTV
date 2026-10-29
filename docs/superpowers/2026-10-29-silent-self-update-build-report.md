# Build report — silent-self-update

grill: skipped · gap hunt: ran · build: sequential · ocr review: yes (set by the orchestrator on Kevin's 2026-10-29 word: "continue unattended until both done, only stop if blocking")

Spec: docs/superpowers/specs/2026-10-29-silent-self-update-design.md
Plan: docs/superpowers/plans/2026-10-29-silent-self-update.md
Gap report: docs/superpowers/2026-10-29-silent-self-update-gap-report.md

## Tasks
1. Debug build switch for a host update server, and the trust check
2. The `silent` block in version.json
3. Silent-install decisions and per-version state
4. "In use" signals and the member-install guard
5. The silent PackageInstaller session
6. Silent results in InstallResultReceiver
7. One silent attempt, end to end
8. Triggers: the leave-the-app job and the daily check
9. release.sh writes the silent block
10. Emulator test server and run steps for AC-14

## Pre-existing failures
- TraktProgressServiceRefreshTest > change seen by a cancelled refresh is fetched by the next poll (gate on identical app code, kevbox 2ddce968d, 2026-10-29 05:16: 2300 tests, 1 failed, 1 skipped; compile and release dry run green). Leave it alone; it does not count.

## Rulings
- 2026-10-29 07:35, Kevin: match KevBox Live, no app-open prompt on Android 12+ unless the silent install was refused. Folded as AC-15 into the spec and Task 8 (85f653206). Orchestrator addition: the prompt also returns when silent can no longer happen for that version (3 attempts, no silent block, no permission) and 72 h after `after` as a safety net.
- Ruling (Task 8): the AC-15 hold check runs only when `decision.showDialog` is already true, over the brief's "whenever force is false": same UI state, and the "App-open prompt held" log line then only prints when a prompt was really held. Cost if wrong: none to behaviour; a log line fewer.

## Task outcomes
- Task 1: landed 08c722c84, review clean (gate green: 2309 tests, only the baseline failure). No interface deviation.
- Task 2: landed b9456143a, review clean (gate green: 2328 tests, only the baseline failure). No interface deviation.
- Task 3: landed e4ef73235, review clean (gate green: 2355 tests, only the baseline failure). No interface deviation.
- Task 4: landed d2ee7ccad, review clean (gate green: 2364 tests, only the baseline failure). No interface deviation (internal helpers only).
- Task 5: landed ae02425a3, review clean (gate green: 2364 tests, only the baseline failure). No interface deviation. Carry to Task 7: call the @RequiresApi(31) silent functions only after the SDK check; abandon the session when commit throws. Carry to Task 6: branch on EXTRA_SILENT before the member path.
- Task 6: landed 4b5f8451b, review clean (gate green: 2370 tests, only the baseline failure). Added internal helper silentResultLogLine (additive).
- Task 7: landed e3c85ba0d, review clean (gate green: 2383 tests, only the baseline failure). No interface deviation.
- Task 8: landed 81700eb39, review clean (gate green: 2386 tests, only the baseline failure). Deviation: hold check guarded by showDialog (ruling above); VisibilityLog.activityStopped (internal) now returns the remaining count.
- Task 9: landed c7faaf582, review clean (gate green: 2386 tests, only the baseline failure; 30 script tests OK). Additions: render refuses a non-array --profiles-json; notes passed as --notes=.
- Task 10: landed 44432a74f + fix round 1/3 d9fd92b48 (2002 test build debuggable; heading; plan text fixed in 3b8fa05d0, the gap fold had hit the wrong line). Re-review: both addressed, gate green (2386 tests, only the baseline failure; 34 script tests OK). Added helpers prepare_dir/make_server (additive).

## Rolled-up minors
- Task 1, app/src/full/java/com/nuvio/tv/updater/UpdateCheckWorker.kt:20-21: class doc still says trusted means https + configured host; test builds also trust http on the test host and port.
- Task 3, app/src/testFull/java/com/nuvio/tv/updater/SilentInstallDecisionTest.kt:151-161: the checksum-not-computed test breaks only checks 6, 8, 9; checks 1-5 and 7 are not pinned.
- Task 4, app/src/full/java/com/nuvio/tv/updater/SilentInstallGuard.kt:66,73: memberSessionCommitted and lastMemberCommitElapsedMs use AppVisibilityTracker.store (null if register() failed) while quietInput uses storeFor(context); two store sources.
- Task 4, app/src/full/java/com/nuvio/tv/updater/AppVisibilityTracker.kt:88: storeFor builds a new PrefsVisibilityStore per call when register() has not run (same two-sources design).
- Task 5 review note (spec level): AC-10's 10-minute window does not cover a member who leaves Android's confirm screen open longer than that; a silent attempt then abandons the member's session (same update, low impact).
- Task 7, app/src/full/java/com/nuvio/tv/updater/SilentInstall.kt:97-131: a read that throws during evaluation logs only "Silent attempt failed: <class>", no "Silent check" line (AC-13 one line per attempt, rare error path).
- Task 7, SilentInstall.kt:70: internal entry point named `run` shadows Kotlin's `run` inside the object.
- Task 8, app/src/testFull/java/com/nuvio/tv/updater/SilentPromptHoldTest.kt:36-55: one test method with a labelled assertion per condition; AC-15 says one unit test per condition.
- Task 8, app/src/full/java/com/nuvio/tv/updater/UpdateViewModel.kt:112-121: hold reads the silent block from decision.update, which is the cached manifest when the live fetch fails (AC-15 says live manifest); bounded by the 72 h net.
- Task 9, release.sh:153: `printf '%s' "$MANIFEST"` drops the trailing newline the renderer adds (version.json now ends at `}`); `printf '%s\n'` restores it.
- Task 9, release.sh:59-72: release.env now sourced before SSH_ALIAS/WEB_ROOT etc.; checked key names only: release.env sets only NUVIO_RELEASE_* and SENTRY_*, so nothing changes. It has no SILENT_EARLY_USERS line yet (Kevin to add; agents never edit release.env).
- Task 9 review, outside the diff: release.sh header says the ABI default is arm64-v8a (code: armeabi-v7a) and step 5 is optional (script always commits and pushes).

## Whole-branch review
Reviewer `d8955072` (with two helper reviewers), 2026-10-29 09:01, over kevbox...cdac10f8e. Gate: compile ok; 2386 tests, only the baseline failure; release dry run ok; 34 script tests ok; `bash -n release.sh` ok; AC-12 BuildConfig and AC-11 render cases re-checked by hand. Cross-task: no AC undone, no contract drift, no missing landing code, no user data in updater logs, no app-start crash path.

Part 1, findings:
1. important, UpdateViewModel.kt:112-121 (AC-15): hold reads decision.update, the cached manifest when the live fetch fails; offline box with the APK gets neither silent install nor prompt until after+72 h (and a past clock may never reach it). Hold only when the live fetch succeeded, plus a test.
2. important, release.sh:43-51 (AC-11): `--no-silent` only counts as the first argument; `./release.sh 1.2.3 --no-silent` publishes WITH a silent block and the notes become "--no-silent". Stop with an error on a misplaced flag.
3. minor, ApkInstaller.kt:55 + UpdatePromptDialog.kt:115-118 (AC-10): the plain install screen never starts the 10-minute guard; a silent install can run under Android's install screen the member is looking at.
4. minor, SilentInstall.kt:76-90 (AC-8/AC-10): a daily attempt reaching tryLock after the job committed can commit a second time; two attempts counted for one install.
5. minor, SilentInstall.kt:103-105/138-141 (AC-2 check 5): hash runs before the profile fetch, not right before the copy.
6. minor, AppVisibilityTracker.kt:85 (AC-3): unregistered tracker reads 0 started activities and no stored times: fails open.
7. minor, UpdateCheckWorker.kt:64 + SilentInstallDecision.kt:95 (AC-15): a cached APK that fails its checksum is never downloaded again; silent logs the mismatch forever, prompt hidden until after+72 h.
8. minor, UpdaterTestUrlTrustTest.kt:9,42-50 (AC-12): tests pair base https://tv.kevbox.dev with the test URL, a setup no build makes.
9. minor, release.sh:149-153: version.json rendered after the APK upload; a render failure leaves a new APK live with a stale manifest.
10. minor, updater_test_server.py:41-42 (AC-14): run steps accept run 1 without the success line.
11. minor, stale comments: SilentInstallDecision.kt:11-13 ("check order"), ApkInstaller.kt:114-115 ("Safe here"), SilentInstallWorker.kt:11 (offline reason).
12. minor, dead code: AppVisibilityTracker.lastStartElapsedMs/lastStopElapsedMs(context) (87-89), SilentInstallGuard.lastMemberCommitElapsedMs (72).

Part 2, rolled-up minors:
- Task 1 UpdateCheckWorker.kt:20-21 doc: fix (one line, wrong for test builds).
- Task 3 checksum-order test: skip (a missed order costs one hash, installs nothing).
- Task 4 two store sources: skip as written (line 73 is dead code, finding 12; real risk is finding 6).
- Task 4 new store per call: skip (Android caches the prefs file).
- Task 5 confirm screen open past 10 min: skip (same version; fix finding 3 instead).
- Task 7 error-path log: skip (same tag, no user data, rare).
- Task 7 `run` name: skip (no ambiguous call).
- Task 8 one-method SilentPromptHoldTest: fix (AC-15 asks one test per condition).
- Task 8 cached manifest: fix (finding 1).
- Task 9 trailing newline: fix (`printf '%s\n'`).
- Task 9 release.env loaded earlier: skip (only signing and Sentry keys).
- Task 9 header comment (ABI default, step 5): fix (comment only).

## ocr review: triaged with the whole-branch review
docs/superpowers/2026-10-29-silent-self-update-ocr-review.json (7e95d6848): 33 files, 15 comments (1 high, 5 medium, 9 low), no coverage failures. Mapped: high = important, medium = minor (decided here), low dropped. Triaged by reading (no jev call).
- high, AppVisibilityTracker.kt:85 fail-open when unregistered: same as reviewer finding 6, "ocr raised it too"; kept at important, on the fix list.
- medium, UpdaterTestUrlTrustTest.kt:52-53: an https test base URL also trusts plain http on that host and port. FIX, together with reviewer finding 8 (trust check must match the test URL's scheme; tests use the base a real test build has).
- medium, SilentPromptHold.kt:29-30: prompt hidden while no pre-download can ever arrive (metered network). FIX: hold only while the pre-download for this version exists, or the network is unmetered (the daily check will fetch it).
- medium, SilentInstallTest.kt:208-212: 15 s account wait never asserted. FIX (assert the virtual clock).
- medium, release.sh:43-48 `--no-silent` position / a typo taken as the version: same as reviewer finding 2, "ocr raised it too"; important. Also refuse any other argument starting with `--`.
- medium, SilentInstallTest.kt:73-77: FakeEnv never exercises the pre-download wiring (missing file, sha mismatch). FIX (cheap tests).
- 9 low: dropped. One exception folded into the finding-6 fix on purpose: SilentInstallGuard.kt:86-88 treats a failed running-services check as "no external playback" (fail-open, same class as the high).

Reviewer new minors: 3 fix (silent under the member's install screen is visible); 4 fix (cheap in-process guard); 5 skip (Android's signature check stops a swapped APK; worst case one wasted attempt); 6 fix (important via ocr); 7 fix (clear a mismatching pre-download so the daily check fetches it again); 8 fix (with the ocr medium); 9 skip (sha comes from the host after upload, so the render cannot move before it; `check` already ran before the bump); 10 skip (the emulator run uses `dumpsys package` as the proof and notes whether the success line arrived); 11 fix; 12 fix.

Ruling: the list has more than 8 items, which would send it to a split fix pass (parallel worktrees, one Gradle build each). One fixer instead (whole-branch-review.md step 3), because several parallel Android Gradle builds on this PC compete for RAM and the shared Gradle cache, and every item is small. Cost if wrong: a slower fix pass.

## Whole-branch fix pass
1. important [review 1] UpdateViewModel.kt:112-121, AC-15: apply the prompt hold only when the live fetch succeeded; test "live fetch failed, prompt shows".
2. important [review 2][ocr] release.sh:43-51, AC-11: `--no-silent` anywhere but first, or any other argument starting with `--`, stops the script with an error before anything changes.
3. important [review 6][ocr high] AppVisibilityTracker.kt:85, AC-3: when the tracker is not registered, count the app as on screen (silent install skipped). Same fail-closed rule for SilentInstallGuard.kt:86-88: a failed running-services check counts as external playback running.
4. minor [rolled-up] UpdateCheckWorker.kt:20-21: class doc names the test-build trust rule.
5. minor [rolled-up] SilentPromptHoldTest.kt:36-55, AC-15: one @Test per condition.
6. minor [rolled-up] release.sh:153: `printf '%s\n'`.
7. minor [rolled-up] release.sh header: ABI default armeabi-v7a; step 5 always commits and pushes.
8. minor [ocr] SilentPromptHold.kt:29-30, AC-15: hold only while the pre-download for this version exists or the network is unmetered; test both.
9. minor [ocr] SilentInstallTest.kt:208-212: assert the 15 s account wait on the virtual clock.
10. minor [ocr] SilentInstallTest.kt:73-77: tests for a missing pre-download file and a checksum mismatch through the real sequence wiring.
11. minor [review 8][ocr] UpdaterTestUrlTrust / UpdaterTestUrlTrustTest.kt, AC-12: the asset URL's scheme must equal the test URL's scheme; tests use base == test URL as a real test build does.
12. minor [review 3] ApkInstaller.kt:55 + UpdatePromptDialog.kt:115-118, AC-10: posting or opening the plain install screen also records the member-install time (memberSessionCommitted).
13. minor [review 4] SilentInstall.kt:76-90, AC-8: remember the committed versionCode in-process and skip a second attempt for it.
14. minor [review 7] UpdateCheckWorker.kt:64 / SilentInstall: on PREDOWNLOAD_CHECKSUM_MISMATCH delete the cached APK (and its .dm) and clear the pre-download pointer, so the next daily check downloads it again.
15. minor [review 11] stale comments in SilentInstallDecision.kt:11-13, ApkInstaller.kt:114-115, SilentInstallWorker.kt:11.
16. minor [review 12] delete AppVisibilityTracker.lastStartElapsedMs/lastStopElapsedMs(context) and SilentInstallGuard.lastMemberCommitElapsedMs if still unused after item 12.
