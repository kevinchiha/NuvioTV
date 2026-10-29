# Gap report — silent-self-update

Run `run-968451f4-1364-4db6-a65b-982d0828a5b0` (phase-gaphunt), 4 slices (build, decide, install,
flow), all 4 hunters returned. 18 raw gaps, 15 unique, 9 confirmed, 0 disputed, 6 refuted,
0 unverified, 22 agents. Artifact:
`~/.dsh/workflow-runs/d8d5a12378801ba748e68da3986b226b7b7258ea5f6574cb376042010510aec4/run-968451f4-1364-4db6-a65b-982d0828a5b0/artifacts/gap-hunt-ecb5263ae287d2ff.json`

## Confirmed

### build-1 (important): `adb -e` can reach the real TV
The plan's safety rule and every AC-14 step used `adb -e` for "the emulator". `-e` means "TCP/IP
device", and a real TV box (192.168.68.71:5555) is attached over network adb: with the emulator
up the steps fail ("more than one emulator"), with it down they would install on the TV.
Refuter: every claim checked against `adb help` and `adb devices`.
Folded: Global constraints and every Task 10 step now name `emulator-5554` after checking
`emu avd name` prints `kevbox_tv`; spec AC-14 says the emulator is named by serial.

### build-2 (important): no way back after the test builds
After AC-14 the emulator keeps a non-debuggable versionCode 2002, and the image is a user build,
so the next normal debug build (1061) cannot install without an uninstall, which wipes Kevin's
sign-in. Refuter: confirmed on the live emulator (`ro.debuggable=0`).
Folded: test APKs are built with `-Pdebuggable=true`; Task 10 ends with a restore step
(`install -r -d` of the normal build); spec AC-14 adds that step.

### decide-2 (important): per-version state transitions untested
`recordSilentAttempt` / `markSilentRefused` reset rules had no test, and the DataStore delegate is
cached per JVM (already claimed by `KevboxCorruptFullStoreResetTest`). A regression would carry a
refused flag or attempt count into the next release, silently. Refuter: confirmed.
Folded: Task 3 adds the pure `nextSilentState(current, versionCode, event)` both writers use, and
its "per-version state" test intent now covers the four transitions (merged into the existing
intent, so the task stays at 5).

### decide-1 (minor): the APK hash ran before the cheap checks
Corrected in Task 3 (checksum is the last eligibility check) and Task 7 behaviour 4 (quiet runs
before eligibility, so a member back on screen never pays a 100 MB hash).

### flow-2 (minor): a cancelled attempt leaves a staged session
Noted on Task 7: abandon the open session in a `finally` under `NonCancellable`, then rethrow.

### build-3 (minor): `kill %1` does nothing across shells
Corrected in Task 10: the server starts with `setsid ... & echo $! > pidfile` and stops by pid,
with a port-free check before run 2.

### build-4 (minor): the moved `source "$KEYENV"` would hit `set -u`
Corrected in Task 9 behaviour 5: the pair moves to right after `REPO`, `GRADLE`, `KEYENV` are
assigned, still before the bump.

### install-6 (minor): member commit guard forgotten by a fresh process
Noted on Task 4: also store the member commit time in `kevbox_app_visibility` and fall back to it.

### flow-4 (minor): `Result.success(update)` inside the worker means `ListenableWorker.Result`
Corrected in Task 8 behaviour 5: `kotlin.Result.success(update)`.

## Refuted
- install-1 (important claimed): no test for the external-playback service match. Refuter: the whole
  claim is a missing test; the plan already names the right class and package rule.
- install-2 (important claimed): receiver routing untestable. Refuter: missing test only; behaviour
  is specified, and the routing is a few lines.
- decide-3 (minor): mixed pre/post-reboot stored times. Refuter: spec wording allows the plan's
  per-value reading; worst case a box waits for the next daily check.
- install-4 (minor): startedActivities not volatile. Refuter: the stored start time backs it up;
  the stale-zero window cannot produce a commit while on screen.
- install-5 (minor): REFUSED with no versionCode. Refuter: "writes nothing" means the DataStore
  write; abandoning is not skipped, and the case is unreachable by design.
- flow-3 (minor): daily-check and job attempts in one cold process. Refuter: the lock handles it;
  only the expected log line's trigger label can differ. (Worth knowing during the AC-14 run: the
  ELIGIBLE line may say `[daily-check]` instead of `[job]`.)

## Minor gaps
- decide-1: corrected in Task 3 behaviour 1 and constraint, Task 7 behaviour 4.
- flow-2: noted on Task 7.
- build-3: corrected in Task 10 run steps.
- build-4: corrected in Task 9 behaviour 5.
- install-6: noted on Task 4.
- flow-4: corrected in Task 8 behaviour 5.

## Graph
Nodes and edges are in the artifact above (`nodes`, `edges`).
