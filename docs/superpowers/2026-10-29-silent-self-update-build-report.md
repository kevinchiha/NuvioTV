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

## Task outcomes

## Rolled-up minors
