#!/usr/bin/env python3
r"""Serve a test version.json and APK to the kevbox_tv emulator for the silent-update check.

    python3 scripts/updater_test_server.py --apk PATH --version-code N --after-hours H \
        [--early-user UUID ...] [--port 8765]

The server binds 127.0.0.1 only. Inside the emulator that address is http://10.0.2.2:<port>.
It renders version.json with release_manifest.render_manifest, so the file has the shape
release.sh publishes, with no profiles entry and a silent block that opens H hours from now.
It never calls adb, ssh or Gradle; the steps below are for a person or the orchestrator.

AC-14 emulator run. Local kevbox_tv emulator only; never a real TV box. A real TV is attached
over network adb, so every command names the serial: E=emulator-5554, and first check
  adb -s $E emu avd name   -> kevbox_tv   (stop if it is anything else)
Start the emulator first (run-emulator skill). PORT=8765, N=2001.

Build both APKs (both use the release key through the debug signing config):
  set -o pipefail
  ./gradlew :app:assembleFullDebug -PupdaterTestBaseUrl=http://10.0.2.2:8765 -PupdaterTestVersionCode=2001 -Pdebuggable=true 2>&1 | tail -5
  cp app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk /tmp/kevbox-2001.apk
  ./gradlew :app:assembleFullDebug -PupdaterTestBaseUrl=http://10.0.2.2:8765 -PupdaterTestVersionCode=2002 2>&1 | tail -5
  cp app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk /tmp/kevbox-2002.apk
  ./gradlew :app:assembleFullDebug -PupdaterTestBaseUrl=http://10.0.2.2:8765 -PupdaterTestVersionCode=2003 -Pdebuggable=true 2>&1 | tail -5
  cp app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk /tmp/kevbox-2003.apk
Never uninstall: the emulator's debug app holds Kevin's sign-in. Each run installs a higher
versionCode over what is there (same release key). The test APKs are debuggable
(-Pdebuggable=true) so that the restore step at the end can go back down to the normal build.

Run 1 (after passed):
  adb -s $E install -r /tmp/kevbox-2001.apk
  adb -s $E shell appops set tv.kevbox.debug REQUEST_INSTALL_PACKAGES allow
  setsid python3 scripts/updater_test_server.py --apk /tmp/kevbox-2002.apk --version-code 2002 --after-hours -1 >/tmp/kevbox-updater-server.log 2>&1 < /dev/null & echo $! > /tmp/kevbox-updater-server.pid
  adb -s $E logcat -c
  adb -s $E shell am start -n tv.kevbox.debug/com.nuvio.tv.MainActivity
  Wait for "Pre-downloaded + verified" in: adb -s $E logcat -d -s UpdateCheckWorker
  adb -s $E shell input keyevent KEYCODE_HOME
  Wait 5 minutes with no input, then:
  adb -s $E shell dumpsys package tv.kevbox.debug | grep versionCode        -> versionCode=2002
  adb -s $E logcat -d -s KevboxSilentUpdate InstallResultReceiver ApkInstaller UpdateCheckWorker
     -> "Silent job queued", "Silent check [job] versionCode=2002: ELIGIBLE",
        "Committed silent session", and "Silent result ... status 0 (INSTALLED)" if the
        result arrived before Android killed the process.
  No package-installer screen: adb -s $E shell dumpsys activity activities | grep -ci packageinstaller -> 0
  Stop the server: kill "$(cat /tmp/kevbox-updater-server.pid)"; wait until port 8765 is free
  (ss -ltn | grep -c :8765 -> 0) before run 2.

Run 2 (not yet, one early user that is not this box; 2002 is installed after run 1):
  adb -s $E shell appops set tv.kevbox.debug REQUEST_INSTALL_PACKAGES allow
  setsid python3 scripts/updater_test_server.py --apk /tmp/kevbox-2003.apk --version-code 2003 --after-hours 24 --early-user 00000000-0000-0000-0000-000000000000 >/tmp/kevbox-updater-server.log 2>&1 < /dev/null & echo $! > /tmp/kevbox-updater-server.pid
  adb -s $E logcat -c
  Open the app as in run 1, wait for the pre-download line, press HOME, wait 5 minutes.
  adb -s $E shell dumpsys package tv.kevbox.debug | grep versionCode        -> versionCode=2002
  adb -s $E logcat -d -s KevboxSilentUpdate  -> "Silent check [job] versionCode=2003: NOT_YET early=false"
  Open the app again: no update prompt appears (AC-15), and logcat -s KevboxSilentUpdate shows
  "App-open prompt held for versionCode 2003". Settings > About > check for updates shows the
  prompt with Install.
  Stop the server as in run 1.

Restore the normal debug build (keeps Kevin's sign-in; works because 2002 is debuggable):
  ./gradlew :app:assembleFullDebug 2>&1 | tail -5
  adb -s $E install -r -d app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk
  adb -s $E shell dumpsys package tv.kevbox.debug | grep versionCode   -> the defaultConfig versionCode

AC-13 privacy check, when an account is signed in on the emulator:
  adb -s $E logcat -d -s KevboxSilentUpdate InstallResultReceiver ApkInstaller UpdateCheckWorker ProfileFetcher | grep -ci -e '<account user id>' -e '<account email>'   -> 0
  (The filter keeps to updater tags on purpose: AuthManager's own debug log prints the raw user id today, and that is outside this phase.)

If UpdateCheckWorker logs "No download this run" on an emulator network that counts as metered,
report it: the pre-download only runs on unmetered networks.
"""
from __future__ import annotations

