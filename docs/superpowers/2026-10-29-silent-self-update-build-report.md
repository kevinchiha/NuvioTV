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

## Task outcomes
- Task 1: landed 08c722c84, review clean (gate green: 2309 tests, only the baseline failure). No interface deviation.
- Task 2: landed b9456143a, review clean (gate green: 2328 tests, only the baseline failure). No interface deviation.

## Rolled-up minors
- Task 1, app/src/full/java/com/nuvio/tv/updater/UpdateCheckWorker.kt:20-21: class doc still says trusted means https + configured host; test builds also trust http on the test host and port.
