#!/usr/bin/env python3
"""Render the KevBox TV version.json that release.sh uploads.

Kept apart from release.sh so each case can run alone, with no ssh and no Gradle:

    python3 scripts/release_manifest.py check
    python3 scripts/release_manifest.py render --version-code N --version-name V --url U \
        --sha256 S --notes TEXT --profiles-json '[...]' [--no-silent] [--now-ms MS]

Both commands read SILENT_EARLY_USERS (comma-separated Supabase auth user ids) from the
environment. Error messages name the bad entry's position, never its value.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import unicodedata

# The silent block opens to every box this long after publish time.
SILENT_DELAY_MS = 86_400_000

_UUID = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


def parse_early_users(raw: str | None) -> list[str]:
    """Split SILENT_EARLY_USERS into trimmed UUIDs. Blank or missing gives []."""
    if raw is None or not raw.strip():
        return []
    users = []
    for position, piece in enumerate(raw.split(","), start=1):
        entry = piece.strip()
        if not _UUID.match(entry):
            raise ValueError(f"SILENT_EARLY_USERS entry {position} is not a UUID")
        users.append(entry)
    return users


def silent_block(now_ms: int, early_users: list[str]) -> dict:
    return {"after": now_ms + SILENT_DELAY_MS, "earlyUsers": list(early_users)}


def _one_line(text: str) -> str:
    # Any control character (newline, tab, CR, ...) becomes a space, as the old bash escaping did.
    return "".join(" " if unicodedata.category(ch) == "Cc" else ch for ch in text)


def render_manifest(*, version_code: int, version_name: str, url: str, sha256: str, notes: str,
                    profiles: list[dict], silent: dict | None) -> str:
    manifest = {
        "versionCode": version_code,
        "versionName": version_name,
        "url": url,
        "sha256": sha256,
        "notes": _one_line(notes),
        "profiles": profiles,
    }
    if silent is not None:
        manifest["silent"] = silent
    return json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Render the KevBox TV version.json.")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check", help="validate SILENT_EARLY_USERS and exit")
    render = commands.add_parser("render", help="print version.json to stdout")
    render.add_argument("--version-code", type=int, required=True)
    render.add_argument("--version-name", required=True)
    render.add_argument("--url", required=True)
    render.add_argument("--sha256", required=True)
    render.add_argument("--notes", required=True)
    render.add_argument("--profiles-json", required=True)
    render.add_argument("--no-silent", action="store_true")
    render.add_argument("--now-ms", type=int)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    raw = os.environ.get("SILENT_EARLY_USERS")

    if args.command == "check":
        try:
            parse_early_users(raw)
        except ValueError as error:
            print(error, file=sys.stderr)
            return 1
        return 0

    silent = None
    if not args.no_silent:
        try:
            users = parse_early_users(raw)
        except ValueError as error:
            print(error, file=sys.stderr)
            return 1
        now_ms = args.now_ms if args.now_ms is not None else time.time_ns() // 1_000_000
        silent = silent_block(now_ms, users)

    try:
        profiles = json.loads(args.profiles_json)
    except json.JSONDecodeError as error:
        print(f"--profiles-json is not valid JSON: {error}", file=sys.stderr)
        return 1
    if not isinstance(profiles, list):
        print("--profiles-json must be a JSON array", file=sys.stderr)
        return 1

    sys.stdout.write(render_manifest(
        version_code=args.version_code,
        version_name=args.version_name,
        url=args.url,
        sha256=args.sha256,
        notes=args.notes,
        profiles=profiles,
        silent=silent,
    ))
    return 0


if __name__ == "__main__":
    sys.exit(main())
