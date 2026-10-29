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