import argparse
import functools
import hashlib
import http.server
import os
import shutil
import signal
import sys
import tempfile
import time

from release_manifest import parse_early_users, render_manifest

# 127.0.0.1 only: the emulator reaches the host's loopback as 10.0.2.2, and nothing else needs this.
BIND_HOST = "127.0.0.1"
EMULATOR_HOST = "10.0.2.2"
DEFAULT_PORT = 8765
HOUR_MS = 3_600_000


def apk_name(version_code: int) -> str:
    return f"kevbox-test-{version_code}.apk"


def _sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _checked_early_users(early_users: list[str]) -> list[str]:
    # One UUID per --early-user flag. parse_early_users splits on commas, so a flag holding two ids
    # or a blank one comes back with a length other than 1 and is refused. The message names the
    # flag's position, never its value.
    checked = []
    for position, value in enumerate(early_users, start=1):
        try:
            parsed = parse_early_users(value)
        except ValueError:
            parsed = []
        if len(parsed) != 1:
            raise ValueError(f"--early-user entry {position} is not a UUID")
        checked.append(parsed[0])
    return checked


def build_manifest(apk_path: str, version_code: int, after_hours: float, early_users: list[str],
                   port: int, now_ms: int) -> str:
    """Render version.json for the APK at apk_path, served on http://10.0.2.2:<port>."""
    users = _checked_early_users(early_users)
    return render_manifest(
        version_code=version_code,
        version_name=f"test-{version_code}",
        url=f"http://{EMULATOR_HOST}:{port}/{apk_name(version_code)}",
        sha256=_sha256(apk_path),
        notes=f"Emulator test build {version_code}",
        profiles=[],
        # Built here rather than with silent_block, which adds a fixed 24 hours.
        silent={"after": now_ms + round(after_hours * HOUR_MS), "earlyUsers": users},
    )


def prepare_dir(directory: str, apk_path: str, version_code: int, after_hours: float,
                early_users: list[str], port: int, now_ms: int) -> str:
    """Place the APK and version.json in directory. Returns the version.json text."""
    target = os.path.join(directory, apk_name(version_code))
    try:
        os.link(apk_path, target)
    except OSError:
        shutil.copyfile(apk_path, target)
    text = build_manifest(target, version_code, after_hours, early_users, port, now_ms)
    with open(os.path.join(directory, "version.json"), "w", encoding="utf-8") as handle:
        handle.write(text)
    return text


def make_server(directory: str, port: int) -> http.server.ThreadingHTTPServer:
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=directory)
    return http.server.ThreadingHTTPServer((BIND_HOST, port), handler)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Serve a test version.json and APK on 127.0.0.1 for the kevbox_tv emulator.",
        epilog="Run steps for AC-14 and AC-13 are in this script's module docstring.",
    )
    parser.add_argument("--apk", required=True, help="APK to serve")
    parser.add_argument("--version-code", type=int, required=True, help="versionCode of that APK")
    parser.add_argument("--after-hours", type=float, required=True,
                        help="silent.after is now plus this many hours; negative means already passed")
    parser.add_argument("--early-user", action="append", default=[], metavar="UUID",
                        help="Supabase user id for silent.earlyUsers; repeat for more")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"default {DEFAULT_PORT}")
    return parser


def _stop_on_sigterm(_signum, _frame) -> None:
    # `kill <pid>` sends SIGTERM; turn it into a normal exit so the temp dir gets removed.
    raise SystemExit(0)


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    if not os.path.isfile(args.apk):
        parser.error(f"--apk {args.apk} is not a file")
    try:
        _checked_early_users(args.early_user)
    except ValueError as error:
        parser.error(str(error))

    directory = tempfile.mkdtemp(prefix="kevbox-updater-test-")
    try:
        text = prepare_dir(directory, args.apk, args.version_code, args.after_hours, args.early_user,
                           args.port, time.time_ns() // 1_000_000)
        server = make_server(directory, args.port)
        signal.signal(signal.SIGTERM, _stop_on_sigterm)
        print(text, end="", flush=True)
        print(f"Serving {directory} on http://{BIND_HOST}:{args.port}/", flush=True)
        print(f"Emulator manifest URL: http://{EMULATOR_HOST}:{args.port}/version.json", flush=True)
        print("Ctrl-C to stop.", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()
    finally:
        shutil.rmtree(directory, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
