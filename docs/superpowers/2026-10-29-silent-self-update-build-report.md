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

## Task outcomes
- Task 1: landed 08c722c84, review clean (gate green: 2309 tests, only the baseline failure). No interface deviation.
- Task 2: landed b9456143a, review clean (gate green: 2328 tests, only the baseline failure). No interface deviation.
- Task 3: landed e4ef73235, review clean (gate green: 2355 tests, only the baseline failure). No interface deviation.
- Task 4: landed d2ee7ccad, review clean (gate green: 2364 tests, only the baseline failure). No interface deviation (internal helpers only).
- Task 5: landed ae02425a3, review clean (gate green: 2364 tests, only the baseline failure). No interface deviation. Carry to Task 7: call the @RequiresApi(31) silent functions only after the SDK check; abandon the session when commit throws. Carry to Task 6: branch on EXTRA_SILENT before the member path.

## Rolled-up minors
- Task 1, app/src/full/java/com/nuvio/tv/updater/UpdateCheckWorker.kt:20-21: class doc still says trusted means https + configured host; test builds also trust http on the test host and port.
- Task 3, app/src/testFull/java/com/nuvio/tv/updater/SilentInstallDecisionTest.kt:151-161: the checksum-not-computed test breaks only checks 6, 8, 9; checks 1-5 and 7 are not pinned.
- Task 4, app/src/full/java/com/nuvio/tv/updater/SilentInstallGuard.kt:66,73: memberSessionCommitted and lastMemberCommitElapsedMs use AppVisibilityTracker.store (null if register() failed) while quietInput uses storeFor(context); two store sources.
- Task 4, app/src/full/java/com/nuvio/tv/updater/AppVisibilityTracker.kt:88: storeFor builds a new PrefsVisibilityStore per call when register() has not run (same two-sources design).
- Task 5 review note (spec level): AC-10's 10-minute window does not cover a member who leaves Android's confirm screen open longer than that; a silent attempt then abandons the member's session (same update, low impact).
